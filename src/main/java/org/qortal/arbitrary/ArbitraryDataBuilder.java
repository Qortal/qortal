package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.exception.DataNotPublishedException;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataCache;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ArbitraryDataBuilder {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataBuilder.class);

    private final String name;
    private final Service service;
    private final String identifier;

    private boolean canRequestMissingFiles;

    private ArbitraryTransactionData transaction;
    private final List<Path> paths;
    private byte[] latestSignature;
    private Path finalPath;

    public ArbitraryDataBuilder(String name, Service service, String identifier) {
        this.name = name;
        this.service = service;
        this.identifier = identifier;
        this.paths = new ArrayList<>();

        // By default we can request missing files
        // Callers can use setCanRequestMissingFiles(false) to prevent it
        this.canRequestMissingFiles = true;
    }

    /**
     * Process transactions, but do not build anything
     * This is useful for checking the status of a given resource
     */
    public void process() throws DataException, IOException, MissingDataException {
        this.fetchTransactions();
        this.validateTransactions();
        this.processTransactions();
        this.validatePaths();
        this.findLatestSignature();
    }

    /**
     * Build the latest state of a given resource
     */
    public void build() throws DataException, IOException, MissingDataException {
        this.process();
        this.buildLatestState();
        this.cacheLatestSignature();
    }

    private void fetchTransactions() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            this.latestSignature
                = repository.getArbitraryRepository().getLatestSignature(this.service, this.name, this.identifier);

            if (this.latestSignature == null) {
                String message = String.format("Couldn't find PUT transaction for name %s, service %s and identifier %s",
                        this.name, this.service, this.identifierString());
                throw new DataNotPublishedException(message);
            }

            ArbitraryTransactionData latestTransaction
                = repository.getArbitraryRepository().getSingleTransactionBySignature(this.latestSignature);

            this.transaction = latestTransaction;
        }
    }

    private void validateTransactions() throws DataException {
        if (this.transaction == null) {
            throw new DataException("Transaction not found");
        }
        if (this.transaction.getMethod() != Method.PUT) {
            throw new DataException("Expected PUT but received PATCH");
        }
    }

    private void processTransactions() throws IOException, DataException, MissingDataException {

        LOGGER.trace("Found arbitrary transaction {}", Base58.encode(this.transaction.getSignature()));

        // Build the data file, overwriting anything that was previously there
        String sig58 = Base58.encode(this.transaction.getSignature());
        ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(sig58, ResourceIdType.TRANSACTION_DATA,
                this.service, this.identifier);
        arbitraryDataReader.setTransactionData(this.transaction);
        arbitraryDataReader.setCanRequestMissingFiles(this.canRequestMissingFiles);
        boolean hasMissingData = false;
        try {
            arbitraryDataReader.loadSynchronously(true);
        }
        catch (MissingDataException e) {
            hasMissingData = true;
        }

        // Handle missing data
        if (hasMissingData) {
            if (!this.canRequestMissingFiles) {
                throw new MissingDataException("Files are missing but were not requested.");
            }

            throw new MissingDataException("Requesting missing files. Please wait and try again.");
        }

        // By this point we should have all data needed to build the layers
        Path path = arbitraryDataReader.getFilePath();
        if (path == null) {
            throw new DataException(String.format("Null path when building data from transaction %s", sig58));
        }
        if (!Files.exists(path)) {
            throw new DataException(String.format("Path doesn't exist when building data from transaction %s", sig58));
        }
        paths.add(path);
    }

    private void findLatestSignature() throws DataException {
        if (this.latestSignature == null) {
            throw new DataException("Unable to find latest signature");
        }
    }

    private void validatePaths() throws DataException {
        if (this.paths.isEmpty()) {
            throw new DataException("No paths available from which to build latest state");
        }
    }

    private void buildLatestState() throws DataException {
        // assert there is no patching
        if (this.paths.size() == 1) {
            this.finalPath = this.paths.get(0);
        }
        else {
            throw new DataException("Expected PUT but received PATCH");
        }
    }

    private void cacheLatestSignature() throws IOException, DataException {
        if (this.latestSignature == null) {
            throw new DataException("Missing latest transaction signature");
        }
        Long now = NTP.getTime();
        if (now == null) {
            throw new DataException("NTP time not synced yet");
        }

        ArbitraryDataMetadataCache cache = new ArbitraryDataMetadataCache(this.finalPath);
        cache.setSignature(this.latestSignature);
        cache.setTimestamp(NTP.getTime());
        cache.write();
    }

    private String identifierString() {
        return identifier != null ? identifier : "";
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

    public byte[] getLatestSignature() {
        return this.latestSignature;
    }

    public int getLayerCount() {
        return 1;
    }

    /**
     * Use the below setter to ensure that we only read existing
     * data without requesting any missing files,
     *
     * @param canRequestMissingFiles
     */
    public void setCanRequestMissingFiles(boolean canRequestMissingFiles) {
        this.canRequestMissingFiles = canRequestMissingFiles;
    }

}
