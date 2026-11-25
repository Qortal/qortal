package org.qortal.crosschain.monero;

import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.*;

public class MoneroAmountsTests {

	@Test
	public void preservesOnePiconeroAndAllTwelveDecimals() {
		assertEquals(BigInteger.ONE, MoneroAmounts.parseXmr("0.000000000001"));
		assertEquals(new BigInteger("1234567890123"), MoneroAmounts.parseXmr("1.234567890123"));
		assertEquals(new BigInteger("1000000000000"), MoneroAmounts.parseXmr("1"));
		assertEquals(new BigInteger("100000000000"), MoneroAmounts.parseXmr("0.1"));
		assertEquals(BigInteger.ZERO, MoneroAmounts.parseXmr("0.000000000000"));
	}

	@Test
	public void formatsExactAmountsWithoutScientificNotation() {
		assertEquals("0.000000000000", MoneroAmounts.formatXmr(BigInteger.ZERO));
		assertEquals("0.000000000001", MoneroAmounts.formatXmr(BigInteger.ONE));
		assertEquals("1.234567890123", MoneroAmounts.formatXmr(new BigInteger("1234567890123")));
		assertEquals("1.000000000000", MoneroAmounts.formatXmr(MoneroAmounts.ATOMIC_UNITS_PER_XMR));
	}

	@Test
	public void supportsTheUnsignedWireRangeAboveSignedLong() {
		BigInteger aboveLong = new BigInteger("9223372036854775808");
		BigInteger maximum = new BigInteger("18446744073709551615");
		assertEquals(aboveLong, MoneroAmounts.parseXmr("9223372.036854775808"));
		assertEquals(aboveLong, MoneroAmounts.parseAtomicUnits("9223372036854775808"));
		assertEquals(maximum, MoneroAmounts.parseXmr("18446744.073709551615"));
		assertEquals(maximum, MoneroAmounts.parseAtomicUnits("18446744073709551615"));
		assertEquals("18446744.073709551615", MoneroAmounts.formatXmr(maximum));
	}

	@Test
	public void rejectsOverflowAtEveryEntryPoint() {
		expectInvalid(() -> MoneroAmounts.parseXmr("18446744.073709551616"));
		expectInvalid(() -> MoneroAmounts.parseXmr("18446745"));
		expectInvalid(() -> MoneroAmounts.parseAtomicUnits("18446744073709551616"));
		expectInvalid(() -> MoneroAmounts.formatXmr(new BigInteger("18446744073709551616")));
	}

	@Test
	public void rejectsRoundingSignsWhitespaceAndAmbiguousDecimals() {
		String[] invalid = { null, "", " ", " 1", "1 ", "1\n", "-1", "-0", "+1", "1e-12",
				"1E1", ".1", "1.", "01", "00.1", "1,000", "NaN", "Infinity", "１", "١",
				"0.0000000000001", "1.0000000000000", "1".repeat(10000) };
		for (String amount : invalid)
			expectInvalid(() -> MoneroAmounts.parseXmr(amount));
	}

	@Test
	public void parsesOnlyCanonicalIntegerAtomicAmounts() {
		assertEquals(BigInteger.ZERO, MoneroAmounts.parseAtomicUnits("0"));
		assertEquals(BigInteger.ONE, MoneroAmounts.parseAtomicUnits("1"));
		assertEquals(new BigInteger("1234567890123"), MoneroAmounts.parseAtomicUnits("1234567890123"));
		String[] invalid = { null, "", "00", "01", "-1", "+1", "1.0", "1e2", "1 ", " 1", "1\n", "１",
				"1".repeat(10000) };
		for (String amount : invalid)
			expectInvalid(() -> MoneroAmounts.parseAtomicUnits(amount));
	}

	@Test
	public void rejectsMissingAndNegativeAtomicValues() {
		expectInvalid(() -> MoneroAmounts.formatXmr(null));
		expectInvalid(() -> MoneroAmounts.formatXmr(BigInteger.valueOf(-1)));
	}

	private static void expectInvalid(Runnable action) {
		try {
			action.run();
			fail("Expected invalid Monero amount to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}
}
