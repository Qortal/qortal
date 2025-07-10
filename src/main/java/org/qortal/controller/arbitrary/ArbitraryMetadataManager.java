package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataResource;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.controller.Controller;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.ArbitraryMetadataMessage;
import org.qortal.network.message.GetArbitraryMetadataMessage;
import org.qortal.network.message.Message;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.ListUtils;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.qortal.controller.arbitrary.ArbitraryDataFileListManager.*;

public class ArbitraryMetadataManager {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryMetadataManager.class);

    private static ArbitraryMetadataManager instance;

    /**
     * Map of recent incoming requests for ARBITRARY transaction metadata.
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
     * <li>we have forwarded the metadata</li>
     * </ul>
     */
    public Map<Integer, Triple<String, Peer, Long>> arbitraryMetadataRequests = Collections.synchronizedMap(new HashMap<>());

    /**
     * Map to keep track of in progress arbitrary metadata requests
     * Key: string - the signature encoded in base58
     * Value: Triple<networkBroadcastCount, directPeerRequestCount, lastAttemptTimestamp>
     */
    private Map<String, Triple<Integer, Integer, Long>> arbitraryMetadataSignatureRequests = Collections.synchronizedMap(new HashMap<>());


    private ArbitraryMetadataManager() {
        scheduler.scheduleAtFixedRate(this::processNetworkGetArbitraryMetadataMessage, 60, 1, TimeUnit.SECONDS);
    }

    public static ArbitraryMetadataManager getInstance() {
        if (instance == null)
            instance = new ArbitraryMetadataManager();

        return instance;
    }

    public void cleanupRequestCache(Long now) {
        if (now == null) {
            return;
        }
        final long requestMinimumTimestamp = now - ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT;
        arbitraryMetadataRequests.entrySet().removeIf(entry -> entry.getValue().getC() == null || entry.getValue().getC() < requestMinimumTimestamp);
    }


    public ArbitraryDataTransactionMetadata fetchMetadata(ArbitraryDataResource arbitraryDataResource, boolean useRateLimiter) {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Find latest transaction
            ArbitraryTransactionData latestTransaction = repository.getArbitraryRepository()
                    .getLatestTransaction(arbitraryDataResource.getResourceId(), arbitraryDataResource.getService(),
                            null, arbitraryDataResource.getIdentifier());

            if (latestTransaction != null) {
                byte[] signature = latestTransaction.getSignature();
                byte[] metadataHash = latestTransaction.getMetadataHash();
                if (metadataHash == null) {
                    // This resource doesn't have metadata
                    throw new IllegalArgumentException("This resource doesn't have metadata");
                }

                ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(metadataHash, signature);
                if (!metadataFile.exists()) {
                    // Request from network
                    this.fetchArbitraryMetadata(latestTransaction, useRateLimiter);
                }

                // Now check again as it may have been downloaded above
                if (metadataFile.exists()) {
                    // Use local copy
                    ArbitraryDataTransactionMetadata transactionMetadata = new ArbitraryDataTransactionMetadata(metadataFile.getFilePath());
                    try {
                        transactionMetadata.read();
                    } catch (DataException e) {
                        // Invalid file, so delete it
                        LOGGER.info("Deleting invalid metadata file due to exception: {}", e.getMessage());
                        transactionMetadata.delete();
                        return null;
                    }
                    return transactionMetadata;
                }
            }

        } catch (DataException | IOException e) {
            LOGGER.error("Repository issue when fetching arbitrary transaction metadata", e);
        }

        return null;
    }


    // Request metadata from network

    public byte[] fetchArbitraryMetadata(ArbitraryTransactionData arbitraryTransactionData, boolean useRateLimiter) {
        byte[] metadataHash = arbitraryTransactionData.getMetadataHash();
        if (metadataHash == null) {
            return null;
        }

        byte[] signature = arbitraryTransactionData.getSignature();
        String signature58 = Base58.encode(signature);

        // Require an NTP sync
        Long now = NTP.getTime();
        if (now == null) {
            return null;
        }

        // If we've already tried too many times in a short space of time, make sure to give up
        if (useRateLimiter && !this.shouldMakeMetadataRequestForSignature(signature58)) {
            LOGGER.trace("Skipping metadata request for signature {} due to rate limit", signature58);
            return null;
        }
        this.addToSignatureRequests(signature58, true, false);

        List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();
        LOGGER.debug(String.format("Sending metadata request for signature %s to %d peers...", signature58, handshakedPeers.size()));

        // Build request
        Message getArbitraryMetadataMessage = new GetArbitraryMetadataMessage(signature, now, 0);

        // Save our request into requests map
        Triple<String, Peer, Long> requestEntry = new Triple<>(signature58, null, NTP.getTime());

        // Assign random ID to this message
        int id;
        do {
            id = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;

            // Put queue into map (keyed by message ID) so we can poll for a response
            // If putIfAbsent() doesn't return null, then this ID is already taken
        } while (arbitraryMetadataRequests.put(id, requestEntry) != null);
        getArbitraryMetadataMessage.setId(id);

        // Broadcast request
        Network.getInstance().broadcast(peer -> getArbitraryMetadataMessage);

        // Poll to see if data has arrived
        final long singleWait = 100;
        long totalWait = 0;
        while (totalWait < ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT) {
            try {
                Thread.sleep(singleWait);
            } catch (InterruptedException e) {
                break;
            }

            requestEntry = arbitraryMetadataRequests.get(id);
            if (requestEntry == null)
                return null;

            if (requestEntry.getA() == null)
                break;

            totalWait += singleWait;
        }

        try {
            ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(metadataHash, signature);
            if (metadataFile.exists()) {
                return metadataFile.getBytes();
            }
        } catch (DataException e) {
            // Do nothing
        }

        return null;
    }


    // Track metadata lookups by signature

    private boolean shouldMakeMetadataRequestForSignature(String signature58) {
        Triple<Integer, Integer, Long> request = arbitraryMetadataSignatureRequests.get(signature58);

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

        // Allow a second attempt after 60 seconds
        if (timeSinceLastAttempt > 60 * 1000L) {
            // We haven't tried for at least 60 seconds

            if (networkBroadcastCount < 2) {
                // We've made less than 2 total attempts
                return true;
            }
        }

        // Then allow another attempt after 60 minutes
        if (timeSinceLastAttempt > 60 * 60 * 1000L) {
            // We haven't tried for at least 60 minutes

            if (networkBroadcastCount < 3) {
                // We've made less than 3 total attempts
                return true;
            }
        }

        return false;
    }

    public boolean isSignatureRateLimited(byte[] signature) {
        String signature58 = Base58.encode(signature);
        return !this.shouldMakeMetadataRequestForSignature(signature58);
    }

    public long lastRequestForSignature(byte[] signature) {
        String signature58 = Base58.encode(signature);
        Triple<Integer, Integer, Long> request = arbitraryMetadataSignatureRequests.get(signature58);

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
        Triple<Integer, Integer, Long> request  = arbitraryMetadataSignatureRequests.get(signature58);
        Long now = NTP.getTime();

        if (request == null) {
            // No entry yet
            Triple<Integer, Integer, Long> newRequest = new Triple<>(0, 0, now);
            arbitraryMetadataSignatureRequests.put(signature58, newRequest);
        }
        else {
            // There is an existing entry
            if (incrementNetworkRequests) {
                request.setA(request.getA() + 1);
            }
            if (incrementPeerRequests) {
                request.setB(request.getB() + 1);
            }
            request.setC(now);
            arbitraryMetadataSignatureRequests.put(signature58, request);
        }
    }

    public void removeFromSignatureRequests(String signature58) {
        arbitraryMetadataSignatureRequests.remove(signature58);
    }


    // Network handlers

    public void onNetworkArbitraryMetadataMessage(Peer peer, Message message) {
        // Don't process if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        ArbitraryMetadataMessage arbitraryMetadataMessage = (ArbitraryMetadataMessage) message;
        LOGGER.debug("Received metadata from peer {}", peer);

        // Do we have a pending request for this data?
        Triple<String, Peer, Long> request = arbitraryMetadataRequests.get(message.getId());
        if (request == null || request.getA() == null) {
            return;
        }
        boolean isRelayRequest = (request.getB() != null);

        // Does this message's signature match what we're expecting?
        byte[] signature = arbitraryMetadataMessage.getSignature();
        String signature58 = Base58.encode(signature);
        if (!request.getA().equals(signature58)) {
            return;
        }

        // Update requests map to reflect that we've received this metadata
        Triple<String, Peer, Long> newEntry = new Triple<>(null, null, request.getC());
        arbitraryMetadataRequests.put(message.getId(), newEntry);

        // Get transaction info
        try (final Repository repository = RepositoryManager.getRepository()) {
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof ArbitraryTransactionData)) {
                return;
            }
            ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

            // Check if the name is blocked
            boolean isBlocked = (arbitraryTransactionData == null || ListUtils.isNameBlocked(arbitraryTransactionData.getName()));

            // Save if not blocked
            ArbitraryDataFile arbitraryMetadataFile = arbitraryMetadataMessage.getArbitraryMetadataFile();
            if (!isBlocked && arbitraryMetadataFile != null) {
                arbitraryMetadataFile.save();
            }

            // Forwarding
            if (isRelayRequest && Settings.getInstance().isRelayModeEnabled()) {
                if (!isBlocked) {
                    Peer requestingPeer = request.getB();
                    if (requestingPeer != null) {

                        ArbitraryMetadataMessage forwardArbitraryMetadataMessage = new ArbitraryMetadataMessage(signature, arbitraryMetadataMessage.getArbitraryMetadataFile());
                        forwardArbitraryMetadataMessage.setId(arbitraryMetadataMessage.getId());

                        // Forward to requesting peer
                        LOGGER.debug("Forwarding metadata to requesting peer: {}", requestingPeer);
                        requestingPeer.sendMessage(forwardArbitraryMetadataMessage);
                       
                    }
                }
            }

            // Add to resource queue to update arbitrary resource caches
            if (arbitraryTransactionData != null) {
                ArbitraryDataCacheManager.getInstance().addToUpdateQueue(arbitraryTransactionData);
            }

        } catch (DataException e) {
            LOGGER.error(String.format("Repository issue while saving arbitrary transaction metadata from peer %s", peer), e);
        }
    }

    // List to collect messages
    private final List<PeerMessage> messageList = new ArrayList<>();
    // Lock to synchronize access to the list
    private final Object lock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public void onNetworkGetArbitraryMetadataMessage(Peer peer, Message message) {

        // Don't respond if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        synchronized (lock) {
            messageList.add(new PeerMessage(peer, message));
        }
    }

    private void processNetworkGetArbitraryMetadataMessage() {

        try {
            List<PeerMessage> messagesToProcess;
            synchronized (lock) {
                messagesToProcess = new ArrayList<>(messageList);
                messageList.clear();
            }

            Map<String, byte[]> signatureBySignature58 = new HashMap<>((messagesToProcess.size()));
            Map<String, Long> nowBySignature58 = new HashMap<>(messagesToProcess.size());
            Map<String,PeerMessage> peerMessageBySignature58 = new HashMap<>(messagesToProcess.size());

            for( PeerMessage peerMessage : messagesToProcess) {
                Controller.getInstance().stats.getArbitraryMetadataMessageStats.requests.incrementAndGet();

                GetArbitraryMetadataMessage getArbitraryMetadataMessage = (GetArbitraryMetadataMessage) peerMessage.message;
                byte[] signature = getArbitraryMetadataMessage.getSignature();
                String signature58 = Base58.encode(signature);
                Long now = NTP.getTime();
                Triple<String, Peer, Long> newEntry = new Triple<>(signature58, peerMessage.peer, now);

                // If we've seen this request recently, then ignore
                if (arbitraryMetadataRequests.putIfAbsent(peerMessage.message.getId(), newEntry) != null) {
                    LOGGER.debug("Ignoring metadata request from peer {} for signature {}", peerMessage.peer, signature58);
                    continue;
                }

                LOGGER.debug("Received metadata request from peer {} for signature {}", peerMessage.peer, signature58);

                signatureBySignature58.put(signature58, signature);
                nowBySignature58.put(signature58, now);
                peerMessageBySignature58.put(signature58, peerMessage);
            }

            if( signatureBySignature58.isEmpty() ) return;

            List<TransactionData> transactionDataList;
            try (final Repository repository = RepositoryManager.getRepository()) {

                // Firstly we need to lookup this file on chain to get its metadata hash
                transactionDataList = repository.getTransactionRepository().fromSignatures(new ArrayList(signatureBySignature58.values()));
            } catch (DataException e) {
                LOGGER.error(String.format("Repository issue while fetching arbitrary transactions"), e);
                return;
            }

            Map<String, ArbitraryTransactionData> dataBySignature58
                = transactionDataList.stream()
                    .filter(data -> data instanceof ArbitraryTransactionData)
                    .map(ArbitraryTransactionData.class::cast)
                    .collect(Collectors.toMap(data -> Base58.encode(data.getSignature()), Function.identity()));

            for(Map.Entry<String, ArbitraryTransactionData> entry : dataBySignature58.entrySet()) {
                String signature58 = entry.getKey();
                ArbitraryTransactionData transactionData = entry.getValue();

                try {

                    // Check if we're even allowed to serve metadata for this transaction
                    if (ArbitraryDataStorageManager.getInstance().canStoreData(transactionData)) {

                        byte[] metadataHash = transactionData.getMetadataHash();
                        if (metadataHash != null) {

                            // Load metadata file
                            ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(metadataHash, transactionData.getSignature());
                            // We should only respond if we have the metadata file
                            if (metadataFile != null && metadataFile.exists()) {

                                PeerMessage peerMessage = peerMessageBySignature58.get(signature58);
                                Message message = peerMessage.message;
                                Peer peer = peerMessage.peer;

                                // We have the metadata file, so update requests map to reflect that we've sent it
                                Triple newEntry = new Triple<>(null, null, nowBySignature58.get(signature58));
                                arbitraryMetadataRequests.put(message.getId(), newEntry);

                                ArbitraryMetadataMessage arbitraryMetadataMessage = new ArbitraryMetadataMessage(entry.getValue().getSignature(), metadataFile);
                                arbitraryMetadataMessage.setId(message.getId());
                                if (!peer.sendMessage(arbitraryMetadataMessage)) {
                                    LOGGER.debug("Couldn't send metadata");
                                    continue;
                                }
                                LOGGER.debug("Sent metadata");

                                // Nothing left to do, so return to prevent any unnecessary forwarding from occurring
                                LOGGER.debug("No need for any forwarding because metadata request is fully served");
                            }

                        }
                    }
                } catch (DataException e) {
                    LOGGER.error(String.format("Repository issue while fetching arbitrary metadata"), e);
                }

                // We may need to forward this request on
                boolean isBlocked = (transactionDataList == null || ListUtils.isNameBlocked(transactionData.getName()));
                if (Settings.getInstance().isRelayModeEnabled() && !isBlocked) {
                    // In relay mode - so ask our other peers if they have it

                    PeerMessage peerMessage = peerMessageBySignature58.get(signature58);
                    GetArbitraryMetadataMessage getArbitraryMetadataMessage = (GetArbitraryMetadataMessage) peerMessage.message;
                    long requestTime = getArbitraryMetadataMessage.getRequestTime();
                    int requestHops = getArbitraryMetadataMessage.getRequestHops() + 1;
                    long totalRequestTime = nowBySignature58.get(signature58) - requestTime;

                    if (totalRequestTime < RELAY_REQUEST_MAX_DURATION) {
                        // Relay request hasn't timed out yet, so can potentially be rebroadcast
                        if (requestHops < RELAY_REQUEST_MAX_HOPS) {
                            // Relay request hasn't reached the maximum number of hops yet, so can be rebroadcast

                            byte[] signature = signatureBySignature58.get(signature58);
                            Message relayGetArbitraryMetadataMessage = new GetArbitraryMetadataMessage(signature, requestTime, requestHops);
                            relayGetArbitraryMetadataMessage.setId(getArbitraryMetadataMessage.getId());

                            Peer peer = peerMessage.peer;
                            LOGGER.debug("Rebroadcasting metadata request from peer {} for signature {} to our other peers... totalRequestTime: {}, requestHops: {}", peer, Base58.encode(signature), totalRequestTime, requestHops);
                            Network.getInstance().broadcast(
                                    broadcastPeer ->
                                            !broadcastPeer.isAtLeastVersion(RELAY_MIN_PEER_VERSION) ? null :
                                                    broadcastPeer == peer || Objects.equals(broadcastPeer.getPeerData().getAddress().getHost(), peer.getPeerData().getAddress().getHost()) ? null : relayGetArbitraryMetadataMessage);

                        } else {
                            // This relay request has reached the maximum number of allowed hops
                        }
                    } else {
                        // This relay request has timed out
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }
}
