package org.qortal.crosschain;

import com.rust.litewalletjni.LiteWalletJni;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.DecoderException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.controller.PirateChainWalletController;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class PirateWallet {

    protected static final Logger LOGGER = LogManager.getLogger(PirateWallet.class);
    private static final int MIN_WALLET_BYTES = 1024;
    private static final long VALIDATOR_TIMEOUT_MS = 45_000L;
    private static final String UNIFIED_WALLET_REGISTRY_FILENAME = "wallet_registry.db";

    private byte[] entropyBytes;
    private final boolean isNullSeedWallet;
    private final boolean unifiedWallet;
    private String seedPhrase;
    private boolean ready = false;

    private String params;
    private String saplingOutput64;
    private String saplingSpend64;

    private final static String COIN_PARAMS_FILENAME = "coinparams.json";
    private final static String SAPLING_OUTPUT_FILENAME = "saplingoutput_base64";
    private final static String SAPLING_SPEND_FILENAME = "saplingspend_base64";
    private static final String CHECKSUM_PREFIX = "sha256_base64=";
    private static final String CHECKSUM_SIZE_PREFIX = "size=";

    public PirateWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
        this.entropyBytes = entropyBytes;
        this.isNullSeedWallet = isNullSeedWallet;
        this.unifiedWallet = Settings.getInstance().isPirateChainWalletUnified();

        Path libDirectory = PirateChainWalletController.getRustLibOuterDirectory();
        if (this.unifiedWallet) {
            // Pirate Unified Wallet retains these JNI arguments for source
            // compatibility, but no longer reads the legacy parameter files.
            this.params = "";
            this.saplingOutput64 = "";
            this.saplingSpend64 = "";
        } else {
            if (!Files.exists(Paths.get(libDirectory.toString(), COIN_PARAMS_FILENAME))) {
                return;
            }

            this.params = Files.readString(Paths.get(libDirectory.toString(), COIN_PARAMS_FILENAME));
            this.saplingOutput64 = Files.readString(Paths.get(libDirectory.toString(), SAPLING_OUTPUT_FILENAME));
            this.saplingSpend64 = Files.readString(Paths.get(libDirectory.toString(), SAPLING_SPEND_FILENAME));
        }

        this.ready = this.initialize();
    }

    private boolean initialize() {
        try {
            LiteWalletJni.initlogging();

            if (this.entropyBytes == null) {
                return false;
            }

            // Pick a random server
            BitcoinyBlockchainProvider provider = PirateChain.getInstance().blockchainProvider;
            ChainableServer server = ensureServerAvailable(provider);
            if (server == null) {
                LOGGER.info("Unable to initialize Pirate wallet: no lightwallet server available");
                return false;
            }
            String serverUri = String.format("https://%s:%d/", server.getHostName(), server.getPort());
            LOGGER.info("Initializing Pirate wallet using server {} (nullSeed={})", serverUri, this.isNullSeedWallet);

            // Pirate library uses base64 encoding
            String entropy64 = Base64.toBase64String(this.entropyBytes);

            // Check before configurestorage(): configuring a previously unused
            // namespace creates its registry file. A registry means that the
            // unified client, rather than Qortal, owns the wallet's birthday.
            boolean hasUnifiedWalletRegistry = this.unifiedWallet && this.hasUnifiedWalletRegistry();
            boolean hasLegacyWalletCache = this.unifiedWallet && Files.exists(this.getCurrentWalletPath());
            if (this.unifiedWallet && !this.configureUnifiedStorage()) {
                return false;
            }

            // Derive seed phrase from entropy bytes
            String inputSeedResponse = LiteWalletJni.getseedphrasefromentropyb64(entropy64);
            JSONObject inputSeedJson = parseJsonObject(inputSeedResponse, "getseedphrasefromentropyb64");
            if (inputSeedJson == null) {
                LOGGER.info("Unable to initialize Pirate Chain wallet: seed phrase response was not valid JSON");
                return false;
            }
            String inputSeedPhrase = null;
            if (inputSeedJson.has("seedPhrase")) {
                inputSeedPhrase = inputSeedJson.getString("seedPhrase");
            }

            int configuredBirthday = Settings.getInstance().getArrrDefaultBirthday();
            boolean forceFullRescan = !this.isNullSeedWallet && configuredBirthday <= 1;

            if (this.unifiedWallet) {
                if (forceFullRescan) {
                    LOGGER.warn("A full rescan requested by birthday {} is not yet supported for persistent Pirate Unified Wallet storage", configuredBirthday);
                }

                Integer requestedNewWalletBirthday = Settings.getInstance().getArrrNewWalletBirthday();
                boolean fetchCurrentHeight = this.isNullSeedWallet
                        || (!hasUnifiedWalletRegistry && !hasLegacyWalletCache && requestedNewWalletBirthday == null);
                Integer currentHeight = fetchCurrentHeight ? this.getCurrentLightwalletHeight(provider) : null;
                int birthday = PirateWallet.chooseUnifiedWalletBirthday(
                        configuredBirthday,
                        requestedNewWalletBirthday,
                        this.isNullSeedWallet,
                        hasUnifiedWalletRegistry,
                        hasLegacyWalletCache,
                        currentHeight);

                if (!this.isNullSeedWallet && !hasUnifiedWalletRegistry && !hasLegacyWalletCache
                        && requestedNewWalletBirthday == null && currentHeight != null) {
                    LOGGER.info("Creating fresh Pirate Unified Wallet at current lightwallet height {}", birthday);
                } else {
                    LOGGER.info("Initializing Pirate Unified Wallet with birthday {} (registryExists={}, legacyCacheExists={})",
                            birthday, hasUnifiedWalletRegistry, hasLegacyWalletCache);
                }

                if (!this.initFromSeed(serverUri, inputSeedPhrase, birthday)) {
                    LOGGER.info("Pirate wallet initFromSeed failed (birthday={})", birthday);
                    return false;
                }

                Integer ourHeight = this.getHeight();
                if (ourHeight == null || ourHeight <= 0) {
                    LOGGER.info("Pirate Unified Wallet height unavailable after init (height={})", ourHeight);
                    return false;
                }
                if (!this.isNullSeedWallet && birthday > 1 && ourHeight < birthday) {
                    LOGGER.warn("Pirate Unified Wallet initialized below birthday {} (height {}).",
                            birthday, ourHeight);
                    return false;
                }
            } else {
                String wallet = this.load();
                boolean loadedFromCache = wallet != null;
                if (wallet != null && forceFullRescan) {
                    LOGGER.info("Forcing full rescan due to configured birthday {}", configuredBirthday);
                    this.deleteWalletCache();
                    wallet = null;
                    loadedFromCache = false;
                }
                if (wallet == null) {
                    // Wallet doesn't exist, so create a new one
                    LOGGER.info("Creating new Pirate wallet (birthday={})", configuredBirthday);

                    int birthday = configuredBirthday;
                    if (this.isNullSeedWallet) {
                        try {
                            // Attempt to set birthday to the current block for null seed wallets
                            birthday = PirateChain.getInstance().blockchainProvider.getCurrentHeight();
                        } catch (ForeignBlockchainException e) {
                            // Use the default height
                        }
                    }

                    // Initialize new wallet
                    if (!this.initFromSeed(serverUri, inputSeedPhrase, birthday)) {
                        LOGGER.info("Pirate wallet initFromSeed failed (birthday={})", birthday);
                        return false;
                    }
                } else {
                    // Restore existing wallet
                    String response = LiteWalletJni.initfromb64(serverUri, params, wallet, saplingOutput64, saplingSpend64);
                    if (response != null && !isInitSuccess(response)) {
                        if (isBufferFillError(response)) {
                            LOGGER.info("Pirate wallet init reported buffer error; backing up cache and retrying");
                            this.backupAndDeleteWalletCache();
                            response = LiteWalletJni.initfromb64(serverUri, params, wallet, saplingOutput64,
                                    saplingSpend64);
                        }
                        if (response != null && !isInitSuccess(response)) {
                            LOGGER.info("Unable to initialize Pirate Chain wallet at {}: {}", serverUri, response);
                            return false;
                        }
                    }
                    LOGGER.info("Loaded Pirate wallet from cache");
                    this.seedPhrase = inputSeedPhrase;
                }

                // Check that we're able to communicate with the library
                Integer ourHeight = this.getHeight();
                if (ourHeight == null || ourHeight <= 0) {
                    LOGGER.info("Pirate wallet height unavailable after init (height={})", ourHeight);
                    return false;
                }
                LOGGER.info("Pirate wallet height after init: {}", ourHeight);

                if (!this.isNullSeedWallet && configuredBirthday > 1 && ourHeight < configuredBirthday) {
                    if (loadedFromCache) {
                        LOGGER.warn("Pirate wallet height {} below configured birthday {}. Recreating wallet cache.",
                                ourHeight, configuredBirthday);
                        this.deleteWalletCache();
                        if (!this.initFromSeed(serverUri, inputSeedPhrase, configuredBirthday)) {
                            LOGGER.info("Pirate wallet re-init from seed failed (birthday={})", configuredBirthday);
                            return false;
                        }
                        ourHeight = this.getHeight();
                    }

                    if (ourHeight == null || ourHeight <= 0 || ourHeight < configuredBirthday) {
                        LOGGER.warn("Pirate wallet initialized below configured birthday {} (height {}).",
                                configuredBirthday, ourHeight);
                        return false;
                    }
                }
            }

            return true;

        } catch (IOException | JSONException | UnsatisfiedLinkError e) {
            LOGGER.info("Unable to initialize Pirate Chain wallet: {}", e.getMessage());
        }

        return false;
    }

    private boolean configureUnifiedStorage() throws IOException {
        String encryptionKey = this.getEncryptionKey();
        if (encryptionKey == null) {
            LOGGER.info("Unable to configure Pirate Unified Wallet storage: missing encryption key");
            return false;
        }

        Path storageDirectory = this.getUnifiedWalletDirectory();
        Files.createDirectories(storageDirectory);
        String response = LiteWalletJni.configurestorage(storageDirectory.toString(), encryptionKey);
        JSONObject json = parseJsonObject(response, "configurestorage");
        if (json == null || !json.optBoolean("initialized", false)) {
            LOGGER.info("Unable to configure Pirate Unified Wallet storage: {}", response);
            return false;
        }

        this.enableUnifiedDebugLoggingIfConfigured();

        return true;
    }

    private void enableUnifiedDebugLoggingIfConfigured() {
        if (!Settings.getInstance().isPirateChainWalletDebugLogging()) {
            return;
        }

        try {
            JSONObject request = new JSONObject()
                    .put("method", "set_debug_logging_enabled")
                    .put("enabled", true);
            JSONObject response = parseJsonObject(LiteWalletJni.invokeJson(request.toString(), false),
                    "set_debug_logging_enabled");
            if (response != null && response.optBoolean("ok", false)) {
                LOGGER.info("Pirate Unified Wallet redacted diagnostic logging enabled");
            } else {
                LOGGER.info("Unable to enable Pirate Unified Wallet diagnostic logging");
            }
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            LOGGER.info("Unable to enable Pirate Unified Wallet diagnostic logging: {}", e.getMessage());
        }
    }

    private boolean hasUnifiedWalletRegistry() {
        return Files.exists(this.getUnifiedWalletDirectory().resolve(UNIFIED_WALLET_REGISTRY_FILENAME));
    }

    private Integer getCurrentLightwalletHeight(BitcoinyBlockchainProvider provider) {
        try {
            int height = provider.getCurrentHeight();
            return height > 0 ? height : null;
        } catch (ForeignBlockchainException e) {
            LOGGER.info("Unable to determine current lightwallet height for new Pirate Unified Wallet: {}", e.getMessage());
            return null;
        }
    }

    /**
     * A stored Unified wallet or a legacy cache might represent historic funds,
     * so it must retain the conservative configured birthday. Only a clean,
     * entropy-backed Unified namespace can safely use the current tip by
     * default. Recovery callers can set arrrNewWalletBirthday explicitly.
     */
    static int chooseUnifiedWalletBirthday(int configuredBirthday, Integer requestedNewWalletBirthday,
                                           boolean nullSeedWallet, boolean hasUnifiedWalletRegistry,
                                           boolean hasLegacyWalletCache, Integer currentLightwalletHeight) {
        if (nullSeedWallet) {
            return currentLightwalletHeight != null && currentLightwalletHeight > 0
                    ? currentLightwalletHeight : configuredBirthday;
        }

        if (hasUnifiedWalletRegistry || hasLegacyWalletCache) {
            return configuredBirthday;
        }

        if (requestedNewWalletBirthday != null && requestedNewWalletBirthday > 0) {
            return requestedNewWalletBirthday;
        }

        return currentLightwalletHeight != null && currentLightwalletHeight > 0
                ? currentLightwalletHeight : configuredBirthday;
    }

    private boolean initFromSeed(String serverUri, String inputSeedPhrase, int birthday) {
        String birthdayString = String.format("%d", birthday);
        String outputSeedResponse = LiteWalletJni.initfromseed(serverUri, this.params, inputSeedPhrase, birthdayString,
                this.saplingOutput64, this.saplingSpend64); // Thread-safe.
        String outputSeedPhrase = parseSeedPhrase(outputSeedResponse, "initfromseed");
        if (!this.unifiedWallet && outputSeedPhrase == null && isBufferFillError(outputSeedResponse)) {
            LOGGER.info("Pirate wallet initfromseed reported buffer error; backing up cache and retrying");
            this.backupAndDeleteWalletCache();
            outputSeedResponse = LiteWalletJni.initfromseed(serverUri, this.params, inputSeedPhrase, birthdayString,
                    this.saplingOutput64, this.saplingSpend64); // Thread-safe.
            outputSeedPhrase = parseSeedPhrase(outputSeedResponse, "initfromseed");
        }
        if (!this.unifiedWallet && outputSeedPhrase == null && isWalletAlreadyExistsError(outputSeedResponse)) {
            LOGGER.info("Clearing litewallet cache after initfromseed reported existing wallet");
            this.backupAndDeleteLitewalletCache();
            outputSeedResponse = LiteWalletJni.initfromseed(serverUri, this.params, inputSeedPhrase, birthdayString,
                    this.saplingOutput64, this.saplingSpend64); // Thread-safe.
            outputSeedPhrase = parseSeedPhrase(outputSeedResponse, "initfromseed");
        }
        if (outputSeedPhrase == null) {
            LOGGER.info("Unable to initialize Pirate Chain wallet: init response did not contain a seed phrase");
            return false;
        }

        // Ensure seed phrase in response matches supplied seed phrase
        if (inputSeedPhrase == null || !Objects.equals(inputSeedPhrase, outputSeedPhrase)) {
            LOGGER.info("Unable to initialize Pirate Chain wallet: seed phrases do not match, or are null");
            return false;
        }

        this.seedPhrase = outputSeedPhrase;
        return true;
    }

    private boolean isBufferFillError(String response) {
        return response != null && response.contains("failed to fill whole buffer");
    }

    private boolean isInitSuccess(String response) {
        if (response == null) {
            return false;
        }
        return response.contains("\"initialized\":true") || response.contains("\"initalized\":true");
    }

    private boolean isWalletAlreadyExistsError(String response) {
        if (response == null) {
            return false;
        }
        String normalized = response.toLowerCase(Locale.ROOT);
        return normalized.contains("wallet already exists");
    }

    private void deleteWalletCache() {
        if (this.unifiedWallet) {
            LOGGER.warn("Refusing to delete active Pirate Unified Wallet storage; the legacy cache reset is not applicable");
            return;
        }
        Path walletPath = this.getCurrentWalletPath();
        boolean walletExists = Files.exists(walletPath);
        LOGGER.info(
                "Deleting Pirate wallet cache file {} (exists={}, walletsPath={})",
                walletPath,
                walletExists,
                Settings.getInstance().getWalletsPath());
        if (walletExists) {
            try {
                LOGGER.info("Pirate wallet cache file size={}, lastModified={}",
                        Files.size(walletPath),
                        Files.getLastModifiedTime(walletPath));
            } catch (IOException e) {
                LOGGER.info("Unable to read Pirate wallet cache metadata at {}: {}", walletPath, e.getMessage());
            }
        }
        try {
            Files.deleteIfExists(walletPath);
        } catch (IOException e) {
            LOGGER.info("Unable to delete Pirate Chain wallet cache at {}: {}", walletPath, e.getMessage());
        }
        boolean walletExistsAfterDelete = Files.exists(walletPath);
        LOGGER.info("Pirate wallet cache file exists after delete: {}", walletExistsAfterDelete);
        if (walletExistsAfterDelete) {
            Path backupPath = walletPath.resolveSibling(walletPath.getFileName() + ".bak");
            try {
                Files.move(walletPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Moved Pirate wallet cache file to {}", backupPath);
            } catch (IOException e) {
                LOGGER.info("Unable to move Pirate wallet cache file to {}: {}", backupPath, e.getMessage());
            }
        }

    }

    private void backupAndDeleteWalletCache() {
        if (this.unifiedWallet) {
            LOGGER.warn("Refusing to delete active Pirate Unified Wallet storage; the legacy cache recovery is not applicable");
            return;
        }
        Path walletPath = this.getCurrentWalletPath();
        if (walletPath == null) {
            return;
        }
        if (!Files.exists(walletPath)) {
            LOGGER.info("No Pirate wallet cache file to backup at {}", walletPath);
            return;
        }
        Path backupPath = walletPath.resolveSibling(".bak-" + walletPath.getFileName());
        try {
            Files.copy(walletPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Backed up Pirate wallet cache file to {}", backupPath);
        } catch (IOException e) {
            LOGGER.info("Unable to backup Pirate wallet cache file to {}: {}", backupPath, e.getMessage());
        }
        try {
            Files.deleteIfExists(walletPath);
            LOGGER.info("Deleted Pirate wallet cache file after backup: {}", walletPath);
        } catch (IOException e) {
            LOGGER.info("Unable to delete Pirate wallet cache file {}: {}", walletPath, e.getMessage());
        }
    }

    private ChainableServer ensureServerAvailable(BitcoinyBlockchainProvider provider) {
        ChainableServer server = provider.getCurrentServer();
        if (server != null) {
            return server;
        }

        Set<ChainableServer> candidates = new HashSet<>(provider.getServers());
        candidates.removeAll(provider.getUselessServers());
        if (candidates.isEmpty()) {
            candidates = new HashSet<>(provider.getServers());
        }
        if (candidates.isEmpty()) {
            return null;
        }

        List<ChainableServer> candidateList = new ArrayList<>(candidates);
        ChainableServer candidate = candidateList.get(new Random().nextInt(candidateList.size()));
        LOGGER.info("Selecting Pirate lightwallet server {} (no current server)", candidate);
        try {
            provider.setCurrentServer(candidate, "PirateWallet");
        } catch (ForeignBlockchainException e) {
            LOGGER.info("Unable to set Pirate lightwallet server {}: {}", candidate, e.getMessage());
            return null;
        }
        return provider.getCurrentServer();
    }

    public void resetCache() {
        LOGGER.info("Clearing Pirate wallet cache");
        this.deleteWalletCache();
    }

    private void deleteLitewalletCache() {
        Path pirateDir = this.getLitewalletDataDirectory();
        Path defaultWalletPath = pirateDir.resolve("arrr-light-wallet.dat");
        LOGGER.info("Leaving litewallet cache file intact: {} (exists={})", defaultWalletPath,
                Files.exists(defaultWalletPath));

        Path qortalCachePath = Paths.get(Settings.getInstance().getWalletsPath(), "PirateChain", "lib");
        LOGGER.info("Leaving Pirate wallet lib cache path intact: {} (exists={})", qortalCachePath,
                Files.exists(qortalCachePath));
    }

    private void backupAndDeleteLitewalletCache() {
        Path pirateDir = this.getLitewalletDataDirectory();
        Path defaultWalletPath = pirateDir.resolve("arrr-light-wallet.dat");
        if (Files.exists(defaultWalletPath)) {
            Path backupPath = defaultWalletPath.resolveSibling("arrr-light-wallet.dat.bak");
            try {
                Files.copy(defaultWalletPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Backed up litewallet cache file to {}", backupPath);
            } catch (IOException e) {
                LOGGER.info("Unable to backup litewallet cache file {}: {}", defaultWalletPath, e.getMessage());
            }
            try {
                Files.deleteIfExists(defaultWalletPath);
                LOGGER.info("Deleted litewallet cache file {}", defaultWalletPath);
            } catch (IOException e) {
                LOGGER.info("Unable to delete litewallet cache file {}: {}", defaultWalletPath, e.getMessage());
            }
        }

        Path tempDir = pirateDir.resolve("temp");
        if (Files.isDirectory(tempDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir, "arrr-light-wallet-*.dat")) {
                for (Path path : stream) {
                    Path backupPath = path.resolveSibling(path.getFileName().toString() + ".bak");
                    try {
                        Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("Backed up litewallet temp cache file to {}", backupPath);
                    } catch (IOException e) {
                        LOGGER.info("Unable to backup litewallet temp cache file {}: {}", path, e.getMessage());
                    }
                    try {
                        Files.deleteIfExists(path);
                        LOGGER.info("Deleted litewallet temp cache file {}", path);
                    } catch (IOException e) {
                        LOGGER.info("Unable to delete litewallet temp cache file {}: {}", path, e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.info("Unable to scan litewallet temp directory at {}: {}", tempDir, e.getMessage());
            }
        }
    }

    private Path getLitewalletDataDirectory() {
        String osName = System.getProperty("os.name");
        String homeDir = System.getProperty("user.home");
        Path baseDir;

        if (osName != null && osName.contains("Windows")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) {
                baseDir = Paths.get(appData, "Pirate");
            } else if (homeDir != null && !homeDir.isEmpty()) {
                baseDir = Paths.get(homeDir, "AppData", "Roaming", "Pirate");
            } else {
                baseDir = Paths.get("Pirate");
            }
        } else if ("Mac OS X".equals(osName)) {
            if (homeDir != null && !homeDir.isEmpty()) {
                baseDir = Paths.get(homeDir, "Library", "Application Support", "Pirate");
            } else {
                baseDir = Paths.get("Pirate");
            }
        } else {
            if (homeDir != null && !homeDir.isEmpty()) {
                baseDir = Paths.get(homeDir, ".pirate");
            } else {
                baseDir = Paths.get(".pirate");
            }
        }

        PirateChain.PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();
        if (pirateChainNet == PirateChain.PirateChainNet.TEST3) {
            baseDir = baseDir.resolve("testnet3");
        } else if (pirateChainNet == PirateChain.PirateChainNet.REGTEST) {
            baseDir = baseDir.resolve("regtest");
        }

        return baseDir;
    }

    public boolean isReady() {
        return this.ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean entropyBytesEqual(byte[] testEntropyBytes) {
        return Arrays.equals(testEntropyBytes, this.entropyBytes);
    }

    private void encrypt() {
        if (this.isEncrypted()) {
            // Nothing to do
            return;
        }

        String encryptionKey = this.getEncryptionKey();
        if (encryptionKey == null) {
            // Can't encrypt without a key
            return;
        }

        this.doEncrypt(encryptionKey);
    }

    public void unlock() {
        if (!this.isEncrypted()) {
            // Nothing to do
            return;
        }

        String encryptionKey = this.getEncryptionKey();
        if (encryptionKey == null) {
            // Can't encrypt without a key
            return;
        }

        this.doUnlock(encryptionKey);
    }

    public boolean save() throws IOException {
        if (!isInitialized()) {
            LOGGER.info("Error: can't save wallet, because no wallet it initialized");
            return false;
        }
        if (this.isNullSeedWallet()) {
            // Don't save wallets that have a null seed
            return false;
        }
        if (this.unifiedWallet) {
            // Unified Wallet persists each mutation to its encrypted SQLite
            // store; serializing a legacy .dat snapshot would be incorrect.
            return true;
        }

        // Encrypt first (will do nothing if already encrypted)
        this.encrypt();

        String wallet64 = LiteWalletJni.save();
        String wallet64Confirm = LiteWalletJni.save();
        if (wallet64 == null || wallet64Confirm == null || !wallet64.equals(wallet64Confirm)) {
            LOGGER.info("Skipping Pirate wallet save: unstable snapshot");
            return false;
        }
        byte[] wallet;
        try {
            wallet = Base64.decode(wallet64);
        } catch (DecoderException e) {
            LOGGER.info("Unable to decode wallet");
            return false;
        }
        if (wallet == null) {
            LOGGER.info("Unable to save wallet");
            return false;
        }

        Path walletPath = this.getCurrentWalletPath();
        Path backupPath = walletPath.resolveSibling(walletPath.getFileName().toString() + ".bak");
        Path checksumPath = this.getChecksumPath(walletPath);
        Path backupChecksumPath = this.getChecksumPath(backupPath);
        Files.createDirectories(walletPath.getParent());
        long existingSize = Files.exists(walletPath) ? Files.size(walletPath) : 0L;
        if (wallet.length < MIN_WALLET_BYTES) {
            LOGGER.info("Skipping Pirate wallet save: payload too small (size={})", wallet.length);
            return false;
        }
        if (existingSize > 0) {
            try {
                Files.copy(walletPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                if (Files.exists(checksumPath)) {
                    Files.copy(checksumPath, backupChecksumPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOGGER.info("Unable to create Pirate wallet backup: {}", e.getMessage());
            }
        }
        Path tempPath = walletPath.resolveSibling(walletPath.getFileName().toString() + ".tmp");
        Files.write(tempPath, wallet, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tempPath, walletPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, walletPath, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            LOGGER.info("Saved Pirate Chain wallet to {} (size={})", walletPath, Files.size(walletPath));
        } catch (IOException e) {
            LOGGER.info("Saved Pirate Chain wallet to {}", walletPath);
        }
        this.writeWalletChecksum(walletPath, wallet);

        return true;
    }

    /**
     * The Unified Wallet database cannot consume the old encrypted LiteWallet
     * snapshot. Preserve that snapshot only after the replacement wallet has
     * reached the chain tip, so an operator can still recover it if a migration
     * issue is discovered.
     */
    public void archiveLegacyWalletCacheAfterUnifiedMigration() {
        if (!this.unifiedWallet || this.isNullSeedWallet() || this.entropyBytes == null) {
            return;
        }

        Path legacyWalletPath = this.getCurrentWalletPath();
        try {
            Path archivePath = PirateWallet.archiveLegacyWalletCache(legacyWalletPath);
            if (archivePath != null) {
                LOGGER.info("Archived legacy Pirate wallet cache to {} after Unified Wallet synchronization", archivePath);
            }
        } catch (IOException e) {
            // The Unified Wallet database is already persistent. Retain the legacy
            // snapshot in place if it cannot be archived rather than risking data loss.
            LOGGER.warn("Unable to archive legacy Pirate wallet cache {}: {}", legacyWalletPath, e.getMessage());
        }
    }

    static Path archiveLegacyWalletCache(Path legacyWalletPath) throws IOException {
        if (!Files.isRegularFile(legacyWalletPath)) {
            return null;
        }

        Path archivePath = PirateWallet.findLegacyWalletArchivePath(legacyWalletPath);
        PirateWallet.moveWithoutReplacement(legacyWalletPath, archivePath);

        Path legacyChecksumPath = PirateWallet.getChecksumPath(legacyWalletPath);
        if (Files.isRegularFile(legacyChecksumPath)) {
            PirateWallet.moveWithoutReplacement(legacyChecksumPath, PirateWallet.getChecksumPath(archivePath));
        }

        return archivePath;
    }

    private static Path findLegacyWalletArchivePath(Path legacyWalletPath) {
        String filename = legacyWalletPath.getFileName().toString();
        Path parent = legacyWalletPath.getParent();
        Path archivePath = parent.resolve(filename + ".legacy-before-unified");
        int suffix = 1;
        while (Files.exists(archivePath) || Files.exists(PirateWallet.getChecksumPath(archivePath))) {
            archivePath = parent.resolve(filename + ".legacy-before-unified-" + suffix++);
        }
        return archivePath;
    }

    private static void moveWithoutReplacement(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    public String load() throws IOException {
        if (this.unifiedWallet) {
            // Unified Wallet state is selected by configurestorage() and then
            // initialized deterministically from the entropy-derived mnemonic.
            return null;
        }
        if (this.isNullSeedWallet()) {
            // Don't load wallets that have a null seed
            return null;
        }
        Path walletPath = this.getCurrentWalletPath();
        Path backupPath = walletPath.resolveSibling(walletPath.getFileName().toString() + ".bak");
        String wallet64 = this.loadWalletFile(walletPath);
        if (wallet64 != null) {
            return wallet64;
        }
        boolean primaryFailed = Files.exists(walletPath);
        if (primaryFailed) {
            this.backupAndDeleteWalletCache();
        }
        if (Files.exists(backupPath)) {
            LOGGER.info("Attempting to load Pirate wallet backup at {}", backupPath);
            wallet64 = this.loadWalletFile(backupPath);
            if (wallet64 != null) {
                return wallet64;
            }
        }
        if (primaryFailed) {
            LOGGER.info("Primary wallet validation failed twice; clearing litewallet cache");
            this.backupAndDeleteLitewalletCache();
        }
        return null;
    }

    private String loadWalletFile(Path walletPath) throws IOException {
        if (!Files.exists(walletPath)) {
            return null;
        }
        long size = Files.size(walletPath);
        if (size < MIN_WALLET_BYTES) {
            LOGGER.info("Pirate wallet file too small to load: {} (size={})", walletPath, size);
            return null;
        }
        byte[] wallet = Files.readAllBytes(walletPath);
        if (wallet == null || wallet.length < MIN_WALLET_BYTES) {
            return null;
        }
        Path checksumPath = this.getChecksumPath(walletPath);
        WalletChecksum checksum = this.readWalletChecksum(checksumPath);
        if (checksum != null) {
            if (checksum.size != size) {
                LOGGER.info("Checksum size mismatch for {} (expected {}, actual {}), validating anyway",
                        walletPath, checksum.size, size);
                checksum = null;
            } else if (!checksum.hashBase64.equals(Base64.toBase64String(Crypto.digest(wallet)))) {
                LOGGER.info("Checksum mismatch for {}, validating anyway", walletPath);
                checksum = null;
            }
        }
        if (!this.validateWalletInSubprocess(walletPath)) {
            LOGGER.info("Skipping Pirate wallet load: validation failed for {}", walletPath);
            return null;
        }
        if (checksum == null) {
            this.writeWalletChecksum(walletPath, wallet);
        }
        return Base64.toBase64String(wallet);
    }

    private String getEntropyHash58() {
        if (this.entropyBytes == null) {
            return null;
        }
        byte[] entropyHash = Crypto.digest(this.entropyBytes);
        return Base58.encode(entropyHash);
    }

    public String getSeedPhrase() {
        return this.seedPhrase;
    }

    private String getEncryptionKey() {
        if (this.entropyBytes == null) {
            return null;
        }

        // Prefix the bytes with a (deterministic) string, to ensure that the resulting
        // hash is different
        String prefix = "ARRRWalletEncryption";

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write(prefix.getBytes(StandardCharsets.UTF_8));
            outputStream.write(this.entropyBytes);

        } catch (IOException e) {
            return null;
        }

        byte[] encryptionKeyHash = Crypto.digest(outputStream.toByteArray());
        return Base58.encode(encryptionKeyHash);
    }

    private Path getCurrentWalletPath() {
        String entropyHash58 = this.getEntropyHash58();
        String filename = String.format("wallet-%s.dat", entropyHash58);
        return Paths.get(Settings.getInstance().getWalletsPath(), "PirateChain", filename);
    }

    private Path getUnifiedWalletDirectory() {
        String entropyHash58 = this.getEntropyHash58();
        return Paths.get(Settings.getInstance().getWalletsPath(), "PirateChain", "unified", entropyHash58);
    }

    public boolean usesPersistentUnifiedStorage() {
        return this.unifiedWallet;
    }

    /**
     * The Unified JNI owns the sync task asynchronously. Its legacy command
     * surface intentionally has no "stop" command, so ask its JSON service to
     * cancel the active persistent-wallet session before changing servers.
     */
    public void cancelUnifiedSync() {
        if (!this.unifiedWallet) {
            return;
        }

        try {
            JSONObject activeWallet = parseJsonObject(
                    LiteWalletJni.invokeJson("{\"method\":\"get_active_wallet\"}", false),
                    "get_active_wallet");
            if (activeWallet == null || !activeWallet.optBoolean("ok", false)
                    || activeWallet.isNull("result")) {
                LOGGER.info("Unable to cancel Pirate Unified Wallet sync: active wallet was unavailable");
                return;
            }

            String walletId = activeWallet.optString("result", null);
            if (walletId == null || walletId.isBlank()) {
                LOGGER.info("Unable to cancel Pirate Unified Wallet sync: active wallet ID was unavailable");
                return;
            }

            JSONObject cancelRequest = new JSONObject()
                    .put("method", "cancel_sync")
                    .put("wallet_id", walletId);
            JSONObject cancelResponse = parseJsonObject(
                    LiteWalletJni.invokeJson(cancelRequest.toString(), false), "cancel_sync");
            if (cancelResponse == null || !cancelResponse.optBoolean("ok", false)) {
                LOGGER.info("Pirate Unified Wallet sync cancellation was not acknowledged");
                return;
            }

            LOGGER.info("Pirate Unified Wallet sync cancellation acknowledged");
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            LOGGER.info("Unable to cancel Pirate Unified Wallet sync: {}", e.getMessage());
        }
    }

    private static Path getChecksumPath(Path walletPath) {
        return walletPath.resolveSibling(walletPath.getFileName().toString() + ".sha256");
    }

    private void writeWalletChecksum(Path walletPath, byte[] wallet) {
        String checksum = Base64.toBase64String(Crypto.digest(wallet));
        String payload = CHECKSUM_PREFIX + checksum + "\n" + CHECKSUM_SIZE_PREFIX + wallet.length + "\n";
        Path checksumPath = this.getChecksumPath(walletPath);
        try {
            Files.writeString(checksumPath, payload, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.info("Unable to write Pirate wallet checksum: {}", e.getMessage());
        }
    }

    private WalletChecksum readWalletChecksum(Path checksumPath) throws IOException {
        if (!Files.exists(checksumPath)) {
            return null;
        }
        List<String> lines = Files.readAllLines(checksumPath, StandardCharsets.US_ASCII);
        String hash = null;
        Long size = null;
        for (String line : lines) {
            if (line.startsWith(CHECKSUM_PREFIX)) {
                hash = line.substring(CHECKSUM_PREFIX.length()).trim();
            } else if (line.startsWith(CHECKSUM_SIZE_PREFIX)) {
                try {
                    size = Long.parseLong(line.substring(CHECKSUM_SIZE_PREFIX.length()).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if (hash == null || size == null) {
            return null;
        }
        return new WalletChecksum(hash, size);
    }

    private static final class WalletChecksum {
        private final String hashBase64;
        private final long size;

        private WalletChecksum(String hashBase64, long size) {
            this.hashBase64 = hashBase64;
            this.size = size;
        }
    }

    private boolean validateWalletInSubprocess(Path walletPath) {
        String javaHome = System.getProperty("java.home");
        String javaBin = Paths.get(javaHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Path libDirectory = PirateChainWalletController.getRustLibOuterDirectory();
        if (libDirectory == null || !Files.exists(libDirectory)) {
            LOGGER.info("Pirate wallet validation skipped: lib directory missing");
            return true;
        }
        String serverUri = this.getValidatorServerUri();
        if (serverUri == null || serverUri.trim().isEmpty()) {
            LOGGER.info("Pirate wallet validation skipped: no lightwallet server available");
            return true;
        }
        ProcessBuilder builder = new ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                "org.qortal.crosschain.PirateWalletValidator",
                walletPath.toString(),
                libDirectory.toString(),
                serverUri);
        builder.redirectErrorStream(true);

        try {
            Process process = builder.start();
            boolean finished = process.waitFor(VALIDATOR_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.info("Pirate wallet validation skipped (timeout) for {}", walletPath);
                return true;
            }
            String output = readProcessOutput(process);
            if (process.exitValue() != 0) {
                LOGGER.info("Pirate wallet validation failed for {} (exit={}): {}",
                        walletPath, process.exitValue(), output);
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.info("Pirate wallet validation skipped for {}: {}", walletPath, e.getMessage());
            return true;
        }
    }

    private String getValidatorServerUri() {
        BitcoinyBlockchainProvider provider = PirateChain.getInstance().blockchainProvider;
        ChainableServer server = ensureServerAvailable(provider);
        if (server == null) {
            return null;
        }
        return String.format("https://%s:%d/", server.getHostName(), server.getPort());
    }

    private String readProcessOutput(Process process) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = process.getInputStream().read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.US_ASCII);
        }
    }

    public boolean isInitialized() {
        return this.entropyBytes != null && this.ready;
    }

    public boolean isSynchronized() {
        Integer height = this.getHeight();
        Integer chainTip = this.getChainTip();

        if (height == null || chainTip == null) {
            return false;
        }

        // Assume synchronized if within 2 blocks of the chain tip
        return height >= (chainTip - 2);
    }

    private JSONObject parseJsonObject(String response, String context) {
        if (response == null) {
            LOGGER.info("Pirate wallet {} response was null", context);
            return null;
        }

        String trimmed = response.trim();
        if (trimmed.isEmpty()) {
            LOGGER.info("Pirate wallet {} response was empty", context);
            return null;
        }
        if (!trimmed.startsWith("{")) {
            LOGGER.info("Pirate wallet {} returned non-JSON response (length {})", context, trimmed.length());
            return null;
        }

        try {
            return new JSONObject(trimmed);
        } catch (JSONException e) {
            LOGGER.info("Pirate wallet {} returned invalid JSON: {}", context, e.getMessage());
            return null;
        }
    }

    private String parseSeedPhrase(String response, String context) {
        if (response == null) {
            LOGGER.info("Pirate wallet {} response was null", context);
            return null;
        }

        String trimmed = response.trim();
        if (trimmed.isEmpty()) {
            LOGGER.info("Pirate wallet {} response was empty", context);
            return null;
        }

        if (trimmed.startsWith("{")) {
            JSONObject json = parseJsonObject(trimmed, context);
            if (json == null) {
                return null;
            }
            if (json.has("seed")) {
                return json.getString("seed");
            }
            if (json.has("error")) {
                LOGGER.info("Pirate wallet {} error: {}", context, json.optString("error"));
                return null;
            }
            LOGGER.info("Pirate wallet {} response missing seed phrase", context);
            return null;
        }

        if (trimmed.startsWith("Error:")) {
            LOGGER.info("Pirate wallet {} error: {}", context, trimmed);
            return null;
        }

        return trimmed;
    }

    private JSONArray parseJsonArray(String response, String context) {
        if (response == null) {
            LOGGER.info("Pirate wallet {} response was null", context);
            return null;
        }

        String trimmed = response.trim();
        if (trimmed.isEmpty()) {
            LOGGER.info("Pirate wallet {} response was empty", context);
            return null;
        }
        if (!trimmed.startsWith("[")) {
            LOGGER.info("Pirate wallet {} returned non-JSON response (length {})", context, trimmed.length());
            return null;
        }

        try {
            return new JSONArray(trimmed);
        } catch (JSONException e) {
            LOGGER.info("Pirate wallet {} returned invalid JSON: {}", context, e.getMessage());
            return null;
        }
    }

    // APIs

    public Integer getHeight() {
        String response = LiteWalletJni.execute("height", "");
        JSONObject json = parseJsonObject(response, "height");
        if (json != null && json.has("height")) {
            return json.getInt("height");
        }
        return null;
    }

    public Integer getChainTip() {
        String response = LiteWalletJni.execute("info", "");
        JSONObject json = parseJsonObject(response, "info");
        if (json != null && json.has("latest_block_height")) {
            return json.getInt("latest_block_height");
        }
        return null;
    }

    public boolean isNullSeedWallet() {
        return this.isNullSeedWallet;
    }

    public Boolean isEncrypted() {
        String response = LiteWalletJni.execute("encryptionstatus", "");
        JSONObject json = parseJsonObject(response, "encryptionstatus");
        if (json != null && json.has("encrypted")) {
            return json.getBoolean("encrypted");
        }
        return null;
    }

    public boolean doEncrypt(String key) {
        String response = LiteWalletJni.execute("encrypt", key);
        JSONObject json = parseJsonObject(response, "encrypt");
        if (json != null && json.has("result")) {
            String result = json.getString("result");
            return Objects.equals(result, "success");
        }
        return false;
    }

    public boolean doDecrypt(String key) {
        String response = LiteWalletJni.execute("decrypt", key);
        JSONObject json = parseJsonObject(response, "decrypt");
        if (json != null && json.has("result")) {
            String result = json.getString("result");
            return Objects.equals(result, "success");
        }
        return false;
    }

    public boolean doUnlock(String key) {
        String response = LiteWalletJni.execute("unlock", key);
        JSONObject json = parseJsonObject(response, "unlock");
        if (json != null && json.has("result")) {
            String result = json.getString("result");
            return Objects.equals(result, "success");
        }
        return false;
    }

    public String getWalletAddress() {
        if (this.unifiedWallet) {
            // The Unified Wallet's export command returns the active address and
            // its matching key material. Do not pick the first balance address:
            // after Ironwood activation that could be an older Sapling address.
            String address = PirateWallet.getUnifiedExportAddress(LiteWalletJni.execute("export", ""));
            if (address == null) {
                LOGGER.info("Unable to obtain active Unified Wallet address from export");
            }
            return address;
        }

        // Get balance, which also contains wallet addresses
        String response = LiteWalletJni.execute("balance", "");
        JSONObject json = parseJsonObject(response, "balance");
        String address = null;

        if (json != null && json.has("z_addresses")) {
            JSONArray z_addresses = json.getJSONArray("z_addresses");

            if (z_addresses != null && !z_addresses.isEmpty()) {
                JSONObject firstAddress = z_addresses.getJSONObject(0);
                if (firstAddress.has("address")) {
                    address = firstAddress.getString("address");
                }
            }
        }
        return address;
    }

    static String getUnifiedExportAddress(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        try {
            JSONArray entries = new JSONArray(response);
            if (entries.isEmpty()) {
                return null;
            }
            JSONObject firstEntry = entries.getJSONObject(0);
            if (!firstEntry.has("address") || firstEntry.isNull("address")) {
                return null;
            }
            return firstEntry.getString("address");
        } catch (JSONException e) {
            return null;
        }
    }

    public String getPrivateKey() {
        String response = LiteWalletJni.execute("export", "");
        JSONArray addressesJson = parseJsonArray(response, "export");
        if (addressesJson != null && !addressesJson.isEmpty()) {
            JSONObject addressJson = addressesJson.getJSONObject(0);
            if (addressJson.has("private_key")) {
                // String address = addressJson.getString("address");
                String privateKey = addressJson.getString("private_key");
                // String viewingKey = addressJson.getString("viewing_key");

                return privateKey;
            }
        }
        return null;
    }

    public String getWalletSeed(String entropy58) {
        // Decode entropy to bytes
        byte[] myEntropyBytes = Base58.decode(entropy58);

        // Pirate library uses base64 encoding
        String myEntropy64 = Base64.toBase64String(myEntropyBytes);

        // Derive seed phrase from entropy bytes
        String mySeedResponse = LiteWalletJni.getseedphrasefromentropyb64(myEntropy64);
        JSONObject mySeedJson = parseJsonObject(mySeedResponse, "getseedphrasefromentropyb64");
        String mySeedPhrase = null;
        if (mySeedJson != null && mySeedJson.has("seedPhrase")) {
            mySeedPhrase = mySeedJson.getString("seedPhrase");

            return mySeedPhrase;
        }
        return null;
    }

    public PirateLightClient.Server getRandomServer() {
        PirateChain.PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();
        Collection<PirateLightClient.Server> servers = pirateChainNet.getServers();
        Random random = new Random();
        int index = random.nextInt(servers.size());
        return (PirateLightClient.Server) servers.toArray()[index];
    }

}
