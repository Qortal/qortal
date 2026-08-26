package org.qortal.crosschain;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PirateWalletMigrationTests {

    @Test
    public void archivesLegacySnapshotAndChecksumWithoutDeletingEither() throws IOException {
        Path temporaryDirectory = Files.createTempDirectory("qortal-pirate-wallet-migration-");
        try {
            Path legacyWallet = temporaryDirectory.resolve("wallet-entropy.dat");
            Path legacyChecksum = temporaryDirectory.resolve("wallet-entropy.dat.sha256");
            byte[] walletContent = "legacy-wallet".getBytes(StandardCharsets.US_ASCII);
            byte[] checksumContent = "legacy-checksum".getBytes(StandardCharsets.US_ASCII);
            Files.write(legacyWallet, walletContent);
            Files.write(legacyChecksum, checksumContent);

            Path archive = PirateWallet.archiveLegacyWalletCache(legacyWallet);

            assertFalse(Files.exists(legacyWallet));
            assertFalse(Files.exists(legacyChecksum));
            assertTrue(Files.isRegularFile(archive));
            assertArrayEquals(walletContent, Files.readAllBytes(archive));
            assertArrayEquals(checksumContent, Files.readAllBytes(archive.resolveSibling(archive.getFileName() + ".sha256")));
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    @Test
    public void preservesExistingArchiveByChoosingANewArchiveName() throws IOException {
        Path temporaryDirectory = Files.createTempDirectory("qortal-pirate-wallet-migration-");
        try {
            Path legacyWallet = temporaryDirectory.resolve("wallet-entropy.dat");
            Path originalArchive = temporaryDirectory.resolve("wallet-entropy.dat.legacy-before-unified");
            Files.write(legacyWallet, "new-legacy-wallet".getBytes(StandardCharsets.US_ASCII));
            Files.write(originalArchive, "older-archive".getBytes(StandardCharsets.US_ASCII));

            Path archive = PirateWallet.archiveLegacyWalletCache(legacyWallet);

            assertTrue(archive.getFileName().toString().endsWith(".legacy-before-unified-1"));
            assertArrayEquals("older-archive".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(originalArchive));
            assertArrayEquals("new-legacy-wallet".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(archive));
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    @Test
    public void unifiedAddressComesFromTheSameExportEntryAsItsKey() {
        String export = "[{\"address\":\"zironwood-active\",\"private_key\":\"secret\"},"
                + "{\"address\":\"zsapling-old\",\"private_key\":\"old-secret\"}]";

        assertEquals("zironwood-active", PirateWallet.getUnifiedExportAddress(export));
        assertNull(PirateWallet.getUnifiedExportAddress("[]"));
        assertNull(PirateWallet.getUnifiedExportAddress("not-json"));
    }

    @Test
    public void freshUnifiedWalletUsesLiveHeightButRecoveryPathsKeepConfiguredBirthday() {
        assertEquals(4_095_410, PirateWallet.chooseUnifiedWalletBirthday(
                2_000_000, null, false, false, false, 4_095_410));
        assertEquals(2_000_000, PirateWallet.chooseUnifiedWalletBirthday(
                2_000_000, null, false, true, false, 4_095_410));
        assertEquals(2_000_000, PirateWallet.chooseUnifiedWalletBirthday(
                2_000_000, null, false, false, true, 4_095_410));
    }

    @Test
    public void explicitNewWalletBirthdaySupportsHistoricRecovery() {
        assertEquals(1_500_000, PirateWallet.chooseUnifiedWalletBirthday(
                2_000_000, 1_500_000, false, false, false, 4_095_410));
        assertEquals(2_000_000, PirateWallet.chooseUnifiedWalletBirthday(
                2_000_000, null, false, false, false, null));
    }

    private static void deleteTemporaryDirectory(Path temporaryDirectory) throws IOException {
        try (var paths = Files.walk(temporaryDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to delete test path " + path, e);
                }
            });
        }
    }
}
