package org.qortal.network.reticulum;

import io.reticulum.link.Link;
import lombok.extern.slf4j.Slf4j;
import org.qortal.network.reticulum.RNSCommon.PeerAspect;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.reticulum.link.LinkStatus.ACTIVE;
import static io.reticulum.link.LinkStatus.CLOSED;
import static io.reticulum.link.LinkStatus.PENDING;
import static java.util.Objects.nonNull;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * The periodic peer-list garbage collector, run from {@code Controller} every 90 seconds via
 * {@link RNS#prunePeers()}.
 * <p>
 * Four independent passes, each taking its own snapshot from the registry at the point it runs:
 * <ol>
 *   <li>initiator peers: timed-out, unreachable-but-ACTIVE, CLOSED/deleteMe, stuck-PENDING;</li>
 *   <li>incoming peers whose link is no longer ACTIVE;</li>
 *   <li>duplicate ACTIVE incoming peers from the same remote identity+aspect;</li>
 *   <li>ACTIVE incoming peers that have gone silent.</li>
 * </ol>
 * Removal itself stays in {@code RNS} — the two remove callbacks carry the side effects
 * ({@code shutdownChannel}, {@code closeIfActive}, {@code makePeerUnavailable}) that must not run
 * with a registry lock held.
 * <p>
 * This runs on the Controller's scheduler thread, so nothing here may block: no
 * {@code teardown()}, no {@code announce()}, no {@code requestPath()} — see {@link #prune()}.
 */
@Slf4j
final class RNSPeerPruner {

    /**
     * How long a Link may go with no inbound activity before we treat it as unreachable. Liveness
     * now comes from the Reticulum Link's native keepalive via its (library-fixed) lastInbound,
     * which is refreshed on real traffic AND on keepalive round-trips (every ~360s, the library
     * KEEPALIVE, when idle). Allow ~2x that so an idle-but-alive link riding on keepalives alone is
     * not culled. Replaces the old app-level ping + 165s lastAccessTimestamp staleness.
     */
    private static final long LINK_INBOUND_TIMEOUT_MS = 2 * 360 * 1000L; // ms (~2x library KEEPALIVE)

    /** Grace period for a PENDING link to establish before it is treated as stuck. */
    private static final long PENDING_LINK_GRACE_SECONDS = 60L;

    private final RNSPeerRegistry registry;
    private final Consumer<ReticulumPeer> removeLinkedPeer;
    private final Consumer<ReticulumPeer> removeIncomingPeer;
    private final BiConsumer<String, PeerAspect> recordPendingFailure;

    RNSPeerPruner(RNSPeerRegistry registry,
                  Consumer<ReticulumPeer> removeLinkedPeer,
                  Consumer<ReticulumPeer> removeIncomingPeer,
                  BiConsumer<String, PeerAspect> recordPendingFailure) {
        this.registry = registry;
        this.removeLinkedPeer = removeLinkedPeer;
        this.removeIncomingPeer = removeIncomingPeer;
        this.recordPendingFailure = recordPendingFailure;
    }

    void prune() {
        logCounts("before");
        pruneInitiatorPeers();
        pruneNonActiveIncoming();
        dedupActiveIncomingByIdentity();
        pruneSilentActiveIncoming();
        logCounts("after");
        // announce() and requestPath() are intentionally NOT called here — both involve
        // Reticulum library calls that can block if the library holds a lock. The Controller
        // thread must not block (node hangs, stop.sh hangs). runBaseLoop() handles both on
        // its own thread every 30 seconds.
    }

    /** Timed-out, unreachable-ACTIVE, CLOSED/deleteMe and stuck-PENDING initiator peers. */
    private void pruneInitiatorPeers() {
        for (ReticulumPeer p : registry.linked()) {
            Link pLink = p.getPeerLink();
            if (pLink == null) {
                continue;
            }
            if (p.getPeerTimedOut()) {
                // options: keep in case peer reconnects or remove => we'll remove it
                p.makePeerUnavailable();
                removeLinkedPeer.accept(p);
                continue;
            }
            if (pLink.getStatus() == ACTIVE) {
                // Even ACTIVE links can be zombie: buffer dead (deleteMe=true from
                // peerBufferReady read error) or silent (no data received for >165s).
                // Without this check, the ACTIVE continue below bypasses deleteMe entirely.
                if (isUnreachable(p)) {
                    log.info("Removing unreachable ACTIVE peer ({}): {}",
                            p.getDeleteMe() ? "deleteMe" : "data timeout",
                            encodeHexString(p.getDestinationHash()));
                    p.makePeerUnavailable();
                    // Close this exact Link — not p.getPeerLink(), which initPeerLink() may
                    // already have re-pointed at a fresh Link. An orphaned ACTIVE Link never
                    // dies on its own: its watchdog sends keepalives, the remote answers,
                    // lastInbound advances, so the staleTime check never fires and the status
                    // never reaches CLOSED. Test-17 wadin leaked 16,642 watchdog threads this
                    // way over 2 days (~340/h, RSS 34.8G) before crashing. Safe to close here
                    // because the link is ACTIVE, not PENDING — no expirePath() cull cascade.
                    pLink.setStatus(CLOSED);
                    removeLinkedPeer.accept(p);
                }
                continue;
            }
            if ((pLink.getStatus() == CLOSED) || (p.getDeleteMe())) {
                p.makePeerUnavailable();
                p.setDeleteMe(false);
                removeLinkedPeer.accept(p);
                continue;
            }
            if (pLink.getStatus() == PENDING) {
                // Give PENDING links 60s to establish before removing them.
                // Removing too early races with QAnnounceHandler (which creates a
                // new link and then finds peerTimedOut=true from the old teardown).
                // Keeping them forever blocks QAnnounceHandler (peerExists=true,
                // status != CLOSED, so the announce is silently ignored).
                long pendingSeconds = Duration.between(
                        p.getCreationTimestamp(), Instant.now()).getSeconds();
                if (pendingSeconds > PENDING_LINK_GRACE_SECONDS) {
                    log.info("Removing PENDING link stuck for {}s: {}", pendingSeconds, p);
                    p.makePeerUnavailable();
                    p.setIsPeerAvailable(false);
                    // Record failure so the reconnect loop backs off to requestPath() for this
                    // peer for PENDING_FAILURE_BACKOFF_MS, avoiding the cull cascade.
                    recordPendingFailure.accept(encodeHexString(p.getDestinationHash()), p.getPeerAspect());
                    removeLinkedPeer.accept(p);
                    // Do NOT call pLink.teardown() here.
                    // teardown() sets status=CLOSED → jobs() finds CLOSED link in pendingLinks
                    // → calls expirePath() → tablesLastCulled=EPOCH → next jobs() does a full
                    // routing table cull (60-120s when announce-flooded). Multiple teardowns
                    // chain into cascading culls that hold the Transport lock for 22+ minutes,
                    // blocking all outbound() / requestPath() calls during that window.
                    // We remove the peer from our own tracking only; the library's zombie PENDING
                    // links have a 774000s (8.9 day) timeout (hopsTo=PATHFINDER_M → no path),
                    // which is harmless compared to the cull cascade.
                }
            }
        }
    }

    /** Incoming peers whose link is missing or no longer ACTIVE. */
    private void pruneNonActiveIncoming() {
        for (ReticulumPeer p : registry.nonActiveIncoming()) {
            // Don't call pLink.teardown() — synchronized(link) can block the Controller
            // scheduler if the Reticulum library is processing on this link. The library
            // handles non-active link cleanup via its own keepalive/watchdog mechanism.
            removeIncomingPeer.accept(p);
        }
    }

    /**
     * Dedup ACTIVE incoming peers by remote identity. linkEstablished() resolves the identity
     * (null at construction time because the handshake wasn't complete yet), so by prune time
     * (~60s later) it is available. Keep the newest peer per identity; remove the rest.
     */
    private void dedupActiveIncomingByIdentity() {
        for (Map.Entry<String, List<ReticulumPeer>> entry : registry.activeIncomingDuplicateGroups().entrySet()) {
            List<ReticulumPeer> dupes = entry.getValue();
            // Keep the one with the most recent data; remove the rest
            dupes.sort((a, b) -> b.getLastAccessTimestamp().compareTo(a.getLastAccessTimestamp()));
            for (int i = 1; i < dupes.size(); i++) {
                log.info("prunePeers: removing duplicate ACTIVE incoming peer from {}", entry.getKey());
                removeIncomingPeer.accept(dupes.get(i));
            }
        }
    }

    /**
     * Prune ACTIVE incoming peers that have gone silent: the initiator moved to a new
     * link so pings stopped flowing, but the old library-level link is still ACTIVE.
     * 165s = 3 missed pings.
     */
    private void pruneSilentActiveIncoming() {
        for (ReticulumPeer p : registry.activeIncoming()) {
            if (isUnreachable(p)) {
                log.info("Removing stale ACTIVE incoming peer (data timeout): {}", encodeHexString(p.getDestinationHash()));
                removeIncomingPeer.accept(p);
            }
        }
    }

    /**
     * Whether a peer's link is dead, marked for removal, or has heard nothing inbound for longer
     * than {@link #LINK_INBOUND_TIMEOUT_MS}.
     */
    static boolean isUnreachable(ReticulumPeer peer) {
        if (peer.getDeleteMe()) {
            return true;
        }
        var link = peer.getPeerLink();
        if (link == null || link.getStatus() == CLOSED) {
            // No link, or the library/Channel already tore it down (a wedged Channel that hits
            // 'retry count exceeded' closes the Link) — definitively unreachable.
            return true;
        }
        // Liveness from the Link's native keepalive via the (now library-fixed) lastInbound,
        // replacing the app-level Channel ping. If a wedged Channel had killed the link we'd have
        // caught it via CLOSED above, so link-level liveness is a sufficient proxy here.
        var lastInbound = link.getLastInbound();
        if (nonNull(lastInbound) && lastInbound.isBefore(Instant.now().minusMillis(LINK_INBOUND_TIMEOUT_MS))) {
            log.debug("RNS - link is unreachable (no inbound for > {}ms)", LINK_INBOUND_TIMEOUT_MS);
            return true;
        }
        return false;
    }

    /**
     * The before/after census. {@code activeIncoming()} is the exact complement of
     * {@code nonActiveIncoming()}, so one traversal serves where the original subtracted two.
     */
    private void logCounts(String when) {
        log.info("number of links (linkedPeers (active) / incomingPeers (active) {} pruning: {} ({}), {} ({})",
                when,
                registry.linked().size(), registry.activeLinked().size(),
                registry.incoming().size(), registry.activeIncoming().size());
    }
}
