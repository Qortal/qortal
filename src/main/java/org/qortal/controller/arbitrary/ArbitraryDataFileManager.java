package org.qortal.controller.arbitrary;

import java.nio.file.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryDirectConnectionInfo;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
import org.qortal.data.arbitrary.ArbitraryRelayInfo;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.NetworkData;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.network.PeerList;
import org.qortal.network.MessageFactory;
import org.qortal.network.PeerSendManagement;
import org.qortal.network.PeerSendManager;
import org.qortal.network.message.ArbitraryDataFileMessage;
import org.qortal.network.message.BlockSummariesMessage;
import org.qortal.network.message.GenericUnknownMessage;
import org.qortal.network.message.GetArbitraryDataFileMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageException;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import com.google.common.net.InetAddresses;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;

public class ArbitraryDataFileManager extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileManager.class);

    private static ArbitraryDataFileManager instance;
    private volatile boolean isStopping = false;

    // Map to keep track of our in progress (outgoing) arbitrary data file requests
    public Map<String, Long> arbitraryDataFileRequests = Collections.synchronizedMap(new HashMap<>());

    // Map to track requested hashes for validation purposes (longer-lived than arbitraryDataFileRequests)
    // Used to validate incoming chunks even after the main request map has been cleaned up
    // This prevents legitimate delayed chunks from being rejected as unsolicited
    // Cleaned up after 3 minutes (vs 24 seconds for the main map)
    private final Map<String, Long> arbitraryDataFileRequestedGuard = Collections.synchronizedMap(new HashMap<>());

    // Map to keep track of hashes that we might need to relay
    public final List<ArbitraryRelayInfo> arbitraryRelayMap = Collections.synchronizedList(new ArrayList<>());

    // List to keep track of any arbitrary data file hash responses
    private final List<ArbitraryFileListResponseInfo> arbitraryDataFileHashResponses = Collections.synchronizedList(new ArrayList<>());

    // List to keep track of peers potentially available for direct connections, based on recent requests
    private final List<ArbitraryDirectConnectionInfo> directConnectionInfo = Collections.synchronizedList(new ArrayList<>());

    /**
     * Map to keep track of peers requesting QDN data that we hold.
     * Key = peer address string, value = time of last request.
     * This allows for additional "burst" connections beyond existing limits.
     */
    private Map<String, Long> recentDataRequests = Collections.synchronizedMap(new HashMap<>());

    // This needs to be a private class
    private final PendingPeersWithHashes pendingPeersWithHashes = new PendingPeersWithHashes();

    // Track pending relay forwards - which peers requested which hashes from us
    // Key format: hash58 + "|" + sourcePeerAddress (peer we're requesting from)
    private final Map<String, List<PendingRelayForward>> pendingRelayForwards = new ConcurrentHashMap<>();
    
    // Track recently sent chunks to detect duplicates within 3 minutes
    // Key format: hash58 + "|" + peerAddress, Value: timestamp when sent
    private final Map<String, Long> recentlySentChunks = new ConcurrentHashMap<>();
    private static final long DUPLICATE_SEND_WINDOW_MS = 3 * 60 * 1000L; // 3 minutes
    
    // Track peers that sent invalid hashes - prevent re-requesting from same peer for entire file
    // Key format: signature58 + "|" + peerAddress, Value: timestamp when validation failed
    private final Map<String, Long> invalidHashCooldowns = new ConcurrentHashMap<>();
    private static final long INVALID_HASH_COOLDOWN_MS = 10 * 60 * 1000L; // 10 minutes

    /** In-flight request: hash58 -> (signature58, peerAddress). Cleared on receive or when cleanup expires the request. */
    private final Map<String, InFlightRequestInfo> inFlightRequestsByHash = new ConcurrentHashMap<>();
    /** Peers we already tried for this chunk (timed out or in flight). Key: signature58 + "|" + hash58. Used to retry from another peer. */
    private final Map<String, Set<String>> triedPeersByChunk = new ConcurrentHashMap<>();

    private static class InFlightRequestInfo {
        final String signature58;
        final String peerAddress;
        InFlightRequestInfo(String signature58, String peerAddress) {
            this.signature58 = signature58;
            this.peerAddress = peerAddress;
        }
    }

    // Metadata hash by signature (signature58 -> entry). Populated when we have tx data at request time;
    // used in receivedArbitraryDataFile to avoid DB fetch for every chunk - only fetch when chunk is metadata.
    // Entry stores hash + last-insert time for TTL cleanup (entries older than 1 hour are removed).
    private final Map<String, MetadataHashEntry> metadataHashBySignature58 = new ConcurrentHashMap<>();
    private static final long METADATA_HASH_CACHE_TTL_MS = 60 * 60 * 1000L; // 1 hour
    private static final long METADATA_HASH_CACHE_CLEANUP_INTERVAL_MS = 15 * 60 * 1000L; // run cleanup every 15 minutes

    private static class MetadataHashEntry {
        final byte[] metadataHash;
        final long lastUpdatedMs;

        MetadataHashEntry(byte[] metadataHash, long lastUpdatedMs) {
            this.metadataHash = metadataHash;
            this.lastUpdatedMs = lastUpdatedMs;
        }
    }

    // Relay cache configuration
    private static final String RELAY_CACHE_DIR_NAME = "relay-cache";
    private static final long RELAY_CACHE_CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L; // 24 hours
    // Initially set to 1GB, adjust based on estimated cache size
    private int RELAY_CACHE_CLEANUP_TRIGGER = 2000; // Trigger cleanup at ~1GB (assuming 500KB avg per file)
    private static final long RELAY_CACHE_MIN_FILE_AGE_MS = 5 * 60 * 1000L; // 5 minutes minimum age before deletion
    private Path relayCacheDir;
    private final AtomicInteger relayCacheFileCount = new AtomicInteger(0);

    /**
     * Creates a composite key for tracking relay requests by hash and source peer
     * @param hash58 The hash in base58 format
     * @param sourcePeer The peer we're requesting the data from
     * @return Composite key in format "hash58|peerAddress"
     */
    private String createRelayKey(String hash58, Peer sourcePeer) {
        return hash58 + "|" + sourcePeer.getPeerData().getAddress().toString();
    }
    
    /**
     * Initializes the relay cache directory in the system temp directory
     */
    private void initializeRelayCache() {
        try {
            this.relayCacheDir = Paths.get(Settings.getInstance().getDataPath() + File.separator + RELAY_CACHE_DIR_NAME);
            Files.createDirectories(relayCacheDir);
            
            // Count existing files on startup
            File[] existingFiles = relayCacheDir.toFile().listFiles();
            if (existingFiles != null) {
                int fileCount = 0;
                for (File file : existingFiles) {
                    if (file.isFile() && file.getName().endsWith(".tmp")) {
                        fileCount++;
                    }
                }
                this.relayCacheFileCount.set(fileCount);
                LOGGER.debug("Initialized relay cache directory: {} ({} existing files)", relayCacheDir, fileCount);
            } else {
                LOGGER.debug("Initialized relay cache directory: {}", relayCacheDir);
            }
            cleanupRelayCache();  // Run cleanup in case disk conditions changed while node was offline
        } catch (IOException e) {
            LOGGER.error("Failed to initialize relay cache directory: {}", e.getMessage());
            relayCacheDir = null;
        }
    }
    
    /**
     * Gets the path for a relay-cached chunk file
     * @param hash58 The hash of the chunk
     * @return Path to the cached file
     */
    private Path getRelayCachePath(String hash58) {
        if (relayCacheDir == null) {
            return null;
        }
        try {
            return relayCacheDir.resolve(hash58 + ".tmp");
        } catch (InvalidPathException e) {
            return null;
        }
    }
    
    /**
     * Saves chunk data to relay cache using streaming write to avoid holding byte[] reference
     * Triggers cleanup if file count exceeds threshold. Overwriting an existing file does not increment the count.
     * @param hash58 The hash of the chunk
     * @param data The chunk data
     */
    private void saveToRelayCache(String hash58, byte[] data) {
        if (relayCacheDir == null || data == null) {
            return;
        }

        Path cachePath = getRelayCachePath(hash58);
        if (cachePath == null) {
            LOGGER.debug("Invalid relay cache path for hash {}", hash58);
            return;
        }

        boolean isNewFile = false;
        try {
            isNewFile = !Files.exists(cachePath);
            if (isNewFile) {
                int currentCount = relayCacheFileCount.incrementAndGet();
                if (currentCount > RELAY_CACHE_CLEANUP_TRIGGER) {
                    LOGGER.trace("Relay cache has {} files (threshold: {}), triggering cleanup",
                            currentCount, RELAY_CACHE_CLEANUP_TRIGGER);
                    cleanupRelayCache();
                }
            }

            // Use streaming write to avoid holding byte[] reference in memory during I/O
            try (java.io.OutputStream out = Files.newOutputStream(cachePath,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                out.write(data);
                out.flush();
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save to relay cache for hash {}: {}", hash58, e.getMessage());
            if (isNewFile) {
                relayCacheFileCount.decrementAndGet(); // Rollback counter on failure
            }
        }
    }
    
    /**
     * Loads chunk data from relay cache
     * @param hash58 The hash of the chunk
     * @return The chunk data, or null if not found
     */
    public byte[] loadFromRelayCache(String hash58) {
        if (relayCacheDir == null) {
            return null;
        }
        
        try {
            Path cachePath = getRelayCachePath(hash58);
            try {
                if (Files.exists(cachePath)) {
                    return Files.readAllBytes(cachePath);
                }
            } catch (SecurityException e) { // unable to read directory or file
                return null;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to load from relay cache for hash {}: {}", hash58, e.getMessage());
        }
        return null;
    }
    
    /**
     * Cleans up the relay cache according to age and space constraints.
     * Dynamically adjusts based on Qortal's storage usage to avoid interfering with cleanup thresholds.
     */
    private void cleanupRelayCache() {
        if (relayCacheDir == null || !Files.exists(relayCacheDir)) {
            return;
        }
        
        try {
            File[] files = relayCacheDir.toFile().listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            
            // Calculate total size of relay cache
            long totalSize = 0;
            List<FileInfo> fileInfos = new ArrayList<>();
            
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".tmp")) {
                    long size = file.length();
                    totalSize += size;
                    
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
                        fileInfos.add(new FileInfo(file, attrs.creationTime().toMillis(), size));
                    } catch (IOException e) {
                        LOGGER.debug("Failed to read attributes for {}: {}", file.getName(), e.getMessage());
                    }
                }
            }
            
            if (fileInfos.isEmpty()) {
                return;
            }
            
            // Sort by creation time (oldest first)
            fileInfos.sort(Comparator.comparingLong(fi -> fi.creationTime));
            
            // Calculate max allowed size based on Qortal's storage headroom
            long maxAllowedSize;
            ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();
            Long qortalStorageCapacity = storageManager.getStorageCapacity();
            long qortalUsedSpace = storageManager.getTotalDirectorySize();
            
            if (qortalStorageCapacity != null && qortalUsedSpace > 0) {
                // Calculate space before Qortal hits its cleanup threshold (90%)
                long qortalCleanupThreshold = (long)(qortalStorageCapacity * ArbitraryDataStorageManager.DELETION_THRESHOLD);
                long qortalHeadroom = qortalCleanupThreshold - qortalUsedSpace;
                
                if (qortalHeadroom < 0) {
                    // Qortal is already over threshold, use minimal relay cache
                    maxAllowedSize = 500L * 1024 * 1024; // 500MB minimum
                    LOGGER.debug("Qortal over storage threshold, relay cache limited to 500MB");
                } else {
                    // Use up to 10% of the headroom, with min/max bounds
                    long calculatedSize = (long)(qortalHeadroom * 0.10);
                    maxAllowedSize = Math.max(500L * 1024 * 1024, // Min 500MB
                                              calculatedSize);    // 10% of free Qortal QDN Space
                    RELAY_CACHE_CLEANUP_TRIGGER = (int)(maxAllowedSize / (512L * 1024)); // 500KB avg per file
                    LOGGER.debug("Relay cache limit: {} MB (based on {}% of {} MB headroom)", 
                            maxAllowedSize / (1024 * 1024), 
                            (int)(0.10 * 100),
                            qortalHeadroom / (1024 * 1024));
                }
            } else {
                // Fallback: conservative fixed size if Qortal's storage not calculated yet
                maxAllowedSize = 2L * 1024 * 1024 * 1024; // 2GB
                LOGGER.debug("Relay cache limit: 2GB (fallback - Qortal storage not calculated)");
            }
            
            int deletedCount = 0;
            long deletedSize = 0;
            long now = System.currentTimeMillis();
            int skippedYoungFiles = 0;
            
            // Delete oldest files until we're under the limit
            // PROTECTION: Don't delete files younger than minimum age (prevents deletion of in-transit files)
            for (FileInfo fileInfo : fileInfos) {
                if (totalSize <= maxAllowedSize) {
                    break;
                }
                
                // Check file age - skip files that are too young
                long fileAge = now - fileInfo.creationTime;
                if (fileAge < RELAY_CACHE_MIN_FILE_AGE_MS) {
                    skippedYoungFiles++;
                    LOGGER.trace("Skipping file {} - too young ({} seconds old, min {} seconds)", 
                            fileInfo.file.getName(), fileAge / 1000, RELAY_CACHE_MIN_FILE_AGE_MS / 1000);
                    continue;
                }
                
                if (fileInfo.file.delete()) {
                    totalSize -= fileInfo.size;
                    deletedSize += fileInfo.size;
                    deletedCount++;
                    LOGGER.trace("Deleted relay cache file: {} (age: {} seconds)", 
                            fileInfo.file.getName(), fileAge / 1000);
                }
            }
            
            // Update file count after cleanup
            File[] remainingFiles = relayCacheDir.toFile().listFiles();
            int actualCount = 0;
            if (remainingFiles != null) {
                for (File f : remainingFiles) {
                    if (f.isFile() && f.getName().endsWith(".tmp")) {
                        actualCount++;
                    }
                }
            }
            relayCacheFileCount.set(actualCount);
            
            if (deletedCount > 0) {
                String youngFilesMsg = skippedYoungFiles > 0 ? String.format(" (skipped %d young files)", skippedYoungFiles) : "";
                LOGGER.trace("Relay cache cleanup: deleted {} files ({} MB), remaining: {} files ({} MB), limit: {} MB{}",
                        deletedCount, deletedSize / (1024 * 1024),
                        actualCount, totalSize / (1024 * 1024),
                        maxAllowedSize / (1024 * 1024), youngFilesMsg);
            } else {
                LOGGER.trace("Relay cache: {} files ({} MB), limit: {} MB - no cleanup needed",
                        actualCount, totalSize / (1024 * 1024), maxAllowedSize / (1024 * 1024));
            }
            
        } catch (Exception e) {
            LOGGER.error("Error during relay cache cleanup: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Helper class to hold file information for cleanup
     */
    private static class FileInfo {
        final File file;
        final long creationTime;
        final long size;
        
        FileInfo(File file, long creationTime, long size) {
            this.file = file;
            this.creationTime = creationTime;
            this.size = size;
        }
    }
    
    /**
     * Gets the total size of the relay cache in bytes
     * @return Total size in bytes, or 0 if cache is not initialized or empty
     */
    public long getRelayCacheSize() {
        if (relayCacheDir == null) {
            return 0;
        }
        
        File cacheDir = relayCacheDir.toFile();
        if (!cacheDir.exists() || !cacheDir.isDirectory()) {
            return 0;
        }
        
        // Quick check: if directory is empty, return 0 immediately
        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) {
            return 0;
        }
        
        try {
            return FileUtils.sizeOfDirectory(cacheDir);
        } catch (IllegalArgumentException e) {
            // Not a directory or other argument issue
            LOGGER.debug("Relay cache path is not a directory: {}", e.getMessage());
            return 0;
        } catch (Exception e) {
            LOGGER.error("Error calculating relay cache size: {}", e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * Erases all files from the relay cache
     * Uses recursive delete with better error handling - continues on individual file failures
     * @return true if successful, false otherwise
     */
    public boolean eraseRelayCache() {
        if (relayCacheDir == null || !Files.exists(relayCacheDir)) {
            return true; // Nothing to erase, consider it successful
        }
        
        if (!Files.isDirectory(relayCacheDir)) {
            LOGGER.warn("Relay cache path exists but is not a directory: {}", relayCacheDir);
            return false;
        }
        
        try {
            // Get the count before cleaning
            int deletedCount = relayCacheFileCount.get();
            final AtomicInteger failedCount = new AtomicInteger(0);
            
            // Recursive delete using Files.walkFileTree for better error handling
            Files.walkFileTree(relayCacheDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    } catch (IOException e) {
                        // File might be locked or have permission issues - log and continue
                        LOGGER.debug("Failed to delete file {}: {}", file, e.getMessage());
                        failedCount.incrementAndGet();
                        return FileVisitResult.CONTINUE; // Continue deleting other files
                    }
                }
                
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    // Don't delete the root relay cache directory itself
                    if (dir.equals(relayCacheDir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    try {
                        Files.delete(dir);
                    } catch (IOException e) {
                        LOGGER.debug("Failed to delete directory {}: {}", dir, e.getMessage());
                        failedCount.incrementAndGet();
                    }
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    // Log but continue processing other files
                    LOGGER.debug("Failed to access file {}: {}", file, exc.getMessage());
                    failedCount.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
            });
            
            // Update file count
            int failed = failedCount.get();
            relayCacheFileCount.set(failed);

            if (failed > 0) {
                LOGGER.warn("Erased relay cache: deleted {} files, {} failed", deletedCount - failed, failed);
            } else {
                LOGGER.info("Erased relay cache: deleted {} files", deletedCount);
            }
            
            return failed == 0; // Return true only if all deletions succeeded
        } catch (IOException e) {
            LOGGER.error("Error erasing relay cache: {}", e.getMessage(), e);
            return false;
        }
    }


    /**
     * Helper class to hold relay request information
     */
    /**
     * Helper class to track peers that requested a specific hash from us
     * so we can forward it when we receive it
     * Store PeerData instead of Peer to prevent memory leaks from stale Peer objects
     */
	private static class PendingRelayForward {
		final PeerData requestingPeerData;  // Store lightweight PeerData instead of heavy Peer object
		final int messageId;  // Store only the ID, not the full Message object
		final long timestamp;

		PendingRelayForward(Peer requestingPeer, Message originalMessage) {
			this.requestingPeerData = requestingPeer.getPeerData();  // Extract PeerData immediately
			this.messageId = originalMessage.getId();  // Extract ID immediately
			this.timestamp = NTP.getTime();
		}

		boolean isStale() {
			Long now = NTP.getTime();
			return now != null && (now - timestamp) > ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT;
		}
		
		/**
		 * Resolves the Peer object from connected peers, or returns null if disconnected
		 */
		Peer getRequestingPeer(PeerList connectedPeers) {
			return connectedPeers.get(requestingPeerData);
		}
	}

    private ArbitraryDataFileManager() {
        // Initialize relay cache
        initializeRelayCache();
        
        this.arbitraryDataFileHashResponseScheduler.scheduleAtFixedRate(this::processResponses, 60, 1, TimeUnit.SECONDS);
        this.arbitraryDataFileHashResponseScheduler.scheduleAtFixedRate(this::handleFileListRequestProcess, 60, 1, TimeUnit.SECONDS);

        // Clean up stale pending relay forwards and disconnected peers
        cleaner.scheduleAtFixedRate(() -> {
            int totalRemoved = 0;
            PeerList connectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();
            Iterator<Map.Entry<String, List<PendingRelayForward>>> iterator = pendingRelayForwards.entrySet().iterator();
            
            while (iterator.hasNext()) {
                Map.Entry<String, List<PendingRelayForward>> entry = iterator.next();
                String compositeKey = entry.getKey();  // Format: hash58|peerAddress
                List<PendingRelayForward> forwards = entry.getValue();
                
                // Remove stale forwards and disconnected peers from the list
                int beforeSize = forwards.size();
                forwards.removeIf(forward -> {
                    // Remove if stale by time
                    if (forward.isStale()) {
                        return true;
                    }
                    // Remove if peer is no longer connected (prevents memory leak from stale Peer references)
                    Peer peer = forward.getRequestingPeer(connectedPeers);
                    return peer == null;
                });
                int removedCount = beforeSize - forwards.size();
                totalRemoved += removedCount;
                
                // If list is now empty, remove the entire entry
                if (forwards.isEmpty()) {
                    iterator.remove();
                    LOGGER.debug("Removed empty pending relay forward list for composite key {}", compositeKey);
                }
            }
            
            if (totalRemoved > 0) {
                LOGGER.debug("Cleaned up {} stale/disconnected pending relay forward(s)", totalRemoved);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        // Clean up relay cache every 24 hours
        cleaner.scheduleAtFixedRate(() -> {
            cleanupRelayCache();
        }, RELAY_CACHE_CLEANUP_INTERVAL_MS, RELAY_CACHE_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Clean up metadata-hash cache: remove entries older than 1 hour (runs every 15 minutes)
        cleaner.scheduleAtFixedRate(() -> {
            cleanupMetadataHashCache();
        }, METADATA_HASH_CACHE_CLEANUP_INTERVAL_MS, METADATA_HASH_CACHE_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public static ArbitraryDataFileManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataFileManager();

        return instance;
    }

    /**
     * Cache metadata hash for a signature so we can avoid DB fetch for non-metadata chunks.
     * Call this when we have ArbitraryTransactionData (e.g. when processing file list responses).
     * Each insertion updates the entry's timestamp so recently requested resources stay in the cache.
     */
    public void setMetadataHashForSignature(byte[] signature, byte[] metadataHash) {
        if (signature == null || metadataHash == null) {
            return;
        }
        long now = System.currentTimeMillis();
        metadataHashBySignature58.put(Base58.encode(signature), new MetadataHashEntry(metadataHash, now));
    }

    /**
     * Get cached metadata hash for a signature, or null if not cached.
     */
    public byte[] getMetadataHashForSignature(byte[] signature) {
        if (signature == null) {
            return null;
        }
        MetadataHashEntry entry = metadataHashBySignature58.get(Base58.encode(signature));
        return entry != null ? entry.metadataHash : null;
    }

    /**
     * Remove metadata-hash cache entries older than {@link #METADATA_HASH_CACHE_TTL_MS}.
     */
    private void cleanupMetadataHashCache() {
        long now = System.currentTimeMillis();
        long cutoff = now - METADATA_HASH_CACHE_TTL_MS;
        int removed = 0;
        for (Iterator<Map.Entry<String, MetadataHashEntry>> it = metadataHashBySignature58.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, MetadataHashEntry> e = it.next();
            if (e.getValue().lastUpdatedMs < cutoff) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("Cleaned up {} stale metadata-hash cache entries (older than 1 hour)", removed);
        }
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Arbitrary Data File Manager");

        try {
            while (!isStopping) {
                // Nothing to do yet
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            // Fall-through to exit thread...
        }
    }

    public void shutdown() {
        isStopping = true;
        
        // Shutdown schedulers first
        LOGGER.info("Shutting down ArbitraryDataFileManager schedulers...");
        
        arbitraryDataFileHashResponseScheduler.shutdown();
        cleaner.shutdown();
        
        try {
            if (!arbitraryDataFileHashResponseScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("arbitraryDataFileHashResponseScheduler did not terminate in time, forcing shutdown");
                arbitraryDataFileHashResponseScheduler.shutdownNow();
            }
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("cleaner scheduler did not terminate in time, forcing shutdown");
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while shutting down schedulers, forcing shutdown");
            arbitraryDataFileHashResponseScheduler.shutdownNow();
            cleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("ArbitraryDataFileManager schedulers shut down successfully");
        
        // Interrupt the main thread
        this.interrupt();
    }

    /**
     * Adds a hash to the validation guard map only.
     * The guard map persists longer to validate delayed chunk arrivals.
     * This is separate from the main request tracking to keep concerns separated.
     * 
     * @param hash58 the hash to track, encoded in base58
     */
    public void addGuardTracking(String hash58) {
        Long now = NTP.getTime();
        if (now != null) {
            arbitraryDataFileRequestedGuard.put(hash58, now);
        }
    }

    /**
     * Removes a hash from the validation guard map.
     * Called after successful validation of a chunk or when a request fails.
     * 
     * @param hash58 the hash to remove, encoded in base58
     */
    void removeGuardTracking(String hash58) {
        arbitraryDataFileRequestedGuard.remove(hash58);
    }
    
    /**
     * Checks if a signature-peer combination is currently in cooldown due to previous hash validation failure.
     * Used by peer selection logic to avoid re-requesting ANY chunk from peers that sent invalid data for a file.
     * 
     * @param signature58 the file signature to check, encoded in base58
     * @param peerAddress the peer address string
     * @return true if this peer is in cooldown for this file, false otherwise
     */
    public boolean isSignaturePeerInCooldown(String signature58, String peerAddress) {
        String cooldownKey = signature58 + "|" + peerAddress;
        Long cooldownTime = invalidHashCooldowns.get(cooldownKey);
        
        if (cooldownTime == null) {
            return false;
        }
        
        Long now = NTP.getTime();
        if (now == null) {
            return true; // Conservative: assume in cooldown if we can't get time
        }
        
        // Check if cooldown period has expired
        return (now - cooldownTime) < INVALID_HASH_COOLDOWN_MS;
    }

    /**
     * Records that we requested a chunk from a peer. Used so we can retry from a different peer on timeout.
     */
    public void recordChunkRequested(String hash58, String signature58, String peerAddress) {
        inFlightRequestsByHash.put(hash58, new InFlightRequestInfo(signature58, peerAddress));
        triedPeersByChunk.computeIfAbsent(signature58 + "|" + hash58, k -> ConcurrentHashMap.newKeySet()).add(peerAddress);
    }

    /**
     * Clears in-flight and tried state when we successfully receive the chunk.
     */
    public void clearChunkReceived(String hash58, String signature58) {
        inFlightRequestsByHash.remove(hash58);
        triedPeersByChunk.remove(signature58 + "|" + hash58);
    }

    /**
     * Returns peer addresses we already tried for this chunk (so we don't retry from the same peer).
     */
    public Set<String> getTriedPeersForChunk(String signature58, String hash58) {
        Set<String> tried = triedPeersByChunk.get(signature58 + "|" + hash58);
        return tried != null ? Collections.unmodifiableSet(new HashSet<>(tried)) : Collections.emptySet();
    }

    /**
     * Clears tried-peers state for a signature when its batch is removed (prevents memory leak).
     */
    public void clearTriedPeersForSignature(String signature58) {
        String prefix = signature58 + "|";
        triedPeersByChunk.keySet().removeIf(k -> k != null && k.startsWith(prefix));
    }

    public void cleanupRequestCache(Long now) {
        if (now == null) {
            return;
        }
        final long requestMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_REQUEST_TIMEOUT;
        // Always remove after timeout so chunk becomes re-requestable; don't block on queue state (avoids stuck IDLE)
        arbitraryDataFileRequests.entrySet().removeIf(entry -> {
            Long value = entry.getValue();
            if (value == null || value < requestMinimumTimestamp) {
                String hash58 = entry.getKey();
                inFlightRequestsByHash.remove(hash58);
                return true;
            }
            return false;
        });

        // Clean up validation guard map with longer timeout (3 minutes to handle slow networks)
        // This allows validation of chunks that arrive after the main map has been cleaned up
        final long guardMinimumTimestamp = now - (10 * 60 * 1000L); // 10 minutes
        arbitraryDataFileRequestedGuard.entrySet().removeIf(entry -> {
            if (entry.getValue() == null || entry.getValue() < guardMinimumTimestamp) {
                return true;
            }
            return false;
        });

        final long relayMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_RELAY_TIMEOUT;
        // Clean up relay map: remove stale entries AND entries for disconnected peers
        PeerList connectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();
        arbitraryRelayMap.removeIf(entry -> {
            if (entry == null || entry.getTimestamp() == null || entry.getTimestamp() < relayMinimumTimestamp) {
                return true; // Remove stale entries
            }
            // Also remove entries for peers that are no longer connected (prevents memory leak)
            if (entry.getPeerData() != null && entry.getPeer(connectedPeers) == null) {
                LOGGER.trace("Removing relay map entry for disconnected peer: {}", entry.getPeerData());
                return true;
            }
            return false;
        });

        final long directConnectionInfoMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_DIRECT_CONNECTION_INFO_TIMEOUT;
        directConnectionInfo.removeIf(entry -> entry.getTimestamp() < directConnectionInfoMinimumTimestamp);

        final long recentDataRequestMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_RECENT_DATA_REQUESTS_TIMEOUT;
        recentDataRequests.entrySet().removeIf(entry -> entry.getValue() < recentDataRequestMinimumTimestamp);
        
        // Clean up recently sent chunks tracking (older than 3 minutes)
        final long recentlySentMinimumTimestamp = now - DUPLICATE_SEND_WINDOW_MS;
        int removedCount = recentlySentChunks.size();
        recentlySentChunks.entrySet().removeIf(entry -> entry.getValue() < recentlySentMinimumTimestamp);
        removedCount -= recentlySentChunks.size();
        if (removedCount > 0) {
            LOGGER.trace("Cleaned up {} expired entries from recentlySentChunks tracking", removedCount);
        }
        
        // Clean up invalid hash cooldowns (older than 10 minutes)
        final long cooldownMinimumTimestamp = now - INVALID_HASH_COOLDOWN_MS;
        int cooldownRemovedCount = invalidHashCooldowns.size();
        invalidHashCooldowns.entrySet().removeIf(entry -> entry.getValue() < cooldownMinimumTimestamp);
        cooldownRemovedCount -= invalidHashCooldowns.size();
        if (cooldownRemovedCount > 0) {
            LOGGER.debug("Cleaned up {} expired invalid hash cooldowns", cooldownRemovedCount);
        }
    }

    
    // Lock to synchronize access to the list
    private final Object arbitraryDataFileHashResponseLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService arbitraryDataFileHashResponseScheduler = Executors.newScheduledThreadPool(1);
    
    // Scheduled executor service for cleanup tasks (relay forwards and cache)
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    public void addResponse(ArbitraryFileListResponseInfo responseInfo) {

        synchronized (arbitraryDataFileHashResponseLock) {
            this.arbitraryDataFileHashResponses.add(responseInfo);
        }
    }

    private void processResponses() {
        try {
            List<ArbitraryFileListResponseInfo> responsesToProcess;
            synchronized (arbitraryDataFileHashResponseLock) {
                if (arbitraryDataFileHashResponses.isEmpty() && !pendingPeersAndChunks()) {
                    return; // nothing to process
                }
                responsesToProcess = new ArrayList<>(arbitraryDataFileHashResponses);
                arbitraryDataFileHashResponses.clear();
            }

            long now = NTP.getTime();
            ArbitraryDataFileRequestThread.getInstance().processFileHashes(now, responsesToProcess, this);
        } catch (InterruptedException e) {
            LOGGER.error("InterruptedException: {}", e.getMessage());
        } catch (MessageException e) {
            LOGGER.error("MessageException: {}", e.getMessage());
        }
        catch (Exception e) {
            LOGGER.error("Error while processing responses: {}", e.getMessage(), e);
        }
    }

    

    public void receivedArbitraryDataFile(Peer peer, ArbitraryDataFile adf) {
        // Mark peer as actively used for QDN (prevents premature disconnect during active downloads)
        peer.QDNUse();
        
        //ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(hash, signature);
        //boolean fileAlreadyExists = existingFile.exists();
        byte[] signature = adf.getSignature();

        String hash58 = adf.getHash58();
        byte[] hash = adf.getHash();

       

        // Get request timestamp BEFORE removing it (for download speed tracking)
        Long requestTime = arbitraryDataFileRequests.get(hash58);
        
       
        
        arbitraryDataFileRequests.remove(hash58);
        LOGGER.trace(String.format("Removed hash %.8s from arbitraryDataFileRequests", hash58));
        
        // Check if any peers requested this hash from us (for relaying)
        // Use composite key with the responding peer to match the specific relay request
        String relayKey = createRelayKey(hash58, peer);
        List<PendingRelayForward> pendingRequests = pendingRelayForwards.remove(relayKey);
        boolean isRelayOnly = (pendingRequests != null && !pendingRequests.isEmpty());
       
        if (isRelayOnly) {
           
            
            // Validate hash before caching and relaying (using efficient validation)
            if (!adf.validateHash(hash)) {
                LOGGER.warn(String.format("Hash mismatch for relay chunk from peer %s: expected %s but got different hash - REFUSING to relay",
                    peer, hash58));
                // Clear fileContent immediately to free memory on validation failure
                adf.clearFileContent();
                return;
            }
            
            // Get chunk data ONCE - this will be shared across all relay forwards (zero-copy)
            byte[] chunkData = adf.getBytes();
            if (chunkData == null) {
                LOGGER.warn("Chunk data is null for relay forward hash {}", hash58);
                // Clear fileContent immediately to free memory
                adf.clearFileContent();
                return;
            }
            
            // Save to relay cache for persistence (uses streaming write to avoid holding reference)
            saveToRelayCache(hash58, chunkData);
            
            // Capture primitives for MessageFactory lambdas
            byte[] hashCopy = hash;
            byte[] signatureCopy = signature;
            String hash58Copy = hash58;
            
            // ZERO-COPY: Share the same byte[] reference across all relay forwards
            // This avoids loading from cache multiple times (one load per forward)
            // The byte[] will be held by all MessageFactory instances until they're processed
            final byte[] sharedChunkData = chunkData;
            
            // Clear fileContent now - we have sharedChunkData reference and cache file
            adf.clearFileContent();
            
            // Get current connected peers snapshot for resolving Peer objects
            PeerList connectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();
            
            // Forward to all peers that requested this hash from us
            for (PendingRelayForward forward : pendingRequests) {
                // Skip stale requests
                if (forward.isStale()) {
                    LOGGER.debug("Skipping stale relay forward for hash {} to peer {} (age: {}ms)", 
                            hash58, forward.requestingPeerData, NTP.getTime() - forward.timestamp);
                    continue;
                }
                
                // Resolve Peer object from connected peers (filters out disconnected peers)
                Peer requestingPeer = forward.getRequestingPeer(connectedPeers);
                if (requestingPeer == null) {
                    // Peer is no longer connected - skip this forward
                    LOGGER.debug("Skipping relay forward for hash {} - peer {} is no longer connected", 
                            hash58, forward.requestingPeerData);
                    continue;
                }
                
                try {
                    int forwardMessageId = forward.messageId;
                    
                    // ZERO-COPY MessageFactory: uses shared byte[] reference instead of loading from cache
                    // This eliminates duplicate memory allocations when forwarding to multiple peers
                    MessageFactory factory = () -> {
                        try {
                            // Use shared chunk data directly (zero-copy)
                            // If sharedChunkData is still valid, use it; otherwise fall back to cache/disk
                            ArbitraryDataFile lazyAdf;
                            if (sharedChunkData != null) {
                                // Zero-copy: reuse the same byte[] reference
                                lazyAdf = new ArbitraryDataFile(sharedChunkData, signatureCopy, false);
                               
                            } else {
                                // Fallback: shared data was GC'd, load from cache
                                byte[] cachedData = loadFromRelayCache(hash58Copy);
                                if (cachedData != null) {
                                    lazyAdf = new ArbitraryDataFile(cachedData, signatureCopy, false);
                                  
                                } else {
                                    // Final fallback: load from permanent storage
                                    lazyAdf = ArbitraryDataFile.fromHash(hashCopy, signatureCopy);
                                    if (!lazyAdf.exists()) {
                                        LOGGER.debug("Chunk {} no longer exists when trying to relay forward", hash58Copy);
                                        return null;
                                    }
                                    LOGGER.trace("Loaded chunk {} from disk for forwarding (fallback)", hash58Copy);
                                }
                            }
                            
                            ArbitraryDataFileMessage msg = new ArbitraryDataFileMessage(signatureCopy, lazyAdf);
                            msg.setId(forwardMessageId);
                            // Note: We don't clear fileContent here because ArbitraryDataFile created from byte array
                            // doesn't have a filePath, so prefetch can't reload it. The prefetch mechanism will
                            // use fileContent directly if available, avoiding double-loading.
                            return msg;
                        } catch (DataException e) {
                            LOGGER.warn("Failed to load chunk {} for relay forward: {}", hash58Copy, e.getMessage());
                            return null;
                        }
                    };
                    
                    // Check if we recently sent this chunk to this peer (within 3 minutes)
                    String sendKey = hash58 + "|" + requestingPeer.getPeerData().getAddress().toString();
                    Long now = NTP.getTime();
                    
                    
                    
                    // Record this send
                    if (now != null) {
                        recentlySentChunks.put(sendKey, now);
                    }
                    
                    // Use estimated size WITHOUT loading data into memory
                    int estimatedSize = 512 * 1024;  // Typical chunk size (~500KB)
                    PeerSendManagement.getInstance().getOrCreateSendManager(requestingPeer, true).queueMessageFactory(factory, estimatedSize);
          
                } catch (MessageException e) {
                    LOGGER.debug("Failed to queue ArbitraryDataFileMessage for relay: {}", e.getMessage());
                } catch (Exception e) {
                    LOGGER.error("Unexpected error queuing relay forward: {}", e.getMessage(), e);
                }
            }
            // Relay chunks are now cached to temp dir - no need to skip further processing
            LOGGER.trace("Completed relay forwarding for chunk {} - saved to relay cache, using zero-copy for {} forwards", hash58, pendingRequests.size());
            return;
        }
        
        // Only save if this was our own request (not a relay)
        // SECURITY: Reject files we never requested
        // Note: requestTime may be null if cleanup happened (>24s), but request may still be valid
        if (requestTime == null) {
            // Check the validation guard map (lasts 3 minutes vs 24 seconds)
            // This handles the case where arbitraryDataFileRequests was cleaned up but chunks are still in transit
            Long guardRequestTime = arbitraryDataFileRequestedGuard.get(hash58);
            if (guardRequestTime == null) {
                LOGGER.debug("Discarding unsolicited arbitrary data file {} from peer {} (not in arbitraryDataFileRequests or arbitraryDataFileRequestedGuard)", 
                    hash58, peer);
                // Clear fileContent immediately to free memory for unsolicited chunks
                adf.clearFileContent();
                return;
            }
            // Found in guard map - this is a legitimate delayed response
            requestTime = guardRequestTime;
           
        }
        
        try {
            ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(hash, signature);
            boolean fileAlreadyExists = existingFile.exists();
            if(fileAlreadyExists) {
                // Clear fileContent immediately if file already exists - no need to keep in memory
                adf.clearFileContent();
                return;
            }
            
            // Validate hash before saving (using efficient streaming validation)
           
            if (!adf.validateHash(hash)) {
                String signature58 = Base58.encode(signature);
                LOGGER.warn("Hash mismatch for chunk {} (file {}) from peer {}: peer sent invalid data (possible malicious or corrupted)", 
                    hash58, signature58, peer);
                
                // Add cooldown for entire file - prevent re-requesting ANY chunk from this peer for this file for 10 minutes
                String cooldownKey = signature58 + "|" + peer.getPeerData().getAddress().toString();
                Long now = NTP.getTime();
                if (now != null) {
                    invalidHashCooldowns.put(cooldownKey, now);
                    LOGGER.warn("Added 10-minute cooldown for file {} from peer {} due to hash mismatch (affects all chunks)", 
                        signature58, peer);
                }
                
                // Clear fileContent immediately to free memory on validation failure
                adf.clearFileContent();
                return;
            }
          
            
            // Track download speed before saving
            // requestTime is guaranteed to be non-null here due to check above
            long roundTripTime = NTP.getTime() - requestTime;
            int typicalChunkSize = 512 * 1024; // Typical chunk size (~500KB)
            Long previousRTT = peer.getDownloadSpeedTracker().getLatestRoundTripTime();
            peer.getDownloadSpeedTracker().addNewTimeMetric(typicalChunkSize, (int) roundTripTime);
            Long newRTT = peer.getDownloadSpeedTracker().getLatestRoundTripTime();
            if (previousRTT == null) {
                LOGGER.debug("Chunk {} downloaded from {} in {}ms (new RTT measurement, avg={}ms)", 
                    hash58, peer, roundTripTime, newRTT);
            } else {
                LOGGER.debug("Chunk {} downloaded from {} in {}ms (RTT smoothed: {}ms → {}ms)", 
                    hash58, peer, roundTripTime, previousRTT, newRTT);
            }
            
            // Disk write
        
            adf.save();
            
            // Clear fileContent after saving - data is now on disk and can be reloaded if needed
            adf.clearFileContent();
            LOGGER.trace("Saved hash {} to disk (our own request)", hash58);
            
          
            
            // Remove from guard map now that we've successfully validated and saved
            removeGuardTracking(hash58);
            // Clear in-flight/tried state and notify request thread to remove chunk from batch pending (enables retry on timeout)
            String signature58 = Base58.encode(signature);
            clearChunkReceived(hash58, signature58);
            ArbitraryDataFileRequestThread.getInstance().onChunkReceived(signature58, hash58);
        } catch (DataException de) {
            LOGGER.error("FAILED to write hash chunk to disk!");
            // Clear fileContent even on save failure to free memory
            adf.clearFileContent();
            return;
        }

        // Avoid DB fetch for every chunk: only fetch when this chunk might be the metadata chunk.
        byte[] cachedMetadataHash = getMetadataHashForSignature(signature);
        if (cachedMetadataHash != null && !Arrays.equals(cachedMetadataHash, hash)) {
            // Cached metadata hash exists and this chunk is not it - skip DB entirely
        } else {
            ArbitraryTransactionData arbitraryTransactionData = null;
            // Fetch the transaction data (cache miss, or this chunk is the metadata chunk)
            try (final Repository repository = RepositoryManager.getRepository()) {
                arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);
            } catch (DataException e) {
                LOGGER.warn("Unable to fetch transaction data from DB: {}", e.getMessage());
            }

            // If this is a metadata file then we need to update the cache
            if (arbitraryTransactionData != null && arbitraryTransactionData.getMetadataHash() != null) {
                if (Arrays.equals(arbitraryTransactionData.getMetadataHash(), hash)) {
                    ArbitraryDataCacheManager.getInstance().addToUpdateQueue(arbitraryTransactionData);
                    // Remove from our metadata-hash cache so we don't retain entries forever
                    metadataHashBySignature58.remove(Base58.encode(signature));
                }
            }
        }

        // We may need to remove the file list request, if we have all the files for this transaction
        this.handleFileListRequests(signature);

    }

    Map<String, byte[]> signatureBySignature58 = new HashMap<>();

    // Lock to synchronize access to the list
    private final Object handleFileListRequestsLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService handleFileListRequestsScheduler = Executors.newScheduledThreadPool(1);

    private void handleFileListRequests(byte[] signature) {

        synchronized (handleFileListRequestsLock) {
            signatureBySignature58.put(Base58.encode(signature), signature);
        }
    }

    private void handleFileListRequestProcess() {

        Map<String, byte[]> signaturesToProcess;

        synchronized (handleFileListRequestsLock) {
            signaturesToProcess = new HashMap<>(signatureBySignature58);
            signatureBySignature58.clear();
        }

        if( signaturesToProcess.isEmpty() ) return;

        try (final Repository repository = RepositoryManager.getRepository()) {

            // Fetch the transaction data
            List<ArbitraryTransactionData> arbitraryTransactionDataList
                = ArbitraryTransactionUtils.fetchTransactionDataList(repository, new ArrayList<>(signaturesToProcess.values()));

            for( ArbitraryTransactionData arbitraryTransactionData : arbitraryTransactionDataList ) {
                boolean completeFileExists = ArbitraryTransactionUtils.completeFileExists(arbitraryTransactionData);

                if (completeFileExists) {
                    String signature58 = Base58.encode(arbitraryTransactionData.getSignature());
                    LOGGER.debug("All chunks or complete file exist for transaction {}", signature58);

                    ArbitraryDataFileListManager.getInstance().deleteFileListRequestsForSignature(signature58);
                }
            }

        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

  

    // Fetch data directly from peers
    private List<ArbitraryDirectConnectionInfo> getDirectConnectionInfoForSignature(byte[] signature) {
        synchronized (directConnectionInfo) {
            return directConnectionInfo.stream().filter(i -> Arrays.equals(i.getSignature(), signature)).collect(Collectors.toList());
        }
    }

    /**
     * Add an ArbitraryDirectConnectionInfo item, but only if one with this peer-signature combination
     * doesn't already exist.
     * @param connectionInfo - the direct connection info to add
     */
    public void addDirectConnectionInfoIfUnique(ArbitraryDirectConnectionInfo connectionInfo) {
        boolean peerAlreadyExists;
        synchronized (directConnectionInfo) {
            peerAlreadyExists = directConnectionInfo.stream()
                    .anyMatch(i -> Arrays.equals(i.getSignature(), connectionInfo.getSignature())
                            && Objects.equals(i.getPeerAddress(), connectionInfo.getPeerAddress()));
        }
        if (!peerAlreadyExists) {
            directConnectionInfo.add(connectionInfo);
        }
    }

    private void removeDirectConnectionInfo(ArbitraryDirectConnectionInfo connectionInfo) {
        this.directConnectionInfo.remove(connectionInfo);
    }

    public boolean fetchDataFilesFromPeersForSignature(byte[] signature) {
        String signature58 = Base58.encode(signature);

        boolean success = false;

        try {
            while (!success) {
                if (isStopping) {
                    return false;
                }
                Thread.sleep(500L);

                // Firstly fetch peers that claim to be hosting files for this signature
                List<ArbitraryDirectConnectionInfo> connectionInfoList = getDirectConnectionInfoForSignature(signature);
                if (connectionInfoList == null || connectionInfoList.isEmpty()) {
                    LOGGER.debug("No remaining direct connection peers found for signature {}", signature58);
                    return false;
                }

                LOGGER.debug("Attempting a direct peer connection for signature {}...", signature58);

                // Peers found, so pick one with the highest number of chunks
                Comparator<ArbitraryDirectConnectionInfo> highestChunkCountFirstComparator =
                        Comparator.comparingInt(ArbitraryDirectConnectionInfo::getHashCount).reversed();
                ArbitraryDirectConnectionInfo directConnectionInfo = connectionInfoList.stream()
                        .sorted(highestChunkCountFirstComparator).findFirst().orElse(null);

                if (directConnectionInfo == null) {
                    return false;
                }

                // Remove from the list so that a different peer is tried next time
                removeDirectConnectionInfo(directConnectionInfo);

                // If we have nodeId, try to use an already-connected peer (same logic as ArbitraryDataFileRequestThread)
                String nodeId = directConnectionInfo.getNodeId();
                if (nodeId != null) {
                    Peer connectedPeer = NetworkData.getInstance().getImmutableConnectedPeers().stream()
                            .filter(peer -> peer.getPeersNodeId() != null && peer.getPeersNodeId().equals(nodeId))
                            .findFirst()
                            .orElse(null);
                    if (connectedPeer != null) {
                        success = ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(connectedPeer, signature);
                        if (success) {
                            ArbitraryDataFileListManager.getInstance().addToSignatureRequests(signature58, false, true);
                        }
                        // If not success, fall through to address-based connection attempts below
                    }
                }

                if (!success) {
                String peerAddressString = directConnectionInfo.getPeerAddress();

                // Parse the peer address to find the host and port
                String host = null;
                int port = -1;
                String[] parts = peerAddressString.split(":");
                if (parts.length > 1) {
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                } else {
                    // Assume no port included
                    host = peerAddressString;
                    // Use default listen port
                    port = Settings.getInstance().getDefaultQDNListenPort();
                }

                String peerAddressStringWithPort = String.format("%s:%d", host, port);
                success = NetworkData.getInstance().requestDataFromPeer(peerAddressStringWithPort, signature);

                int defaultPort = Settings.getInstance().getDefaultQDNListenPort();

                // If unsuccessful, and using a non-standard port, try a second connection with the default listen port,
                // since almost all nodes use that. This is a workaround to account for any ephemeral ports that may
                // have made it into the dataset.
                if (!success) {
                    if (host != null && port > 0) {
                        if (port != defaultPort) {
                            String newPeerAddressString = String.format("%s:%d", host, defaultPort);
                            success = NetworkData.getInstance().requestDataFromPeer(newPeerAddressString, signature);
                        }
                    }
                }

                // If _still_ unsuccessful, try matching the peer's IP address with some known peers, and then connect
                // to each of those in turn until one succeeds.
                if (!success) {
                    if (host != null) {
                        final String finalHost = host;
                        List<PeerData> knownPeers = NetworkData.getInstance().getAllKnownPeers().stream()
                                .filter(knownPeerData -> knownPeerData.getAddress().getHost().equals(finalHost))
                                .collect(Collectors.toList());
                        // Loop through each match and attempt a connection
                        for (PeerData matchingPeer : knownPeers) {
                            String matchingPeerAddress = matchingPeer.getAddress().toString();
                            int matchingPeerPort = matchingPeer.getAddress().getPort();
                            // Make sure that it's not a port we've already tried
                            if (matchingPeerPort != port && matchingPeerPort != defaultPort) {
                                success = NetworkData.getInstance().requestDataFromPeer(matchingPeerAddress, signature);
                                if (success) {
                                    // Successfully connected, so stop making connections
                                    break;
                                }
                            }
                        }
                    }
                }

                if (success) {
                    // We were able to connect with a peer, so track the request
                    ArbitraryDataFileListManager.getInstance().addToSignatureRequests(signature58, false, true);
                }

                } 
            }
        } catch (InterruptedException e) {
            // Do nothing
        }

        return success;
    }

    // Relays
    private List<ArbitraryRelayInfo> getRelayInfoListForHash(String hash58) {
        synchronized (arbitraryRelayMap) {
            return arbitraryRelayMap.stream()
                    .filter(relayInfo -> Objects.equals(relayInfo.getHash58(), hash58))
                    .collect(Collectors.toList());
        }
    }

    private ArbitraryRelayInfo getOptimalRelayInfoEntryForHash(String hash58) {
        LOGGER.trace("Fetching relay info for hash: {}", hash58);
        List<ArbitraryRelayInfo> relayInfoList = this.getRelayInfoListForHash(hash58);
        if (relayInfoList != null && !relayInfoList.isEmpty()) {

            // Remove any with null requestHops
            relayInfoList.removeIf(r -> r.getRequestHops() == null);

            // If list is now empty, then just return one at random
            if (relayInfoList.isEmpty()) {
                return this.getRandomRelayInfoEntryForHash(hash58);
            }

            // Sort by number of hops (lowest first)
            relayInfoList.sort(Comparator.comparingInt(ArbitraryRelayInfo::getRequestHops));

            // FUTURE: secondary sort by requestTime?

            ArbitraryRelayInfo relayInfo = relayInfoList.get(0);

            LOGGER.trace("Returning optimal relay info for hash: {} (requestHops {})", hash58, relayInfo.getRequestHops());
            return relayInfo;
        }
        LOGGER.trace("No relay info exists for hash: {}", hash58);
        return null;
    }

    private ArbitraryRelayInfo getRandomRelayInfoEntryForHash(String hash58) {
        LOGGER.trace("Fetching random relay info for hash: {}", hash58);
        List<ArbitraryRelayInfo> relayInfoList = this.getRelayInfoListForHash(hash58);
        if (relayInfoList != null && !relayInfoList.isEmpty()) {

            // Pick random item
            int index = new SecureRandom().nextInt(relayInfoList.size());
            LOGGER.trace("Returning random relay info for hash: {} (index {})", hash58, index);
            return relayInfoList.get(index);
        }
        LOGGER.trace("No relay info exists for hash: {}", hash58);
        return null;
    }

    public void addToRelayMap(ArbitraryRelayInfo newEntry) {
        if (newEntry == null || !newEntry.isValid()) {
            return;
        }

        // Remove existing entry for this peer if it exists, to renew the timestamp
        this.removeFromRelayMap(newEntry);

        // Re-add
        arbitraryRelayMap.add(newEntry);
        LOGGER.debug("Added entry to relay map: {}", newEntry);
    }

    private void removeFromRelayMap(ArbitraryRelayInfo entry) {
        arbitraryRelayMap.removeIf(relayInfo -> relayInfo.equals(entry));
    }

    // Peers requesting QDN data from us
    /**
     * Adds a recent data request timestamp for the specified peer address.
     * <p>
     * The method performs validation on the input to ensure it is non-null,
     * that the current NTP time is available, and that the host portion of
     * the peer address is a valid IP address. If valid, the host portion
     * (without the port) is recorded with the current timestamp.
     * <p>
     * This is typically used to track which peers have recently requested data,
     * while normalizing peer addresses to avoid mismatches due to port differences.
     *
     * @param peerAddress the address of the peer in the format "host:port" (e.g., "192.168.0.2:1234")
     *
     */
    public void addRecentDataRequest(String peerAddress) {
        if (peerAddress == null) {
            return;
        }

        Long now = NTP.getTime();
        if (now == null) {
            return;
        }

        // Make sure to remove the port, since it isn't guaranteed to match next time
        String[] parts = peerAddress.split(":");
        if (parts.length == 0) {
            return;
        }
        String host = parts[0];
        if (!InetAddresses.isInetAddress(host)) {
            // Invalid host
            return;
        }

        this.recentDataRequests.put(host, now);
    }

    public boolean isPeerRequestingData(String peerAddressWithoutPort) {
        return this.recentDataRequests.containsKey(peerAddressWithoutPort);
    }

    public boolean hasPendingDataRequest() {
        return !this.recentDataRequests.isEmpty();
    }

    // Network handlers
    private void processDataFile(Peer peer, byte[] hash, byte[] sig, Message originalMessage) {
        // Mark peer as actively used for QDN (we're serving them data)
        peer.QDNUse();

        final String hash58 = Base58.encode(hash);
        try {
            ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, sig);
            
            // Priority 1: Check permanent storage
            if (arbitraryDataFile.exists()) {
           

                // Check if we recently sent this chunk to this peer (within 3 minutes)
                String sendKey = hash58 + "|" + peer.getPeerData().getAddress().toString();
                Long now = NTP.getTime();
                
             
                
                // Record this send
                if (now != null) {
                    recentlySentChunks.put(sendKey, now);
                }

                // Use lazy loading - only load chunk from disk when about to send
                int messageId = originalMessage.getId();
                MessageFactory factory = () -> {
                    try {
                        ArbitraryDataFile adf = ArbitraryDataFile.fromHash(hash, sig);
                        if (!adf.exists()) {
                            LOGGER.warn("Chunk {} no longer exists when trying to send", hash58);
                            return null;
                        }
                        ArbitraryDataFileMessage msg = new ArbitraryDataFileMessage(sig, adf);
                        msg.setId(messageId);
                        return msg;
                    } catch (DataException e) {
                        LOGGER.warn("Failed to load chunk {} from disk: {}", hash58, e.getMessage());
                        return null;
                    }
                };
                
                // Estimate chunk size (~500KB typical)
                int estimatedSize = 512 * 1024;
                PeerSendManagement.getInstance().getOrCreateSendManager(peer, true).queueMessageFactory(factory, estimatedSize);
                return; // Early return - found in permanent storage
            } else {
                LOGGER.debug("Hash {} does not exist in permanent storage, queueing send to {}", hash58, peer);
            }
            
            // Priority 2: Check relay cache (before checking relayInfo)
            byte[] cachedData = loadFromRelayCache(hash58);
            if (cachedData != null) {
                LOGGER.debug("Hash {} found in relay cache, sending directly to {} (cache hit!)", hash58, peer);
                
                int messageId = originalMessage.getId();
                MessageFactory factory = () -> {
                    try {
                        byte[] data = loadFromRelayCache(hash58);
                        if (data == null) {
                            LOGGER.warn("Chunk {} disappeared from relay cache between check and send", hash58);
                            return null;
                        }
                        ArbitraryDataFile adf = new ArbitraryDataFile(data, sig, false);
                        ArbitraryDataFileMessage msg = new ArbitraryDataFileMessage(sig, adf);
                        msg.setId(messageId);
                        // Note: We don't clear fileContent here because ArbitraryDataFile created from byte array
                        // doesn't have a filePath, so prefetch can't reload it. The prefetch mechanism will
                        // use fileContent directly if available, avoiding double-loading.
                        return msg;
                    } catch (DataException e) {
                        LOGGER.warn("Failed to load chunk {} from relay cache: {}", hash58, e.getMessage());
                        return null;
                    }
                };
                
                int estimatedSize = 512 * 1024;
                PeerSendManagement.getInstance().getOrCreateSendManager(peer, true).queueMessageFactory(factory, estimatedSize);
                return; // Early return - found in relay cache, skip all relay logic
            }
            
            // Priority 3: Check relay info (cache miss - need to fetch from network)
            // First, log all available relay options to diagnose potential issues
            List<ArbitraryRelayInfo> allRelayInfos = this.getRelayInfoListForHash(hash58);
            if (allRelayInfos != null && !allRelayInfos.isEmpty()) {
                LOGGER.trace("Found {} relay info entries for hash {}", allRelayInfos.size(), hash58);
                for (ArbitraryRelayInfo info : allRelayInfos) {
                    PeerAddress addr = info.getPeerData() != null ? info.getPeerData().getAddress() : null;
                    LOGGER.trace("  - Relay option: peer={}, hops={}, timestamp={}", 
                            addr, info.getRequestHops(), info.getTimestamp());
                }
            }
            
            ArbitraryRelayInfo relayInfo = this.getOptimalRelayInfoEntryForHash(hash58);
            if (relayInfo != null) {
                removeFromRelayMap(relayInfo);
                if (!Settings.getInstance().isRelayModeEnabled()) {
                    LOGGER.info("Relay info exists for hash {} but relay mode is disabled", hash58);
                }
                LOGGER.trace("Selected optimal relay info for hash {}: peer={}, hops={}", hash58,
                        relayInfo.getPeerData() != null ? relayInfo.getPeerData().getAddress() : "null",
                        relayInfo.getRequestHops());
                
                // Get the relay peer info - resolve from connected peers to avoid stale references
                PeerList connectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();
                Peer relayPeer = relayInfo.getPeer(connectedPeers);
                PeerData relayPeerData = relayInfo.getPeerData();
                PeerAddress relayPeerAddress = relayPeerData != null ? relayPeerData.getAddress() : null;
                
                if (relayPeerData == null) {
                    LOGGER.warn("Relay peer data is null for hash {}", hash58);
                } else {
                    LOGGER.trace("Processing relay for hash {}: requesting peer={}, relay peer={}", 
                            hash58, peer.getPeerData().getAddress(), relayPeerAddress);
                    
                    //  Check for relay loop - don't send back to the requesting peer
                    // This can happen when multiple relay infos exist and the "optimal" one (by hop count)
                    // points back to the requester
                    if (relayPeer != null) {
                        PeerAddress requestingPeerAddress = peer.getPeerData().getAddress();
                        PeerAddress resolvedRelayAddress = relayPeer.getPeerData().getAddress();
                        
                        // Check if the relay peer is the same as the requesting peer (would create a loop)
                        boolean isSamePeer = requestingPeerAddress.getHost().equalsIgnoreCase(resolvedRelayAddress.getHost())
                                          && requestingPeerAddress.getPort() == resolvedRelayAddress.getPort();
                        
                        if (isSamePeer) {
                            LOGGER.debug("Relay loop detected for hash {}! Optimal relay peer {} ({}:{}) is the SAME as requesting peer {} ({}:{})", 
                                    hash58,
                                    relayPeer, resolvedRelayAddress.getHost(), resolvedRelayAddress.getPort(),
                                    peer, requestingPeerAddress.getHost(), requestingPeerAddress.getPort());
                            LOGGER.debug("This indicates multiple relay infos exist and hop-count optimization selected the requester");
                            
                            // Try to find an alternative relay peer that ISN'T the requesting peer
                            if (allRelayInfos != null && allRelayInfos.size() > 1) {
                                LOGGER.debug("Searching for alternative relay peer from {} available options", allRelayInfos.size());
                                ArbitraryRelayInfo alternativeRelayInfo = allRelayInfos.stream()
                                        .filter(info -> {
                                            PeerAddress infoAddr = info.getPeerData() != null ? info.getPeerData().getAddress() : null;
                                            if (infoAddr == null) return false;
                                            // Filter out the requesting peer to prevent loops
                                            boolean isNotRequestingPeer = !(infoAddr.getHost().equalsIgnoreCase(requestingPeerAddress.getHost())
                                                                         && infoAddr.getPort() == requestingPeerAddress.getPort());
                                            return isNotRequestingPeer;
                                        })
                                        .min(Comparator.comparingInt(info -> info.getRequestHops() != null ? info.getRequestHops() : Integer.MAX_VALUE))
                                        .orElse(null);
                                
                                if (alternativeRelayInfo != null) {
                                    LOGGER.debug("Found alternative relay peer: {} (hops: {})", 
                                            alternativeRelayInfo.getPeerData().getAddress(),
                                            alternativeRelayInfo.getRequestHops());
                                    relayInfo = alternativeRelayInfo;
                                    relayPeer = relayInfo.getPeer(connectedPeers);
                                    relayPeerData = relayInfo.getPeerData();
                                    relayPeerAddress = relayPeerData != null ? relayPeerData.getAddress() : null;
                                } else {
                                    LOGGER.debug("No alternative relay peer found - all relay infos point to requesting peer! Skipping relay.");
                                    relayPeer = null;
                                }
                            } else {
                                LOGGER.debug("Only one relay info exists and it points to requesting peer - skipping relay for hash {}", hash58);
                                relayPeer = null;
                            }
                        } else {
                            LOGGER.trace("Relay peer {} is different from requesting peer {} - OK to relay", 
                                    resolvedRelayAddress, requestingPeerAddress);
                        }
                    }
                    
                    // Try to get the current NetworkData peer for this address
                   
                    boolean socketOpen = relayPeer != null 
                            && relayPeer.getSocketChannel() != null 
                            && relayPeer.getSocketChannel().isOpen();
                    
                    LOGGER.trace("Resolved relayPeer: {}, socketOpen: {}", relayPeer, socketOpen);
                    
                    if (!socketOpen && Settings.getInstance().isRelayModeEnabled()) {
                        // Socket is closed or peer not found - try to force connect
                        LOGGER.info("Socket closed or peer not found for relay to {}. Attempting reconnect...", relayPeerAddress);
                        
                        // Add this hash to pending requests for when connection completes
                        // (Similar to direct connection handling in ArbitraryDataFileRequestThread)
                        // For now, we'll just log and skip - connection will be established for next time
                        LOGGER.warn("Skipping relay for hash {} because socket is closed. Forcing reconnect for future requests.", hash58);
                       
                    }
                    else if (socketOpen && Settings.getInstance().isRelayModeEnabled()) {
                       
                        
                        // Track that this peer requested this hash from us, using composite key
                        // Key includes the relay peer we're requesting from, so we can match responses correctly
                        String relayKey = createRelayKey(hash58, relayPeer);
                        pendingRelayForwards.computeIfAbsent(relayKey, k -> Collections.synchronizedList(new ArrayList<>()))
                            .add(new PendingRelayForward(peer, originalMessage));
                        
                 
                        
                        LOGGER.debug("Relaying hash {} from requesting peer {} ({}:{}) to relay peer {} ({}:{})", 
                                hash58, 
                                peer, peer.getPeerData().getAddress().getHost(), peer.getPeerData().getAddress().getPort(),
                                relayPeer, relayPeer.getPeerData().getAddress().getHost(), relayPeer.getPeerData().getAddress().getPort());
                        
                        // Relay tracking is handled by pendingRelayForwards map only
                        // No need for arbitraryDataFileRequests or guard tracking since:
                        // 1. Relay chunks are identified via pendingRelayForwards (line 946)
                        // 2. Relay chunks exit early (line 1062) before guard check (line 1071)
                        // 3. Relay validation uses pendingRelayForwards timeout, not guard timeout
                        
                        // Request the file from the relay peer using PeerSendManager
                        try {
                            GetArbitraryDataFileMessage getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(sig, hash);
                            getArbitraryDataFileMessage.setId(originalMessage.getId());
                            PeerSendManagement.getInstance().getOrCreateSendManager(relayPeer, true).queueMessage(getArbitraryDataFileMessage);
                            LOGGER.debug("Successfully sent GetArbitraryDataFileMessage for hash {} to relay peer {} ({}:{})", 
                                    hash58, relayPeer, 
                                    relayPeer.getPeerData().getAddress().getHost(), 
                                    relayPeer.getPeerData().getAddress().getPort());
                        } catch (MessageException e) {
                            LOGGER.error("Failed to create GetArbitraryDataFileMessage for relay: {}", e.getMessage());
                            // Clean up pendingRelayForwards since request failed
                            List<PendingRelayForward> forwards = pendingRelayForwards.get(relayKey);
                            if (forwards != null) {
                                // Remove by matching PeerData and message ID
                                PeerData peerData = peer.getPeerData();
                                forwards.removeIf(f -> f.requestingPeerData.equals(peerData) && f.messageId == originalMessage.getId());
                            }
                        }
                    }
                    else {
                        LOGGER.warn("Cannot relay for hash {}: relayPeer={}, socketOpen={}, relayModeEnabled={}",
                                hash58, relayPeer, socketOpen, Settings.getInstance().isRelayModeEnabled());
                    }
                }
            }
            else {
                LOGGER.debug("Hash {} doesn't exist anywhere (permanent storage, relay cache, or relay info)", hash58);

                // We don't have this file
                Controller.getInstance().stats.getArbitraryDataFileMessageStats.unknownFiles.getAndIncrement();

                // Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
                LOGGER.debug("Sending 'file unknown' response to peer {} for GET_FILE request for unknown file {}", peer, arbitraryDataFile);

                // Send generic 'unknown' message as it's very short
                Message fileUnknownMessage = peer.getPeersVersion() >= GenericUnknownMessage.MINIMUM_PEER_VERSION
                        ? new GenericUnknownMessage()
                        : new BlockSummariesMessage(Collections.emptyList());
                fileUnknownMessage.setId(originalMessage.getId());
                if (!peer.sendMessage(fileUnknownMessage)) {
                    LOGGER.debug("Couldn't sent file-unknown response");
                }
                else {
                    LOGGER.debug("Sent file-unknown response for file {}", arbitraryDataFile);
                }
            }
        }
        catch (DataException e) {
            LOGGER.debug("Unable to handle request for arbitrary data file: {}", hash58);
        } catch (MessageException e) {
            throw new RuntimeException(e);
        } 

    }

    // @ToDo: New Message Type, get a series of file hashes
//    public void onNetworkGetArbitraryDataFilesMessage(Peer peer, Message message) {
//        // Request for Multiple-Files
//        if (!Settings.getInstance().isQdnEnabled()) {
//            return;
//        }
//        LOGGER.info("NEW MESSAGE - GetArbitraryDataFiles");
//        GetArbitraryDataFilesMessage getArbitraryDataFilesMessage = (GetArbitraryDataFilesMessage) message;
//        List<byte[]> hashes = getArbitraryDataFilesMessage.getHashes();
//
//        byte[] signature = getArbitraryDataFilesMessage.getSignature();
//        int hashCount = getArbitraryDataFilesMessage.getHashCount();
//        Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.addAndGet(hashCount);
//        //LOGGER.info("Received GetArbitraryDataFilesMessages from peer {} for {} hashes", peer, hashCount);
//        for (byte[] hash : hashes) {
//            processDataFile(peer, hash, signature, message.getId());
//        }
//    }

    public void onNetworkGetArbitraryDataFileMessage(Peer peer, Message message) {
        // Don't respond if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }
        GetArbitraryDataFileMessage getArbitraryDataFileMessage = (GetArbitraryDataFileMessage) message;
        byte[] hash = getArbitraryDataFileMessage.getHash();
        byte[] signature = getArbitraryDataFileMessage.getSignature();
        Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.incrementAndGet();

        processDataFile(peer, hash, signature, message);
    }


    

    /* Calls to SubClass */
    Map<String, Integer> getPeerTimeOuts() {
        return this.pendingPeersWithHashes.getTimeOuts();
    }

    Map<String, List<ArbitraryFileListResponseInfo>> getPendingPeerAndChunks() {
        return this.pendingPeersWithHashes.getPendingPeersWithChunks();
    }

    void removePeerTimeOut(String peer) {
        this.pendingPeersWithHashes.cleanOut(peer);
    }

 

    void incrementTimeOuts() {
        this.pendingPeersWithHashes.incrementTimeOuts();
    }

    boolean pendingPeersAndChunks() {
        return this.pendingPeersWithHashes.isPending();
    }

    void addResponseToPending(Peer peer, ArbitraryFileListResponseInfo ri) {
        this.pendingPeersWithHashes.addPeerAndInfo(peer, ri);
    }

    boolean getIsConnectingPeer(String peer) {
        return this.pendingPeersWithHashes.isConnecting(peer);
    }

    void setIsConnecting(String peer, boolean v) {
        this.pendingPeersWithHashes.setConnecting(peer, v);
    }

    public static class PendingPeersWithHashes {

        // All keys are in the format "Host:Port"
        private final Map<String, List<ArbitraryFileListResponseInfo>> pendingPeerAndChunks;
        private final Map<String, Integer> pendingPeerTries;

        private final Set<String> isConnectingPeers;
        private final Object combinedLock = new Object();

        public PendingPeersWithHashes() {
            pendingPeerTries = new HashMap<>();
            pendingPeerAndChunks = new HashMap<>();
            isConnectingPeers = new HashSet<>();
        }

        Map<String, Integer> getTimeOuts() {
            synchronized (combinedLock) {
                return new HashMap<>(this.pendingPeerTries);
            }
        }

        // Snapshot of pendingPeerAndChunks (deep-ish copy of lists)
        Map<String, List<ArbitraryFileListResponseInfo>> getPendingPeersWithChunks() {
            synchronized (combinedLock) {
                Map<String, List<ArbitraryFileListResponseInfo>> copy = new HashMap<>(this.pendingPeerAndChunks.size());
                for (Map.Entry<String, List<ArbitraryFileListResponseInfo>> e : this.pendingPeerAndChunks.entrySet()) {
                    // create a new List copy so callers can iterate safely
                    copy.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
                return copy;
            }
        }

        void cleanOut(String peer) {
            synchronized (combinedLock) {
                this.pendingPeerTries.remove(peer);
                this.pendingPeerAndChunks.remove(peer);
                this.isConnectingPeers.remove(peer);  // Also clear the connecting flag on timeout
            }
        }

        void incrementTimeOuts() {
            synchronized (combinedLock) {
                this.pendingPeerTries.replaceAll((key, value) -> value + 1);
            }
        }

        boolean isPending() {
            return !this.pendingPeerAndChunks.isEmpty();
        }



        void addPeerAndInfo(Peer peer, ArbitraryFileListResponseInfo aflri) {
            synchronized (combinedLock) {
                String peerKey = peer.toString();
                this.pendingPeerTries.putIfAbsent(peerKey, 0);
                //LOGGER.info("Unique peer count is: {}", pendingPeerTries.size());

                // 1. Get the current list for this peer, or create a new one
                List<ArbitraryFileListResponseInfo> currentList = pendingPeerAndChunks
                        .computeIfAbsent(peerKey, k -> new ArrayList<>());

                // 2. Check if the hash58 is already in the list
                boolean isDuplicate = currentList.stream()
                        .anyMatch(existingAflri -> aflri.getHash58().equals(existingAflri.getHash58()));

                // 3. Only add if it is not a duplicate
                if (!isDuplicate) {
                    currentList.add(aflri);
                    LOGGER.trace("ADDED unique AFLRI with hash {} for peer {}", aflri.getHash58(), peerKey);
                } else {
                    LOGGER.trace("SKIPPED adding duplicate AFLRI with hash {} for peer {}", aflri.getHash58(), peerKey);
                }

            } // End of synchronized block
        }



        void setConnecting(String peer, boolean v) {
            synchronized (combinedLock) {
                if (v) {
                    isConnectingPeers.add(peer);
                }
                else {
                    try {
                        isConnectingPeers.remove(peer);
                    } catch (Exception ignored) {

                    }
                }
            }
        }

        boolean isConnecting(String peer) {
            return isConnectingPeers.contains(peer);
        }
    }
}
