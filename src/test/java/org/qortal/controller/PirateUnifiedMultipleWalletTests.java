package org.qortal.controller;

import com.rust.litewalletjni.LiteWalletJni;
import org.json.JSONObject;
import org.junit.Test;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.ChainableServer;
import org.qortal.crosschain.PirateChain;
import org.qortal.crosschain.PirateLightClient;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;
import org.qortal.utils.Base58;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Pinned JNI, two public fixture seeds, isolated storage and loopback RPCs only. */
public class PirateUnifiedMultipleWalletTests extends Common {

    @Test(timeout = 90000)
    public void twoHubAccountsQueueAndRetainTheirOwnWalletData() throws Exception {
        assumeTrue(Boolean.getBoolean("qortal.runPirateUnifiedMultipleWalletTests"));
        String bundle = System.getProperty("qortal.pirateUnifiedBundlePath");
        assumeTrue(bundle != null && !bundle.isBlank());
        Path library = Path.of(bundle).resolve(PirateChainWalletController.getRustLibFilename());
        assertTrue(Files.isRegularFile(library));
        assertFalse(Files.isSymbolicLink(library));
        Path storage = Files.createTempDirectory("qortal-two-wallets-");
        String diagnostic = System.getenv("PIRATE_DEBUG_LOG_PATH");
        assertTrue(diagnostic != null && Path.of(diagnostic).isAbsolute());

        Settings settings = Settings.getInstance();
        Field walletsPath = field(Settings.class, "walletsPath");
        Field birthday = field(Settings.class, "arrrNewWalletBirthday");
        Field defaultBirthday = field(Settings.class, "arrrDefaultBirthday");
        Field provider = field(Bitcoiny.class, "blockchainProvider");
        Field instance = field(PirateChainWalletController.class, "instance");
        PirateChain chain = PirateChain.getInstance();
        Object oldPath = walletsPath.get(settings);
        Object oldBirthday = birthday.get(settings);
        Object oldDefaultBirthday = defaultBirthday.get(settings);
        Object oldProvider = provider.get(chain);
        Object oldInstance = instance.get(null);
        ExecutorService requests = Executors.newSingleThreadExecutor();

        try (PirateUnifiedLoopbackLightwalletd fixture = new PirateUnifiedLoopbackLightwalletd(true)) {
            LiteWalletJni.loadLibraryFrom(library);
            assertTrue(LiteWalletJni.isLoaded());
            walletsPath.set(settings, storage.toString());
            birthday.set(settings, (int) PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT);
            defaultBirthday.set(settings, (int) PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT);
            provider.set(chain, new FixtureProvider(fixture.endpoint()));
            PirateChainWalletController controller = new PirateChainWalletController();
            instance.set(null, controller);
            String first = entropy(7);
            String second = entropy(8);
            String firstAddress = initializeAndSync(controller, first);
            String secondAddress = initializeAndSync(controller, second);
            assertFalse(firstAddress.equals(secondAddress));

            controller.beginWalletUse(first, false, true, true);
            Future<String> secondRequest;
            try {
                CountDownLatch started = new CountDownLatch(1);
                secondRequest = requests.submit(() -> {
                    started.countDown();
                    controller.beginWalletUse(second, false, true, true);
                    try {
                        assertEquals(0L, new JSONObject(LiteWalletJni.execute("balance", "")).getLong("zbalance"));
                        return controller.getCurrentWallet().getWalletAddress();
                    } finally {
                        controller.endWalletUse();
                    }
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                Thread.sleep(100);
                assertFalse("Another account must queue while the first session is active", secondRequest.isDone());
                JSONObject status = new JSONObject(controller.getSyncStatusJson());
                assertEquals("Synchronized", status.getString("status"));
                assertEquals(firstAddress, controller.getCurrentWallet().getWalletAddress());
                assertEquals(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
                        new JSONObject(LiteWalletJni.execute("balance", "")).getLong("zbalance"));
            } finally {
                controller.endWalletUse();
            }
            assertEquals(secondAddress, secondRequest.get(15, TimeUnit.SECONDS));

            // Reopen each encrypted namespace repeatedly, as independent Hub polls do.
            for (int i = 0; i < 3; i++) {
                assertEquals(firstAddress, chain.getWalletAddress(first));
                assertEquals(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
                        chain.getWalletBalance(first).longValue());
                assertEquals(secondAddress, chain.getWalletAddress(second));
                assertEquals(0L, chain.getWalletBalance(second).longValue());
            }
            assertEquals(0, fixture.forbiddenRpcCount());
            assertEquals(0, fixture.unexpectedRpcCount());
        } finally {
            requests.shutdownNow();
            assertTrue(requests.awaitTermination(5, TimeUnit.SECONDS));
            instance.set(null, oldInstance);
            provider.set(chain, oldProvider);
            walletsPath.set(settings, oldPath);
            birthday.set(settings, oldBirthday);
            defaultBirthday.set(settings, oldDefaultBirthday);
        }
    }

    private static String initializeAndSync(PirateChainWalletController controller, String entropy) throws Exception {
        controller.beginWalletUse(entropy, false, false, true);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                JSONObject status = new JSONObject(LiteWalletJni.execute("syncStatus", ""));
                long height = new JSONObject(LiteWalletJni.execute("height", "")).getLong("height");
                if (!PirateChainWalletController.isSyncInProgress(status)
                        && height == PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT) {
                    return controller.getCurrentWallet().getWalletAddress();
                }
                Thread.sleep(25);
            }
            throw new AssertionError("Loopback wallet did not synchronize");
        } finally {
            controller.endWalletUse();
        }
    }

    private static String entropy(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return Base58.encode(bytes);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class FixtureProvider extends PirateLightClient {
        private final ChainableServer selected;

        private FixtureProvider(String endpoint) {
            super("PirateChain-MAIN", null, Collections.emptyList(), Map.of());
            URI uri = URI.create(endpoint);
            selected = new Server(uri.getHost(), ChainableServer.ConnectionType.TCP, uri.getPort());
        }

        @Override public int getCurrentHeight() { return (int) PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT; }
        @Override public ChainableServer getCurrentServer() { return selected; }
    }
}
