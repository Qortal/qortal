package org.qortal.network.reticulum;

import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import lombok.extern.slf4j.Slf4j;
import org.qortal.network.reticulum.RNSCommon.PeerAspect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.reticulum.link.LinkStatus.ACTIVE;
import static io.reticulum.utils.DestinationUtils.hashFromNameAndIdentity;
import static java.util.Objects.nonNull;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * The two peer collections and the only code that mutates them.
 * <p>
 * Two lists are kept for each subset of peers:
 * <ul>
 *   <li>a backing list, modified when peers are added/removed;</li>
 *   <li>an immutable snapshot, rebuilt to mirror it, served to consumers.</li>
 * </ul>
 * <b>linked</b> peers are "initiators" (holding an initiator Reticulum Link), actively doing work.
 * <b>incoming</b> peers are "non-initiators", the passive end of bidirectional Reticulum Buffers.
 * <p>
 * <b>Locking rules.</b> Every mutation rebuilds the snapshot inside the same lock, so a reader can
 * never observe a snapshot that is missing a peer already present in the backing list. Every read
 * returns the snapshot or a private copy — the backing lists never escape, which is what makes
 * unsynchronised iteration impossible by construction.
 * <p>
 * <b>Side effects stay out.</b> This class only manipulates lists. Closing links, shutting down
 * channels and de-registering from {@code Network} are the caller's job, performed after the lock
 * is released — {@code makePeerUnavailable()} acquires Network's peer-list locks, and taking those
 * while holding a registry lock would invert lock order. Methods that evict peers therefore
 * <i>return</i> them rather than tearing them down.
 */
@Slf4j
final class RNSPeerRegistry {

    private final Object linkedLock = new Object();
    private final List<ReticulumPeer> linked = new ArrayList<>();          // guarded by linkedLock
    private volatile List<ReticulumPeer> linkedSnapshot = List.of();

    private final Object incomingLock = new Object();
    private final List<ReticulumPeer> incoming = new ArrayList<>();        // guarded by incomingLock
    private volatile List<ReticulumPeer> incomingSnapshot = List.of();

    // ── linked (initiator) peers ─────────────────────────────────────────────

    /**
     * Track an initiator peer, unless one with the same destination hash is already tracked.
     * <p>
     * The dedup must be atomic with the add: receivedAnnounce() and the reconnect task can both
     * call this concurrently when a peer drops and reconnects — both see an empty slot and race to
     * fill it.
     *
     * @return true when the peer was added; false when it lost the race and the caller should
     *         discard it (see the loser's link handling in {@code RNS.addLinkedPeer})
     */
    boolean addLinked(ReticulumPeer peer) {
        synchronized (linkedLock) {
            boolean duplicate = linked.stream()
                    .anyMatch(p -> Arrays.equals(p.getDestinationHash(), peer.getDestinationHash()));
            if (duplicate) {
                return false;
            }
            linked.add(peer);
            linkedSnapshot = List.copyOf(linked);
            return true;
        }
    }

    void removeLinked(ReticulumPeer peer) {
        synchronized (linkedLock) {
            if (linked.remove(peer)) {
                linkedSnapshot = List.copyOf(linked);
            }
        }
    }

    /** All initiator peers, tracked regardless of link state. */
    List<ReticulumPeer> linked() {
        return linkedSnapshot;
    }

    /**
     * Initiator peers with an ACTIVE link that are not marked for removal.
     * <p>
     * deleteMe peers are excluded because their buffer is dead even while the library-level link is
     * still ACTIVE; counting them would hide the real active count from the loops and delay
     * reconnect until the next prunePeers() cycle.
     */
    List<ReticulumPeer> activeLinked() {
        List<ReticulumPeer> active = new ArrayList<>();
        for (ReticulumPeer p : linkedSnapshot) {
            Link link = p.getPeerLink();
            if (nonNull(link) && link.getStatus() == ACTIVE && !p.getDeleteMe()) {
                active.add(p);
            }
        }
        return active;
    }

    /** {@link #activeLinked()} restricted to one aspect. */
    List<ReticulumPeer> activeLinked(PeerAspect aspect) {
        List<ReticulumPeer> active = new ArrayList<>();
        for (ReticulumPeer p : activeLinked()) {
            if (p.getPeerAspect() == aspect) {
                active.add(p);
            }
        }
        return active;
    }

    /** Whether any initiator peer already holds this destination hash (PENDING or ACTIVE). */
    boolean isLinkedTracked(byte[] destinationHash) {
        for (ReticulumPeer p : linkedSnapshot) {
            if (Arrays.equals(p.getDestinationHash(), destinationHash)) {
                return true;
            }
        }
        return false;
    }

    // ── incoming (non-initiator) peers ───────────────────────────────────────

    /**
     * Track an incoming peer, evicting any existing incoming peer from the same node with the same
     * aspect.
     * <p>
     * Aspect must be part of the key: hashing both aspects under CORE_ASPECT would match the
     * CORE/DATA peer pair from a single remote node and evict the wrong one.
     *
     * @return the superseded peers, already untracked — the caller tears them down outside the lock
     */
    List<ReticulumPeer> addIncoming(ReticulumPeer peer) {
        List<ReticulumPeer> superseded = new ArrayList<>();
        Identity newId = peer.getServerIdentity();
        String newAspect = aspectName(peer);

        synchronized (incomingLock) {
            if (newId != null) {
                byte[] newHash = hashFromNameAndIdentity(newAspect, newId);
                Iterator<ReticulumPeer> it = incoming.iterator();
                while (it.hasNext()) {
                    ReticulumPeer existing = it.next();
                    Identity existingId = existing.getServerIdentity();
                    String existingAspect = aspectName(existing);
                    if (existingId != null && existingAspect.equals(newAspect)
                            && Arrays.equals(hashFromNameAndIdentity(existingAspect, existingId), newHash)) {
                        log.info("addIncomingPeer: replacing stale {} incoming peer from {}",
                                newAspect, encodeHexString(newHash));
                        it.remove();
                        superseded.add(existing);
                    }
                }
            }
            incoming.add(peer);
            incomingSnapshot = List.copyOf(incoming);
        }
        return superseded;
    }

    void removeIncoming(ReticulumPeer peer) {
        synchronized (incomingLock) {
            if (incoming.remove(peer)) {
                incomingSnapshot = List.copyOf(incoming);
            }
        }
    }

    /** All incoming peers, tracked regardless of link state. */
    List<ReticulumPeer> incoming() {
        return incomingSnapshot;
    }

    /** Incoming peers whose link is ACTIVE, both aspects. */
    List<ReticulumPeer> activeIncoming() {
        return activeIncoming(null);
    }

    /** Incoming peers whose link is ACTIVE, restricted to one aspect when {@code aspect} is given. */
    List<ReticulumPeer> activeIncoming(PeerAspect aspect) {
        List<ReticulumPeer> active = new ArrayList<>();
        for (ReticulumPeer p : incomingSnapshot) {
            Link link = p.getPeerLink();
            if ((aspect == null || p.getPeerAspect() == aspect)
                    && nonNull(link) && link.getStatus() == ACTIVE) {
                active.add(p);
            }
        }
        return active;
    }

    /** Incoming peers whose link is missing or not ACTIVE — prune candidates. */
    List<ReticulumPeer> nonActiveIncoming() {
        List<ReticulumPeer> result = new ArrayList<>();
        for (ReticulumPeer p : incomingSnapshot) {
            Link link = p.getPeerLink();
            if (link == null || link.getStatus() != ACTIVE) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Destination hashes (hex) of ACTIVE incoming peers of one aspect.
     * <p>
     * Computed once per reconnect cycle so the per-target "already connected as incoming" check is
     * an O(1) set lookup. Doing it per target instead means a stream plus a
     * hashFromNameAndIdentity (a SHA-256) over every incoming peer for every target — O(targets ×
     * incoming) crypto hashing each cycle.
     */
    Set<String> activeIncomingHashes(PeerAspect aspect) {
        String aspectName = aspectName(aspect);
        Set<String> hashes = new HashSet<>();
        for (ReticulumPeer p : incomingSnapshot) {
            Link link = p.getPeerLink();
            Identity identity = p.getServerIdentity();
            if (p.getPeerAspect() == aspect && nonNull(link) && link.getStatus() == ACTIVE && identity != null) {
                hashes.add(encodeHexString(hashFromNameAndIdentity(aspectName, identity)));
            }
        }
        return hashes;
    }

    /**
     * Incoming peers other than {@code keep} that share its remote identity and aspect.
     *
     * @return the duplicates, still tracked — the caller removes them (removeIncoming mutates the
     *         list, so eviction cannot happen while iterating it here)
     */
    List<ReticulumPeer> duplicateIncomingByIdentity(ReticulumPeer keep) {
        Identity keepId = keep.getServerIdentity();
        if (keepId == null) return List.of();

        String keepAspect = aspectName(keep);
        byte[] keepHash = hashFromNameAndIdentity(keepAspect, keepId);

        List<ReticulumPeer> duplicates = new ArrayList<>();
        for (ReticulumPeer p : incomingSnapshot) {
            if (p == keep) continue;
            Identity pid = p.getServerIdentity();
            if (pid == null) continue;
            String pAspect = aspectName(p);
            if (pAspect.equals(keepAspect)
                    && Arrays.equals(hashFromNameAndIdentity(pAspect, pid), keepHash)) {
                duplicates.add(p);
            }
        }
        return duplicates;
    }

    /**
     * ACTIVE incoming peers grouped by remote identity+aspect, only where more than one shares a
     * key. Keyed by {@link #incomingIdentityKey}, which is what identifies the remote node — an
     * incoming peer's own destinationHash is <i>our</i> destination, not the remote's.
     */
    Map<String, List<ReticulumPeer>> activeIncomingDuplicateGroups() {
        Map<String, List<ReticulumPeer>> byIdentity = new HashMap<>();
        for (ReticulumPeer p : incomingSnapshot) {
            Link link = p.getPeerLink();
            Identity remoteId = p.getServerIdentity();
            if (nonNull(link) && link.getStatus() == ACTIVE && remoteId != null) {
                byIdentity.computeIfAbsent(incomingIdentityKey(p), k -> new ArrayList<>()).add(p);
            }
        }
        byIdentity.values().removeIf(group -> group.size() <= 1);
        return byIdentity;
    }

    /**
     * The remote node+aspect this peer belongs to, as hex — {@code hashFromNameAndIdentity(aspect,
     * remoteIdentity)}. Use this to identify an incoming peer in logs: its {@code destinationHash}
     * is our own destination (the link's destination is us), so it is the same for every inbound
     * peer and tells you nothing about who connected.
     *
     * @return null when the remote identity has not resolved yet
     */
    static String incomingIdentityKey(ReticulumPeer peer) {
        Identity identity = peer.getServerIdentity();
        if (identity == null) return null;
        return encodeHexString(hashFromNameAndIdentity(aspectName(peer), identity));
    }

    private static String aspectName(ReticulumPeer peer) {
        return aspectName(peer.getPeerAspect());
    }

    private static String aspectName(PeerAspect aspect) {
        return aspect == PeerAspect.DATA ? RNS.QDN_ASPECT : RNS.CORE_ASPECT;
    }
}
