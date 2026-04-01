package org.qortal.arbitrary;

import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataCache;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.FilesystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class ArbitraryDataCache {

    private final boolean overwrite;
    private final Path filePath;
    private final String resourceId;
    private final ResourceIdType resourceIdType;
    private final Service service;
    private final String identifier;

    public ArbitraryDataCache(Path filePath, boolean overwrite, String resourceId,
                              ResourceIdType resourceIdType, Service service, String identifier) {
        this.filePath = filePath;
        this.overwrite = overwrite;
        this.resourceId = resourceId;
        this.resourceIdType = resourceIdType;
        this.service = service;
        this.identifier = identifier;
    }

    public boolean isCachedDataAvailable() {
        return !this.shouldInvalidate();
    }

    public boolean shouldInvalidate() {
        try {
            // If the user has requested an overwrite, always invalidate the cache
            if (this.overwrite) {
                return true;
            }

            // Overwrite is false, but we still need to invalidate if no files exist
            if (!Files.exists(this.filePath) || FilesystemUtils.isDirectoryEmpty(this.filePath)) {
                return true;
            }

            // We might want to overwrite anyway, if an updated version is available
            if (this.shouldInvalidateResource()) {
                return true;
            }

        } catch (IOException e) {
            // Something went wrong, so invalidate the cache just in case
            return true;
        }

        // No need to invalidate the cache
        // Remember that it's up to date, so that we won't check again for a while
        ArbitraryDataManager.getInstance().addResourceToCache(this.getArbitraryDataResource());

        return false;
    }

    private boolean shouldInvalidateResource() {
        switch (this.resourceIdType) {

            case NAME:
                return this.shouldInvalidateName();

            default:
                // Other resource ID types remain constant, so no need to invalidate
                return false;
        }
    }

    private boolean shouldInvalidateName() {
        // To avoid spamming the database too often, we shouldn't check sigs or invalidate when rate limited
        if (this.rateLimitInEffect()) {
            return false;
        }

        // If the state's sig doesn't match the latest transaction's sig, we need to invalidate
        // This means that an updated layer is available
        return this.shouldInvalidateDueToSignatureMismatch();
    }

    /**
     * rateLimitInEffect()
     *
     * When loading a website, we need to check the cache for every static asset loaded by the page.
     * This would involve asking the database for the latest transaction every time.
     * To reduce database load and page load times, we maintain an in-memory list to "rate limit" lookups.
     * Once a resource ID is in this in-memory list, we will avoid cache invalidations until it
     * has been present in the list for a certain amount of time.
     * Items are automatically removed from the list when a new arbitrary transaction arrives, so this
     * should not prevent updates from taking effect immediately.
     *
     * @return whether to avoid lookups for this resource due to the in-memory cache
     */
    private boolean rateLimitInEffect() {
        return ArbitraryDataManager.getInstance().isResourceCached(this.getArbitraryDataResource());
    }

    private boolean shouldInvalidateDueToSignatureMismatch() {

        // Fetch the latest transaction for this name and service
        byte[] latestTransactionSig = this.fetchLatestTransactionSignature();

        // Now fetch the transaction signature stored in the cache metadata
        byte[] cachedSig = this.fetchCachedSignature();

        // If either are null, we should invalidate
        if (latestTransactionSig == null || cachedSig == null) {
            return true;
        }

        // Check if they match
        return !Arrays.equals(latestTransactionSig, cachedSig);
    }

    private byte[] fetchLatestTransactionSignature() {
        try (final Repository repository = RepositoryManager.getRepository()) {
            return repository.getArbitraryRepository().getLatestSignature(this.service, this.resourceId, this.identifier);
        } catch (DataException e) {
            return null;
        }
    }

    private byte[] fetchCachedSignature() {
        try {
            // Fetch the transaction signature stored in the cache metadata
            ArbitraryDataMetadataCache cache = new ArbitraryDataMetadataCache(this.filePath);
            cache.read();
            return cache.getSignature();

        } catch (IOException | DataException e) {
            return null;
        }
    }

    private ArbitraryDataResource getArbitraryDataResource() {
        // TODO: pass an ArbitraryDataResource into the constructor, rather than individual components
        return new ArbitraryDataResource(this.resourceId, this.resourceIdType, this.service, this.identifier);
    }

}
