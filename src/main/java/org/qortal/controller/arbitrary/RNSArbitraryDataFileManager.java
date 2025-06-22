package org.qortal.controller.arbitrary;

import com.google.common.net.InetAddresses;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.RNSArbitraryDirectConnectionInfo;
import org.qortal.data.arbitrary.RNSArbitraryFileListResponseInfo;
import org.qortal.data.arbitrary.RNSArbitraryRelayInfo;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.RNSNetwork;
import org.qortal.network.RNSPeer;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class RNSArbitraryDataFileManager extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(RNSArbitraryDataFileManager.class);

    private static RNSArbitraryDataFileManager instance;
    private volatile boolean isStopping = false;


    /**
     * Map to keep track of our in progress (outgoing) arbitrary data file requests
     */
    public Map<String, Long> arbitraryDataFileRequests = Collections.synchronizedMap(new HashMap<>());

    /**
     * Map to keep track of hashes that we might need to relay
     */
    public final List<RNSArbitraryRelayInfo> arbitraryRelayMap = Collections.synchronizedList(new ArrayList<>());

    /**
     * List to keep track of any arbitrary data file hash responses
     */
    public final List<RNSArbitraryFileListResponseInfo> arbitraryDataFileHashResponses = Collections.synchronizedList(new ArrayList<>());

    /**
     * List to keep track of peers potentially available for direct connections, based on recent requests
     */
    private final List<RNSArbitraryDirectConnectionInfo> directConnectionInfo = Collections.synchronizedList(new ArrayList<>());

    /**
     * Map to keep track of peers requesting QDN data that we hold.
     * Key = peer address string, value = time of last request.
     * This allows for additional "burst" connections beyond existing limits.
     */
    private Map<String, Long> recentDataRequests = Collections.synchronizedMap(new HashMap<>());


    public static int MAX_FILE_HASH_RESPONSES = 1000;


    private RNSArbitraryDataFileManager() {
    }

    public static RNSArbitraryDataFileManager getInstance() {
        if (instance == null)
            instance = new RNSArbitraryDataFileManager();

        return instance;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Arbitrary Data File Manager");

        try {
            // Use a fixed thread pool to execute the arbitrary data file requests
            int threadCount = 5;
            ExecutorService arbitraryDataFileRequestExecutor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                arbitraryDataFileRequestExecutor.execute(new ArbitraryDataFileRequestThread());
            }

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
        this.interrupt();
    }


    public void cleanupRequestCache(Long now) {
        if (now == null) {
            return;
        }
        final long requestMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_REQUEST_TIMEOUT;
        arbitraryDataFileRequests.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < requestMinimumTimestamp);

        final long relayMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_RELAY_TIMEOUT;
        arbitraryRelayMap.removeIf(entry -> entry == null || entry.getTimestamp() == null || entry.getTimestamp() < relayMinimumTimestamp);
        arbitraryDataFileHashResponses.removeIf(entry -> entry.getTimestamp() < relayMinimumTimestamp);

        final long directConnectionInfoMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_DIRECT_CONNECTION_INFO_TIMEOUT;
        directConnectionInfo.removeIf(entry -> entry.getTimestamp() < directConnectionInfoMinimumTimestamp);

        final long recentDataRequestMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_RECENT_DATA_REQUESTS_TIMEOUT;
        recentDataRequests.entrySet().removeIf(entry -> entry.getValue() < recentDataRequestMinimumTimestamp);
    }



    // Fetch data files by hash

    public boolean fetchArbitraryDataFiles(Repository repository,
                                           RNSPeer peer,
                                           byte[] signature,
                                           ArbitraryTransactionData arbitraryTransactionData,
                                           List<byte[]> hashes) throws DataException {

        // Load data file(s)
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(arbitraryTransactionData);
        boolean receivedAtLeastOneFile = false;

        // Now fetch actual data from this peer
        for (byte[] hash : hashes) {
            if (isStopping) {
                return false;
            }
            String hash58 = Base58.encode(hash);
            if (!arbitraryDataFile.chunkExists(hash)) {
                // Only request the file if we aren't already requesting it from someone else
                if (!arbitraryDataFileRequests.containsKey(Base58.encode(hash))) {
                    LOGGER.debug("Requesting data file {} from peer {}", hash58, peer);
                    Long startTime = NTP.getTime();
                    ArbitraryDataFile receivedArbitraryDataFile = fetchArbitraryDataFile(peer, null, arbitraryTransactionData, signature, hash, null);
                    Long endTime = NTP.getTime();
                    if (receivedArbitraryDataFile != null) {
                        LOGGER.debug("Received data file {} from peer {}. Time taken: {} ms", receivedArbitraryDataFile.getHash58(), peer, (endTime-startTime));
                        receivedAtLeastOneFile = true;

                        // Remove this hash from arbitraryDataFileHashResponses now that we have received it
                        arbitraryDataFileHashResponses.remove(hash58);
                    }
                    else {
                        LOGGER.debug("Peer {} didn't respond with data file {} for signature {}. Time taken: {} ms", peer, Base58.encode(hash), Base58.encode(signature), (endTime-startTime));

                        // Remove this hash from arbitraryDataFileHashResponses now that we have failed to receive it
                        arbitraryDataFileHashResponses.remove(hash58);

                        // Stop asking for files from this peer
                        break;
                    }
                }
                else {
                    LOGGER.trace("Already requesting data file {} for signature {} from peer {}", arbitraryDataFile, Base58.encode(signature), peer);
                }
            }
            else {
                // Remove this hash from arbitraryDataFileHashResponses because we have a local copy
                arbitraryDataFileHashResponses.remove(hash58);
            }
        }

        if (receivedAtLeastOneFile) {
            // Invalidate the hosted transactions cache as we are now hosting something new
            ArbitraryDataStorageManager.getInstance().invalidateHostedTransactionsCache();

            // Check if we have all the files we need for this transaction
            if (arbitraryDataFile.allFilesExist()) {

                // We have all the chunks for this transaction, so we should invalidate the transaction's name's
                // data cache so that it is rebuilt the next time we serve it
                ArbitraryDataManager.getInstance().invalidateCache(arbitraryTransactionData);
            }
        }

        return receivedAtLeastOneFile;
    }

    private ArbitraryDataFile fetchArbitraryDataFile(RNSPeer peer, RNSPeer requestingPeer, ArbitraryTransactionData arbitraryTransactionData, byte[] signature, byte[] hash, Message originalMessage) throws DataException {
        ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(hash, signature);
        boolean fileAlreadyExists = existingFile.exists();
        String hash58 = Base58.encode(hash);
        ArbitraryDataFile arbitraryDataFile;

        // Fetch the file if it doesn't exist locally
        if (!fileAlreadyExists) {
            LOGGER.debug(String.format("Fetching data file %.8s from peer %s", hash58, peer));
            arbitraryDataFileRequests.put(hash58, NTP.getTime());
            Message getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(signature, hash);

            Message response = null;
            // TODO - revisit with RNS
            try {
                response = peer.getResponseWithTimeout(getArbitraryDataFileMessage, (int) ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT);
            } catch (InterruptedException e) {
                // Will return below due to null response
            }
            arbitraryDataFileRequests.remove(hash58);
            LOGGER.trace(String.format("Removed hash %.8s from arbitraryDataFileRequests", hash58));

            // We may need to remove the file list request, if we have all the files for this transaction
            this.handleFileListRequests(signature);

            if (response == null) {
                LOGGER.debug("Received null response from peer {}", peer);
                return null;
            }
            if (response.getType() != MessageType.ARBITRARY_DATA_FILE) {
                LOGGER.debug("Received response with invalid type: {} from peer {}", response.getType(), peer);
                return null;
            }

            ArbitraryDataFileMessage peersArbitraryDataFileMessage = (ArbitraryDataFileMessage) response;
            arbitraryDataFile = peersArbitraryDataFileMessage.getArbitraryDataFile();
        } else {
            LOGGER.debug(String.format("File hash %s already exists, so skipping the request", hash58));
            arbitraryDataFile = existingFile;
        }

        if (arbitraryDataFile == null) {
            // We don't have a file, so give up here
            return null;
        }

        // We might want to forward the request to the peer that originally requested it
        this.handleArbitraryDataFileForwarding(requestingPeer, new ArbitraryDataFileMessage(signature, arbitraryDataFile), originalMessage);

        boolean isRelayRequest = (requestingPeer != null);
        if (isRelayRequest) {
            if (!fileAlreadyExists) {
                // File didn't exist locally before the request, and it's a forwarding request, so delete it if it exists.
                // It shouldn't exist on the filesystem yet, but leaving this here just in case.
                arbitraryDataFile.delete(10);
            }
        }
        else {
            arbitraryDataFile.save();
        }

        // If this is a metadata file then we need to update the cache
        if (arbitraryTransactionData != null && arbitraryTransactionData.getMetadataHash() != null) {
            if (Arrays.equals(arbitraryTransactionData.getMetadataHash(), hash)) {
                ArbitraryDataCacheManager.getInstance().addToUpdateQueue(arbitraryTransactionData);
            }
        }

        return arbitraryDataFile;
    }

    private void handleFileListRequests(byte[] signature) {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Fetch the transaction data
            ArbitraryTransactionData arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);
            if (arbitraryTransactionData == null) {
                return;
            }

            boolean allChunksExist = ArbitraryTransactionUtils.allChunksExist(arbitraryTransactionData);

            if (allChunksExist) {
                // Update requests map to reflect that we've received all chunks
                RNSArbitraryDataFileListManager.getInstance().deleteFileListRequestsForSignature(signature);
            }

        } catch (DataException e) {
            LOGGER.debug("Unable to handle file list requests: {}", e.getMessage());
        }
    }

    public void handleArbitraryDataFileForwarding(RNSPeer requestingPeer, Message message, Message originalMessage) {
        // Return if there is no originally requesting peer to forward to
        if (requestingPeer == null) {
            return;
        }

        // Return if we're not in relay mode or if this request doesn't need forwarding
        if (!Settings.getInstance().isRelayModeEnabled()) {
            return;
        }

        LOGGER.debug("Received arbitrary data file - forwarding is needed");

        // The ID needs to match that of the original request
        message.setId(originalMessage.getId());

        //if (!requestingPeer.sendMessageWithTimeout(message, (int) ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT)) {
        //    LOGGER.debug("Failed to forward arbitrary data file to peer {}", requestingPeer);
        //    requestingPeer.disconnect("failed to forward arbitrary data file");
        //}
        //else {
        //    LOGGER.debug("Forwarded arbitrary data file to peer {}", requestingPeer);
        //}
        requestingPeer.sendMessageWithTimeout(message, (int) ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT);
    }


    // Fetch data directly from peers

    private List<RNSArbitraryDirectConnectionInfo> getDirectConnectionInfoForSignature(byte[] signature) {
        synchronized (directConnectionInfo) {
            return directConnectionInfo.stream().filter(i -> Arrays.equals(i.getSignature(), signature)).collect(Collectors.toList());
        }
    }

    /**
     * Add an ArbitraryDirectConnectionInfo item, but only if one with this peer-signature combination
     * doesn't already exist.
     * @param connectionInfo - the direct connection info to add
     */
    public void addDirectConnectionInfoIfUnique(RNSArbitraryDirectConnectionInfo connectionInfo) {
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

    private void removeDirectConnectionInfo(RNSArbitraryDirectConnectionInfo connectionInfo) {
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
                List<RNSArbitraryDirectConnectionInfo> connectionInfoList = getDirectConnectionInfoForSignature(signature);
                if (connectionInfoList == null || connectionInfoList.isEmpty()) {
                    LOGGER.debug("No remaining direct connection peers found for signature {}", signature58);
                    return false;
                }

                LOGGER.debug("Attempting a direct peer connection for signature {}...", signature58);

                // Peers found, so pick one with the highest number of chunks
                Comparator<RNSArbitraryDirectConnectionInfo> highestChunkCountFirstComparator =
                        Comparator.comparingInt(RNSArbitraryDirectConnectionInfo::getHashCount).reversed();
                RNSArbitraryDirectConnectionInfo directConnectionInfo = connectionInfoList.stream()
                        .sorted(highestChunkCountFirstComparator).findFirst().orElse(null);

                if (directConnectionInfo == null) {
                    return false;
                }

                // Remove from the list so that a different peer is tried next time
                removeDirectConnectionInfo(directConnectionInfo);

                //// TODO - rework this section (RNS network address?)
                //String peerAddressString = directConnectionInfo.getPeerAddress();
                //
                //// Parse the peer address to find the host and port
                //String host = null;
                //int port = -1;
                //String[] parts = peerAddressString.split(":");
                //if (parts.length > 1) {
                //    host = parts[0];
                //    port = Integer.parseInt(parts[1]);
                //} else {
                //    // Assume no port included
                //    host = peerAddressString;
                //    // Use default listen port
                //    port = Settings.getInstance().getDefaultListenPort();
                //}
                //
                //String peerAddressStringWithPort = String.format("%s:%d", host, port);
                //success = Network.getInstance().requestDataFromPeer(peerAddressStringWithPort, signature);
                //
                //int defaultPort = Settings.getInstance().getDefaultListenPort();
                //
                //// If unsuccessful, and using a non-standard port, try a second connection with the default listen port,
                //// since almost all nodes use that. This is a workaround to account for any ephemeral ports that may
                //// have made it into the dataset.
                //if (!success) {
                //    if (host != null && port > 0) {
                //        if (port != defaultPort) {
                //            String newPeerAddressString = String.format("%s:%d", host, defaultPort);
                //            success = Network.getInstance().requestDataFromPeer(newPeerAddressString, signature);
                //        }
                //    }
                //}
                //
                //// If _still_ unsuccessful, try matching the peer's IP address with some known peers, and then connect
                //// to each of those in turn until one succeeds.
                //if (!success) {
                //    if (host != null) {
                //        final String finalHost = host;
                //        List<PeerData> knownPeers = Network.getInstance().getAllKnownPeers().stream()
                //                .filter(knownPeerData -> knownPeerData.getAddress().getHost().equals(finalHost))
                //                .collect(Collectors.toList());
                //        // Loop through each match and attempt a connection
                //        for (PeerData matchingPeer : knownPeers) {
                //            String matchingPeerAddress = matchingPeer.getAddress().toString();
                //            int matchingPeerPort = matchingPeer.getAddress().getPort();
                //            // Make sure that it's not a port we've already tried
                //            if (matchingPeerPort != port && matchingPeerPort != defaultPort) {
                //                success = Network.getInstance().requestDataFromPeer(matchingPeerAddress, signature);
                //                if (success) {
                //                    // Successfully connected, so stop making connections
                //                    break;
                //                }
                //            }
                //        }
                //    }
                //}

                if (success) {
                    // We were able to connect with a peer, so track the request
                    RNSArbitraryDataFileListManager.getInstance().addToSignatureRequests(signature58, false, true);
                }

            }
        } catch (InterruptedException e) {
            // Do nothing
        }

        return success;
    }


    // Relays

    private List<RNSArbitraryRelayInfo> getRelayInfoListForHash(String hash58) {
        synchronized (arbitraryRelayMap) {
            return arbitraryRelayMap.stream()
                    .filter(relayInfo -> Objects.equals(relayInfo.getHash58(), hash58))
                    .collect(Collectors.toList());
        }
    }

    private RNSArbitraryRelayInfo getOptimalRelayInfoEntryForHash(String hash58) {
        LOGGER.trace("Fetching relay info for hash: {}", hash58);
        List<RNSArbitraryRelayInfo> relayInfoList = this.getRelayInfoListForHash(hash58);
        if (relayInfoList != null && !relayInfoList.isEmpty()) {

            // Remove any with null requestHops
            relayInfoList.removeIf(r -> r.getRequestHops() == null);

            // If list is now empty, then just return one at random
            if (relayInfoList.isEmpty()) {
                return this.getRandomRelayInfoEntryForHash(hash58);
            }

            // Sort by number of hops (lowest first)
            relayInfoList.sort(Comparator.comparingInt(RNSArbitraryRelayInfo::getRequestHops));

            // FUTURE: secondary sort by requestTime?

            RNSArbitraryRelayInfo relayInfo = relayInfoList.get(0);

            LOGGER.trace("Returning optimal relay info for hash: {} (requestHops {})", hash58, relayInfo.getRequestHops());
            return relayInfo;
        }
        LOGGER.trace("No relay info exists for hash: {}", hash58);
        return null;
    }

    private RNSArbitraryRelayInfo getRandomRelayInfoEntryForHash(String hash58) {
        LOGGER.trace("Fetching random relay info for hash: {}", hash58);
        List<RNSArbitraryRelayInfo> relayInfoList = this.getRelayInfoListForHash(hash58);
        if (relayInfoList != null && !relayInfoList.isEmpty()) {

            // Pick random item
            int index = new SecureRandom().nextInt(relayInfoList.size());
            LOGGER.trace("Returning random relay info for hash: {} (index {})", hash58, index);
            return relayInfoList.get(index);
        }
        LOGGER.trace("No relay info exists for hash: {}", hash58);
        return null;
    }

    public void addToRelayMap(RNSArbitraryRelayInfo newEntry) {
        if (newEntry == null || !newEntry.isValid()) {
            return;
        }

        // Remove existing entry for this peer if it exists, to renew the timestamp
        this.removeFromRelayMap(newEntry);

        // Re-add
        arbitraryRelayMap.add(newEntry);
        LOGGER.debug("Added entry to relay map: {}", newEntry);
    }

    private void removeFromRelayMap(RNSArbitraryRelayInfo entry) {
        arbitraryRelayMap.removeIf(relayInfo -> relayInfo.equals(entry));
    }


    // Peers requesting QDN data from us

    /**
     * Add an address string of a peer that is trying to request data from us.
     * @param peerAddress
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

    public void onNetworkGetArbitraryDataFileMessage(RNSPeer peer, Message message) {
        // Don't respond if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        GetArbitraryDataFileMessage getArbitraryDataFileMessage = (GetArbitraryDataFileMessage) message;
        byte[] hash = getArbitraryDataFileMessage.getHash();
        String hash58 = Base58.encode(hash);
        byte[] signature = getArbitraryDataFileMessage.getSignature();
        Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.incrementAndGet();

        LOGGER.debug("Received GetArbitraryDataFileMessage from peer {} for hash {}", peer, Base58.encode(hash));

        try {
            ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, signature);
            RNSArbitraryRelayInfo relayInfo = this.getOptimalRelayInfoEntryForHash(hash58);

            if (arbitraryDataFile.exists()) {
                LOGGER.trace("Hash {} exists", hash58);

                // We can serve the file directly as we already have it
                LOGGER.debug("Sending file {}...", arbitraryDataFile);
                ArbitraryDataFileMessage arbitraryDataFileMessage = new ArbitraryDataFileMessage(signature, arbitraryDataFile);
                arbitraryDataFileMessage.setId(message.getId());
                //if (!peer.sendMessageWithTimeout(arbitraryDataFileMessage, (int) ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT)) {
                //    LOGGER.debug("Couldn't send file {}", arbitraryDataFile);
                //    peer.disconnect("failed to send file");
                //}
                //else {
                //    LOGGER.debug("Sent file {}", arbitraryDataFile);
                //}
                peer.sendMessageWithTimeout(arbitraryDataFileMessage, (int) ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT);
            }
            //// TODO: rework (doesn't work with Reticulum)
            //else if (relayInfo != null) {
            //    LOGGER.debug("We have relay info for hash {}", Base58.encode(hash));
            //    // We need to ask this peer for the file
            //    Peer peerToAsk = relayInfo.getPeer();
            //    if (peerToAsk != null) {
            //
            //        // Forward the message to this peer
            //        LOGGER.debug("Asking peer {} for hash {}", peerToAsk, hash58);
            //        // No need to pass arbitraryTransactionData below because this is only used for metadata caching,
            //        // and metadata isn't retained when relaying.
            //        this.fetchArbitraryDataFile(peerToAsk, peer, null, signature, hash, message);
            //    }
            //    else {
            //        LOGGER.debug("Peer {} not found in relay info", peer);
            //    }
            //}
            else {
                LOGGER.debug("Hash {} doesn't exist and we don't have relay info", hash58);

                // We don't have this file
                Controller.getInstance().stats.getArbitraryDataFileMessageStats.unknownFiles.getAndIncrement();

                // Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
                LOGGER.debug(String.format("Sending 'file unknown' response to peer %s for GET_FILE request for unknown file %s", peer, arbitraryDataFile));

                //// Send generic 'unknown' message as it's very short
                //Message fileUnknownMessage = peer.getPeersVersion() >= GenericUnknownMessage.MINIMUM_PEER_VERSION
                //        ? new GenericUnknownMessage()
                //        : new BlockSummariesMessage(Collections.emptyList());
                //fileUnknownMessage.setId(message.getId());
                //if (!peer.sendMessage(fileUnknownMessage)) {
                //    LOGGER.debug("Couldn't sent file-unknown response");
                //    peer.disconnect("failed to send file-unknown response");
                //}
                //else {
                //    LOGGER.debug("Sent file-unknown response for file {}", arbitraryDataFile);
                //}
                Message fileUnknownMessage = new GenericUnknownMessage();
                peer.sendMessage(fileUnknownMessage);
            }
        }
        catch (DataException e) {
            LOGGER.debug("Unable to handle request for arbitrary data file: {}", hash58);
        }
    }

}
