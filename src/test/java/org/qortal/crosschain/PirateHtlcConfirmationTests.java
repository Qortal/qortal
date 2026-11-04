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
