package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.junit.Test;
import org.qortal.crypto.Crypto;
import org.qortal.test.common.Common;
import org.qortal.utils.Base58;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real classifier regressions: the same script is not the same funding outpoint. */
public class PirateOutpointTests extends Common {
    private static final long VALUE = 100020000L;
    private static final String TXID = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    private static final String OTHER = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f";

    @Test public void spendingTheOtherOutputLeavesTheSavedOutputFunded() throws Exception {
        Fixture f = new Fixture();
        f.history.add(f.refund(TXID, 3));
        when(f.chain.getUnspentOutputs(f.address, false)).thenReturn(List.of(f.output(TXID, 2)));
        assertEquals(BitcoinyHTLC.Status.FUNDED, f.status(TXID, 2, 3));
        assertEquals(BitcoinyHTLC.Status.REFUNDED, f.status(TXID, 3, 3));
    }

    @Test public void matchingVoutInAnotherTransactionCannotConfirmSettlement() throws Exception {
        Fixture f = new Fixture();
        f.history.add(f.refund(OTHER, 2));
        when(f.chain.getUnspentOutputs(f.address, false)).thenReturn(List.of(f.output(TXID, 2)));
        assertEquals(BitcoinyHTLC.Status.FUNDED, f.status(TXID, 2, 3));
        assertEquals(BitcoinyHTLC.Status.REFUNDED, f.status(OTHER, 2, 3));
    }

    @Test public void exactOutpointAlsoRequiresTheScriptAndConfirmationDepth() throws Exception {
        Fixture f = new Fixture();
        f.history.add(f.refund(TXID, 2));
        when(f.chain.getCurrentHeight()).thenReturn(101);
        assertEquals(BitcoinyHTLC.Status.REFUND_IN_PROGRESS, f.status(TXID, 2, 3));
        assertEquals(BitcoinyHTLC.Status.REFUNDED, f.status(TXID, 2, 1));
        f.history.clear();
        f.history.add(new BitcoinyTransaction(OTHER, 100, 0, null,
                List.of(new BitcoinyTransaction.Input("00", -2, wireHash(TXID), 2)), List.of()));
        when(f.chain.getNetId()).thenReturn(UUID.randomUUID().toString());
        when(f.chain.getUnspentOutputs(f.address, false)).thenReturn(List.of(f.output(TXID, 2)));
        assertEquals(BitcoinyHTLC.Status.FUNDED, f.status(TXID, 2, 3));
    }

    @Test public void legacySelectionRejectsMultipleOutputsButPinnedSelectionCanRecover() throws Exception {
        Fixture f = new Fixture();
        when(f.chain.getUnspentOutputs(f.address, false)).thenReturn(List.of(f.output(TXID, 2), f.output(TXID, 3)));
        try {
            PirateChainHTLC.getUnspentFundingOutput(f.chain, f.address, VALUE);
            fail("Ambiguous legacy funding must remain recoverable");
        } catch (ForeignBlockchainException expected) { assertTrue(expected.getMessage().contains("Ambiguous")); }
        assertEquals(2, PirateChainHTLC.getUnspentFundingOutput(f.chain, f.address, VALUE, TXID, 2).index);
        f.history.add(new BitcoinyTransaction(TXID, 100, 0, null, List.of(), List.of(
                new BitcoinyTransaction.Output(f.script, VALUE), new BitcoinyTransaction.Output(f.script, VALUE))));
        assertEquals(2, PirateChainHTLC.countFundingOutputs(f.chain, f.address, VALUE));
    }

    private static String wireHash(String txid) {
        byte[] hash = HashCode.fromString(txid).asBytes();
        com.google.common.primitives.Bytes.reverse(hash);
        return HashCode.fromBytes(hash).toString();
    }

    private static class Fixture {
        final BitcoinyBlockchainProvider chain = mock(BitcoinyBlockchainProvider.class);
        final List<BitcoinyTransaction> history = new ArrayList<>();
        final byte[] redeem;
        final String address;
        final String script;

        Fixture() throws Exception {
            Common.useDefaultSettings();
            redeem = PirateChainHTLC.buildScript(new byte[33], 1700000000, new byte[33], new byte[20]);
            byte[] hash = Crypto.hash160(redeem);
            address = Base58.encode(ByteBuffer.allocate(25).put((byte) 85).put(hash).array());
            script = HashCode.fromBytes(ByteBuffer.allocate(23).put((byte) 0xa9).put((byte) 20).put(hash).put((byte) 0x87).array()).toString();
            when(chain.getNetId()).thenReturn(UUID.randomUUID().toString());
            when(chain.getCurrentHeight()).thenReturn(102);
            when(chain.getAddressBitcoinyTransactions(address, false)).thenAnswer(i -> new ArrayList<>(history));
        }

        UnspentOutput output(String txid, int index) {
            return new UnspentOutput(HashCode.fromString(wireHash(txid)).asBytes(), index, 90, VALUE,
                    HashCode.fromString(script).asBytes(), address);
        }

        BitcoinyTransaction refund(String txid, int index) {
            byte[] sig = ByteBuffer.allocate(redeem.length + 6)
                    .put(new byte[] {1, 1, 1, 0x51, 0x4c, (byte) redeem.length}).put(redeem).array();
            BitcoinyTransaction tx = new BitcoinyTransaction(OTHER, 100, 0, null,
                    List.of(new BitcoinyTransaction.Input(HashCode.fromBytes(sig).toString(), -2, wireHash(txid), index)), List.of());
            tx.height = 100;
            return tx;
        }

        BitcoinyHTLC.Status status(String txid, int index, int depth) throws Exception {
            return PirateChainHTLC.determineHtlcStatus(chain, address, VALUE, depth, txid, index);
        }
    }
}
