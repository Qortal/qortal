package org.qortal.test;

import org.junit.Test;
import org.qortal.api.AmountTypeAdapter;
import java.util.List;
import static org.junit.Assert.*;

public class AmountTypeAdapterTests {
    @Test public void qortAmountsRetainExactSignedAtomicUnits() throws Exception {
        AmountTypeAdapter adapter = new AmountTypeAdapter();
        assertEquals(Long.valueOf(1), adapter.unmarshal("0.00000001"));
        assertEquals(Long.valueOf(-123456789), adapter.unmarshal("-1.23456789"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), adapter.unmarshal("92233720368.54775807"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), adapter.unmarshal("-92233720368.54775808"));
        assertEquals("1.23456789", adapter.marshal(123456789L));
        assertNull(adapter.unmarshal(null));
    }

    @Test public void qortFractionalUnitsAndOverflowAreRejected() throws Exception {
        for (String invalid : List.of("0.000000001", "92233720368.54775808", "-92233720368.54775809")) {
            try { new AmountTypeAdapter().unmarshal(invalid); fail("Must not round or truncate QORT amounts"); }
            catch (ArithmeticException expected) { }
        }
    }
}
