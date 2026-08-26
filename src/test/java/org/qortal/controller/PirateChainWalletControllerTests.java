package org.qortal.controller;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PirateChainWalletControllerTests {

    private static final String LEGACY_SIGNATURE =
            "EsfUw54perxkEtfoUoL7Z97XPrNsZRZXePVZPz3cwRm9qyEPSofD5KmgVpDqVitQp7LhnZRmL6z2V9hEe1YS45T";

    @Test
    public void testQdnWalletSignatureValidation() {
        assertTrue(PirateChainWalletController.isValidQdnWalletSignature(
                PirateChainWalletController.DEFAULT_QDN_WALLET_SIGNATURE));
        assertTrue(PirateChainWalletController.isValidQdnWalletSignature(LEGACY_SIGNATURE));
        assertFalse(PirateChainWalletController.isValidQdnWalletSignature(null));
        assertFalse(PirateChainWalletController.isValidQdnWalletSignature("not-base58-0"));
        assertFalse(PirateChainWalletController.isValidQdnWalletSignature("1234"));
    }

    @Test
    public void testWalletLibraryCacheKeyKeepsProductionAndTestBundlesSeparate() {
        String productionSignature = PirateChainWalletController.DEFAULT_QDN_WALLET_SIGNATURE;
        assertEquals(productionSignature.substring(0, 8),
                PirateChainWalletController.getWalletLibraryCacheKey(productionSignature));

        assertEquals(LEGACY_SIGNATURE, PirateChainWalletController.getWalletLibraryCacheKey(LEGACY_SIGNATURE));
    }

    @Test
    public void testNativeLibraryFilenameOnlySelectsMatchingPlatform() {
        String originalOsName = System.getProperty("os.name");
        String originalOsArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("os.arch", "aarch64");
            assertEquals("librust-macos-aarch64.dylib", PirateChainWalletController.getRustLibFilename());

            System.setProperty("os.name", "FreeBSD");
            System.setProperty("os.arch", "amd64");
            assertNull(PirateChainWalletController.getRustLibFilename());
        } finally {
            System.setProperty("os.name", originalOsName);
            System.setProperty("os.arch", originalOsArch);
        }
    }

    @Test
    public void testUnifiedSyncProgressUsesStartBlockAndStaysWithinTarget() {
        assertEquals(140L, PirateChainWalletController.calculateSyncHeight(100L, 200L, 40L));
        assertEquals(200L, PirateChainWalletController.calculateSyncHeight(100L, 200L, 150L));
        assertEquals(100L, PirateChainWalletController.calculateSyncHeight(100L, -1L, 0L));
    }

    @Test
    public void testSyncStatusAcceptsCurrentAndLegacyInProgressFields() {
        assertTrue(PirateChainWalletController.isSyncInProgress(new JSONObject("{\"in_progress\":true}")));
        assertTrue(PirateChainWalletController.isSyncInProgress(
                new JSONObject("{\"syncing\":true,\"synced_blocks\":2029001,\"total_blocks\":4095410}")));
        assertFalse(PirateChainWalletController.isSyncInProgress(new JSONObject("{\"scanned_height\":4095410}")));
    }
}
