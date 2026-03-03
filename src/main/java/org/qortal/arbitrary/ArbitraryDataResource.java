package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.exception.DataNotPublishedException;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.arbitrary.ArbitraryDataBuildManager;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
import org.qortal.controller.arbitrary.ArbitraryDataStorageManager;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.FilesystemUtils;
import org.qortal.utils.ListUtils;
import org.qortal.utils.NTP;
import org.qortal.utils.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qortal.data.arbitrary.ArbitraryResourceStatus.Status;

public class ArbitraryDataResource {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataResource.class);

    protected final String resourceId;
    protected final ResourceIdType resourceIdType;
    protected final Service service;
    protected final String identifier;

    private List<ArbitraryTransactionData> transactions;
    private ArbitraryTransactionData latestPutTransaction;
    private ArbitraryTransactionData latestTransaction;
    private int layerCount;
    private Integer localChunkCount = null;
    private Integer totalChunkCount = null;
    private boolean exists = false;


    public ArbitraryDataResource(String resourceId, ResourceIdType resourceIdType, Service service, String identifier) {
        this.resourceId = resourceId.toLowerCase();
        this.resourceIdType = resourceIdType;
        this.service = service;

        // If identifier is a blank string, or reserved keyword "default", treat it as null
        if (identifier == null || identifier.isEmpty() || identifier.equals("default")) {
            identifier = null;
        }
        this.identifier = identifier;
    }

    public ArbitraryResourceStatus getStatusAndUpdateCache(boolean updateCache) {
        ArbitraryResourceStatus arbitraryResourceStatus = null;

        try (final Repository repository = RepositoryManager.getRepository()) {
            arbitraryResourceStatus = this.getStatus(repository);

            if (updateCache) {
                 // Update cache if possible
                 ArbitraryResourceStatus.Status status = arbitraryResourceStatus != null ? arbitraryResourceStatus.getStatus() : null;
                 ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData(this.service, this.resourceId, this.identifier);
                 repository.discardChanges();
                 repository.getArbitraryRepository().setStatus(arbitraryResourceData, status);
                 repository.saveChanges();
 
            }
        } catch (DataException e) {
            LOGGER.info("Unable to update status cache for resource {}: {}", this.toString(), e.getMessage());
        }

        return arbitraryResourceStatus;
    }

    /**
     * Get current status of resource
     *
     * @param repository
     * @return the resource's status
     */
    public ArbitraryResourceStatus getStatus(Repository repository) {
        // Calculate the chunk counts
        this.calculateChunkCounts(repository);

        if (!this.exists) {
            return new ArbitraryResourceStatus(Status.NOT_PUBLISHED, this.localChunkCount, this.totalChunkCount);
        }

        if (resourceIdType != ResourceIdType.NAME) {
            // We only support statuses for resources with a name
            return new ArbitraryResourceStatus(Status.UNSUPPORTED, this.localChunkCount, this.totalChunkCount);
        }

        // Check if the name is blocked
        if (ListUtils.isNameBlocked(this.resourceId)) {
            return new ArbitraryResourceStatus(Status.BLOCKED, this.localChunkCount, this.totalChunkCount);
        }

        // Check if a build has failed
        ArbitraryDataBuildQueueItem queueItem =
                new ArbitraryDataBuildQueueItem(resourceId, resourceIdType, service, identifier);
        if (ArbitraryDataBuildManager.getInstance().isInFailedBuildsList(queueItem)) {
            return new ArbitraryResourceStatus(Status.BUILD_FAILED, this.localChunkCount, this.totalChunkCount);
        }

        // Firstly check the cache to see if it's already built
        try {
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(
                    resourceId, resourceIdType, service, identifier);
            if (arbitraryDataReader.isCachedDataAvailable()) {
                return new ArbitraryResourceStatus(Status.READY, this.localChunkCount, this.totalChunkCount);
            }
        } catch (DataException e) {
            // Assume no usable data
            return new ArbitraryResourceStatus(Status.PUBLISHED, this.localChunkCount, this.totalChunkCount);
        }

        // Check if we have all data locally for this resource
        if (!this.allFilesDownloaded(repository)) {
            if (this.isDownloading(repository)) {
                return new ArbitraryResourceStatus(Status.DOWNLOADING, this.localChunkCount, this.totalChunkCount);
            }
            else if (this.isDataPotentiallyAvailable(repository)) {
                return new ArbitraryResourceStatus(Status.PUBLISHED, this.localChunkCount, this.totalChunkCount);
            }
            return new ArbitraryResourceStatus(Status.MISSING_DATA, this.localChunkCount, this.totalChunkCount);
        }

        // Check if there's a build in progress
        if (ArbitraryDataBuildManager.getInstance().isInBuildQueue(queueItem)) {
            return new ArbitraryResourceStatus(Status.BUILDING, this.localChunkCount, this.totalChunkCount);
        }

        // We have all data locally
        return new ArbitraryResourceStatus(Status.DOWNLOADED, this.localChunkCount, this.totalChunkCount);
    }

    public ArbitraryDataTransactionMetadata getLatestTransactionMetadata() {
        this.fetchLatestTransaction();

        if (latestTransaction != null) {
            byte[] signature = latestTransaction.getSignature();
            byte[] metadataHash = latestTransaction.getMetadataHash();
            if (metadataHash == null) {
                // This resource doesn't have metadata
                return null;
            }

            try {
                ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(metadataHash, signature);
                if (metadataFile.exists()) {
                    ArbitraryDataTransactionMetadata transactionMetadata = new ArbitraryDataTransactionMetadata(metadataFile.getFilePath());
                    transactionMetadata.read();
                    return transactionMetadata;
                }

            } catch (DataException | IOException e) {
                // Do nothing
            }
        }

        return null;
    }

    public boolean delete(Repository repository, boolean deleteMetadata) {
        try {
            this.fetchTransactions(repository);
            if (this.transactions == null) {
                return false;
            }

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

            for (ArbitraryTransactionData transactionData : transactionDataList) {
                ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(transactionData);
                if (arbitraryDataFile == null) {
                    continue;
                }

                // Delete any chunks or complete files from each transaction
                arbitraryDataFile.deleteAll(deleteMetadata);
            }

            // Also delete cached data for the entire resource
            this.deleteCache();

            // Invalidate the hosted transactions cache as we have removed an item
            ArbitraryDataStorageManager.getInstance().invalidateHostedTransactionsCache();

            return true;

        } catch (DataException | IOException e) {
            return false;
        }
    }

    public void deleteCache() throws IOException {
        // Don't delete anything if there's a build in progress
        ArbitraryDataBuildQueueItem queueItem =
                new ArbitraryDataBuildQueueItem(resourceId, resourceIdType, service, identifier);
        if (ArbitraryDataBuildManager.getInstance().isInBuildQueue(queueItem)) {
            return;
        }

        String baseDir = Settings.getInstance().getTempDataPath();
        String identifier = this.identifier != null ?  this.identifier : "default";
        Path cachePath = Paths.get(baseDir, "reader", this.resourceIdType.toString(), StringUtils.sanitizeString(this.resourceId), this.service.toString(), StringUtils.sanitizeString(identifier));
        if (cachePath.toFile().exists()) {
            boolean success = FilesystemUtils.safeDeleteDirectory(cachePath, true);
            if (success) {
                LOGGER.info("Cleared cache for resource {}", this.toString());
            }
        }
    }

    private boolean allFilesDownloaded(Repository repository) {
        // Use chunk counts to speed things up if we can
        if (this.localChunkCount != null && this.totalChunkCount != null &&
            this.localChunkCount >= this.totalChunkCount) {
            return true;
        }

        try {
            this.fetchTransactions(repository);
            if (this.transactions == null) {
                return false;
            }

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

            // Optimized: Create ArbitraryDataFile once per transaction instead of twice
            for (ArbitraryTransactionData transactionData : transactionDataList) {
                ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(transactionData);
                if (arbitraryDataFile == null || !arbitraryDataFile.allFilesExist()) {
                    return false;
                }
            }
            return true;
        
        } catch (DataException e) {
            return false;
        }
    }

  

    /**
     * Calculate chunk counts of a resource
     * Optimized to call fromTransactionData() only once per transaction
     *
     * @param repository optional - a new instance will be created if null
     */
    private void calculateChunkCounts(Repository repository) {
        try {
            this.fetchTransactions(repository);
            if (this.transactions == null) {
                this.exists = false;
                this.localChunkCount = 0;
                this.totalChunkCount = 0;
                return;
            }

            this.exists = true;

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);
            int localChunkCount = 0;
            int totalChunkCount = 0;

            for (ArbitraryTransactionData transactionData : transactionDataList) {
                // Create ArbitraryDataFile once and reuse it for both counts
                ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(transactionData);
                if (arbitraryDataFile == null) {
                    continue;
                }

                // Calculate local chunk count (files that exist on disk)
                Path parentPath = arbitraryDataFile.getFilePath().getParent();
                String[] files = parentPath.toFile().list();
                if (files != null) {
                    // Remove the original copy indicator file if it exists
                    int count = files.length;
                    for (String file : files) {
                        if (file.equals(".original")) {
                            count--;
                            break;
                        }
                    }
                    
                    // If the complete file exists (and this transaction has chunks), subtract it from the count
                    if (arbitraryDataFile.chunkCount() > 0 && arbitraryDataFile.exists()) {
                        // We are only measuring the individual chunks, not the joined file
                        count -= 1;
                    }
                    
                    localChunkCount += count;
                }

                // Calculate total chunk count (expected chunks based on metadata)
                if (transactionData.getMetadataHash() == null) {
                    // This file doesn't have any metadata, therefore it has a single (complete) chunk
                    totalChunkCount += 1;
                } else {
                    totalChunkCount += arbitraryDataFile.fileCount();
                }
            }

            this.localChunkCount = localChunkCount;
            this.totalChunkCount = totalChunkCount;

        } catch (DataException e) {
            // If there's an error, set counts to 0
            this.localChunkCount = 0;
            this.totalChunkCount = 0;
        }
    }

    private boolean isRateLimited(Repository repository) {
        try {
            this.fetchTransactions(repository);
            if (this.transactions == null) {
                return true;
            }

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

            for (ArbitraryTransactionData transactionData : transactionDataList) {
                if (ArbitraryDataManager.getInstance().isSignatureRateLimited(transactionData.getSignature())) {
                    return true;
                }
            }
            return true;

        } catch (DataException e) {
            return false;
        }
    }

    /**
     * Best guess as to whether data might be available
     * This is only used to give an indication to the user of progress
     * @return - whether data might be available on the network
     */
    private boolean isDataPotentiallyAvailable(Repository repository) {
        try {
            this.fetchTransactions(repository);
            if (this.transactions == null) {
                return false;
            }

            Long now = NTP.getTime();
            if (now == null) {
                return false;
            }

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

            for (ArbitraryTransactionData transactionData : transactionDataList) {
                long lastRequestTime = ArbitraryDataManager.getInstance().lastRequestForSignature(transactionData.getSignature());
                // If we haven't requested yet, or requested in the last 30 seconds, there's still a
                // chance that data is on its way but hasn't arrived yet
                if (lastRequestTime == 0 || now - lastRequestTime < 30 * 1000L) {
                    return true;
                }
            }
            return false;

        } catch (DataException e) {
            return false;
        }
    }


    /**
     * Best guess as to whether we are currently downloading a resource
     * This is only used to give an indication to the user of progress
     * @return - whether we are trying to download the resource
     */
    private boolean isDownloading(Repository repository) {
        try {
            this.fetchTransactions(repository);
            if (this.transactions == null) {
                return false;
            }

            Long now = NTP.getTime();
            if (now == null) {
                return false;
            }

            List<ArbitraryTransactionData> transactionDataList = new ArrayList<>(this.transactions);

            for (ArbitraryTransactionData transactionData : transactionDataList) {
                long lastRequestTime = ArbitraryDataManager.getInstance().lastRequestForSignature(transactionData.getSignature());
                // If were have requested data in the last 30 seconds, treat it as "downloading"
                if (lastRequestTime > 0 && now - lastRequestTime < 30 * 1000L) {
                    return true;
                }
            }

            // FUTURE: we may want to check for file hashes (including the metadata file hash) in
            // ArbitraryDataManager.arbitraryDataFileRequests and return true if one is found.

            return false;

        } catch (DataException e) {
            return false;
        }
    }


    /**
     * Fetch relevant arbitrary transactions for resource
     *
     * @param repository
     * @throws DataException
     */
    private void fetchTransactions(Repository repository) throws DataException {
        if (this.transactions != null && !this.transactions.isEmpty()) {
            // Already fetched
            return;
        }

        try {
            // Get the most recent PUT
            ArbitraryTransactionData latestPut = repository.getArbitraryRepository()
                    .getLatestTransaction(this.resourceId, this.service, ArbitraryTransactionData.Method.PUT, this.identifier);
            if (latestPut == null) {
                String message = String.format("Couldn't find PUT transaction for name %s, service %s and identifier %s",
                        this.resourceId, this.service, this.identifierString());
                throw new DataNotPublishedException(message);
            }
            this.latestPutTransaction = latestPut;

            // Load all transactions since the latest PUT
            List<ArbitraryTransactionData> transactionDataList = repository.getArbitraryRepository()
                    .getArbitraryTransactions(this.resourceId, this.service, this.identifier, latestPut.getTimestamp());

            this.transactions = transactionDataList;
            this.layerCount = transactionDataList.size();

        } catch (DataNotPublishedException e) {
            // Ignore without logging
        }
        catch (DataException e) {
            LOGGER.info(String.format("Repository error when fetching transactions for resource %s: %s", this, e.getMessage()));
        }
    }

    private void fetchLatestTransaction() {
        if (this.latestTransaction != null) {
            // Already fetched
            return;
        }

        try (final Repository repository = RepositoryManager.getRepository()) {

            // Get the most recent transaction
            ArbitraryTransactionData latestTransaction = repository.getArbitraryRepository()
                    .getLatestTransaction(this.resourceId, this.service, null, this.identifier);
            if (latestTransaction == null) {
                String message = String.format("Couldn't find transaction for name %s, service %s and identifier %s",
                        this.resourceId, this.service, this.identifierString());
                throw new DataException(message);
            }
            this.latestTransaction = latestTransaction;

        } catch (DataException e) {
            LOGGER.info(String.format("Repository error when fetching latest transaction for resource %s: %s", this, e.getMessage()));
        }
    }

    private String resourceIdString() {
        return resourceId != null ? resourceId : "";
    }

    private String resourceIdTypeString() {
        return resourceIdType != null ? resourceIdType.toString() : "";
    }

    private String serviceString() {
        return service != null ? service.toString() : "";
    }

    private String identifierString() {
        return identifier != null ? identifier : "";
    }

    @Override
    public String toString() {
        return String.format("%s %s %s", this.serviceString(), this.resourceIdString(), this.identifierString());
    }


    /**
     * @return unique key used to identify this resource
     */
    public String getUniqueKey() {
        return String.format("%s-%s-%s", this.service, this.resourceId, this.identifier).toLowerCase();
    }

    public String getResourceId() {
        return this.resourceId;
    }

    public Service getService() {
        return this.service;
    }

    public String getIdentifier() {
        return this.identifier;
    }

    public Integer getLocalChunkCount() {
        return this.localChunkCount;
    }

    public Integer getTotalChunkCount() {
        return this.totalChunkCount;
    }


}
