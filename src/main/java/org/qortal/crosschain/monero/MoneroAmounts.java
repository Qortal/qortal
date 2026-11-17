package org.qortal.crosschain.monero;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/** Exact conversions for individual Monero uint64 wire amounts, in piconero. */
public final class MoneroAmounts {

	public static final int DECIMAL_PLACES = 12;
	public static final BigInteger ATOMIC_UNITS_PER_XMR = BigInteger.TEN.pow(DECIMAL_PLACES);
	public static final BigInteger MAX_ATOMIC_UNITS = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

	private static final Pattern XMR_AMOUNT = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,12})?");
	private static final Pattern ATOMIC_AMOUNT = Pattern.compile("(?:0|[1-9][0-9]*)");

	private MoneroAmounts() {
	}

	/** Parse a plain nonnegative decimal XMR amount without rounding or floating point. */
	public static BigInteger parseXmr(String amount) {
		// The largest uint64 XMR amount is 18446744.073709551615 (21 characters).
		if (amount == null || amount.length() > 21 || !XMR_AMOUNT.matcher(amount).matches())
			throw new IllegalArgumentException("Expected a plain XMR amount with at most 12 decimal places");

		return requireRange(new BigDecimal(amount).movePointRight(DECIMAL_PLACES).toBigIntegerExact());
	}

	/** Parse an unsigned integer token without losing values above Long.MAX_VALUE. */
	public static BigInteger parseAtomicUnits(String amount) {
		if (amount == null || amount.length() > 20 || !ATOMIC_AMOUNT.matcher(amount).matches())
			throw new IllegalArgumentException("Expected a nonnegative integer Monero amount");

		return requireRange(new BigInteger(amount));
	}

	/** Format an individual wire amount with exactly 12 decimal places. */
	public static String formatXmr(BigInteger atomicUnits) {
		return new BigDecimal(requireRange(atomicUnits), DECIMAL_PLACES).toPlainString();
	}

	private static BigInteger requireRange(BigInteger amount) {
		if (amount == null || amount.signum() < 0 || amount.compareTo(MAX_ATOMIC_UNITS) > 0)
			throw new IllegalArgumentException("Monero amount is outside the uint64 range");
		return amount;
	}
}
