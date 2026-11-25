package org.qortal.controller;

import com.rust.litewalletjni.LiteWalletJni;
import io.grpc.Status;
import org.json.JSONObject;
import org.junit.Test;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.ChainableServer;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.PirateChain;
import org.qortal.crosschain.PirateLightClient;
import org.qortal.crosschain.PirateWallet;
import org.qortal.test.common.Common;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real pinned JNI cache, isolated storage and loopback-only history; no transaction RPCs. */
public class PirateUnifiedWalletFreshnessTests extends Common {

    @Test(timeout = 60000)
    public void cachedNativeTipCannotSuppressSyncOrApproveStaleSpending() throws Exception {
        assumeTrue(Boolean.getBoolean("qortal.runPirateUnifiedWalletFreshnessTests"));
        String bundlePath = System.getProperty("qortal.pirateUnifiedBundlePath");
        assumeTrue(bundlePath != null && !bundlePath.isBlank());
        String diagnosticPath = System.getenv("PIRATE_DEBUG_LOG_PATH");
        assertTrue("Native tests require a dedicated absolute PIRATE_DEBUG_LOG_PATH",
                diagnosticPath != null && Path.of(diagnosticPath).isAbsolute());
        Path library = Path.of(bundlePath).resolve(PirateChainWalletController.getRustLibFilename());
        assertTrue(Files.isRegularFile(library));
        assertFalse(Files.isSymbolicLink(library));
        Path storage = Files.createTempDirectory("qortal-native-freshness-");

        try (PirateUnifiedLoopbackLightwalletd fixture = new PirateUnifiedLoopbackLightwalletd(true)) {
            LiteWalletJni.loadLibraryFrom(library);
            assertTrue(new JSONObject(LiteWalletJni.configurestorage(storage.toString(), "freshness-test"))
                    .optBoolean("initialized"));
            byte[] entropy = new byte[32];
            Arrays.fill(entropy, (byte) 7);
            String seed = new JSONObject(LiteWalletJni.getseedphrasefromentropyb64(
                    Base64.getEncoder().encodeToString(entropy))).getString("seedPhrase");
            JSONObject initialized = new JSONObject(LiteWalletJni.initfromseed(fixture.endpoint(), "", seed,
                    Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""));
            assertTrue(initialized.has("wallet_id"));
            assertEquals("success", new JSONObject(LiteWalletJni.execute("sync", "")).getString("result"));
            awaitFixtureSync();
            int scanned = new JSONObject(LiteWalletJni.execute("height", "")).getInt("height");
            int cached = new JSONObject(LiteWalletJni.execute("info", "")).getInt("latest_block_height");
            assertEquals(PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT, scanned);
            assertEquals(scanned, cached);

            HeightClient provider = new HeightClient(fixture.endpoint(), scanned + 141);
            PirateChain chain = PirateChain.getInstance();
            Field providerField = Bitcoiny.class.getDeclaredField("blockchainProvider");
            providerField.setAccessible(true);
            Object previousProvider = providerField.get(chain);
            providerField.set(chain, provider);
            try {
                // Null entropy prevents initialization or storage changes. Height getters
                // query the already-configured isolated native wallet, as Core does.
                PirateWallet wallet = new PirateWallet(null, false);
                assertTrue(wallet.usesPersistentUnifiedStorage());
                Field endpoint = PirateWallet.class.getDeclaredField("serverUri");
                endpoint.setAccessible(true);
                endpoint.set(wallet, fixture.endpoint().replaceAll("/$", "") + "/");

                assertFalse("Cached JNI height must not approve an already-expired send", wallet.isSynchronized());
                assertEquals(Integer.valueOf(scanned + 141), wallet.getChainTip());
                assertTrue("Idle stale wallet must be eligible for incremental sync",
                        PirateChainWalletController.shouldStartUnifiedSync(
                                new JSONObject("{\"in_progress\":false}"), wallet.isSynchronized()));

                provider.height = scanned + 2;
                assertTrue("Retain the existing two-block tolerance", wallet.isSynchronized());
                provider.height = scanned + 3;
                assertFalse(wallet.isSynchronized());
                provider.height = scanned - 10;
                assertFalse("A wallet ahead after a rollback must synchronize", wallet.isSynchronized());

                provider.height = scanned;
                provider.failure = true;
                assertNull("Network failure must not fall back to cached native info", wallet.getChainTip());
                assertFalse(wallet.isSynchronized());
                provider.failure = false;
                provider.rotate = true;
                assertNull("A changed endpoint requires native alignment", wallet.getChainTip());
                provider.rotate = false;
                assertTrue(wallet.isSynchronized());

                // Legacy mode still uses its own info response, without the new live query.
                Field unified = PirateWallet.class.getDeclaredField("unifiedWallet");
                unified.setAccessible(true);
                unified.set(wallet, false);
                int queries = provider.queries;
                assertEquals(Integer.valueOf(cached), wallet.getChainTip());
                assertEquals(queries, provider.queries);
            } finally {
                providerField.set(chain, previousProvider);
                LiteWalletJni.invokeJson(new JSONObject().put("method", "cancel_sync")
                        .put("wallet_id", initialized.getString("wallet_id")).toString(), false);
            }
            assertEquals(0, fixture.forbiddenRpcCount());
            assertEquals(0, fixture.unexpectedRpcCount());
        }
    }

    private static void awaitFixtureSync() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            JSONObject status = new JSONObject(LiteWalletJni.execute("syncStatus", ""));
            if (!PirateChainWalletController.isSyncInProgress(status)
                    && new JSONObject(LiteWalletJni.execute("height", "")).getLong("height")
                    == PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT) return;
            Thread.sleep(50);
        }
        fail("Isolated native wallet failed to complete its fixture sync");
    }

    private static final class HeightClient extends PirateLightClient {
        private final ChainableServer selected;
        private int height;
        private int queries;
        private boolean failure;
        private boolean rotate;

        private HeightClient(String endpoint, int height) {
            super("PirateChain-MAIN", null, Collections.emptyList(), Map.of());
            URI uri = URI.create(endpoint);
            this.selected = new PirateLightClient.Server(uri.getHost(), ChainableServer.ConnectionType.TCP, uri.getPort());
            this.height = height;
        }

        @Override public int getCurrentHeight() throws ForeignBlockchainException {
            queries++;
            if (failure) throw Status.UNAVAILABLE.withDescription("fixture unavailable").asRuntimeException();
            return height;
        }

        @Override public ChainableServer getCurrentServer() {
            return rotate ? new PirateLightClient.Server("changed.invalid", ChainableServer.ConnectionType.SSL, 443) : selected;
        }
    }
}
