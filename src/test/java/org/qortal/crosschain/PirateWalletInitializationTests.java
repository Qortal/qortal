package org.qortal.crosschain;

import org.junit.Test;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import static org.junit.Assert.*;

public class PirateWalletInitializationTests {
    @Test
    public void inspectingFreshStorageDoesNotInitializeOrCreateFiles() throws Exception {
        Path root = Files.createTempDirectory("pirate-init-choice-");
        try {
            Path directory = root.resolve("wallet");
            JSONObject state = PirateWalletInitialization.status(directory, root.resolve("legacy"), 2000000);
            assertTrue(state.getBoolean("needsSelection"));
            assertEquals(2000000, state.getInt("defaultBirthday"));
            assertFalse(Files.exists(directory));
        } finally { remove(root); }
    }

    @Test
    public void explicitChoiceSurvivesReopenAndCannotBeSilentlyOverwritten() throws Exception {
        Path root = Files.createTempDirectory("pirate-init-choice-");
        try {
            Path directory = root.resolve("wallet");
            Path legacy = root.resolve("legacy");
            assertFalse(PirateWalletInitialization.select(directory, legacy, 4224000, 2000000)
                    .getBoolean("needsSelection"));
            assertEquals(Integer.valueOf(4224000), PirateWalletInitialization.savedBirthday(directory));
            PirateWalletInitialization.select(directory, legacy, 2000000, 2000000);
            assertEquals(Integer.valueOf(4224000), PirateWalletInitialization.savedBirthday(directory));
            assertFalse(Files.exists(directory.resolve("wallet_registry.db")));
        } finally { remove(root); }
    }

    @Test
    public void existingUnifiedOrLegacyWalletIsNeverReinitialized() throws Exception {
        Path root = Files.createTempDirectory("pirate-init-choice-");
        try {
            Path directory = Files.createDirectory(root.resolve("wallet"));
            Path legacy = root.resolve("legacy");
            Path registry = directory.resolve("wallet_registry.db");
            Files.writeString(registry, "registry");
            assertTrue(PirateWalletInitialization.status(directory, legacy, 2000000).getBoolean("existingWallet"));
            PirateWalletInitialization.select(directory, legacy, 4224000, 2000000);
            assertNull(PirateWalletInitialization.savedBirthday(directory));
            assertEquals("registry", Files.readString(registry));
            Files.delete(registry);
            Files.writeString(legacy, "legacy-wallet");
            PirateWalletInitialization.select(directory, legacy, 4224000, 2000000);
            assertNull(PirateWalletInitialization.savedBirthday(directory));
            assertEquals("legacy-wallet", Files.readString(legacy));
        } finally { remove(root); }
    }

    @Test
    public void invalidSavedChoiceFailsClosed() throws Exception {
        Path root = Files.createTempDirectory("pirate-init-choice-");
        try {
            for (String value : new String[] { "", "0", "-1", "garbage", "2147483648" }) {
                Files.writeString(root.resolve("scan-start-height"), value);
                try {
                    PirateWalletInitialization.status(root, root.resolve("legacy"), 2000000);
                    fail("Invalid choice must not fall back to current height");
                } catch (IOException expected) { }
            }
        } finally { remove(root); }
    }

    private static void remove(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}
