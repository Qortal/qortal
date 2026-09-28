package org.qortal.controller;

import com.rust.litewalletjni.LiteWalletJni;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Opt-in smoke test for the stock Unified Wallet APIs used by Core enrichment. */
public class PirateUnifiedStockApiTests {

    private static final String RUN_PROPERTY = "qortal.runPirateUnifiedStockApiTests";
    private static final String BUNDLE_PATH_PROPERTY = "qortal.pirateUnifiedBundlePath";
    private static final String STORAGE_PATH_PROPERTY = "qortal.pirateUnifiedStockApiStoragePath";

    @Test
    public void stockBundleProvidesLocalHistoryDetailsAndEndpointPoolActions() {
        assumeTrue(Boolean.getBoolean(RUN_PROPERTY));
        String bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY);
        assumeTrue(bundlePath != null && !bundlePath.isBlank());
        String storagePath = System.getProperty(STORAGE_PATH_PROPERTY);
        assumeTrue(storagePath != null && !storagePath.isBlank() && Path.of(storagePath).isAbsolute());

        Path library = Path.of(bundlePath).toAbsolutePath().normalize()
                .resolve(PirateChainWalletController.getRustLibFilename());
        assertTrue(Files.isRegularFile(library));
        assertFalse(Files.isSymbolicLink(library));
        assertFalse(Files.exists(Path.of(storagePath)));

        LiteWalletJni.loadLibraryFrom(library);
        assertTrue(LiteWalletJni.isLoaded());
        LiteWalletJni.initlogging();
        assertTrue(object(LiteWalletJni.configurestorage(storagePath, "qortal-stock-api-test"))
                .optBoolean("initialized"));

        byte[] entropy = new byte[32];
        Arrays.fill(entropy, (byte) 11);
        String seed = object(LiteWalletJni.getseedphrasefromentropyb64(
                Base64.getEncoder().encodeToString(entropy))).getString("seedPhrase");
        JSONObject initialized = object(LiteWalletJni.initfromseed(
                "https://arrr.qortal.link:443/", "", seed, "2955000", "", ""));
        String walletId = initialized.getString("wallet_id");

        assertEmptyResult(new JSONObject()
                .put("method", "list_transactions")
                .put("wallet_id", walletId)
                .put("limit", JSONObject.NULL));
        assertEmptyResult(new JSONObject()
                .put("method", "list_incoming_deposits")
                .put("wallet_id", walletId)
                .put("limit", JSONObject.NULL));

        JSONObject endpointPool = invoke(new JSONObject()
                .put("method", "set_lightd_endpoint_pool")
                .put("wallet_id", walletId)
                .put("url", "https://arrr.qortal.link:443/")
                .put("tls_pin_opt", JSONObject.NULL)
                .put("failover_endpoints", new JSONArray()
                        .put("https://arrr3.qortal.link:443/")));
        assertTrue(endpointPool.optBoolean("ok"));
        assertTrue(endpointPool.getJSONObject("result").optBoolean("acknowledged"));

        JSONObject details = invoke(new JSONObject()
                .put("method", "get_transaction_details")
                .put("wallet_id", walletId)
                .put("txid", "00".repeat(32)));
        assertTrue(details.optBoolean("ok"));
        assertTrue(details.isNull("result"));
    }

    private static void assertEmptyResult(JSONObject request) {
        JSONObject response = invoke(request);
        assertTrue(response.optBoolean("ok"));
        assertEquals(0, response.getJSONArray("result").length());
    }

    private static JSONObject invoke(JSONObject request) {
        return object(LiteWalletJni.invokeJson(request.toString(), false));
    }

    private static JSONObject object(String response) {
        return new JSONObject(response);
    }
}
