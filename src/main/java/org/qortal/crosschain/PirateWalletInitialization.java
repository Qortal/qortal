package org.qortal.crosschain;

import org.json.JSONObject;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A public scan-height choice; contains no wallet keys and never rewrites a wallet. */
public final class PirateWalletInitialization {
    private static final String CHOICE_FILE = "scan-start-height";
    private final Path directory;
    private final Path legacy;

    public PirateWalletInitialization(byte[] entropy) {
        String hash = Base58.encode(Crypto.digest(entropy));
        Path root = Path.of(Settings.getInstance().getWalletsPath(), "PirateChain");
        this.directory = root.resolve("unified").resolve(hash);
        this.legacy = root.resolve("wallet-" + hash + ".dat");
    }

    public JSONObject status() throws IOException {
        return status(this.directory, this.legacy, Settings.getInstance().getArrrDefaultBirthday());
    }

    public JSONObject select(int birthday) throws IOException {
        return select(this.directory, this.legacy, birthday, Settings.getInstance().getArrrDefaultBirthday());
    }

    public Integer savedBirthday() throws IOException {
        return savedBirthday(this.directory);
    }

    static Integer savedBirthday(Path directory) throws IOException {
        Path choice = directory.resolve(CHOICE_FILE);
        if (!Files.exists(choice, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(choice, LinkOption.NOFOLLOW_LINKS) || Files.size(choice) > 16)
            throw new IOException("Invalid Pirate wallet scan-height choice");
        try {
            int birthday = Integer.parseInt(Files.readString(choice).trim());
            if (birthday < 1) throw new NumberFormatException();
            return birthday;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Pirate wallet scan-height choice");
        }
    }

    static JSONObject status(Path directory, Path legacy, int defaultBirthday) throws IOException {
        boolean existing = Files.exists(directory.resolve("wallet_registry.db"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        Integer birthday = savedBirthday(directory);
        return new JSONObject().put("existingWallet", existing)
                .put("needsSelection", !existing && birthday == null)
                .put("birthday", birthday == null ? JSONObject.NULL : birthday)
                .put("defaultBirthday", Math.max(1, defaultBirthday));
    }

    static JSONObject select(Path directory, Path legacy, int birthday, int defaultBirthday) throws IOException {
        if (birthday < 1) throw new IllegalArgumentException("Birthday must be positive");
        JSONObject current = status(directory, legacy, defaultBirthday);
        if (!current.getBoolean("needsSelection")) return current;
        Files.createDirectories(directory);
        // First choice wins. A partial/corrupt write fails closed on subsequent
        // reads; neither this endpoint nor initialization deletes existing state.
        Files.writeString(directory.resolve(CHOICE_FILE), Integer.toString(birthday),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return status(directory, legacy, defaultBirthday);
    }
}
