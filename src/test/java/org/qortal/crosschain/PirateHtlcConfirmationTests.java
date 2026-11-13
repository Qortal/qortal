package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.junit.Test;
import org.qortal.crypto.Crypto;
import org.qortal.test.common.Common;
import org.qortal.utils.Base58;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PirateHtlcConfirmationTests extends Common {
    @Test public void fundingDiscoveryRetainsTheSelectedIndexAndValue() throws Exception {
        BitcoinyBlockchainProvider chain = mock(BitcoinyBlockchainProvider.class);
        byte[] hash = Crypto.hash160(new byte[]{1, 2, 3});
        String address = Base58.encode(ByteBuffer.allocate(25).put((byte)85).put(hash).array());
        byte[] script = ByteBuffer.allocate(23).put((byte)0xa9).put((byte)20).put(hash).put((byte)0x87).array();
        byte[] txid = new byte[32]; txid[0] = 1;
        UnspentOutput funding = new UnspentOutput(txid, 2, 100, 100020000L, script, address);
        when(chain.getUnspentOutputs(address, false)).thenReturn(List.of(
                new UnspentOutput(new byte[32], 0, 100, 200000000L, new byte[]{0}, address), funding));
        assertSame(funding, PirateChainHTLC.getUnspentFundingOutput(chain, address, 100020000L));
        assertNull(PirateChainHTLC.getUnspentFundingOutput(chain, address, 100030000L));
        ByteBuffer raw = ByteBuffer.allocate(100).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        raw.putInt(1).put((byte)0).put((byte)3);
        raw.putLong(1).put((byte)1).put((byte)0x51).putLong(1).put((byte)1).put((byte)0x51);
        raw.putLong(100020000L).put((byte)script.length).put(script).putInt(0);
        when(chain.getRawTransaction(any(byte[].class))).thenReturn(Arrays.copyOf(raw.array(), raw.position()));
        byte[] displayHash = txid.clone(); com.google.common.primitives.Bytes.reverse(displayHash);
        UnspentOutput captured = PirateChainHTLC.getFundingOutput(chain, HashCode.fromBytes(displayHash).toString(), address, 100020000L);
        assertEquals(2, captured.index); assertEquals(100020000L, captured.value); assertArrayEquals(txid, captured.hash);
        String t3Address = Base58.encode(ByteBuffer.allocate(26).putShort((short)0x1cbd).put(hash).array());
        assertEquals(2, PirateChainHTLC.getFundingOutput(chain, HashCode.fromBytes(displayHash).toString(), t3Address, 100020000L).index);
        verify(chain, times(2)).getRawTransaction(txid);
    }
    @Test public void refundRequiresRequestedDepthAndDoesNotReuseOneConfirmationCache() throws Exception {
        Common.useDefaultSettings();
        byte[] script = PirateChainHTLC.buildScript(new byte[33], 1700000000, new byte[33], new byte[20]);
        byte[] hash = Crypto.hash160(script);
        String address = Base58.encode(ByteBuffer.allocate(25).put((byte)85).put(hash).array());
        byte[] sig = ByteBuffer.allocate(script.length + 6).put(new byte[]{1,1,1,0x51,0x4c,(byte)script.length}).put(script).array();
        BitcoinyTransaction spend = new BitcoinyTransaction("11".repeat(32), 100, 1700000000, null,
                List.of(new BitcoinyTransaction.Input(HashCode.fromBytes(sig).toString(), -2, "22".repeat(32), 0)), List.of());
        spend.height = 100;
        BitcoinyBlockchainProvider chain = mock(BitcoinyBlockchainProvider.class);
        when(chain.getNetId()).thenReturn(UUID.randomUUID().toString());
        when(chain.getCurrentHeight()).thenReturn(101);
        when(chain.getAddressBitcoinyTransactions(address, false)).thenAnswer(i -> new ArrayList<>(List.of(spend)));
        assertEquals(BitcoinyHTLC.Status.REFUNDED, PirateChainHTLC.determineHtlcStatus(chain, address, 1));
        assertEquals(BitcoinyHTLC.Status.REFUND_IN_PROGRESS, PirateChainHTLC.determineHtlcStatus(chain, address, 1, 3));
        when(chain.getNetId()).thenReturn(UUID.randomUUID().toString());
        when(chain.getCurrentHeight()).thenReturn(102);
        assertEquals(BitcoinyHTLC.Status.REFUNDED, PirateChainHTLC.determineHtlcStatus(chain, address, 1, 3));
    }
}
