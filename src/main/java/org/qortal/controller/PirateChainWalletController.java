package org.qortal.controller;

import com.rust.litewalletjni.LiteWalletJni;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.ArbitraryDataResource;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.PirateWallet;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.FilesystemUtils;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

public class PirateChainWalletController extends Thread {

    protected static final Logger LOGGER = LogManager.getLogger(PirateChainWalletController.class);

    private static PirateChainWalletController instance;

    final private static long SAVE_INTERVAL = 60 * 60 * 1000L; // 1 hour
    private long lastSaveTime = 0L;

    private boolean running;
    private PirateWallet currentWallet = null;
    private boolean shouldLoadWallet = false;
    private String loadStatus = null;

    private static final String qdnWalletSignature = "EsfUw54perxkEtfoUoL7Z97XPrNsZRZXePVZPz3cwRm9qyEPSofD5KmgVpDqVitQp7LhnZRmL6z2V9hEe1YS45T";


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

    @Override
    public void run() {
        Thread.currentThread().setName("Pirate Chain Wallet Controller");
        Thread.currentThread().setPriority(MIN_PRIORITY);

        try {
            while (running && !Controller.isStopping()) {
                Thread.sleep(1000);

                // Wait until we have a request to load the wallet
                if (!shouldLoadWallet) {
                    continue;
                }

                if (!LiteWalletJni.isLoaded()) {
                    this.loadLibrary();

                    // If still not loaded, sleep to prevent too many requests
                    if (!LiteWalletJni.isLoaded()) {
                        Thread.sleep(5 * 1000);
                        continue;
                    }
                }

                // Wallet is downloaded, so clear the status
                this.loadStatus = null;

                if (this.currentWallet == null) {
                    // Nothing to do yet
                    continue;
                }
                if (this.currentWallet.isNullSeedWallet()) {
                    // Don't sync the null seed wallet
                    continue;
                }

                LOGGER.debug("Syncing Pirate Chain wallet...");
                String response = LiteWalletJni.execute("sync", "");
                LOGGER.debug("sync response: {}", response);

                try {
                    JSONObject json = new JSONObject(response);
                    if (json.has("result")) {
                        String result = json.getString("result");

                        // We may have to set wallet to ready if this is the first ever successful sync
                        if (Objects.equals(result, "success")) {
                            this.currentWallet.setReady(true);
                        }
                    }
                } catch (JSONException e) {
                    LOGGER.info("Unable to interpret JSON", e);
                }

                // Rate limit sync attempts
                Thread.sleep(30000);

                // Save wallet if needed
                Long now = NTP.getTime();
                if (now != null && now-SAVE_INTERVAL >= this.lastSaveTime) {
                    this.saveCurrentWallet();
                }
            }
        } catch (InterruptedException e) {
            // Fall-through to exit
        }
    }

    public void shutdown() {
        // Save the wallet
        try {
            this.saveCurrentWallet();

            this.running = false;
            this.interrupt();
        } catch (Exception e) {

        }
    }


    // QDN & wallet libraries

    private void loadLibrary() throws InterruptedException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Check if architecture is supported
            String libFileName = PirateChainWalletController.getRustLibFilename();
            if (libFileName == null) {
                String osName = System.getProperty("os.name");
                String osArchitecture = System.getProperty("os.arch");
                this.loadStatus = String.format("Unsupported architecture (%s %s)", osName, osArchitecture);
                return;
            }

            // Check if the library exists in the wallets folder
            Path libDirectory = PirateChainWalletController.getRustLibOuterDirectory();
            Path libPath = Paths.get(libDirectory.toString(), libFileName);
            if (Files.exists(libPath)) {
                // Already downloaded; we can load the library right away
                LiteWalletJni.loadLibrary();
                return;
            }

            // Library not found, so check if we've fetched the resource from QDN
            ArbitraryTransactionData t = this.getTransactionData(repository);
            if (t == null || t.getService() == null) {
                // Can't find the transaction - maybe on a different chain?
                return;
            }

            // Wait until we have a sufficient number of peers to attempt QDN downloads
            List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();
            if (handshakedPeers.size() < Settings.getInstance().getMinBlockchainPeers()) {
                // Wait for more peers
                this.loadStatus = String.format("Searching for peers...");
                return;
            }

            // Build resource
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(t.getName(),
                    ArbitraryDataFile.ResourceIdType.NAME, t.getService(), t.getIdentifier());
            try {
                arbitraryDataReader.loadSynchronously(false);
            } catch (MissingDataException e) {
                LOGGER.info("Missing data when loading Pirate Chain library");
            }

            // Check its status
            ArbitraryResourceStatus status = ArbitraryTransactionUtils.getStatus(
                    t.getService(), t.getName(), t.getIdentifier(), false, true);

            if (status.getStatus() != ArbitraryResourceStatus.Status.READY) {
                LOGGER.info("Not ready yet: {}", status.getTitle());
                this.loadStatus = String.format("Downloading files from QDN... (%d / %d)", status.getLocalChunkCount(), status.getTotalChunkCount());
                return;
            }

            // Files are downloaded, so copy the necessary files to the wallets folder
            // Delete the wallets/*/lib directory first, in case earlier versions of the wallet are present
            Path walletsLibDirectory = PirateChainWalletController.getWalletsLibDirectory();
            if (Files.exists(walletsLibDirectory)) {
                FilesystemUtils.safeDeleteDirectory(walletsLibDirectory, false);
            }
            Files.createDirectories(libDirectory);
            FileUtils.copyDirectory(arbitraryDataReader.getFilePath().toFile(), libDirectory.toFile());

            // Clear reader cache so only one copy exists
            ArbitraryDataResource resource = new ArbitraryDataResource(t.getName(),
                    ArbitraryDataFile.ResourceIdType.NAME, t.getService(), t.getIdentifier());
            resource.deleteCache();

            // Finally, load the library
            LiteWalletJni.loadLibrary();

        } catch (DataException e) {
            LOGGER.error("Repository issue when loading Pirate Chain library", e);
        } catch (IOException e) {
            LOGGER.error("Error when loading Pirate Chain library", e);
        }
    }

    private ArbitraryTransactionData getTransactionData(Repository repository) {
        try {
            byte[] signature = Base58.decode(qdnWalletSignature);
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof ArbitraryTransactionData))
                return null;

            ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);
            if (arbitraryTransaction != null) {
                return (ArbitraryTransactionData) arbitraryTransaction.getTransactionData();
            }

            return null;
        } catch (DataException e) {
            return null;
        }
    }

    public static String getRustLibFilename() {
        String osName = System.getProperty("os.name");
        String osArchitecture = System.getProperty("os.arch");

        if (osName.equals("Mac OS X") && osArchitecture.equals("x86_64")) {
            return "librust-macos-x86_64.dylib";
        }
        else if ((osName.equals("Linux") || osName.equals("FreeBSD")) && osArchitecture.equals("aarch64")) {
            return "librust-linux-aarch64.so";
        }
        else if ((osName.equals("Linux") || osName.equals("FreeBSD")) && osArchitecture.equals("amd64")) {
            return "librust-linux-x86_64.so";
        }
        else if (osName.contains("Windows") && osArchitecture.equals("amd64")) {
            return "librust-windows-x86_64.dll";
        }

        return null;
    }

    public static Path getWalletsLibDirectory() {
        return Paths.get(Settings.getInstance().getWalletsPath(), "PirateChain", "lib");
    }

    public static Path getRustLibOuterDirectory() {
        String sigPrefix = qdnWalletSignature.substring(0, 8);
        return Paths.get(Settings.getInstance().getWalletsPath(), "PirateChain", "lib", sigPrefix);
    }


    // Wallet functions

    public boolean initWithEntropy58(String entropy58) {
        return this.initWithEntropy58(entropy58, false);
    }

    public boolean initNullSeedWallet() {
        return this.initWithEntropy58(Base58.encode(new byte[32]), true);
    }

    private boolean initWithEntropy58(String entropy58, boolean isNullSeedWallet) {
        // If the JNI library isn't loaded yet then we can't proceed
        if (!LiteWalletJni.isLoaded()) {
            shouldLoadWallet = true;
            return false;
        }

        byte[] entropyBytes = Base58.decode(entropy58);

        if (entropyBytes == null || entropyBytes.length != 32) {
            LOGGER.info("Invalid entropy bytes");
            return false;
        }

        if (this.currentWallet != null) {
            if (this.currentWallet.entropyBytesEqual(entropyBytes)) {
                // Wallet already active - nothing to do
                return true;
            }
            else {
                // Different wallet requested - close the existing one and switch over
                this.closeCurrentWallet();
            }
        }

        try {
            this.currentWallet = new PirateWallet(entropyBytes, isNullSeedWallet);
            if (!this.currentWallet.isReady()) {
                // Don't persist wallets that aren't ready
                this.currentWallet = null;
            }
            return true;
        } catch (IOException e) {
            LOGGER.info("Unable to initialize wallet: {}", e.getMessage());
        }

        return false;
    }

    private void saveCurrentWallet() {
        if (this.currentWallet == null) {
            // Nothing to do
            return;
        }
        try {
            if (this.currentWallet.save()) {
                Long now = NTP.getTime();
                if (now != null) {
                    this.lastSaveTime = now;
                }
            }
        } catch (IOException e) {
            LOGGER.info("Unable to save wallet");
        }
    }

    public PirateWallet getCurrentWallet() {
        return this.currentWallet;
    }

    private void closeCurrentWallet() {
        this.saveCurrentWallet();
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
        if (this.currentWallet == null || !this.currentWallet.isSynchronized()) {
            throw new ForeignBlockchainException("Wallet isn't synchronized yet");
        }

        String response = LiteWalletJni.execute("syncStatus", "");
        JSONObject json = new JSONObject(response);
        if (json.has("syncing")) {
            boolean isSyncing = Boolean.valueOf(json.getString("syncing"));
            if (isSyncing) {
                long syncedBlocks = json.getLong("synced_blocks");
                long totalBlocks = json.getLong("total_blocks");

                throw new ForeignBlockchainException(String.format("Sync in progress (%d / %d). Please try again later.", syncedBlocks, totalBlocks));
            }
        }
    }

    public String getSyncStatus() {
        if (this.currentWallet == null || !this.currentWallet.isInitialized()) {
            if (this.loadStatus != null) {
                return this.loadStatus;
            }

            return "Not initialized yet";
        }

        String syncStatusResponse = LiteWalletJni.execute("syncStatus", "");
        org.json.JSONObject json = new JSONObject(syncStatusResponse);
        if (json.has("syncing")) {
            boolean isSyncing = Boolean.valueOf(json.getString("syncing"));
            if (isSyncing) {
                long syncedBlocks = json.getLong("synced_blocks");
                long totalBlocks = json.getLong("total_blocks");
                return String.format("Sync in progress (%d / %d)", syncedBlocks, totalBlocks);
            }
        }

        boolean isSynchronized = this.currentWallet.isSynchronized();
        if (isSynchronized) {
            return "Synchronized";
        }

        return "Initializing wallet...";
    }

}
