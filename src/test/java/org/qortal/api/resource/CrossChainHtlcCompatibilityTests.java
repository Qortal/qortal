package org.qortal.api.resource;

import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.qortal.api.Security;
import org.qortal.crosschain.*;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.*;
import org.qortal.repository.*;
import org.qortal.test.common.Common;
import org.qortal.utils.NTP;

import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Verify BTC-family batch routes still use their own script builder, keys and broadcast. */
public class CrossChainHtlcCompatibilityTests extends Common {
    @Test public void refundAllPreservesBitcoinFamilySpending() throws Exception { checkBatch(false); }
    @Test public void redeemAllPreservesBitcoinFamilySpending() throws Exception { checkBatch(true); }

    private void checkBatch(boolean redeem) throws Exception {
        Repository repository = mock(Repository.class);
        CrossChainRepository cross = mock(CrossChainRepository.class);
        ATRepository ats = mock(ATRepository.class);
        when(repository.getCrossChainRepository()).thenReturn(cross); when(repository.getATRepository()).thenReturn(ats);
        ATData at = mock(ATData.class); when(at.getIsFinished()).thenReturn(true); when(at.getCodeHash()).thenReturn(new byte[32]);
        when(ats.fromATAddress("AT")).thenReturn(at);
        Bitcoiny coin = mock(Bitcoiny.class);
        when(coin.getCurrencyCode()).thenReturn("BTC"); when(coin.getNetworkParameters()).thenReturn(MainNetParams.get());
        when(coin.getBlockchainProvider()).thenReturn(mock(BitcoinyBlockchainProvider.class));
        when(coin.getMedianBlockTime()).thenReturn(100); when(coin.getP2shFee(any())).thenReturn(10000L);
        String receiving = LegacyAddress.fromPubKeyHash(MainNetParams.get(), new byte[20]).toString();
        when(coin.getUnusedReceiveAddress("synthetic-wallet")).thenReturn(receiving);
        when(coin.deriveP2shAddress(any())).thenReturn("synthetic-htlc");
        when(coin.getUnspentOutputs("synthetic-htlc", false)).thenReturn(List.of());
        CrossChainTradeData data = new CrossChainTradeData();
        data.mode = redeem ? AcctMode.REDEEMED : AcctMode.REFUNDED;
        data.expectedForeignAmount = 100000000L; data.lockTimeA = 1; data.tradeTimeout = 60;
        data.partnerForeignPKH = new byte[20]; data.creatorForeignPKH = new byte[20]; data.hashOfSecretA = new byte[20];
        ACCT acct = mock(ACCT.class); when(acct.getBlockchain()).thenReturn(coin);
        when(acct.populateTradeData(repository, at)).thenReturn(data); when(acct.findSecretA(repository, data)).thenReturn(new byte[32]);
        byte[] privateKey = new byte[32]; privateKey[31] = 2;
        TradeBotData trade = new TradeBotData(privateKey, "BitcoinACCTv3", redeem ? "BOB_DONE" : "ALICE_REFUNDING_A",
                redeem ? 30 : 105, "account", "AT", 1L, 100L, new byte[32], new byte[20], "trade-address",
                new byte[32], new byte[20], "BITCOIN", new byte[33], new byte[20], 100000000L,
                redeem ? null : "synthetic-wallet", null, 1, new byte[20]);
        when(cross.getAllTradeBotData()).thenReturn(List.of(trade));
        Transaction transaction = mock(Transaction.class);
        try (MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class);
             MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class);
             MockedStatic<Security> security = mockStatic(Security.class);
             MockedStatic<BitcoinyHTLC> htlc = mockStatic(BitcoinyHTLC.class);
             MockedStatic<NTP> clock = mockStatic(NTP.class)) {
            repos.when(RepositoryManager::getRepository).thenReturn(repository);
            chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
            clock.when(NTP::getTime).thenReturn(1000000L);
            htlc.when(() -> BitcoinyHTLC.buildScript(any(), anyInt(), any(), any())).thenReturn(new byte[]{1});
            htlc.when(() -> BitcoinyHTLC.determineHtlcStatus(any(), anyString(), anyLong())).thenReturn(BitcoinyHTLC.Status.FUNDED);
            htlc.when(() -> BitcoinyHTLC.buildRefundTransaction(any(), any(), any(), any(), any(), anyLong(), any())).thenReturn(transaction);
            htlc.when(() -> BitcoinyHTLC.buildRedeemTransaction(any(), any(), any(), any(), any(), any(), any())).thenReturn(transaction);
            assertTrue(redeem ? new CrossChainHtlcResource().redeemAllHtlc("dummy") : new CrossChainHtlcResource().refundAllHtlc("dummy"));
            verify(coin).broadcastTransaction(transaction);
        }
    }
}
