package org.qortal.crosschain.monero;

import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

public class MoneroAddressTests {

	// Public example and decoded keys: https://docs.getmonero.org/public-address/standard-address/
	private static final String STANDARD = "4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge";
	private static final byte[] SPEND_KEY = Hex.decode("eda9fe8dfcdd25d5430ea64229d04f6b41b2e5a1587c29cd499a63eb79d11711");
	private static final byte[] VIEW_KEY = Hex.decode("3076a02b73d130fb904c9e91075fcd16f735c6850dfadb125eb826d96a113f09");
	private static final byte[] PAYMENT_ID = Hex.decode("0123456789abcdef");

	// Frozen independently using Python integer block encoding + PyCryptodome 3.23.0 Keccak.
	// Same public keys, explicit prefixes from Monero v0.18.5.4 cryptonote_config.h.
	// Subaddress fixtures test the wire format; they are not derived spendable wallets.
	private static final String[][] VECTORS = {
			{ "MAINNET", "STANDARD", STANDARD },
			{ "MAINNET", "INTEGRATED", "4LL9oSLmtpccfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2WMZAr69qxgTxyKRqx" },
			{ "MAINNET", "SUBADDRESS", "8BTd81B7syWcfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv25pnJx6" },
			{ "TESTNET", "STANDARD", "A2B2GtBYZvCcfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv266WT61" },
			{ "TESTNET", "INTEGRATED", "ABshHh13BBicfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2WMZAr69qxgTyGaSaF" },
			{ "TESTNET", "SUBADDRESS", "BhBkQxNUebNcfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv237mhMG" },
			{ "STAGENET", "STANDARD", "5AqWsUSEwACcfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv23X7tqA" },
			{ "STAGENET", "INTEGRATED", "5LYBtHFjYRicfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2WMZAr69qxgU3zqZfQ" },
			{ "STAGENET", "SUBADDRESS", "7BFb3AGAENQcfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv256BPSr" }
	};

	@Test
	public void decodesPublishedStandardAddressAndKeys() {
		MoneroAddress address = MoneroAddress.parse(STANDARD, MoneroNetwork.MAINNET);
		assertEquals(STANDARD, address.getAddress());
		assertEquals(MoneroAddress.Type.STANDARD, address.getType());
		assertEquals(MoneroNetwork.MAINNET, address.getNetwork());
		assertArrayEquals(SPEND_KEY, address.getPublicSpendKey());
		assertArrayEquals(VIEW_KEY, address.getPublicViewKey());
		assertNull(address.getPaymentId());
	}

	@Test
	public void acceptsPublishedIntegratedAddress() {
		// https://docs.getmonero.org/public-address/integrated-address/
		String integrated = "4LL9oSLmtpccfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2bYXZKKQePHES9khPK";
		MoneroAddress address = MoneroAddress.parse(integrated, MoneroNetwork.MAINNET);
		assertEquals(MoneroAddress.Type.INTEGRATED, address.getType());
		assertArrayEquals(SPEND_KEY, address.getPublicSpendKey());
		assertArrayEquals(VIEW_KEY, address.getPublicViewKey());
		assertEquals(8, address.getPaymentId().length);
	}

	@Test
	public void decodesAllThreeAddressTypesOnAllThreeNetworks() {
		for (String[] vector : VECTORS) {
			MoneroNetwork network = MoneroNetwork.valueOf(vector[0]);
			MoneroAddress.Type type = MoneroAddress.Type.valueOf(vector[1]);
			MoneroAddress address = MoneroAddress.parse(vector[2], network);
			assertTrue(MoneroAddress.isValid(vector[2], network));
			assertEquals(network, address.getNetwork());
			assertEquals(type, address.getType());
			assertArrayEquals(SPEND_KEY, address.getPublicSpendKey());
			assertArrayEquals(VIEW_KEY, address.getPublicViewKey());
			if (type == MoneroAddress.Type.INTEGRATED)
				assertArrayEquals(PAYMENT_ID, address.getPaymentId());
			else
				assertNull(address.getPaymentId());
		}
	}

	@Test
	public void rejectsEveryWrongNetworkCombination() {
		for (String[] vector : VECTORS)
			for (MoneroNetwork network : MoneroNetwork.values())
				if (!network.name().equals(vector[0]))
					assertFalse(MoneroAddress.isValid(vector[2], network));
	}

	@Test
	public void rejectsSingleCharacterCorruptionThroughoutTheAddress() {
		for (String[] vector : VECTORS) {
			String address = vector[2];
			for (int index = 0; index < address.length(); ++index) {
				char replacement = address.charAt(index) == '1' ? '2' : '1';
				String changed = address.substring(0, index) + replacement + address.substring(index + 1);
				assertFalse(MoneroAddress.isValid(changed, MoneroNetwork.valueOf(vector[0])));
			}
		}
	}

	@Test
	public void rejectsWhitespaceCaseChangesNonBase58AndWrongLengths() {
		String[] invalid = { null, "", " " + STANDARD, STANDARD + " ", STANDARD + "\n",
				STANDARD.substring(1), STANDARD + "1", STANDARD.substring(0, 94), "1".repeat(10000),
				STANDARD.toUpperCase(Locale.ROOT), STANDARD.toLowerCase(Locale.ROOT),
				"0" + STANDARD.substring(1), "O" + STANDARD.substring(1), "I" + STANDARD.substring(1),
				"l" + STANDARD.substring(1), "é" + STANDARD.substring(1) };
		for (String address : invalid)
			assertFalse(MoneroAddress.isValid(address, MoneroNetwork.MAINNET));
	}

	@Test
	public void rejectsOverflowInBothFullAndFinalBase58Blocks() {
		// One greater than the 8-byte/5-byte maximum values in Monero's Base58 unit vectors.
		assertFalse(MoneroAddress.isValid("jpXCZedGfVR" + STANDARD.substring(11), MoneroNetwork.MAINNET));
		assertFalse(MoneroAddress.isValid(STANDARD.substring(0, 88) + "VtB5VXd", MoneroNetwork.MAINNET));
		assertFalse(MoneroAddress.isValid("z".repeat(95), MoneroNetwork.MAINNET));
	}

	@Test
	public void rejectsChecksumValidUnknownPrefixesAndMismatchedPayloadLengths() {
		String[] invalid = {
				"3zvomphngHacfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv26foNUi",
				"4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2WK48GNSUQf14bwHzh",
				"4LL9oSLmtpccfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv24HfTtX"
		};
		for (String address : invalid)
			assertFalse(MoneroAddress.isValid(address, MoneroNetwork.MAINNET));
	}

	@Test
	public void rejectsChecksumValidInvalidAndLowOrderPublicKeys() {
		String[] invalid = {
				"4BKnGLZNZ5pjpXCZedGfVQjpXCZedGfVQjpXCZedGfVQjgf3goS3G8Xj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv26Azxs8",
				"4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx41d7FXjswpJjpXCZedGfVQjpXCZedGfVQjpXCZedGfVQVoWeRDg",
				"41fJjQDhryD11111111111111111111111111111111112qX8xdGCudj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv26EAsgJ",
				"41d7FXjswpK11111111111111111111111111111111112qX8xdGCudj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv26cNv91"
		};
		for (String address : invalid)
			assertFalse(MoneroAddress.isValid(address, MoneroNetwork.MAINNET));
	}

	@Test
	public void requiresExplicitNetworkSelection() {
		assertFalse(MoneroAddress.isValid(STANDARD, null));
		try {
			MoneroAddress.parse(STANDARD, null);
			fail("Expected missing network to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	@Test
	public void keepsDecodedPublicDataImmutable() {
		MoneroAddress address = MoneroAddress.parse(VECTORS[1][2], MoneroNetwork.MAINNET);
		address.getPublicSpendKey()[0] ^= 1;
		address.getPublicViewKey()[0] ^= 1;
		address.getPaymentId()[0] ^= 1;
		assertArrayEquals(SPEND_KEY, address.getPublicSpendKey());
		assertArrayEquals(VIEW_KEY, address.getPublicViewKey());
		assertArrayEquals(PAYMENT_ID, address.getPaymentId());
	}
}
