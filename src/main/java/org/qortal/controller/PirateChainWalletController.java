package org.qortal.controller;

import com.rust.litewalletjni.LiteWalletJni;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.PirateChain;
import org.qortal.crosschain.PirateLightClient;
import org.qortal.crosschain.PirateWallet;
import org.qortal.crosschain.ChainableServer;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class PirateChainWalletController extends Thread {

    protected static final Logger LOGGER = LogManager.getLogger(PirateChainWalletController.class);

    private static PirateChainWalletController instance;

    final private static long SAVE_INTERVAL = 60 * 60 * 1000L; // 1 hour
    private long lastSaveTime = 0L;

    private boolean running;
    private volatile PirateWallet currentWallet = null;
    private volatile boolean shouldLoadWallet = false;
    private volatile String loadStatus = null;

    private void updateLoadStatus(String status) {
        this.loadStatus = status;
        if (status != null) {
            LOGGER.info(status);
        }
    }

    private static final long WALLET_LOCK_TIMEOUT_MS = 5_000L;
    private static final long SAVE_LOCK_TIMEOUT_MS = 2_000L;
    private static final long SWITCH_LOCK_TIMEOUT_MS = 30_000L;
    private static final long STATUS_LOCK_TIMEOUT_MS = 500L;
    private static final long SKIP_LOG_INTERVAL_MS = 30_000L;
    private final Object statusLock = new Object();

    private static final String NULL_SEED_ENTROPY58 = Base58.encode(new byte[32]);

    private final ReentrantLock walletLock = new ReentrantLock(true);
    private final Object switchingMonitor = new Object();
    private static final long WALLET_INIT_RETRY_DELAY_MS = 100_000L;
    private static final int WALLET_INIT_MAX_RETRIES = 5;
    private static final long SYNC_STATUS_TIMEOUT_MS = 10_000L;
    private static final long SYNC_STATUS_INIT_TIMEOUT_MS = 30_000L;
    private static final long SYNC_CALL_TIMEOUT_MS = 60_000L;
    private static final int SYNC_STATUS_STALL_THRESHOLD = 8;
    // Unified Wallet can spend minutes persisting a historical shard-tree batch
    // without moving the externally reported block height.
    private static final int UNIFIED_SYNC_STATUS_STALL_THRESHOLD = 72;
    private static final int SYNC_STATUS_IDLE_THRESHOLD = 5;
    private static final int SYNC_STATUS_REPEAT_THRESHOLD = 5;
    private static final long SYNC_STATUS_ROTATE_COOLDOWN_MS = 5 * 60 * 1000L;
    private static final int SYNC_STATUS_INIT_TIMEOUT_THRESHOLD = 1;
    // Sync status polling timeout.

    private volatile long lastWalletInitFailureMs = 0L;
    private volatile Thread switchingThread = null;
    private int switchingDepth = 0;
    private final ThreadLocal<Integer> switchingClaims = ThreadLocal.withInitial(() -> 0);
    private volatile long lastSkipLogTime = 0L;
    private volatile String lastSkipReason = null;
    private int walletInitRetryCount = 0;
    private final Semaphore syncStatusSemaphore = new Semaphore(1);
    private long lastSyncStatusId = -1L;
    private String lastSyncProgress = null;
    private int syncStatusStallCount = 0;
    private long lastSyncStatusRotateMs = 0L;
    private int syncStatusInitTimeoutCount = 0;
    private int syncStatusIdleCount = 0;
    private long lastSyncStatusIdleHeight = -1L;
    private int syncStatusTimeoutCount = 0;
    private long lastSyncStatusRepeatId = -1L;
    private String lastSyncStatusRepeatProgress = null;
    private long lastSyncStatusRepeatHeight = -1L;
    private int syncStatusRepeatCount = 0;
    private volatile Thread activeSyncThread = null;
    private volatile boolean restartRequested = false;
    private volatile String restartReason = null;

    /**
     * The reviewed Pirate Unified Wallet production bundle. Operators can
     * override it with pirateChainWalletQdnSignature to test a specific,
     * immutable QDN publication without changing production data.
     */
    public static final String DEFAULT_QDN_WALLET_SIGNATURE = "5mjggoNvtQ9KCA5Ytbdc1BhQiUyjovSR62JgthQGWUgBzN8awE4KNS7LxjnHECqASSEZFfTXiTNv4WNa6nbWBEX8";

    private PirateChainWalletController() {
        this.running = Settings.getInstance().isWalletEnabled("ARRR");
    }

    public static PirateChainWalletController getInstance() {
        if (!Settings.getInstance().isWalletEnabled("ARRR"))
            return null;

        if (instance == null)
            instance = new PirateChainWalletController();

        return instance;
    }

    private boolean isSwitching() {
        synchronized (this.switchingMonitor) {
            return this.switchingThread != null;
        }
    }

    private boolean isSwitchingByCurrentThread() {
        synchronized (this.switchingMonitor) {
            return Thread.currentThread() == this.switchingThread;
        }
    }

    private boolean claimSwitching() {
        synchronized (this.switchingMonitor) {
            if (this.switchingThread == null) {
                this.switchingThread = Thread.currentThread();
                this.switchingDepth = 1;
            } else if (this.switchingThread == Thread.currentThread()) {
                this.switchingDepth++;
            } else {
                return false;
            }
        }
        this.switchingClaims.set(this.switchingClaims.get() + 1);
        return true;
    }

    private void releaseSwitchingClaim() {
        int claims = this.switchingClaims.get();
        if (claims > 0) {
            this.switchingClaims.set(claims - 1);
            synchronized (this.switchingMonitor) {
                if (this.switchingThread == Thread.currentThread()) {
                    this.switchingDepth--;
                    if (this.switchingDepth <= 0) {
                        this.switchingDepth = 0;
                        this.switchingThread = null;
                    }
                }
            }
        }
    }

    private boolean acquireWalletLock(long timeoutMs) {
        try {
            return this.walletLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void releaseWalletLockIfHeld() {
        if (this.walletLock.isHeldByCurrentThread()) {
            this.walletLock.unlock();
        }
    }

    private void logSyncSkip(String reason) {
        long now = System.currentTimeMillis();
        if (!Objects.equals(reason, this.lastSkipReason) || now - this.lastSkipLogTime >= SKIP_LOG_INTERVAL_MS) {
            LOGGER.info("Pirate wallet sync skipped: {}", reason);
            this.lastSkipReason = reason;
            this.lastSkipLogTime = now;
        }
    }

    private String formatSwitchingStatus() {
        if (this.loadStatus != null) {
            return "Wallet switch in progress (" + this.loadStatus + ")";
        }
        return "Wallet switch in progress";
    }

    private void rotateLightwalletServer(String reason) {
        PirateChain pirateChain = PirateChain.getInstance();
        if (!(pirateChain.getBlockchainProvider() instanceof PirateLightClient)) {
            return;
        }

        PirateLightClient lightClient = (PirateLightClient) pirateChain.getBlockchainProvider();
        List<ChainableServer> candidates = new ArrayList<>(lightClient.getServers());
        ChainableServer currentServer = lightClient.getCurrentServer();
        if (currentServer != null) {
            candidates.remove(currentServer);
        }
        candidates.removeAll(lightClient.getUselessServers());

        if (candidates.isEmpty()) {
            LOGGER.info("No alternate Pirate lightwallet servers available to rotate");
            return;
        }

        ChainableServer next = candidates.get(new Random().nextInt(candidates.size()));
        try {
            LOGGER.info("Switching Pirate lightwallet server to {} ({})", next, reason);
            lightClient.setCurrentServer(next, "PirateChainWalletController");
            if (this.currentWallet != null) {
                LOGGER.info("Pirate wallet reconnect requested after server switch");
            }
        } catch (ForeignBlockchainException e) {
            LOGGER.info("Unable to switch Pirate lightwallet server: {}", e.getMessage());
        }
    }

    private boolean needsWalletSwitch(byte[] entropyBytes, boolean isNullSeedWallet) {
        if (this.currentWallet == null) {
            return true;
        }
        if (!this.currentWallet.entropyBytesEqual(entropyBytes)) {
            return true;
        }
        return this.currentWallet.isNullSeedWallet() != isNullSeedWallet;
    }

    private void sleepInitRetryDelay() {
        try {
            Thread.sleep(WALLET_INIT_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Pirate Chain Wallet Controller");
        Thread.currentThread().setPriority(MIN_PRIORITY);
        // Sync watchdog disabled: never force-stop long syncs.

        try {
            while (running && !Controller.isStopping()) {
                if (!this.sleepUntilNextControllerWork(1000)) {
                    continue;
                }

                if (this.restartRequested) {
                    this.performRestart();
                }

                // Wait until we have a request to load the wallet or an active wallet to sync
                if (!shouldLoadWallet && this.currentWallet == null) {
                    this.logSyncSkip("no wallet requested or active");
                    continue;
                }

                if (!LiteWalletJni.isLoaded()) {
                    this.loadLibrary();

                    // If still not loaded, sleep to prevent too many requests
                    if (!LiteWalletJni.isLoaded()) {
                        this.logSyncSkip("JNI library not loaded");
                        Thread.sleep(5 * 1000);
                        continue;
                    }
                }

                // Wallet is downloaded, so clear the status
                this.updateLoadStatus(null);

                PirateWallet wallet = this.currentWallet;
                if (wallet == null) {
                    // Nothing to do yet
                    this.logSyncSkip("wallet not initialized");
                    continue;
                }
                if (wallet.isNullSeedWallet()) {
                    // Don't sync the null seed wallet
                    this.logSyncSkip("null-seed wallet is not synced");
                    continue;
                }
                if (this.isSwitching()) {
                    this.logSyncSkip("wallet switching in progress");
                    continue;
                }
                if (!this.acquireWalletLock(0)) {
                    this.logSyncSkip("wallet lock busy");
                    continue;
                }
                try {
                    if (this.isSwitching()) {
                        this.logSyncSkip("wallet switching in progress");
                        continue;
                    }
                    LOGGER.debug("Syncing Pirate Chain wallet...");
                    String response;
                    try {
                        response = this.executeSyncWithTimeout();
                    } catch (RuntimeException e) {
                        LOGGER.info("Pirate wallet sync call failed: {}", e.getMessage());
                        response = null;
                    }
                    if (response == null || response.trim().isEmpty()) {
                        LOGGER.info("Pirate wallet sync returned empty response");
                        continue;
                    }
                    LOGGER.debug("sync response: {}", response);
                    try {
                        JSONObject json = new JSONObject(response);
                        if (json.has("result")) {
                            String result = json.getString("result");

                            // We may have to set wallet to ready if this is the first ever successful sync
                            if (Objects.equals(result, "success")) {
                                this.currentWallet.setReady(true);
                                if (this.currentWallet.isSynchronized()) {
                                    this.currentWallet.archiveLegacyWalletCacheAfterUnifiedMigration();
                                }
                            } else {
                                String reason = json.optString("reason", null);
                                if (reason != null && !reason.isEmpty()) {
                                    String lowerReason = reason.toLowerCase();
                                    if (lowerReason.contains("interrupted") || lowerReason.contains("interupted")) {
                                        LOGGER.info("Pirate wallet sync interrupted: {}", reason);
                                    } else {
                                        this.requestRestart("sync failure: " + reason);
                                    }
                                } else {
                                    this.requestRestart("sync failure");
                                }
                            }
                        }
                    } catch (JSONException e) {
                        LOGGER.info("Unable to interpret JSON", e);
                    }
                } finally {
                    this.releaseWalletLockIfHeld();
                }

                if (this.restartRequested) {
                    this.performRestart();
                    continue;
                }

                // Rate limit sync attempts
                if (!this.sleepUntilNextControllerWork(30000)) {
                    continue;
                }

                // Save wallet if needed
                long now = this.getNowMillis();
                if (now - SAVE_INTERVAL >= this.lastSaveTime) {
                    this.saveCurrentWallet();
                }
            }
        } catch (InterruptedException e) {
            // Fall-through to exit
        }
    }

    /**
     * A status request can request a recovery while this controller is sleeping
     * between sync attempts. Wake promptly for that request, but preserve a
     * normal shutdown interrupt as an exit condition.
     */
    private boolean sleepUntilNextControllerWork(long durationMs) throws InterruptedException {
        try {
            Thread.sleep(durationMs);
            return true;
        } catch (InterruptedException e) {
            if (this.restartRequested && this.running && !Controller.isStopping()) {
                return false;
            }
            throw e;
        }
    }

    public void shutdown() {
        // Save the wallet
        this.saveCurrentWallet();

        this.running = false;
        this.interrupt();
    }

    private long getNowMillis() {
        Long now = NTP.getTime();
        return now != null ? now : System.currentTimeMillis();
    }

    // QDN & wallet libraries

    private void loadLibrary() throws InterruptedException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            this.updateLoadStatus("Loading Pirate Chain wallet library...");

            String qdnWalletSignature = Settings.getInstance().getPirateChainWalletQdnSignature();
            if (!PirateChainWalletController.isValidQdnWalletSignature(qdnWalletSignature)) {
                LOGGER.error("Invalid pirateChainWalletQdnSignature setting");
                this.updateLoadStatus("Invalid Pirate Chain wallet QDN transaction signature in settings");
                return;
            }
            LOGGER.info("Loading Pirate Chain wallet JNI selected by QDN transaction {}", qdnWalletSignature);

            // Check if architecture is supported
            String libFileName = PirateChainWalletController.getRustLibFilename();
            if (libFileName == null) {
                String osName = System.getProperty("os.name");
                String osArchitecture = System.getProperty("os.arch");
                this.updateLoadStatus(String.format("Unsupported architecture (%s %s)", osName, osArchitecture));
                return;
            }

            // Check if the library exists in the wallets folder
            Path libDirectory = PirateChainWalletController.getRustLibOuterDirectory(qdnWalletSignature);
            Path libPath = Paths.get(libDirectory.toString(), libFileName);
            if (Files.exists(libPath)) {
                // Already downloaded; we can load the library right away
                LOGGER.info("Loading cached Pirate Chain wallet JNI selected by QDN transaction {}", qdnWalletSignature);
                this.updateLoadStatus("Loading cached Pirate Chain library from disk...");
                LiteWalletJni.loadLibrary();
                if (LiteWalletJni.isLoaded()) {
                    this.onLibraryLoaded();
                } else {
                    this.updateLoadStatus("Unable to load cached Pirate Chain JNI library");
                }
                return;
            }

            // Library not found, so check if we've fetched the resource from QDN
            ArbitraryTransactionData t = this.getTransactionData(repository, qdnWalletSignature);
            if (t == null) {
                this.updateLoadStatus("Waiting for configured Pirate Chain library transaction to appear on QDN...");
                return;
            }
            if (t.getService() != Service.ARBITRARY_DATA) {
                LOGGER.error("Configured Pirate Chain wallet transaction is not an ARBITRARY_DATA publication: {}", qdnWalletSignature);
                this.updateLoadStatus("Configured Pirate Chain wallet transaction is not an ARBITRARY_DATA publication");
                return;
            }

            // Wait until we have a sufficient number of peers to attempt QDN downloads
            List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();
            if (handshakedPeers.size() < Settings.getInstance().getMinBlockchainPeers()) {
                // Wait for more peers
                this.updateLoadStatus("Searching for peers...");
                return;
            }

            // Fetch the immutable transaction selected in settings. Using NAME here would
            // instead build the latest state for that name and could load a different bundle.
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(qdnWalletSignature,
                    ArbitraryDataFile.ResourceIdType.TRANSACTION_DATA, t.getService(), t.getIdentifier());
            arbitraryDataReader.setTransactionData(t);
            try {
                arbitraryDataReader.loadSynchronously(false);
            } catch (MissingDataException e) {
                LOGGER.info("Missing data when loading configured Pirate Chain library");
                this.updateLoadStatus("Downloading configured Pirate Chain library from QDN...");
                return;
            }

            Path resourcePath = arbitraryDataReader.getFilePath();
            if (resourcePath == null || !Files.isDirectory(resourcePath)) {
                LOGGER.error("Configured Pirate Chain wallet library was not built as a directory: {}", resourcePath);
                this.updateLoadStatus("Configured Pirate Chain library could not be read from QDN");
                return;
            }
            if (!Files.isRegularFile(resourcePath.resolve(libFileName))) {
                LOGGER.error("Configured Pirate Chain wallet library is missing {}", libFileName);
                this.updateLoadStatus(String.format("Configured Pirate Chain library is missing %s", libFileName));
                return;
            }

            // The cache directory is tied to the selected QDN transaction. Do not delete
            // the shared lib root: that could remove another (including production) bundle.
            Files.createDirectories(libDirectory);
            this.updateLoadStatus("Copying Pirate Chain library into wallets folder...");
            FileUtils.copyDirectory(resourcePath.toFile(), libDirectory.toFile());

            // Finally, load the library
            LiteWalletJni.loadLibrary();
            if (LiteWalletJni.isLoaded()) {
                this.onLibraryLoaded();
            } else {
                this.updateLoadStatus("Unable to load configured Pirate Chain JNI library");
            }

        } catch (DataException e) {
            LOGGER.error("Repository issue when loading Pirate Chain library", e);
            this.updateLoadStatus("Repository issue while loading Pirate Chain library");
        } catch (IOException e) {
            LOGGER.error("Error when loading Pirate Chain library", e);
            this.updateLoadStatus("IO error while loading Pirate Chain library");
        }
    }

    private void onLibraryLoaded() {
        this.shouldLoadWallet = false;
        this.updateLoadStatus("Pirate Chain library loaded and ready");
    }

    private ArbitraryTransactionData getTransactionData(Repository repository, String qdnWalletSignature) {
        try {
            byte[] signature = Base58.decode(qdnWalletSignature);
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof ArbitraryTransactionData))
                return null;

            return (ArbitraryTransactionData) transactionData;
        } catch (DataException | NumberFormatException e) {
            return null;
        }
    }

    public static String getRustLibFilename() {
        String osName = System.getProperty("os.name");
        String osArchitecture = System.getProperty("os.arch");

        if (osName.equals("Mac OS X") && osArchitecture.equals("x86_64")) {
            return "librust-macos-x86_64.dylib";
        } else if (osName.equals("Mac OS X") && osArchitecture.equals("aarch64")) {
            return "librust-macos-aarch64.dylib";
        } else if (osName.equals("Linux") && osArchitecture.equals("aarch64")) {
            return "librust-linux-aarch64.so";
        } else if (osName.equals("Linux") && osArchitecture.equals("amd64")) {
            return "librust-linux-x86_64.so";
        } else if (osName.contains("Windows") && osArchitecture.equals("amd64")) {
            return "librust-windows-x86_64.dll";
        }

        return null;
    }

    public static Path getWalletsLibDirectory() {
        return Paths.get(Settings.getInstance().getWalletsPath(), "PirateChain", "lib");
    }

    public static Path getRustLibOuterDirectory() {
        return PirateChainWalletController.getRustLibOuterDirectory(
                Settings.getInstance().getPirateChainWalletQdnSignature());
    }

    static Path getRustLibOuterDirectory(String qdnWalletSignature) {
        return Paths.get(Settings.getInstance().getWalletsPath(), "PirateChain", "lib",
                PirateChainWalletController.getWalletLibraryCacheKey(qdnWalletSignature));
    }

    static boolean isValidQdnWalletSignature(String qdnWalletSignature) {
        if (qdnWalletSignature == null) {
            return false;
        }

        try {
            return Base58.decode(qdnWalletSignature).length == Crypto.SIGNATURE_LENGTH;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String getWalletLibraryCacheKey(String qdnWalletSignature) {
        if (!PirateChainWalletController.isValidQdnWalletSignature(qdnWalletSignature)) {
            throw new IllegalArgumentException("Invalid QDN wallet transaction signature");
        }

        // The active production bundle gets a short cache directory. Legacy and
        // test publications get a full-signature directory so they cannot
        // overwrite the production bundle.
        if (DEFAULT_QDN_WALLET_SIGNATURE.equals(qdnWalletSignature)) {
            return qdnWalletSignature.substring(0, 8);
        }
        return qdnWalletSignature;
    }

    // Wallet functions

    public boolean initWithEntropy58(String entropy58) {
        return this.initWithEntropy58(entropy58, false);
    }

    public boolean initNullSeedWallet() {
        return this.initWithEntropy58(NULL_SEED_ENTROPY58, true);
    }

    private boolean initWithEntropy58(String entropy58, boolean isNullSeedWallet) {
        try {
            this.beginWalletUse(entropy58, isNullSeedWallet, false, false);
            return true;
        } catch (ForeignBlockchainException e) {
            return false;
        } finally {
            this.endWalletUse();
        }
    }

    public void beginWalletUse(String entropy58, boolean isNullSeedWallet, boolean requireSync,
            boolean requireNotNullSeed)
            throws ForeignBlockchainException {
        // If the JNI library isn't loaded yet then we can't proceed
        if (!LiteWalletJni.isLoaded()) {
            this.shouldLoadWallet = true;
            LOGGER.info("Pirate wallet init requested but JNI library is not loaded yet");
            throw new ForeignBlockchainException("Pirate wallet isn't initialized yet");
        }

        if (entropy58 == null) {
            throw new ForeignBlockchainException("Invalid entropy bytes");
        }

        byte[] entropyBytes = Base58.decode(entropy58);
        if (entropyBytes == null || entropyBytes.length != 32) {
            throw new ForeignBlockchainException("Invalid entropy bytes");
        }
        boolean needsSwitch = this.needsWalletSwitch(entropyBytes, isNullSeedWallet);
        boolean claimedSwitching = false;
        if (!needsSwitch && this.isSwitching() && !this.isSwitchingByCurrentThread()) {
            throw new ForeignBlockchainException("Wallet switch in progress");
        }
        if (needsSwitch) {
            LOGGER.info("Pirate wallet switch required (nullSeed={})", isNullSeedWallet);
            claimedSwitching = this.claimSwitching();
            if (!claimedSwitching) {
                throw new ForeignBlockchainException("Wallet switch in progress");
            }
        } else if (this.isSwitchingByCurrentThread()) {
            claimedSwitching = this.claimSwitching();
        }

        long timeoutMs = needsSwitch ? SWITCH_LOCK_TIMEOUT_MS : WALLET_LOCK_TIMEOUT_MS;
        if (!this.acquireWalletLock(timeoutMs)) {
            if (claimedSwitching) {
                this.releaseSwitchingClaim();
            }
            if (this.isSwitching()) {
                LOGGER.info("Pirate wallet init blocked: wallet switch in progress");
                throw new ForeignBlockchainException("Wallet switch in progress");
            }
            LOGGER.info("Pirate wallet init blocked: wallet busy");
            throw new ForeignBlockchainException("Wallet busy. Please try again later.");
        }

        try {
            boolean needsSwitchLocked = this.needsWalletSwitch(entropyBytes, isNullSeedWallet);
            if (needsSwitchLocked) {
                long now = System.currentTimeMillis();
                if (this.lastWalletInitFailureMs > 0L
                        && now - this.lastWalletInitFailureMs < WALLET_INIT_RETRY_DELAY_MS) {
                    throw new ForeignBlockchainException("Wallet init cooling down. Please try again shortly.");
                }
                this.closeCurrentWallet(true);
                LOGGER.info("Creating Pirate wallet instance (nullSeed={})", isNullSeedWallet);
                PirateWallet wallet = new PirateWallet(entropyBytes, isNullSeedWallet);
                while (!wallet.isReady() && this.walletInitRetryCount < WALLET_INIT_MAX_RETRIES) {
                    this.walletInitRetryCount++;
                    this.lastWalletInitFailureMs = System.currentTimeMillis();
                    LOGGER.info(
                            "Pirate wallet init failed; retrying same server (attempt {}/{}) after delay",
                            this.walletInitRetryCount,
                            WALLET_INIT_MAX_RETRIES);
                    this.sleepInitRetryDelay();
                    wallet = new PirateWallet(entropyBytes, isNullSeedWallet);
                }
                if (!wallet.isReady()) {
                    LOGGER.info("Pirate wallet init failed after retries; switching server");
                    this.rotateLightwalletServer("wallet init failed");
                    this.walletInitRetryCount = 0;
                    this.lastWalletInitFailureMs = System.currentTimeMillis();
                    this.sleepInitRetryDelay();
                    wallet = new PirateWallet(entropyBytes, isNullSeedWallet);
                }
                this.currentWallet = wallet.isReady() ? wallet : null;
                if (this.currentWallet != null) {
                    this.walletInitRetryCount = 0;
                }
                if (this.currentWallet != null) {
                    LOGGER.info(
                            "Pirate wallet instance created (ready={}, initialized={}, synchronized={})",
                            this.currentWallet.isReady(),
                            this.currentWallet.isInitialized(),
                            this.currentWallet.isSynchronized());

                    /*
                     * The Unified JNI service keeps its last progress snapshot after a
                     * cancelled sync, but removes the active native sync session. A
                     * status request made while recreating the wallet can consequently
                     * report the old scan as still in progress. Start the replacement
                     * scan here rather than waiting for the controller loop to infer
                     * that one is needed from that stale status.
                     */
                    if (!this.currentWallet.isNullSeedWallet()
                            && this.currentWallet.usesPersistentUnifiedStorage()) {
                        this.resetSyncStatusStallTracker();
                        LOGGER.info("Starting Pirate Unified wallet sync after wallet initialization");
                        this.startUnifiedSync();
                    }
                }
                if (this.currentWallet == null) {
                    LOGGER.info("Pirate wallet init failed after retry: wallet not ready");
                    this.lastWalletInitFailureMs = System.currentTimeMillis();
                    throw new ForeignBlockchainException("Pirate wallet isn't initialized yet");
                }
            }

            this.ensureInitialized();
            if (requireNotNullSeed) {
                this.ensureNotNullSeed();
            }
            if (requireSync) {
                this.ensureSynchronized();
            }
        } catch (ForeignBlockchainException e) {
            this.releaseWalletLockIfHeld();
            if (claimedSwitching) {
                this.releaseSwitchingClaim();
            }
            throw e;
        } catch (IOException e) {
            this.releaseWalletLockIfHeld();
            if (claimedSwitching) {
                this.releaseSwitchingClaim();
            }
            throw new ForeignBlockchainException("Unable to initialize wallet: " + e.getMessage());
        } catch (RuntimeException e) {
            this.releaseWalletLockIfHeld();
            if (claimedSwitching) {
                this.releaseSwitchingClaim();
            }
            throw e;
        }
    }

    public void beginNullSeedWalletUse(boolean requireSync) throws ForeignBlockchainException {
        this.beginWalletUse(NULL_SEED_ENTROPY58, true, requireSync, false);
    }

    public void endWalletUse() {
        this.releaseWalletLockIfHeld();
        this.releaseSwitchingClaim();
    }

    private void saveCurrentWallet() {
        if (this.currentWallet == null) {
            // Nothing to do
            return;
        }
        if (!this.acquireWalletLock(SAVE_LOCK_TIMEOUT_MS)) {
            LOGGER.info("Skipping Pirate wallet save: wallet busy");
            return;
        }
        try {
            if (this.currentWallet == null) {
                return;
            }
            if (this.currentWallet.usesPersistentUnifiedStorage()) {
                // Pirate Unified Wallet persists mutations in its own encrypted
                // SQLite store. It must not be serialized as a legacy .dat file.
                this.lastSaveTime = this.getNowMillis();
                return;
            }
            if (this.currentWallet.save()) {
                this.lastSaveTime = this.getNowMillis();
            }
        } catch (IOException e) {
            LOGGER.info("Unable to save wallet");
        } finally {
            this.releaseWalletLockIfHeld();
        }
    }

    public PirateWallet getCurrentWallet() {
        return this.currentWallet;
    }

    private void closeCurrentWallet(boolean save) {
        if (save) {
            this.saveCurrentWallet();
        }
        this.currentWallet = null;
    }

    public void ensureInitialized() throws ForeignBlockchainException {
        if (!LiteWalletJni.isLoaded() || this.currentWallet == null || !this.currentWallet.isInitialized()) {
            throw new ForeignBlockchainException("Pirate wallet isn't initialized yet");
        }
    }

    public void ensureNotNullSeed() throws ForeignBlockchainException {
        // Safety check to make sure funds aren't sent to a null seed wallet
        if (this.currentWallet == null || this.currentWallet.isNullSeedWallet()) {
            throw new ForeignBlockchainException("Invalid wallet");
        }
    }

    public void ensureSynchronized() throws ForeignBlockchainException {
        if (this.isSwitching() && !this.isSwitchingByCurrentThread()) {
            throw new ForeignBlockchainException("Wallet switch in progress");
        }
        if (!this.walletLock.isHeldByCurrentThread()) {
            throw new ForeignBlockchainException("Wallet busy. Please try again later.");
        }
        if (this.currentWallet == null || !this.currentWallet.isSynchronized()) {
            throw new ForeignBlockchainException("Wallet isn't synchronized yet");
        }

        String response = LiteWalletJni.execute("syncStatus", "");
        if (response == null || response.trim().isEmpty()) {
            throw new ForeignBlockchainException("Sync status unavailable. Please try again later.");
        }
        try {
            JSONObject json = new JSONObject(response);
            if (PirateChainWalletController.isSyncInProgress(json)) {
                String progress = this.formatSyncProgress(json);
                String progressSuffix = progress != null ? String.format(" (%s)", progress) : "";
                throw new ForeignBlockchainException(
                        String.format("Sync in progress%s. Please try again later.", progressSuffix));
            }
        } catch (JSONException e) {
            throw new ForeignBlockchainException("Sync status unavailable. Please try again later.");
        }
    }

    private Long fetchChainHeight() {
        String response = LiteWalletJni.execute("info", "");
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(response);
            if (json.has("latest_block_height")) {
                return json.getLong("latest_block_height");
            }
        } catch (JSONException e) {
            // Fall through to return null.
        }
        return null;
    }

    private String formatSyncProgress(JSONObject statusJson) {
        long scannedHeight = statusJson.optLong("scanned_height", -1);
        if (scannedHeight >= 0) {
            Long chainHeight = this.fetchChainHeight();
            if (chainHeight != null && chainHeight >= 0) {
                long currentHeight = Math.min(scannedHeight, chainHeight);
                return String.format("%d / %d", currentHeight, chainHeight);
            }
        }

        long syncedBlocks = statusJson.optLong("synced_blocks", -1);
        long endBlock = statusJson.optLong("end_block", -1);
        long startBlock = statusJson.optLong("start_block", -1);

        if (startBlock >= 0 && syncedBlocks >= 0) {
            long currentHeight = PirateChainWalletController.calculateSyncHeight(startBlock, endBlock, syncedBlocks);

            Long chainHeight = this.fetchChainHeight();
            if (chainHeight != null && chainHeight >= 0) {
                if (currentHeight > chainHeight) {
                    currentHeight = chainHeight;
                }
                return String.format("%d / %d", currentHeight, chainHeight);
            }
        }

        long totalBlocks = statusJson.optLong("total_blocks", -1);
        if (syncedBlocks >= 0 && totalBlocks >= 0) {
            return String.format("%d / %d", syncedBlocks, totalBlocks);
        }

        return null;
    }

    /**
     * Unified Wallet's synced_blocks counter is relative to start_block, not
     * end_block. Keep it bounded by the captured sync target for a stable
     * progress display even if the chain advances during the sync.
     */
    static long calculateSyncHeight(long startBlock, long endBlock, long syncedBlocks) {
        long currentHeight = startBlock + syncedBlocks;
        return endBlock >= startBlock ? Math.min(currentHeight, endBlock) : currentHeight;
    }

    /**
     * Unified Wallet uses {@code in_progress}. Some deployed JNI builds retain
     * the older {@code syncing} field, so accept both while publishing a
     * backwards-compatible Core response for Qortal Hub and Q-Wallets.
     */
    static boolean isSyncInProgress(JSONObject statusJson) {
        return statusJson.optBoolean("in_progress", false) || statusJson.optBoolean("syncing", false);
    }

    private String formatSyncStatus(PirateWallet wallet) {
        return this.formatSyncStatus(wallet, this.fetchSyncStatusResponse());
    }

    private String formatSyncStatus(PirateWallet wallet, String syncStatusResponse) {
        if (syncStatusResponse != null) {
            LOGGER.info("Pirate wallet syncStatus response: {}", syncStatusResponse);
        }
        if (syncStatusResponse == null || syncStatusResponse.trim().isEmpty()) {
            LOGGER.info("Pirate wallet syncStatus returned empty response");
            return "Sync status unavailable";
        }

        JSONObject json;
        try {
            json = new JSONObject(syncStatusResponse);
        } catch (JSONException e) {
            LOGGER.info("Pirate wallet syncStatus returned invalid JSON: {}", syncStatusResponse);
            return "Sync status unavailable";
        }

        if (PirateChainWalletController.isSyncInProgress(json)) {
            String progress = this.formatSyncProgress(json);
            if (this.handleStalledSyncStatus(json, progress)) {
                return "Switching servers";
            }
            if (progress != null) {
                LOGGER.info("Pirate wallet sync progress: {}", progress);
                return String.format("Sync in progress (%s)", progress);
            }
            LOGGER.info("Pirate wallet sync in progress without detailed progress");
            return "Sync in progress";
        }

        if (wallet != null && wallet.isSynchronized()) {
            return "Synchronized";
        }

        if (this.handleRepeatedSyncStatus(json, wallet)) {
            return "Switching servers";
        }
        this.handleIdleSyncStatus(json, wallet);
        return "Initializing wallet...";
    }

    private boolean handleRepeatedSyncStatus(JSONObject json, PirateWallet wallet) {
        if (wallet == null || wallet.isSynchronized()) {
            this.resetRepeatTracker();
            return false;
        }
        if (PirateChainWalletController.isSyncInProgress(json)) {
            this.resetRepeatTracker();
            return false;
        }
        long syncId = json.optLong("sync_id", -1);
        long scannedHeight = json.optLong("scanned_height", -1);
        String progress = this.formatSyncProgress(json);
        boolean same = syncId == this.lastSyncStatusRepeatId
                && scannedHeight == this.lastSyncStatusRepeatHeight
                && Objects.equals(progress, this.lastSyncStatusRepeatProgress);
        if (same) {
            this.syncStatusRepeatCount++;
        } else {
            this.lastSyncStatusRepeatId = syncId;
            this.lastSyncStatusRepeatHeight = scannedHeight;
            this.lastSyncStatusRepeatProgress = progress;
            this.syncStatusRepeatCount = 1;
        }
        if (this.syncStatusRepeatCount >= SYNC_STATUS_REPEAT_THRESHOLD) {
            boolean switching = this.requestSyncStatusRestart("syncStatus repeated");
            this.resetRepeatTracker();
            return switching;
        }
        return false;
    }

    private void resetRepeatTracker() {
        this.lastSyncStatusRepeatId = -1L;
        this.lastSyncStatusRepeatHeight = -1L;
        this.lastSyncStatusRepeatProgress = null;
        this.syncStatusRepeatCount = 0;
    }

    private void handleIdleSyncStatus(JSONObject json, PirateWallet wallet) {
        if (wallet == null || wallet.isSynchronized()) {
            this.syncStatusIdleCount = 0;
            this.lastSyncStatusIdleHeight = -1L;
            return;
        }
        if (PirateChainWalletController.isSyncInProgress(json)) {
            this.syncStatusIdleCount = 0;
            this.lastSyncStatusIdleHeight = -1L;
            return;
        }
        long syncId = json.optLong("sync_id", -1);
        long scannedHeight = json.optLong("scanned_height", -1);
        int configuredBirthday = Settings.getInstance().getArrrDefaultBirthday();
        if (syncId != 0 || scannedHeight < 0 || scannedHeight > configuredBirthday) {
            this.syncStatusIdleCount = 0;
            this.lastSyncStatusIdleHeight = -1L;
            return;
        }
        if (scannedHeight == this.lastSyncStatusIdleHeight) {
            this.syncStatusIdleCount++;
        } else {
            this.syncStatusIdleCount = 1;
            this.lastSyncStatusIdleHeight = scannedHeight;
        }
        if (this.syncStatusIdleCount >= SYNC_STATUS_IDLE_THRESHOLD) {
            this.requestSyncStatusRestart("syncStatus idle");
            this.syncStatusIdleCount = 0;
            this.lastSyncStatusIdleHeight = -1L;
        }
    }

    private boolean handleStalledSyncStatus(JSONObject json, String progress) {
        if (!PirateChainWalletController.isSyncInProgress(json)) {
            this.resetSyncStatusStallTracker();
            return false;
        }

        long syncId = json.optLong("sync_id", -1);
        if (syncId < 0 || progress == null) {
            this.resetSyncStatusStallTracker();
            return false;
        }

        if (syncId == this.lastSyncStatusId && Objects.equals(progress, this.lastSyncProgress)) {
            this.syncStatusStallCount++;
        } else {
            this.syncStatusStallCount = 0;
            this.lastSyncStatusId = syncId;
            this.lastSyncProgress = progress;
        }

        int stallThreshold = this.currentWallet != null && this.currentWallet.usesPersistentUnifiedStorage()
                ? UNIFIED_SYNC_STATUS_STALL_THRESHOLD
                : SYNC_STATUS_STALL_THRESHOLD;
        if (this.syncStatusStallCount >= stallThreshold) {
            boolean switching = this.requestSyncStatusRestart("syncStatus stalled");
            if (switching) {
                LOGGER.info("Pirate wallet syncStatus stalled; rotating server (syncId={}, progress={})",
                        syncId, progress);
            }
            this.syncStatusStallCount = 0;
            return switching;
        }

        return false;
    }

    /**
     * Return true only when a recovery was actually scheduled. Previously the
     * status endpoint could say "Switching servers" during its cooldown even
     * though the native scan was left stalled on the same endpoint.
     */
    private boolean requestSyncStatusRestart(String reason) {
        if (this.restartRequested) {
            LOGGER.info("Pirate wallet {} recovery already scheduled", reason);
            return true;
        }
        long now = System.currentTimeMillis();
        if (this.isSwitching()) {
            LOGGER.info("Pirate wallet {} recovery already in progress", reason);
            return false;
        }
        if (now - this.lastSyncStatusRotateMs < SYNC_STATUS_ROTATE_COOLDOWN_MS) {
            LOGGER.info("Pirate wallet {} recovery skipped during rotation cooldown", reason);
            return false;
        }

        this.lastSyncStatusRotateMs = now;
        this.requestRestart(reason);
        return true;
    }

    private void resetSyncStatusStallTracker() {
        this.lastSyncStatusId = -1L;
        this.lastSyncProgress = null;
        this.syncStatusStallCount = 0;
        this.syncStatusIdleCount = 0;
        this.lastSyncStatusIdleHeight = -1L;
        this.syncStatusTimeoutCount = 0;
        this.resetRepeatTracker();
    }

    private String buildSyncStatusJson(String status, String rawResponse) {
        JSONObject payload = new JSONObject();
        payload.put("status", status);
        if (rawResponse != null && !rawResponse.trim().isEmpty()) {
            try {
                payload.put("raw", new JSONObject(rawResponse));
            } catch (JSONException e) {
                payload.put("raw", rawResponse);
            }
        }
        return payload.toString();
    }

    public String getSyncStatus() {
        PirateWallet wallet = this.currentWallet;
        if (this.isSwitching() && !this.isSwitchingByCurrentThread()) {
            return this.formatSwitchingStatus();
        }
        if (wallet == null || !wallet.isInitialized()) {
            if (this.loadStatus != null) {
                LOGGER.info("Pirate wallet sync status: {}", this.loadStatus);
                return this.loadStatus;
            }

            LOGGER.info("Pirate wallet sync status: initializing (wallet not ready)");
            return "Not initialized yet";
        }

        boolean lockedHere = false;
        if (!this.walletLock.isHeldByCurrentThread()) {
            lockedHere = this.acquireWalletLock(STATUS_LOCK_TIMEOUT_MS);
            if (!lockedHere) {
                if (this.isSwitching()) {
                    return this.formatSwitchingStatus();
                }
                String rawResponse = this.fetchSyncStatusResponse();
                return this.formatSyncStatus(wallet, rawResponse);
            }
        }

        try {
            if (this.currentWallet == null || !this.currentWallet.isInitialized()) {
                if (this.loadStatus != null) {
                    LOGGER.info("Pirate wallet sync status: {}", this.loadStatus);
                    return this.loadStatus;
                }
                LOGGER.info("Pirate wallet sync status: initializing (wallet not ready)");
                return "Not initialized yet";
            }

            return this.formatSyncStatus(this.currentWallet);
        } finally {
            if (lockedHere) {
                this.releaseWalletLockIfHeld();
            }
        }
    }

    public String getSyncStatusJson() {
        PirateWallet wallet = this.currentWallet;
        if (this.isSwitching() && !this.isSwitchingByCurrentThread()) {
            return this.buildSyncStatusJson(this.formatSwitchingStatus(), null);
        }
        if (wallet == null || !wallet.isInitialized()) {
            if (this.loadStatus != null) {
                LOGGER.info("Pirate wallet sync status: {}", this.loadStatus);
                return this.buildSyncStatusJson(this.loadStatus, null);
            }

            LOGGER.info("Pirate wallet sync status: initializing (wallet not ready)");
            return this.buildSyncStatusJson("Not initialized yet", null);
        }

        String status;
        String rawResponse = this.fetchSyncStatusResponse();
        if (rawResponse == null) {
            LOGGER.info("Pirate wallet syncStatus unavailable");
        }
        status = this.formatSyncStatus(wallet, rawResponse);

        return this.buildSyncStatusJson(status, rawResponse);
    }

    private String fetchSyncStatusResponse() {
        if (!this.syncStatusSemaphore.tryAcquire()) {
            LOGGER.info("Pirate wallet syncStatus skipped: busy");
            return null;
        }
        final String[] responseHolder = new String[1];
        Thread syncStatusThread = new Thread(() -> {
            try {
                responseHolder[0] = LiteWalletJni.execute("syncStatus", "");
            } catch (Exception e) {
                LOGGER.info("Pirate wallet syncStatus failed: {}", e.getClass().getSimpleName());
            }
        }, "PirateWalletSyncStatus");
        syncStatusThread.setDaemon(true);
        syncStatusThread.start();
        try {
            syncStatusThread.join(SYNC_STATUS_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Pirate wallet syncStatus interrupted");
            return null;
        } finally {
            if (syncStatusThread.isAlive()) {
                LOGGER.info("Pirate wallet syncStatus timed out after {} ms", SYNC_STATUS_TIMEOUT_MS);
                syncStatusThread.interrupt();
                this.handleSyncStatusTimeout();
            }
            this.syncStatusSemaphore.release();
        }

        if (syncStatusThread.isAlive()) {
            return null;
        }

        return responseHolder[0];
    }

    private void handleSyncStatusTimeout() {
        this.syncStatusTimeoutCount++;
        if (this.syncStatusTimeoutCount < SYNC_STATUS_INIT_TIMEOUT_THRESHOLD) {
            return;
        }
        this.syncStatusTimeoutCount = 0;
        if (this.isSwitching()) {
            return;
        }
        this.requestRestart("syncStatus timeout");
    }

    private String executeSyncWithTimeout() {
        if (this.currentWallet != null && this.currentWallet.usesPersistentUnifiedStorage()) {
            return this.startUnifiedSync();
        }

        final String[] responseHolder = new String[1];
        Thread syncThread = new Thread(() -> {
            try {
                responseHolder[0] = LiteWalletJni.execute("sync", "");
            } catch (Exception e) {
                LOGGER.info("Pirate wallet sync failed: {}", e.getClass().getSimpleName());
            }
        }, "PirateWalletSync");
        syncThread.setDaemon(true);
        this.activeSyncThread = syncThread;
        syncThread.start();
        try {
            syncThread.join(SYNC_CALL_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Pirate wallet sync interrupted");
            this.activeSyncThread = null;
            return null;
        }
        if (syncThread.isAlive()) {
            LOGGER.info("Pirate wallet sync timed out after {} ms", SYNC_CALL_TIMEOUT_MS);
            syncThread.interrupt();
            this.requestRestart("sync timeout");
            this.activeSyncThread = null;
            return null;
        }
        this.activeSyncThread = null;
        return responseHolder[0];
    }

    /**
     * Unified JNI sync owns a persistent background service. Do not apply the
     * legacy 60-second blocking-call watchdog to it: a scan can remain active
     * while status calls continue to report progress. Starting a second sync or
     * restarting the wallet at that point interrupts a healthy scan.
     */
    private String startUnifiedSync() {
        if (this.isSyncThreadActive()) {
            return "{\"result\":\"success\"}";
        }

        Thread syncThread = new Thread(() -> {
            try {
                String response = LiteWalletJni.execute("sync", "");
                if (response == null || response.trim().isEmpty()) {
                    LOGGER.info("Pirate Unified wallet sync returned empty response");
                } else {
                    LOGGER.debug("Pirate Unified wallet sync response: {}", response);
                }
            } catch (Exception e) {
                LOGGER.info("Pirate Unified wallet sync failed: {}", e.getClass().getSimpleName());
            } finally {
                if (this.activeSyncThread == Thread.currentThread()) {
                    this.activeSyncThread = null;
                }
            }
        }, "PirateUnifiedWalletSync");
        syncThread.setDaemon(true);
        this.activeSyncThread = syncThread;
        syncThread.start();
        return "{\"result\":\"success\"}";
    }

    public String getSyncStatusWithInit(String entropy58) throws ForeignBlockchainException {
        synchronized (this.statusLock) {
            this.initWithEntropy58(entropy58);
            return this.getSyncStatus();
        }
    }

    public String getSyncStatusJsonWithInit(String entropy58) throws ForeignBlockchainException {
        synchronized (this.statusLock) {
            this.initWithEntropy58(entropy58);
            return this.getSyncStatusJson();
        }
    }

    public String getSyncStatusWithInitTimeout(String entropy58) throws ForeignBlockchainException {
        return this.getSyncStatusWithInitTimeout(entropy58, false);
    }

    public String getSyncStatusJsonWithInitTimeout(String entropy58) throws ForeignBlockchainException {
        return this.getSyncStatusWithInitTimeout(entropy58, true);
    }

    private String getSyncStatusWithInitTimeout(String entropy58, boolean json) throws ForeignBlockchainException {
        if (this.isSwitching() && !this.isSwitchingByCurrentThread()) {
            String status = "Switching servers";
            return json ? this.buildSyncStatusJson(status, null) : status;
        }
        final String[] responseHolder = new String[1];
        final ForeignBlockchainException[] exceptionHolder = new ForeignBlockchainException[1];
        Thread statusThread = new Thread(() -> {
            try {
                responseHolder[0] = json ? this.getSyncStatusJsonWithInit(entropy58)
                        : this.getSyncStatusWithInit(entropy58);
            } catch (ForeignBlockchainException e) {
                exceptionHolder[0] = e;
            } catch (Exception e) {
                exceptionHolder[0] = new ForeignBlockchainException(e.getMessage());
            }
        }, "PirateWalletSyncStatusInit");
        statusThread.setDaemon(true);
        statusThread.start();

        try {
            statusThread.join(SYNC_STATUS_INIT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForeignBlockchainException("Sync status interrupted");
        }

        if (statusThread.isAlive()) {
            LOGGER.info("Pirate wallet syncStatus init timed out after {} ms", SYNC_STATUS_INIT_TIMEOUT_MS);
            this.handleSyncStatusInitTimeout();
            return json ? this.buildSyncStatusJson("Sync status unavailable", null) : "Sync status unavailable";
        }

        if (exceptionHolder[0] != null) {
            throw exceptionHolder[0];
        }

        this.syncStatusInitTimeoutCount = 0;
        return responseHolder[0];
    }

    private void handleSyncStatusInitTimeout() {
        this.syncStatusInitTimeoutCount++;
        if (this.syncStatusInitTimeoutCount < SYNC_STATUS_INIT_TIMEOUT_THRESHOLD) {
            return;
        }
        this.syncStatusInitTimeoutCount = 0;
        this.requestRestart("syncStatus init timeout");
    }

    private void requestRestart(String reason) {
        this.restartReason = reason;
        this.restartRequested = true;
        LOGGER.info("Pirate wallet restart requested ({})", reason);
        this.stopActiveSync();
        this.interrupt();
    }

    private void performRestart() {
        String reason = this.restartReason != null ? this.restartReason : "unknown";
        LOGGER.info("Restarting Pirate wallet controller ({})", reason);
        this.restartRequested = false;
        this.restartReason = null;

        if (!this.isSyncThreadActive()) {
            this.saveCurrentWallet();
        } else {
            LOGGER.info("Skipping Pirate wallet save during restart: sync still active");
        }
        this.closeCurrentWallet(false);
        this.resetSyncStatusStallTracker();
        this.syncStatusInitTimeoutCount = 0;
        this.lastSyncStatusRotateMs = 0L;
        this.walletInitRetryCount = 0;
        this.lastWalletInitFailureMs = 0L;
        this.rotateLightwalletServer(reason);
        this.shouldLoadWallet = true;
        this.updateLoadStatus("Restarting Pirate wallet...");
    }

    private boolean isSyncThreadActive() {
        Thread syncThread = this.activeSyncThread;
        return syncThread != null && syncThread.isAlive();
    }

    private void stopActiveSync() {
        Thread syncThread = this.activeSyncThread;
        if (syncThread != null && syncThread.isAlive()) {
            syncThread.interrupt();
        }
        if (this.currentWallet != null && this.currentWallet.usesPersistentUnifiedStorage()) {
            this.currentWallet.cancelUnifiedSync();
            return;
        }
        try {
            LiteWalletJni.execute("stop", "");
        } catch (RuntimeException e) {
            LOGGER.debug("Unable to stop Pirate Chain sync: {}", e.getMessage());
        }
    }

}
