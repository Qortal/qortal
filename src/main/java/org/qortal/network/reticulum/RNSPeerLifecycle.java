package org.qortal.network.reticulum;

import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static io.reticulum.link.LinkStatus.ACTIVE;
import static io.reticulum.link.LinkStatus.CLOSED;
import static java.util.Objects.nonNull;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * Everything that happens to a peer between "exists" and "gone": accepting an inbound link,
 * creating an outbound one, and the ordered teardown on the way out.
 * <p>
 * The split against {@link RNSPeerRegistry} is deliberate and load-bearing. The registry owns the
 * collections and does nothing else: it mutates a list and republishes a snapshot under its own
 * lock, and hands back any peer it displaced. This class owns the <i>side effects</i> —
 * {@code shutdownChannel()}, {@code closeIfActive()}, {@code makePeerUnavailable()} — and runs every
 * one of them with no registry lock held, which is rule 4 of the refactor: a Reticulum or Network
 * call made under a registry monitor is the ABBA deadlock this design exists to avoid.
 */
@Slf4j
final class RNSPeerLifecycle {

    private final RNSPeerRegistry registry;
    /** Teardowns and dedup run here, never on a Reticulum callback thread. */
    private final ExecutorService workerPool;
    private final BooleanSupplier shuttingDown;
    /** Kicks an aspect's announce/reconnect cycle after a peer is lost. */
    private final Consumer<RNSCommon.PeerAspect> announceKick;
    /** (destinationHash hex, aspect) → that aspect's ReconnectPolicy. */
    private final BiConsumer<String, RNSCommon.PeerAspect> recordFailure;
    private final AnnouncedVersionCache versions;
    private final byte[] messageMagic;

    RNSPeerLifecycle(RNSPeerRegistry registry, ExecutorService workerPool,
                     BooleanSupplier shuttingDown, Consumer<RNSCommon.PeerAspect> announceKick,
                     BiConsumer<String, RNSCommon.PeerAspect> recordFailure,
                     AnnouncedVersionCache versions, byte[] messageMagic) {
        this.registry = registry;
        this.workerPool = workerPool;
        this.shuttingDown = shuttingDown;
        this.announceKick = announceKick;
        this.recordFailure = recordFailure;
        this.versions = versions;
        this.messageMagic = messageMagic;
    }

    // ── inbound: a remote initiated a link to one of our destinations ────────

    /**
     * Link-established callback for both destinations, registered in {@code RNS.start()}.
     * <p>
     * One method for both aspects: the two used to be near-identical copies, which is the shape
     * that hid the §14.1 finding-1 bug for as long as it did.
     */
    void clientConnected(Link link, RNSCommon.PeerAspect aspect) {
        ReticulumPeer newPeer = new ReticulumPeer(link);
        newPeer.setPeerLinkHash(link.getHash());
        newPeer.setPeerAspect(aspect);
        newPeer.setMessageMagic(messageMagic);
        // Capture the initiator's identity once it identifies over the link (see ReticulumPeer's
        // initiator-side link.identify()). Until this fires, an inbound peer has no remote identity
        // and identity-based dedup cannot collapse duplicate inbound links from the same remote.
        link.setRemoteIdentifiedCallback((l, id) -> onIncomingPeerIdentified(newPeer, id));
        // createPeerBuffer() rather than getOrInitPeerBuffer() — avoids synchronized(link)
        // contention on the broadcast path (see ReticulumPeer.createPeerBuffer javadoc).
        newPeer.createPeerBuffer();
        addIncomingPeer(newPeer);
        log.info("{} client connected — link {} (hash {})", aspect,
                encodeHexString(link.getLinkId()), encodeHexString(link.getHash()));
    }

    /**
     * Called from the inbound link's remoteIdentified callback (registered in
     * {@link #clientConnected}) once the initiator has identified itself via link.identify().
     * Records the resolved remote identity on the peer — the constructor could not, because the
     * handshake hadn't completed and getRemoteIdentity() was null then — so identity-based dedup
     * finally has a key to work with. Then collapses any older duplicate inbound links from the same
     * remote+aspect, keeping this newly-identified one.
     */
    void onIncomingPeerIdentified(ReticulumPeer peer, Identity identity) {
        if (identity == null) return;
        peer.setServerIdentity(identity);
        // Now that we know the remote identity, attach its announced version (if we've heard its
        // announce) so /peers/reticulum shows the real version for inbound peers too.
        String version = versions.get(identity);
        if (version != null) {
            peer.setPeersVersionString(version);
        }
        log.info("inbound {} peer identified as {} (link {}), version {}",
                peer.getPeerAspect(), encodeHexString(identity.getHash()),
                peer.getPeerLink() != null ? encodeHexString(peer.getPeerLink().getLinkId()) : "null",
                version);
        dedupIncomingPeerByIdentity(peer);
    }

    // ── outbound: we initiate a link to a remote ─────────────────────────────

    // Create and add an initiator ReticulumPeer directly from a cached identity (no announce
    // needed). Called from a runner's reconnect pass when recall() finds the identity in the local
    // known-destinations DB.
    //
    // The ReticulumPeer constructor calls initPeerLink() (which sends the LINK OPEN via outbound()).
    // Do NOT call getOrInitPeerLink() here: the peer's link is already PENDING, so getOrInitPeerLink()
    // would call initPeerLink() a second time — creating a zombie PENDING link in the Reticulum library.
    // The zombie establishes on the remote end (adding a spurious incoming peer there), and when it
    // times out it fires expirePath() → tablesLastCulled=EPOCH → cascading 60-120s cull cycles.
    void createLinkedPeerFromIdentity(byte[] destinationHash, Identity identity,
                                      RNSCommon.PeerAspect aspect) {
        ReticulumPeer newPeer = new ReticulumPeer(destinationHash, aspect);
        newPeer.setServerIdentity(identity);
        newPeer.setIsInitiator(true);
        newPeer.setMessageMagic(messageMagic);
        addLinkedPeer(newPeer);
        log.info("{}: proactively connecting to known peer {} via cached identity",
                aspect, encodeHexString(destinationHash));
        // Link already created in constructor — do NOT call getOrInitPeerLink() here.
        // Detect immediate send failure: ReticulumPeer() → initPeerLink() → new Link() → packet.send()
        // → outbound() is synchronous; if the LINKREQUEST couldn't be sent (no route, backbone down),
        // the link is already CLOSED by the time we get here. Record a failure so the reconnect loop
        // backs off to requestPath() rather than creating a new CLOSED link on every 15s cycle.
        Link lnk = newPeer.getPeerLink();
        if (lnk != null && lnk.getStatus() == CLOSED) {
            log.warn("{}: LINKREQUEST to {} failed immediately — switching to requestPath backoff",
                    aspect, encodeHexString(destinationHash));
            recordFailure.accept(encodeHexString(destinationHash), aspect);
        }
    }

    // ── registry mutation, with the side effects the registry must not run ───

    void addLinkedPeer(ReticulumPeer peer) {
        // The registry dedups atomically with the add: receivedAnnounce() and the reconnect task
        // can both call this concurrently when a peer drops and reconnects.
        if (!registry.addLinked(peer)) {
            log.debug("addLinkedPeer: skipping duplicate for {}", encodeHexString(peer.getDestinationHash()));
            // The loser was built via new ReticulumPeer(dhash), whose constructor already called
            // initPeerLink() and sent the LINKREQUEST — so its Link is live (PENDING/establishing)
            // even though we're discarding the peer. Closing it here prevents a leaked local
            // watchdog thread AND stops the half-formed link from establishing on the remote as a
            // spurious incoming peer (which would itself become a duplicate the remote must prune).
            // The loser always holds its own fresh Link (distinct from the retained peer's), so
            // closing it cannot disturb the live connection. Non-blocking volatile write only.
            peer.closePeerLinkNonBlocking();
            return;
        }
        // Hash is persisted only once the peer's buffer is confirmed ACTIVE (see confirmPeerHash(),
        // called from ReticulumPeer.createPeerBuffer()). This prevents transient/failed connections
        // from accumulating in the persisted peer list.
    }

    void removeLinkedPeer(ReticulumPeer peer) {
        peer.shutdownChannel(); // clears channel + nulls peerBuffer; no close() to avoid deadlock
        // NOTE: deliberately does NOT close peerLink. Callers that remove an ACTIVE link must close
        // it themselves, passing the exact Link they decided on (see prunePeers) — closing here
        // would both re-read a peerLink that initPeerLink() may have re-pointed at a fresh Link,
        // and close PENDING links, which triggers the expirePath() cull cascade documented below.
        registry.removeLinked(peer);
        // Remove from Network's connected/handshaked lists on EVERY removal path. This was
        // previously commented out (and only some callers, e.g. prunePeers, called
        // makePeerUnavailable() explicitly first) — so removal paths that didn't, like
        // RNS.removePeer(), leaked the dead peer into Network.handshakedPeers/connectedPeers
        // forever. Over a churny run that bloated the ping-scan lists and grew Network-Scheduler
        // CPU without bound (test-20/21 wadin). Idempotent, so a double call with an explicit
        // makePeerUnavailable() before the call is harmless. No RNS lock is held here, so acquiring
        // Network's peer-list locks cannot deadlock.
        peer.makePeerUnavailable();
    }

    void addIncomingPeer(ReticulumPeer peer) {
        // The registry evicts any existing incoming peer from the same node with the same aspect
        // (identity + aspect, so a remote's CORE and DATA peers don't evict each other) and hands
        // the superseded ones back for teardown out here, with no registry lock held.
        for (ReticulumPeer superseded : registry.addIncoming(peer)) {
            superseded.shutdownChannel();
            // The superseded peer always holds a different Link object than the replacement (both
            // baseClientConnected and dataClientConnected build a fresh ReticulumPeer per incoming
            // Link), so closing it cannot disturb the new peer. Without this its watchdog thread
            // leaks (see removeLinkedPeer).
            closeIfActive(superseded);
        }
    }

    void removeIncomingPeer(ReticulumPeer peer) {
        peer.shutdownChannel(); // clears channel + nulls peerBuffer; no close() to avoid deadlock
        // A stale-but-still-ACTIVE incoming link is kept alive indefinitely by the remote
        // initiator's keepalives, so its watchdog thread never exits — close it. Incoming peers are
        // built fresh per Link (baseClientConnected/dataClientConnected) and never re-initiated, so
        // reading peerLink here cannot pick up a replacement. Only ACTIVE: see removeLinkedPeer.
        closeIfActive(peer);
        registry.removeIncoming(peer);
        // Incoming BASE peers are also registered in Network's connected/handshaked lists via
        // makePeerAvailable(); remove them here so they don't leak (see removeLinkedPeer).
        peer.makePeerUnavailable();
    }

    /**
     * Closes a discarded peer's Link if — and only if — it is still ACTIVE, so its watchdog thread
     * can exit. Plain volatile write, no lock, so it cannot deadlock with the Reticulum receive
     * thread (the ABBA inversion avoided elsewhere in this class).
     * <p>
     * ACTIVE-only is deliberate. A PENDING link closed here would be picked up by Transport's
     * jobs() loop as a CLOSED entry in pendingLinks, which calls expirePath() and resets
     * tablesLastCulled — cascading routing-table culls that hold the Transport lock for minutes.
     * PENDING, HANDSHAKE and STALE watchdogs all reach CLOSED on their own via the establishment
     * or stale timeout, so they never leak; only ACTIVE orphans do.
     */
    private void closeIfActive(ReticulumPeer peer) {
        var link = peer.getPeerLink();
        if (nonNull(link) && link.getStatus() == ACTIVE) {
            link.setStatus(CLOSED);
        }
    }

    // ── removal on the fast path ─────────────────────────────────────────────

    /**
     * Immediately remove a peer from the peer list and kick reconnect, rather than waiting
     * for the next prunePeers() cycle (~60s). Called from ReticulumPeer whenever a link or buffer
     * turns out to be dead. Runs on the rnsWorkerPool to avoid blocking the Reticulum callback
     * thread.
     * <p>
     * At most one teardown is queued per peer: every thread that tries to use a dying peer
     * discovers the death independently, so the claim is what stops N concurrent senders queueing
     * N identical teardowns and writing N identical warnings.
     *
     * @return true if this call claimed the teardown — callers use it to log once
     */
    boolean markPeerForImmediateRemoval(ReticulumPeer peer) {
        if (shuttingDown.getAsBoolean()) return false;
        if (!peer.claimRemoval()) return false;
        try {
            workerPool.submit(() -> {
                peer.makePeerUnavailable();
                if (Boolean.TRUE.equals(peer.getIsInitiator())) {
                    removeLinkedPeer(peer);
                } else {
                    removeIncomingPeer(peer);
                }
                announceKick.accept(peer.getPeerAspect()); // reconnect this aspect within ~5s
            });
        } catch (RejectedExecutionException e) {
            // Pool shut down — prunePeers() will clean up on next cycle
        }
        return true;
    }

    /**
     * Proactively evict duplicate incoming peers as soon as the remote identity is known.
     * <p>
     * {@link #addIncomingPeer} runs at link-construction time (from {@link #clientConnected}) when
     * {@code getRemoteIdentity()} is still null because the handshake hasn't completed — so its
     * identity-based dedup is skipped and multiple incoming links from the same remote+aspect
     * accumulate until the next {@code prunePeers()} cycle (~60s). This is called from
     * {@link ReticulumPeer#linkEstablished} once {@code serverIdentity} resolves, so redundant links
     * are dropped within seconds instead. The {@code keep} peer (the just-established link) is
     * retained; every other incoming peer with the same identity+aspect is removed. Runs on
     * rnsWorkerPool to avoid mutating the peer list from the Reticulum I/O thread (same discipline
     * as {@link #markPeerForImmediateRemoval}). The prunePeers() pass remains as a backstop.
     */
    void dedupIncomingPeerByIdentity(ReticulumPeer keep) {
        if (shuttingDown.getAsBoolean()) return;
        if (keep.getServerIdentity() == null) return;
        try {
            workerPool.submit(() -> {
                for (ReticulumPeer p : registry.duplicateIncomingByIdentity(keep)) {
                    log.info("dedupIncomingPeerByIdentity: removing duplicate {} incoming peer from {}",
                            p.getPeerAspect(), RNSPeerRegistry.incomingIdentityKey(p));
                    removeIncomingPeer(p);
                }
            });
        } catch (RejectedExecutionException e) {
            // Pool shut down — prunePeers() will clean up on next cycle
        }
    }
}
