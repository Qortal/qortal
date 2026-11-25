package org.qortal.api.resource;

import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.qortal.api.Security;
import org.qortal.crosschain.*;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.*;
import org.qortal.repository.*;
import org.qortal.test.common.Common;
import org.qortal.utils.NTP;

import java.math.BigInteger;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Batch routes preserve the agreed payout using actual signed transactions for all five coins. */
public class CrossChainHtlcCompatibilityTests extends Common {
    @Test public void refundAllPreservesBitcoinFamilySpending() throws Exception { checkBatch(false); }
    @Test public void redeemAllPreservesBitcoinFamilySpending() throws Exception { checkBatch(true); }

    private void checkBatch(boolean redeem) throws Exception {
        NetworkParameters[] networks = { Bitcoin.BitcoinNet.MAIN.getParams(), Litecoin.LitecoinNet.MAIN.getParams(),
                Dogecoin.DogecoinNet.MAIN.getParams(), Digibyte.DigibyteNet.MAIN.getParams(), Ravencoin.RavencoinNet.MAIN.getParams() };
        String[] symbols = {"BTC", "LTC", "DOGE", "DGB", "RVN"};
        for (int index = 0; index < networks.length; index++) {
            NetworkParameters params = networks[index];
            Repository repository = mock(Repository.class); CrossChainRepository cross = mock(CrossChainRepository.class);
            ATRepository ats = mock(ATRepository.class);
            when(repository.getCrossChainRepository()).thenReturn(cross); when(repository.getATRepository()).thenReturn(ats);
            ATData at = mock(ATData.class); when(at.getIsFinished()).thenReturn(true); when(at.getCodeHash()).thenReturn(new byte[32]);
            when(ats.fromATAddress("AT")).thenReturn(at);
            Bitcoiny coin = mock(Bitcoiny.class); BitcoinyBlockchainProvider provider = mock(BitcoinyBlockchainProvider.class);
            when(coin.getCurrencyCode()).thenReturn(symbols[index]); when(coin.getNetworkParameters()).thenReturn(params);
            when(coin.getBlockchainProvider()).thenReturn(provider); when(provider.getMinimumRelayFeePerKb()).thenReturn(100000L);
            when(coin.getMedianBlockTime()).thenReturn(1800000000); when(coin.getP2shFee(any())).thenReturn(10000L);
            when(coin.getFeePerKb()).thenReturn(Coin.valueOf(100000));
            ECKey key = ECKey.fromPrivate(BigInteger.valueOf(2)); byte[] secret = new byte[32]; secret[0] = 47;
            String receiving = LegacyAddress.fromPubKeyHash(params, key.getPubKeyHash()).toString();
            when(coin.getUnusedReceiveAddress("synthetic-wallet")).thenReturn(receiving);
            CrossChainTradeData data = new CrossChainTradeData(); data.mode = redeem ? AcctMode.REDEEMED : AcctMode.REFUNDED;
            data.expectedForeignAmount = 100000000L; data.lockTimeA = 1795000000; data.tradeTimeout = 60;
            ECKey counterparty = ECKey.fromPrivate(BigInteger.valueOf(3));
            data.partnerForeignPKH = (redeem ? counterparty : key).getPubKeyHash(); data.creatorForeignPKH = (redeem ? key : counterparty).getPubKeyHash(); data.hashOfSecretA = Crypto.hash160(secret);
            byte[] script = BitcoinyHTLC.buildScript(data.partnerForeignPKH, data.lockTimeA, data.creatorForeignPKH, data.hashOfSecretA);
            String address = LegacyAddress.fromScriptHash(params, Crypto.hash160(script)).toString();
            when(coin.deriveP2shAddress(script)).thenReturn(address);
            Transaction funding = new Transaction(params); funding.addInput(Sha256Hash.ZERO_HASH, 7, new Script(new byte[0]));
            funding.addOutput(Coin.valueOf(100030900L), ScriptBuilder.createP2SHOutputScript(Crypto.hash160(script)));
            when(coin.getUnspentOutputs(address, false)).thenReturn(funding.getOutputs());
            ACCT acct = mock(ACCT.class); when(acct.getBlockchain()).thenReturn(coin);
            when(acct.populateTradeData(repository, at)).thenReturn(data); when(acct.findSecretA(repository, data)).thenReturn(secret);
            TradeBotData trade = new TradeBotData(key.getPrivKeyBytes(), "BitcoinACCTv3", redeem ? "BOB_DONE" : "ALICE_REFUNDING_A",
                    redeem ? 30 : 105, "account", "AT", 1L, 100L, new byte[32], new byte[20], "trade-address",
                    secret, data.hashOfSecretA, "BITCOIN", key.getPubKey(), key.getPubKeyHash(), 100000000L,
                    redeem ? null : "synthetic-wallet", null, data.lockTimeA, key.getPubKeyHash());
            when(cross.getAllTradeBotData()).thenReturn(List.of(trade)); when(cross.getTradeBotData(key.getPrivKeyBytes())).thenReturn(trade);
            try (MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class);
                 MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class);
                 MockedStatic<Security> security = mockStatic(Security.class);
                 MockedStatic<BitcoinyHTLC> status = mockStatic(BitcoinyHTLC.class, CALLS_REAL_METHODS);
                 MockedStatic<NTP> clock = mockStatic(NTP.class)) {
                repos.when(RepositoryManager::getRepository).thenReturn(repository);
                chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
                clock.when(NTP::getTime).thenReturn(1800000000000L);
                status.when(() -> BitcoinyHTLC.determineHtlcStatus(provider, address, data.expectedForeignAmount)).thenReturn(BitcoinyHTLC.Status.FUNDED);
                assertTrue(symbols[index], redeem ? new CrossChainHtlcResource().redeemAllHtlc("dummy") : new CrossChainHtlcResource().refundAllHtlc("dummy"));
                ArgumentCaptor<Transaction> spend = ArgumentCaptor.forClass(Transaction.class); verify(coin).broadcastTransaction(spend.capture());
                assertEquals(100000000L, spend.getValue().getOutput(0).getValue().value);
                spend.getValue().getInput(0).getScriptSig().correctlySpends(spend.getValue(), 0, funding.getOutput(0).getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
            }
        }
    }
}
