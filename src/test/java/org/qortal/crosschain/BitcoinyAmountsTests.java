package org.qortal.crosschain;

import org.junit.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import static org.junit.Assert.*;

public class BitcoinyAmountsTests {
    @Test public void exactNumericRepresentationsPreserveAtomicUnits() {
        for (Number number : List.of(1, 1L, BigInteger.ONE, new BigDecimal("1.00000000"), 1.0))
            assertEquals(100000000L, BitcoinyAmounts.atomicUnits(number));
        assertEquals(1L, BitcoinyAmounts.atomicUnits(1e-8));
        assertEquals(0L, BitcoinyAmounts.atomicUnits(0));
        assertEquals(Long.MAX_VALUE, BitcoinyAmounts.atomicUnits(new BigDecimal("92233720368.54775807")));
    }

    @Test public void ambiguousAndOverflowingAmountsRequireWireEvidence() {
        for (Number number : List.of(0.00010001000000000001, 75000000.00000024,
                new BigDecimal("92233720368.54775808"), 0.000000005)) {
            try { BitcoinyAmounts.atomicUnits(number); fail("Must not round or truncate " + number); }
            catch (ArithmeticException expected) { }
        }
    }

    @Test public void invalidOutputAmountsAreRejected() {
        for (Object invalid : List.of(-1, "1.0", Double.NaN, Double.POSITIVE_INFINITY)) {
            try { BitcoinyAmounts.atomicUnits(invalid); fail("Invalid output amount accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
