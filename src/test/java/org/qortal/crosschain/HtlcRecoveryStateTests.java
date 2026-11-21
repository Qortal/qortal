package org.qortal.crosschain;

import org.junit.Test;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;

import static org.junit.Assert.*;

public class HtlcRecoveryStateTests extends Common {
    @Test public void pendingCheckpointSurvivesCommittedDatabaseAndJsonReload() throws Exception {
        Common.useDefaultSettings();
        byte[] key = new byte[32]; key[31] = 7;
        TradeBotData trade = new TradeBotData(key, "DigibyteACCTv3", "BOB_DONE", 30,
                "account", "AT", 1L, 100L, new byte[32], new byte[20], "trade-address",
                new byte[32], new byte[20], "DIGIBYTE", new byte[33], new byte[20], 500000000000L,
                null, null, 1795000000, new byte[20]);
        trade.setFundingTxid("12".repeat(32)); trade.setFundingOutputIndex(2); trade.setFundingFee(10000L);
        byte[] checkpoint = new HtlcRecoveryState("34".repeat(32), 1795500000000L, 30900L, false).encode();
        assertEquals(64, checkpoint.length); trade.setLastTransactionSignature(checkpoint);
        try (Repository repository = RepositoryManager.getRepository()) {
            repository.getCrossChainRepository().save(trade); repository.saveChanges();
        }
        try (Repository reopened = RepositoryManager.getRepository()) {
            TradeBotData saved = reopened.getCrossChainRepository().getTradeBotData(key);
            assertArrayEquals(checkpoint, saved.getLastTransactionSignature());
            assertFalse("Pending recovery must not be deletable", TradeBot.getInstance().deleteEntry(reopened, key));
            TradeBotData json = TradeBotData.fromJson(saved.toJson());
            HtlcRecoveryState decoded = HtlcRecoveryState.decode(json.getLastTransactionSignature());
            assertEquals("34".repeat(32), decoded.txid); assertEquals(30900L, decoded.fee); assertFalse(decoded.confirmed);
            assertEquals("12".repeat(32), json.getFundingTxid()); assertEquals(2, json.getFundingOutputIndex());
        }
    }
    @Test public void unrelatedSignaturesAreNotMistakenForRecoveryIntent() {
        assertNull(HtlcRecoveryState.decode(null)); assertNull(HtlcRecoveryState.decode(new byte[64]));
        assertNull(HtlcRecoveryState.decode(new byte[63]));
    }
}
