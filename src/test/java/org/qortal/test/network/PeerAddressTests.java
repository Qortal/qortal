package org.qortal.test.network;

import org.junit.jupiter.api.Test;
import org.qortal.data.network.PeerData;
import org.qortal.network.IPPeerAddress;
import org.qortal.network.PeerAddress;
import org.qortal.network.reticulum.ReticulumPeerAddress;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards value-equality of {@link PeerAddress} implementations.
 * <p>
 * These once carried an {@code equals(IPPeerAddress)} overload rather than an
 * {@link Object#equals(Object)} override. Callers all hold the interface type, so the compiler bound
 * to {@code Object.equals} and compared by reference: peer de-duplication silently stopped matching
 * and allKnownPeers grew without bound. Every assertion below fails if that regresses — note in
 * particular that they compare through {@code PeerAddress}-typed references on purpose.
 */
class PeerAddressTests {

	@Test
	void ipAddressEqualityThroughInterfaceType() {
		PeerAddress a = new IPPeerAddress("1.2.3.4:12392");
		PeerAddress b = new IPPeerAddress("1.2.3.4:12392");

		assertEquals(a, b, "distinct instances with the same host:port must be equal");
		assertEquals(a.hashCode(), b.hashCode(), "equal addresses must share a hashCode");
	}

	@Test
	void ipAddressInequality() {
		PeerAddress base = new IPPeerAddress("1.2.3.4:12392");

		assertNotEquals(base, new IPPeerAddress("1.2.3.5:12392"), "differing host must not be equal");
		assertNotEquals(base, new IPPeerAddress("1.2.3.4:12393"), "differing port must not be equal");
		assertFalse(base.equals("1.2.3.4:12392"), "must not equal an unrelated type");
	}

	@Test
	void ipAddressHostIsCaseInsensitive() {
		PeerAddress upper = new IPPeerAddress("Node1.Qortal.ORG:12392");
		PeerAddress lower = new IPPeerAddress("node1.qortal.org:12392");

		assertEquals(upper, lower, "host comparison must ignore case");
		assertEquals(upper.hashCode(), lower.hashCode(),
				"case-insensitive equality demands a case-insensitive hashCode");
	}

	@Test
	void reticulumAddressEquality() {
		byte[] hash = { 0x01, 0x02, 0x03, 0x04 };
		PeerAddress a = new ReticulumPeerAddress(hash.clone());
		PeerAddress b = new ReticulumPeerAddress(hash.clone());

		assertEquals(a, b, "same destination hash means same peer");
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, new ReticulumPeerAddress(new byte[] { 0x01, 0x02, 0x03, 0x05 }));
	}

	@Test
	void addressTypesNeverEqualEachOther() {
		PeerAddress ip = new IPPeerAddress("1.2.3.4:12392");
		PeerAddress reticulum = new ReticulumPeerAddress(new byte[] { 0x01, 0x02 });

		assertNotEquals(ip, reticulum);
		assertNotEquals(reticulum, ip);
	}

	/**
	 * Network.mergePeersUnlocked de-duplicates by putting the incoming batch into a HashSet and
	 * probing it with each known peer's address, so both equals and hashCode have to hold up.
	 */
	@Test
	void addressesUsableAsHashSetMembers() {
		Set<PeerAddress> known = new HashSet<>(Arrays.asList(
				new IPPeerAddress("1.2.3.4:12392"),
				new IPPeerAddress("Node1.Qortal.ORG:12392"),
				new ReticulumPeerAddress(new byte[] { 0x01, 0x02 })));

		assertTrue(known.contains(new IPPeerAddress("1.2.3.4:12392")), "equal IP address must be found");
		assertTrue(known.contains(new IPPeerAddress("node1.qortal.org:12392")),
				"differently-cased host must be found");
		assertTrue(known.contains(new ReticulumPeerAddress(new byte[] { 0x01, 0x02 })),
				"equal Reticulum address must be found");
		assertFalse(known.contains(new IPPeerAddress("9.9.9.9:12392")), "unknown address must not be found");

		// A batch containing duplicates must collapse
		assertEquals(1, new HashSet<>(Arrays.asList(
				new IPPeerAddress("1.2.3.4:12392"),
				new IPPeerAddress("1.2.3.4:12392"))).size());
	}

	@Test
	void peerDataIsSameAddress() {
		PeerData a = new PeerData(new IPPeerAddress("1.2.3.4:12392"));
		PeerData b = new PeerData(new IPPeerAddress("1.2.3.4:12392"));
		PeerData other = new PeerData(new IPPeerAddress("1.2.3.5:12392"));

		assertTrue(a.isSameAddress(b));
		assertFalse(a.isSameAddress(other));
	}
}
