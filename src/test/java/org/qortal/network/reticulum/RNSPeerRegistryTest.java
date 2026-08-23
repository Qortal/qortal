package org.qortal.network.reticulum;

import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import org.junit.jupiter.api.Test;
import org.qortal.network.reticulum.RNSCommon.PeerAspect;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the peer registry.
 * <p>
 * Lives in the production package (a separate source root) because {@link RNSPeerRegistry} is
 * package-private on purpose — the registry being unreachable from outside
 * {@code org.qortal.network.reticulum} is what enforces "only RNS mutates the peer lists".
 * <p>
 * Peers are Mockito stubs: a real {@code ReticulumPeer} sends a LINKREQUEST through Transport from
 * its constructor, so it cannot be built in a unit test.
 */
class RNSPeerRegistryTest {

    // ── linked peers ─────────────────────────────────────────────────────────

    @Test
    void addLinkedRejectsDuplicateDestinationHash() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        byte[] hash = { 1, 2, 3 };

        assertTrue(registry.addLinked(linkedPeer(hash, LinkStatus.ACTIVE, PeerAspect.BASE)));
        assertFalse(registry.addLinked(linkedPeer(hash, LinkStatus.ACTIVE, PeerAspect.BASE)),
                "second peer with the same destination hash must lose");
        assertEquals(1, registry.linked().size());
    }

    @Test
    void addLinkedAcceptsDistinctDestinationHashes() {
        RNSPeerRegistry registry = new RNSPeerRegistry();

        assertTrue(registry.addLinked(linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE, PeerAspect.BASE)));
        assertTrue(registry.addLinked(linkedPeer(new byte[] { 2 }, LinkStatus.ACTIVE, PeerAspect.BASE)));
        assertEquals(2, registry.linked().size());
    }

    @Test
    void removeLinkedUpdatesTheSnapshot() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        ReticulumPeer peer = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE, PeerAspect.BASE);
        registry.addLinked(peer);

        registry.removeLinked(peer);

        assertTrue(registry.linked().isEmpty());
    }

    @Test
    void snapshotsAreImmutable() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        registry.addLinked(linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE, PeerAspect.BASE));
        registry.addIncoming(incomingPeer(null, LinkStatus.ACTIVE, PeerAspect.BASE));

        assertThrows(UnsupportedOperationException.class, () -> registry.linked().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.incoming().clear());
    }

    @Test
    void activeLinkedExcludesInactiveAndDeleteMePeers() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        ReticulumPeer active = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer pending = linkedPeer(new byte[] { 2 }, LinkStatus.PENDING, PeerAspect.BASE);
        ReticulumPeer closed = linkedPeer(new byte[] { 3 }, LinkStatus.CLOSED, PeerAspect.BASE);
        ReticulumPeer noLink = linkedPeer(new byte[] { 4 }, null, PeerAspect.BASE);
        ReticulumPeer deleteMe = linkedPeer(new byte[] { 5 }, LinkStatus.ACTIVE, PeerAspect.BASE);
        when(deleteMe.getDeleteMe()).thenReturn(true);
        List.of(active, pending, closed, noLink, deleteMe).forEach(registry::addLinked);

        assertEquals(List.of(active), registry.activeLinked());
    }

    @Test
    void activeLinkedFiltersByAspect() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        ReticulumPeer base = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer data = linkedPeer(new byte[] { 2 }, LinkStatus.ACTIVE, PeerAspect.DATA);
        registry.addLinked(base);
        registry.addLinked(data);

        assertEquals(List.of(base), registry.activeLinked(PeerAspect.BASE));
        assertEquals(List.of(data), registry.activeLinked(PeerAspect.DATA));
    }

    @Test
    void isLinkedTrackedMatchesPendingPeersToo() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        registry.addLinked(linkedPeer(new byte[] { 7, 7 }, LinkStatus.PENDING, PeerAspect.BASE));

        assertTrue(registry.isLinkedTracked(new byte[] { 7, 7 }));
        assertFalse(registry.isLinkedTracked(new byte[] { 8, 8 }));
    }

    // ── incoming peers ───────────────────────────────────────────────────────

    @Test
    void addIncomingEvictsSameIdentityAndAspect() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        Identity identity = identity((byte) 0xAA);
        ReticulumPeer stale = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer fresh = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.BASE);
        registry.addIncoming(stale);

        List<ReticulumPeer> superseded = registry.addIncoming(fresh);

        assertEquals(List.of(stale), superseded, "the caller must get the stale peer back to tear down");
        assertEquals(List.of(fresh), registry.incoming());
    }

    @Test
    void addIncomingKeepsBothAspectsFromTheSameNode() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        Identity identity = identity((byte) 0xAA);
        ReticulumPeer base = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer data = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.DATA);
        registry.addIncoming(base);

        List<ReticulumPeer> superseded = registry.addIncoming(data);

        assertTrue(superseded.isEmpty(),
                "a node's CORE and DATA peers share an identity and must not evict each other");
        assertEquals(2, registry.incoming().size());
    }

    @Test
    void addIncomingKeepsUnidentifiedPeers() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        registry.addIncoming(incomingPeer(null, LinkStatus.ACTIVE, PeerAspect.BASE));

        List<ReticulumPeer> superseded = registry.addIncoming(incomingPeer(null, LinkStatus.ACTIVE, PeerAspect.BASE));

        assertTrue(superseded.isEmpty(), "identity is unknown until the handshake completes");
        assertEquals(2, registry.incoming().size());
    }

    @Test
    void nonActiveIncomingCoversMissingAndInactiveLinks() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        ReticulumPeer active = incomingPeer(null, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer closed = incomingPeer(null, LinkStatus.CLOSED, PeerAspect.BASE);
        ReticulumPeer noLink = incomingPeer(null, null, PeerAspect.BASE);
        List.of(active, closed, noLink).forEach(registry::addIncoming);

        assertEquals(List.of(closed, noLink), registry.nonActiveIncoming());
        assertEquals(List.of(active), registry.activeIncoming());
    }

    @Test
    void activeIncomingHashesKeysByIdentityAndAspect() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        Identity identity = identity((byte) 0xAA);
        ReticulumPeer base = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer data = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.DATA);
        ReticulumPeer inactive = incomingPeer(identity((byte) 0xBB), LinkStatus.CLOSED, PeerAspect.BASE);
        ReticulumPeer unidentified = incomingPeer(null, LinkStatus.ACTIVE, PeerAspect.BASE);
        List.of(base, data, inactive, unidentified).forEach(registry::addIncoming);

        Set<String> baseHashes = registry.activeIncomingHashes(PeerAspect.BASE);

        assertEquals(Set.of(RNSPeerRegistry.incomingIdentityKey(base)), baseHashes);
        assertFalse(baseHashes.contains(RNSPeerRegistry.incomingIdentityKey(data)),
                "the DATA aspect hashes to a different destination");
    }

    @Test
    void duplicateIncomingByIdentityExcludesTheKeeperAndOtherAspects() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        Identity identity = identity((byte) 0xAA);
        ReticulumPeer keep = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer duplicate = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer otherAspect = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.DATA);
        ReticulumPeer otherNode = incomingPeer(identity((byte) 0xBB), LinkStatus.ACTIVE, PeerAspect.BASE);
        // addIncoming would evict the duplicate on insert, so seed the list in an order that keeps both
        List.of(keep, otherAspect, otherNode).forEach(registry::addIncoming);
        forceAddWithoutEviction(registry, duplicate);

        assertEquals(List.of(duplicate), registry.duplicateIncomingByIdentity(keep));
    }

    @Test
    void duplicateIncomingByIdentityIsEmptyForUnidentifiedPeers() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        ReticulumPeer keep = incomingPeer(null, LinkStatus.ACTIVE, PeerAspect.BASE);
        registry.addIncoming(keep);

        assertTrue(registry.duplicateIncomingByIdentity(keep).isEmpty());
    }

    @Test
    void activeIncomingDuplicateGroupsOnlyReportsRealDuplicates() {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        Identity identity = identity((byte) 0xAA);
        ReticulumPeer first = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer second = incomingPeer(identity, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer alone = incomingPeer(identity((byte) 0xBB), LinkStatus.ACTIVE, PeerAspect.BASE);
        registry.addIncoming(first);
        registry.addIncoming(alone);
        forceAddWithoutEviction(registry, second);

        var groups = registry.activeIncomingDuplicateGroups();

        assertEquals(1, groups.size(), "only the shared identity forms a group");
        assertEquals(2, groups.get(RNSPeerRegistry.incomingIdentityKey(first)).size());
    }

    // ── the invariant the registry exists to guarantee ───────────────────────

    /**
     * A peer that was added and not removed must be visible in the snapshot, even when the add
     * races a concurrent remove.
     * <p>
     * This is the race the pre-registry code had: remove() read the backing list, an adder
     * published its snapshot, then remove() published its own stale copy — leaving a live peer
     * invisible to every consumer (broadcast, both loops, prunePeers) until the next mutation.
     * Each round starts a batch of removes and a batch of adds at the same instant, so the adds
     * land inside the window a non-atomic remove would have open, then asserts every added peer
     * survived. Probabilistic by nature — many rounds, not a proof — but it fails reliably against
     * a remove that rebuilds the snapshot outside the lock.
     */
    @Test
    void concurrentAddsAreNotLostByRacingRemoves() throws Exception {
        RNSPeerRegistry registry = new RNSPeerRegistry();
        int rounds = 40;
        int batchSize = 16;

        // One thread per task: every task parks on the same latch, so a smaller pool would queue
        // the adds behind the removes and they would never overlap.
        ExecutorService pool = Executors.newFixedThreadPool(2 * batchSize);
        try {
            List<ReticulumPeer> previous = newBatch(0, batchSize);
            previous.forEach(registry::addLinked);

            for (int round = 1; round <= rounds; round++) {
                List<ReticulumPeer> incoming = newBatch(round * batchSize, batchSize);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2 * batchSize);

                for (int i = 0; i < batchSize; i++) {
                    ReticulumPeer stale = previous.get(i);
                    ReticulumPeer fresh = incoming.get(i);
                    pool.submit(() -> {
                        start.await();
                        registry.removeLinked(stale);
                        done.countDown();
                        return null;
                    });
                    pool.submit(() -> {
                        start.await();
                        registry.addLinked(fresh);
                        done.countDown();
                        return null;
                    });
                }

                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "round " + round + " did not finish");

                List<ReticulumPeer> snapshot = registry.linked();
                assertTrue(snapshot.containsAll(incoming),
                        "round " + round + ": a concurrently added peer is missing from the snapshot");
                assertEquals(batchSize, snapshot.size(),
                        "round " + round + ": snapshot lost or duplicated peers");
                previous = incoming;
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<ReticulumPeer> newBatch(int firstId, int count) {
        return IntStream.range(firstId, firstId + count)
                .mapToObj(i -> linkedPeer(new byte[] { (byte) (i >> 8), (byte) i },
                        LinkStatus.ACTIVE, PeerAspect.BASE))
                .collect(Collectors.toList());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Adds an incoming peer while bypassing identity eviction, to build the duplicate state that
     * only arises when peers identify <i>after</i> being added (the handshake completes late).
     */
    private static void forceAddWithoutEviction(RNSPeerRegistry registry, ReticulumPeer peer) {
        Identity identity = peer.getServerIdentity();
        when(peer.getServerIdentity()).thenReturn(null);
        registry.addIncoming(peer);
        when(peer.getServerIdentity()).thenReturn(identity);
    }

    private static ReticulumPeer linkedPeer(byte[] destinationHash, LinkStatus status, PeerAspect aspect) {
        Link link = link(status);   // built before stubbing: Mockito forbids nested when(...)
        ReticulumPeer peer = mock(ReticulumPeer.class);
        when(peer.getDestinationHash()).thenReturn(destinationHash);
        when(peer.getPeerLink()).thenReturn(link);
        when(peer.getPeerAspect()).thenReturn(aspect);
        when(peer.getDeleteMe()).thenReturn(false);
        return peer;
    }

    private static ReticulumPeer incomingPeer(Identity identity, LinkStatus status, PeerAspect aspect) {
        Link link = link(status);   // built before stubbing: Mockito forbids nested when(...)
        ReticulumPeer peer = mock(ReticulumPeer.class);
        when(peer.getPeerLink()).thenReturn(link);
        when(peer.getPeerAspect()).thenReturn(aspect);
        when(peer.getServerIdentity()).thenReturn(identity);
        when(peer.getLastAccessTimestamp()).thenReturn(Instant.now());
        return peer;
    }

    private static Link link(LinkStatus status) {
        if (status == null) return null;
        Link link = mock(Link.class);
        when(link.getStatus()).thenReturn(status);
        return link;
    }

    /** An Identity whose hash is all {@code fill} — hashFromNameAndIdentity only reads getHash(). */
    private static Identity identity(byte fill) {
        byte[] hash = new byte[16];
        java.util.Arrays.fill(hash, fill);
        Identity identity = mock(Identity.class);
        when(identity.getHash()).thenReturn(hash);
        return identity;
    }
}
