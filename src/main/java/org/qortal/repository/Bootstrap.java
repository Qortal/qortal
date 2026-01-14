package org.qortal.repository;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.block.BlockData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.gui.SplashFrame;
import org.qortal.network.Network;
import org.qortal.repository.hsqldb.HSQLDBImportExport;
import org.qortal.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;
import org.qortal.utils.SevenZ;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;


public class Bootstrap {

    private Repository repository;

    private int retryMinutes = 1;

    private static final Logger LOGGER = LogManager.getLogger(Bootstrap.class);

    /** The maximum number of untrimmed blocks allowed to be included in a bootstrap, beyond the trim threshold */
    private static final int MAXIMUM_UNTRIMMED_BLOCKS = 100;

    /** The maximum number of unpruned blocks allowed to be included in a bootstrap, beyond the prune threshold */
    private static final int MAXIMUM_UNPRUNED_BLOCKS = 100;


    public Bootstrap() {
    }

    public Bootstrap(Repository repository) {
        this.repository = repository;
    }

    /**
     * canCreateBootstrap()
     * Performs basic initial checks to ensure everything is in order
     * @return true if ready for bootstrap creation, or an exception if not
     * All failure reasons are logged and included in the exception
     * @throws DataException
     */
    public boolean checkRepositoryState() throws DataException {
        LOGGER.info("Checking repository state...");

        final boolean isTopOnly = Settings.getInstance().isTopOnly();
        final boolean archiveEnabled = Settings.getInstance().isArchiveEnabled();

        // Make sure we have a repository instance
        if (repository == null) {
            throw new DataException("Repository instance required to check if we can create a bootstrap.");
        }

        // Require that a block archive has been built
        if (!isTopOnly && !archiveEnabled) {
            throw new DataException("Unable to create bootstrap because the block archive isn't enabled. " +
                    "Set {\"archivedEnabled\": true} in settings.json to fix.");
        }

        // Make sure that the block archiver is up to date
        boolean upToDate = BlockArchiveWriter.isArchiverUpToDate(repository);
        if (!upToDate) {
            throw new DataException("Unable to create bootstrap because the block archive isn't fully built yet.");
        }

        // Ensure that this database contains the ATStatesHeightIndex which was missing in some cases
        boolean hasAtStatesHeightIndex = repository.getATRepository().hasAtStatesHeightIndex();
        if (!hasAtStatesHeightIndex) {
            throw new DataException("Unable to create bootstrap due to missing ATStatesHeightIndex. A re-sync from genesis is needed.");
        }

        // Ensure we have synced NTP time
        if (NTP.getTime() == null) {
            throw new DataException("Unable to create bootstrap because the node hasn't synced its time yet.");
        }

        // Ensure the chain is synced
        final BlockData chainTip = Controller.getInstance().getChainTip();
        final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
        if (minLatestBlockTimestamp == null || chainTip.getTimestamp() < minLatestBlockTimestamp) {
            throw new DataException("Unable to create bootstrap because the blockchain isn't fully synced.");
        }

        // FUTURE: ensure trim and prune settings are using default values

        if (!isTopOnly) {
            // We don't trim in top-only mode because we prune the blocks instead
            // If we're not in top-only mode we should make sure that trimming is up to date

            // Ensure that the online account signatures have been fully trimmed
            final int accountsTrimStartHeight = repository.getBlockRepository().getOnlineAccountsSignaturesTrimHeight();
            final long accountsUpperTrimmableTimestamp = NTP.getTime() - BlockChain.getInstance().getOnlineAccountSignaturesMaxLifetime();
            final int accountsUpperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(accountsUpperTrimmableTimestamp);
            final int accountsBlocksRemaining = accountsUpperTrimmableHeight - accountsTrimStartHeight;
            if (accountsBlocksRemaining > MAXIMUM_UNTRIMMED_BLOCKS) {
                throw new DataException(String.format("Blockchain is not fully trimmed. Please allow the node to run for longer, " +
                        "then try again. Blocks remaining (online accounts signatures): %d", accountsBlocksRemaining));
            }

            // Ensure that the AT states data has been fully trimmed
            final int atTrimStartHeight = repository.getATRepository().getAtTrimHeight();
            final long atUpperTrimmableTimestamp = chainTip.getTimestamp() - Settings.getInstance().getAtStatesMaxLifetime();
            final int atUpperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(atUpperTrimmableTimestamp);
            final int atBlocksRemaining = atUpperTrimmableHeight - atTrimStartHeight;
            if (atBlocksRemaining > MAXIMUM_UNTRIMMED_BLOCKS) {
                throw new DataException(String.format("Blockchain is not fully trimmed. Please allow the node to run " +
                        "for longer, then try again. Blocks remaining (AT states): %d", atBlocksRemaining));
            }
        }

        // Ensure that blocks have been fully pruned
        final int blockPruneStartHeight = repository.getBlockRepository().getBlockPruneHeight();
        int blockUpperPrunableHeight = chainTip.getHeight() - Settings.getInstance().getPruneBlockLimit();
        if (archiveEnabled) {
            blockUpperPrunableHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight() - 1;
        }
        final int blocksPruneRemaining = blockUpperPrunableHeight - blockPruneStartHeight;
        if (blocksPruneRemaining > MAXIMUM_UNPRUNED_BLOCKS) {
            throw new DataException(String.format("Blockchain is not fully pruned. Please allow the node to run " +
                    "for longer, then try again. Blocks remaining: %d", blocksPruneRemaining));
        }

        // Ensure that AT states have been fully pruned
        final int atPruneStartHeight = repository.getATRepository().getAtPruneHeight();
        int atUpperPrunableHeight = chainTip.getHeight() - Settings.getInstance().getPruneBlockLimit();
        if (archiveEnabled) {
            atUpperPrunableHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight() - 1;
        }
        final int atPruneRemaining = atUpperPrunableHeight - atPruneStartHeight;
        if (atPruneRemaining > MAXIMUM_UNPRUNED_BLOCKS) {
            throw new DataException(String.format("Blockchain is not fully pruned. Please allow the node to run " +
                    "for longer, then try again. Blocks remaining (AT states): %d", atPruneRemaining));
        }

        LOGGER.info("Repository state checks passed");
        return true;
    }

    /**
     * validateBlockchain
     * Performs quick validation of recent blocks in blockchain, prior to creating a bootstrap
     * @return true if valid, an exception if not
     * @throws DataException
     */
    public boolean validateBlockchain() throws DataException {
        LOGGER.info("Validating blockchain...");

        try {
            BlockChain.validate();

            LOGGER.info("Blockchain is valid");

            return true;
        } catch (DataException e) {
            throw new DataException(String.format("Blockchain validation failed: %s", e.getMessage()));
        }
    }

    /**
     * validateCompleteBlockchain
     * Performs intensive validation of all blocks in blockchain
     * @return true if valid, false if not
     */
    public boolean validateCompleteBlockchain() {
        LOGGER.info("Validating blockchain...");

        try {
            // Perform basic startup validation
            BlockChain.validate();

            // Perform more intensive full-chain validation
            BlockChain.validateAllBlocks();

            LOGGER.info("Blockchain is valid");

            return true;
        } catch (DataException e) {
            LOGGER.info("Blockchain validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String create() throws DataException, InterruptedException, IOException {

        // Make sure we have a repository instance
        if (repository == null) {
            throw new DataException("Repository instance required in order to create a bootstrap");
        }

        LOGGER.info("Deleting temp directory if it exists...");
        this.deleteAllTempDirectories();

        LOGGER.info("Acquiring blockchain lock...");
        ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
        blockchainLock.lockInterruptibly();

        Path inputPath = null;
        Path outputPath = null;

        try {

            LOGGER.info("Exporting local data...");
            repository.exportNodeLocalData();

            LOGGER.info("Deleting trade bot states...");
            List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
            for (TradeBotData tradeBotData : allTradeBotData) {
                repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
            }

            LOGGER.info("Deleting minting accounts...");
            List<MintingAccountData> mintingAccounts = repository.getAccountRepository().getMintingAccounts();
            for (MintingAccountData mintingAccount : mintingAccounts) {
                repository.getAccountRepository().delete(mintingAccount.getPrivateKey());
            }

            repository.saveChanges();

            LOGGER.info("Deleting peers list...");
            repository.getNetworkRepository().deleteAllPeers();
            repository.saveChanges();

            LOGGER.info("Adding initial peers...");
            Network.installInitialPeers(repository);

            LOGGER.info("Creating bootstrap...");
            // Timeout if the database isn't ready for backing up after 10 seconds
            long timeout = 10 * 1000L;
            repository.backup(false, "bootstrap", timeout);

            LOGGER.info("Moving files to output directory...");
            inputPath = Paths.get(Settings.getInstance().getRepositoryPath(), "bootstrap");
            outputPath = Paths.get(this.createTempDirectory().toString(), "bootstrap");


            // Move the db backup to a "bootstrap" folder in the root directory
            Files.move(inputPath, outputPath, REPLACE_EXISTING);

            // If in archive mode, copy the archive folder to inside the bootstrap folder
            if (!Settings.getInstance().isTopOnly() && Settings.getInstance().isArchiveEnabled()) {
                FileUtils.copyDirectory(
                        Paths.get(Settings.getInstance().getRepositoryPath(), "archive").toFile(),
                        Paths.get(outputPath.toString(), "archive").toFile()
                );
            }

            LOGGER.info("Preparing output path...");
            Path compressedOutputPath = this.getBootstrapOutputPath();
            try {
                Files.delete(compressedOutputPath);
            } catch (NoSuchFileException e) {
                // Doesn't exist, so no need to delete
            }

            LOGGER.info("Compressing...");
            SevenZ.compress(compressedOutputPath.toString(), outputPath.toFile());

            LOGGER.info("Generating checksum file...");
            String checksum = Crypto.digestHexString(compressedOutputPath.toFile(), 1024*1024);
            LOGGER.info("checksum: {}", checksum);
            Path checksumPath = Paths.get(String.format("%s.sha256", compressedOutputPath.toString()));
            LOGGER.info("Writing checksum to path: {}", checksumPath);
            Files.writeString(checksumPath, checksum, StandardOpenOption.CREATE);

            // Return the path to the compressed bootstrap file
            LOGGER.info("Bootstrap creation complete. Output file: {}", compressedOutputPath.toAbsolutePath().toString());
            return compressedOutputPath.toAbsolutePath().toString();

        }
        catch (TimeoutException e) {
            throw new DataException(String.format("Unable to create bootstrap due to timeout: %s", e.getMessage()));
        }
        finally {
            try {
                LOGGER.info("Re-importing local data...");
                Path exportPath = HSQLDBImportExport.getExportDirectory(false);
                repository.importDataFromFile(Paths.get(exportPath.toString(), "TradeBotStates.json").toString());
                repository.importDataFromFile(Paths.get(exportPath.toString(), "MintingAccounts.json").toString());
                repository.saveChanges();

            } catch (IOException e) {
                LOGGER.info("Unable to re-import local data, but created bootstrap is still valid. {}", e);
            }

            LOGGER.info("Unlocking blockchain...");
            blockchainLock.unlock();

            // Cleanup
            LOGGER.info("Cleaning up...");
            Thread.sleep(5000L);
            this.deleteAllTempDirectories();
        }
    }

    public void startImport() throws InterruptedException {
        while (!Controller.isStopping()) {
            try (final Repository repository = RepositoryManager.getRepository()) {
                this.repository = repository;

                this.updateStatus("Starting import of bootstrap...");

                this.doImport();
                break;

            } catch (DataException e) {
                LOGGER.info("Bootstrap import failed", e);
                this.updateStatus(String.format("Bootstrapping failed. Retrying in %d minutes...", retryMinutes));
                Thread.sleep(retryMinutes * 60 * 1000L);
                retryMinutes *= 2;
            }
        }
    }

    private void doImport() throws DataException {
        Path path = null;
        try {
            Path tempDir = this.createTempDirectory();
            String filename = String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), this.getFilename());
            path = Paths.get(tempDir.toString(), filename);

            this.downloadToPath(path);
            this.importFromPath(path);

        } catch (InterruptedException | DataException | IOException e) {
            throw new DataException("Unable to import bootstrap", e);
        }
        finally {
            if (path != null) {
                try {
                    Files.delete(path);

                } catch (IOException e) {
                    // Temp folder will be cleaned up below, so ignore this failure
                }
            }
            this.deleteAllTempDirectories();
        }
    }

    private String getFilename() {
        boolean isTopOnly = Settings.getInstance().isTopOnly();
        boolean archiveEnabled = Settings.getInstance().isArchiveEnabled();
        boolean isTestnet = Settings.getInstance().isTestNet();
        String prefix = isTestnet ? "testnet-" : "";

        if (isTopOnly) {
            return prefix.concat("bootstrap-toponly.7z");
        }
        else if (archiveEnabled) {
            return prefix.concat("bootstrap-archive.7z");
        }
        else {
            return prefix.concat("bootstrap-full.7z");
        }
    }

    private void downloadToPath(Path path) throws DataException {
        String bootstrapHost = this.getRandomHost();
        String bootstrapFilename = this.getFilename();
        String bootstrapUrl = String.format("%s/%s", bootstrapHost, bootstrapFilename);
        String type = Settings.getInstance().isTopOnly() ? "top-only" : "full node";

        SplashFrame.getInstance().updateStatus(String.format("Downloading %s bootstrap...", type));
        LOGGER.info(String.format("Downloading %s bootstrap from %s ...", type, bootstrapUrl));

        // Delete an existing file if it exists
        try {
            Files.delete(path);
        } catch (IOException e) {
            // No need to do anything
        }

        // Get the total file size
        URL url;
        long fileSize;
        try {
            url = new URL(bootstrapUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.connect();
            fileSize = connection.getContentLengthLong();
            connection.disconnect();

        } catch (MalformedURLException e) {
            throw new DataException(String.format("Malformed URL when downloading bootstrap: %s", e.getMessage()));
        } catch (IOException e) {
            throw new DataException(String.format("Unable to get bootstrap file size from %s. " +
                    "Please check your internet connection.", e.getMessage()));
        }

        // Download the file and update the status with progress
        try (BufferedInputStream in = new BufferedInputStream(url.openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(path.toFile())) {
            byte[] buffer = new byte[1024 * 1024];
            long downloaded = 0;
            int bytesRead;
            while ((bytesRead = in.read(buffer, 0, 1024)) != -1) {
                fileOutputStream.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                if (fileSize > 0) {
                    double progress = (double)downloaded / (double)fileSize * 100;
                    SplashFrame.getInstance().updateStatus(String.format("Downloading %s bootstrap... (%.1f%%)", type, progress));
                }
            }

        } catch (IOException e) {
            throw new DataException(String.format("Unable to download bootstrap: %s", e.getMessage()));
        }
    }

    public String getRandomHost() {
        // Select a random host from bootstrapHosts
        String[] hosts = Settings.getInstance().getBootstrapHosts();
        int index = new SecureRandom().nextInt(hosts.length);
        String bootstrapHost = hosts[index];
        return bootstrapHost;
    }

    public void importFromPath(Path path) throws InterruptedException, DataException, IOException {

        ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
        blockchainLock.lockInterruptibly();

        try {
            this.updateStatus("Stopping repository...");
            // Close the repository while we are still able to
            // Otherwise, the caller will run into difficulties when it tries to close it
            repository.discardChanges();
            repository.close();
            // Now close the repository factory so that we can swap out the database files
            RepositoryManager.closeRepositoryFactory();

            this.updateStatus("Deleting existing repository...");
            Path input = path.toAbsolutePath();
            Path output = path.toAbsolutePath().getParent().toAbsolutePath();
            Path inputPath = Paths.get(output.toString(), "bootstrap");
            Path outputPath = Paths.get(Settings.getInstance().getRepositoryPath());
            FileUtils.deleteDirectory(outputPath.toFile());

            this.updateStatus("Extracting bootstrap...");
            SevenZ.decompress(input.toString(), output.toFile());

            if (!inputPath.toFile().exists()) {
                throw new DataException("Extracted bootstrap doesn't exist");
            }

            // Move the "bootstrap" folder in place of the "db" folder
            this.updateStatus("Moving files to output directory...");
            Files.move(inputPath, outputPath);

            this.updateStatus("Starting repository from bootstrap...");
        }
        finally {
            RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
            RepositoryManager.setRepositoryFactory(repositoryFactory);

            blockchainLock.unlock();
        }
    }

    private Path createTempDirectory() throws IOException {
        Path initialPath = Paths.get(Settings.getInstance().getRepositoryPath()).toAbsolutePath().getParent();
        String baseDir = Paths.get(initialPath.toString(), "tmp").toFile().getCanonicalPath();
        String identifier = UUID.randomUUID().toString();
        Path tempDir = Paths.get(baseDir, identifier);
        Files.createDirectories(tempDir);
        return tempDir;
    }

    private void deleteAllTempDirectories() {
        Path initialPath = Paths.get(Settings.getInstance().getRepositoryPath()).toAbsolutePath().getParent();
        Path path = Paths.get(initialPath.toString(), "tmp");
        try {
            FileUtils.deleteDirectory(path.toFile());
        } catch (IOException e) {
            LOGGER.info("Unable to delete temp directory path: {}", path.toString(), e);
        }
    }

    public Path getBootstrapOutputPath() {
        Path initialPath = Paths.get(Settings.getInstance().getRepositoryPath()).toAbsolutePath().getParent();
        String compressedFilename = String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), this.getFilename());
        Path compressedOutputPath = Paths.get(initialPath.toString(), compressedFilename);
        return compressedOutputPath;
    }

    private void updateStatus(String text) {
        LOGGER.info(text);
        SplashFrame.getInstance().updateStatus(text);
    }

}
