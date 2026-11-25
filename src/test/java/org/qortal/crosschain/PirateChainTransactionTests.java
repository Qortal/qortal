package org.qortal.crosschain;

import org.junit.Test;
import org.qortal.transform.TransformationException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class PirateChainTransactionTests {
    private byte[] fixture(int version, int group, int scriptSize) {
        ByteBuffer b = ByteBuffer.allocate(1000).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x80000000 | version).putInt(group);
        if (version >= 5) b.putInt(0x37a5165b).putInt(1700000000).putInt(4000000);
        b.put((byte)1).put(new byte[32]).putInt(7);
        if (scriptSize >= 253) b.put((byte)253).putShort((short)scriptSize); else b.put((byte)scriptSize);
        b.put(new byte[scriptSize]).putInt(0xfffffffe);
        b.put((byte)1).putLong(123456789).put((byte)1).put((byte)0x51);
        if (version < 5) b.putInt(1700000000).putInt(4000000);
        return java.util.Arrays.copyOf(b.array(), b.position());
    }
    @Test public void legacyAndModernTransparentPrefixesUseTheCorrectHeaderOrder() throws Exception {
        int[] groups = {0x03c48270, 0x892f2085, 0x26a7270a, 0xd884b698};
        for (int version = 3; version <= 6; version++) {
            PirateChainTransaction tx = PirateChainTransaction.read(fixture(version, groups[version-3], 300));
            assertEquals(4000000, tx.expiryHeight);
            assertEquals(1700000000, tx.transaction.locktime);
            assertEquals(7, tx.transaction.inputs.get(0).outputVout);
            assertEquals(600, tx.transaction.inputs.get(0).scriptSig.length());
            assertEquals(123456789, tx.transaction.outputs.get(0).value);
        }
    }
    @Test public void truncatedAndUnsupportedHeadersFailClosed() throws Exception {
        for (byte[] bytes : new byte[][] {new byte[0], new byte[10], fixture(6, 0, 1),
                java.util.Arrays.copyOf(fixture(4, 0x892f2085, 300), 50)}) {
            try { PirateChainTransaction.read(bytes); fail("Accepted invalid transaction"); }
            catch (TransformationException expected) { }
        }
    }
    @Test public void malformedCompactSizeCannotCauseAnUnboundedAllocation() throws Exception {
        byte[] bytes = fixture(6, 0xd884b698, 1);
        bytes[20] = (byte)255;
        for (int i=21; i<29; i++) bytes[i]=(byte)255;
        try { PirateChainTransaction.read(bytes); fail(); } catch (TransformationException expected) { }
    }
}
