package org.qortal.controller.arbitrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.NetworkData;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.network.PeerList;
import org.qortal.network.PeerSendManagement;
import org.qortal.network.PeerSendManager;
import org.qortal.network.message.GetArbitraryDataFileMessage;
import org.qortal.network.message.MessageException;
import org.qortal.network.message.MessageType;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

public class ArbitraryDataFileRequestThread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileRequestThread.class);

    // Batching configuration
    private static final int MAX_BATCH_SIZE = 40;        // Maximum chunks per batch
    private static final long BATCH_INTERVAL_MS = 2000L;  // Interval between batches
    private static final long STALE_BATCH_TIMEOUT_MS = 300000L; // 5 minutes - remove batches that haven't completed

    // Inner class to track pending chunks with their available peers
    // Store PeerData instead of Peer to prevent memory leaks from stale Peer objects
    private static class PendingChunk {
        final ArbitraryFileListResponseInfo responseInfo;
        final List<PeerData> availablePeers;  // Store lightweight PeerData instead of heavy Peer objects

        PendingChunk(ArbitraryFileListResponseInfo responseInfo) {
            this.responseInfo = responseInfo;
            this.availablePeers = new ArrayList<>();
            // Use getPeerData() directly instead of getPeer().getPeerData() to avoid null pointer
            this.availablePeers.add(responseInfo.getPeerData());
        }

        synchronized void addPeer(Peer peer) {
            PeerData peerData = peer.getPeerData();
            // Check if peer is already in the list (by PeerData)
            boolean alreadyAdded = availablePeers.stream()
                    .anyMatch(pd -> pd.equals(peerData));
            if (!alreadyAdded) {
                availablePeers.add(peerData);
            }
        }

        synchronized List<PeerData> getAvailablePeerData() {
            return new ArrayList<>(availablePeers);
        }

        // Resolve Peer objects on-demand from connected peers
        // Filters out disconnected peers automatically and removes them from the list to prevent memory leaks
        synchronized List<Peer> getAvailablePeers(PeerList connectedPeers) {
            // Remove disconnected peers from the list to prevent memory accumulation
            availablePeers.removeIf(peerData -> connectedPeers.get(peerData) == null);
            
            // Return list of connected peers
            return availablePeers.stream()
                    .map(peerData -> connectedPeers.get(peerData))
                    .filter(peer -> peer != null)  // Defensive check (shouldn't be needed after removeIf)
                    .collect(Collectors.toList());
        }
    }

    // Inner class to track batches per signature
    private static class SignatureBatch {
        final String signature58;
        final long firstSeenTime;
        final Map<String, PendingChunk> pendingChunks = new ConcurrentHashMap<>();
        int peerIndex = 0;  // Persists across batches for fair round-robin distribution
        AtomicBoolean initialBatchSent = new AtomicBoolean(false);  // Track if initial batch has been sent
        volatile ArbitraryTransactionData transactionData = null;  // Cached transaction data to avoid repeated DB fetches
        volatile long lastUpdatedTime;  // Track when chunks were last added/modified

        SignatureBatch(String signature58, long firstSeenTime) {
            this.signature58 = signature58;
            this.firstSeenTime = firstSeenTime;
            this.lastUpdatedTime = firstSeenTime;  // Initialize to creation time
        }
    }

    // Map to track batches by signature
    private final ConcurrentHashMap<String, SignatureBatch> signatureBatches = new ConcurrentHashMap<>();

    // Scheduler for batch processing
    private final ScheduledExecutorService batchScheduler = Executors.newScheduledThreadPool(2);

    // Thread-safe singleton using eager initialization
    // Static final fields are initialized during class loading, which is thread-safe
    private static final ArbitraryDataFileRequestThread INSTANCE = new ArbitraryDataFileRequestThread();

    private ArbitraryDataFileRequestThread() {
        // Start single scheduled task to process all batches every 2 seconds
        // Use scheduleWithFixedDelay to prevent overlapping executions if processing takes longer than interval
        batchScheduler.scheduleWithFixedDelay(this::processAllBatches, 1000, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public static ArbitraryDataFileRequestThread getInstance() {
        return INSTANCE;
    }

    /**
     * Called when a chunk is successfully received and saved. Removes the chunk from the batch's
     * pending set so it is no longer retried. Called from ArbitraryDataFileManager.receivedArbitraryDataFile.
     */
    public void onChunkReceived(String signature58, String hash58) {
        SignatureBatch batch = signatureBatches.get(signature58);
        if (batch != null) {
            batch.pendingChunks.remove(hash58);
        }
    }

    public void processFileHashes(Long now, List<ArbitraryFileListResponseInfo> responseInfos, ArbitraryDataFileManager arbitraryDataFileManager) throws InterruptedException, MessageException {
        if (Controller.isStopping()) {
            shutdownFileFetcherPool();
            return;
        }

        Map<String, byte[]> signatureBySignature58 = new HashMap<>(responseInfos.size());
        Map<String, List<ArbitraryFileListResponseInfo>> responseInfoBySignature58 = new HashMap<>();

        PeerList completeConnectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();

        // Remove any pending direct connects that have exceeded the timeout, increment others
        for (Map.Entry<String, Integer> peerTimeLapse : arbitraryDataFileManager.getPeerTimeOuts().entrySet()) {
            // ... (Peer Timeout Logic) ...
            String peer = peerTimeLapse.getKey();
            Integer elapsedSeconds = peerTimeLapse.getValue();

            if (elapsedSeconds > 12 ) {  // stale, drop list and counter
                arbitraryDataFileManager.removePeerTimeOut(peer);
                LOGGER.debug("Removing Peer: {} for time out greater than 8", peer);
            }
        }

        // Add +1 to all peers pending connection
        arbitraryDataFileManager.incrementTimeOuts();

        // If we have some held hashes waiting for a peer connection
        LOGGER.trace("Do we have pending chunks?: {}", arbitraryDataFileManager.pendingPeersAndChunks());
        if (arbitraryDataFileManager.pendingPeersAndChunks()) {
            for (Map.Entry<String, List<ArbitraryFileListResponseInfo>> peerWithInfos: arbitraryDataFileManager.getPendingPeerAndChunks().entrySet()) {

                String peerString = peerWithInfos.getKey();
                PeerAddress peerAddress = new PeerAddress(peerString);
                // We need to check by IP/Host here, and then put in the proper peer object

                LOGGER.trace("We are going to look for: {}",peerString);
                Peer connectedPeer = NetworkData.getInstance().getPeerByPeerAddress( peerAddress );

                if (connectedPeer == null)
                    LOGGER.warn("connectedPeer is null, not connected");
                //if (connectedPeer != null && completeConnectedPeers.contains(connectedPeer)) {            // If the peer is now connected
                if (connectedPeer != null ) {            // If the peer is now connected
                    LOGGER.trace("We are adding responseInfos from the queue");
                    responseInfos.addAll(peerWithInfos.getValue());     // add all responseInfos for this peer to the list
                    arbitraryDataFileManager.removePeerTimeOut(peerString);
                    arbitraryDataFileManager.setIsConnecting(peerString, false);
                }
            }
        }

        if (responseInfos.isEmpty())
            return;

        for( ArbitraryFileListResponseInfo responseInfo : responseInfos) {

            if( responseInfo == null ) continue;

            if (Controller.isStopping()) {
                return;
            }

            Boolean isDirectlyConnectable = responseInfo.isDirectConnectable();
            LOGGER.trace("Is Directly Connectable: {}", isDirectlyConnectable);

            // Direct: we're connected to the content holder — resolve by nodeId.
            // Relay: we're connected to the sender (relay), not the content holder — resolve by PeerData only.
            Peer connectedPeer = null;
            if (Boolean.TRUE.equals(isDirectlyConnectable)) {
                String nodeId = responseInfo.getNodeId();
                if (nodeId != null) {
                    connectedPeer = NetworkData.getInstance().getImmutableConnectedPeers().stream()
                        .filter(peerEa ->
                            peerEa.getPeersNodeId() != null &&
                            peerEa.getPeersNodeId().equals(nodeId))
                        .findFirst()
                        .orElse(null);
                }
            } else {
                PeerData peerData = responseInfo.getPeerData();
                if (peerData != null) {
                    connectedPeer = NetworkData.getInstance().getPeerByPeerData(peerData);
                    if (connectedPeer != null) {
                        LOGGER.trace("Relay: resolved peer by PeerData: {}", peerData.getAddress());
                    }
                }
            }

            if (Boolean.TRUE.equals(isDirectlyConnectable)) {
                if (connectedPeer == null) {
                    // Peer not connected - create Peer from PeerData if needed for pending/connect
                    Peer peer;
                    PeerData peerData = responseInfo.getPeerData();
                    if (peerData != null) {
                        peer = new Peer(peerData, Peer.NETWORKDATA);
                    } else {
                        LOGGER.warn("Cannot create Peer: PeerData is null for responseInfo with hash {}", responseInfo.getHash58());
                        continue;
                    }
                    arbitraryDataFileManager.addResponseToPending(peer, responseInfo);
                    if (!arbitraryDataFileManager.getIsConnectingPeer(peer.toString())) {
                        LOGGER.trace("Forcing Connect for QDN to: {}", peer);
                        arbitraryDataFileManager.setIsConnecting(peer.toString(), true);
                        NetworkData.getInstance().forceConnectPeer(peer);
                        Thread.sleep(50);
                    }
                    continue;
                }
                if (now - responseInfo.getTimestamp() >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT || responseInfo.getSignature58() == null) {
                    LOGGER.trace("TIMED OUT in ArbitraryDataFileRequestThread");
                    continue;
                }
            }

            // Skip if already requesting, but don't remove, as we might want to retry later
            if (arbitraryDataFileManager.arbitraryDataFileRequests.containsKey(responseInfo.getHash58())) {
                continue;
            }

            byte[] hash = Base58.decode(responseInfo.getHash58());
            byte[] signature = Base58.decode(responseInfo.getSignature58());

            // We resolve by nodeId only; nodeId is always present, so we need connectedPeer to fetch
            if (signature == null || hash == null || connectedPeer == null) {
                LOGGER.trace("Signature was null or hash was null or no connected peer for fetch (by nodeId)");
                continue;
            }

            // Store responseInfo that carries the nodeId-resolved peer so getPeer/getAvailablePeers use the same connection
            ArbitraryFileListResponseInfo infoToAdd = new ArbitraryFileListResponseInfo(
                    responseInfo.getHash58(),
                    responseInfo.getSignature58(),
                    connectedPeer,
                    responseInfo.getNodeId(),
                    responseInfo.getTimestamp(),
                    responseInfo.getRequestTime(),
                    responseInfo.getRequestHops(),
                    responseInfo.isDirectConnectable());

            // We want to process this file, store and map data to process later
            signatureBySignature58.put(responseInfo.getSignature58(), signature);
            responseInfoBySignature58 // Can contain different peers
                    .computeIfAbsent(responseInfo.getSignature58(), signature58 -> new ArrayList<>())
                    .add(infoToAdd);
        }

        // if there are no signatures, then there is nothing to process and nothing query the database
        if( signatureBySignature58.isEmpty() ) return;

        List<ArbitraryTransactionData> arbitraryTransactionDataList = new ArrayList<>();

        // Fetch the transaction data
        try (final Repository repository = RepositoryManager.getRepository()) {
            arbitraryTransactionDataList.addAll(
                    ArbitraryTransactionUtils.fetchTransactionDataList(repository, new ArrayList<>(signatureBySignature58.values())));
        } catch (DataException e) {
            LOGGER.warn("Unable to fetch transaction data from DB: {}", e.getMessage());
        }

        if( !arbitraryTransactionDataList.isEmpty() ) {
            LOGGER.trace("List of files is not empty, adding to batch system");

            // PHASE 2: Send immediate metadata requests before batching
            // OPTIMIZATION: Metadata is ALWAYS the first element in response lists that contain it
            // (see ArbitraryDataFileListManager line 876 and shuffle exclusion line 963)
            for(ArbitraryTransactionData data : arbitraryTransactionDataList) {
                byte[] metadataHash = data.getMetadataHash();
                
                if (metadataHash == null) {
                    continue; // No metadata for this resource
                }
                
                String signature58 = Base58.encode(data.getSignature());
                String metadataHash58 = Base58.encode(metadataHash);
                // Cache metadata hash so receivedArbitraryDataFile can skip DB fetch for non-metadata chunks
                arbitraryDataFileManager.setMetadataHashForSignature(data.getSignature(), metadataHash);
                List<ArbitraryFileListResponseInfo> responseInfoList = responseInfoBySignature58.get(signature58);
                
                if (responseInfoList == null || responseInfoList.isEmpty()) {
                    continue;
                }
                
                // Check if first element is metadata (it should be if peer has it)
                ArbitraryFileListResponseInfo firstResponse = responseInfoList.get(0);
                
                // If first element isn't metadata, peer probably doesn't have it - skip
                if (!metadataHash58.equals(firstResponse.getHash58())) {
                    LOGGER.trace("First hash for {} is not metadata - peer may not have metadata yet", signature58);
                    continue; // Don't process this response list for immediate metadata
                }
                
                // First element IS metadata - process immediately
                try {
                    // Check if metadata already exists on disk
                    ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(metadataHash, data.getSignature());
                    
                    if (!metadataFile.exists()) {
                        // Metadata doesn't exist - check if we're already requesting it
                        if (!arbitraryDataFileManager.arbitraryDataFileRequests.containsKey(metadataHash58)) {
                            // Resolve peer from connected peers
                            Peer peer = firstResponse.getPeer(completeConnectedPeers);
                            if (peer == null) {
                                peer = completeConnectedPeers.get(firstResponse.getPeerData());
                            }
                            
                            if (peer != null) {
                                // Mark as requesting atomically
                                Long prev = arbitraryDataFileManager.arbitraryDataFileRequests.putIfAbsent(
                                    metadataHash58, NTP.getTime());
                                
                                if (prev == null) {
                                    // We won the race - send request NOW
                                    arbitraryDataFileManager.addGuardTracking(metadataHash58);
                                    
                                    try {
                                        GetArbitraryDataFileMessage message = new GetArbitraryDataFileMessage(
                                            data.getSignature(), metadataHash);
                                        // Queue message through PeerSendManager (same as batch system)
                                        PeerSendManagement.getInstance().getOrCreateSendManager(peer, true)
                                            .queueMessage(message, metadataHash58);
                                    } catch (MessageException e) {
                                        LOGGER.error("Failed to queue immediate metadata request for hash {}: {}", 
                                            metadataHash58, e.getMessage());
                                        // Remove from request tracking since we failed to send
                                        arbitraryDataFileManager.arbitraryDataFileRequests.remove(metadataHash58);
                                        arbitraryDataFileManager.removeGuardTracking(metadataHash58);
                                    }
                                }
                            }
                        }
                    }
                    
                    // Remove metadata from response list so it's not added to batch
                    responseInfoList.remove(0);
                    
                } catch (DataException e) {
                    LOGGER.warn("Error checking/requesting metadata file for {}: {}", signature58, e.getMessage());
                    // Continue anyway - let it go through normal batching if immediate send failed
                }
            }

            // Instead of sending immediately, add chunks to batching system
            for(ArbitraryTransactionData data : arbitraryTransactionDataList ) {  // a file
                String signature58 = Base58.encode(data.getSignature());
                List<ArbitraryFileListResponseInfo> responseInfoList = responseInfoBySignature58.get(signature58);
                
                if (responseInfoList == null || responseInfoList.isEmpty()) {
                    continue;
                }

                // Get or create batch for this signature
                SignatureBatch batch = signatureBatches.computeIfAbsent(signature58, sig -> {
                    SignatureBatch newBatch = new SignatureBatch(sig, now);
                    LOGGER.trace("Created new batch for signature {}", sig);
                    return newBatch;
                });
                
                // Cache transaction data if we have it and batch doesn't have it yet
                if (batch.transactionData == null && data != null) {
                    batch.transactionData = data;
                }

                // Track if this is a newly created batch (pendingChunks will be empty if so)
                boolean isNewBatch = batch.pendingChunks.isEmpty();

                // Add chunks to batch, tracking peer availability
                for( ArbitraryFileListResponseInfo responseInfo : responseInfoList) {
                    // Resolve Peer from connected peers using stored PeerData
                    Peer peer = responseInfo.getPeer(completeConnectedPeers);
                    if (peer == null) {
                        // Try via PeerData directly
                        peer = completeConnectedPeers.get(responseInfo.getPeerData());
                    }
                    LOGGER.trace("ResponseInfo peer: {}", peer);

                    // Use the PeerList snapshot for a host/IP-only lookup to ensure we have
                    // the *current* connected Peer object for messaging.
                    Peer connectedPeer = peer;

                    if (connectedPeer == null) {
                        // Peer is no longer connected/handshaked in the snapshot. Skip processing this request.
                        LOGGER.trace("Peer {} for hash {} is no longer handshaked/connected. Skipping.", responseInfo.getPeerData(), responseInfo.getHash58());
                        continue;
                    }

                    // Update the 'peer' variable to the connected one
                    peer = connectedPeer;
                    LOGGER.trace("We set peer to: {}", peer);
                    
                    // Store as final for lambda usage
                    final Peer finalPeer = peer;

                    String fileHash = responseInfo.getHash58();
                    
                    // IMPORTANT: Check if hash is already in batch FIRST - this allows adding multiple peers
                    // to chunks that are waiting to be sent, even if they're also being requested
                    if (batch.pendingChunks.containsKey(fileHash)) {
                        // Hash is already in batch - add this peer as available source, but don't process it again
                        PendingChunk existingChunk = batch.pendingChunks.get(fileHash);
                        existingChunk.addPeer(finalPeer);
                        batch.lastUpdatedTime = System.currentTimeMillis();  // Update timestamp
                     
                        continue; // Skip - don't let it get sent again
                    }
                    
                    // Skip if already requesting (not in batch, but already sent/queued from a previous batch)
                    if (arbitraryDataFileManager.arbitraryDataFileRequests.containsKey(fileHash)) {
                        LOGGER.trace("Already requesting hash {} from another peer, skipping peer {}", fileHash, peer);
                        continue;
                    }

                    // Skip if file already exists on disk - no need to request it
                    try {
                        byte[] fileHashBytes = Base58.decode(fileHash);
                        ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(fileHashBytes, data.getSignature());
                        if (existingFile.exists()) {
                            LOGGER.trace("Skipping hash {} - file already exists on disk", fileHash);
                            continue;
                        }
                        
                        // Check relay cache before network request
                        byte[] cachedChunk = arbitraryDataFileManager.loadFromRelayCache(fileHash);
                        if (cachedChunk != null) {
                            LOGGER.debug("Hash {} found in relay cache, skipping network request (cache hit!)", fileHash);
                            // Save from cache to permanent storage
                            try {
                                ArbitraryDataFile cachedFile = new ArbitraryDataFile(cachedChunk, data.getSignature(), false);
                                if (cachedFile.validateHash(fileHashBytes)) {
                                    cachedFile.save();
                                    LOGGER.trace("Saved chunk {} from relay cache to permanent storage", fileHash);
                                    continue; // Skip adding to batch
                                } else {
                                    LOGGER.warn("Cached chunk {} failed hash validation, removing from cache and will re-request", fileHash);
                                    // Let it fall through to network request
                                }
                            } catch (Exception e) {
                                LOGGER.warn("Failed to save chunk {} from relay cache: {}", fileHash, e.getMessage());
                                // Fall through to network request
                            }
                        }
                        
                    } catch (DataException e) {
                        LOGGER.warn("Error checking if file exists for hash {}: {}", fileHash, e.getMessage());
                        // Continue anyway - better to request than to skip on error
                    }

                    // Hash is NOT in batch yet - add it normally
                    // Add chunk to batch with peer availability info
                    PendingChunk pendingChunk = batch.pendingChunks.computeIfAbsent(fileHash, h -> {
                        // Update timestamp when adding new chunk
                        batch.lastUpdatedTime = System.currentTimeMillis();
                        
                        // Create a new pending chunk with updated peer reference
                        ArbitraryFileListResponseInfo updatedInfo = new ArbitraryFileListResponseInfo(
                            responseInfo.getHash58(),
                            responseInfo.getSignature58(),
                            finalPeer,  // Use the final connected peer
                            responseInfo.getNodeId(),
                            responseInfo.getTimestamp(),
                            responseInfo.getRequestTime(),
                            responseInfo.getRequestHops(),
                            responseInfo.isDirectConnectable()
                        );
                        return new PendingChunk(updatedInfo);
                    });
                    
                    // Add this peer as an available source for this chunk
                    pendingChunk.addPeer(finalPeer);
                    
                    LOGGER.trace("[PEER_ADD_NEW] Added hash {} to batch for signature {} with peer {} (new chunk)", fileHash, signature58, peer);
                }

                // If this is a new batch, send initial batch immediately
                // The global scheduler will handle incremental batches
                // Only send initial batch if batch was empty when we started (isNewBatch)
                // This ensures the initial batch represents a coherent first wave
                if (isNewBatch && batch.initialBatchSent.compareAndSet(false, true)) {
                    if (!batch.pendingChunks.isEmpty()) {
                        LOGGER.trace("Sending initial batch for signature {} with {} chunks", signature58, batch.pendingChunks.size());
                        sendBatchForSignature(batch, MAX_BATCH_SIZE, arbitraryDataFileManager, true);
                    } else {
                        // If no chunks yet, reset the flag so it can be sent later
                        batch.initialBatchSent.set(false);
                    }
                }

              
            }

        }
    }

    public void shutdownFileFetcherPool() {
        // Shutdown batch scheduler
        batchScheduler.shutdown();
        try {
            if (!batchScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                batchScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get unique peer count for a file request by signature.
     * @param signature58 Base58-encoded transaction signature
     * @return Number of unique peers available, or null if no active batch
     */
    public Integer getPeerCountForSignature(String signature58) {
        SignatureBatch batch = signatureBatches.get(signature58);
        if (batch == null) {
            return null; // No active batch for this signature
        }
        
        PeerList connectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();
        
        // Count unique peers across all chunks
        Map<PeerData, PeerData> uniquePeers = new HashMap<>();
        
        for (PendingChunk chunk : batch.pendingChunks.values()) {
            List<Peer> availablePeers = chunk.getAvailablePeers(connectedPeers);
            for (Peer peer : availablePeers) {
                uniquePeers.put(peer.getPeerData(), peer.getPeerData());
            }
        }
        
        return uniquePeers.size();
    }

    /**
     * Helper class to store peer details during processing.
     */
    public static class PeerDetails {
        public final Peer peer;
        public final boolean isDirect;
        
        public PeerDetails(Peer peer, boolean isDirect) {
            this.peer = peer;
            this.isDirect = isDirect;
        }
    }

    /**
     * Get detailed peer information for a file request by signature.
     * @param signature58 Base58-encoded transaction signature
     * @return Map of unique peers with their details, or null if no active batch
     */
    public Map<PeerData, PeerDetails> getDetailedPeersForSignature(String signature58) {
        SignatureBatch batch = signatureBatches.get(signature58);
        if (batch == null) {
            return null; // No active batch for this signature
        }
        
        PeerList connectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();
        
        // Track unique peers with their details
        Map<PeerData, PeerDetails> peerDetailsMap = new HashMap<>();
        
        for (PendingChunk chunk : batch.pendingChunks.values()) {
            List<Peer> availablePeers = chunk.getAvailablePeers(connectedPeers);
            
            // Get isDirect from the chunk's responseInfo
            boolean isDirect = chunk.responseInfo.isDirectConnectable() != null 
                && chunk.responseInfo.isDirectConnectable();
            Integer requestHops = chunk.responseInfo.getRequestHops();
            if (requestHops != null && requestHops == 0) {
                isDirect = true;
            }
            
            // Make isDirect effectively final for lambda
            final boolean isDirectFinal = isDirect;
            
            for (Peer peer : availablePeers) {
                PeerData peerData = peer.getPeerData();
                
                // Make peer effectively final for lambda
                final Peer finalPeer = peer;
                
                // Only add if not already present (or merge isDirect status)
                peerDetailsMap.compute(peerData, (key, existing) -> {
                    if (existing != null) {
                        // Merge: if ANY chunk is direct, mark as direct
                        return new PeerDetails(finalPeer, existing.isDirect || isDirectFinal);
                    } else {
                        return new PeerDetails(finalPeer, isDirectFinal);
                    }
                });
            }
        }
        
        return peerDetailsMap;
    }

    /**
     * Process all active batches. Called by scheduler every 2 seconds.
     * Sends incremental batches and removes batches when all chunks are requested.
     */
    private void processAllBatches() {
        try {
            Long now = NTP.getTime();
            if (now == null) {
                LOGGER.warn("NTP time is null, skipping batch processing");
                return;
            }

            // Process each active batch
            Iterator<Map.Entry<String, SignatureBatch>> iterator = signatureBatches.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, SignatureBatch> entry = iterator.next();
                SignatureBatch batch = entry.getValue();
                
                long elapsed = now - batch.firstSeenTime;
                
                // Remove stale batches that have been around too long (prevents memory leak from stuck chunks)
                if (elapsed > STALE_BATCH_TIMEOUT_MS) {
                    int remainingChunks = batch.pendingChunks.size();
                    ArbitraryDataFileManager.getInstance().clearTriedPeersForSignature(batch.signature58);
                    iterator.remove();
                    LOGGER.warn("Removed stale batch for signature {} (age: {}s, {} chunks remaining)", 
                                batch.signature58, elapsed / 1000, remainingChunks);
                    continue;
                }
                
                // Count remaining chunks (chunks removed on receive via onChunkReceived, or when no peer available)
                int remainingChunks = batch.pendingChunks.size();
                
                LOGGER.trace("Signature {}: {} chunks remaining to request (elapsed: {}s)", 
                            batch.signature58, remainingChunks, elapsed / 1000);
                
                if (remainingChunks == 0) {
                    // Only remove if batch has been idle for at least 5 seconds
                    // This prevents race condition where chunks are added right after we check
                    long idleTime = now - batch.lastUpdatedTime;
                    if (idleTime > 5000) {  // 5 seconds
                        ArbitraryDataFileManager.getInstance().clearTriedPeersForSignature(batch.signature58);
                        iterator.remove();
                        LOGGER.trace("Removed completed batch for signature {} (all chunks requested, idle for {}ms)", 
                                    batch.signature58, idleTime);
                    } else {
                        LOGGER.trace("Batch for signature {} is empty but not idle yet (idle: {}ms), keeping for next check", 
                                     batch.signature58, idleTime);
                    }
                } else {
                    // Send incremental batch (normal operation)
                    sendBatchForSignature(batch, MAX_BATCH_SIZE, ArbitraryDataFileManager.getInstance(), false);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error in batch processing: {}", e.getMessage(), e);
        }
    }

    /**
     * Selects a peer for a chunk using tier-based load balancing with capacity-proportional distribution.
     * 
     * Selection strategy:
     * 1. Categorize peers by tier (based on hop count):
     *    - Tier 1: Hops 0-1 (BEST - directly connected)
     *    - Tier 2: Hops 2-3 (MEDIUM)
     *    - Tier 3: Hops 4+ (WORST)
     * 
     * 2. Always select from best available tier (quality-first prioritization)
     * 
     * 3. Within tier, sort by CAPACITY-WEIGHTED load (not raw load):
     *    - Fast peers can handle more chunks before being deprioritized
     *    - Capacity = 1000.0 / RTT (chunks per second)
     *    - Weighted load = currentLoad / capacity (normalized workload)
     *    - Then RTT, queue size as tie-breakers
     * 
     * 4. No artificial per-batch load limits - only real queue capacity matters
     *    (checked later in sendBatchForSignature using RTT-aware calculation)
     * 
     * This ensures:
     * - Fast peers get proportionally more work than slow peers
     * - Better quality peers always preferred over worse ones
     * - Slow peers aren't excluded, just get less work
     * - Load is balanced proportionally within each quality tier
     * 
     * @param chunk the chunk to assign
     * @param peerSnapshots map of chunks to their available peers
     * @param availablePeersMap map of PeerData to connected Peer objects
     * @param chunksByPeer current assignment of chunks to peers (for load tracking)
     * @param signature58 the file signature (for cooldown checking)
     * @param adfm the ArbitraryDataFileManager instance
     * @param now current timestamp (unused - kept for compatibility)
     * @param staleThreshold threshold for "recent" data (unused - kept for compatibility)
     * @return selected peer, or null if no peer available
     */
    private Peer selectPeerWithLoadBalancing(
            PendingChunk chunk,
            Map<PendingChunk, List<Peer>> peerSnapshots,
            Map<PeerData, Peer> availablePeersMap,
            Map<Peer, List<PendingChunk>> chunksByPeer,
            String signature58,
            ArbitraryDataFileManager adfm,
            Long now,
            long staleThreshold) {
        
        // No artificial per-batch load limits - only real queue capacity matters
        // Tiers are used for quality-based prioritization, not capacity limiting
        
        // Get chunk's hop count to determine preferred quality
        Integer chunkHops = chunk.responseInfo.getRequestHops();
        int hopCount = (chunkHops != null) ? chunkHops : 100; // Treat null as high hops
        
        // Collect all available peers for this chunk with their tier and metrics
        List<PeerCandidate> tier1 = new ArrayList<>(); // Low hops (0-1)
        List<PeerCandidate> tier2 = new ArrayList<>(); // Medium hops (2-3)
        List<PeerCandidate> tier3 = new ArrayList<>(); // High hops (4+)
        
        Set<String> triedPeers = adfm.getTriedPeersForChunk(signature58, chunk.responseInfo.getHash58());
        for (Peer peer : peerSnapshots.get(chunk)) {
            Peer connectedPeer = availablePeersMap.get(peer.getPeerData());
            if (connectedPeer == null) {
                continue; // Skip disconnected peers
            }
            
            String peerAddress = connectedPeer.getPeerData().getAddress().toString();
            if (triedPeers.contains(peerAddress)) {
                continue; // Already tried this peer for this chunk (timeout or in-flight); retry from another peer
            }
            
            // Check if this peer is in cooldown for this file (sent invalid data for ANY chunk of this file before)
            if (adfm.isSignaturePeerInCooldown(signature58, peerAddress)) {
                LOGGER.debug("Skipping peer {} for file {} - in 10min cooldown due to previous hash mismatch on this file", 
                    peerAddress, signature58);
                continue; // Skip this peer - they sent invalid data for this file before
            }
            
            // Factor 1: Current load (chunks assigned in this batch)
            int currentLoad = chunksByPeer.getOrDefault(connectedPeer, Collections.emptyList()).size();
            
            // Factor 2: Queue size (number of queued messages)
            PeerSendManager sendManager = PeerSendManagement.getInstance().getOrCreateSendManager(connectedPeer, true);
            int queueSize = sendManager.getQueueMessageSize() + connectedPeer.getSendQueueSize();
            
            // Factor 3: Round trip time (lower is better)
            Long transferTime = connectedPeer.getDownloadSpeedTracker().getLatestRoundTripTime();
            long roundTripTime = (transferTime != null && transferTime > 0) ? transferTime : Long.MAX_VALUE;
            
            // Skip peers with RTT > 10 seconds - they're too slow and would block download progress
            // Peer will automatically get another chance after 10 seconds of inactivity (RTT reset)
            final long MAX_USABLE_RTT_MS = 10_000L; // 10 seconds
            if (roundTripTime != Long.MAX_VALUE && roundTripTime > MAX_USABLE_RTT_MS) {
                LOGGER.debug("Skipping peer {} for chunk {} - RTT {}ms exceeds maximum usable threshold of {}ms (will retry after idle reset)",
                    connectedPeer, chunk.responseInfo.getHash58(), roundTripTime, MAX_USABLE_RTT_MS);
                continue; // Skip this peer temporarily
            }
            
            // Create candidate
            PeerCandidate candidate = new PeerCandidate(
                connectedPeer, 
                currentLoad, 
                hopCount,
                queueSize,
                roundTripTime
            );
            
            // Categorize by tier based on hop count - NO LOAD LIMITS
            // All peers in a tier are eligible regardless of load
            // Real queue capacity check happens later in sendBatchForSignature()
            if (hopCount <= 1) {
                // Tier 1: Low hops (0-1) - BEST quality
                tier1.add(candidate);
            } else if (hopCount <= 3) {
                // Tier 2: Medium hops (2-3) - MEDIUM quality
                tier2.add(candidate);
            } else {
                // Tier 3: High hops (4+) - WORST quality
                tier3.add(candidate);
            }
        }
        
       
        
        // Select from best available tier (quality-based prioritization)
        List<PeerCandidate> selectedTier = null;
        String tierName = "";
        if (!tier1.isEmpty()) {
            selectedTier = tier1;
            tierName = "Tier1";
        } else if (!tier2.isEmpty()) {
            selectedTier = tier2;
            tierName = "Tier2";
        } else if (!tier3.isEmpty()) {
            selectedTier = tier3;
            tierName = "Tier3";
        } else {
            // No peers available for this chunk
            return null;
        }
        
        // Sort within tier by: capacity-weighted load, RTT, queue size, hop count
        // CAPACITY-PROPORTIONAL: Fast peers can accumulate more chunks before being deprioritized
        // Weighted load = currentLoad / capacity, where capacity = 1000.0 / RTT (chunks/sec)
        selectedTier.sort((c1, c2) -> {
            // 1. Calculate capacity-weighted load (normalized workload)
            // Capacity = 1000 / RTT (chunks per second)
            // Weighted load = currentLoad / capacity (how "full" the peer is relative to its speed)
            // For peers with no RTT data (0 or Long.MAX_VALUE), assume a default RTT of 5000ms
            // This gives them a fair chance to establish their actual RTT instead of being permanently penalized
            final long DEFAULT_RTT_MS = 5000L;
            
            long effectiveRTT1 = (c1.roundTripTime != Long.MAX_VALUE && c1.roundTripTime > 0)
                ? c1.roundTripTime : DEFAULT_RTT_MS;
            long effectiveRTT2 = (c2.roundTripTime != Long.MAX_VALUE && c2.roundTripTime > 0)
                ? c2.roundTripTime : DEFAULT_RTT_MS;
            
            double capacity1 = 1000.0 / effectiveRTT1;
            double capacity2 = 1000.0 / effectiveRTT2;
            
            double weightedLoad1 = c1.currentLoad / capacity1;
            double weightedLoad2 = c2.currentLoad / capacity2;
            
            // Lower weighted load wins (peer with more relative capacity available)
            // This means fast peers naturally get more chunks before being deprioritized
            // Direct comparison without epsilon - let Double.compare handle precision
            int weightedLoadComparison = Double.compare(weightedLoad1, weightedLoad2);
            if (weightedLoadComparison != 0) {
                return weightedLoadComparison;
            }
            
            // 2. Lower round trip time wins (tie-breaker when weighted loads are exactly equal)
            //    This handles the case where both peers have 0 load and ensures fast peers are preferred
            if (c1.roundTripTime != c2.roundTripTime) {
                return Long.compare(c1.roundTripTime, c2.roundTripTime);
            }
            
            // 3. Lower queue size wins (tie-breaker for same weighted load and RTT)
            if (c1.queueSize != c2.queueSize) {
                return Integer.compare(c1.queueSize, c2.queueSize);
            }
            
            // 4. Lower hop count wins (final tie-breaker within tier)
            if (c1.hopCount != c2.hopCount) {
                return Integer.compare(c1.hopCount, c2.hopCount);
            }
            
            // 5. Consistent ordering by address
            return c1.peer.getPeerData().getAddress().toString()
                    .compareTo(c2.peer.getPeerData().getAddress().toString());
        });
        
        Peer selectedPeer = selectedTier.get(0).peer;
        
        return selectedPeer;
    }
    
    /**
     * Helper class to track peer candidates for selection.
     */
    private static class PeerCandidate {
        final Peer peer;
        final int currentLoad;
        final int hopCount;
        final int queueSize;
        final long roundTripTime; // Round trip time in ms (lower is better)
        
        PeerCandidate(Peer peer, int currentLoad, int hopCount, int queueSize, long roundTripTime) {
            this.peer = peer;
            this.currentLoad = currentLoad;
            this.hopCount = hopCount;
            this.queueSize = queueSize;
            this.roundTripTime = roundTripTime;
        }
    }

    /**
     * Send a batch of chunks for a signature, distributing across available peers.
     * Uses adaptive batch sizing based on peer RTT to prevent queue buildup.
     * Each peer gets up to maxChunks of DIFFERENT chunks (no duplicates).
     * Total sent can be maxChunks * numPeers.
     * For initial batch, prioritizes peers by hop count (hop 0 = best, directly connected).
     * 
     * @param batch the signature batch to process
     * @param requestedMaxChunks requested maximum number of chunks to send per peer (may be reduced adaptively)
     * @param adfm the ArbitraryDataFileManager instance
     * @param isInitialBatch true if this is the initial batch (uses hop-priority sorting), false for incremental batches
     */
    private void sendBatchForSignature(SignatureBatch batch, int requestedMaxChunks, ArbitraryDataFileManager adfm, boolean isInitialBatch) {
        // Use cached transaction data, or fetch lazily on first use
        ArbitraryTransactionData transactionData = batch.transactionData;
        if (transactionData == null) {
            // Lazy fetch on first use (in case batch was created before we had the data)
            byte[] signature = Base58.decode(batch.signature58);
            if (signature == null) {
                LOGGER.warn("Could not decode signature {}", batch.signature58);
                return;
            }
            try (final Repository repository = RepositoryManager.getRepository()) {
                List<ArbitraryTransactionData> dataList = ArbitraryTransactionUtils.fetchTransactionDataList(
                        repository, Collections.singletonList(signature));
                if (!dataList.isEmpty()) {
                    transactionData = dataList.get(0);
                    batch.transactionData = transactionData;  // Cache it for future use
                }
            } catch (DataException e) {
                LOGGER.warn("Unable to fetch transaction data for signature {}: {}", batch.signature58, e.getMessage());
                return;
            }
            if (transactionData == null) {
                LOGGER.warn("No transaction data found for signature {}", batch.signature58);
                return;
            }
        }

        // Get current connected peers snapshot
        PeerList connectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();

        // Get all unrequested chunks
        List<PendingChunk> unrequestedChunks = batch.pendingChunks.values().stream()
                .filter(chunk -> !adfm.arbitraryDataFileRequests.containsKey(chunk.responseInfo.getHash58()))
                .collect(Collectors.toList());

        if (unrequestedChunks.isEmpty()) {
            return;
        }

        // Create immutable snapshots once per chunk to avoid repeated allocations
        // This prevents massive allocation pressure from getAvailablePeers() being called in loops
        // Resolve Peer objects on-demand from connectedPeers (filters out disconnected peers)
        Map<PendingChunk, List<Peer>> peerSnapshots = new HashMap<>();
        for (PendingChunk chunk : unrequestedChunks) {
            peerSnapshots.put(chunk, Collections.unmodifiableList(chunk.getAvailablePeers(connectedPeers)));
        }

        LOGGER.trace("Processing {} unrequested chunks for signature {}", unrequestedChunks.size(), batch.signature58);

        // Get unique list of available connected peers
        // Use Map keyed by PeerData for consistent deduplication (Peer.equals/hashCode may be inconsistent)
        Map<PeerData, Peer> availablePeersMap = new HashMap<>();
        for (PendingChunk chunk : unrequestedChunks) {
            for (Peer peer : peerSnapshots.get(chunk)) {
                // Verify peer is still connected
                Peer connectedPeer = connectedPeers.get(peer.getPeerData());
                if (connectedPeer != null) {
                    // Use PeerData as key for consistent deduplication
                    availablePeersMap.put(connectedPeer.getPeerData(), connectedPeer);
                    LOGGER.trace("Peer {} is connected for signature {}", connectedPeer, batch.signature58);
                } else {
                    LOGGER.trace("Peer {} is NOT connected (skipping) for signature {}", peer, batch.signature58);
                }
            }
        }

        if (availablePeersMap.isEmpty()) {
            LOGGER.trace("No connected peers available for signature {}", batch.signature58);
            return;
        }

        // Sort peers for consistent ordering across batches
        List<Peer> availablePeersList = new ArrayList<>(availablePeersMap.values());
        availablePeersList.sort((p1, p2) -> 
            p1.getPeerData().getAddress().toString().compareTo(p2.getPeerData().getAddress().toString())
        );

     

        // Always use MAX_BATCH_SIZE - let per-peer capacity checks throttle individual peers
        // The capacity-proportional load balancing and RTT-aware queue checks provide sufficient protection
        int maxChunks = requestedMaxChunks;

        // isInitialBatch is passed as parameter to correctly identify initial vs incremental batches
        // (The flag batch.initialBatchSent is already set to true before calling this method for initial batches)

        // Get current time and stale threshold for priority system (used by both initial and incremental batches)
        Long now = NTP.getTime();
        long staleThreshold = 5 * 1000; // 5 seconds in milliseconds

        // Distribute chunks across peers
        // Each chunk goes to ONE peer (no duplicates)
        Map<Peer, List<PendingChunk>> chunksByPeer = new HashMap<>();

        if (isInitialBatch) {
            // For initial batch: process all chunks without sorting
            // Hop count is incorporated into tier-based peer selection
            
            for (PendingChunk chunk : unrequestedChunks) {
                Peer selectedPeer = selectPeerWithLoadBalancing(
                    chunk,
                    peerSnapshots,
                    availablePeersMap,
                    chunksByPeer,
                    batch.signature58,
                    adfm,
                    now,
                    staleThreshold
                );
                
                if (selectedPeer != null) {
                    chunksByPeer.computeIfAbsent(selectedPeer, k -> new ArrayList<>()).add(chunk);
                } else {
                    // No peer available (e.g. all tried for this chunk) - remove so we don't retry forever
                    String hash58 = chunk.responseInfo.getHash58();
                    batch.pendingChunks.remove(hash58);
                    adfm.clearChunkReceived(hash58, batch.signature58);
                }
            }
        } else {
            // For subsequent batches: use same priority system as initial batch
            // Prefer peers with recent data (latestArrivalTime <= 5 seconds)
            // If all peers have stale data (> 5 seconds), fall back to hop count
        
            
            for (PendingChunk chunk : unrequestedChunks) {
                Peer selectedPeer = selectPeerWithLoadBalancing(
                    chunk,
                    peerSnapshots,
                    availablePeersMap,
                    chunksByPeer,
                    batch.signature58,
                    adfm,
                    now,
                    staleThreshold
                );
                
                if (selectedPeer != null) {
                    chunksByPeer.computeIfAbsent(selectedPeer, k -> new ArrayList<>()).add(chunk);
                } else {
                    String hash58 = chunk.responseInfo.getHash58();
                    batch.pendingChunks.remove(hash58);
                    adfm.clearChunkReceived(hash58, batch.signature58);
                }
            }
        }

        int totalSent = 0;
        
        // Maximum time we want to queue for each peer (in milliseconds)
        // Prevents overwhelming slow peers with too many pending chunks
        // Balanced to allow fast peers (≤3000ms) full batch, good peers (4000ms) get 30 chunks
        final long MAX_QUEUE_DRAIN_TIME_MS = 120_000L; // 2 minutes of queued work

        // Send up to maxChunks to EACH peer from their assigned list
        for (Map.Entry<Peer, List<PendingChunk>> peerEntry : chunksByPeer.entrySet()) {
            Peer peer = peerEntry.getKey();
            
            // Get RTT for time-based load calculation
            // RTT represents round-trip time per chunk in milliseconds
            Long rtt = peer.getDownloadSpeedTracker().getLatestRoundTripTime();
            long effectiveRTT = (rtt != null && rtt > 0) ? rtt : 5000L; // Default 5s if no data yet
            
            // Check peer's queue capacity using RTT-aware calculation
            // Need to check both PeerSendManager queue and Peer sendQueue since messages flow:
            // Batching → PeerSendManager.queue → Peer.sendQueue → network
            PeerSendManager sendManager = PeerSendManagement.getInstance().getOrCreateSendManager(peer, true);
            int sendManagerQueueSize = sendManager.getQueueMessageSize();
            int peerSendQueueSize = peer.getSendQueueSize();
            int peerSendQueueCapacity = peer.getSendQueueCapacity();
            
            // Total pending messages = messages in PeerSendManager + messages in Peer.sendQueue
            int totalPendingMessages = sendManagerQueueSize + peerSendQueueSize;
            
            // Calculate time-to-drain: how long will current queue take to complete?
            // This is more meaningful than just counting messages
            long queueDrainTimeMs = totalPendingMessages * effectiveRTT;
            
            // Skip peer if queue will take too long to drain (peer is genuinely busy)
            if (queueDrainTimeMs >= MAX_QUEUE_DRAIN_TIME_MS) {
                LOGGER.debug("REQUESTER QUEUE BUSY: peer={}, queue={}, RTT={}ms, drainTime={}s, SKIPPING", 
                    peer, totalPendingMessages, effectiveRTT, queueDrainTimeMs / 1000);
                continue; // Skip this peer, try again next batch
            }
            
            // Calculate how many chunks we can safely add without exceeding drain time threshold
            // This ensures we don't queue more than MAX_QUEUE_DRAIN_TIME_MS worth of work
            long remainingTimeMs = MAX_QUEUE_DRAIN_TIME_MS - queueDrainTimeMs;
            int maxSafeChunks = (int) (remainingTimeMs / effectiveRTT);
            
            // Also respect physical queue capacity as a hard limit
            int maxQueueSize = (int) (peerSendQueueCapacity * 0.8); // 80% safety margin
            int availableQueueSpace = maxQueueSize - totalPendingMessages;
            
            if (availableQueueSpace <= 0) {
                LOGGER.debug("REQUESTER QUEUE FULL: peer={}, PeerSendMgr={}, Peer.sendQueue={}/{}, total={}, capacity={}, SKIPPING", 
                    peer, sendManagerQueueSize, peerSendQueueSize, peerSendQueueCapacity, 
                    totalPendingMessages, peerSendQueueCapacity);
                continue; // Physical queue is full
            }

            // Take minimum of: requested batch size, time-based limit, physical space
            // Minimum of 1 chunk ensures even slow peers can make progress without blocking downloads
            int maxChunksForThisPeer = Math.max(1, Math.min(Math.min(maxChunks, maxSafeChunks), availableQueueSpace));
            
            LOGGER.trace("REQUESTER QUEUE STATUS: peer={}, PeerSendMgr={}, Peer.sendQueue={}/{}, queue={}, RTT={}ms, drainTime={}s, sendingChunks={}", 
                peer, sendManagerQueueSize, peerSendQueueSize, peerSendQueueCapacity, 
                totalPendingMessages, effectiveRTT, queueDrainTimeMs / 1000, maxChunksForThisPeer);
            
            List<PendingChunk> chunksForThisPeer = peerEntry.getValue().stream()
                    .limit(maxChunksForThisPeer)
                    .collect(Collectors.toList());

            int sentToThisPeer = 0;
            for (PendingChunk chunk : chunksForThisPeer) {
                // Re-check queue space before each message (in case it changed)
                PeerSendManager currentSendManager = PeerSendManagement.getInstance().getOrCreateSendManager(peer, true);
                int currentSendManagerQueueSize = currentSendManager.getQueueMessageSize();
                int currentPeerSendQueueSize = peer.getSendQueueSize();
                int currentTotalPending = currentSendManagerQueueSize + currentPeerSendQueueSize;
                
                // Check both time-based and physical capacity
                long currentDrainTime = currentTotalPending * effectiveRTT;
                int currentMaxQueueSize = (int) (peerSendQueueCapacity * 0.8);
                
                if (currentDrainTime >= MAX_QUEUE_DRAIN_TIME_MS || currentTotalPending >= currentMaxQueueSize) {
                    LOGGER.trace("Peer {} queues reached limit (queue: {}, drainTime: {}s, physical: {}/{}), stopping batch send for signature {}", 
                        peer, currentTotalPending, currentDrainTime / 1000, currentTotalPending, currentMaxQueueSize, batch.signature58);
                    break; // Stop sending to this peer
                }
                
                String fileHash = chunk.responseInfo.getHash58();
                
                byte[] fileHashBytes = Base58.decode(fileHash);
                if (fileHashBytes == null) {
                    LOGGER.warn("Could not decode hash {}", fileHash);
                    continue;
                }

                // Atomically mark as requesting - only proceed if we win the race
                // This prevents duplicate requests when multiple threads process the same chunk
                Long prev = adfm.arbitraryDataFileRequests.putIfAbsent(fileHash, NTP.getTime());
                if (prev != null) {
                    // Another thread already marked this as requesting - skip (chunk stays in pending for retry if that request times out)
                    continue;
                }
                
                // Separately add to guard map for delayed validation (separate concern)
                adfm.addGuardTracking(fileHash);

                try {
                    GetArbitraryDataFileMessage message = new GetArbitraryDataFileMessage(
                            transactionData.getSignature(), fileHashBytes);
                    
                    // Record that we're assigning work to this peer
                    // This helps RTT reset work correctly - even if in-flight requests are slow,
                    // the peer can recover after 10s of no new assignments
                    peer.getDownloadSpeedTracker().recordChunkAssigned();
                    
                    // Pass fileHash for tracking in PeerSendManager pipeline
                    PeerSendManagement.getInstance().getOrCreateSendManager(peer, true).queueMessage(message, fileHash);
                    
                    adfm.recordChunkRequested(fileHash, batch.signature58, peer.getPeerData().getAddress().toString());
                    sentToThisPeer++;
                    totalSent++;
                    // Chunk stays in pendingChunks until we receive it (onChunkReceived) or cleanup expires the request
                } catch (MessageException e) {
                    LOGGER.error("Failed to create or queue message for hash {}: {}", fileHash, e.getMessage());
                    adfm.arbitraryDataFileRequests.remove(fileHash);
                    adfm.removeGuardTracking(fileHash);
                    // Chunk stays in pendingChunks so we can retry from another peer
                }
            }

            
        }

       
    }


}