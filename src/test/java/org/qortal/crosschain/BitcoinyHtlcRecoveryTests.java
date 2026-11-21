package org.qortal.crosschain;

import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.*;
import org.mockito.MockedStatic;
import org.qortal.api.Security;
import org.qortal.api.model.crosschain.*;
import org.qortal.api.resource.CrossChainHtlcResource;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.*;
import org.qortal.repository.*;
import org.qortal.test.common.Common;
import org.qortal.utils.NTP;

import java.math.BigInteger;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exact public preview, authorization, signed bytes and durable uncertain-broadcast recovery. */
public class BitcoinyHtlcRecoveryTests extends Common {
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private Repository repository;
    private CrossChainRepository cross;
    private BitcoinyBlockchainProvider provider;
    private Bitcoiny coin;
    private CrossChainTradeData data;
    private ACCT acct;
    private ATData at;
    private TradeBotData trade;
    private Transaction funding;
    private List<TransactionOutput> unspent;
    private byte[] broadcast;
    private boolean failBroadcast, committed;
    private long feeRate = 100_000L, time = 1_795_500_000_000L;
    private String destination;
    private static final int LOCK = 1_795_000_000;
    private static final long AMOUNT = 500_000_000_000L;
    private final ECKey buyer = ECKey.fromPrivate(BigInteger.valueOf(2)), seller = ECKey.fromPrivate(BigInteger.valueOf(3));
    private final byte[] secret = new byte[32];

    @Before public void setup() throws Exception {
        Common.useDefaultSettings(); secret[0] = 47;
        repository = mock(Repository.class); cross = mock(CrossChainRepository.class);
        ATRepository ats = mock(ATRepository.class);
        when(repository.getCrossChainRepository()).thenReturn(cross); when(repository.getATRepository()).thenReturn(ats);
        when(cross.getTradeBotData(any(byte[].class))).thenAnswer(i -> trade);
        when(cross.getAllTradeBotData()).thenAnswer(i -> List.of(trade));
        doAnswer(i -> { committed = true; return null; }).when(repository).saveChanges();
        at = mock(ATData.class); when(at.getIsFinished()).thenReturn(true); when(at.getCodeHash()).thenReturn(new byte[32]);
        when(ats.fromATAddress("AT")).thenReturn(at);
        provider = mock(BitcoinyBlockchainProvider.class);
        when(provider.getNetId()).thenReturn("Digibyte-MAIN"); when(provider.getMinimumRelayFeePerKb()).thenAnswer(i -> feeRate);
        when(provider.getCurrentHeight()).thenReturn(100);
        coin = new Bitcoiny(provider, new Context(MainNetParams.get()), "DGB", Coin.valueOf(100_000)) {
            @Override public long getP2shFee(Long timestamp) { return 10_000; }
            @Override public long getFeeRequired() { return 10_000; }
            @Override public void setFeeRequired(long fee) { }
            @Override public int getMedianBlockTime() { return LOCK + 100; }
            @Override public List<TransactionOutput> getUnspentOutputs(String address, boolean pending) { return unspent; }
            @Override public String getUnusedReceiveAddress(String wallet) { return destination; }
            @Override public List<TransactionOutput> getOutputs(byte[] hash) { return funding.getOutputs(); }
        };
        when(provider.getRawTransaction(anyString())).thenAnswer(i -> {
            if (broadcast == null) throw new ForeignBlockchainException.NotFoundException();
            return broadcast;
        });
        doAnswer(i -> {
            assertTrue("Pending intent must commit before broadcast", committed);
            HtlcRecoveryState state = HtlcRecoveryState.decode(trade.getLastTransactionSignature());
            assertNotNull(state); assertFalse(state.confirmed);
            broadcast = i.getArgument(0); Transaction tx = new Transaction(MainNetParams.get(), broadcast);
            assertEquals(tx.getTxId().toString(), i.getArgument(1)); assertEquals(state.txid, i.getArgument(1));
            if (failBroadcast) throw new ForeignBlockchainException("fixture timeout after submit");
            return null;
        }).when(provider).broadcastTransaction(any(byte[].class), anyString());
        data = new CrossChainTradeData(); data.expectedForeignAmount = AMOUNT; data.lockTimeA = LOCK;
        data.partnerForeignPKH = buyer.getPubKeyHash(); data.creatorForeignPKH = seller.getPubKeyHash();
        data.hashOfSecretA = org.qortal.crypto.Crypto.hash160(secret);
        acct = mock(ACCT.class); when(acct.getBlockchain()).thenReturn(coin);
        when(acct.populateTradeData(repository, at)).thenReturn(data); when(acct.findSecretA(repository, data)).thenReturn(secret);
        MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class); statics.add(chains);
        chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
        chains.when(() -> SupportedBlockchain.getAcctByName(anyString())).thenReturn(acct);
        MockedStatic<NTP> clock = mockStatic(NTP.class); statics.add(clock); clock.when(NTP::getTime).thenAnswer(i -> time);
        MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class); statics.add(repos);
        repos.when(RepositoryManager::getRepository).thenReturn(repository);
        statics.add(mockStatic(Security.class));
        select(false);
    }
    @After public void closeMocks() { Collections.reverse(statics); statics.forEach(MockedStatic::close); }

    private void select(boolean refund) {
        ECKey key = refund ? buyer : seller;
        data.mode = refund ? AcctMode.REFUNDED : AcctMode.REDEEMED;
        destination = coin.pkhToAddress(key.getPubKeyHash());
        trade = new TradeBotData(key.getPrivKeyBytes(), "DigibyteACCTv3", refund ? "ALICE_REFUNDING_A" : "BOB_DONE",
                refund ? 105 : 30, "account", "AT", 1L, 100L, new byte[32], new byte[20], "trade-address",
                secret, data.hashOfSecretA, "DIGIBYTE", key.getPubKey(), key.getPubKeyHash(), AMOUNT,
                refund ? "public-synthetic-wallet" : null, null, LOCK, key.getPubKeyHash());
        setFunding(10_000);
    }
    private void setFunding(long reserve) {
        byte[] script = BitcoinyHTLC.buildScript(buyer.getPubKeyHash(), LOCK, seller.getPubKeyHash(), data.hashOfSecretA);
        funding = new Transaction(MainNetParams.get());
        funding.addInput(Sha256Hash.wrap("12".repeat(32)), 7, new Script(new byte[0]));
        funding.addOutput(Coin.valueOf(AMOUNT + reserve), ScriptBuilder.createP2SHOutputScript(org.qortal.crypto.Crypto.hash160(script)));
        unspent = List.of(funding.getOutput(0));
    }
    private HtlcRecoveryApproval approve(HtlcRecoveryQuote q) {
        HtlcRecoveryApproval a = new HtlcRecoveryApproval(); a.tradeAddress = q.tradeAddress; a.quoteId = q.quoteId; a.expiresAt = q.expiresAt; return a;
    }
    private interface CheckedCall { void run() throws Exception; }
    private void rejected(CheckedCall call) throws Exception {
        try { call.run(); fail("Unsafe recovery was allowed"); } catch (ForeignBlockchainException expected) { }
        assertNull(broadcast); verify(cross, never()).save(any(TradeBotData.class));
    }
    @Test public void previewIsExactPublicAndDoesNotPersistOrBroadcast() throws Exception {
        HtlcRecoveryQuote q = BitcoinyHtlcRecovery.preview(repository, trade, false);
        assertEquals("10000", q.originalReserve); assertEquals("30900", q.requiredFee);
        assertEquals("20900", q.additionalFee); assertEquals("499999979100", q.receiveAmount);
        assertEquals(destination, q.destination); assertEquals(funding.getTxId().toString(), q.fundingTxid);
        assertEquals(0, q.fundingOutputIndex); assertEquals(time + 300_000, q.expiresAt);
        assertNull(trade.getLastTransactionSignature()); assertNull(broadcast); verify(repository, never()).saveChanges();
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(q);
        assertFalse(json.contains("privateKey")); assertFalse(json.contains("secret"));
    }
    @Test public void exactRedeemApprovalSignsOnlyReviewedPayout() throws Exception { submit(false); }
    @Test public void exactRefundApprovalUsesOwnWalletAndLocktime() throws Exception { submit(true); }
    private void submit(boolean refund) throws Exception {
        select(refund); HtlcRecoveryQuote q = BitcoinyHtlcRecovery.preview(repository, trade, refund);
        assertTrue(BitcoinyHtlcRecovery.submit(repository, trade, refund, approve(q)));
        Transaction tx = new Transaction(MainNetParams.get(), broadcast);
        assertEquals(Long.parseLong(q.receiveAmount), tx.getOutput(0).getValue().value);
        assertEquals(refund ? LOCK : 0L, tx.getLockTime());
        tx.getInput(0).getScriptSig().correctlySpends(tx, 0, funding.getOutput(0).getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
    }
    @Test public void changedFeeRejectsStaleApproval() throws Exception {
        var a = approve(BitcoinyHtlcRecovery.preview(repository, trade, false)); feeRate *= 2;
        rejected(() -> BitcoinyHtlcRecovery.submit(repository, trade, false, a));
    }
    @Test public void changedOutpointRejectsApproval() throws Exception {
        var a = approve(BitcoinyHtlcRecovery.preview(repository, trade, false)); setFunding(10_001);
        rejected(() -> BitcoinyHtlcRecovery.submit(repository, trade, false, a));
    }
    @Test public void changedDestinationRejectsApproval() throws Exception {
        var a = approve(BitcoinyHtlcRecovery.preview(repository, trade, false)); trade.getReceivingAccountInfo()[0] ^= 1;
        rejected(() -> BitcoinyHtlcRecovery.submit(repository, trade, false, a));
    }
    @Test public void expiredOrWrongRoleApprovalCannotSign() throws Exception {
        var a = approve(BitcoinyHtlcRecovery.preview(repository, trade, false)); time = a.expiresAt;
        rejected(() -> BitcoinyHtlcRecovery.submit(repository, trade, false, a));
        time--; rejected(() -> BitcoinyHtlcRecovery.submit(repository, trade, true, a));
    }
    @Test public void competingFundingOutputsRequireInvestigation() throws Exception {
        TransactionOutput original = funding.getOutput(0); setFunding(20_000);
        unspent = List.of(original, funding.getOutput(0));
        rejected(() -> BitcoinyHtlcRecovery.preview(repository, trade, false));
    }
    @Test public void knownOutpointCannotSwitchToAnotherDeposit() throws Exception {
        trade.setFundingTxid("22".repeat(32)); trade.setFundingOutputIndex(0);
        rejected(() -> BitcoinyHtlcRecovery.preview(repository, trade, false));
    }
    @Test public void timeoutPersistsIntentAndBlocksRestartReplacement() throws Exception {
        var a = approve(BitcoinyHtlcRecovery.preview(repository, trade, false)); failBroadcast = true;
        try { BitcoinyHtlcRecovery.submit(repository, trade, false, a); fail(); } catch (ForeignBlockchainException expected) { }
        TradeBotData reopened = TradeBotData.fromJson(trade.toJson()); trade = reopened;
        HtlcRecoveryState state = HtlcRecoveryState.decode(reopened.getLastTransactionSignature()); assertNotNull(state);
        assertTrue(BitcoinyHtlcRecovery.holdPending(repository, reopened));
        try { BitcoinyHtlcRecovery.submit(repository, reopened, false, a); fail(); } catch (ForeignBlockchainException expected) { }
        verify(provider, times(1)).broadcastTransaction(any(byte[].class), anyString());
    }
    @Test public void exactPendingSpendNeedsThreeConfirmations() throws Exception {
        submit(false); String txid = HtlcRecoveryState.decode(trade.getLastTransactionSignature()).txid;
        when(provider.getAddressTransactions(any(byte[].class), eq(true))).thenReturn(List.of(new TransactionHash(99, txid)));
        assertTrue(BitcoinyHtlcRecovery.holdPending(repository, trade));
        when(provider.getCurrentHeight()).thenReturn(101);
        assertFalse(BitcoinyHtlcRecovery.holdPending(repository, trade));
        assertTrue(HtlcRecoveryState.decode(trade.getLastTransactionSignature()).confirmed);
    }
    @Test public void wrongPendingTransactionCannotClearIntent() throws Exception {
        submit(false); broadcast = funding.bitcoinSerialize();
        try { BitcoinyHtlcRecovery.holdPending(repository, trade); fail(); } catch (ForeignBlockchainException expected) { }
        assertFalse(HtlcRecoveryState.decode(trade.getLastTransactionSignature()).confirmed);
    }
    @Test public void apiPreviewAndExplicitPostUseMatchingRecord() throws Exception {
        var resource = new CrossChainHtlcResource();
        var quote = resource.previewRedeem("test", "AT", trade.getTradeNativeAddress()).get(0);
        assertTrue(resource.redeemHtlc("test", "AT", approve(quote)));
        assertNotNull(broadcast);
    }
    @Test public void batchCannotApproveAdditionalFees() throws Exception {
        String address = coin.deriveP2shAddress(BitcoinyHTLC.buildScript(buyer.getPubKeyHash(), LOCK, seller.getPubKeyHash(), data.hashOfSecretA));
        try (MockedStatic<BitcoinyHTLC> status = mockStatic(BitcoinyHTLC.class, CALLS_REAL_METHODS)) {
            status.when(() -> BitcoinyHTLC.determineHtlcStatus(provider, address, AMOUNT)).thenReturn(BitcoinyHTLC.Status.FUNDED);
            assertFalse(new CrossChainHtlcResource().redeemAllHtlc("test"));
            assertNull(broadcast); verify(cross, never()).save(any(TradeBotData.class));
        }
    }
}
