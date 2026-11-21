package org.qortal.crosschain;

import org.bitcoinj.core.Bech32;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.*;

public class PirateChainAddressTests {

	// Public deterministic vectors from the pinned Unified JNI library, entropy 11.
	private static final String SAPLING = "zs1mj80tkyjqjhc84aquetymwgrasxjdkkft0qcn5xtp2p9r42vs5ernyv4nzgufmz6xzuqvw20w9z";
	private static final String IRONWOOD = "pirate1lyp0g5438qkp9s5me5epku9ak9k0dxqsfyt9z894zym8qw38sps488ljyee27yw29vc2cyj8mse";

	@Test
	public void acceptsSaplingAndIronwoodMainnetAddresses() {
		assertTrue(PirateChainAddress.isValid(SAPLING));
		assertTrue(PirateChainAddress.isValid(IRONWOOD));
		assertTrue(PirateChainAddress.isValid(SAPLING.toUpperCase(Locale.ROOT)));
		assertTrue(PirateChainAddress.isValid(IRONWOOD.toUpperCase(Locale.ROOT)));
	}

	@Test
	public void rejectsChecksumErrorsMixedCaseAndWrongNetworks() {
		assertFalse(PirateChainAddress.isValid(IRONWOOD.substring(0, 81) + "q"));
		assertFalse(PirateChainAddress.isValid(SAPLING.substring(0, 77) + "q"));
		assertFalse(PirateChainAddress.isValid("P" + IRONWOOD.substring(1)));
		byte[] receiver = Bech32.decode(IRONWOOD).data;
		assertFalse(PirateChainAddress.isValid(Bech32.encode("pirate-test", receiver)));
		assertFalse(PirateChainAddress.isValid(Bech32.encode("pirate-regtest", receiver)));
		assertFalse(PirateChainAddress.isValid(Bech32.encode("ztestsapling", receiver)));
		assertFalse(PirateChainAddress.isValid(Bech32.encode("foobar", receiver)));
	}

	@Test
	public void rejectsMissingAddressesAndWrongReceiverLengths() {
		assertFalse(PirateChainAddress.isValid(null));
		assertFalse(PirateChainAddress.isValid(""));
		assertFalse(PirateChainAddress.isValid(" " + IRONWOOD));
		assertFalse(PirateChainAddress.isValid(Bech32.encode("pirate", new byte[68])));
		assertFalse(PirateChainAddress.isValid(Bech32.encode("zs", new byte[70])));
	}

	@Test
	public void rejectsNonzeroPaddingEvenWithAValidChecksum() {
		byte[] receiver = Bech32.decode(IRONWOOD).data;
		receiver[68] |= 1;
		assertFalse(PirateChainAddress.isValid(Bech32.encode("pirate", receiver)));
		assertFalse(PirateChainAddress.isValid(Bech32.encode("zs", receiver)));
	}

	@Test
	public void preservesIronwoodPoolAndReceiverForSettlement() {
		byte[] stored = PirateChainAddress.toReceivingAccountInfo(IRONWOOD);
		assertEquals(IRONWOOD, new String(stored, StandardCharsets.US_ASCII));
		assertEquals(IRONWOOD, PirateChainAddress.fromReceivingAccountInfo(stored));
		assertEquals(IRONWOOD, PirateChainAddress.fromReceivingAccountInfo(
				PirateChainAddress.toReceivingAccountInfo(IRONWOOD.toUpperCase(Locale.ROOT))));
	}

	@Test
	public void keepsSaplingCompatibleWithExistingTradesAndBackups() {
		byte[] legacy = Bech32.decode(SAPLING).data;
		assertArrayEquals(legacy, PirateChainAddress.toReceivingAccountInfo(SAPLING));
		assertEquals(SAPLING, PirateChainAddress.fromReceivingAccountInfo(legacy));
		assertEquals(SAPLING, PirateChainAddress.fromReceivingAccountInfo(SAPLING.getBytes(StandardCharsets.US_ASCII)));
	}

	@Test
	public void rejectsInvalidAddressesBeforePersistingThem() {
		expectInvalid(() -> PirateChainAddress.toReceivingAccountInfo("invalid"));
		expectInvalid(() -> PirateChainAddress.toReceivingAccountInfo(null));
	}

	@Test
	public void rejectsCorruptStoredAddresses() {
		byte[] badLegacy = new byte[69];
		Arrays.fill(badLegacy, (byte) 32);
		expectInvalid(() -> PirateChainAddress.fromReceivingAccountInfo(badLegacy));
		expectInvalid(() -> PirateChainAddress.fromReceivingAccountInfo(null));
		expectInvalid(() -> PirateChainAddress.fromReceivingAccountInfo(new byte[43]));
		byte[] badPadding = Bech32.decode(SAPLING).data;
		badPadding[68] |= 1;
		expectInvalid(() -> PirateChainAddress.fromReceivingAccountInfo(badPadding));
	}

	private static void expectInvalid(Runnable action) {
		try {
			action.run();
			fail("Expected an invalid receiving address to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected validation failure.
		}
	}
}
