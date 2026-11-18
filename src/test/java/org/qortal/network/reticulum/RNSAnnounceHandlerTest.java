package org.qortal.network.reticulum;

import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import org.junit.jupiter.api.Test;
import org.qortal.network.reticulum.RNSCommon.PeerAspect;

import java.util.Arrays;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RNSAnnounceHandler#updatePeerVersions}: an announce refreshes the version
 * of peers that already exist with that remote. Peers are Mockito stubs, as in
 * {@link RNSPeerRegistryTest}.
 */
class RNSAnnounceHandlerTest {

    private final RNSPeerRegistry registry = new RNSPeerRegistry();
    private final RNSAnnounceHandler handler = new RNSAnnounceHandler("qortaltest.core", PeerAspect.BASE,
            5, registry, null, new AnnouncedVersionCache(), new byte[4], peer -> { });

    @Test
    void updatesTheLinkedPeerWithTheAnnouncedDestination() {
        byte[] destination = filled((byte) 0x11);
        ReticulumPeer match = linkedPeer(destination);
        ReticulumPeer other = linkedPeer(filled((byte) 0x22));
        registry.addLinked(match);
        registry.addLinked(other);

        handler.updatePeerVersions(destination, identity((byte) 0xAA), "6.1.9-abc");

        verify(match).setPeersVersionString("6.1.9-abc");
        verify(other, never()).setPeersVersionString("6.1.9-abc");
    }

    @Test
    void updatesIncomingPeersOfTheSameIdentityAndAspectOnly() {
        Identity remote = identity((byte) 0xAA);
        ReticulumPeer match = incomingPeer(identity((byte) 0xAA), PeerAspect.BASE);
        ReticulumPeer otherAspect = incomingPeer(identity((byte) 0xAA), PeerAspect.DATA);
        ReticulumPeer otherNode = incomingPeer(identity((byte) 0xBB), PeerAspect.BASE);
        ReticulumPeer unidentified = incomingPeer(null, PeerAspect.BASE);
        registry.addIncoming(match);
        registry.addIncoming(otherAspect);
        registry.addIncoming(otherNode);
        registry.addIncoming(unidentified);

        handler.updatePeerVersions(filled((byte) 0x11), remote, "6.1.9-abc");

        verify(match).setPeersVersionString("6.1.9-abc");
        verify(otherAspect, never()).setPeersVersionString("6.1.9-abc");
        verify(otherNode, never()).setPeersVersionString("6.1.9-abc");
        verify(unidentified, never()).setPeersVersionString("6.1.9-abc");
    }

    private static ReticulumPeer linkedPeer(byte[] destinationHash) {
        Link link = link();   // built before stubbing: Mockito forbids nested when(...)
        ReticulumPeer peer = mock(ReticulumPeer.class);
        when(peer.getDestinationHash()).thenReturn(destinationHash);
        when(peer.getPeerLink()).thenReturn(link);
        when(peer.getPeerAspect()).thenReturn(PeerAspect.BASE);
        return peer;
    }

    private static ReticulumPeer incomingPeer(Identity identity, PeerAspect aspect) {
        Link link = link();
        ReticulumPeer peer = mock(ReticulumPeer.class);
        when(peer.getPeerLink()).thenReturn(link);
        when(peer.getPeerAspect()).thenReturn(aspect);
        when(peer.getServerIdentity()).thenReturn(identity);
        return peer;
    }

    private static Link link() {
        Link link = mock(Link.class);
        when(link.getStatus()).thenReturn(LinkStatus.ACTIVE);
        return link;
    }

    private static byte[] filled(byte fill) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, fill);
        return bytes;
    }

    /** An Identity whose hash is all {@code fill}. */
    private static Identity identity(byte fill) {
        byte[] hash = filled(fill);
        Identity identity = mock(Identity.class);
        when(identity.getHash()).thenReturn(hash);
        return identity;
    }
}
