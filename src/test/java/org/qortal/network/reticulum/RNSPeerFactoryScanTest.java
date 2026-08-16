package org.qortal.network.reticulum;

import org.junit.jupiter.api.Test;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.reflections.Reflections;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the classpath scan that {@code PeerFactory} and {@code PeerAddressFactory} depend on.
 * <p>
 * Both build their constructor registries in a static block from
 * {@code new Reflections("org.qortal.network")}, picking up {@code @PeerCtor} /
 * {@code @PeerAddressCtor} on every {@link Peer} / {@link PeerAddress} implementation found. That
 * is a package-<i>prefix</i> scan, so it still reaches the reticulum classes after they moved to
 * {@code org.qortal.network.reticulum} — but nothing about it is checked by the compiler. Moving,
 * renaming or repackaging an implementation would silently empty its registry entry and only fail
 * at runtime, with an IllegalArgumentException from a factory key that no longer resolves.
 */
class RNSPeerFactoryScanTest {

    @Test
    void reticulumPeerIsDiscoverableByTheFactoryScan() {
        Set<String> peers = simpleNames(new Reflections("org.qortal.network").getSubTypesOf(Peer.class));

        assertTrue(peers.contains("ReticulumPeer"),
                "PeerFactory's scan no longer sees ReticulumPeer; found " + peers);
    }

    @Test
    void reticulumPeerAddressIsDiscoverableByTheFactoryScan() {
        Set<String> addresses =
                simpleNames(new Reflections("org.qortal.network").getSubTypesOf(PeerAddress.class));

        assertTrue(addresses.contains("ReticulumPeerAddress"),
                "PeerAddressFactory's scan no longer sees ReticulumPeerAddress; found " + addresses);
    }

    private static Set<String> simpleNames(Set<? extends Class<?>> classes) {
        return classes.stream().map(Class::getSimpleName).collect(Collectors.toSet());
    }
}
