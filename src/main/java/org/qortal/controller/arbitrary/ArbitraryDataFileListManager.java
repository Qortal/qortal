package org.qortal.controller.arbitrary;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataFileChunk;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryDirectConnectionInfo;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
import org.qortal.data.arbitrary.ArbitraryRelayInfo;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.Network;
import org.qortal.network.NetworkData;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.network.PeerList;
import org.qortal.network.message.ArbitraryDataFileListMessage;
import org.qortal.network.message.GetArbitraryDataFileListMessage;
import org.qortal.network.message.Message;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.ListUtils;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

public class ArbitraryDataFileListManager {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileListManager.class);

    private static ArbitraryDataFileListManager instance;

    /**
     * Map of recent incoming requests for ARBITRARY transaction data file lists.
     * <p>
     * Key is original request's message ID<br>
     * Value is Triple&lt;transaction signature in base58, first requesting peer, first request's timestamp&gt;
     * <p>
     * If peer is null then either:<br>
     * <ul>
     * <li>we are the original requesting peer</li>
     * <li>we have already sent data payload to original requesting peer.</li>
     * </ul>
     * If signature is null then we have already received the file list and either:<br>
     * <ul>
     * <li>we are the original requesting peer and have processed it</li>
     * <li>we have forwarded the file list</li>
     * </ul>
     */
    public Map<Integer, Triple<String, Peer, Long>> arbitraryDataFileListRequests = Collections.synchronizedMap(new HashMap<>());

    /**
     * Map to keep track of in progress arbitrary data signature requests
     * Key: string - the signature encoded in base58
     * Value: Triple<networkBroadcastCount, directPeerRequestCount, lastAttemptTimestamp>
     * Uses ConcurrentHashMap for thread-safe atomic operations like compute()
     */
    private final ConcurrentHashMap<String, Triple<Integer, Integer, Long>> arbitraryDataSignatureRequests = new ConcurrentHashMap<>();


    /** Maximum number of seconds that a file list relay request is able to exist on the network */
    public static long RELAY_REQUEST_MAX_DURATION = 20000L;
    /** Maximum number of hops that a file list relay request is allowed to make */
    public static int RELAY_REQUEST_MAX_HOPS = 4; // was 4, this is no longer ArbData, only metaData/Lists
    public static int SEARCH_DEPTH_MAX_HOPS = 6; // Only used to determine if we should forward or terminate a search for QDN data
    /** Minimum peer version to use relay */
    public static String RELAY_MIN_PEER_VERSION = "3.4.0";
 
    private final Boolean isRelayAvailable;

    private ArbitraryDataFileListManager() {
        getArbitraryDataFileListMessageScheduler.scheduleAtFixedRate(this::processNetworkGetArbitraryDataFileListMessage, 60 * 1000, 500, TimeUnit.MILLISECONDS);
        arbitraryDataFileListMessageScheduler.scheduleAtFixedRate(this::processNetworkArbitraryDataFileListMessage, 60 * 1000, 500, TimeUnit.MILLISECONDS);
        this.isRelayAvailable = Settings.getInstance().isRelayModeEnabled();
    }

 

    private Boolean getIsDirectConnectable() {
        return NetworkData.getInstance().canAcceptInbound();
    }

    public static ArbitraryDataFileListManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataFileListManager();

        return instance;
    }


    public void cleanupRequestCache(Long now) {
        if (now == null) {
            return;
        }
        final long requestMinimumTimestamp = now - ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT;
        arbitraryDataFileListRequests.entrySet().removeIf(entry -> entry.getValue().getC() == null || entry.getValue().getC() < requestMinimumTimestamp);
        
        // Clean up old signature request tracking entries
        // These are used for rate-limiting with a maximum backoff of 6 hours
        // After 24 hours, entries are obsolete and safe to remove to prevent memory leaks
        final long signatureRequestMaxAge = 24 * 60 * 60 * 1000L; // 24 hours
        final long signatureRequestMinimumTimestamp = now - signatureRequestMaxAge;
        int removedCount = arbitraryDataSignatureRequests.size();
        arbitraryDataSignatureRequests.entrySet().removeIf(entry -> {
            Long timestamp = entry.getValue().getC();
            return timestamp != null && timestamp < signatureRequestMinimumTimestamp;
        });
        removedCount -= arbitraryDataSignatureRequests.size();
        
        if (removedCount > 0) {
            LOGGER.debug("Cleaned up {} expired signature request tracking entries (older than 24 hours)", removedCount);
        }
    }


    // Track file list lookups by signature

    private boolean shouldMakeFileListRequestForSignature(String signature58) {
        Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

        if (request == null) {
            // Not attempted yet
            return true;
        }

        // Extract the components
        Integer networkBroadcastCount = request.getA();
        // Integer directPeerRequestCount = request.getB();
        Long lastAttemptTimestamp = request.getC();

        if (lastAttemptTimestamp == null) {
            // Not attempted yet
            return true;
        }

        long timeSinceLastAttempt = NTP.getTime() - lastAttemptTimestamp;

        // Allow a second attempt after 15 seconds, and another after 30 seconds
        if (timeSinceLastAttempt > 15 * 1000L) {
            // We haven't tried for at least 15 seconds

            if (networkBroadcastCount < 12) {
                // We've made less than 12 total attempts
                return true;
            }
        }

        // Then allow another 5 attempts, each 1 minute apart
        if (timeSinceLastAttempt > 60 * 1000L) {
            // We haven't tried for at least 1 minute

            if (networkBroadcastCount < 40) {
                // We've made less than 40 total attempts
                return true;
            }
        }

        // Then allow another 8 attempts, each 15 minutes apart
        if (timeSinceLastAttempt > 15 * 60 * 1000L) {
            // We haven't tried for at least 15 minutes

            if (networkBroadcastCount < 16) {
                // We've made less than 16 total attempts
                return true;
            }
        }

        // From then on, only try once every 6 hours, to reduce network spam
        if (timeSinceLastAttempt > 6 * 60 * 60 * 1000L) {
            // We haven't tried for at least 6 hours
            return true;
        }

        return false;
    }

    private boolean shouldMakeDirectFileRequestsForSignature(String signature58) {
        if (!Settings.getInstance().isDirectDataRetrievalEnabled()) {
            // Direct connections are disabled in the settings
            return false;
        }

        Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

        if (request == null) {
            // Not attempted yet
            return true;
        }

        // Extract the components
        //Integer networkBroadcastCount = request.getA();
        Integer directPeerRequestCount = request.getB();
        Long lastAttemptTimestamp = request.getC();

        if (lastAttemptTimestamp == null) {
            // Not attempted yet
            return true;
        }

        if (directPeerRequestCount == 0) {
            // We haven't tried asking peers directly yet, so we should
            return true;
        }

        long timeSinceLastAttempt = NTP.getTime() - lastAttemptTimestamp;
        if (timeSinceLastAttempt > 10 * 1000L) {
            // We haven't tried for at least 10 seconds
            if (directPeerRequestCount < 5) {
                // We've made less than 5 total attempts
                return true;
            }
        }

        if (timeSinceLastAttempt > 5 * 60 * 1000L) {
            // We haven't tried for at least 5 minutes
            if (directPeerRequestCount < 10) {
                // We've made less than 10 total attempts
                return true;
            }
        }

        if (timeSinceLastAttempt > 60 * 60 * 1000L) {
            // We haven't tried for at least 1 hour
            return true;
        }

        return false;
    }

    public boolean isSignatureRateLimited(byte[] signature) {
        String signature58 = Base58.encode(signature);
        return !this.shouldMakeFileListRequestForSignature(signature58)
                && !this.shouldMakeDirectFileRequestsForSignature(signature58);
    }

    public long lastRequestForSignature(byte[] signature) {
        String signature58 = Base58.encode(signature);
        Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

        if (request == null) {
            // Not attempted yet
            return 0;
        }

        // Extract the components
        Long lastAttemptTimestamp = request.getC();
        if (lastAttemptTimestamp != null) {
            return  lastAttemptTimestamp;
        }
        return 0;
    }

    public void addToSignatureRequests(String signature58, boolean incrementNetworkRequests, boolean incrementPeerRequests) {
        Long now = NTP.getTime();
        
        // Use compute() for atomic check-and-update operation
        arbitraryDataSignatureRequests.compute(signature58, (key, existing) -> {
            if (existing == null) {
                // No entry yet - create new entry
                int networkCount = incrementNetworkRequests ? 1 : 0;
                int peerCount = incrementPeerRequests ? 1 : 0;
                return new Triple<>(networkCount, peerCount, now);
            } else {
                // There is an existing entry - update it atomically
                int networkCount = existing.getA();
                int peerCount = existing.getB();
                
                if (incrementNetworkRequests) {
                    networkCount = networkCount + 1;
                }
                if (incrementPeerRequests) {
                    peerCount = peerCount + 1;
                }
                
                // Create new Triple with updated values (Triple is immutable, so we create a new one)
                return new Triple<>(networkCount, peerCount, now);
            }
        });
    }

    public void removeFromSignatureRequests(String signature58) {
        arbitraryDataSignatureRequests.remove(signature58);
    }


    // Lookup file lists by signature (and optionally hashes)

    public boolean fetchArbitraryDataFileList(ArbitraryTransactionData arbitraryTransactionData) {
        byte[] signature = arbitraryTransactionData.getSignature();
        String signature58 = Base58.encode(signature);

        // Require an NTP sync
        Long now = NTP.getTime();
        if (now == null) {
            return false;
        }

        // ATOMIC check-and-update using compute() to prevent race conditions
        // This ensures only one thread can pass the rate limit check and update the counter
        // compute() returns the new value that was stored in the map
        Triple<Integer, Integer, Long> updatedRequest = arbitraryDataSignatureRequests.compute(signature58, (key, existing) -> {
            if (existing == null) {
                // First request - allow it and create entry
                return new Triple<>(1, 0, now);
            }
            
            // Extract the components
            Integer networkBroadcastCount = existing.getA();
            Integer directPeerRequestCount = existing.getB();
            Long lastAttemptTimestamp = existing.getC();
            
            if (lastAttemptTimestamp == null) {
                // Not attempted yet - allow it
                return new Triple<>(networkBroadcastCount + 1, directPeerRequestCount, now);
            }
            
            long timeSinceLastAttempt = now - lastAttemptTimestamp;
            
            // Rate limiting logic (same as shouldMakeFileListRequestForSignature)
            // Allow a second attempt after 15 seconds, and another after 30 seconds
            if (timeSinceLastAttempt > 15 * 1000L) {
                // We haven't tried for at least 15 seconds
                if (networkBroadcastCount < 12) {
                    // We've made less than 12 total attempts - allow it
                    return new Triple<>(networkBroadcastCount + 1, directPeerRequestCount, now);
                }
            }
            
            // Then allow another 5 attempts, each 1 minute apart
            if (timeSinceLastAttempt > 60 * 1000L) {
                // We haven't tried for at least 1 minute
                if (networkBroadcastCount < 40) {
                    // We've made less than 40 total attempts - allow it
                    return new Triple<>(networkBroadcastCount + 1, directPeerRequestCount, now);
                }
            }
            
            // Then allow another 8 attempts, each 15 minutes apart
            if (timeSinceLastAttempt > 15 * 60 * 1000L) {
                // We haven't tried for at least 15 minutes
                if (networkBroadcastCount < 16) {
                    // We've made less than 16 total attempts - allow it
                    return new Triple<>(networkBroadcastCount + 1, directPeerRequestCount, now);
                }
            }
            
            // From then on, only try once every 6 hours, to reduce network spam
            if (timeSinceLastAttempt > 6 * 60 * 60 * 1000L) {
                // We haven't tried for at least 6 hours - allow it
                return new Triple<>(networkBroadcastCount + 1, directPeerRequestCount, now);
            }
            
            // Rate limited - return existing value unchanged to indicate rejection
            return existing;
        });
        
        // Check if the request was rate limited by comparing the timestamp
        // If the timestamp equals 'now', it was updated (allowed)
        // If the timestamp is different, it was rate limited (returned existing value)
        boolean requestAllowed = updatedRequest.getC().equals(now);
        
        if (!requestAllowed) {
            // Check if we should make direct connections to peers
            if (this.shouldMakeDirectFileRequestsForSignature(signature58)) {
                return ArbitraryDataFileManager.getInstance().fetchDataFilesFromPeersForSignature(signature);
            }
            
            LOGGER.trace("Skipping file list request for signature {} due to rate limit", signature58);
            return false;
        }

        PeerList handshakedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();
        List<byte[]> missingHashes = null;

        // Find hashes that we are missing
        try {
            ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(arbitraryTransactionData);
            missingHashes = arbitraryDataFile.missingHashes();
        } catch (DataException e) {
            // Leave missingHashes as null, so that all hashes are requested
        }
        int hashCount = missingHashes != null ? missingHashes.size() : 0;

        LOGGER.trace(String.format("Sending data file list request for signature %s with %d hashes to %d peers...", signature58, hashCount, handshakedPeers.size()));

        // Send our address as requestingPeer, to allow for potential direct connections with seeds/peers
        //String requestingPeer = NetworkData.getInstance().getOurExternalIpAddressAndPort();
        String requestingPeer = Network.getInstance().getOurExternalIpAddress() + ":" + Settings.getInstance().getQDNListenPort();

        // Build request
        Message getArbitraryDataFileListMessage = new GetArbitraryDataFileListMessage(signature, missingHashes, now, 0, requestingPeer);

        // Save our request into requests map
        Triple<String, Peer, Long> requestEntry = new Triple<>(signature58, null, NTP.getTime());

        // Assign random ID to this message
        int id;
        do {
            id = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;

            // Put queue into map (keyed by message ID) so we can poll for a response
            // If putIfAbsent() doesn't return null, then this ID is already taken
        } while (arbitraryDataFileListRequests.put(id, requestEntry) != null);
        getArbitraryDataFileListMessage.setId(id);

        // Broadcast request
        NetworkData.getInstance().broadcast(peer -> getArbitraryDataFileListMessage);

        // Poll to see if data has arrived
        final long singleWait = 100;
        long totalWait = 0;
        while (totalWait < ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT) {
            try {
                Thread.sleep(singleWait);
            } catch (InterruptedException e) {
                break;
            }

            requestEntry = arbitraryDataFileListRequests.get(id);
            if (requestEntry == null)
                return false;

            if (requestEntry.getA() == null)
                break;

            totalWait += singleWait;
        }
        return true;
    }

    public boolean fetchArbitraryDataFileList(Peer peer, byte[] signature) {
        String signature58 = Base58.encode(signature);

        // Require an NTP sync
        Long now = NTP.getTime();
        if (now == null) {
            return false;
        }

        int hashCount = 0;
        LOGGER.trace(String.format("Sending data file list request for signature %s with %d hashes to peer %s...", signature58, hashCount, peer));

        // Build request
        // Use a time in the past, so that the recipient peer doesn't try and relay it
        // Also, set hashes to null since it's easier to request all hashes than it is to determine which ones we need
        // This could be optimized in the future
        long timestamp = now - 60000L;
        List<byte[]> hashes = null;
        Message getArbitraryDataFileListMessage = new GetArbitraryDataFileListMessage(signature, hashes, timestamp, 0, null);

        // Save our request into requests map
        Triple<String, Peer, Long> requestEntry = new Triple<>(signature58, null, NTP.getTime());

        // Assign random ID to this message
        int id;
        do {
            id = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;

            // Put queue into map (keyed by message ID) so we can poll for a response
            // If putIfAbsent() doesn't return null, then this ID is already taken
        } while (arbitraryDataFileListRequests.put(id, requestEntry) != null);
        getArbitraryDataFileListMessage.setId(id);

        // Send the request
        peer.sendMessage(getArbitraryDataFileListMessage);

        // Poll to see if data has arrived
        final long singleWait = 100;
        long totalWait = 0;
        while (totalWait < ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT) {
            try {
                Thread.sleep(singleWait);
            } catch (InterruptedException e) {
                break;
            }

            requestEntry = arbitraryDataFileListRequests.get(id);
            if (requestEntry == null)
                return false;

            if (requestEntry.getA() == null)
                break;

            totalWait += singleWait;
        }
        return true;
    }

    public void deleteFileListRequestsForSignature(String signature58) {

        for (Iterator<Map.Entry<Integer, Triple<String, Peer, Long>>> it = arbitraryDataFileListRequests.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Triple<String, Peer, Long>> entry = it.next();
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (Objects.equals(entry.getValue().getA(), signature58)) {
                // Update requests map to reflect that we've received all chunks
                Triple<String, Peer, Long> newEntry = new Triple<>(null, null, entry.getValue().getC());
                arbitraryDataFileListRequests.put(entry.getKey(), newEntry);
            }
        }
    }

    // Network handlers

    // List to collect messages
    private final List<PeerMessage> arbitraryDataFileListMessageList = new ArrayList<>();
    // Lock to synchronize access to the list
    private final Object arbitraryDataFileListMessageLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService arbitraryDataFileListMessageScheduler = Executors.newScheduledThreadPool(1);

    public void onNetworkArbitraryDataFileListMessage(Peer peer, Message message) {
        LOGGER.trace("onNetworkArbitraryDataFileListMessage called: peer={}, messageId={}", peer, message.getId());
        // Don't process if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        synchronized (arbitraryDataFileListMessageLock) {
            arbitraryDataFileListMessageList.add(new PeerMessage(peer, message));
        }
    }

    /*
     * Received a ArbitraryDataList Message
     */
    private void processNetworkArbitraryDataFileListMessage() {

        try {
            List<PeerMessage> messagesToProcess;
            synchronized (arbitraryDataFileListMessageLock) {
                messagesToProcess = new ArrayList<>(arbitraryDataFileListMessageList);
                arbitraryDataFileListMessageList.clear();
            }

            if (messagesToProcess.isEmpty()) return;

            // Store ALL peer messages per signature (not just the last one)
            Map<String, List<PeerMessage>> peerMessagesBySignature58 = new HashMap<>(messagesToProcess.size());
            Map<String, byte[]> signatureBySignature58 = new HashMap<>(messagesToProcess.size());
            Map<String, Boolean> isRelayRequestBySignature58 = new HashMap<>(messagesToProcess.size());
            Map<String, List<byte[]>> hashesBySignature58 = new HashMap<>(messagesToProcess.size());
            Map<String, Triple<String, Peer, Long>> requestBySignature58 = new HashMap<>(messagesToProcess.size());

            for (PeerMessage peerMessage : messagesToProcess) {
                Peer peer = peerMessage.getPeer();
                // Mark peer as actively used for QDN (they're sending us file lists)
                peer.QDNUse();
                
                Message message = peerMessage.getMessage();

                ArbitraryDataFileListMessage arbitraryDataFileListMessage = (ArbitraryDataFileListMessage) message;

                LOGGER.trace("Received hash list from peer {} with {} hashes", peer, arbitraryDataFileListMessage.getHashes().size());

                if (LOGGER.isDebugEnabled() && arbitraryDataFileListMessage.getRequestTime() != null) {
                    long totalRequestTime = NTP.getTime() - arbitraryDataFileListMessage.getRequestTime();
                    LOGGER.debug("totalRequestTime: {}, requestHops: {}, peerAddress: {}, isRelayPossible: {}",
                            totalRequestTime, arbitraryDataFileListMessage.getRequestHops(),
                            arbitraryDataFileListMessage.getPeerAddress(), arbitraryDataFileListMessage.isRelayPossible());
                }

                // Do we have a pending request for this data?
                Triple<String, Peer, Long> request = arbitraryDataFileListRequests.get(message.getId());
                if (request == null || request.getA() == null) {
                    LOGGER.trace("CONTINUE - No Pending Request");
                    continue;
                }

                boolean isRelayRequest = (request.getB() != null); //Peer?
                LOGGER.trace("isRelayRequest: {}", isRelayRequest);
                // Does this message's signature match what we're expecting?
                byte[] signature = arbitraryDataFileListMessage.getSignature();
                String signature58 = Base58.encode(signature);
                if (!request.getA().equals(signature58)) {
                    LOGGER.trace("CONTINUE - Sig58 does not match");
                    continue;
                }

                List<byte[]> hashes = arbitraryDataFileListMessage.getHashes();
                if (hashes == null || hashes.isEmpty()) {
                    LOGGER.trace("CONTINUE - null or isEmpty");
                    continue;
                }

                // Append to list instead of overwriting - this allows multiple peers per signature
                peerMessagesBySignature58.computeIfAbsent(signature58, k -> new ArrayList<>()).add(peerMessage);
                signatureBySignature58.put(signature58, signature);
                isRelayRequestBySignature58.put(signature58, isRelayRequest);
                hashesBySignature58.put(signature58, hashes);
                requestBySignature58.put(signature58, request);
            }

            if (signatureBySignature58.isEmpty()) {
                LOGGER.trace("RETURN - Sig58 is Empty");
                return;
            }

            List<ArbitraryTransactionData> arbitraryTransactionDataList;

            // Check transaction exists and hashes are correct
            try (final Repository repository = RepositoryManager.getRepository()) {
                arbitraryTransactionDataList
                        = repository.getTransactionRepository()
                        .fromSignatures(new ArrayList<>(signatureBySignature58.values())).stream()
                        .filter(data -> data instanceof ArbitraryTransactionData)
                        .map(data -> (ArbitraryTransactionData) data)
                        .collect(Collectors.toList());
            } catch (DataException e) {
                LOGGER.error(String.format("Repository issue while finding arbitrary transaction data list"), e);
                return;
            }

            for (ArbitraryTransactionData arbitraryTransactionData : arbitraryTransactionDataList) {

                if( arbitraryTransactionData == null )
                    continue;

                if (ListUtils.isNameBlocked(arbitraryTransactionData.getName())) {
                    LOGGER.trace("User is Blocked - Will not fetch data");
                    continue;  //ToDo: Can this be changed to break to increase performance?
                }

                byte[] signature = arbitraryTransactionData.getSignature();
                String signature58 = Base58.encode(signature);

                List<byte[]> hashes = hashesBySignature58.get(signature58);
                
                // Process ALL peers that responded for this signature
                List<PeerMessage> peerMessages = peerMessagesBySignature58.get(signature58);
                if (peerMessages == null || peerMessages.isEmpty()) {
                    continue;
                }
                
                Boolean isRelayRequest = isRelayRequestBySignature58.get(signature58);
                
                // Determine how many peers to process based on request type
                List<PeerMessage> peersToProcess;
                if (isRelayRequest != null && isRelayRequest && this.isRelayAvailable) {
                    // For relay requests, only process first peer to avoid duplicate forwards
                    peersToProcess = Collections.singletonList(peerMessages.get(0));
                   
                } else {
                    // For direct requests, process all peers to maximize download speed
                    peersToProcess = peerMessages;
                  
                }

                // Iterate over selected peers
                for (PeerMessage peerMessage : peersToProcess) {
                    Peer peer = peerMessage.getPeer();
                    Message message = peerMessage.getMessage();

                    ArbitraryDataFileListMessage arbitraryDataFileListMessage = (ArbitraryDataFileListMessage) message;

                    // Process direct download responses (not relay forwarding)
                    if (!isRelayRequest || !this.isRelayAvailable) {

                        Long now = NTP.getTime();

                        // Keep track of the hashes this peer reports to have access to
                        for (byte[] hash : hashes) {
                            String hash58 = Base58.encode(hash);

                            // Skip if we already have this hash (check permanent storage first, then relay cache)
                            try {
                                ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(hash, signature);
                                if (existingFile.exists()) {
                                    LOGGER.trace("Skipping hash {} from file list - already exists in permanent storage", hash58);
                                    continue;
                                }
                                
                                // Check relay cache
                                byte[] cachedData = ArbitraryDataFileManager.getInstance().loadFromRelayCache(hash58);
                                if (cachedData != null) {
                                    LOGGER.debug("Hash {} found in relay cache while processing file list - saving to permanent storage", hash58);
                                    try {
                                        ArbitraryDataFile cachedFile = new ArbitraryDataFile(cachedData, signature, false);
                                        if (cachedFile.validateHash(hash)) {
                                            cachedFile.save();
                                            LOGGER.trace("Saved hash {} from relay cache to permanent storage", hash58);
                                            continue; // Skip adding to response tracking
                                        } else {
                                            LOGGER.warn("Cached chunk {} failed validation, will re-request", hash58);
                                            // Fall through to add to tracking
                                        }
                                    } catch (Exception e) {
                                        LOGGER.warn("Failed to save hash {} from relay cache: {}", hash58, e.getMessage());
                                        // Fall through to add to tracking
                                    }
                                }
                            } catch (DataException e) {
                                LOGGER.warn("Error checking if hash {} exists: {}", hash58, e.getMessage());
                                // Fall through to add to tracking
                            }

                            // Treat null request hops as 100, so that they are able to be sorted (and put to the end of the list)
                            int requestHops = arbitraryDataFileListMessage.getRequestHops() != null ? arbitraryDataFileListMessage.getRequestHops() : 100;

                            String peerWithFilesString = arbitraryDataFileListMessage.getPeerAddress();
                            PeerAddress pa = PeerAddress.fromString(peerWithFilesString); // HOST:PORT
                            PeerData pd = new PeerData(pa,now, "INIT");
                            Peer peerWithFiles = new Peer(pd, Peer.NETWORKDATA);
                            String nodeId = arbitraryDataFileListMessage.getNodeId();
                            // Update Response based on Content Holder being able to access direct connect
                            ArbitraryFileListResponseInfo responseInfo;

                            if(arbitraryDataFileListMessage.isDirectConnectable()) {
                                responseInfo = new ArbitraryFileListResponseInfo(hash58, signature58,
                                        peerWithFiles, nodeId, now, arbitraryDataFileListMessage.getRequestTime(), requestHops, true);
                                LOGGER.debug("Adding QDN Direct Connect responseInfo to ArbDataFileManager peer: {} FileHash: {}", peerWithFilesString, hash58);
                            } else { // We have to relay the peers chunks because they cant Direct Connect
                                responseInfo = new ArbitraryFileListResponseInfo(hash58, signature58,
                                        peer, nodeId, now, arbitraryDataFileListMessage.getRequestTime(), requestHops, false);
                                LOGGER.trace("Adding QDN Relay-able responseInfo to ArbDataFileManager peer: {} FileHash: {}", peer, hash58);
                            }
                            ArbitraryDataFileManager.getInstance().addResponse(responseInfo);
                        }

                        // Keep track of the source peer, for direct connections
                        if (arbitraryDataFileListMessage.getPeerAddress() != null) {
                            ArbitraryDataFileManager.getInstance().addDirectConnectionInfoIfUnique(
                                    new ArbitraryDirectConnectionInfo(signature, arbitraryDataFileListMessage.getPeerAddress(), arbitraryDataFileListMessage.getNodeId(), hashes, now));
                        }
                    }

                    // Forwarding - We are not the original requestor, just in the middle
                    LOGGER.trace("Status of isRelayRequest {}", isRelayRequest);
                    if (isRelayRequest && this.isRelayAvailable) {
                        Triple<String, Peer, Long> request = requestBySignature58.get(signature58);
                        Peer requestingPeer = request.getB();
                        if (requestingPeer != null) {
                            LOGGER.trace("Requesting Peer is: {}", requestingPeer);
                            Long requestTime = arbitraryDataFileListMessage.getRequestTime();
                            Integer requestHops = arbitraryDataFileListMessage.getRequestHops();
                            Boolean isDirectConnectable = arbitraryDataFileListMessage.isDirectConnectable();
                            String nodeId = arbitraryDataFileListMessage.getNodeId();
                            // Add each hash to our local mapping so we know who to ask later
                            Long now = NTP.getTime();
                            for (byte[] hash : hashes) {
                                String hash58 = Base58.encode(hash);
                                ArbitraryRelayInfo relayInfo = new ArbitraryRelayInfo(hash58, signature58, peer, nodeId, now, requestTime, requestHops, isDirectConnectable);
                                ArbitraryDataFileManager.getInstance().addToRelayMap(relayInfo);
                            }

                            // Bump requestHops if it exists
                            if (requestHops != null) {
                                requestHops++;
                            }

                            ArbitraryDataFileListMessage forwardArbitraryDataFileListMessage = new ArbitraryDataFileListMessage(signature, hashes, requestTime, requestHops,
                                    arbitraryDataFileListMessage.getPeerAddress(), arbitraryDataFileListMessage.getNodeId(), arbitraryDataFileListMessage.isRelayPossible(), arbitraryDataFileListMessage.isDirectConnectable());

                            forwardArbitraryDataFileListMessage.setId(message.getId());

                            // Forward to requesting peer
                            LOGGER.trace("Forwarding file list with {} hashes to requesting peer: {}", hashes.size(), requestingPeer);
                            requestingPeer.sendMessage(forwardArbitraryDataFileListMessage);
                        }
                    }

                  
                }

            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    // List to collect messages
    private final List<PeerMessage> getArbitraryDataFileListMessageList = new ArrayList<>();
    // Lock to synchronize access to the list
    private final Object getArbitraryDataFileListMessageLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService getArbitraryDataFileListMessageScheduler = Executors.newScheduledThreadPool(1);

    /* We receive a request to provide a data file list */
    public void onNetworkGetArbitraryDataFileListMessage(Peer peer, Message message) {
        // Don't respond if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        synchronized (getArbitraryDataFileListMessageLock) {
            getArbitraryDataFileListMessageList.add(new PeerMessage(peer, message));
        }
    }

    /* We received a request to provide a data List  */
    private void processNetworkGetArbitraryDataFileListMessage() {

        try {
            List<PeerMessage> messagesToProcess;
            synchronized (getArbitraryDataFileListMessageLock) {
                messagesToProcess = new ArrayList<>(getArbitraryDataFileListMessageList);
                getArbitraryDataFileListMessageList.clear();
            }

            if (messagesToProcess.isEmpty()) return;

            Map<String, byte[]> signatureBySignature58 = new HashMap<>(messagesToProcess.size());
            Map<String, List<byte[]>> requestedHashesBySignature58 = new HashMap<>(messagesToProcess.size());
            Map<String, String> requestingPeerBySignature58 = new HashMap<>(messagesToProcess.size());
            Map<String, Long> nowBySignature58 = new HashMap<>((messagesToProcess.size()));
            Map<String, PeerMessage> peerMessageBySignature58 = new HashMap<>(messagesToProcess.size());

            for (PeerMessage messagePeer : messagesToProcess) {
                Controller.getInstance().stats.getArbitraryDataFileListMessageStats.requests.incrementAndGet();

                Message message = messagePeer.message;
                Peer peer = messagePeer.peer;
                // Mark peer as actively used for QDN (we're serving them file lists)
                peer.QDNUse();

                GetArbitraryDataFileListMessage getArbitraryDataFileListMessage = (GetArbitraryDataFileListMessage) message;
                byte[] signature = getArbitraryDataFileListMessage.getSignature();
                String signature58 = Base58.encode(signature);
                Long now = NTP.getTime();
                Triple<String, Peer, Long> newEntry = new Triple<>(signature58, peer, now);

                // If we've seen this request recently, then ignore
                if (arbitraryDataFileListRequests.putIfAbsent(message.getId(), newEntry) != null) {
                    LOGGER.info("Ignoring hash list request from peer {} for signature {}", peer, signature58);
                    continue;
                }

                List<byte[]> requestedHashes = getArbitraryDataFileListMessage.getHashes();
                int hashCount = requestedHashes != null ? requestedHashes.size() : 0;
                String requestingPeer = getArbitraryDataFileListMessage.getRequestingPeer();

                if (requestingPeer != null) {
                    LOGGER.info("Received a request to provide a list request with {} hashes from peer {} (requesting peer {}) for signature {} messageId {}", hashCount, peer, requestingPeer, signature58, message.getId());
                } else {
                    LOGGER.info("Requesting Peer is null");
                }

                signatureBySignature58.put(signature58, signature);
                requestedHashesBySignature58.put(signature58, requestedHashes);
                requestingPeerBySignature58.put(signature58, requestingPeer);
                nowBySignature58.put(signature58, now);
                peerMessageBySignature58.put(signature58, messagePeer);
            }

            if (signatureBySignature58.isEmpty()) {
                LOGGER.trace("signatureBySignature58 is EMPTY");
                return;
            }

            List<ArbitraryTransactionData> transactionDataList;
            try (final Repository repository = RepositoryManager.getRepository()) {

                // Firstly we need to lookup this file on chain to get a list of its hashes
                transactionDataList
                        = repository.getTransactionRepository()
                        .fromSignatures(new ArrayList<>(signatureBySignature58.values())).stream()
                        .filter(data -> data instanceof ArbitraryTransactionData)
                        .map(data -> (ArbitraryTransactionData) data)
                        .collect(Collectors.toList());

            } catch (DataException e) {
                LOGGER.error(String.format("Repository issue while fetching arbitrary file list for peer"), e);
                return;
            }

            for (ArbitraryTransactionData transactionData : transactionDataList) {

                // Moved up in check priority to prevent "dead work"
                if(transactionData == null)
                    continue;

                if (ListUtils.isNameBlocked(transactionData.getName())) {
                    LOGGER.debug("User is Blocked - Stop processing");
                    continue;
                }

                byte[] signature = transactionData.getSignature();
                String signature58 = Base58.encode(signature);
                List<byte[]> requestedHashes = requestedHashesBySignature58.get(signature58);
                List<byte[]> hashes = new ArrayList<>();
                List<byte[]> originalRequestedHashes = requestedHashes;
                List<byte[]> missingRequestedHashes = null;
                boolean allChunksExist = false;
                boolean hasMetadata = false;

                // Check if we're even allowed to serve data for this transaction
                if (ArbitraryDataStorageManager.getInstance().canStoreData(transactionData)) {

                    try {
                        // Load file(s) and add any that exist to the list of hashes
                        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(transactionData);

                        // If the peer didn't supply a hash list, we need to return all hashes for this transaction
                        if (requestedHashes == null || requestedHashes.isEmpty()) {
                            requestedHashes = new ArrayList<>();

                            // Add the metadata file
                            if (arbitraryDataFile.getMetadataHash() != null) {
                                requestedHashes.add(arbitraryDataFile.getMetadataHash());
                                hasMetadata = true;
                            }

                            // Add the chunk hashes
                            if (!arbitraryDataFile.getChunkHashes().isEmpty()) {
                                requestedHashes.addAll(arbitraryDataFile.getChunkHashes());
                            }
                            // Add complete file if there are no hashes
                            else {
                                requestedHashes.add(arbitraryDataFile.getHash());
                            }
                        }

                        // Assume all chunks exists, unless one can't be found below
                        allChunksExist = true;
                        List<byte[]> computedMissingRequestedHashes = new ArrayList<>();

                        // Optimization: Batch check file existence by scanning directory once
                        Set<String> existingFileNames = getExistingFilesForSignature(signature);
                        
                        if (existingFileNames != null) {
                            LOGGER.trace("Existing file names");
                            // Fast path: Use in-memory set lookup
                            for (byte[] requestedHash : requestedHashes) {
                                String hash58 = Base58.encode(requestedHash);
                                if (existingFileNames.contains(hash58)) {
                                    hashes.add(requestedHash);
                                    //LOGGER.trace("Added hash {}", hash58);
                                } else {
                                    // Check relay cache before marking as missing
                                    byte[] cachedData = ArbitraryDataFileManager.getInstance().loadFromRelayCache(hash58);
                                    if (cachedData != null) {
                                        LOGGER.debug("Hash {} found in relay cache - including in response to peer", hash58);
                                        hashes.add(requestedHash);
                                        // Mark that not all chunks are in permanent storage (some only in cache)
                                        allChunksExist = false;
                                        // Don't add to missing list - we can serve it from cache
                                    } else {
                                        LOGGER.trace("Couldn't add hash {} because it doesn't exist", hash58);
                                        allChunksExist = false;
                                        computedMissingRequestedHashes.add(requestedHash);
                                    }
                                }
                            }
                        } else {
                            LOGGER.trace("legacy fallback file search");
                            // Fallback path: Directory doesn't exist or error scanning
                            for (byte[] requestedHash : requestedHashes) {
                                ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash(requestedHash, signature);
                                if (chunk.exists()) {
                                    hashes.add(chunk.getHash());
                                    //LOGGER.trace("Added hash {}", chunk.getHash58());
                                } else {
                                    // Check relay cache before marking as missing
                                    String hash58 = Base58.encode(requestedHash);
                                    byte[] cachedData = ArbitraryDataFileManager.getInstance().loadFromRelayCache(hash58);
                                    if (cachedData != null) {
                                        LOGGER.debug("Hash {} found in relay cache - including in response to peer", hash58);
                                        hashes.add(requestedHash);
                                        // Mark that not all chunks are in permanent storage (some only in cache)
                                        allChunksExist = false;
                                        // Don't add to missing list - we can serve it from cache
                                    } else {
                                        LOGGER.trace("Couldn't add hash {} because it doesn't exist", chunk.getHash58());
                                        allChunksExist = false;
                                        computedMissingRequestedHashes.add(requestedHash);
                                    }
                                }
                            }
                        }
                        missingRequestedHashes = computedMissingRequestedHashes;
                    } catch (DataException e) {
                        LOGGER.error(e.getMessage(), e);
                    }
                }

                // If the only file we have is the metadata then we shouldn't respond. Most nodes will already have that,
                // or can use the separate metadata protocol to fetch it. This should greatly reduce network spam.
                if (hasMetadata && hashes.size() == 1) {
                    hashes.clear();
                }

                PeerMessage peerMessage = peerMessageBySignature58.get(signature58);
                Peer peer = peerMessage.getPeer();
                Message message = peerMessage.getMessage();

                Long now = nowBySignature58.get(signature58);

                // We should only respond if we have at least one hash
                String requestingPeer = requestingPeerBySignature58.get(signature58);
                LOGGER.info("Preparing our response to GetArbitraryDataFileList, {}", signature58);
                if (!hashes.isEmpty()) {

                    // Firstly we should keep track of the requesting peer, to allow for potential direct connections later
                    ArbitraryDataFileManager.getInstance().addRecentDataRequest(requestingPeer);

                    // We have all the chunks, so update requests map to reflect that we've sent it
                    // There is no need to keep track of the request, as we can serve all the chunks
                    if (allChunksExist) {
                        Triple<String, Peer, Long> newEntry = new Triple<>(null, null, now);
                        arbitraryDataFileListRequests.put(message.getId(), newEntry);
                    }

                    String ourAddress = Network.getInstance().getOurExternalIpAddress() + ":" + Settings.getInstance().getQDNListenPort();
                    LOGGER.trace("We Think our external address is: {}", Network.getInstance().getOurExternalIpAddress());
                    ArbitraryDataFileListMessage arbitraryDataFileListMessage;

                    Collections.shuffle(hashes.subList(1, hashes.size()));

                    String nodeId = NetworkData.getInstance().getOurNodeId();
                    arbitraryDataFileListMessage = new ArbitraryDataFileListMessage(signature,
                        hashes, NTP.getTime(), 0, ourAddress, nodeId, this.isRelayAvailable, this.getIsDirectConnectable());

                    arbitraryDataFileListMessage.setId(message.getId());

                    if (!peer.sendMessage(arbitraryDataFileListMessage)) {
                        LOGGER.trace("Couldn't send list of hashes");
                        continue;
                    }

                    if (allChunksExist) {
                        // Only skip forwarding if we have complete knowledge (metadata loaded with chunks)
                        // We have complete knowledge if:
                        // 1. The original request was empty (we expanded it), AND
                        // 2. We actually loaded chunk hashes (requestedHashes has more than just metadata + file hash)
                        // If requestedHashes only has 2 items (metadata + file hash), we didn't load chunk info
                        boolean expandedFromEmpty = originalRequestedHashes == null || originalRequestedHashes.isEmpty();
                        boolean hasLoadedChunkHashes = expandedFromEmpty && requestedHashes.size() > 2;
                        boolean hasCompleteKnowledge = hasLoadedChunkHashes && !hashes.isEmpty() && missingRequestedHashes != null && missingRequestedHashes.isEmpty();
                        
                        if (hasCompleteKnowledge) {
                            // Nothing left to do, so return to prevent any unnecessary forwarding from occurring
                            LOGGER.info("No need for any forwarding because file list request is fully served with complete knowledge");
                            continue;
                        }
                        LOGGER.trace("Have some chunks but may have incomplete knowledge - will still forward request");
                    }
                }

                LOGGER.trace("We don't have hashes or all hashes - Checking Relay Mode");
                // We may need to forward this request on

                //if (this.isRelayAvailable ) { = We do not want to block the relay of file find requests, this prevents Direct-Connect finding
                    // In relay mode - so ask our other peers if they have it

                    GetArbitraryDataFileListMessage getArbitraryDataFileListMessage = (GetArbitraryDataFileListMessage) message;

                    long requestTime = getArbitraryDataFileListMessage.getRequestTime();
                    int requestHops = getArbitraryDataFileListMessage.getRequestHops() + 1;
                    long totalRequestTime = now - requestTime;

                   
                    // If we checked locally and don't need anything else, don't relay.
                    // (An empty list can be interpreted as "request all" by the receiver.)
                    if (missingRequestedHashes != null && missingRequestedHashes.isEmpty()) {
                        LOGGER.trace("No missing hashes remaining for signature {}, skipping relay", signature58);
                        continue;
                    }

                    if (totalRequestTime < RELAY_REQUEST_MAX_DURATION) { // 5 Seconds
                        // Relay request hasn't timed out yet, so can potentially be rebroadcast
                        if (requestHops < SEARCH_DEPTH_MAX_HOPS) { // 6 Hops
                            // Relay request hasn't reached the maximum number of hops yet, so can be rebroadcast

                            // When relaying, always forward the original request unchanged to preserve the requester's intent.
                            // Using missingRequestedHashes would filter based on our incomplete knowledge (we may not have metadata).
                            // This ensures the origin node can provide the complete picture directly to the requester.
                            List<byte[]> relayHashes = originalRequestedHashes;
                            Message relayGetArbitraryDataFileListMessage = new GetArbitraryDataFileListMessage(signature, relayHashes, requestTime, requestHops, requestingPeer);
                            relayGetArbitraryDataFileListMessage.setId(message.getId());

                            LOGGER.debug("Rebroadcasting hash list request from peer {} for signature {} to our other peers... totalRequestTime: {}, requestHops: {}", peer, Base58.encode(signature), totalRequestTime, requestHops);
                            NetworkData.getInstance().broadcast(
                                    broadcastPeer ->
                                            broadcastPeer == peer || Objects.equals(broadcastPeer.getPeerData().getAddress().getHost(), peer.getPeerData().getAddress().getHost()) ? null : relayGetArbitraryDataFileListMessage
                            );
                        } else {
                            LOGGER.trace("Request has reached the maximum number of allowed hops");
                        }
                    } else {
                        LOGGER.trace("Relay Request has timed out");
                    }
                //} else {
                //    LOGGER.debug("Relay (fetch-reserve) is disabled");
                //}
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Efficiently gets all existing file names for a given signature by scanning the directory once.
     * Returns a Set of file names (hash58 strings) for fast lookup, or null if directory doesn't exist or error occurs.
     * 
     * @param signature The signature bytes identifying the transaction
     * @return Set of existing file names (hash58 strings), or null if directory doesn't exist
     */
    private Set<String> getExistingFilesForSignature(byte[] signature) {
        try {
            // Construct the directory path using the same logic as ArbitraryDataFile
            String signature58 = Base58.encode(signature);
            String sig58First2Chars = signature58.substring(0, 2).toLowerCase();
            String sig58Next2Chars = signature58.substring(2, 4).toLowerCase();
            Path directory = Path.of(Settings.getInstance().getDataPath(), sig58First2Chars, sig58Next2Chars, signature58);

            // Check if directory exists
            if (!Files.exists(directory) || !Files.isDirectory(directory)) {
                return null;
            }

            // Scan directory once and collect all file names
            Set<String> fileNames = new HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry)) {
                        fileNames.add(entry.getFileName().toString());
                    }
                }
            }
            
            LOGGER.trace("Scanned directory {} and found {} files for signature {}", 
                    directory, fileNames.size(), signature58);
            return fileNames;
            
        } catch (IOException e) {
            LOGGER.debug("Error scanning directory for signature {}: {}", 
                    Base58.encode(signature), e.getMessage());
            return null;
        } catch (Exception e) {
            LOGGER.error("Unexpected error scanning directory for signature {}: {}", 
                    Base58.encode(signature), e.getMessage());
            return null;
        }
    }
    
    /**
     * Shutdown the scheduled executor services.
     * Called during application shutdown to properly clean up background threads.
     */
    public void shutdown() {
        LOGGER.info("Shutting down ArbitraryDataFileListManager schedulers...");
        
        arbitraryDataFileListMessageScheduler.shutdown();
        getArbitraryDataFileListMessageScheduler.shutdown();
        
        try {
            if (!arbitraryDataFileListMessageScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("arbitraryDataFileListMessageScheduler did not terminate in time, forcing shutdown");
                arbitraryDataFileListMessageScheduler.shutdownNow();
            }
            if (!getArbitraryDataFileListMessageScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("getArbitraryDataFileListMessageScheduler did not terminate in time, forcing shutdown");
                getArbitraryDataFileListMessageScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while shutting down schedulers, forcing shutdown");
            arbitraryDataFileListMessageScheduler.shutdownNow();
            getArbitraryDataFileListMessageScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("ArbitraryDataFileListManager schedulers shut down successfully");
    }
}
