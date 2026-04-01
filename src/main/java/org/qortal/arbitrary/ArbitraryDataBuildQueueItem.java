package org.qortal.arbitrary;

import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.repository.DataException;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.NTP;

import java.io.IOException;

public class ArbitraryDataBuildQueueItem extends ArbitraryDataResource {

    private final Long creationTimestamp;
    private Long buildStartTimestamp = null;
    private Long buildEndTimestamp = null;
    private Integer priority = 0;
    private boolean failed = false;
    // Track if we've already recalculated priority with chunk counts to avoid repeated calculations
    private boolean priorityRecalculatedWithChunks = false;

    private static int HIGH_PRIORITY_THRESHOLD = 5;
    
    // Threshold for considering a file "large" (in chunks, where each chunk = 512KB)
    // Files with > 200 chunks (~100MB) are considered large
    private static int LARGE_FILE_CHUNK_THRESHOLD = 200;
    
    // Priority aging: boost priority by 1 every 10 seconds to prevent starvation
    private static long PRIORITY_AGING_INTERVAL_MS = 10 * 1000L; // 10 seconds

    /* The maximum amount of time to spend on a single build */
    // TODO: interrupt an in-progress build
    public static long BUILD_TIMEOUT = 60*1000L; // 60 seconds
    /* The amount of time to remember that a build has failed, to avoid retries */
    public static long FAILURE_TIMEOUT = 5*60*1000L; // 5 minutes

    public ArbitraryDataBuildQueueItem(String resourceId, ResourceIdType resourceIdType, Service service, String identifier) {
        super(resourceId, resourceIdType, service, identifier);

        this.creationTimestamp = NTP.getTime();
        // Calculate initial priority based on file size
        this.calculatePriority();
    }

    public void prepareForBuild() {
        this.buildStartTimestamp = NTP.getTime();
    }

    public void build() throws IOException, DataException, MissingDataException {
        Long now = NTP.getTime();
        if (now == null) {
            this.buildStartTimestamp = null;
            throw new DataException("NTP time hasn't synced yet");
        }

        if (this.buildStartTimestamp == null) {
            this.buildStartTimestamp = now;
        }
        ArbitraryDataReader arbitraryDataReader =
                new ArbitraryDataReader(this.resourceId, this.resourceIdType, this.service, this.identifier);

        try {
            arbitraryDataReader.loadSynchronously(true);
        } finally {
            this.buildEndTimestamp = NTP.getTime();

            // Update status after build
            ArbitraryTransactionUtils.getStatus(service, resourceId, identifier, false, true);
        }
    }

    public boolean isBuilding() {
        return this.buildStartTimestamp != null;
    }

    public boolean isQueued() {
        return this.buildStartTimestamp == null;
    }

    public boolean hasReachedBuildTimeout(Long now) {
        if (now == null || this.creationTimestamp == null) {
            return true;
        }
        return now - this.creationTimestamp > BUILD_TIMEOUT;
    }

    public boolean hasReachedFailureTimeout(Long now) {
        if (now == null || this.buildStartTimestamp == null) {
            return true;
        }
        return now - this.buildStartTimestamp > FAILURE_TIMEOUT;
    }

    public Long getBuildStartTimestamp() {
        return this.buildStartTimestamp;
    }

    /**
     * Calculate priority based on file size (chunk count).
     * Smaller files get higher priority (lower number) to improve responsiveness.
     */
    public void calculatePriority() {
        Integer totalChunks = this.getTotalChunkCount();
        
        if (totalChunks == null) {
            // If we don't know size yet, use default priority
            this.priority = 0;
            return;
        }
        
        // Smaller files get higher priority (lower number)
        if (totalChunks <= 5) {
            // Very small: < 2.5MB - highest priority
            this.priority = 10;
        } else if (totalChunks <= 20) {
            // Small: < 10MB - high priority
            this.priority = 5;
        } else if (totalChunks <= 100) {
            // Medium: < 50MB - normal priority
            this.priority = 0;
        } else {
            // Large: > 50MB - lower priority
            this.priority = -5;
        }
    }

    /**
     * Get effective priority with aging applied.
     * Priority increases (number decreases) over time to prevent starvation.
     * Also recalculates priority ONCE if chunk counts have become available since creation.
     * 
     * @return effective priority (lower number = higher priority)
     */
    public Integer getEffectivePriority() {
        // Only recalculate priority ONCE when chunk counts become available
        // Don't recalculate on every call (this was causing high CPU usage during polling)
        if (!priorityRecalculatedWithChunks) {
            Integer totalChunks = this.getTotalChunkCount();
            if (totalChunks != null) {
                // Chunk count is now available - recalculate priority once
                this.calculatePriority();
                this.priorityRecalculatedWithChunks = true;
            }
        }
        
        Long now = NTP.getTime();
        if (now == null || this.creationTimestamp == null) {
            return this.getPriority();
        }
        
        // Calculate age in milliseconds
        long ageMs = now - this.creationTimestamp;
        
        // Boost priority by 1 every 10 seconds (prevents starvation)
        // Lower number = higher priority, so we subtract the boost
        int ageBoost = (int) (ageMs / PRIORITY_AGING_INTERVAL_MS);
        
        return this.getPriority() - ageBoost;
    }

    public Integer getPriority() {
        if (this.priority != null) {
            return this.priority;
        }
        return 0;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public boolean isHighPriority() {
        return this.priority >= HIGH_PRIORITY_THRESHOLD;
    }
    
    /**
     * Check if this is a large file based on chunk count.
     * Large files should use the reserved thread to prevent blocking small files.
     * 
     * @return true if file has more than LARGE_FILE_CHUNK_THRESHOLD chunks
     */
    public boolean isLargeFile() {
        Integer totalChunks = this.getTotalChunkCount();
        if (totalChunks == null) {
            return false; // Unknown size, treat as not large
        }
        return totalChunks > LARGE_FILE_CHUNK_THRESHOLD;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }
}
