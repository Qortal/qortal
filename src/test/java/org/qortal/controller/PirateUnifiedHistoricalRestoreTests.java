package org.qortal.controller;

import com.rust.litewalletjni.LiteWalletJni;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Opt-in proof that a clean same-entropy wallet recovers history with an explicit birthday. */
public class PirateUnifiedHistoricalRestoreTests {

    private static final String RUN_PROPERTY = "qortal.runPirateUnifiedHistoricalRestoreTests";
    private static final String BUNDLE_PATH_PROPERTY = "qortal.pirateUnifiedBundlePath";
    private static final String STORAGE_PATH_PROPERTY = "qortal.pirateUnifiedHistoricalRestoreStoragePath";
    private static final String EXPECTED_ADDRESS =
            "zs1ra3g8uphtg8ad7p8ye76pg06nr9rg5y8m5ycq40vpw4nvae6amehenaafv02g3dny9myxz7f60s";

    @Test
    public void freshSameEntropyWalletRecoversHistoricalTransactionAndBalance() throws Exception {
        assumeTrue(Boolean.getBoolean(RUN_PROPERTY));
        String bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY);
        assumeTrue(bundlePath != null && !bundlePath.isBlank());
        String storagePath = System.getProperty(STORAGE_PATH_PROPERTY);
        assumeTrue(storagePath != null && !storagePath.isBlank() && Path.of(storagePath).isAbsolute());

        Path bundle = Path.of(bundlePath).toAbsolutePath().normalize();
        Path storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        assertFalse("Historical-restore storage path already exists", Files.exists(storageRoot));
        String libraryFilename = PirateChainWalletController.getRustLibFilename();
        assumeTrue(libraryFilename != null);
        Path library = bundle.resolve(libraryFilename);
        assertTrue("Pinned Pirate Unified library is missing", Files.isRegularFile(library));
        assertFalse("Pinned Pirate Unified library must not be a symlink", Files.isSymbolicLink(library));

        try (PirateUnifiedLoopbackLightwalletd lightwalletd = new PirateUnifiedLoopbackLightwalletd(true)) {
            LiteWalletJni.loadLibraryFrom(library);
            assertTrue("Pirate Unified JNI library did not load", LiteWalletJni.isLoaded());
            LiteWalletJni.initlogging();

            byte[] entropy = new byte[32];
            Arrays.fill(entropy, (byte) 7);
            String seed = object(LiteWalletJni.getseedphrasefromentropyb64(
                    Base64.getEncoder().encodeToString(entropy)), "deterministic seed derivation")
                    .getString("seedPhrase");
            assertFalse("Deterministic seed derivation returned an empty phrase", seed.isBlank());

            JSONObject configured = object(LiteWalletJni.configurestorage(
                    storageRoot.toString(), "qortal-historical-restore-test"), "fresh storage configuration");
            assertTrue("Fresh restore storage was not initialized", configured.optBoolean("initialized"));
            JSONObject initialized = object(LiteWalletJni.initfromseed(
                    lightwalletd.endpoint(), "", seed,
                    Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""),
                    "fresh same-entropy wallet initialization");
            assertEquals("Fresh restore changed the explicit recovery birthday",
                    PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT,
                    initialized.getLong("birthday"));
            assertTrue("Fresh restore did not derive the expected account address",
                    LiteWalletJni.execute("export", "").contains(EXPECTED_ADDRESS));
            assertEquals("Fresh restore contained balance before scanning history", 0L,
                    object(LiteWalletJni.execute("balance", ""), "pre-sync balance").getLong("zbalance"));
            assertEquals("Fresh restore contained transactions before scanning history", 0,
                    array(LiteWalletJni.execute("list", ""), "pre-sync transaction list").length());

            String walletId = initialized.getString("wallet_id");
            JSONObject cancelRequest = new JSONObject().put("method", "cancel_sync").put("wallet_id", walletId);
            try {
                JSONObject syncStarted = object(LiteWalletJni.execute("sync", ""), "historical sync start");
                assertEquals("Historical sync command was not accepted", "success", syncStarted.getString("result"));
                awaitNativeSync();
                assertHistoricalState(walletId);
            } finally {
                JSONObject cancelled = object(LiteWalletJni.invokeJson(cancelRequest.toString(), false),
                        "historical sync cancellation");
                assertTrue("Historical sync cancellation failed", cancelled.optBoolean("ok"));
            }

            assertTrue("Fresh restore did not request its complete birthday-to-tip range; observed "
                            + lightwalletd.observedRanges(),
                    lightwalletd.completeRangeCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE) > 0);
            assertEquals("Historical restore attempted a forbidden transaction RPC", 0,
                    lightwalletd.forbiddenRpcCount());
            assertEquals("Historical restore attempted an RPC outside the deterministic fixture", 0,
                    lightwalletd.unexpectedRpcCount());
        }
    }

    private static void assertHistoricalState(String walletId) {
        JSONObject balance = object(LiteWalletJni.execute("balance", ""), "post-sync historical balance");
        assertEquals(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE, balance.getLong("zbalance"));
        assertEquals(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
                balance.getLong("verified_zbalance"));

        JSONArray transactions = array(LiteWalletJni.execute("list", ""), "post-sync historical transaction list");
        assertEquals("Historical transaction was not recovered exactly once", 1, transactions.length());
        JSONObject transaction = transactions.getJSONObject(0);
        assertEquals(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_HEIGHT,
                transaction.getLong("block_height"));
        assertEquals(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE, transaction.getLong("amount"));
        assertFalse("Historical transaction remained unconfirmed", transaction.optBoolean("unconfirmed"));
        JSONArray incoming = transaction.getJSONArray("incoming_metadata");
        assertEquals(1, incoming.length());
        assertEquals(EXPECTED_ADDRESS, incoming.getJSONObject(0).getString("address"));
        assertEquals(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
                incoming.getJSONObject(0).getLong("value"));

        JSONObject localHistory = object(LiteWalletJni.invokeJson(new JSONObject()
                        .put("method", "list_transactions")
                        .put("wallet_id", walletId)
                        .put("limit", JSONObject.NULL)
                        .toString(), false),
                "local Unified transaction list");
        assertTrue("Local Unified transaction list failed", localHistory.optBoolean("ok"));
        assertEquals("Local Unified history changed the recovered transaction count",
                1, localHistory.getJSONArray("result").length());

        JSONObject localDeposits = object(LiteWalletJni.invokeJson(new JSONObject()
                        .put("method", "list_incoming_deposits")
                        .put("wallet_id", walletId)
                        .put("limit", JSONObject.NULL)
                        .toString(), false),
                "local Unified incoming deposits");
        assertTrue("Local Unified incoming deposits failed", localDeposits.optBoolean("ok"));
        JSONArray deposits = localDeposits.getJSONArray("result");
        assertEquals("Local Unified deposits changed the recovered transaction count", 1, deposits.length());
        assertEquals(EXPECTED_ADDRESS, deposits.getJSONObject(0).getString("address"));
    }

    private static void awaitNativeSync() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        JSONObject status = null;
        long height = Long.MIN_VALUE;
        do {
            status = object(LiteWalletJni.execute("syncStatus", ""), "historical sync status");
            height = object(LiteWalletJni.execute("height", ""), "historical wallet height").getLong("height");
            if (height >= PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT && !isSyncInProgress(status))
                break;
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);

        assertEquals(PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT, height);
        assertFalse(status != null && isSyncInProgress(status));
    }

    private static boolean isSyncInProgress(JSONObject status) {
        if (status.has("in_progress"))
            return status.getBoolean("in_progress");
        if (status.has("syncing"))
            return status.getBoolean("syncing");
        throw new AssertionError("Historical sync status omitted its activity field");
    }

    private static JSONObject object(String response, String operation) {
        try {
            return new JSONObject(response);
        } catch (RuntimeException e) {
            throw new AssertionError(operation + " returned invalid JSON without exposing its response", e);
        }
    }

    private static JSONArray array(String response, String operation) {
        try {
            return new JSONArray(response);
        } catch (RuntimeException e) {
            throw new AssertionError(operation + " returned invalid JSON without exposing its response", e);
        }
    }
}
