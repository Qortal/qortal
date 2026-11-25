package org.qortal.crosschain;

import java.math.BigDecimal;

/** Exact conversion of eight-decimal coin amounts returned by JSON providers. */
final class BitcoinyAmounts {
    private BitcoinyAmounts() { }

    /** An arithmetic failure requires wire data; never round an ambiguous amount. */
    static long atomicUnits(Object value) {
        if (!(value instanceof Number))
            throw new IllegalArgumentException("Expected a numeric coin amount");
        BigDecimal amount = new BigDecimal(value.toString());
        if (amount.signum() < 0)
            throw new IllegalArgumentException("Negative transaction output amount");
        // Large altcoin values can lose whole atomic units in a JSON double.
        double precision = value instanceof Float ? Math.ulp((Float) value)
                : value instanceof Double ? Math.ulp((Double) value) : 0;
        if (precision >= 0.00000001)
            throw new ArithmeticException("JSON number lacks atomic-unit precision");
        return amount.movePointRight(8).longValueExact();
    }
}
