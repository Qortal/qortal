package org.qortal.network;

import com.dosse.upnp.UPnP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.controller.arbitrary.ArbitraryDataFileListManager;
import org.qortal.controller.arbitrary.ArbitraryDataFileManager;
import org.qortal.controller.arbitrary.ArbitraryMetadataManager;
import org.qortal.crypto.Crypto;
import org.qortal.data.network.PeerData;
import org.qortal.network.message.*;
import org.qortal.network.task.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.ExecuteProduceConsume;
import org.qortal.utils.ExecuteProduceConsume.StatsSnapshot;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.channels.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// For managing arbitrary data between peers
public class NetworkData {
    private static final Logger LOGGER = LogManager.getLogger(NetworkData.class);

    // the maximum number of pending connections the operating system will allow to queue up for the server socket
    private static final int LISTEN_BACKLOG = 5;

    // How long before retrying after a connection failure, in milliseconds.
    private static final long CONNECT_FAILURE_BACKOFF = 2 * 60 * 1000L; // ms
    
    /**
     * How long to wait between connection attempts when isolated (no peers) and retrying backoff peers, in milliseconds.
     * This prevents hammering peers when the node has no connections.
     */
    private static final long ISOLATION_RETRY_INTERVAL = 60 * 1000L; // ms

    //  Maximum time allowed for handshake to complete, in milliseconds.
    private static final long HANDSHAKE_TIMEOUT = 60 * 1000L; // ms

    private static final byte[] MAINNET_MESSAGE_MAGIC = new byte[]{0x51, 0x4f, 0x52, 0x54}; // QORT
    private static final byte[] TESTNET_MESSAGE_MAGIC = new byte[]{0x71, 0x6f, 0x72, 0x54}; // qorT

    private static final long NETWORK_EPC_KEEPALIVE = 5L; // seconds

    private static final long DISCONNECTION_CHECK_INTERVAL = 180 * 1000L; // milliseconds - 3min

    // Generate our node keys / ID
    private final Ed25519PrivateKeyParameters edPrivateKeyParams = new Ed25519PrivateKeyParameters(new SecureRandom());
    private final Ed25519PublicKeyParameters edPublicKeyParams = edPrivateKeyParams.generatePublicKey();
    private final String ourNodeId = Crypto.toNodeAddress(edPublicKeyParams.getEncoded());
    public final static int MAX_NODEID_SIZE = 34;

    private final int maxMessageSize;
    private final int minOutboundPeers;
    private final int maxPeers;

    private long nextDisconnectionCheck = 0L;

    private final List<PeerData> allKnownPeers = new ArrayList<>();
    
    /**
     * Track whether the last peer selected was from the backoff list.
     * Used to determine retry interval when isolated.
     */
    private volatile boolean lastPeerWasFromBackoff = false;

    /**
     * Maintain a list for each subset of peers:
     * - A synchronizedList, to be modified when peers are added/removed
     */
    private final List<Peer> connectedPeers = Collections.synchronizedList(new ArrayList<>());
    private final List<Peer> handshakedPeers = Collections.synchronizedList(new ArrayList<>());
    private final List<Peer> outboundHandshakedPeers = Collections.synchronizedList(new ArrayList<>());

    //  Count threads per message type in order to enforce limits
    private final Map<MessageType, Integer> threadsPerMessageType = Collections.synchronizedMap(new HashMap<>());

    //  Keep track of total thread count, to warn when the thread pool is getting low
    private int totalThreadCount = 0;

    // * Thresholds at which to warn about the number of active threads
    private final int threadCountWarningThreshold = (int) (Settings.getInstance().getMaxNetworkThreadPoolSize() * 0.9f);
    private final Integer threadCountPerMessageTypeWarningThreshold = Settings.getInstance().getThreadCountPerMessageTypeWarningThreshold();

    private final List<PeerAddress> selfPeers = new ArrayList<>();

    /**
     * Track outbound connection failures by peer IP address.
     * Used to implement reachability fallback: if outbound to a peer keeps failing,
     * we allow inbound connections from them even if deterministic tie-breaking says we should be outbound.
     */
    private final Map<String, OutboundFailureInfo> outboundFailures = new ConcurrentHashMap<>();
    
    /**
     * Track outbound connection failures by peer nodeId (preferred, handles multiple nodes per IP).
     * Falls back to IP-based tracking if nodeId is not available (first-time connection).
     */
    private final Map<String, OutboundFailureInfo> outboundFailuresByNodeId = new ConcurrentHashMap<>();
    
    /**
     * Configuration for outbound failure tracking.
     * Allow inbound fallback after this many failures within the time window.
     */
    private static final int OUTBOUND_FAILURE_THRESHOLD = 3;
    private static final long OUTBOUND_FAILURE_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes

    /**
     * Tracks outbound connection failure history for a peer IP.
     */
    private static class OutboundFailureInfo {
        int failureCount = 0;
        long firstFailureTimestamp = 0;
        long lastFailureTimestamp = 0;
    }

    /**
     * Direction mismatch tracking: prevents immediate reconnect thrash when we disconnect
     * a peer for having the wrong connection direction. Tracks by nodeId (survives IP changes).
     * NetworkData uses MORE LENIENT parameters than Network (QDN can tolerate asymmetry better).
     */
    private final Map<String, DirectionMismatchInfo> directionMismatchByNodeId = new ConcurrentHashMap<>();
    
    /**
     * Cache mapping address → nodeId, learned from successful handshakes.
     * Used to look up nodeId before connecting, to check if we should skip due to direction mismatch.
     * Expires after 24 hours to prevent stale mappings.
     */
    private final Map<String, CachedNodeIdInfo> addressToNodeIdCache = new ConcurrentHashMap<>();
    
    /**
     * Configuration for direction mismatch tracking (NetworkData - more lenient than Network).
     * Exponential backoff: 5min base, up to 60min max (longer than Network's 2min/30min).
     */
    private static final long DIRECTION_MISMATCH_BASE_BACKOFF = 5 * 60 * 1000L; // 5 minutes
    private static final long DIRECTION_MISMATCH_MAX_BACKOFF = 60 * 60 * 1000L; // 60 minutes
    private static final long ADDRESS_CACHE_EXPIRY = 24 * 60 * 60 * 1000L; // 24 hours
    
    /**
     * Tracks direction mismatch history for a peer nodeId.
     * Uses exponential backoff to prevent both thrash and permanent blocking.
     */
    private static class DirectionMismatchInfo {
        int count = 0;
        long firstMismatch = 0;
        long lastMismatch = 0;
        
        long getBackoffDuration() {
            // Exponential backoff: 5min, 10min, 20min, 40min, capped at 60min
            return Math.min(DIRECTION_MISMATCH_BASE_BACKOFF * (1L << (count - 1)), 
                           DIRECTION_MISMATCH_MAX_BACKOFF);
        }
    }
    
    /**
     * Cached nodeId info with timestamp for expiry.
     */
    private static class CachedNodeIdInfo {
        String nodeId;
        long lastUpdated;
        
        CachedNodeIdInfo(String nodeId, long lastUpdated) {
            this.nodeId = nodeId;
            this.lastUpdated = lastUpdated;
        }
    }

    private String bindAddress = null;

    private final ExecuteProduceConsume networkDataEPC;
    
    /**
     * Dedicated thread pool for processing ARBITRARY_DATA_FILE messages.
     * This prevents chunk validation and disk I/O from blocking the NetworkProcessor
     * thread, which needs to quickly drain socket buffers via selector.select().
     * 
     * Pool size: 10 threads to handle multiple concurrent chunk writes without
     * blocking the network read loop.
     */
    private static final ExecutorService chunkProcessorPool = new ThreadPoolExecutor(
            5, // corePoolSize: maintain 5 threads for chunk processing
            20, // maximumPoolSize: scale up to 20 for burst traffic
            60L, TimeUnit.SECONDS, // keepAliveTime: idle threads die after 1 minute
            new LinkedBlockingQueue<>(100), // bounded queue to prevent memory bloat
            new NamedThreadFactory("ChunkProcessor", Thread.NORM_PRIORITY),
            new ThreadPoolExecutor.CallerRunsPolicy() // back-pressure: if queue full, caller processes
    );
    
    private Selector channelSelector;
    private ServerSocketChannel serverChannel;
    private SelectionKey serverSelectionKey;
    private final Set<SelectableChannel> channelsPendingWrite = ConcurrentHashMap.newKeySet();

    /**
     * Lock for atomic peer list operations to prevent race conditions.
     * Used to ensure peer additions/removals are atomic across both connectedPeers and handshakedPeers.
     */
    private final Object peerListsLock = new Object();

    private final List<String> ourExternalIpAddressHistory = new ArrayList<>();
    private String ourExternalIpAddress = null;
    private int ourExternalPort = Settings.getInstance().getQDNListenPort();
    private boolean canAcceptInbound = false; 
    private volatile boolean isShuttingDown = false;

    // Constructors
    private NetworkData() {
        maxMessageSize = 4 + 1 + 4 + BlockChain.getInstance().getMaxBlockSize();

        minOutboundPeers = Settings.getInstance().getMinOutboundPeers();
        maxPeers = Settings.getInstance().getMaxPeers();

        int networkDataPriority = Settings.getInstance().getNetworkThreadPriority();
        if (networkDataPriority > 1)
                networkDataPriority--;  // Create QDN with a lowerThread priority than the primary data

        // ToDo: Need to adjust the thread size based on max connections allowed 
        ExecutorService networkExecutor = new ThreadPoolExecutor(
                10, // corePoolSize: maintain 10 threads
                20, // maximumPoolSize
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), // Use an unbounded queue to hold excess tasks
                new NamedThreadFactory("NetworkData-EPC", networkDataPriority));

        networkDataEPC = new NetworkDataProcessor(networkExecutor);
    }

    public void start() throws IOException, DataException {
        LOGGER.trace("Running start()");
        // Grab QDN port from settings
        int listenPort = Settings.getInstance().getQDNListenPort();

        // Grab bind addresses from settings
        List<String> bindAddresses = new ArrayList<>();
        if (Settings.getInstance().getBindAddress() != null) {
            bindAddresses.add(Settings.getInstance().getBindAddress());
        }
        if (Settings.getInstance().getBindAddressFallback() != null) {
            bindAddresses.add(Settings.getInstance().getBindAddressFallback());
        }

        for (int i=0; i<bindAddresses.size(); i++) {
            try {
                String testBindAddress = bindAddresses.get(i);
                InetAddress bindAddr = InetAddress.getByName(testBindAddress);
                InetSocketAddress endpoint = new InetSocketAddress(bindAddr, listenPort);

                channelSelector = Selector.open();

                // Set up listen socket
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                serverChannel.bind(endpoint, LISTEN_BACKLOG);
                serverSelectionKey = serverChannel.register(channelSelector, SelectionKey.OP_ACCEPT);

                this.bindAddress = testBindAddress; // Store the selected address, so that it can be used by other parts of the app
                LOGGER.trace("Success - Bound to interface: {}:{}", this.bindAddress,listenPort);
                break; // We don't want to bind to more than one address
            } catch (UnknownHostException | UnsupportedAddressTypeException e) {
                LOGGER.error("Can't bind listen socket to address {}", Settings.getInstance().getBindAddress());
                if (i == bindAddresses.size()-1) { // Only throw an exception if all addresses have been tried
                    throw new IOException("Can't bind listen socket to address", e);
                }
            } catch (IOException e) {
                LOGGER.error("Can't create listen socket: {}", e.getMessage());
                if (i == bindAddresses.size()-1) { // Only throw an exception if all addresses have been tried
                    throw new IOException("Can't create listen socket", e);
                }
            }
        }

        // Attempt to set up UPnP for QDN. All errors are ignored.
        int qdnPort = Settings.getInstance().getQDNListenPort();
        if (Settings.getInstance().isUPnPEnabled()) {
            UPnP.openPortTCP(qdnPort);
            if (UPnP.isMappedTCP(qdnPort)) {
                this.ourExternalIpAddress = UPnP.getExternalAddress();
                LOGGER.info("UPnP Mapped for QDN, port: {}", qdnPort);
            } else {
                LOGGER.warn("Unable to map QDN port: {} with UPnP, port in use?", qdnPort);
            }
        }
        else {
            UPnP.closePortTCP(qdnPort);
        }

        // Start up first networking thread
        networkDataEPC.start();
    }

    // Getters / setters

    private static class SingletonContainer {
        private static final NetworkData INSTANCE = new NetworkData();
    }

    public Map<MessageType, Integer> getThreadsPerMessageType() {
        return this.threadsPerMessageType;
    }

    public int getTotalThreadCount() {
        synchronized (this) {
            return this.totalThreadCount;
        }
    }

    public static NetworkData getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public String getBindAddress() {
        return this.bindAddress;
    }

    public int getMaxPeers() {
        return this.maxPeers;
    }

    public byte[] getMessageMagic() {
        return Settings.getInstance().isTestNet() ? TESTNET_MESSAGE_MAGIC : MAINNET_MESSAGE_MAGIC;
    }

    public String getOurNodeId() {
        return this.ourNodeId;
    }

    protected byte[] getOurPublicKey() {
        return this.edPublicKeyParams.getEncoded();
    }

    /**
     * Maximum message size (bytes). Needs to be at least maximum block size + MAGIC + message type, etc.
     */
    protected int getMaxMessageSize() {
        return this.maxMessageSize;
    }

    // Outbound failure tracking for reachability fallback

    /**
     * Record an outbound connection failure.
     * Used to track when outbound connections to a peer are failing,
     * so we can allow inbound connections as a fallback.
     * Prefers tracking by nodeId (persistent across IP changes), falls back to IP.
     */
    public void recordOutboundFailure(String peerAddress, String nodeId) {
        // Track by nodeId if available (handles multiple nodes per IP)
        if (nodeId != null) {
            OutboundFailureInfo info = outboundFailuresByNodeId.computeIfAbsent(
                nodeId, k -> new OutboundFailureInfo()
            );
            synchronized (info) {
                if (info.firstFailureTimestamp == 0) {
                    info.firstFailureTimestamp = System.currentTimeMillis();
                }
                info.failureCount++;
                info.lastFailureTimestamp = System.currentTimeMillis();
            }
            LOGGER.debug("Recorded outbound failure #{} for nodeId {}", 
                info.failureCount, nodeId.substring(0, 8));
        } else {
            // Fallback: track by IP if nodeId unknown (first-time connection)
            String peerIP = PeerAddress.fromString(peerAddress).getHost();
            OutboundFailureInfo info = outboundFailures.computeIfAbsent(
                peerIP, k -> new OutboundFailureInfo()
            );
            synchronized (info) {
                if (info.firstFailureTimestamp == 0) {
                    info.firstFailureTimestamp = System.currentTimeMillis();
                }
                info.failureCount++;
                info.lastFailureTimestamp = System.currentTimeMillis();
            }
            LOGGER.debug("Recorded outbound failure #{} for IP {} (nodeId unknown)", 
                info.failureCount, peerIP);
        }
    }

    /**
     * Check if outbound connections to the given peer have been failing recently.
     * Returns true if there have been at least OUTBOUND_FAILURE_THRESHOLD failures
     * within the OUTBOUND_FAILURE_WINDOW_MS time window.
     * Prefers checking by nodeId, falls back to IP if nodeId unknown.
     */
    public boolean hasRecentOutboundFailures(String nodeId, String peerIP) {
        long now = System.currentTimeMillis();
        
        // Check by nodeId first (most accurate, handles multiple nodes per IP)
        if (nodeId != null) {
            OutboundFailureInfo info = outboundFailuresByNodeId.get(nodeId);
            if (info != null) {
                synchronized (info) {
                    if (now - info.lastFailureTimestamp > OUTBOUND_FAILURE_WINDOW_MS) {
                        outboundFailuresByNodeId.remove(nodeId);
                        return false;
                    }
                    return info.failureCount >= OUTBOUND_FAILURE_THRESHOLD;
                }
            }
        }
        
        // Fallback: check by IP (for first-time connections or cache miss)
        if (peerIP != null) {
            OutboundFailureInfo info = outboundFailures.get(peerIP);
            if (info != null) {
                synchronized (info) {
                    if (now - info.lastFailureTimestamp > OUTBOUND_FAILURE_WINDOW_MS) {
                        outboundFailures.remove(peerIP);
                        return false;
                    }
                    return info.failureCount >= OUTBOUND_FAILURE_THRESHOLD;
                }
            }
        }
        
        return false;
    }

    /**
     * Clear outbound failure records for the given peer.
     * Called when a connection is successfully established.
     * Clears both IP-based and nodeId-based tracking.
     */
    public void clearOutboundFailures(String peerIP, String nodeId) {
        // Clear IP-based tracking
        OutboundFailureInfo removed = outboundFailures.remove(peerIP);
        if (removed != null) {
            LOGGER.debug("Cleared outbound failures for peer IP {} (was {} failures)", 
                peerIP, removed.failureCount);
        }
        
        // Clear nodeId-based tracking
        if (nodeId != null) {
            OutboundFailureInfo removedById = outboundFailuresByNodeId.remove(nodeId);
            if (removedById != null) {
                LOGGER.debug("Cleared outbound failures for nodeId {} (was {} failures)", 
                    nodeId.substring(0, 8), removedById.failureCount);
            }
        }
    }

    /**
     * Periodically clean up stale outbound failure records to prevent memory accumulation.
     * Called from checkLongestConnection during prunePeers() (every 90 seconds).
     */
    private void cleanupStaleOutboundFailures() {
        if (outboundFailures.isEmpty() && outboundFailuresByNodeId.isEmpty()) {
            return;
        }
        
        long now = System.currentTimeMillis();
        int removed = 0;
        
        // Clean up IP-based failures
        var iterator = outboundFailures.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            OutboundFailureInfo info = entry.getValue();
            synchronized (info) {
                if ((now - info.lastFailureTimestamp) > OUTBOUND_FAILURE_WINDOW_MS) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        
        // Clean up nodeId-based failures
        var nodeIdIterator = outboundFailuresByNodeId.entrySet().iterator();
        while (nodeIdIterator.hasNext()) {
            var entry = nodeIdIterator.next();
            OutboundFailureInfo info = entry.getValue();
            synchronized (info) {
                if ((now - info.lastFailureTimestamp) > OUTBOUND_FAILURE_WINDOW_MS) {
                    nodeIdIterator.remove();
                    removed++;
                }
            }
        }
        
        if (removed > 0) {
            LOGGER.debug("Cleaned up {} stale outbound failure records", removed);
        }
    }

    // Direction mismatch tracking

    /**
     * Record that a peer was disconnected due to direction mismatch.
     * Tracks by nodeId (survives IP/port changes from UPnP, DHCP, etc).
     * Uses exponential backoff to prevent thrash while allowing eventual retry.
     */
    public void recordDirectionMismatch(String nodeId) {
        DirectionMismatchInfo info = directionMismatchByNodeId.computeIfAbsent(
            nodeId, k -> new DirectionMismatchInfo()
        );
        
        synchronized (info) {
            if (info.firstMismatch == 0) {
                info.firstMismatch = System.currentTimeMillis();
            }
            info.count++;
            info.lastMismatch = System.currentTimeMillis();
        }
        
        LOGGER.debug("Recorded direction mismatch #{} for nodeId {} - backoff: {}ms", 
                info.count, nodeId.substring(0, 8), info.getBackoffDuration());
    }

    /**
     * Check if a peer nodeId has a recent direction mismatch and should be skipped for outbound.
     * Returns true if within backoff period, false otherwise.
     */
    public boolean hasRecentDirectionMismatch(String nodeId) {
        DirectionMismatchInfo info = directionMismatchByNodeId.get(nodeId);
        if (info == null) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        long backoffDuration = info.getBackoffDuration();
        
        synchronized (info) {
            if (now - info.lastMismatch > backoffDuration) {
                // Backoff expired - clear it
                directionMismatchByNodeId.remove(nodeId);
                return false;
            }
            return true;
        }
    }

    /**
     * Clear direction mismatch record for the given peer nodeId.
     * Called when an inbound connection from this peer succeeds,
     * indicating they can reach us and we don't need to avoid them.
     */
    public void clearDirectionMismatch(String nodeId) {
        DirectionMismatchInfo removed = directionMismatchByNodeId.remove(nodeId);
        if (removed != null) {
            LOGGER.debug("Cleared direction mismatch for nodeId {} (was {} mismatches)", 
                    nodeId.substring(0, 8), removed.count);
        }
    }

    /**
     * Update the address → nodeId cache with a fresh mapping.
     * Called on every successful handshake to keep cache current.
     * Helps handle IP changes from DHCP/UPnP/VPN.
     */
    private void updateAddressToNodeIdCache(String address, String nodeId) {
        addressToNodeIdCache.put(address, new CachedNodeIdInfo(nodeId, System.currentTimeMillis()));
    }

    /**
     * Periodically clean up stale direction mismatch records and address cache.
     * Called from prunePeers.
     */
    private void cleanupStaleDirectionMismatches() {
        long now = System.currentTimeMillis();
        int removedMismatches = 0;
        int removedCache = 0;
        
        // Clean up expired mismatch records
        var mismatchIterator = directionMismatchByNodeId.entrySet().iterator();
        while (mismatchIterator.hasNext()) {
            var entry = mismatchIterator.next();
            DirectionMismatchInfo info = entry.getValue();
            synchronized (info) {
                if (now - info.lastMismatch > info.getBackoffDuration()) {
                    mismatchIterator.remove();
                    removedMismatches++;
                }
            }
        }
        
        // Clean up old address cache entries (24 hour expiry)
        var cacheIterator = addressToNodeIdCache.entrySet().iterator();
        while (cacheIterator.hasNext()) {
            var entry = cacheIterator.next();
            if (now - entry.getValue().lastUpdated > ADDRESS_CACHE_EXPIRY) {
                cacheIterator.remove();
                removedCache++;
            }
        }
        
        if (removedMismatches > 0 || removedCache > 0) {
            LOGGER.debug("Cleaned up {} stale direction mismatch records and {} stale cache entries", 
                    removedMismatches, removedCache);
        }
    }

    public StatsSnapshot getStatsSnapshot() {
        return this.networkDataEPC.getStatsSnapshot();
    }


    public List<PeerData> getAllKnownPeers() {
        synchronized (this.allKnownPeers) {
            return new ArrayList<>(this.allKnownPeers);
        }
    }

    public PeerList getImmutableConnectedPeers() {
        return new PeerList(this.connectedPeers);
    }


    public void addConnectedPeer(Peer peer) {
        // ATOMIC: Synchronize for consistency with removeConnectedPeer()
        synchronized (this.connectedPeers) {
            this.connectedPeers.add(peer);
        }
    }

    public void removeConnectedPeer(Peer peer) {
        // ATOMIC: Lock both lists to prevent race condition with onHandshakeCompleted
        // This ensures peer isn't added to handshakedPeers while being removed from connectedPeers
        synchronized (this.peerListsLock) {
            // Firstly remove from handshaked peers
            this.removeHandshakedPeer(peer);
            // CRITICAL: Use object identity (==), not equals()
            // Peer.equals() compares by address, which can fail to find the exact object
            synchronized (this.connectedPeers) {
                this.connectedPeers.removeIf(p -> p == peer);
            }
        }
    }

    public List<PeerAddress> getSelfPeers() {
        synchronized (this.selfPeers) {
            return new ArrayList<>(this.selfPeers);
        }
    }

    public Peer getPeerByPeerData(PeerData pd) {
        PeerList handshakedSnapshot = this.getImmutableHandshakedPeers();
        return handshakedSnapshot.get(pd);
    }

    public Peer getPeerByPeerAddress(PeerAddress pa) {
        PeerList handshakedSnapshot = this.getImmutableHandshakedPeers();
        return handshakedSnapshot.get(pa);
    }

    public boolean requestDataFromPeer(String peerAddressString, byte[] signature) {
        if (peerAddressString != null) {
            PeerAddress peerAddress = PeerAddress.fromString(peerAddressString);
            PeerData peerData; //= null;

            LOGGER.trace("Requesting data using NetworkData from {}", peerAddressString);
            // Reuse an existing PeerData instance if it's already in the known peers list
            synchronized (this.allKnownPeers) {
                peerData = this.allKnownPeers.stream()
                        .filter(knownPeerData -> knownPeerData.getAddress().equals(peerAddress))
                        .findFirst()
                        .orElse(null);
            }

            // When should we get a peer we don't know about?  We get our peers from the main NetWork
            if (peerData == null) {
                // Not a known peer, so we need to create one
                Long addedWhen =  NTP.getTime();
                String addedBy = "requestDataFromPeer";
                peerData = new PeerData(peerAddress, addedWhen, addedBy);
            }

            PeerList connectedSnapshot = this.getImmutableConnectedPeers();
            Peer connectedPeer = connectedSnapshot.get(peerAddress);

            boolean isConnected = (connectedPeer != null);
            boolean isHandshaked = this.getImmutableHandshakedPeers().contains(peerAddress);

            if (isConnected && isHandshaked) {
                // Already connected
                return this.requestDataFromConnectedPeer(connectedPeer, signature);
            }
            else {
                // We need to connect to this peer before we can request data
                try {
                    if (!isConnected) {
                        // Add this signature to the list of pending requests for this peer
                        LOGGER.debug("Making connection to peer {} to request files for signature {}...", peerAddressString, Base58.encode(signature));
                        Peer peer = new Peer(peerData, Peer.NETWORKDATA);
                        peer.setIsDataPeer(true);   // This is set when we make a connection
                        peer.addPendingSignatureRequest(signature);
                        return this.connectPeer(peer);
                        // If connection (and handshake) is successful, data will automatically be requested
                    }
                    else if (!isHandshaked) {
                        LOGGER.trace("Peer {} is connected but not handshaked. Not attempting a new connection.", peerAddress);
                        return false;
                    }

                } catch (InterruptedException e) {
                    LOGGER.warn("Interrupted when connecting to peer {}", peerAddress);
                    return false;
                }
            }
        }
        return false;
    }

    private boolean requestDataFromConnectedPeer(Peer connectedPeer, byte[] signature) {
        if (signature == null) {  // Nothing to do
            return false;
        }
        return ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(connectedPeer, signature);
    }

    /**
     * Returns list of connected peers that have completed handshaking.
     */
    public PeerList getImmutableHandshakedPeers() {
        // A new PeerList is created as a snapshot every time this is called
        return new PeerList(this.handshakedPeers);
    }

    public void addHandshakedPeer(Peer peer) {
        // ATOMIC: Synchronize for consistency with removeHandshakedPeer()
        synchronized (this.handshakedPeers) {
            this.handshakedPeers.add(peer);
        }
    
        // Also add to outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.addOutboundHandshakedPeer(peer);
        } else {
            // Only inbound connections prove we can accept inbound
            // Outbound connections only prove we can reach others, not that they can reach us
            this.canAcceptInbound = true; 
        }
    }

    public void removeHandshakedPeer(Peer peer) {
        // CRITICAL: Use object identity (==), not equals()
        // Peer.equals() compares by address, which can fail to find the exact object
        synchronized (this.handshakedPeers) {
            this.handshakedPeers.removeIf(p -> p == peer);
        }

        // Also remove from outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.removeOutboundHandshakedPeer(peer);
        }
    }

    /**
     * Returns list of peers we connected to that have completed handshaking.
     */
    public PeerList getImmutableOutboundHandshakedPeers() {
        // A new PeerList is created as a snapshot every time this is called
        return new PeerList(this.outboundHandshakedPeers);
    }

    public void addOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        // ATOMIC: Synchronize for consistency with removeOutboundHandshakedPeer()
        synchronized (this.outboundHandshakedPeers) {
            this.outboundHandshakedPeers.add(peer);
        }
    }

    public void removeOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        synchronized (this.outboundHandshakedPeers) {
            this.outboundHandshakedPeers.removeIf(p -> p == peer);
        }
    }

    /**
     * Returns first peer that has completed handshaking and has matching public key.
     * Searches handshakedPeers directly as the authoritative source for completed handshakes.
     */
    public Peer getHandshakedPeerWithPublicKey(byte[] publicKey) {
        // Search handshakedPeers directly - this is the authoritative list for completed handshakes
        return this.getImmutableHandshakedPeers().stream()
                .filter(peer -> Arrays.equals(peer.getPeersPublicKey(), publicKey))
                .findFirst().orElse(null);
    }

    // Peer list filters

    /**
     * Must be inside <tt>synchronized (this.selfPeers) {...}</tt>
     */
    private final Predicate<PeerData> isSelfPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.selfPeers.stream().anyMatch(selfPeer -> selfPeer.equals(peerAddress));
    };

    private final Predicate<PeerData> isConnectedPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.getImmutableConnectedPeers().stream().anyMatch(peer -> peer.getPeerData().getAddress().equals(peerAddress));
    };

    // private final Predicate<PeerData> isResolvedAsConnectedPeer = peerData -> {
    //     try {
    //         InetSocketAddress resolvedSocketAddress = peerData.getAddress().toSocketAddress();
    //         return this.getImmutableConnectedPeers().stream()
    //                 .anyMatch(peer -> peer.getResolvedAddress().equals(resolvedSocketAddress));
    //     } catch (UnknownHostException e) {
    //         // Can't resolve - no point even trying to connect
    //         return true;
    //     }
    // };

    // Main thread

    class NetworkDataProcessor extends ExecuteProduceConsume {

        /** Max MessageTasks produced in a row before forcing a channel task (select/read/write). Prevents starving I/O. */
        private static final int MAX_MESSAGE_TASKS_BEFORE_CHANNEL_TASK = 15;

        private final Logger LOGGER = LogManager.getLogger(NetworkDataProcessor.class);
        private final AtomicLong nextConnectTaskTimestamp = new AtomicLong(0L); // ms - try first connect once NTP syncs
        /** Number of MessageTasks produced since last channel task; used for fair scheduling. */
        private final AtomicInteger messageTasksSinceLastChannelTask = new AtomicInteger(0);
        private Iterator<SelectionKey> channelIterator = null;

        NetworkDataProcessor(ExecutorService executor) {
            super(executor);
        }

        @Override
        protected void onSpawnFailure() {
            // For debugging:
            // ExecutorDumper.dump(this.executor, 3, ExecuteProduceConsume.class);
        }

        public int[] getThreadPoolStats() {
            if (this.executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) this.executor;
                return new int[]{
                    tpe.getActiveCount(),
                    tpe.getQueue().size(),
                    tpe.getPoolSize()
                };
            }
            return null;
        }

        @Override
        protected Task produceTask(boolean canBlock) throws InterruptedException {
            Task task;

            // Fair scheduling: cap MessageTasks in a row so select() / ChannelReadTask / ChannelWriteTask get to run.
            // Otherwise under chunk flood we never run select(), TCP buffers fill, flow control stalls the download.
            if (messageTasksSinceLastChannelTask.get() >= MAX_MESSAGE_TASKS_BEFORE_CHANNEL_TASK) {
                messageTasksSinceLastChannelTask.set(0);
                task = null;
            } else {
                task = maybeProducePeerMessageTask();
                if (task != null) {
                    messageTasksSinceLastChannelTask.incrementAndGet();
                    return task;
                }
            }

            final Long now = NTP.getTime();

            // If it's a new peer we need to connect it on the data port
            task = maybeProduceConnectPeerTask(now);
            if (task != null) {
                return task;
            }

            // Run channel I/O (select then read or write). Reset so we can produce more MessageTasks next round.
            messageTasksSinceLastChannelTask.set(0);
            // Only this method can block to reduce CPU spin
            return maybeProduceChannelTask(canBlock);
        }

        private Task maybeProducePeerMessageTask() {
            return getImmutableConnectedPeers().stream()
                    .map(peer -> peer.getMessageTask(Peer.NETWORKDATA))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        private Task maybeProduceConnectPeerTask(Long now) throws InterruptedException {
            if(now == null) {
                return null;
            }
            if (now < nextConnectTaskTimestamp.get()) {
                return null;
            }

            if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
                LOGGER.debug("Not going to try to connect, .size() >= {}", minOutboundPeers);
                return null;
            }

            // Check if we're isolated (no peers at all, inbound or outbound)
            boolean hasNoPeers = getImmutableHandshakedPeers().isEmpty();
            
            // When isolated AND retrying backoff peers, use longer interval to avoid hammering
            if (hasNoPeers && lastPeerWasFromBackoff) {
                nextConnectTaskTimestamp.set(now + ISOLATION_RETRY_INTERVAL);
            } else {
                nextConnectTaskTimestamp.set(now + 3000L); // change from 1s to 3s, don't need to get data peers so aggressively
            }

            Peer targetPeer = getConnectablePeer(now);
            if (targetPeer == null) {
                return null;
            }
            targetPeer.setPeerType(Peer.NETWORKDATA);      // Make sure we set this to a NetworkData Type

            LOGGER.trace("Time to connect a Peer");
            // Create connection task
            return new PeerConnectTask(targetPeer);
        }

        private Task maybeProduceChannelTask(boolean canBlock) throws InterruptedException {
            // Synchronization here to enforce thread-safety on channelIterator
            synchronized (channelSelector) {
                // anything to do?
                if (channelIterator == null) {
                    try {
                        long selectStart = System.nanoTime();
                        int selectedCount;
                        if (canBlock) {
                            selectedCount = channelSelector.select(50L);  // Reduced from 1000L - wakeups control latency now
                        } else {
                            selectedCount = channelSelector.selectNow();
                        }
                        long selectTime = System.nanoTime() - selectStart;
                        
                        // Log if selector blocked for significant time (> 10ms) or selected many channels
                        if (selectTime > 10_000_000) { // > 10ms
                            LOGGER.trace("NetworkData selector blocked for {} ms, selected {} channels",
                                    selectTime / 1_000_000.0, selectedCount);
                        }
                    } catch (IOException e) {
                        LOGGER.warn("Channel selection threw IOException: {}", e.getMessage());
                        return null;
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }

                    channelIterator = channelSelector.selectedKeys().iterator();
                    LOGGER.trace("Thread {}, after {} select, channelIterator now {}",
                            Thread.currentThread().getId(),
                            canBlock ? "blocking": "non-blocking",
                            channelIterator);
                }

                if (!channelIterator.hasNext()) {
                    channelIterator = null; // Nothing to do so reset iterator to cause new select

                    LOGGER.trace("Thread {}, channelIterator now null", Thread.currentThread().getId());
                    return null;
                }

                final SelectionKey nextSelectionKey = channelIterator.next();
                channelIterator.remove();

                // Just in case underlying socket channel already closed elsewhere, etc.
                if (!nextSelectionKey.isValid())
                    return null;

                LOGGER.trace("Thread {}, nextSelectionKey {}", Thread.currentThread().getId(), nextSelectionKey);

                SelectableChannel socketChannel = nextSelectionKey.channel();

                try {
                    if (nextSelectionKey.isReadable()) {
                        clearInterestOps(nextSelectionKey, SelectionKey.OP_READ);
                        Peer peer = getPeerFromChannel((SocketChannel) socketChannel);
                        if (peer == null)
                            return null;
                        return new ChannelReadTask((SocketChannel) socketChannel, peer);
                    }

                    if (nextSelectionKey.isWritable()) {
                        clearInterestOps(nextSelectionKey, SelectionKey.OP_WRITE);

                        Peer peer = getPeerFromChannel((SocketChannel) socketChannel);
        
                        if (peer == null)
                            return null;

                        // Any thread that queues a message to send can set OP_WRITE,
                        // but we only allow one pending/active ChannelWriteTask per Peer
                        if (!channelsPendingWrite.add(socketChannel)) {
                            // Another ChannelWriteTask is already running for this channel.  
                            return null;
                        }   

                        return new ChannelWriteTask((SocketChannel) socketChannel, peer);
                    }

                    if (nextSelectionKey.isAcceptable()) {
                        clearInterestOps(nextSelectionKey, SelectionKey.OP_ACCEPT);
                      

                        // Need to pass in a reference to the network type
                        return new ChannelAcceptTask((ServerSocketChannel) socketChannel, Peer.NETWORKDATA);
                    }
                } catch (CancelledKeyException e) {
                    /*
                     * Sometimes nextSelectionKey is cancelled / becomes invalid between the isValid() test at line 586
                     * and later calls to isReadable() / isWritable() / isAcceptable() which themselves call isValid()!
                     * Those isXXXable() calls could throw CancelledKeyException, so we catch it here and return null.
                     */
                    return null;
                }
            }

            return null;
        }
    }

    /**
     * Repairs inconsistent peer state where a peer is in one list but not the other.
     * This can happen due to race conditions in duplicate connection handling during
     * handshake completion.
     * 
     * Two types of orphaned peers are detected:
     * 1. Peer in connectedPeers with COMPLETED status but not in handshakedPeers
     * 2. Peer in handshakedPeers but not in connectedPeers (invisible to API, can't sync data)
     */
    private void repairOrphanedPeers() {
        // Collect peers to disconnect outside the lock
        List<Peer> zombiesToDisconnect = new ArrayList<>();
        
        // Check 1: connectedPeers → handshakedPeers
        for (Peer peer : getImmutableConnectedPeers()) {
            // CRITICAL: Use object identity (==), not equals()
            // Peer.equals() compares by address, which can match different Peer objects
            // This caused zombies to go undetected when a different object with same address was in handshakedPeers
            boolean inHandshaked = getImmutableHandshakedPeers().stream()
                    .anyMatch(p -> p == peer);
            
            if (!inHandshaked) {
                // Peer is orphaned - in connectedPeers but not in handshakedPeers
                
                if (peer.getHandshakeStatus() == Handshake.COMPLETED) {
                    // ATOMIC: Lock to prevent disconnect during repair (double-check pattern)
                    synchronized (this.peerListsLock) {
                        // Recheck after acquiring lock - peer might have been removed
                        boolean stillInConnected = this.connectedPeers.stream().anyMatch(p -> p == peer);
                        boolean stillNotInHandshaked = !this.handshakedPeers.stream().anyMatch(p -> p == peer);
                        
                        if (stillInConnected && stillNotInHandshaked) {
                            // Normal case: handshake completed but peer missing from handshakedPeers
                            // This can happen due to race conditions in duplicate handling
                            LOGGER.debug("[{}] Repairing orphaned data peer {} - in connectedPeers with COMPLETED status but not in handshakedPeers",
                                    peer.getPeerConnectionId(), peer);
                            this.addHandshakedPeer(peer);
                        }
                    }
                } else {
                    // Zombie case: peer in connectedPeers but handshake status is not COMPLETED
                    // This is an inconsistent state that should never exist - the peer was either:
                    // 1. Removed from handshakedPeers but status was corrupted
                    // 2. Never properly completed handshake but stayed in connectedPeers
                    // 3. Had its status reset by a bug
                    // Collect for disconnect outside lock to avoid holding lock during cleanup
                    LOGGER.debug("[{}] Detected zombie data peer {} - in connectedPeers but not in handshakedPeers (status={}, age={}ms)",
                            peer.getPeerConnectionId(), peer, peer.getHandshakeStatus(), peer.getConnectionAge());
                    zombiesToDisconnect.add(peer);
                }
            }
        }
        
        // Disconnect zombies outside the lock
        for (Peer zombie : zombiesToDisconnect) {
            zombie.disconnect("zombie peer - inconsistent state");
        }
        
        // Check 2: handshakedPeers → connectedPeers (reverse check)
        // This catches peers that are available for data transfer but invisible to management
        for (Peer peer : getImmutableHandshakedPeers()) {
            // CRITICAL: Use object identity (==), not equals()
            boolean inConnected = getImmutableConnectedPeers().stream()
                    .anyMatch(p -> p == peer);
            
            if (!inConnected) {
                // ATOMIC: Lock to prevent disconnect during repair (double-check pattern)
                synchronized (this.peerListsLock) {
                    // Recheck after acquiring lock - peer might have been removed
                    boolean stillInHandshaked = this.handshakedPeers.stream().anyMatch(p -> p == peer);
                    boolean stillNotInConnected = !this.connectedPeers.stream().anyMatch(p -> p == peer);
                    
                    if (stillInHandshaked && stillNotInConnected) {
                        // Peer is orphaned - in handshakedPeers but not in connectedPeers
                        // This causes the peer to be invisible to management and can prevent proper data sync
                        LOGGER.warn("[{}] Repairing orphaned data peer {} - in handshakedPeers but not in connectedPeers",
                                peer.getPeerConnectionId(), peer);
                        this.addConnectedPeer(peer);
                    }
                }
            }
        }
    }

    /**
     * Enforces the direction invariant: for a given nodeId, exactly one connection
     * should exist, and its direction must match the deterministic rule (lower nodeId
     * initiates outbound). This fixes zombies caused by simultaneous outbound connects
     * where both connections complete handshake before duplicate detection can run.
     */
    private void enforceDirectionInvariant() {
        // Guard against running during shutdown or not nodeId
        if (this.isShuttingDown || this.ourNodeId == null) {
            return;
        }
        
        // Group handshaked peers by their nodeId (reading from immutable snapshot)
        Map<String, List<Peer>> byNodeId = getImmutableHandshakedPeers().stream()
                .filter(p -> p.getPeersNodeId() != null)
                .collect(Collectors.groupingBy(Peer::getPeersNodeId));
        
        // Grace period before enforcing direction on single connections
        // NetworkData: 30 minutes (much longer than Network's 2 minutes)
        // QDN data transfer can tolerate asymmetry better than consensus
        final long DIRECTION_GRACE_PERIOD = 30 * 60 * 1000L; // 30 minutes
        
        // Collect disconnection decisions before executing them
        List<Peer> peersToDisconnect = new ArrayList<>();
        List<String> disconnectReasons = new ArrayList<>();
        
        for (Map.Entry<String, List<Peer>> entry : byNodeId.entrySet()) {
            List<Peer> peers = entry.getValue();
            String theirNodeId = entry.getKey();
            boolean weShouldBeOutbound = ourNodeId.compareTo(theirNodeId) < 0;
            
            if (peers.size() == 1) {
                // Validate single connection for correct direction
                // Only enforce after grace period to avoid killing transient connections
                Peer peer = peers.get(0);
                if (peer.isOutbound() != weShouldBeOutbound 
                        && peer.getConnectionAge() > DIRECTION_GRACE_PERIOD) {
                    LOGGER.debug("[NetworkData: {}] Will disconnect single peer {} with wrong direction (outbound={}, shouldBeOutbound={}, age={}ms)",
                            peer.getPeerConnectionId(), peer.getPeerData().getAddress(),
                            peer.isOutbound(), weShouldBeOutbound, peer.getConnectionAge());
                    
                    // Record direction mismatch if WE initiated (outbound) - prevents immediate reconnect thrash
                    if (peer.isOutbound()) {
                        try {
                            String peerAddress = peer.getPeerData().getAddress().toString();
                            recordDirectionMismatch(theirNodeId);
                            updateAddressToNodeIdCache(peerAddress, theirNodeId);
                        } catch (Exception e) {
                            LOGGER.debug("Failed to record direction mismatch: {}", e.getMessage());
                        }
                    }
                    
                    peersToDisconnect.add(peer);
                    disconnectReasons.add("direction incorrect - single connection");
                }
            } else if (peers.size() > 1) {
                // Multiple connections - keep the correctly-directed one
                Peer correctPeer = peers.stream()
                        .filter(p -> p.isOutbound() == weShouldBeOutbound)
                        .findFirst()
                        .orElse(null);
                
                // If no correct-direction peer exists, keep the oldest established connection
                if (correctPeer == null) {
                    correctPeer = peers.stream()
                            .min(Comparator.comparingLong(Peer::getConnectionEstablishedTime))
                            .orElse(peers.get(0));
                    LOGGER.warn("[NetworkData] No correct-direction peer found for nodeId {}, keeping oldest peer {}",
                            theirNodeId, correctPeer);
                }
                
                // Collect peers to disconnect (all except the correct one)
                for (Peer p : peers) {
                    if (p != correctPeer) {
                        LOGGER.debug("[NetworkData: {}] Will disconnect direction-incorrect peer {} (outbound={}, shouldBeOutbound={}, correctPeer={})",
                                p.getPeerConnectionId(), p.getPeerData().getAddress(), 
                                p.isOutbound(), weShouldBeOutbound, correctPeer.getPeerConnectionId());
                        
                        // Record direction mismatch if WE initiated (outbound) - prevents immediate reconnect thrash
                        if (p.isOutbound()) {
                            try {
                                String peerAddress = p.getPeerData().getAddress().toString();
                                recordDirectionMismatch(theirNodeId);
                                updateAddressToNodeIdCache(peerAddress, theirNodeId);
                            } catch (Exception e) {
                                LOGGER.debug("Failed to record direction mismatch: {}", e.getMessage());
                            }
                        }
                        
                        peersToDisconnect.add(p);
                        disconnectReasons.add("direction invariant violation");
                    }
                }
            }
        }
        
        // Execute all disconnections
        for (int i = 0; i < peersToDisconnect.size(); i++) {
            peersToDisconnect.get(i).disconnect(disconnectReasons.get(i));
        }
    }

    private Peer getConnectablePeer(final Long now) throws InterruptedException {
        List<PeerData> peers = this.getAllKnownPeers();
            
        // Fallback: If NetworkData has no peers, try to get peers from Network
        // Only use peers that actually advertise QDN capability
        if (peers.isEmpty()) {
            try {
                Network network = Network.getInstance();
                if (network != null) {
                    // Get connected peers with capabilities, not just known addresses
                    List<Peer> connectedNetworkPeers = network.getImmutableHandshakedPeers();
                    if (!connectedNetworkPeers.isEmpty()) {
                        Long addedWhen = NTP.getTime();
                        String addedBy = "Network-fallback";
                        int peersAdded = 0;
                        
                        // Only use peers that advertise QDN capability
                        for (Peer networkPeer : connectedNetworkPeers) {
                            Object qdnCapability = networkPeer.getPeerCapability("QDN");
                            
                            // Skip peers without QDN capability
                            if (qdnCapability == null) {
                                continue;
                            }
                            
                            // Get the actual QDN port from peer's capability
                            int qdnPort;
                            try {
                                if (qdnCapability instanceof Integer) {
                                    qdnPort = (Integer) qdnCapability;
                                } else if (qdnCapability instanceof Long) {
                                    qdnPort = ((Long) qdnCapability).intValue();
                                } else {
                                    LOGGER.debug("Peer {} has invalid QDN capability type: {}", 
                                            networkPeer.getPeerData().getAddress(), qdnCapability.getClass());
                                    continue;
                                }
                            } catch (Exception e) {
                                LOGGER.debug("Failed to parse QDN port for peer {}: {}", 
                                        networkPeer.getPeerData().getAddress(), e.getMessage());
                                continue;
                            }
                            
                            String host = networkPeer.getPeerData().getAddress().getHost();
                            String qdnAddress = host + ":" + qdnPort;
                            PeerAddress qdnPeerAddress = PeerAddress.fromString(qdnAddress);
                            PeerData qdnPeerData = new PeerData(
                                qdnPeerAddress,
                                null,  // lastAttempted - not attempted yet
                                null,  // lastConnected - not connected yet
                                null,  // lastMisbehaved
                                addedWhen,
                                addedBy
                            );
                            peers.add(qdnPeerData);
                            peersAdded++;
                        }
                        
                        // Also add to our known peers list for future use
                        if (peersAdded > 0) {
                            synchronized (this.allKnownPeers) {
                                for (PeerData qdnPeer : peers) {
                                    // Check if already exists
                                    boolean alreadyExists = this.allKnownPeers.stream()
                                        .anyMatch(pd -> pd.getAddress().equals(qdnPeer.getAddress()));
                                    if (!alreadyExists) {
                                        this.allKnownPeers.add(qdnPeer);
                                    }
                                }
                            }
                            
                            LOGGER.trace("NetworkData had no peers - using {} QDN-capable peer(s) from Network as fallback", peersAdded);
                        } else {
                            LOGGER.debug("NetworkData had no peers and no Network peers advertise QDN capability");
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to get peers from Network fallback: {}", e.getMessage());
            }
        }
        
        if (peers.isEmpty()) {
            return null;
        }
        
    
        
        // Check if we have any handshaked peers (inbound or outbound) - are we isolated?
        boolean hasNoPeers = getImmutableHandshakedPeers().isEmpty();

        // Don't consider peers with recent connection failures
        final long lastAttemptedThreshold = now - CONNECT_FAILURE_BACKOFF;
        
        // Save peers in backoff for later consideration if we're isolated
        List<PeerData> peersInBackoff = new ArrayList<>();
        if (hasNoPeers) {
            peersInBackoff = peers.stream()
                .filter(peerData -> peerData.getLastAttempted() != null
                    && (peerData.getLastConnected() == null
                    || peerData.getLastConnected() < peerData.getLastAttempted())
                    && peerData.getLastAttempted() > lastAttemptedThreshold)
                .collect(Collectors.toList());
        }

        peers.removeIf(peerData -> peerData.getLastAttempted() != null
            && (peerData.getLastConnected() == null ));

        peers.removeIf(peerData ->
            peerData.getLastAttempted() != null
            && peerData.getLastConnected() != null
            && peerData.getLastConnected() < peerData.getLastAttempted()
            && peerData.getLastAttempted() > lastAttemptedThreshold);

        // Don't consider peers that we know loop back to self
        synchronized (this.selfPeers) {
            peers.removeIf(isSelfPeer);
        }

        // Don't consider already connected peers (simple address match)
        peers.removeIf(isConnectedPeer);

        // Don't consider peers we're already connected to by nodeId
        // This handles cases where we have an inbound connection on an ephemeral port
        // but allKnownPeers has the listen port (common when peer is added from Network)
        peers.removeIf(peerData -> {
            String peerAddress = peerData.getAddress().toString();
            CachedNodeIdInfo cachedInfo = addressToNodeIdCache.get(peerAddress);
            
            if (cachedInfo != null) {
                // We know this peer's nodeId - check if already connected
                String candidateNodeId = cachedInfo.nodeId;
                boolean alreadyConnected = this.getImmutableConnectedPeers().stream()
                        .anyMatch(peer -> peer.getPeersNodeId() != null 
                                && peer.getPeersNodeId().equals(candidateNodeId));
                
                if (alreadyConnected) {
                    LOGGER.debug("Skipping peer {} (nodeId {}) - already connected",
                            peerAddress, candidateNodeId.substring(0, 8));
                    return true;
                }
            }    
            return false;
        });

        // Don't consider peers with recent direction mismatches
        // NetworkData: no fixed peer exemption (QDN doesn't have fixed bootstrap nodes)
        peers.removeIf(peerData -> {
            // Try to resolve address to nodeId using cache
            String peerAddress = peerData.getAddress().toString();
            CachedNodeIdInfo cachedInfo = addressToNodeIdCache.get(peerAddress);
            
            if (cachedInfo != null) {
                // We know this peer's nodeId from previous handshake
                boolean shouldSkip = hasRecentDirectionMismatch(cachedInfo.nodeId);
                if (shouldSkip) {
                    LOGGER.debug("Skipping peer {} (nodeId {}) due to recent direction mismatch",
                            peerAddress, cachedInfo.nodeId.substring(0, 8));
                }
                return shouldSkip;
            }
            
            // No cached nodeId - can't determine if mismatch, allow connection
            // (First-time connection, or cache expired)
            return false;
        });

        // If we have no available peers but have peers in backoff, and we're isolated, retry them
        // Being isolated is worse than retrying a peer that might still be down
        if (peers.isEmpty() && !peersInBackoff.isEmpty() && hasNoPeers) {
            // Filter out self and connected from backoff list
            synchronized (this.selfPeers) {
                peersInBackoff.removeIf(isSelfPeer);
            }
            peersInBackoff.removeIf(isConnectedPeer);
            
            if (!peersInBackoff.isEmpty()) {
                peers = peersInBackoff;
                lastPeerWasFromBackoff = true;
                LOGGER.debug("No connected data peers - retrying {} peer(s) in backoff period", peers.size());
            }
        } else {
            lastPeerWasFromBackoff = false;
        }

        // Any left?
        if (peers.isEmpty()) {
            if (hasNoPeers) {
                LOGGER.warn("Isolated node: No connectable data peers found!");
            }
            return null;
        }

        // Pick random peer
        int peerIndex = new Random().nextInt(peers.size());

        // Pick candidate
        PeerData peerData = peers.get(peerIndex);
        Peer newPeer = new Peer(peerData, Peer.NETWORKDATA);
        newPeer.setIsDataPeer(true);

        // Update connection attempt info
        peerData.setLastAttempted(now);
        return newPeer;
    }

    public boolean connectPeer(Peer newPeer) throws InterruptedException {
        // Also checked before creating PeerConnectTask
        if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
            return false;
        }

        SocketChannel socketChannel = newPeer.connect(Peer.NETWORKDATA);
        if (socketChannel == null) {
            // Record outbound failure for reachability fallback
            try {
                String peerAddress = newPeer.getPeerData().getAddress().toString();
                
                // Try to get nodeId from cache for more accurate tracking
                String nodeId = null;
                CachedNodeIdInfo cachedInfo = addressToNodeIdCache.get(peerAddress);
                if (cachedInfo != null) {
                    nodeId = cachedInfo.nodeId;
                }
                
                recordOutboundFailure(peerAddress, nodeId);
            } catch (Exception e) {
                LOGGER.debug("Failed to record outbound failure: {}", e.getMessage());
            }
            return false;
        }

        if (Thread.currentThread().isInterrupted()) {
            LOGGER.debug("Thread is interrupted");
            return false;
        }

        this.addConnectedPeer(newPeer);
        this.onPeerReady(newPeer);

        return true;
    }

    /* Same as connectPeer except it ignores the max peer count */
    public boolean forceConnectPeer(Peer newPeer) {

        SocketChannel socketChannel = newPeer.connect(Peer.NETWORKDATA);
        if (socketChannel == null) {
            return false;
        }

        if (Thread.currentThread().isInterrupted()) {
            return false;
        }

        this.addConnectedPeer(newPeer);
        this.onPeerReady(newPeer);

        return true;
    }

    public Peer getPeerFromChannel(SocketChannel socketChannel) {
        for (Peer peer : this.getImmutableConnectedPeers()) {
            if (peer.getSocketChannel() == socketChannel) {
                return peer;
            }
        }
       
        return null;
    }

    private void checkLongestConnection(Long now) {
        if (now == null || now < nextDisconnectionCheck) {
            return;
        }

        // Find peers that have reached their maximum connection age, and disconnect them
        List<Peer> peersToDisconnect = this.getImmutableConnectedPeers().stream()
                .filter(peer -> !peer.isSyncInProgress())
                .filter(peer -> peer.hasReachedMaxConnectionAge())
                .collect(Collectors.toList());

        if (peersToDisconnect != null && !peersToDisconnect.isEmpty()) {
            for (Peer peer : peersToDisconnect) {
                LOGGER.debug("Forcing disconnection of peer {} because connection age ({} ms) " +
                        "has reached the maximum ({} ms)", peer, peer.getConnectionAge(), peer.getMaxConnectionAge());
                peer.disconnect("Connection age too old");
            }
        }

        // Clean up stale outbound failure records
        cleanupStaleOutboundFailures();

        // Check again after a minimum fixed interval
        nextDisconnectionCheck = now + DISCONNECTION_CHECK_INTERVAL;
    }

    // SocketChannel interest-ops manipulations

    private static final String[] OP_NAMES = new String[SelectionKey.OP_ACCEPT * 2];
    static {
        for (int i = 0; i < OP_NAMES.length; i++) {
            StringJoiner joiner = new StringJoiner(",");

            if ((i & SelectionKey.OP_READ) != 0) joiner.add("OP_READ");
            if ((i & SelectionKey.OP_WRITE) != 0) joiner.add("OP_WRITE");
            if ((i & SelectionKey.OP_CONNECT) != 0) joiner.add("OP_CONNECT");
            if ((i & SelectionKey.OP_ACCEPT) != 0) joiner.add("OP_ACCEPT");

            OP_NAMES[i] = joiner.toString();
        }
    }

    public void clearInterestOps(SelectableChannel socketChannel, int interestOps) {
        SelectionKey selectionKey = socketChannel.keyFor(channelSelector);
        if (selectionKey == null)
            return;

        clearInterestOps(selectionKey, interestOps);
    }

    private void clearInterestOps(SelectionKey selectionKey, int interestOps) {
        if (!selectionKey.channel().isOpen())
            return;

        LOGGER.trace("Thread {} clearing {} interest-ops on channel: {}",
                Thread.currentThread().getId(),
                OP_NAMES[interestOps],
                selectionKey.channel());

        selectionKey.interestOpsAnd(~interestOps);
    }

    public void setInterestOps(SelectableChannel socketChannel, int interestOps) {
        
        
        SelectionKey selectionKey = socketChannel.keyFor(channelSelector);

        if (selectionKey == null) {
            try {
                selectionKey = socketChannel.register(this.channelSelector, interestOps);
            } catch (ClosedChannelException e) {
                // Channel already closed so ignore
                LOGGER.trace("Failed to set interest ops on channel {} - channel already closed", socketChannel);
                return;
            } catch (Exception e) {
                LOGGER.trace("Failed to register channel {} for interest ops {}: {}", socketChannel, interestOps, e.getMessage());
                return;
            }
            // Fall-through to allow logging
        }

        try {
            setInterestOps(selectionKey, interestOps);
        } catch (Exception e) {
            LOGGER.trace("Failed to set interest ops {} on selection key for channel {}: {}", interestOps, socketChannel, e.getMessage());
        }
    }

    private void setInterestOps(SelectionKey selectionKey, int interestOps) {
        if (!selectionKey.isValid() || !selectionKey.channel().isOpen()) { // Added isValid()
            return;
        }

        LOGGER.trace("Thread {} setting {} interest-ops on channel: {}",
                Thread.currentThread().getId(),
                OP_NAMES[interestOps],
                selectionKey.channel());

        selectionKey.interestOpsOr(interestOps);
        
        // Wake selector immediately for write operations to avoid 50ms timeout delays that cascade
        // across multiple queued messages. Without this, 80+ chunks would wait 50ms each = 4+ seconds.
        // Real-world measurements showed 57-second delays for bulk chunk transfers.
        // Read operations can tolerate the natural selector wake cycle without performance impact.
        if (interestOps == SelectionKey.OP_WRITE) {
            channelSelector.wakeup();
            LOGGER.trace("Selector woken for OP_WRITE on channel {}", selectionKey.channel());
        }
    }

    // Peer / Task callbacks

    public void notifyChannelNotWriting(SelectableChannel socketChannel) {
        this.channelsPendingWrite.remove(socketChannel);
    }

    protected void wakeupChannelSelector() {
        this.channelSelector.wakeup();
    }

    /**
     * Wake up the selector immediately.
     * This is useful after re-arming OP_READ to avoid waiting for the selector timeout.
     */
    public void wakeSelector() {
        this.channelSelector.wakeup();
    }

    protected boolean verify(byte[] signature, byte[] message) {
        return Crypto.verify(this.edPublicKeyParams.getEncoded(), signature, message);
    }

    protected byte[] sign(byte[] message) {
        return Crypto.sign(this.edPrivateKeyParams, message);
    }

    protected byte[] getSharedSecret(byte[] publicKey) {
        return Crypto.getSharedSecret(this.edPrivateKeyParams.getEncoded(), publicKey);
    }

    /**
     * Called when Peer's thread has setup and is ready to process messages
     */
    public void onPeerReady(Peer peer) {
        onHandshakingMessage(peer, null, Handshake.STARTED);

    }

    public void onDisconnect(Peer peer) {
        if (peer.getConnectionEstablishedTime() > 0L) {
            LOGGER.debug("[{}] Disconnected from peer {}", peer.getPeerConnectionId(), peer);
        } else {
            LOGGER.debug("[{}] Failed to connect to peer {}", peer.getPeerConnectionId(), peer);
        }

        this.removeConnectedPeer(peer);
        this.channelsPendingWrite.remove(peer.getSocketChannel());
        
        // Clean up PeerSendManager immediately when peer disconnects
        // This prevents messages from being queued to a dead manager
        PeerSendManagement.getInstance().removeSendManager(peer);

        if (this.isShuttingDown)
            // No need to do any further processing, like re-enabling listen socket or notifying Controller
            return;

        if (getImmutableConnectedPeers().size() < maxPeers - 1
                && serverSelectionKey.isValid()
                && (serverSelectionKey.interestOps() & SelectionKey.OP_ACCEPT) == 0) {
            try {
                LOGGER.debug("Re-enabling accepting incoming connections because the server is no longer full");
                setInterestOps(serverSelectionKey, SelectionKey.OP_ACCEPT);
            } catch (CancelledKeyException e) {
                LOGGER.error("Failed to re-enable accepting of incoming connections: {}", e.getMessage());
            }
        }

        // Notify Controller
        Controller.getInstance().onPeerDisconnect(peer);
    }

    /**
     * Called when a new message arrives for a peer. message can be null if called after connection
     */
    public void onMessage(Peer peer, Message message) {
        if (message != null) {
            LOGGER.trace("[{}] Processing {} message with ID {} from peer {}", peer.getPeerConnectionId(),
                    message.getType().name(), message.getId(), peer);
        }

        Handshake handshakeStatus = peer.getHandshakeStatus();
        if (handshakeStatus != Handshake.COMPLETED) {
            LOGGER.trace("Calling onHandShakingMessage : {} : on {}", handshakeStatus.toString(), peer.getPeerType());
            onHandshakingMessage(peer, message, handshakeStatus);
            return;
        }

 

        // Warn if necessary
        if (threadCountPerMessageTypeWarningThreshold != null) {
            Integer threadCount = threadsPerMessageType.get(message.getType());
            if (threadCount != null && threadCount > threadCountPerMessageTypeWarningThreshold) {
                LOGGER.info("Warning: high thread count for {} message type: {}", message.getType().name(), threadCount);
            }
           
        }

        // Add to per-message thread count (first initializing to 0 if not already present)
        threadsPerMessageType.computeIfAbsent(message.getType(), key -> 0);
        threadsPerMessageType.computeIfPresent(message.getType(), (key, value) -> value + 1);
        
        // Add to total thread count
        synchronized (this) {
            totalThreadCount++;

            if (totalThreadCount >= threadCountWarningThreshold) {
                LOGGER.info("Warning: high total thread count: {} / {}", totalThreadCount, Settings.getInstance().getMaxNetworkThreadPoolSize());
            }
        }

        // Ordered by message type value
        switch (message.getType()) {

            case HELLO:
            case HELLO_V2:
            case CHALLENGE:
            case RESPONSE:
                LOGGER.debug("[{}] Unexpected handshaking message {} from peer {}", peer.getPeerConnectionId(),
                        message.getType().name(), peer);
                peer.disconnect("unexpected handshaking message");
                return;

            case ARBITRARY_DATA_FILE:
                ArbitraryDataFileMessage adfm = (ArbitraryDataFileMessage) message;
                ArbitraryDataFile adf = adfm.getArbitraryDataFile();

                // CRITICAL: Offload heavy processing (validation + disk I/O) to separate thread pool
                // to prevent blocking the NetworkProcessor thread, which needs to quickly return
                // to selector.select() to drain TCP buffers from all peers.
                // 
                // Without this, the NetworkProcessor thread blocks for 1200-1400ms per chunk,
                // causing other peers' data to pile up in TCP receive buffers (saw 2.3 MB backlog),
                // resulting in 50-80 second apparent "RTT" (actually just queue wait time).
                final Peer finalPeer = peer;
                chunkProcessorPool.execute(() -> {
                    try {
                        ArbitraryDataFileManager.getInstance().receivedArbitraryDataFile(finalPeer, adf);
                    } catch (Exception e) {
                        LOGGER.error("Error processing chunk {} from peer {}", adf.getHash58(), finalPeer, e);
                    }
                });
                break;

			case ARBITRARY_DATA_FILE_LIST:
				ArbitraryDataFileListManager.getInstance().onNetworkArbitraryDataFileListMessage(peer, message);
				break;

			case GET_ARBITRARY_DATA_FILE:
				ArbitraryDataFileManager.getInstance().onNetworkGetArbitraryDataFileMessage(peer, message);
				break;

            case GET_ARBITRARY_DATA_FILE_LIST:
                ArbitraryDataFileListManager.getInstance().onNetworkGetArbitraryDataFileListMessage(peer, message);
                break;

			case GET_ARBITRARY_METADATA:
				ArbitraryMetadataManager.getInstance().onNetworkGetArbitraryMetadataMessage(peer, message);
				break;

			case ARBITRARY_METADATA:
				ArbitraryMetadataManager.getInstance().onNetworkArbitraryMetadataMessage(peer, message);
				break;
            default:
                // Bump up to controller for possible action
                Controller.getInstance().onNetworkMessage(peer, message);
                break;
        }

        // Remove from per-message thread count (first initializing to 0 if not already present)
        threadsPerMessageType.computeIfAbsent(message.getType(), key -> 0);
        threadsPerMessageType.computeIfPresent(message.getType(), (key, value) -> value - 1);

        // Remove from total thread count
        synchronized (this) {
            totalThreadCount--;
        }
    }

    private void onHandshakingMessage(Peer peer, Message message, Handshake handshakeStatus) {
        try {
            LOGGER.trace("[NetworkData: {}] Handshake status {}, message {} from peer {} isOutbound {}",
                    peer.getPeerConnectionId(),
                    handshakeStatus != null ? handshakeStatus.name() : "null",
                    (message != null ? message.getType().name() : "null"),
                    peer,
                    peer.isOutbound());
    
            // Initial outbound handshake kick-off calls into here with message == null (STARTED).
            // Don't touch message.getType() in that case; just advance state and perform the action.
            if (message == null) {
                Handshake newHandshakeStatus = handshakeStatus.onMessage(peer, null);
    
                if (newHandshakeStatus == null) {
                    peer.disconnect("handshake failure");
                    return;
                }
    
                // Ensure this peer is marked as NETWORKDATA
                peer.setPeerType(Peer.NETWORKDATA);
    
                if (peer.isOutbound()) {
                    newHandshakeStatus.action(peer);
                }
    
                peer.setHandshakeStatus(newHandshakeStatus);
    
                // Do NOT call onHandshakeCompleted() here.
                // Completion requires RESPONSE validation + our RESPONSE sent (PoW thread).
                return;
            }
    
            // HELLO / HELLO_V2 can arrive out-of-order during handshake (side-band updates).
            // Don't tear down the connection because of them.
            if (message.getType() == MessageType.HELLO_V2
                    && handshakeStatus != Handshake.HELLO
                    && handshakeStatus != Handshake.HELLO_V2) {
                Handshake.HELLO_V2.onMessage(peer, message);
                return;
            }
    
            if (message.getType() == MessageType.HELLO
                    && handshakeStatus != Handshake.HELLO) {
                Handshake.HELLO.onMessage(peer, message);
                return;
            }
    
            Handshake effectiveHandshakeStatus = handshakeStatus;
    
            // If peer sends CHALLENGE early (while we're still in HELLO/HELLO_V2), handle it as CHALLENGE.
           // If peer sends CHALLENGE early (while we're still in HELLO/HELLO_V2), handle it as CHALLENGE.
            if ((handshakeStatus == Handshake.HELLO || handshakeStatus == Handshake.HELLO_V2)
                && message.getType() == MessageType.CHALLENGE) {
            effectiveHandshakeStatus = Handshake.CHALLENGE;
            }

            // If peer sends RESPONSE early (while we're still in CHALLENGE), handle it as RESPONSE.
            if (handshakeStatus == Handshake.CHALLENGE
                && message.getType() == MessageType.RESPONSE) {
            effectiveHandshakeStatus = Handshake.RESPONSE;
            }

    
            // Check message type is as expected
            boolean unexpectedMessage = effectiveHandshakeStatus.expectedMessageType != null
                    && message.getType() != effectiveHandshakeStatus.expectedMessageType;
    
            // HELLO accepts HELLO or HELLO_V2
            if (effectiveHandshakeStatus == Handshake.HELLO
                    && (message.getType() == MessageType.HELLO || message.getType() == MessageType.HELLO_V2)) {
                unexpectedMessage = false;
            }
    
            if (unexpectedMessage) {
                LOGGER.debug("[{}] Unexpected {} message from {}, expected {}",
                        peer.getPeerConnectionId(),
                        message.getType().name(),
                        peer,
                        effectiveHandshakeStatus.expectedMessageType);
                peer.disconnect("unexpected message");
                return;
            }
    
            Handshake newHandshakeStatus = effectiveHandshakeStatus.onMessage(peer, message);
    
            if (newHandshakeStatus == null) {
                LOGGER.debug("[{}] Handshake failure with peer {} message {}",
                        peer.getPeerConnectionId(), peer, message.getType().name());
                peer.disconnect("handshake failure");
                return;
            }
    
            // Ensure this peer is marked as NETWORKDATA
            peer.setPeerType(Peer.NETWORKDATA);
    
            // Perform actions (send responses)
            if (peer.isOutbound()) {
                // Outbound: act first for the NEXT state
                newHandshakeStatus.action(peer);
            } else {
                // Inbound: respond "in kind"
                // Special case: HELLO -> HELLO_V2 transition, call CURRENT state's action
                // Also skip RESPONDING because it's just a holding state while PoW runs.
                if (newHandshakeStatus == Handshake.HELLO_V2) {
                    handshakeStatus.action(peer);
                } else if (newHandshakeStatus != Handshake.RESPONDING) {
                    newHandshakeStatus.action(peer);
                }
            }
    
            // Note: RESPONSE.onMessage() always returns RESPONDING now.
            // Completion is handled by tryCompleteHandshake() which is called from:
            // - RESPONSE.onMessage() after setting handshakeResponseValidated = true (RX side)
            // - RESPONSE.action() after setting handshakeResponseSent = true (TX side)
            // Whichever thread completes second will trigger the actual completion.
            peer.setHandshakeStatus(newHandshakeStatus);
    
        } finally {
            peer.resetHandshakeMessagePending();
        }
    }
    
    protected void onHandshakeCompleted(Peer peer) {
        LOGGER.trace("[NetworkData: {}] Handshake completed with peer {} on {}", peer.getPeerConnectionId(), peer,
                peer.getPeersVersionString());

        // Clear any outbound failure records for this peer's IP since connection succeeded
        // Also update address→nodeId cache and clear direction mismatch for inbound
        try {
            if (peer.getResolvedAddress() != null && peer.getPeersNodeId() != null) {
                String peerIP = peer.getResolvedAddress().getAddress().getHostAddress();
                int peerPort = peer.getResolvedAddress().getPort();
                String peerAddress = peerIP + ":" + peerPort;
                String theirNodeId = peer.getPeersNodeId();
                
                // Keep cache updated with latest address for this nodeId
                // Handles IP changes from DHCP/UPnP/VPN
                updateAddressToNodeIdCache(peerAddress, theirNodeId);
                
                clearOutboundFailures(peerIP, theirNodeId);
                
                // Clear direction mismatch if inbound succeeds
                // (They successfully connected to us, so we don't need to avoid them)
                if (!peer.isOutbound()) {
                    clearDirectionMismatch(theirNodeId);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to update peer tracking: {}", e.getMessage());
        }

        // ATOMIC: Lock both peer lists during add operations to prevent race condition
        // This prevents disconnect from removing peer from connectedPeers between the two add operations
        // which would leave peer orphaned in handshakedPeers only
        
        // Determine what action to take while holding the lock, then execute disconnects outside
        Peer peerToDisconnect = null;
        String disconnectReason = null;
        boolean shouldAddPeer = false;
        
        synchronized (this.peerListsLock) {
            // Ensure peer is in connectedPeers before adding to handshakedPeers
            // This can happen if the PoW thread completes handshake but the peer wasn't properly
            // added to connectedPeers during connection establishment
            // Use object identity (==), not equals() which compares by address
            if (!this.connectedPeers.stream().anyMatch(p -> p == peer)) {
                LOGGER.warn("[NetworkData: {}] Peer {} not in connectedPeers during handshake completion - adding now",
                        peer.getPeerConnectionId(), peer);
                this.addConnectedPeer(peer);
            }

            // Synchronize duplicate check and add operation to prevent race condition
            synchronized (this.handshakedPeers) {
                // Check if this exact peer is already in handshakedPeers (duplicate call protection)
                // Use object identity (==), not equals() which compares by address
                if (this.handshakedPeers.stream().anyMatch(p -> p == peer)) {
                    LOGGER.debug("[NetworkData: {}] Peer {} already in handshakedPeers, skipping duplicate add",
                            peer.getPeerConnectionId(), peer);
                    return;
                }

                // Are we already connected to this peer (by public key)?
                Peer existingPeer = getHandshakedPeerWithPublicKey(peer.getPeersPublicKey());
                // NOTE: actual object reference compare, not Peer.equals()
                if (existingPeer != null && existingPeer != peer) {
                    // First check if existing peer is actually usable (not stale/dead)
                    // This is critical for force-connected peers to replace stale connections
                    boolean existingPeerUsable = existingPeer.getSocketChannel() != null 
                        && existingPeer.getSocketChannel().isOpen()
                        && !existingPeer.isStopping();
                    
                    if (!existingPeerUsable) {
                        // Existing peer is dead/stale - always replace it with new connection
                        // This ensures force-connected peers can replace stale entries
                        LOGGER.trace("[NetworkData: {}] Existing peer {} is stale (socket closed or stopping), replacing with new peer {}",
                                peer.getPeerConnectionId(),
                                existingPeer.getPeerConnectionId(),
                                peer.getPeerConnectionId());
                        peerToDisconnect = existingPeer;
                        disconnectReason = "replaced stale connection";
                        shouldAddPeer = true;
                    } else {
                        // Existing peer is alive - use deterministic tie-breaking
                        // Deterministic tie-breaking based on nodeId comparison
                        // Both nodes will compute the same result, eliminating reconnection loops
                        String ourNodeId = this.getOurNodeId();
                        String theirNodeId = peer.getPeersNodeId();
                        
                        // The node with the lower nodeId should be the one making outbound connections
                        boolean weShouldBeOutbound = ourNodeId.compareTo(theirNodeId) < 0;
                        
                        // Determine which connection direction is correct
                        boolean existingDirectionCorrect = (existingPeer.isOutbound() == weShouldBeOutbound);
                        boolean newDirectionCorrect = (peer.isOutbound() == weShouldBeOutbound);
                        
                        String winner = existingDirectionCorrect ? "existing" : (newDirectionCorrect ? "new" : "existing");
                        LOGGER.debug("[NetworkData: {}] Duplicate peer decision: existing={} (outbound={}), new={} (outbound={}), weShouldBeOutbound={}, winner={}",
                                peer.getPeerConnectionId(),
                                existingPeer.getPeerConnectionId(), existingPeer.isOutbound(),
                                peer.getPeerConnectionId(), peer.isOutbound(),
                                weShouldBeOutbound, winner);

                        if (existingDirectionCorrect) {
                            // Existing connection has the correct direction - keep existing, reject new
                            peerToDisconnect = peer;
                            disconnectReason = "duplicate connection - existing has correct direction";
                        } else if (newDirectionCorrect) {
                            // New connection has the correct direction - replace existing with new
                            peerToDisconnect = existingPeer;
                            disconnectReason = "replaced by connection with correct direction";
                            shouldAddPeer = true;  // Continue to add new peer
                        } else {
                            // Neither has correct direction (shouldn't happen in normal cases)
                            // Keep existing to avoid churn
                            peerToDisconnect = peer;
                            disconnectReason = "duplicate connection - keeping existing";
                        }
                    }
                } else {
                    // No duplicate - proceed with adding
                    shouldAddPeer = true;
                }

                // Add to handshaked peers cache if decision was made to add
                if (shouldAddPeer) {
                    this.addHandshakedPeer(peer);
                }
            }
        }
        
        // Execute disconnect outside the lock to avoid holding lock during cleanup
        if (peerToDisconnect != null) {
            peerToDisconnect.disconnect(disconnectReason);
            // If we disconnected the new peer, return early
            if (peerToDisconnect == peer) {
                return;
            }
        }

        // Make a note that we've successfully completed handshake (and when)
        peer.getPeerData().setLastConnected(NTP.getTime());

        // @ToDo : Need to understand what this is, what are pending signatures?
        //   Should this be part of the other thread?
        // Process any pending signature requests, as this peer may have been connected for this purpose only
        List<byte[]> pendingSignatureRequests = new ArrayList<>(peer.getPendingSignatureRequests());
        if (!pendingSignatureRequests.isEmpty()) {
            for (byte[] signature : pendingSignatureRequests) {
                this.requestDataFromConnectedPeer(peer, signature);
                peer.removePendingSignatureRequest(signature);
            }
        }

        // FUTURE: we may want to disconnect from this peer if we've finished requesting data from it

        // Only the outbound side needs to send anything (after we've received handshake-completing response).
        // (If inbound sent anything here, it's possible it could be processed out-of-order with handshake message).


        LOGGER.trace("Handshake has been completed");
        // Ask Controller if they want to do anything
        Controller.getInstance().onPeerHandshakeCompleted(peer);
    }

    public boolean canAcceptInbound() {
        return this.canAcceptInbound;
    }
    // External IP / peerAddress tracking

    public synchronized void ourPeerAddressUpdated(String peerAddress) {
        if (peerAddress == null || peerAddress.isEmpty()) {
            return;
        }

        // Validate IP address
        String[] parts = peerAddress.split(":");
        if (parts.length != 2) {
            return;
        }
        String host = parts[0];
        
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isAnyLocalAddress() || addr.isSiteLocalAddress()) {
                // Ignore local addresses
                return;
            }
        } catch (UnknownHostException e) {
            return;
        }

        // Keep track of the port
        try {
            this.ourExternalPort = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            LOGGER.debug("Invalid port number in peer address: {}", peerAddress);
            return;
        }

        // Add to the list
        this.ourExternalIpAddressHistory.add(host);

        // Limit to 25 entries
        while (this.ourExternalIpAddressHistory.size() > 25) {
            this.ourExternalIpAddressHistory.remove(0);
        }

        // Now take a copy of the IP address history so it can be safely iterated
        // Without this, another thread could remove an element, resulting in an exception
        List<String> ipAddressHistory = new ArrayList<>(this.ourExternalIpAddressHistory);

        // If we've had 10 consecutive matching addresses, and they're different from
        // our stored IP address value, treat it as updated.
        int consecutiveReadingsRequired = 10;
        int size = ipAddressHistory.size();
        if (size < consecutiveReadingsRequired) {
            // Need at least 10 readings
            return;
        }

        // Count the number of consecutive IP address readings from the end of the list
        String lastReading = ipAddressHistory.get(size - 1);
        int consecutiveReadings = 1; // Start at 1 since the last element counts as the first match
        for (int i = size - 2; i >= 0; i--) {
            String reading = ipAddressHistory.get(i);
            if (Objects.equals(reading, lastReading)) {
                consecutiveReadings++;
            } else {
                // Stop when we find a different address (we want consecutive matches only)
                break;
            }
        }

        if (consecutiveReadings >= consecutiveReadingsRequired) {
            // Last 10 readings were the same - i.e. more than one peer agreed on the new IP address...
            String ip = ipAddressHistory.get(size - 1);
            if (ip != null && !Objects.equals(ip, "null")) {
                if (!Objects.equals(ip, this.ourExternalIpAddress)) {
                    // ... and the readings were different to our current recorded value, so
                    // update our external IP address value
                    this.ourExternalIpAddress = ip;
                }
            }
        }
    }

    public String getOurExternalIpAddress() {
        return this.ourExternalIpAddress;
    }

    public String getOurExternalIpAddressAndPort() {
        String ipAddress = this.getOurExternalIpAddress();
        if (ipAddress == null) {
            return null;
        }
        return String.format("%s:%d", ipAddress, this.ourExternalPort);
    }


    // Peer-management calls

    public void noteToSelf(Peer peer) {
        LOGGER.debug("[{}] No longer considering peer address {} as it connects to self",
                peer.getPeerConnectionId(), peer);

        synchronized (this.selfPeers) {
            this.selfPeers.add(peer.getPeerData().getAddress());
        }
    }

    public boolean forgetPeer(PeerAddress peerAddress) throws DataException {
        boolean numDeleted;

        synchronized (this.allKnownPeers) {
            numDeleted = this.allKnownPeers.removeIf(peerData -> peerData.getAddress().equals(peerAddress));
        }

        disconnectPeer(peerAddress);

        return numDeleted;
    }

    public int forgetAllPeers() throws DataException {
        int numDeleted;

        synchronized (this.allKnownPeers) {
            this.allKnownPeers.clear();

            try (Repository repository = RepositoryManager.getRepository()) {
                numDeleted = repository.getNetworkRepository().deleteAllPeers();
                repository.saveChanges();
            }
        }

        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.disconnect("to be forgotten");
        }

        return numDeleted;
    }

    private void disconnectPeer(PeerAddress peerAddress) {
        // Create snapshot first (acquires and releases lock)
        PeerList peerListSnapshot = this.getImmutableConnectedPeers();
        
        // Find matching peer in snapshot (no lock held)
        Peer matchingPeer = peerListSnapshot.stream()
            .filter(peer -> peerAddress.equals(peer.getPeerData().getAddress()))
            .findFirst()
            .orElse(null);
        
        // Disconnect outside the iteration (no lock held)
        if (matchingPeer != null) {
            matchingPeer.disconnect("to be forgotten");
        }
    }

    // Network-wide calls
    public void addPeer(Peer p) {
        LOGGER.trace("Passed a newly connected peer from Network : {}", p);

        // We need the ip address only
        String remoteHost = p.getPeerData().getAddress().getHost();
        Object qdnCapability = p.getPeerCapability("QDN");
        
        // Skip peers that don't advertise QDN capability
        if (qdnCapability == null) {
            LOGGER.debug("Peer {} does not advertise QDN capability, skipping NetworkData registration", remoteHost);
            return;
        }
        
        // Parse QDN port from capability (handle both Integer and Long types)
        int remoteHostQDNPort;
        try {
            if (qdnCapability instanceof Integer) {
                remoteHostQDNPort = (Integer) qdnCapability;
            } else if (qdnCapability instanceof Long) {
                remoteHostQDNPort = ((Long) qdnCapability).intValue();
            } else {
                LOGGER.warn("Peer {} has invalid QDN capability type: {}, skipping", remoteHost, qdnCapability.getClass());
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse QDN capability for peer {}: {}", remoteHost, e.getMessage());
            return;
        }

        synchronized (this.allKnownPeers) {
            // if All Known Peers  already has this host. return;
            boolean alreadyKnown = allKnownPeers.stream()
                    .anyMatch(pd -> pd.getAddress().getHost().equals(remoteHost));
            if (alreadyKnown)
                return;

            // Clean out values that were passed in
            String target = remoteHost + ":" + remoteHostQDNPort;

            PeerAddress pa = PeerAddress.fromString(target);
            PeerData pd = new PeerData(
                    pa,
                    0L,
                    0L,
                    0L,
                    System.currentTimeMillis(),
                    "INIT");
            allKnownPeers.add(pd);
            
            LOGGER.debug("Added QDN peer {} (port {}) from Network connection", remoteHost, remoteHostQDNPort);
            
            // CRITICAL FIX: Update cache to map QDN listen address to nodeId
            // This allows getConnectablePeer() to identify this peer even if we're
            // connected via a different port (e.g., inbound ephemeral port)
            if (p.getPeersNodeId() != null) {
                updateAddressToNodeIdCache(target, p.getPeersNodeId());
                LOGGER.debug("Cached QDN address {} → nodeId {}", target, p.getPeersNodeId().substring(0, 8));
            }
        }
    }

    public boolean mergePeers(String addedBy, long addedWhen, List<PeerAddress> peerAddresses) throws DataException {
        List<PeerData> newPeers;
        synchronized (this.allKnownPeers) {
            for (PeerData knownPeerData : this.allKnownPeers) {
                // Filter out duplicates, without resolving via DNS
                Predicate<PeerAddress> isKnownAddress = peerAddress -> knownPeerData.getAddress().equals(peerAddress);
                peerAddresses.removeIf(isKnownAddress);
            }

            if (peerAddresses.isEmpty()) {
                return false;
            }

            // Add leftover peer addresses to known peers list
            newPeers = peerAddresses.stream()
                    .map(peerAddress -> new PeerData(peerAddress, addedWhen, addedBy))
                    .collect(Collectors.toList());

            this.allKnownPeers.addAll(newPeers);

            return true;
        }
    }

    public void prunePeers() throws DataException {
        // Guard against running during shutdown
        if (this.isShuttingDown) {
            return;
        }
        
        final Long now = NTP.getTime();
        if (now == null) {
            return;
        }

        // Repair any orphaned peers (bidirectional check between connectedPeers and handshakedPeers)
        try {
            repairOrphanedPeers();
        } catch (Exception e) {
            LOGGER.error("Error repairing orphaned peers: {}", e.getMessage(), e);
            // Continue with other pruning operations - don't let one failure stop the rest
        }
        
        // Enforce direction invariant (fixes simultaneous outbound connect zombies)
        try {
            enforceDirectionInvariant();
        } catch (Exception e) {
            LOGGER.error("Error enforcing direction invariant: {}", e.getMessage(), e);
            // Continue with other pruning operations - don't let one failure stop the rest
        }
        
        // Clean up stale direction mismatch records and address cache
        try {
            cleanupStaleDirectionMismatches();
        } catch (Exception e) {
            LOGGER.error("Error cleaning up stale direction mismatches: {}", e.getMessage(), e);
            // Continue with other pruning operations
        }

        // Disconnect peers that are stuck during handshake
        // Get the PeerList snapshot and create a mutable copy (ArrayList) of its contents.
        // We use .stream().collect(Collectors.toList()) to create the mutable List<Peer>.
        List<Peer> handshakePeers = this.getImmutableConnectedPeers().stream()
                .collect(Collectors.toList());

        // Disregard peers that have completed handshake or only connected recently
        handshakePeers.removeIf(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                || peer.getConnectionTimestamp() == null || peer.getConnectionTimestamp() > now - HANDSHAKE_TIMEOUT);

        for (Peer peer : handshakePeers) {
            LOGGER.trace("Disconnecting stuck peer {} at handshake status {}", 
                    peer.getPeerData().getAddress(), peer.getHandshakeStatus().name());
            peer.disconnect(String.format("handshake timeout at %s", peer.getHandshakeStatus().name()));
        }

        // Clean up peers with closed sockets (zombie connections)
        // These can block new connections due to duplicate detection during handshake
        // This catches peers in any handshake state (including COMPLETED) where the socket
        // has been closed but the peer hasn't been removed from the connected list yet
        List<Peer> deadPeers = this.getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getSocketChannel() == null || !peer.getSocketChannel().isOpen())
                .collect(Collectors.toList());

        for (Peer peer : deadPeers) {
            LOGGER.trace("Disconnecting dead data peer {} (socket closed, handshake status: {})",
                    peer.getPeerData().getAddress(), peer.getHandshakeStatus().name());
            peer.disconnect("socket closed");
        }

        // Additional defensive cleanup: Check handshakedPeers for zombie connections
        // This catches the case where onDisconnect() might have failed to remove a peer
        // from handshakedPeers even though the socket is closed
        // NOTE: We only check for closed/null sockets, NOT isStopping() - that flag is set
        // during normal disconnect flow and would incorrectly remove all disconnecting peers
        List<Peer> zombieHandshakedPeers = this.getImmutableHandshakedPeers().stream()
                .filter(peer -> peer.getSocketChannel() == null || !peer.getSocketChannel().isOpen())
                .collect(Collectors.toList());

        if (!zombieHandshakedPeers.isEmpty()) {
            LOGGER.warn("Found {} zombie data peer(s) in handshakedPeers list, forcing cleanup", 
                    zombieHandshakedPeers.size());
            for (Peer peer : zombieHandshakedPeers) {
                LOGGER.warn("Removing zombie handshaked data peer {} (socket closed)",
                        peer.getPeerData().getAddress());
                // Directly remove from lists as a defensive measure
                // This shouldn't normally be needed if disconnect() works properly,
                // but provides a safety net against the bug we're fixing
                this.removeHandshakedPeer(peer);
                this.removeConnectedPeer(peer);
            }
        }

        // Disconnect peers that have stuck writes (no progress for 60 seconds)
        final long WRITE_STUCK_TIMEOUT = 60_000L;
        List<Peer> stuckWritePeers = this.getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED)
                .filter(peer -> peer.hasStuckWrite(WRITE_STUCK_TIMEOUT))
                .collect(Collectors.toList());

        for (Peer peer : stuckWritePeers) {
            String stuckInfo = peer.getStuckWriteInfo();
            LOGGER.warn("Disconnecting peer {} with stuck write: {}", 
                    peer.getPeerData().getAddress(), stuckInfo);
            peer.disconnect("write stuck: " + stuckInfo);
        }



        // Prune 'old' peers from if we are over the count
        // getImmutableHandshakedPeers().size() works fine as PeerList has a size() method.
        int overCount = this.getImmutableHandshakedPeers().size() - Settings.getInstance().getMaxDataPeers();
        if (overCount > 0) { // Too Many peers we need to trim some out
            List<Peer> listDisconnectPeers = findOldPeers(overCount);
            for (Peer disconnectPeer : listDisconnectPeers) {
                disconnectPeer.disconnect("Over Max and Old");
            }
        }
    }

    /**
     * Returns the N peers with the lowest getLastQDNUse() values.
     *
     * @param num number of peers to return
     * @return list of peers with lowest getLastQDNUse()
     */
     List<Peer> findOldPeers(int num) {
        return this.getImmutableHandshakedPeers().stream()
                .sorted(Comparator.comparingLong(Peer::getLastQDNUse))
                .limit(num)
                .collect(Collectors.toList());
     }

    public void broadcast(Function<Peer, Message> peerMessageBuilder) {
        for (Peer peer : getImmutableHandshakedPeers()) {
            if (this.isShuttingDown)
                return;

            Message message = peerMessageBuilder.apply(peer);

            if (message == null) {
                continue;
            }

            LOGGER.trace("Broadcasting Message {} : {} to {} on NETWORKDATA", message.getType(), message.toString(), peer);

            // Use PeerSendManager for retry logic and backpressure handling
            try {
                PeerSendManager sendManager = PeerSendManagement.getInstance().getOrCreateSendManager(peer, true);
                
                
                // Calculate estimated message size for queue management
                int estimatedSize;
                try {
                    byte[] messageBytes = message.toBytes();
                    estimatedSize = messageBytes != null ? messageBytes.length : 1024;
                } catch (MessageException e) {
                    LOGGER.warn("Failed to calculate message size for broadcast, using default: {}", e.getMessage());
                    estimatedSize = 1024;
                }
                
                // Use HIGH_PRIORITY for broadcasts since they're important (file list requests, etc.)
                sendManager.queueMessageFactoryWithPriority(
                    PeerSendManager.HIGH_PRIORITY,
                    () -> message,
                    estimatedSize,
                    null  // No hash tracking for broadcast messages
                );
            } catch (MessageException e) {
                // PeerSendManager rejected the message (cooldown, etc.)
                LOGGER.debug("PeerSendManager rejected broadcast message to {}: {}", peer, e.getMessage());
                
                // Only disconnect if the socket is actually gone
                if (peer.getSocketChannel() == null || !peer.getSocketChannel().isOpen()) {
                    LOGGER.trace("Failed to broadcast message to {} - socket closed", peer);
                    peer.disconnect("failed to broadcast message");
                }
            }
        }
    }

    // Shutdown
    public void shutdown() {
        this.isShuttingDown = true;

        // Close listen socket to prevent more incoming connections
        if (this.serverChannel != null && this.serverChannel.isOpen()) {
            try {
                this.serverChannel.close();
            } catch (IOException e) {
                // Not important
            }
        }

        // Shutdown chunk processor pool first (stop accepting new chunk processing tasks)
        LOGGER.info("Shutting down chunk processor pool...");
        chunkProcessorPool.shutdown();
        try {
            // Wait up to 30 seconds for pending chunk processing to complete
            if (!chunkProcessorPool.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.warn("Chunk processor pool did not terminate gracefully, forcing shutdown");
                chunkProcessorPool.shutdownNow();
            } else {
                LOGGER.info("Chunk processor pool shutdown complete");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for chunk processor pool to terminate");
            chunkProcessorPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Stop processing threads
        try {
            if (!this.networkDataEPC.shutdown(5000)) {
                LOGGER.warn("Network threads failed to terminate");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for networking threads to terminate");
        }

        try {  // DeMap QDN uPnP so other nodes can use it when we are done
            UPnP.closePortTCP(Settings.getInstance().getQDNListenPort());
        } catch (Exception e) {
            // do nothing
        }
        // Close all peer connections
        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.shutdown();
        }
    }
}
