package org.qortal.controller.tradebot;

import org.junit.*;
import org.mockito.MockedStatic;
import org.qortal.api.Security;
import org.qortal.api.resource.CrossChainHtlcResource;
import org.qortal.crosschain.*;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.*;
import org.qortal.repository.*;
import org.qortal.test.common.Common;
import org.qortal.utils.NTP;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual scheduler/API refund methods; repository, clock, network and JNI boundary are mocked. */
public class PirateChainRefundTests extends Common {
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private Repository repository;
    private CrossChainRepository cross;
    private PirateChain pirate;
    private BitcoinyBlockchainProvider chain;
    private ATData at;
    private CrossChainTradeData data;
    private MockedStatic<PirateChainHTLC> htlc;
    private MockedStatic<NTP> clock;
    private final PirateChainACCTv3TradeBot bot = PirateChainACCTv3TradeBot.getInstance();
    private static final String TXID = "11".repeat(32);

    @Before public void setup() throws Exception {
        Common.useDefaultSettings();
        repository = mock(Repository.class);
        cross = mock(CrossChainRepository.class);
        when(repository.getCrossChainRepository()).thenReturn(cross);
        when(cross.getTradeBotData(any(byte[].class))).thenAnswer(call -> cross.getAllTradeBotData().stream()
                .filter(row -> Arrays.equals(row.getTradePrivateKey(), call.getArgument(0))).findFirst().orElse(null));
        ATRepository ats = mock(ATRepository.class);
        when(repository.getATRepository()).thenReturn(ats);
        at = mock(ATData.class);
        when(at.getIsFinished()).thenReturn(true);
        when(at.getCodeHash()).thenReturn(new byte[32]);
        when(ats.fromATAddress("AT")).thenReturn(at);
        pirate = mock(PirateChain.class);
        chain = mock(BitcoinyBlockchainProvider.class);
        when(pirate.getBlockchainProvider()).thenReturn(chain);
        when(chain.getCurrentHeight()).thenReturn(100);
        when(pirate.getMedianBlockTime()).thenReturn(100);
        when(pirate.getP2shFee(any())).thenReturn(10000L);
        when(pirate.getCurrencyCode()).thenReturn("ARRR");
        when(pirate.getWalletAddress(anyString())).thenAnswer(i -> "address-" + i.getArgument(0));
        when(pirate.deriveP2shAddressBPrefix(any())).thenAnswer(i -> "b-" + ((byte[]) i.getArgument(0))[0]);
        when(pirate.deriveP2shAddress(any())).thenAnswer(i -> "t3-" + ((byte[]) i.getArgument(0))[0]);
        when(pirate.refundP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyInt())).thenReturn(TXID);
        when(pirate.redeemP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt())).thenReturn(TXID);
        data = new CrossChainTradeData();
        data.mode = AcctMode.REFUNDED; data.expectedForeignAmount = 100000000L;
        data.tradeTimeout = 60; data.creatorForeignPKH = new byte[33];
        MockedStatic<PirateChain> pirates = mockStatic(PirateChain.class); statics.add(pirates);
        pirates.when(PirateChain::getInstance).thenReturn(pirate);
        htlc = mockStatic(PirateChainHTLC.class); statics.add(htlc);
        htlc.when(() -> PirateChainHTLC.fundingTxid(any())).thenCallRealMethod();
        htlc.when(() -> PirateChainHTLC.determineHtlcStatus(any(), anyString(), anyLong(), anyInt(), anyString(), anyInt()))
                .thenAnswer(i -> (int) i.getArgument(3) == 1
                        ? PirateChainHTLC.determineHtlcStatus(i.getArgument(0), i.getArgument(1), i.getArgument(2))
                        : PirateChainHTLC.determineHtlcStatus(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong(), anyString(), anyInt()))
                .thenAnswer(i -> PirateChainHTLC.getUnspentFundingOutput(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        htlc.when(() -> PirateChainHTLC.buildScript(any(), anyInt(), any(), any())).thenAnswer(i -> new byte[]{ ((byte[])i.getArgument(0))[0] });
        status(BitcoinyHTLC.Status.FUNDED);
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(funding(0, 10000L));
        clock = mockStatic(NTP.class); statics.add(clock);
        clock.when(NTP::getTime).thenReturn(1_000_000L);
    }
    @After public void cleanupMocks() { Collections.reverse(statics); statics.forEach(MockedStatic::close); }
    private void status(BitcoinyHTLC.Status value) {
        htlc.when(() -> PirateChainHTLC.determineHtlcStatus(any(), anyString(), anyLong(), eq(3))).thenReturn(value);
    }
    @Test public void waitingOfferRetriesOnlyItsMessageAndKeepsOneSubmissionWorker() throws Exception {
        PirateChainACCTv3TradeBot recovery = spy(freshBot());
        List<Runnable> queued = new ArrayList<>();
        doAnswer(i -> { queued.add(i.getArgument(0)); return null; })
                .when(recovery).startFundingAnnouncementWorker(any());
        TradeBotData attempt = trade(1);
        attempt.setState("ALICE_WAITING_FOR_AT_LOCK"); attempt.setStateValue(85); attempt.setLockTimeA(2000);
        when(at.getIsFinished()).thenReturn(false);
        data.mode = AcctMode.OFFERING; data.qortalCreatorTradeAddress = "creator";
        org.qortal.repository.MessageRepository messages = mock(org.qortal.repository.MessageRepository.class);
        when(repository.getMessageRepository()).thenReturn(messages);
        when(messages.exists(any(), anyString(), any())).thenThrow(new DataException("temporary message repository failure")).thenReturn(false);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            try { recovery.progress(repository, attempt); fail(); } catch (DataException expected) { }
            for (int tip = 0; tip < 4; tip++) recovery.progress(repository, attempt);
            assertEquals(1, queued.size());
            try (MockedStatic<RepositoryManager> repositories = mockStatic(RepositoryManager.class)) {
                repositories.when(RepositoryManager::getRepository).thenThrow(new DataException("worker repository unavailable"));
                queued.get(0).run();
            }
            recovery.progress(repository, attempt);
            assertEquals(2, queued.size());
        }
        assertEquals("ALICE_WAITING_FOR_AT_LOCK", attempt.getState());
        verify(pirate, never()).fundP2SH(anyString(), anyString(), anyLong(), anyString());
        noBroadcast();
    }

    @Test public void ambiguousLegacyFundingNeverConfirmsOrDeletesTheRecoveryRecord() throws Exception {
        TradeBotData legacy = trade(1); legacy.setState("ALICE_REFUNDED"); legacy.setStateValue(110);
        legacy.setLastTransactionSignature(new PirateChainRefundState(0, 1L, TXID, true).encode());
        htlc.when(() -> PirateChainHTLC.countFundingOutputs(any(), anyString(), anyLong())).thenReturn(2);
        status(BitcoinyHTLC.Status.REFUNDED);
        assertFalse(bot.refund(repository, legacy, at, data));
        assertFalse(legacy.hasFundingOutpoint());
        assertFalse(bot.canDelete(repository, legacy));
        assertEquals(Boolean.FALSE, legacy.getForeignRefundConfirmed());
        verify(cross, never()).delete(any());
        noBroadcast();
    }
    private TradeBotData trade(int id) {
        byte[] key = new byte[32]; key[0]=(byte)id;
        byte[] pub = new byte[33]; pub[0]=(byte)id;
        return new TradeBotData(key, PirateChainACCTv3.NAME, "ALICE_REFUNDING_A", 105, "account-"+id, "AT", 1L, 100L,
                new byte[32], new byte[20], "native-"+id, new byte[32], new byte[20], "PIRATECHAIN", pub,
                new byte[20], 100000000L, "wallet-"+id, null, 1, new byte[25]);
    }
    private void noBroadcast() throws Exception {
        verify(pirate, never()).refundP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyInt());
    }
    @Test public void disabledWalletSkipsEverySchedulerStateBeforeRepositoryAccess() throws Exception {
        org.mockito.Mockito.clearInvocations(repository, cross, chain, pirate);
        @SuppressWarnings("unchecked")
        MockedStatic<PirateChain> pirates = (MockedStatic<PirateChain>) statics.get(0);
        pirates.when(PirateChain::getInstance).thenReturn(null);
        for (TradeStates.State state : TradeStates.State.values()) {
            TradeBotData disabled = trade(99);
            disabled.setState(state.name()); disabled.setStateValue(state.value);
            freshBot().progress(repository, disabled);
        }
        verifyNoInteractions(repository, cross, chain, pirate);
    }
    @Test public void acknowledgementStaysPendingAndCheckpointSurvivesBackup() throws Exception {
        TradeBotData trade = trade(1);
        assertTrue(bot.refund(repository, trade, at, data));
        assertEquals("ALICE_REFUNDING_A", trade.getState());
        TradeBotData restored = TradeBotData.fromJson(trade.toJson());
        assertEquals(64, restored.getLastTransactionSignature().length);
        assertEquals(TXID, PirateChainRefundState.decode(restored.getLastTransactionSignature()).txid);
        clearInvocations(pirate);
        assertFalse(bot.refund(repository, restored, at, data));
        noBroadcast();
    }
    @Test public void reservationIsCommittedBeforeNativeCallAndLostAckIsQuarantined() throws Exception {
        TradeBotData trade = trade(1);
        doAnswer(i -> {
            assertNotNull(PirateChainRefundState.decode(trade.getLastTransactionSignature()));
            verify(repository, atLeastOnce()).saveChanges();
            throw new ForeignBlockchainException("lost acknowledgement");
        }).when(pirate).refundP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyInt());
        try { bot.refund(repository, trade, at, data); fail(); } catch (ForeignBlockchainException expected) { }
        assertEquals("ALICE_REFUNDING_A", trade.getState());
        clearInvocations(pirate);
        assertFalse(bot.refund(repository, TradeBotData.fromJson(trade.toJson()), at, data)); noBroadcast();
    }
    private UnspentOutput funding(int index, long fee) {
        byte[] hash = new byte[32]; Arrays.fill(hash, (byte)0x22);
        return new UnspentOutput(hash, index, 90, data.expectedForeignAmount + fee);
    }
    @Test public void originalFundingFeeAndNonzeroOutputSurviveFeeDrift() throws Exception {
        TradeBotData trade = trade(1); trade.setFundingFee(20000L);
        when(pirate.getP2shFee(any())).thenReturn(50000L);
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), eq(100020000L)))
                .thenReturn(funding(2, 20000L));
        assertTrue(bot.refund(repository, trade, at, data));
        verify(pirate).refundP2sh(anyString(), anyString(), eq(100000000L), anyString(), anyString(), anyInt(), anyString(), eq(20000L), eq(2));
        htlc.verify(() -> PirateChainHTLC.determineHtlcStatus(chain, "b-1", 100020000L, 3));
        assertEquals(2, trade.getFundingOutputIndex());
    }
    @Test public void legacyReserveIsRecoveredFromTheFundingOutput() throws Exception {
        TradeBotData trade = trade(1);
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong()))
                .thenReturn(funding(2, 20000L));
        assertTrue(bot.refund(repository, trade, at, data));
        assertEquals(Long.valueOf(20000), trade.getFundingFee());
        verify(pirate).refundP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), eq(20000L), eq(2));
    }
    @Test public void legacyReserveBelowDefaultIsRecoveredBeforeStatusCheck() throws Exception {
        TradeBotData trade = trade(1);
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(funding(0, 5000L));
        assertTrue(bot.refund(repository, trade, at, data));
        htlc.verify(() -> PirateChainHTLC.determineHtlcStatus(chain, "b-1", 100005000L, 3));
        verify(pirate).refundP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), eq(5000L), eq(0));
    }
    @Test public void deterministicRejectionClearsPersistedCheckpointAndAllowsImmediateRetry() throws Exception {
        TradeBotData trade = trade(1);
        when(pirate.refundP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyInt()))
                .thenThrow(new ForeignBlockchainException.TransactionRejectedException("Funding output value exceeds outputs plus fee"))
                .thenReturn(TXID);
        try { bot.refund(repository, trade, at, data); fail(); }
        catch (ForeignBlockchainException.TransactionRejectedException expected) { }
        assertNull(trade.getLastTransactionSignature());
        verify(cross, atLeastOnce()).save(trade);
        verify(repository, times(3)).saveChanges();
        assertTrue(bot.refund(repository, TradeBotData.fromJson(trade.toJson()), at, data));
    }
    @Test public void transientNetworkFailureKeepsCheckpoint() throws Exception {
        TradeBotData trade = trade(1);
        when(pirate.refundP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyInt()))
                .thenThrow(new ForeignBlockchainException.NetworkException("timeout"));
        try { bot.refund(repository, trade, at, data); fail(); } catch (ForeignBlockchainException.NetworkException expected) { }
        assertNotNull(PirateChainRefundState.decode(trade.getLastTransactionSignature()));
        clearInvocations(pirate);
        assertFalse(bot.refund(repository, trade, at, data)); noBroadcast();
    }
    @Test public void sellerRecoversOriginalReserveAndOutputIndexBeforeRedeeming() throws Exception {
        TradeBotData seller = trade(1); seller.setState("BOB_WAITING_FOR_AT_REDEEM"); seller.setStateValue(25);
        data.mode = AcctMode.REDEEMED; data.lockTimeA = 1; data.partnerForeignPKH = new byte[33];
        data.creatorForeignPKH = seller.getTradeForeignPublicKey();
        data.hashOfSecretA = org.qortal.crypto.Crypto.hash160(new byte[32]);
        htlc.when(() -> PirateChainHTLC.determineHtlcStatus(any(), anyString(), anyLong())).thenReturn(BitcoinyHTLC.Status.FUNDED);
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(funding(2, 20000L));
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.findSecretA(repository, data)).thenReturn(new byte[32]);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        byte[] receiving = PirateChainAddress.toReceivingAccountInfo("zs1mj80tkyjqjhc84aquetymwgrasxjdkkft0qcn5xtp2p9r42vs5ernyv4nzgufmz6xzuqvw20w9z");
        java.lang.reflect.Field field = TradeBotData.class.getDeclaredField("receivingAccountInfo"); field.setAccessible(true); field.set(seller, receiving);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            bot.progress(repository, seller);
        }
        verify(pirate).redeemP2sh(anyString(), anyString(), eq(100000000L), anyString(), anyString(), anyString(), anyString(), eq(20000L), eq(2));
        assertEquals(Long.valueOf(20000), seller.getFundingFee());
        assertEquals(2, seller.getFundingOutputIndex());
    }
    @Test public void confirmedRefundIsTerminal() throws Exception {
        TradeBotData trade = trade(1); status(BitcoinyHTLC.Status.REFUNDED);
        assertFalse(bot.refund(repository, trade, at, data));
        assertEquals("ALICE_REFUNDED", trade.getState()); noBroadcast();
    }
    @Test public void shallowRefundAndSellerSpendAreNotTerminal() throws Exception {
        for (BitcoinyHTLC.Status value : List.of(BitcoinyHTLC.Status.REFUND_IN_PROGRESS, BitcoinyHTLC.Status.REDEEM_IN_PROGRESS)) {
            TradeBotData trade = trade(1); status(value);
            assertFalse(bot.refund(repository, trade, at, data)); assertEquals("ALICE_REFUNDING_A", trade.getState());
        }
        noBroadcast();
    }
    @Test public void confirmedSellerSpendWins() throws Exception {
        TradeBotData trade = trade(1); status(BitcoinyHTLC.Status.REDEEMED);
        assertFalse(bot.refund(repository, trade, at, data)); assertEquals("ALICE_DONE", trade.getState()); noBroadcast();
    }
    @Test public void maturityAndMissingClockPreventBroadcast() throws Exception {
        when(pirate.getMedianBlockTime()).thenReturn(1);
        assertFalse(bot.refund(repository, trade(1), at, data));
        clock.when(NTP::getTime).thenReturn(null);
        assertFalse(bot.refund(repository, trade(1), at, data)); noBroadcast();
    }
    @Test public void expiredRefundIsRetriedOnlyAfterBlockAndTimeQuarantine() throws Exception {
        TradeBotData trade = trade(1);
        trade.setLastTransactionSignature(new PirateChainRefundState(70, 0L, TXID).encode());
        byte[] raw = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(0x80000006).putInt(0xd884b698).putInt(0x37a5165b).putInt(1).putInt(90).put((byte)0).put((byte)0).array();
        when(chain.getRawTransaction(any(byte[].class))).thenReturn(raw);
        assertTrue(bot.refund(repository, trade, at, data));
    }
    @Test public void stillLiveTransactionAndLookupFailurePreventReplacement() throws Exception {
        TradeBotData trade = trade(1);
        trade.setLastTransactionSignature(new PirateChainRefundState(70, 0L, TXID).encode());
        byte[] raw = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(0x80000006).putInt(0xd884b698).putInt(0x37a5165b).putInt(1).putInt(200).put((byte)0).put((byte)0).array();
        when(chain.getRawTransaction(any(byte[].class))).thenReturn(raw);
        assertFalse(bot.refund(repository, trade, at, data));
        when(chain.getRawTransaction(any(byte[].class))).thenThrow(new ForeignBlockchainException("network unavailable"));
        try { bot.refund(repository, trade, at, data); fail(); } catch (ForeignBlockchainException expected) { }
        noBroadcast();
    }
    @Test public void unknownAckCanRetryAfterQuarantineButMissingUtxoCannot() throws Exception {
        TradeBotData trade = trade(1); trade.setLastTransactionSignature(new PirateChainRefundState(70, 0L, null).encode());
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(null);
        assertFalse(bot.refund(repository, trade, at, data)); noBroadcast();
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(funding(0, 10000L));
        assertTrue(bot.refund(repository, trade, at, data));
    }
    @Test public void manualRecoveryKeepsDestinationsSeparateAndAllowsLosingBuyer() throws Exception {
        TradeBotData first = trade(1), second = trade(2);
        when(cross.getAllTradeBotData()).thenReturn(List.of(first, second));
        htlc.when(() -> PirateChainHTLC.determineHtlcStatus(any(), eq("b-1"), anyLong(), eq(3))).thenReturn(BitcoinyHTLC.Status.REFUNDED);
        ACCT acct = mock(ACCT.class);
        when(acct.getBlockchain()).thenReturn(pirate); when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class);
             MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class);
             MockedStatic<Security> security = mockStatic(Security.class)) {
            repos.when(RepositoryManager::getRepository).thenReturn(repository);
            chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
            data.mode = AcctMode.REDEEMED; data.qortalPartnerAddress = "other-winner";
            assertTrue(new CrossChainHtlcResource().refundHtlc("dummy", "AT"));
            verify(pirate).refundP2sh(eq("t3-2"), eq("address-wallet-2"), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyInt());
            verify(pirate, never()).getWalletAddress("wallet-1");
        }
    }
    @Test public void successfulBuyerAndMalformedOrSellerRecordCannotRefund() throws Exception {
        data.mode = AcctMode.REDEEMED; data.qortalPartnerAddress = "native-1";
        assertFalse(bot.refund(repository, trade(1), at, data));
        TradeBotData seller = trade(2); seller.setState("BOB_DONE");
        assertFalse(bot.refund(repository, seller, at, data)); noBroadcast();
    }
    @Test public void nativeTxidIsConvertedToLightwalletdByteOrder() {
        String display = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        byte[] hash = new PirateChainRefundState(1, 1, display).transactionHash();
        assertEquals(31, hash[0]); assertEquals(0, hash[31]);
    }
    @Test public void apiDoesNotAdvertiseLegacyAckAsConfirmed() throws Exception {
        TradeBotData legacy=trade(1); legacy.setState("ALICE_REFUNDED");
        assertEquals(Boolean.FALSE, legacy.getForeignRefundConfirmed());
        legacy.setLastTransactionSignature(new PirateChainRefundState(130, 1L, TXID).encode());
        assertEquals(Boolean.FALSE, legacy.getForeignRefundConfirmed());
        legacy.setLastTransactionSignature(new PirateChainRefundState(0, 1L, null, true).encode());
        assertEquals(Boolean.FALSE, legacy.getForeignRefundConfirmed()); // Old script-only confirmation is insufficient.
        legacy.setFundingTxid("22".repeat(32));
        assertEquals(Boolean.TRUE, legacy.getForeignRefundConfirmed());
        var context = org.eclipse.persistence.jaxb.JAXBContextFactory.createContext(new Class[]{TradeBotData.class}, null);
        var marshaller = context.createMarshaller();
        marshaller.setProperty(org.eclipse.persistence.jaxb.MarshallerProperties.MEDIA_TYPE, "application/json");
        marshaller.setProperty(org.eclipse.persistence.jaxb.MarshallerProperties.JSON_INCLUDE_ROOT, false);
        var json = new java.io.StringWriter(); marshaller.marshal(legacy, json);
        assertTrue(new org.json.JSONObject(json.toString()).getBoolean("foreignRefundConfirmed"));
        legacy.setState("ALICE_REFUNDING_A"); assertNull(legacy.getForeignRefundConfirmed());
    }
    @Test public void activeRefundCannotBeDeletedEvenWhenAtIsMissing() throws Exception {
        assertFalse(bot.canDelete(repository, trade(1)));
    }
    @Test public void unauditedLegacyRefundCannotBeDeleted() throws Exception {
        TradeBotData legacy=trade(1); legacy.setState("ALICE_REFUNDED"); legacy.setStateValue(110);
        assertFalse(bot.canDelete(repository, legacy));
    }
    @Test public void staleCallerUsesTheLatestCommittedCheckpoint() throws Exception {
        TradeBotData stale = trade(1), committed = trade(1);
        committed.setLastTransactionSignature(new PirateChainRefundState(130, 999999L, TXID).encode());
        doReturn(committed).when(cross).getTradeBotData(stale.getTradePrivateKey());
        assertFalse(bot.refund(repository, stale, at, data)); noBroadcast();
    }
    @Test public void legacyAckOnlyTerminalIsReopenedWithoutBlindBroadcast() throws Exception {
        TradeBotData legacy = trade(1); legacy.setState("ALICE_REFUNDED"); legacy.setStateValue(110);
        assertFalse(bot.refund(repository, legacy, at, data));
        assertEquals("ALICE_REFUNDING_A", legacy.getState());
        assertNotNull(PirateChainRefundState.decode(legacy.getLastTransactionSignature())); noBroadcast();
    }
    @Test public void confirmedLegacyTerminalReceivesPersistentAuditMarker() throws Exception {
        TradeBotData legacy = trade(1); legacy.setState("ALICE_REFUNDED"); legacy.setStateValue(110);
        status(BitcoinyHTLC.Status.REFUNDED);
        assertFalse(bot.refund(repository, legacy, at, data));
        assertEquals("ALICE_REFUNDED", legacy.getState());
        assertNotNull(PirateChainRefundState.decode(legacy.getLastTransactionSignature())); noBroadcast();
    }
    @Test public void schedulerAuditsLegacyTerminalsGradually() throws Exception {
        var constructor = PirateChainACCTv3TradeBot.class.getDeclaredConstructor(); constructor.setAccessible(true);
        PirateChainACCTv3TradeBot freshBot = constructor.newInstance();
        TradeBotData first = trade(1), second = trade(2);
        first.setState("ALICE_REFUNDED"); first.setStateValue(110);
        first.setLastTransactionSignature(new PirateChainRefundState(130, 1L, TXID).encode());
        second.setState("ALICE_REFUNDED"); second.setStateValue(110);
        when(cross.getAllTradeBotData()).thenReturn(List.of(first, second));
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            freshBot.progress(repository, first);
            freshBot.progress(repository, second);
            assertEquals("ALICE_REFUNDING_A", first.getState());
            assertEquals("ALICE_REFUNDED", second.getState());
            clock.when(NTP::getTime).thenReturn(1_060_001L);
            freshBot.progress(repository, second);
            assertEquals("ALICE_REFUNDING_A", second.getState()); noBroadcast();
        }
    }
    @Test public void unavailableAtsCannotStarveTheSixthLegacyRecordInStableOrder() throws Exception {
        PirateChainACCTv3TradeBot fresh = freshBot();
        List<TradeBotData> records = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            TradeBotData candidate = trade(i);
            candidate.setState("ALICE_REFUNDED"); candidate.setStateValue(110);
            candidate.setAtAddress(i == 6 ? "AT" : "missing-" + i);
            records.add(candidate);
        }
        when(cross.getAllTradeBotData()).thenReturn(records);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            for (int minute = 0; minute < 30; minute++) {
                final long now = 1_000_000L + minute * 60_001L;
                clock.when(NTP::getTime).thenReturn(now);
                for (TradeBotData candidate : records) fresh.progress(repository, candidate);
            }
        }
        assertEquals("ALICE_REFUNDING_A", records.get(5).getState());
        verify(acct, atLeastOnce()).populateTradeData(repository, at);
        noBroadcast();
    }
    @Test public void ambiguousConfirmedLegacyRecordCannotStarveALaterRecoverableRecord() throws Exception {
        for (boolean reverse : List.of(false, true)) {
            PirateChainACCTv3TradeBot fresh = freshBot();
            TradeBotData ambiguous = trade(1), recoverable = trade(2);
            for (TradeBotData candidate : List.of(ambiguous, recoverable)) {
                candidate.setState("ALICE_REFUNDED"); candidate.setStateValue(110);
            }
            ambiguous.setLastTransactionSignature(new PirateChainRefundState(0, 1L, TXID, true).encode());
            htlc.when(() -> PirateChainHTLC.countFundingOutputs(any(), eq("b-1"), anyLong())).thenReturn(2);
            List<TradeBotData> records = reverse ? List.of(recoverable, ambiguous) : List.of(ambiguous, recoverable);
            when(cross.getAllTradeBotData()).thenReturn(records);
            PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
            when(acct.populateTradeData(repository, at)).thenReturn(data);
            try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
                accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
                for (int minute = 0; minute < 30; minute++) {
                    clock.when(NTP::getTime).thenReturn(1_000_000L + minute * 60_001L);
                    for (TradeBotData candidate : records) fresh.progress(repository, candidate);
                }
            }
            assertEquals("ALICE_REFUNDED", ambiguous.getState()); assertFalse(ambiguous.hasFundingOutpoint());
            assertTrue(PirateChainRefundState.isConfirmed(ambiguous.getLastTransactionSignature()));
            assertEquals("ALICE_REFUNDING_A", recoverable.getState()); assertTrue(recoverable.hasFundingOutpoint());
        }
        htlc.verify(() -> PirateChainHTLC.countFundingOutputs(any(), eq("b-1"), anyLong()), times(2));
        noBroadcast();
    }

    @Test public void spentOfferCandidatesDoNotCompleteSellerOrHideALaterFundedCandidate() throws Exception {
        for (BitcoinyHTLC.Status spent : List.of(BitcoinyHTLC.Status.REDEEM_IN_PROGRESS, BitcoinyHTLC.Status.REDEEMED)) {
            TradeBotData seller = seller(3, SAPLING);
            seller.setState("BOB_WAITING_FOR_MESSAGE"); seller.setStateValue(15);
            when(at.getIsFinished()).thenReturn(false); data.mode = AcctMode.OFFERING;
            MessageRepository messages = mock(MessageRepository.class);
            when(repository.getMessageRepository()).thenReturn(messages);
            var first = mock(org.qortal.data.transaction.MessageTransactionData.class);
            var second = mock(org.qortal.data.transaction.MessageTransactionData.class);
            for (var message : List.of(first, second)) {
                byte[] partner = new byte[33]; partner[0] = (byte) (message == first ? 1 : 2);
                when(message.getData()).thenReturn(org.qortal.api.resource.CrossChainUtils.buildOfferMessage(partner, new byte[20], 2000));
                when(message.getTimestamp()).thenReturn(1_000_000L);
                when(message.getCreatorPublicKey()).thenReturn(new byte[32]);
            }
            when(messages.getMessagesByParticipants(any(), any(), any(), any(), any())).thenReturn(List.of(first));
            when(messages.exists(any(), any(), any())).thenReturn(true); // Later candidate's lock message already sent.
            htlc.when(() -> PirateChainHTLC.determineHtlcStatus(any(), eq("b-1"), anyLong())).thenReturn(spent);
            htlc.when(() -> PirateChainHTLC.determineHtlcStatus(any(), eq("b-2"), anyLong())).thenReturn(BitcoinyHTLC.Status.FUNDED);
            PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
            when(acct.populateTradeData(repository, at)).thenReturn(data);
            try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class, CALLS_REAL_METHODS);
                 MockedStatic<TradeBot> bots = mockStatic(TradeBot.class, CALLS_REAL_METHODS)) {
                accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
                bots.when(TradeBot::getInstance).thenReturn(mock(TradeBot.class));
                PirateChainACCTv3TradeBot fresh = freshBot();
                fresh.progress(repository, seller);
                assertEquals("BOB_WAITING_FOR_MESSAGE", seller.getState());
                verify(cross, never()).save(seller);
                when(messages.getMessagesByParticipants(any(), any(), any(), any(), any())).thenReturn(List.of(first, second));
                fresh.progress(repository, seller);
                assertEquals("BOB_WAITING_FOR_AT_REDEEM", seller.getState());
            }
        }
    }

    @Test public void partialAcknowledgementNeverBindsAnotherTransaction() throws Exception {
        TradeBotData attempt = trade(1); attempt.setFundingTxid("33".repeat(32)); attempt.setFundingOutputIndex(-1);
        htlc.when(() -> PirateChainHTLC.getFundingOutput(any(), eq(attempt.getFundingTxid()), anyString(), anyLong()))
                .thenReturn(funding(2, 10000L)); // Deliberately wrong transaction returned by the boundary fixture.
        try {
            bot.refund(repository, attempt, at, data); fail("Must retain the acknowledged transaction ID");
        } catch (ForeignBlockchainException expected) {
            assertTrue(expected.getMessage().contains("outpoint changed"));
        }
        assertEquals("33".repeat(32), attempt.getFundingTxid()); assertEquals(-1, attempt.getFundingOutputIndex());
        assertFalse(attempt.hasFundingOutpoint());
        htlc.verify(() -> PirateChainHTLC.countFundingOutputs(any(), anyString(), anyLong()), never());
        noBroadcast();
    }

    @Test public void refundAllContinuesAfterOneWalletFails() throws Exception {
        TradeBotData malformed = trade(3);
        var pub = TradeBotData.class.getDeclaredField("tradeForeignPublicKey"); pub.setAccessible(true); pub.set(malformed, null);
        when(cross.getAllTradeBotData()).thenReturn(List.of(malformed, trade(1), trade(2)));
        when(pirate.getWalletAddress("wallet-1")).thenThrow(new ForeignBlockchainException("wallet unavailable"));
        ACCT acct = mock(ACCT.class);
        when(acct.getBlockchain()).thenReturn(pirate); when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class);
             MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class);
             MockedStatic<Security> security = mockStatic(Security.class)) {
            repos.when(RepositoryManager::getRepository).thenReturn(repository);
            chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
            assertTrue(new CrossChainHtlcResource().refundAllHtlc("dummy"));
            verify(pirate).refundP2sh(eq("t3-2"), eq("address-wallet-2"), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyInt());
            verify(pirate, never()).getWalletAddress("wallet-3");
        }
    }
    @Test public void refundAllLogsFullFailuresAtErrorAndPreservesBooleanResponse() throws Exception {
        when(cross.getAllTradeBotData()).thenReturn(List.of(trade(1)));
        ForeignBlockchainException failure = new ForeignBlockchainException("wallet unavailable");
        when(pirate.getWalletAddress("wallet-1")).thenThrow(failure);
        ACCT acct = mock(ACCT.class);
        when(acct.getBlockchain()).thenReturn(pirate); when(acct.populateTradeData(repository, at)).thenReturn(data);
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(CrossChainHtlcResource.class);
        var level = logger.getLevel();
        List<org.apache.logging.log4j.core.LogEvent> events = new ArrayList<>();
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("arrr-refund-test", null, null, false, null) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) { events.add(event.toImmutable()); }
        };
        appender.start(); logger.addAppender(appender); logger.setLevel(org.apache.logging.log4j.Level.ERROR);
        try (MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class);
             MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class);
             MockedStatic<Security> security = mockStatic(Security.class)) {
            repos.when(RepositoryManager::getRepository).thenReturn(repository);
            chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
            assertFalse(new CrossChainHtlcResource().refundAllHtlc("dummy"));
            assertTrue(events.stream().anyMatch(event -> event.getLevel() == org.apache.logging.log4j.Level.ERROR
                    && event.getThrown() == failure && event.getMessage().getFormattedMessage().contains("AT AT (trade native-1)")));
        } finally { logger.removeAppender(appender); logger.setLevel(level); appender.stop(); }
    }

    private PirateChainACCTv3TradeBot freshBot() throws Exception {
        var constructor = PirateChainACCTv3TradeBot.class.getDeclaredConstructor(); constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static final class RecoveryLogCapture implements AutoCloseable {
        private final org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(PirateChainACCTv3TradeBot.class);
        private final org.apache.logging.log4j.Level previousLevel = logger.getLevel();
        private final List<String> messages = new ArrayList<>();
        private final org.apache.logging.log4j.core.appender.AbstractAppender appender =
                new org.apache.logging.log4j.core.appender.AbstractAppender("arrr-recovery-diagnostics", null, null, false, null) {
                    @Override public void append(org.apache.logging.log4j.core.LogEvent event) {
                        messages.add(event.getMessage().getFormattedMessage());
                    }
                };
        RecoveryLogCapture() { appender.start(); logger.addAppender(appender); logger.setLevel(org.apache.logging.log4j.Level.INFO); }
        List<String> diagnostics() {
            return messages.stream().filter(message -> message.startsWith("ARRR recovery for AT ")).collect(java.util.stream.Collectors.toList());
        }
        @Override public void close() { logger.removeAppender(appender); logger.setLevel(previousLevel); appender.stop(); }
    }

    @Test public void missingReserveAndUnfundedHistoryProducePublicDiagnosticsWithoutSecrets() throws Exception {
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(null);
        status(BitcoinyHTLC.Status.UNFUNDED);
        TradeBotData attempt = trade(1);
        try (RecoveryLogCapture logs = new RecoveryLogCapture()) {
            assertFalse(freshBot().refund(repository, attempt, at, data));
            assertEquals(1, logs.diagnostics().size());
            String message = logs.diagnostics().get(0);
            assertTrue(message.contains("AT AT (trade native-1)"));
            assertTrue(message.contains("phase=funding-recovery state=ALICE_REFUNDING_A reason=FUNDING_OUTPOINT_UNKNOWN"));
            assertTrue(message.contains("htlc=b-1 expectedAmount=100000000 fundingFee=null minimumAmount=100000000"));
            assertFalse(message.contains(attempt.getForeignKey()));
            assertFalse(message.contains(org.qortal.utils.Base58.encode(attempt.getTradePrivateKey())));
            assertFalse(message.contains(org.qortal.utils.Base58.encode(attempt.getSecret())));
        }
        noBroadcast();
    }

    @Test public void nativeSubmissionStagesAndAcknowledgementIdentifyTheAttempt() throws Exception {
        TradeBotData attempt = trade(1);
        try (RecoveryLogCapture logs = new RecoveryLogCapture()) {
            assertTrue(freshBot().refund(repository, attempt, at, data));
            assertEquals(2, logs.diagnostics().size());
            assertTrue(logs.diagnostics().get(0).contains("reason=PREPARING_NATIVE_REFUND"));
            assertTrue(logs.diagnostics().get(1).contains("reason=SUBMITTING_NATIVE_REFUND htlc=b-1 fundingOutputIndex=0 checkpoint=committed"));
            assertTrue(logs.messages.stream().anyMatch(message -> message.contains("ARRR refund broadcast: " + TXID + " for AT AT (trade native-1)")));
        }
        assertEquals(TXID, PirateChainRefundState.decode(attempt.getLastTransactionSignature()).txid);
        assertEquals("ALICE_REFUNDING_A", attempt.getState());
    }

    @Test public void clockAndMaturityWaitsIdentifyTheirGateAndDoNotBroadcast() throws Exception {
        PirateChainACCTv3TradeBot recovery = freshBot();
        try (RecoveryLogCapture logs = new RecoveryLogCapture()) {
            clock.when(NTP::getTime).thenReturn(null);
            assertFalse(recovery.refund(repository, trade(1), at, data));
            clock.when(NTP::getTime).thenReturn(1_000L);
            assertFalse(recovery.refund(repository, trade(1), at, data));
            clock.when(NTP::getTime).thenReturn(1_000_000L);
            when(pirate.getMedianBlockTime()).thenReturn(1);
            assertFalse(recovery.refund(repository, trade(1), at, data));
            assertEquals(3, logs.diagnostics().size());
            assertTrue(logs.diagnostics().get(0).contains("reason=CLOCK_UNAVAILABLE"));
            assertTrue(logs.diagnostics().get(1).contains("reason=LOCKTIME_NOT_REACHED"));
            assertTrue(logs.diagnostics().get(2).contains("reason=MEDIAN_TIME_NOT_MATURE medianBlockTime=1 lockTime=1"));
        }
        noBroadcast();
    }

    @Test public void repeatedQuarantineIsQuietButLiveTransactionReasonAppearsImmediately() throws Exception {
        PirateChainACCTv3TradeBot recovery = freshBot();
        TradeBotData attempt = trade(1);
        attempt.setLastTransactionSignature(new PirateChainRefundState(130, 1_000_000L, TXID).encode());
        try (RecoveryLogCapture logs = new RecoveryLogCapture()) {
            assertFalse(recovery.refund(repository, attempt, at, data));
            assertFalse(recovery.refund(repository, attempt, at, data));
            assertEquals(1, logs.diagnostics().size());
            assertTrue(logs.diagnostics().get(0).contains("reason=RETRY_QUARANTINE"));
            assertTrue(logs.diagnostics().get(0).contains("retryAfterHeight=130"));
            assertTrue(logs.diagnostics().get(0).contains("retryAtMillis=1600000"));
            when(chain.getCurrentHeight()).thenReturn(131);
            clock.when(NTP::getTime).thenReturn(1_600_000L);
            byte[] raw = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x80000006).putInt(0xd884b698).putInt(0x37a5165b).putInt(1).putInt(200).put((byte)0).put((byte)0).array();
            when(chain.getRawTransaction(any(byte[].class))).thenReturn(raw);
            assertFalse(recovery.refund(repository, attempt, at, data));
            assertEquals(2, logs.diagnostics().size());
            assertTrue(logs.diagnostics().get(1).contains("reason=SPEND_NOT_EXPIRED"));
            assertTrue(logs.diagnostics().get(1).contains("expiryHeight=200"));
        }
        noBroadcast();
    }

    @Test public void fundedHistoryWithMissingUnspentOutputReportsDiscoveryMismatch() throws Exception {
        TradeBotData attempt = trade(1); attempt.setFundingFee(10000L);
        attempt.setFundingTxid("22".repeat(32));
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(null);
        try (RecoveryLogCapture logs = new RecoveryLogCapture()) {
            assertFalse(freshBot().refund(repository, attempt, at, data));
            assertEquals(1, logs.diagnostics().size());
            assertTrue(logs.diagnostics().get(0).contains("reason=NO_CONFIRMED_FUNDING_OUTPUT"));
            assertTrue(logs.diagnostics().get(0).contains("htlc=b-1 historyStatus=FUNDED minimumAmount=100010000"));
        }
        noBroadcast();
    }

    @Test public void shallowSpendsReportConfirmationWaitWithoutAnotherSpend() throws Exception {
        PirateChainACCTv3TradeBot recovery = freshBot();
        try (RecoveryLogCapture logs = new RecoveryLogCapture()) {
            for (BitcoinyHTLC.Status pending : List.of(BitcoinyHTLC.Status.REFUND_IN_PROGRESS, BitcoinyHTLC.Status.REDEEM_IN_PROGRESS)) {
                status(pending);
                assertFalse(recovery.refund(repository, trade(1), at, data));
            }
            assertEquals(2, logs.diagnostics().size());
            assertTrue(logs.diagnostics().get(0).contains("reason=HTLC_REFUND_IN_PROGRESS htlc=b-1 requiredConfirmations=3"));
            assertTrue(logs.diagnostics().get(1).contains("reason=HTLC_REDEEM_IN_PROGRESS htlc=b-1 requiredConfirmations=3"));
        }
        noBroadcast();
    }

    @Test public void fundingIntentWaitHasItsOwnPhaseAndDoesNotRepeatFunding() throws Exception {
        TradeBotData attempt = trade(1); attempt.setState("ALICE_WAITING_FOR_FUNDING"); attempt.setStateValue(80);
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(null);
        status(BitcoinyHTLC.Status.UNFUNDED);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class);
             RecoveryLogCapture logs = new RecoveryLogCapture()) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            freshBot().progress(repository, attempt);
            assertEquals(1, logs.diagnostics().size());
            assertTrue(logs.diagnostics().get(0).contains("phase=funding-recovery state=ALICE_WAITING_FOR_FUNDING reason=FUNDING_OUTPOINT_UNKNOWN"));
            assertTrue(logs.diagnostics().get(0).contains("funding is not resubmitted"));
        }
        assertEquals("ALICE_WAITING_FOR_FUNDING", attempt.getState());
        verify(pirate, never()).fundP2SH(anyString(), anyString(), anyLong(), anyString());
        noBroadcast();
    }

    @Test public void expiredBuyerReportsRefundEntryBeforeChangingState() throws Exception {
        TradeBotData attempt = trade(1); attempt.setState("ALICE_WAITING_FOR_AT_LOCK"); attempt.setStateValue(85);
        data.mode = AcctMode.OFFERING; when(at.getIsFinished()).thenReturn(false);
        htlc.when(() -> PirateChainHTLC.determineHtlcStatus(any(), anyString(), anyLong())).thenReturn(BitcoinyHTLC.Status.UNFUNDED);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class);
             RecoveryLogCapture logs = new RecoveryLogCapture()) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            freshBot().progress(repository, attempt);
            assertEquals(1, logs.diagnostics().size());
            assertTrue(logs.diagnostics().get(0).contains("phase=refund-entry state=ALICE_WAITING_FOR_AT_LOCK reason=HTLC_UNFUNDED"));
        }
        assertEquals("ALICE_REFUNDING_A", attempt.getState());
        noBroadcast();
    }

    @Test public void legacyBuyerWithoutQortSettlementReentersRecoveryAndCannotBeDeleted() throws Exception {
        TradeBotData buyer = trade(1); buyer.setState("ALICE_DONE"); buyer.setStateValue(95);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            assertFalse(bot.canDelete(repository, buyer));
            freshBot().progress(repository, buyer);
            assertEquals("ALICE_REFUNDING_A", buyer.getState());
            noBroadcast();
        }
    }

    @Test public void submittedQortMessageDoesNotCompleteBuyerUntilAtSettles() throws Exception {
        TradeBotData buyer = trade(1); buyer.setState("ALICE_WAITING_FOR_AT_LOCK"); buyer.setStateValue(85);
        var lock = TradeBotData.class.getDeclaredField("lockTimeA"); lock.setAccessible(true); lock.set(buyer, 2000);
        when(at.getIsFinished()).thenReturn(false);
        data.mode = AcctMode.TRADING; data.qortalPartnerAddress = buyer.getTradeNativeAddress();
        data.qortalCreatorTradeAddress = "seller-contact"; data.refundTimeout = 8;
        var message = mock(org.qortal.data.transaction.MessageTransactionData.class);
        when(message.getTimestamp()).thenReturn(1_000_000L);
        MessageRepository messages = mock(MessageRepository.class);
        when(repository.getMessageRepository()).thenReturn(messages);
        when(messages.getMessagesByParticipants(any(), any(), any(), any(), any())).thenReturn(List.of(message));
        when(messages.exists(any(), any(), any())).thenReturn(true);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class);
             MockedStatic<TradeBot> bots = mockStatic(TradeBot.class, CALLS_REAL_METHODS)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            bots.when(TradeBot::getInstance).thenReturn(mock(TradeBot.class));
            bot.progress(repository, buyer);
            assertEquals("ALICE_WAITING_FOR_AT_LOCK", buyer.getState());
            assertFalse(bot.canDelete(repository, buyer));
            when(at.getIsFinished()).thenReturn(true); data.mode = AcctMode.REDEEMED;
            bot.progress(repository, buyer);
            assertEquals("ALICE_DONE", buyer.getState()); noBroadcast();
        }
    }

    @Test public void actuallySettledBuyerRemainsDoneAndIsDeletable() throws Exception {
        TradeBotData buyer = trade(1); buyer.setState("ALICE_DONE"); buyer.setStateValue(95);
        data.mode = AcctMode.REDEEMED; data.qortalPartnerAddress = buyer.getTradeNativeAddress();
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            freshBot().progress(repository, buyer);
            assertEquals("ALICE_DONE", buyer.getState()); assertTrue(bot.canDelete(repository, buyer));
            noBroadcast();
        }
    }

    @Test public void unknownFundingIntentIsRecoveredWithoutRepeatingFunding() throws Exception {
        TradeBotData buyer = trade(1); buyer.setState("ALICE_WAITING_FOR_FUNDING"); buyer.setStateValue(80);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            freshBot().progress(repository, TradeBotData.fromJson(buyer.toJson()));
            verify(pirate, never()).fundP2SH(anyString(), anyString(), anyLong(), anyString());
            verify(cross, atLeastOnce()).save(argThat(row -> row.getState().equals("ALICE_REFUNDING_A")));
            assertFalse(bot.canDelete(repository, buyer));
        }
    }

    @Test public void absentFundingIntentIsRetainedWithoutAnnouncingOrResending() throws Exception {
        TradeBotData buyer = trade(1); buyer.setState("ALICE_WAITING_FOR_FUNDING"); buyer.setStateValue(80);
        status(BitcoinyHTLC.Status.UNFUNDED);
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(null);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            freshBot().progress(repository, buyer);
            assertEquals("ALICE_WAITING_FOR_FUNDING", buyer.getState());
            verify(pirate, never()).fundP2SH(anyString(), anyString(), anyLong(), anyString()); noBroadcast();
        }
    }

    private TradeBotData seller(int id, String address) throws Exception {
        TradeBotData seller = trade(id); seller.setState("BOB_WAITING_FOR_AT_REDEEM"); seller.setStateValue(25);
        var field = TradeBotData.class.getDeclaredField("receivingAccountInfo"); field.setAccessible(true);
        field.set(seller, PirateChainAddress.toReceivingAccountInfo(address));
        return seller;
    }

    private void sellerData(TradeBotData seller) {
        data.mode = AcctMode.REDEEMED; data.lockTimeA = 1; data.partnerForeignPKH = new byte[33];
        data.creatorForeignPKH = seller.getTradeForeignPublicKey();
        data.hashOfSecretA = org.qortal.crypto.Crypto.hash160(new byte[32]);
    }

    private static final String SAPLING = "zs1mj80tkyjqjhc84aquetymwgrasxjdkkft0qcn5xtp2p9r42vs5ernyv4nzgufmz6xzuqvw20w9z";
    private static final String IRONWOOD = "pirate1lyp0g5438qkp9s5me5epku9ak9k0dxqsfyt9z894zym8qw38sps488ljyee27yw29vc2cyj8mse";

    @Test public void redeemAllUsesNativeArrrAddressesAndQuarantinesLostAcknowledgements() throws Exception {
        TradeBotData seller = seller(1, IRONWOOD); sellerData(seller);
        when(cross.getAllTradeBotData()).thenReturn(List.of(trade(2), seller));
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.getBlockchain()).thenReturn(pirate); when(acct.populateTradeData(repository, at)).thenReturn(data);
        when(acct.findSecretA(repository, data)).thenReturn(new byte[32]);
        when(pirate.redeemP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt()))
                .thenThrow(new ForeignBlockchainException("lost native acknowledgement"));
        try (MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class);
             MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class);
             MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class);
             MockedStatic<Security> security = mockStatic(Security.class)) {
            repos.when(RepositoryManager::getRepository).thenReturn(repository);
            chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            assertFalse(new CrossChainHtlcResource().redeemAllHtlc("dummy"));
            assertNotNull(PirateChainRefundState.decode(seller.getLastTransactionSignature()));
            assertEquals("BOB_WAITING_FOR_AT_REDEEM", seller.getState());
            clearInvocations(pirate);
            assertFalse(new CrossChainHtlcResource().redeemAllHtlc("dummy"));
            verify(pirate, never()).redeemP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
        }
    }

    @Test public void targetedArrrRedeemUsesSellerRecordAndWaitsForThreeConfirmations() throws Exception {
        for (String destination : List.of(SAPLING, IRONWOOD)) {
            TradeBotData seller = seller(1, destination); sellerData(seller);
            when(cross.getAllTradeBotData()).thenReturn(List.of(trade(2), seller));
            PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
            when(acct.getBlockchain()).thenReturn(pirate); when(acct.populateTradeData(repository, at)).thenReturn(data);
            when(acct.findSecretA(repository, data)).thenReturn(new byte[32]);
            try (MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class);
                 MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class);
                 MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class);
                 MockedStatic<Security> security = mockStatic(Security.class)) {
                repos.when(RepositoryManager::getRepository).thenReturn(repository);
                chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
                accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
                status(BitcoinyHTLC.Status.FUNDED);
                assertTrue(new CrossChainHtlcResource().redeemHtlc("dummy", "AT"));
                verify(pirate).redeemP2sh(anyString(), eq(destination), eq(100000000L), anyString(), anyString(), anyString(), anyString(), eq(10000L), eq(0));
                status(BitcoinyHTLC.Status.REDEEM_IN_PROGRESS);
                assertFalse(new CrossChainHtlcResource().redeemAllHtlc("dummy"));
                assertEquals("BOB_WAITING_FOR_AT_REDEEM", seller.getState());
                status(BitcoinyHTLC.Status.REDEEMED);
                assertFalse(new CrossChainHtlcResource().redeemAllHtlc("dummy"));
                assertEquals("BOB_DONE", seller.getState()); assertTrue(bot.canDelete(repository, seller));
            }
        }
    }

    @Test public void redeemRejectsUnsettledAtWrongSellerAndWrongSecret() throws Exception {
        TradeBotData seller = seller(1, SAPLING); sellerData(seller);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.findSecretA(repository, data)).thenReturn(new byte[32]);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            when(at.getIsFinished()).thenReturn(false); assertFalse(bot.redeem(repository, seller, at, data));
            when(at.getIsFinished()).thenReturn(true); data.creatorForeignPKH = new byte[33];
            assertFalse(bot.redeem(repository, seller, at, data));
            sellerData(seller); data.hashOfSecretA = new byte[20]; assertFalse(bot.redeem(repository, seller, at, data));
            verify(pirate, never()).redeemP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
        }
    }

    @Test public void staleSellerUsesCommittedCheckpointAndCannotRepeatRedemption() throws Exception {
        TradeBotData stale = seller(1, SAPLING), committed = seller(1, SAPLING); sellerData(stale);
        committed.setLastTransactionSignature(new PirateChainRefundState(130, 999999L, TXID).encode());
        doReturn(committed).when(cross).getTradeBotData(stale.getTradePrivateKey());
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.findSecretA(repository, data)).thenReturn(new byte[32]);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            assertFalse(bot.redeem(repository, stale, at, data));
            verify(pirate, never()).redeemP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
        }
    }

    @Test public void legacySellerIsAuditedWithoutImmediateDuplicateAndCannotBeDeleted() throws Exception {
        TradeBotData seller = seller(1, SAPLING); sellerData(seller);
        seller.setState("BOB_DONE"); seller.setStateValue(30);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.findSecretA(repository, data)).thenReturn(new byte[32]);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            assertFalse(bot.canDelete(repository, seller)); freshBot().progress(repository, seller);
            assertEquals("BOB_WAITING_FOR_AT_REDEEM", seller.getState());
            assertNotNull(PirateChainRefundState.decode(seller.getLastTransactionSignature()));
            verify(pirate, never()).redeemP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
        }
    }

    @Test public void legacySpentSellerCanBeBoundAndConfirmedWithoutAnUnspentOutput() throws Exception {
        TradeBotData seller = seller(1, SAPLING); sellerData(seller);
        seller.setState("BOB_DONE"); seller.setStateValue(30);
        htlc.when(() -> PirateChainHTLC.getUnspentFundingOutput(any(), anyString(), anyLong())).thenReturn(null);
        htlc.when(() -> PirateChainHTLC.recoverSpentFundingOutput(any(), anyString(), anyLong())).thenReturn(funding(2, 10000L));
        status(BitcoinyHTLC.Status.REDEEMED);
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.findSecretA(repository, data)).thenReturn(new byte[32]);
        try (MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class)) {
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            assertFalse(bot.redeem(repository, seller, at, data));
            assertEquals(2, seller.getFundingOutputIndex()); assertTrue(seller.hasFundingOutpoint());
            assertTrue(PirateChainRefundState.isConfirmed(seller.getLastTransactionSignature()));
            assertTrue(bot.canDelete(repository, seller));
            verify(pirate, never()).redeemP2sh(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
        }
    }

    @Test public void redeemAllContinuesAnotherArrrSellerAfterAnUncertainBroadcast() throws Exception {
        TradeBotData first = seller(1, SAPLING), second = seller(2, IRONWOOD);
        sellerData(first);
        var address = TradeBotData.class.getDeclaredField("atAddress"); address.setAccessible(true); address.set(second, "AT2");
        CrossChainTradeData secondData = new CrossChainTradeData();
        secondData.mode = AcctMode.REDEEMED; secondData.lockTimeA = 1; secondData.expectedForeignAmount = 100000000L;
        secondData.creatorForeignPKH = second.getTradeForeignPublicKey(); secondData.partnerForeignPKH = new byte[33];
        secondData.hashOfSecretA = org.qortal.crypto.Crypto.hash160(new byte[32]);
        ATData secondAt = mock(ATData.class); when(secondAt.getIsFinished()).thenReturn(true); when(secondAt.getCodeHash()).thenReturn(new byte[32]);
        when(repository.getATRepository().fromATAddress("AT2")).thenReturn(secondAt);
        when(cross.getAllTradeBotData()).thenReturn(List.of(first, second));
        PirateChainACCTv3 acct = mock(PirateChainACCTv3.class);
        when(acct.getBlockchain()).thenReturn(pirate);
        when(acct.populateTradeData(repository, at)).thenReturn(data);
        when(acct.populateTradeData(repository, secondAt)).thenReturn(secondData);
        when(acct.findSecretA(eq(repository), any())).thenReturn(new byte[32]);
        when(pirate.redeemP2sh(anyString(), eq(SAPLING), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt()))
                .thenThrow(new ForeignBlockchainException("Synthetic lost response"));
        try (MockedStatic<RepositoryManager> repos = mockStatic(RepositoryManager.class);
             MockedStatic<SupportedBlockchain> chains = mockStatic(SupportedBlockchain.class);
             MockedStatic<PirateChainACCTv3> accts = mockStatic(PirateChainACCTv3.class);
             MockedStatic<Security> security = mockStatic(Security.class)) {
            repos.when(RepositoryManager::getRepository).thenReturn(repository);
            chains.when(() -> SupportedBlockchain.getAcctByCodeHash(any())).thenReturn(acct);
            accts.when(PirateChainACCTv3::getInstance).thenReturn(acct);
            assertTrue(new CrossChainHtlcResource().redeemAllHtlc("dummy"));
            assertNotNull(PirateChainRefundState.decode(first.getLastTransactionSignature()));
            assertEquals(TXID, PirateChainRefundState.decode(second.getLastTransactionSignature()).txid);
            verify(pirate).redeemP2sh(eq("t3-0"), eq(IRONWOOD), eq(100000000L), anyString(), anyString(), anyString(),
                    eq(org.qortal.utils.Base58.encode(second.getTradePrivateKey())), eq(10000L), eq(0));
            noBroadcast();
        }
    }

}
