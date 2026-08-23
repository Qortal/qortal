package org.qortal.network.reticulum;

import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import org.junit.jupiter.api.Test;
import org.qortal.network.reticulum.RNSCommon.PeerAspect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the four prune passes.
 * <p>
 * Lives in the production package for the same reason as {@link RNSPeerRegistryTest}:
 * {@link RNSPeerPruner} is package-private. Peers are Mockito stubs — a real
 * {@code ReticulumPeer} sends a LINKREQUEST through Transport from its constructor.
 * <p>
 * The remove callbacks both record the peer <i>and</i> apply the removal to the registry, so the
 * passes see the same progressively-shrinking state they do in production.
 */
class RNSPeerPrunerTest {

    private final RNSPeerRegistry registry = new RNSPeerRegistry();
    private final List<ReticulumPeer> removedLinked = new ArrayList<>();
    private final List<ReticulumPeer> removedIncoming = new ArrayList<>();
    private final Map<String, PeerAspect> recordedFailures = new LinkedHashMap<>();

    private final RNSPeerPruner pruner = new RNSPeerPruner(registry,
            peer -> { removedLinked.add(peer); registry.removeLinked(peer); },
            peer -> { removedIncoming.add(peer); registry.removeIncoming(peer); },
            recordedFailures::put);

    // ── isUnreachable ────────────────────────────────────────────────────────

    @Test
    void unreachableWhenMarkedForDeletion() {
        ReticulumPeer peer = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE);
        when(peer.getDeleteMe()).thenReturn(true);

        assertTrue(RNSPeerPruner.isUnreachable(peer));
    }

    @Test
    void unreachableWithoutALinkOrWithAClosedOne() {
        assertTrue(RNSPeerPruner.isUnreachable(linkedPeer(new byte[] { 1 }, null)));
        assertTrue(RNSPeerPruner.isUnreachable(linkedPeer(new byte[] { 2 }, LinkStatus.CLOSED)));
    }

    @Test
    void reachableWhileInboundIsRecentOrUnknown() {
        ReticulumPeer neverHeard = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE);
        ReticulumPeer recent = linkedPeer(new byte[] { 2 }, LinkStatus.ACTIVE);
        when(recent.getPeerLink().getLastInbound()).thenReturn(Instant.now().minusSeconds(60));

        assertFalse(RNSPeerPruner.isUnreachable(neverHeard), "null lastInbound must not count as silence");
        assertFalse(RNSPeerPruner.isUnreachable(recent));
    }

    @Test
    void unreachableAfterTwiceTheKeepaliveWithoutInbound() {
        ReticulumPeer silent = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE);
        when(silent.getPeerLink().getLastInbound()).thenReturn(Instant.now().minusSeconds(2 * 360 + 30));

        assertTrue(RNSPeerPruner.isUnreachable(silent));
    }

    // ── pass 1: initiator peers ──────────────────────────────────────────────

    @Test
    void removesTimedOutInitiatorPeer() {
        ReticulumPeer peer = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE);
        when(peer.getPeerTimedOut()).thenReturn(true);
        registry.addLinked(peer);

        pruner.prune();

        assertEquals(List.of(peer), removedLinked);
        verify(peer).makePeerUnavailable();
    }

    @Test
    void keepsHealthyActiveInitiatorPeer() {
        ReticulumPeer peer = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE);
        registry.addLinked(peer);

        pruner.prune();

        assertTrue(removedLinked.isEmpty());
        verify(peer, never()).makePeerUnavailable();
    }

    @Test
    void removesZombieActivePeerAndClosesThatExactLink() {
        ReticulumPeer peer = linkedPeer(new byte[] { 1 }, LinkStatus.ACTIVE);
        Link link = peer.getPeerLink();
        when(peer.getDeleteMe()).thenReturn(true);   // dead buffer behind a still-ACTIVE link
        registry.addLinked(peer);

        pruner.prune();

        assertEquals(List.of(peer), removedLinked);
        // Closing the orphan is what lets its watchdog thread exit (16,642 leaked in test-17).
        verify(link).setStatus(LinkStatus.CLOSED);
    }

    @Test
    void removesClosedInitiatorPeerAndClearsItsDeleteFlag() {
        ReticulumPeer peer = linkedPeer(new byte[] { 1 }, LinkStatus.CLOSED);
        registry.addLinked(peer);

        pruner.prune();

        assertEquals(List.of(peer), removedLinked);
        verify(peer).setDeleteMe(false);
    }

    @Test
    void keepsYoungPendingLink() {
        ReticulumPeer peer = pendingPeer(new byte[] { 1 }, PeerAspect.BASE, 30);
        registry.addLinked(peer);

        pruner.prune();

        assertTrue(removedLinked.isEmpty(), "a PENDING link under 60s must be given time to establish");
        assertTrue(recordedFailures.isEmpty());
    }

    @Test
    void removesStuckPendingLinkAndRecordsTheFailurePerAspect() {
        ReticulumPeer base = pendingPeer(new byte[] { 0x0a }, PeerAspect.BASE, 90);
        ReticulumPeer data = pendingPeer(new byte[] { 0x0b }, PeerAspect.DATA, 90);
        registry.addLinked(base);
        registry.addLinked(data);

        pruner.prune();

        assertEquals(List.of(base, data), removedLinked);
        assertEquals(Map.of("0a", PeerAspect.BASE, "0b", PeerAspect.DATA), recordedFailures);
        // Tearing the link down here would trigger the expirePath() cull cascade.
        verify(base.getPeerLink(), never()).teardown();
    }

    @Test
    void skipsInitiatorPeerWithNoLinkAtAll() {
        ReticulumPeer peer = linkedPeer(new byte[] { 1 }, null);
        when(peer.getPeerTimedOut()).thenReturn(true);   // would be removed if it had a link
        registry.addLinked(peer);

        pruner.prune();

        assertTrue(removedLinked.isEmpty());
    }

    // ── passes 2-4: incoming peers ───────────────────────────────────────────

    @Test
    void removesIncomingPeersWhoseLinkIsNoLongerActive() {
        ReticulumPeer closed = incomingPeer(identity((byte) 1), LinkStatus.CLOSED, PeerAspect.BASE);
        ReticulumPeer linkless = incomingPeer(identity((byte) 2), null, PeerAspect.BASE);
        ReticulumPeer active = incomingPeer(identity((byte) 3), LinkStatus.ACTIVE, PeerAspect.BASE);
        List.of(closed, linkless, active).forEach(registry::addIncoming);

        pruner.prune();

        assertEquals(List.of(closed, linkless), removedIncoming);
        assertEquals(List.of(active), registry.incoming());
    }

    @Test
    void keepsTheFreshestOfDuplicateIncomingPeersFromOneRemote() {
        Identity remote = identity((byte) 7);
        ReticulumPeer stale = incomingPeer(remote, LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer fresh = incomingPeer(remote, LinkStatus.ACTIVE, PeerAspect.BASE);
        when(stale.getLastAccessTimestamp()).thenReturn(Instant.now().minusSeconds(120));
        // A remote's BASE and DATA peers share an identity but must not be deduped against each other.
        ReticulumPeer sameRemoteData = incomingPeer(remote, LinkStatus.ACTIVE, PeerAspect.DATA);
        List.of(stale, fresh, sameRemoteData).forEach(p -> forceAddWithoutEviction(registry, p));

        pruner.prune();

        assertEquals(List.of(stale), removedIncoming);
        assertEquals(List.of(fresh, sameRemoteData), registry.incoming());
    }

    @Test
    void removesSilentButStillActiveIncomingPeer() {
        ReticulumPeer silent = incomingPeer(identity((byte) 1), LinkStatus.ACTIVE, PeerAspect.BASE);
        ReticulumPeer live = incomingPeer(identity((byte) 2), LinkStatus.ACTIVE, PeerAspect.BASE);
        when(silent.getPeerLink().getLastInbound()).thenReturn(Instant.now().minusSeconds(2 * 360 + 30));
        registry.addIncoming(silent);
        registry.addIncoming(live);

        pruner.prune();

        assertEquals(List.of(silent), removedIncoming);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** See {@code RNSPeerRegistryTest}: duplicates only arise when peers identify after being added. */
    private static void forceAddWithoutEviction(RNSPeerRegistry registry, ReticulumPeer peer) {
        Identity identity = peer.getServerIdentity();
        when(peer.getServerIdentity()).thenReturn(null);
        registry.addIncoming(peer);
        when(peer.getServerIdentity()).thenReturn(identity);
    }

    private static ReticulumPeer linkedPeer(byte[] destinationHash, LinkStatus status) {
        return linkedPeer(destinationHash, status, PeerAspect.BASE, Instant.now());
    }

    private static ReticulumPeer pendingPeer(byte[] destinationHash, PeerAspect aspect, long ageSeconds) {
        return linkedPeer(destinationHash, LinkStatus.PENDING, aspect,
                Instant.now().minusSeconds(ageSeconds));
    }

    private static ReticulumPeer linkedPeer(byte[] destinationHash, LinkStatus status,
                                            PeerAspect aspect, Instant created) {
        Link link = link(status);   // built before stubbing: Mockito forbids nested when(...)
        ReticulumPeer peer = mock(ReticulumPeer.class);
        when(peer.getDestinationHash()).thenReturn(destinationHash);
        when(peer.getPeerLink()).thenReturn(link);
        when(peer.getPeerAspect()).thenReturn(aspect);
        when(peer.getDeleteMe()).thenReturn(false);
        when(peer.getPeerTimedOut()).thenReturn(false);
        when(peer.getCreationTimestamp()).thenReturn(created);
        return peer;
    }

    private static ReticulumPeer incomingPeer(Identity identity, LinkStatus status, PeerAspect aspect) {
        Link link = link(status);   // built before stubbing: Mockito forbids nested when(...)
        ReticulumPeer peer = mock(ReticulumPeer.class);
        when(peer.getDestinationHash()).thenReturn(new byte[] { 0x77 });   // ours, not the remote's
        when(peer.getPeerLink()).thenReturn(link);
        when(peer.getPeerAspect()).thenReturn(aspect);
        when(peer.getServerIdentity()).thenReturn(identity);
        when(peer.getDeleteMe()).thenReturn(false);
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
