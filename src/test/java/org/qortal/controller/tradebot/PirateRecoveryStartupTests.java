package org.qortal.controller.tradebot;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.qortal.controller.Controller;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.PirateChain;
import org.qortal.crosschain.PirateChainACCTv3;
import org.qortal.crosschain.PirateChainRefundState;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBImportExport;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual HSQLDB commits and actual Controller startup importer; no wallet/network activity. */
public class PirateRecoveryStartupTests extends Common {
    private Field exportPath;
    private String previousPath;
    private Path directory;

    @Before public void setup() throws Exception {
        Common.useDefaultSettings();
        directory = Files.createTempDirectory(Path.of("target"), "pirate-startup-");
        exportPath = Settings.class.getDeclaredField("exportPath"); exportPath.setAccessible(true);
        previousPath = Settings.getInstance().getExportPath(); exportPath.set(Settings.getInstance(), directory.toString());
    }

    @After public void restore() throws Exception {
        if (exportPath != null) exportPath.set(Settings.getInstance(), previousPath);
    }

    @Test public void startupCannotReplaceTheCommittedRefundCheckpointWithAPreFundingBackup() throws Exception {
        TradeBotData trade = fixture();
        saveAndBackup(trade);
        trade.setState("ALICE_REFUNDING_A"); trade.setStateValue(105);
        trade.setFundingFee(20000L); trade.setFundingOutputIndex(2); trade.setFundingTxid("ab".repeat(32));
        byte[] checkpoint = new PirateChainRefundState(130, 1_000_000L, "cd".repeat(32)).encode();
        trade.setLastTransactionSignature(checkpoint);
        try (Repository repository = RepositoryManager.getRepository()) {
            repository.getCrossChainRepository().save(trade); repository.saveChanges();
        }
        startup();
        try (Repository reopened = RepositoryManager.getRepository()) {
            TradeBotData committed = reopened.getCrossChainRepository().getTradeBotData(trade.getTradePrivateKey());
            assertEquals("ALICE_REFUNDING_A", committed.getState());
            assertEquals(2, committed.getFundingOutputIndex()); assertEquals(trade.getFundingTxid(), committed.getFundingTxid());
            assertArrayEquals(checkpoint, committed.getLastTransactionSignature());
            assertFalse(PirateChainRefundState.decode(checkpoint).mayRetry(130, 1_600_000L));
        }
    }

    @Test public void startupPreservesDefinitiveDeletionEvenWhenItWasTheLastTrade() throws Exception {
        TradeBotData trade = fixture(); saveAndBackup(trade);
        try (Repository repository = RepositoryManager.getRepository()) {
            repository.getCrossChainRepository().delete(trade.getTradePrivateKey()); repository.saveChanges();
        }
        startup(); startup();
        try (Repository reopened = RepositoryManager.getRepository()) {
            assertTrue(reopened.getCrossChainRepository().getAllTradeBotData().isEmpty());
            assertTrue(reopened.getCrossChainRepository().wasTradeBotDeleted(trade.getTradePrivateKey()));
        }
    }

    @Test public void aNewEmptyRepositoryStillRecoversTheCurrentBackup() throws Exception {
        TradeBotData trade = fixture(); saveAndBackup(trade);
        try (Repository repository = RepositoryManager.getRepository(); var statement = repository.getConnection().createStatement()) {
            // Simulate replacement with an empty database, not an intentional per-trade deletion.
            statement.execute("DELETE FROM TradeBotStates"); repository.getConnection().commit();
        }
        startup();
        try (Repository reopened = RepositoryManager.getRepository()) {
            assertEquals(trade.toJson().toString(), reopened.getCrossChainRepository().getTradeBotData(trade.getTradePrivateKey()).toJson().toString());
        }
    }

    @Test public void deletionMarkerRollsBackWithTheUncommittedDeletion() throws Exception {
        TradeBotData trade = fixture(); saveAndBackup(trade);
        try (Repository repository = RepositoryManager.getRepository()) {
            repository.getCrossChainRepository().delete(trade.getTradePrivateKey()); repository.discardChanges();
        }
        startup();
        try (Repository reopened = RepositoryManager.getRepository()) {
            assertNotNull(reopened.getCrossChainRepository().getTradeBotData(trade.getTradePrivateKey()));
            assertFalse(reopened.getCrossChainRepository().wasTradeBotDeleted(trade.getTradePrivateKey()));
        }
    }

    @Test public void explicitManualRestoreRemainsDistinctFromAutomaticReconciliation() throws Exception {
        TradeBotData trade = fixture(); saveAndBackup(trade);
        try (Repository repository = RepositoryManager.getRepository()) {
            repository.getCrossChainRepository().delete(trade.getTradePrivateKey()); repository.saveChanges();
            repository.importDataFromFile(directory.resolve("TradeBotStates.json").toString()); repository.saveChanges();
            assertNotNull(repository.getCrossChainRepository().getTradeBotData(trade.getTradePrivateKey()));
            assertFalse(repository.getCrossChainRepository().wasTradeBotDeleted(trade.getTradePrivateKey()));
        }
    }

    @Test public void nativeRejectionBeforeBroadcastCannotResurrectItsSavedFundingIntent() throws Exception {
        PirateChain pirate = mock(PirateChain.class);
        when(pirate.getP2shFee(any())).thenReturn(10000L);
        when(pirate.deriveP2shAddress(any())).thenReturn("public-fixture-contract");
        when(pirate.fundP2SH(anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new ForeignBlockchainException.TransactionRejectedException("fixture rejected before broadcast"));
        try (MockedStatic<PirateChain> pirates = mockStatic(PirateChain.class); MockedStatic<NTP> clock = mockStatic(NTP.class);
             Repository repository = RepositoryManager.getRepository()) {
            pirates.when(PirateChain::getInstance).thenReturn(pirate); clock.when(NTP::getTime).thenReturn(1_000_000L);
            CrossChainTradeData data = new CrossChainTradeData();
            data.qortalAtAddress = Common.getTestAccount(repository, "bob").getAddress(); data.creatorForeignPKH = new byte[33];
            data.tradeTimeout = 60; data.qortAmount = 100000000L; data.expectedForeignAmount = 100000000L;
            assertEquals(AcctTradeBot.ResponseResult.NETWORK_ISSUE, PirateChainACCTv3TradeBot.getInstance().startResponse(
                    repository, null, PirateChainACCTv3.getInstance(), data, Base58.encode(new byte[32]),
                    Common.getTestAccount(repository, "alice").getAddress()));
            assertTrue(Files.exists(directory.resolve("TradeBotStates.json")));
        }
        startup();
        try (Repository reopened = RepositoryManager.getRepository()) { assertTrue(reopened.getCrossChainRepository().getAllTradeBotData().isEmpty()); }
        verify(pirate, times(1)).fundP2SH(anyString(), anyString(), anyLong(), anyString());
    }

    private TradeBotData fixture() throws Exception {
        try (Repository repository = RepositoryManager.getRepository()) {
            String account = Common.getTestAccount(repository, "alice").getAddress();
            return new TradeBotData(new byte[32], PirateChainACCTv3.NAME, "ALICE_WAITING_FOR_FUNDING", 80,
                    account, Common.getTestAccount(repository, "bob").getAddress(), 1L, 100L,
                    new byte[32], new byte[20], account, new byte[32], new byte[20], "PIRATECHAIN", new byte[33],
                    new byte[20], 100000000L, "public-unfunded-wallet-fixture", null, 1, new byte[25]);
        }
    }

    private void saveAndBackup(TradeBotData trade) throws Exception {
        try (Repository repository = RepositoryManager.getRepository()) {
            repository.getCrossChainRepository().save(trade); repository.saveChanges();
            HSQLDBImportExport.backupTradeBotStates(repository, null);
        }
    }

    private void startup() throws Exception {
        var method = Controller.class.getDeclaredMethod("importRepositoryData"); method.setAccessible(true); method.invoke(null);
    }
}
