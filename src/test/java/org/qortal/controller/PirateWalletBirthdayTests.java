package org.qortal.controller;

import com.rust.litewalletjni.LiteWalletJni;
import org.junit.Test;
import org.qortal.crosschain.*;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real JNI registry/account reopening with an injected constructor-height boundary; loopback only. */
public class PirateWalletBirthdayTests extends Common {
    private static final ThreadLocal<Integer> initializationHeight = new ThreadLocal<>();
    private static PirateWallet open(byte[] entropy, int height) throws Exception {
        initializationHeight.set(height);
        try {
            return new PirateWallet(entropy, false) {
                @Override public Integer getHeight() {
                    Integer boundary = initializationHeight.get();
                    return boundary == null ? super.getHeight() : boundary;
                }
            };
        } finally { initializationHeight.remove(); }
    }
    @Test(timeout=60000) public void cancelledScanReopensAtBirthdayMinusOneAfterAccountSwitch() throws Exception { check(false); }
    @Test(timeout=60000) public void registryBirthdaySurvivesAChangedCoreDefault() throws Exception { check(true); }

    private void check(boolean changeDefault) throws Exception {
        assumeTrue(Boolean.getBoolean("qortal.runPirateWalletBirthdayTests"));
        String bundle = System.getProperty("qortal.pirateUnifiedBundlePath");
        assumeTrue(bundle != null && !bundle.isBlank());
        assertTrue(Path.of(System.getenv("PIRATE_DEBUG_LOG_PATH")).isAbsolute());
        Path library = Path.of(bundle).resolve(PirateChainWalletController.getRustLibFilename());
        assertTrue(Files.isRegularFile(library)); assertFalse(Files.isSymbolicLink(library));
        Path storage = Files.createTempDirectory("qortal-wallet-birthday-");
        Settings settings = Settings.getInstance();
        Field path = field(Settings.class, "walletsPath"), requested = field(Settings.class, "arrrNewWalletBirthday");
        Field defaultBirthday = field(Settings.class, "arrrDefaultBirthday"), provider = field(Bitcoiny.class, "blockchainProvider");
        PirateChain coin = PirateChain.getInstance();
        Object oldPath = path.get(settings), oldBirthday = requested.get(settings), oldDefault = defaultBirthday.get(settings), oldProvider = provider.get(coin);
        try (PirateUnifiedLoopbackLightwalletd fixture = new PirateUnifiedLoopbackLightwalletd(true)) {
            LiteWalletJni.loadLibraryFrom(library); assertTrue(LiteWalletJni.isLoaded());
            path.set(settings, storage.toString());
            int birthday = (int) PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT + 1;
            requested.set(settings, birthday); defaultBirthday.set(settings, birthday);
            URI endpoint = URI.create(fixture.endpoint());
            provider.set(coin, new PirateLightClient("PirateChain-MAIN", null, List.of(), Map.of()) {
                @Override public ChainableServer getCurrentServer() { return new Server(endpoint.getHost(), ChainableServer.ConnectionType.TCP, endpoint.getPort()); }
                @Override public int getCurrentHeight() { return (int) PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT + 100; }
            });
            byte[] first = new byte[32]; first[0] = 7;
            PirateWallet initial = open(first, birthday - 1);
            assertTrue("Birthday-1 is a valid unscanned wallet", initial.isReady());
            assertTrue(initial.getHeight() >= birthday - 1 && initial.getHeight() <= birthday);
            assertFalse("Initialization must not call an unscanned wallet synchronized", initial.isSynchronized());
            if (changeDefault) { requested.set(settings, null); defaultBirthday.set(settings, birthday + 100); }
            byte[] second = new byte[32]; second[0] = 8;
            if (!changeDefault) assertTrue(open(second, birthday - 1).isReady());
            PirateWallet reopened = open(first, birthday - 1);
            assertTrue("Reopening must retain the registry checkpoint", reopened.isReady());
            assertTrue(reopened.getHeight() >= birthday - 1 && reopened.getHeight() <= birthday);
            assertFalse("Earlier checkpoints must remain rejected", open(first, birthday - 2).isReady());
            assertFalse(reopened.isSynchronized());
            assertEquals(0, fixture.forbiddenRpcCount()); assertEquals(0, fixture.unexpectedRpcCount());
        } finally {
            path.set(settings, oldPath); requested.set(settings, oldBirthday); defaultBirthday.set(settings, oldDefault); provider.set(coin, oldProvider);
        }
    }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
}
