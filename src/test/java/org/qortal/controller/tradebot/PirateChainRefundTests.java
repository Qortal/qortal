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
        data = new CrossChainTradeData();
        data.mode = AcctMode.REFUNDED; data.expectedForeignAmount = 100000000L;
        data.tradeTimeout = 60; data.creatorForeignPKH = new byte[33];
        MockedStatic<PirateChain> pirates = mockStatic(PirateChain.class); statics.add(pirates);
        pirates.when(PirateChain::getInstance).thenReturn(pirate);
        htlc = mockStatic(PirateChainHTLC.class); statics.add(htlc);
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
            verify(repository).saveChanges();
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
        verify(repository, times(2)).saveChanges();
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
        data.mode = AcctMode.REDEEMED; data.lockTimeA = 1; data.partnerForeignPKH = new byte[33]; data.hashOfSecretA = new byte[20];
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
        when(cross.getTradeBotData(stale.getTradePrivateKey())).thenReturn(committed);
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
    @Test public void refundAllContinuesAfterOneWalletFails() throws Exception {
        when(cross.getAllTradeBotData()).thenReturn(List.of(trade(1), trade(2)));
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

}
