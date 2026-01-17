package org.qortal.network;

import com.dosse.upnp.UPnP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.controller.arbitrary.ArbitraryDataFileListManager;
import org.qortal.crypto.Crypto;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.TransactionData;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.qortal.network.RNSCommon.PeerType;
import static io.reticulum.link.LinkStatus.ACTIVE;
import static java.util.Objects.nonNull;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

// For managing peers
public class Network {
    private static final Logger LOGGER = LogManager.getLogger(Network.class);

    private static final int LISTEN_BACKLOG = 5;
    /**
     * How long before retrying after a connection failure, in milliseconds.
     */
    private static final long CONNECT_FAILURE_BACKOFF = 5 * 60 * 1000L; // ms
    /**
     * How long between informational broadcasts to all connected peers, in milliseconds.
     */
    private static final long BROADCAST_INTERVAL = 30 * 1000L; // ms
    /**
     * Maximum time since last successful connection for peer info to be propagated, in milliseconds.
     */
    private static final long RECENT_CONNECTION_THRESHOLD = 24 * 60 * 60 * 1000L; // ms
    /**
     * Maximum time since last connection attempt before a peer is potentially considered "old", in milliseconds.
     */
    private static final long OLD_PEER_ATTEMPTED_PERIOD = 24 * 60 * 60 * 1000L; // ms
    /**
     * Maximum time since last successful connection before a peer is potentially considered "old", in milliseconds.
     */
    private static final long OLD_PEER_CONNECTION_PERIOD = 7 * 24 * 60 * 60 * 1000L; // ms
    /**
     * Maximum time allowed for handshake to complete, in milliseconds.
     */
    private static final long HANDSHAKE_TIMEOUT = 60 * 1000L; // ms

    private static final byte[] MAINNET_MESSAGE_MAGIC = new byte[]{0x51, 0x4f, 0x52, 0x54}; // QORT
    private static final byte[] TESTNET_MESSAGE_MAGIC = new byte[]{0x71, 0x6f, 0x72, 0x54}; // qorT

    private static final String[] INITIAL_PEERS = new String[]{
            "node1.qortal.org", "node2.qortal.org", "node3.qortal.org", "node4.qortal.org", "node5.qortal.org",
            "node6.qortal.org", "node7.qortal.org", "node8.qortal.org", "node9.qortal.org", "node10.qortal.org",
            "node11.qortal.org", "node12.qortal.org", "node13.qortal.org", "node14.qortal.org", "node15.qortal.org",
            "node.qortal.ru", "node2.qortal.ru", "node3.qortal.ru", "node.qortal.uk", "qnode1.crowetic.com", "bootstrap-ssh.qortal.org",
            "proxynodes.qortal.link", "api.qortal.org", "bootstrap2-ssh.qortal.org", "bootstrap3-ssh.qortal.org",
            "node2.qortalnodes.live", "node3.qortalnodes.live", "node4.qortalnodes.live", "node5.qortalnodes.live",
            "node6.qortalnodes.live", "node7.qortalnodes.live", "node8.qortalnodes.live", "ubuntu-monster.qortal.org"
    };

    private static final long NETWORK_EPC_KEEPALIVE = 5L; // seconds

    public static final int MAX_SIGNATURES_PER_REPLY = 500;
    public static final int MAX_BLOCK_SUMMARIES_PER_REPLY = 500;

    private static final long DISCONNECTION_CHECK_INTERVAL = 20 * 1000L; // milliseconds

    private static final int BROADCAST_CHAIN_TIP_DEPTH = 7; // Just enough to fill a SINGLE TCP packet (~1440 bytes)

    // Generate our node keys / ID
    private final Ed25519PrivateKeyParameters edPrivateKeyParams = new Ed25519PrivateKeyParameters(new SecureRandom());
    private final Ed25519PublicKeyParameters edPublicKeyParams = edPrivateKeyParams.generatePublicKey();
    private final String ourNodeId = Crypto.toNodeAddress(edPublicKeyParams.getEncoded());

    private final int maxMessageSize;
    private final int minOutboundPeers;
    private final int maxPeers;

    private long nextDisconnectionCheck = 0L;

    private final List<PeerData> allKnownPeers = new ArrayList<>();

    /**
     * Maintain two lists for each subset of peers:
     * - A synchronizedList, to be modified when peers are added/removed
     * - An immutable List, which is rebuilt automatically to mirror the synchronized list, and is then served to consumers
     * This allows for thread safety without having to synchronize every time a thread requests a peer list
     */
    private final List<Peer> connectedPeers = Collections.synchronizedList(new ArrayList<>());
    private List<Peer> immutableConnectedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above

    private final List<Peer> handshakedPeers = Collections.synchronizedList(new ArrayList<>());
    private List<Peer> immutableHandshakedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above

    private final List<Peer> outboundHandshakedPeers = Collections.synchronizedList(new ArrayList<>());
    private List<Peer> immutableOutboundHandshakedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above


    /**
     * Count threads per message type in order to enforce limits
     */
    private final Map<MessageType, Integer> threadsPerMessageType = Collections.synchronizedMap(new HashMap<>());

    /**
     * Keep track of total thread count, to warn when the thread pool is getting low
     */
    private int totalThreadCount = 0;

    /**
     * Thresholds at which to warn about the number of active threads
     */
    private final int threadCountWarningThreshold = (int) (Settings.getInstance().getMaxNetworkThreadPoolSize() * 0.9f);
    private final Integer threadCountPerMessageTypeWarningThreshold = Settings.getInstance().getThreadCountPerMessageTypeWarningThreshold();

    private final List<PeerAddress> selfPeers = new ArrayList<>();

    private String bindAddress = null;

    private final ExecuteProduceConsume networkEPC;
    private Selector channelSelector;
    private ServerSocketChannel serverChannel;
    private SelectionKey serverSelectionKey;
    private final Set<SelectableChannel> channelsPendingWrite = ConcurrentHashMap.newKeySet();

    private final Lock mergePeersLock = new ReentrantLock();

    private List<String> ourExternalIpAddressHistory = new ArrayList<>();
    private String ourExternalIpAddress = null;
    private int ourExternalPort = Settings.getInstance().getListenPort();

    private volatile boolean isShuttingDown = false;

    private static volatile RNS rns;

    // Constructors

    private Network() {
        maxMessageSize = 4 + 1 + 4 + BlockChain.getInstance().getMaxBlockSize();

        var settings = Settings.getInstance();
        minOutboundPeers = settings.getMinOutboundPeers() + settings.getReticulumMinOutboundPeers();
        maxPeers = settings.getIpMaxPeers() + settings.getReticulumMaxPeers();

        // We'll use a cached thread pool but with more aggressive timeout.
        ExecutorService networkExecutor = new ThreadPoolExecutor(2,
                Settings.getInstance().getMaxNetworkThreadPoolSize(),
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new NamedThreadFactory("Network-EPC", Settings.getInstance().getNetworkThreadPriority()));
        networkEPC = new NetworkProcessor(networkExecutor);
    }

    public void start() throws IOException, DataException {

        // Grab P2P port from settings
        int listenPort = Settings.getInstance().getListenPort();

        // Grab P2P bind addresses from settings
        List<String> bindAddresses = new ArrayList<>();
        if (Settings.getInstance().getBindAddress() != null) {
            bindAddresses.add(Settings.getInstance().getBindAddress());
        }
        if (Settings.getInstance().getBindAddressFallback() != null) {
            bindAddresses.add(Settings.getInstance().getBindAddressFallback());
        }

        for (int i=0; i<bindAddresses.size(); i++) {
            try {
                String bindAddress = bindAddresses.get(i);
                InetAddress bindAddr = InetAddress.getByName(bindAddress);
                InetSocketAddress endpoint = new InetSocketAddress(bindAddr, listenPort);

                channelSelector = Selector.open();

                // Set up listen socket
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                serverChannel.bind(endpoint, LISTEN_BACKLOG);
                serverSelectionKey = serverChannel.register(channelSelector, SelectionKey.OP_ACCEPT);

                this.bindAddress = bindAddress; // Store the selected address, so that it can be used by other parts of the app
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

        // Load all known peers from repository
        synchronized (this.allKnownPeers) {
            List<String> fixedNetwork = Settings.getInstance().getFixedNetwork();
            if (fixedNetwork != null && !fixedNetwork.isEmpty()) {
                Long addedWhen = NTP.getTime();
                String addedBy = "fixedNetwork";
                List<PeerAddress> peerAddresses = new ArrayList<>();
                try {
                    for (String address : fixedNetwork) {
                        //PeerAddress peerAddress = PeerAddress.fromString(address);
                        PeerAddress peerAddress = PeerAddressFactory.create("address", address); // only IPPeer
                        peerAddresses.add(peerAddress);
                    }
                } catch (ReflectiveOperationException e) {
                    LOGGER.error("Can't add peer address to fixed network: {}", e.getMessage());
                }
                List<PeerData> peers = peerAddresses.stream()
                        .map(peerAddress -> new PeerData(peerAddress, addedWhen, addedBy))
                        .collect(Collectors.toList());
                this.allKnownPeers.addAll(peers);
            } else {
                try (Repository repository = RepositoryManager.getRepository()) {
                    this.allKnownPeers.addAll(repository.getNetworkRepository().getAllPeers());
                }
            }

            LOGGER.debug("starting with {} known peers", this.allKnownPeers.size());
        }

        // Attempt to set up UPnP. All errors are ignored.
        if (Settings.getInstance().isUPnPEnabled()) {
            UPnP.openPortTCP(Settings.getInstance().getListenPort());
        }
        else {
            UPnP.closePortTCP(Settings.getInstance().getListenPort());
        }

        // Start up first networking thread
        networkEPC.start();

        // Network setup done, time to launch Reticulum mesh
        LOGGER.info("Starting Reticulum");
        RNS.getInstance().start();
    }

    // Getters / setters

    private static class SingletonContainer {
        private static final Network INSTANCE = new Network();
    }

    public Map<MessageType, Integer> getThreadsPerMessageType() {
        return this.threadsPerMessageType;
    }

    public int getTotalThreadCount() {
        synchronized (this) {
            return this.totalThreadCount;
        }
    }

    public static Network getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public int getMaxPeers() {
        return this.maxPeers;
    }

    public String getBindAddress() {
        return this.bindAddress;
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

    public StatsSnapshot getStatsSnapshot() {
        return this.networkEPC.getStatsSnapshot();
    }

    // Peer lists

    public List<PeerData> getAllKnownPeers() {
        synchronized (this.allKnownPeers) {
            //return new ArrayList<>(this.allKnownPeers);
            return Stream.concat(this.allKnownPeers.stream(), RNS.getInstance().getAllKnownPeers().stream())
                    .distinct()
                    .collect(Collectors.toList());
        }
    }

    public List<Peer> getImmutableConnectedPeers() {
        return this.immutableConnectedPeers;
    }

    public List<Peer> getImmutableConnectedIPPeers() {
        return this.immutableConnectedPeers.stream()
                .filter(p -> p.getPeerData().getPeerType() == PeerType.IP)
                .collect(Collectors.toList());
    }

    public List<Peer> getImmutableConnectedDataPeers() {
        return this.getImmutableConnectedPeers().stream()
                .filter(p -> p.isDataPeer())
                .collect(Collectors.toList());
    }

    public List<Peer> getImmutableConnectedNonDataPeers() {
        return this.getImmutableConnectedPeers().stream()
                .filter(p -> !p.isDataPeer())
                .collect(Collectors.toList());
    }

    public void addConnectedPeer(Peer peer) {
        this.connectedPeers.add(peer); // thread safe thanks to synchronized list
        this.immutableConnectedPeers = List.copyOf(this.connectedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)
    }

    public void removeConnectedPeer(Peer peer) {
        // Firstly remove from handshaked peers
        this.removeHandshakedPeer(peer);

        this.connectedPeers.remove(peer); // thread safe thanks to synchronized list
        this.immutableConnectedPeers = List.copyOf(this.connectedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)
    }

    public List<PeerAddress> getSelfPeers() {
        synchronized (this.selfPeers) {
            return new ArrayList<>(this.selfPeers);
        }
    }

    public boolean requestDataFromPeer(String peerAddressString, byte[] signature) {
        if (peerAddressString != null) {
            //PeerAddress peerAddress = PeerAddress.fromString(peerAddressString);
            try {
                //PeerAddress peerAddress = null;
                PeerData peerData = null;

                final PeerAddress peerAddress = PeerAddressFactory.create("address", peerAddressString);

                // Reuse an existing PeerData instance if it's already in the known peers list
                synchronized (this.allKnownPeers) {
                    peerData = this.allKnownPeers.stream()
                            .filter(knownPeerData -> knownPeerData.getAddress().equals(peerAddress))
                            .findFirst()
                            .orElse(null);
                }

                if (peerData == null) {
                    // Not a known peer, so we need to create one
                    Long addedWhen = NTP.getTime();
                    String addedBy = "requestDataFromPeer";
                    peerData = new PeerData(peerAddress, addedWhen, addedBy);
                }

                if (peerData == null) {
                    LOGGER.info("PeerData is null when trying to request data from peer {}", peerAddressString);
                    return false;
                }

                // Check if we're already connected to and handshaked with this peer
                Peer connectedPeer = this.getImmutableConnectedPeers().stream()
                            .filter(p -> p.getPeerData().getAddress().equals(peerAddress))
                            .findFirst()
                            .orElse(null);

                boolean isConnected = (connectedPeer != null);

                boolean isHandshaked = this.getImmutableHandshakedPeers().stream()
                        .anyMatch(p -> p.getPeerData().getAddress().equals(peerAddress));

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
                            //Peer peer = new Peer(peerData);
                            Peer peer = PeerFactory.create("peer-data", peerData);
                            peer.setIsDataPeer(true);
                            peer.addPendingSignatureRequest(signature);
                            return this.connectPeer(peer);
                            // If connection (and handshake) is successful, data will automatically be requested
                        }
                        else if (!isHandshaked) {
                            LOGGER.info("Peer {} is connected but not handshaked. Not attempting a new connection.", peerAddress);
                            return false;
                        }

                    } catch (InterruptedException e) {
                        LOGGER.info("Interrupted when connecting to peer {}", peerAddress);
                        return false;
                    } catch (ReflectiveOperationException e) {
                        LOGGER.warn("Exception when trying to request data from peer {}", peerAddress, e);
                    }
                }
            } catch (ReflectiveOperationException e) {
                LOGGER.error("Can't create peer address: {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean requestDataFromConnectedPeer(Peer connectedPeer, byte[] signature) {
        if (signature == null) {
            // Nothing to do
            return false;
        }

        return ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(connectedPeer, signature);
    }

    /**
     * Returns list of connected peers that have completed handshaking.
     */
    public List<Peer> getImmutableHandshakedPeers() {
        return this.immutableHandshakedPeers;
    }

    public List<Peer> getImmutableHandshakedIPPeers() {
        return this.immutableHandshakedPeers.stream()
                .filter(p -> p.getPeerData().getPeerType() == PeerType.IP)
                .collect(Collectors.toList());
    }

    public void addHandshakedPeer(Peer peer) {
        this.handshakedPeers.add(peer); // thread safe thanks to synchronized list
        this.immutableHandshakedPeers = List.copyOf(this.handshakedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)

        // Also add to outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.addOutboundHandshakedPeer(peer);
        }
    }

    public void removeHandshakedPeer(Peer peer) {
        this.handshakedPeers.remove(peer); // thread safe thanks to synchronized list
        this.immutableHandshakedPeers = List.copyOf(this.handshakedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)

        // Also remove from outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.removeOutboundHandshakedPeer(peer);
        }
    }

    /**
     * Returns list of peers we connected to that have completed handshaking.
     */
    public List<Peer> getImmutableOutboundHandshakedPeers() {
        return this.immutableOutboundHandshakedPeers;
    }

    public void addOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        this.outboundHandshakedPeers.add(peer); // thread safe thanks to synchronized list
        this.immutableOutboundHandshakedPeers = List.copyOf(this.outboundHandshakedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)
    }

    public void removeOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        this.outboundHandshakedPeers.remove(peer); // thread safe thanks to synchronized list
        this.immutableOutboundHandshakedPeers = List.copyOf(this.outboundHandshakedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)
    }

    /**
     * Returns first peer that has completed handshaking and has matching public key.
     */
    public Peer getHandshakedPeerWithPublicKey(byte[] publicKey) {
        //return this.getImmutableConnectedPeers().stream()
        return this.getImmutableConnectedIPPeers().stream()
                .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                        && Arrays.equals(peer.getPeersPublicKey(), publicKey))
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

    //private final Predicate<PeerData> isNotIPPeer = peerData -> {
    //    PeerType peerType = peerData.getPeerType();
    //    return this.getImmutableConnectedPeers().stream().anyMatch(Ppeer -> peerType.equals(PeerType.RETICULUM));
    //};

    private final Predicate<PeerData> isConnectedPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.getImmutableConnectedPeers().stream().anyMatch(peer -> peer.getPeerData().getAddress().equals(peerAddress));
    };

    private final Predicate<PeerData> isResolvedAsConnectedPeer = peerData -> {
        try {
            InetSocketAddress resolvedSocketAddress = peerData.getAddress().toSocketAddress();
            return this.getImmutableConnectedPeers().stream()
                    .anyMatch(peer -> peer.getResolvedAddress().equals(resolvedSocketAddress));
        } catch (UnknownHostException e) {
            // Can't resolve - no point even trying to connect
            return true;
        }
    };

    // Initial setup

    public static void installInitialPeers(Repository repository) throws DataException {
        for (String address : INITIAL_PEERS) {
            try {
                //PeerAddress peerAddress = PeerAddress.fromString(address);
                PeerAddress peerAddress = PeerAddressFactory.create("address", address);

                PeerData peerData = new PeerData(peerAddress, System.currentTimeMillis(), "INIT");
                repository.getNetworkRepository().save(peerData);
            } catch (ReflectiveOperationException e) {
                LOGGER.error("Can't create peer address: {}", e.getMessage());
            }
        }

        repository.saveChanges();
    }

    // Main thread

    class NetworkProcessor extends ExecuteProduceConsume {

        private final Logger LOGGER = LogManager.getLogger(NetworkProcessor.class);

        private final AtomicLong nextConnectTaskTimestamp = new AtomicLong(0L); // ms - try first connect once NTP syncs
        private final AtomicLong nextBroadcastTimestamp = new AtomicLong(0L); // ms - try first broadcast once NTP syncs

        private Iterator<SelectionKey> channelIterator = null;

        NetworkProcessor(ExecutorService executor) {
            super(executor);
        }

        @Override
        protected void onSpawnFailure() {
            // For debugging:
            // ExecutorDumper.dump(this.executor, 3, ExecuteProduceConsume.class);
        }

        @Override
        protected Task produceTask(boolean canBlock) throws InterruptedException {
            Task task;

            task = maybeProducePeerMessageTask();
            if (task != null) {
                return task;
            }

            final Long now = NTP.getTime();

            task = maybeProducePeerPingTask(now);
            if (task != null) {
                return task;
            }

            task = maybeProduceConnectPeerTask(now);
            if (task != null) {
                return task;
            }

            task = maybeProduceBroadcastTask(now);
            if (task != null) {
                return task;
            }

            // Only this method can block to reduce CPU spin
            return maybeProduceChannelTask(canBlock);
        }

        private Task maybeProducePeerMessageTask() {
            return getImmutableConnectedPeers().stream()
                    .map(Peer::getMessageTask)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        private Task maybeProducePeerPingTask(Long now) {
            //return getImmutableHandshakedIPPeers().stream()
            return getImmutableHandshakedPeers().stream()
                    .map(peer -> peer.getPingTask(now))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        private Task maybeProduceConnectPeerTask(Long now) throws InterruptedException {
            if (now == null || now < nextConnectTaskTimestamp.get()) {
                return null;
            }

            // only IPPeer know about Handshake.COMPLETED
            //var iOHP = getImmutableHandshakedPeers().stream()
            var iOHP = getImmutableHandshakedIPPeers().stream()
                    .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED)
                    .collect(Collectors.toList());
            if (iOHP.size() >= minOutboundPeers) {
                return null;
            }
            //if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
            //    return null;
            //}

            nextConnectTaskTimestamp.set(now + 1000L);

            Peer targetPeer = getConnectablePeer(now);
            if (targetPeer == null) {
                return null;
            }

            // Create connection task
            return new PeerConnectTask(targetPeer);
        }

        private Task maybeProduceBroadcastTask(Long now) {
            if (now == null || now < nextBroadcastTimestamp.get()) {
                return null;
            }

            nextBroadcastTimestamp.set(now + BROADCAST_INTERVAL);
            return new BroadcastTask();
        }

        private Task maybeProduceChannelTask(boolean canBlock) throws InterruptedException {
            // Synchronization here to enforce thread-safety on channelIterator
            synchronized (channelSelector) {
                // anything to do?
                if (channelIterator == null) {
                    try {
                        if (canBlock) {
                            channelSelector.select(1000L);
                        } else {
                            channelSelector.selectNow();
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
                        if (!channelsPendingWrite.add(socketChannel))
                            return null;

                        return new ChannelWriteTask((SocketChannel) socketChannel, peer);
                    }

                    if (nextSelectionKey.isAcceptable()) {
                        clearInterestOps(nextSelectionKey, SelectionKey.OP_ACCEPT);
                        return new ChannelAcceptTask((ServerSocketChannel) socketChannel);
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

    public boolean ipNotInFixedList(PeerAddress address, List<String> fixedNetwork) {
        for (String ipAddress : fixedNetwork) {
            String[] bits = ipAddress.split(":");
            if (bits.length >= 1 && bits.length <= 2 && address.getHost().equals(bits[0])) {
                return false;
            }
        }
        return true;
    }

    private Peer getConnectablePeer(final Long now) throws InterruptedException {

        // Find an address to connect to
        List<PeerData> peers = this.getAllKnownPeers();

        // Don't consider peers with recent connection failures
        final long lastAttemptedThreshold = now - CONNECT_FAILURE_BACKOFF;
        peers.removeIf(peerData -> peerData.getLastAttempted() != null
                && (peerData.getLastConnected() == null
                || peerData.getLastConnected() < peerData.getLastAttempted())
                && peerData.getLastAttempted() > lastAttemptedThreshold
                || peerData.getPeerType() == PeerType.RETICULUM);

        // Don't consider peers that we know loop back to ourself
        synchronized (this.selfPeers) {
            peers.removeIf(isSelfPeer);
        }

        // Don't consider any non-IP peers (eg. ReticulumPeer)
        Predicate<PeerData> isNotIPPeer = peerData -> {
            PeerType peerType = peerData.getPeerType();
            return this.getImmutableConnectedPeers().stream().anyMatch(peer -> peerType != PeerType.IP);
        };
        peers.removeIf(isNotIPPeer);

        // Don't consider already connected peers (simple address match)
        peers.removeIf(isConnectedPeer);

        // Don't consider already connected peers (resolved address match)
        // Disabled because this might be too slow if we end up waiting a long time for hostnames to resolve via DNS
        // Which is ok because duplicate connections to the same peer are handled during handshaking
        // peers.removeIf(isResolvedAsConnectedPeer);

        this.checkLongestConnection(now);

        // Any left?
        if (peers.isEmpty()) {
            return null;
        }

        // Pick random peer
        int peerIndex = new Random().nextInt(peers.size());

        // Pick candidate
        PeerData peerData = peers.get(peerIndex);
        //Peer newPeer = new Peer(peerData);
        //newPeer.setIsDataPeer(false);

        Peer newPeer = null;
        try {
            newPeer = PeerFactory.create("peer-data", peerData);
            newPeer.setIsDataPeer(false);
            return newPeer;
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Error while creating peer from peerData", e);
        }

        // Update connection attempt info
        peerData.setLastAttempted(now);

        return newPeer;
    }

    public boolean connectPeer(Peer newPeer) throws InterruptedException {
        // Also checked before creating PeerConnectTask
        var iOHP = getImmutableHandshakedPeers().stream()
                                                .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                                                && peer.getPeerType() == PeerType.IP)
                                                .collect(Collectors.toList());
        if (iOHP.size() >= minOutboundPeers) {
            return false;
        }
        //if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers)
        //    return false;

        SocketChannel socketChannel = newPeer.connect();
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

        // Find (ip) peers that have reached their maximum connection age, and disconnect them
        List<Peer> peersToDisconnect = this.getImmutableConnectedPeers().stream()
                .filter(peer -> !peer.isSyncInProgress())
                .filter(peer -> peer.hasReachedMaxConnectionAge() && peer.getPeerType() == PeerType.IP)
                .collect(Collectors.toList());

        if (peersToDisconnect != null && !peersToDisconnect.isEmpty()) {
            for (Peer peer : peersToDisconnect) {
                LOGGER.debug("Forcing disconnection of peer {} because connection age ({} ms) " +
                        "has reached the maximum ({} ms)", peer, peer.getConnectionAge(), peer.getMaxConnectionAge());
                peer.disconnect("Connection age too old");
            }
        }

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
                return;
            }
            // Fall-through to allow logging
        }

        setInterestOps(selectionKey, interestOps);
    }

    private void setInterestOps(SelectionKey selectionKey, int interestOps) {
        if (!selectionKey.channel().isOpen())
            return;

        LOGGER.trace("Thread {} setting {} interest-ops on channel: {}",
                Thread.currentThread().getId(),
                OP_NAMES[interestOps],
                selectionKey.channel());

        selectionKey.interestOpsOr(interestOps);
    }

    // Peer / Task callbacks

    public void notifyChannelNotWriting(SelectableChannel socketChannel) {
        this.channelsPendingWrite.remove(socketChannel);
    }

    protected void wakeupChannelSelector() {
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

        if (this.isShuttingDown)
            // No need to do any further processing, like re-enabling listen socket or notifying Controller
            return;

        if (getImmutableConnectedPeers().size() < maxPeers - 1
                && serverSelectionKey.isValid()
                && (serverSelectionKey.interestOps() & SelectionKey.OP_ACCEPT) == 0) {
            try {
                LOGGER.debug("Re-enabling accepting incoming connections because the server is not longer full");
                setInterestOps(serverSelectionKey, SelectionKey.OP_ACCEPT);
            } catch (CancelledKeyException e) {
                LOGGER.error("Failed to re-enable accepting of incoming connections: {}", e.getMessage());
            }
        }

        // Notify Controller
        Controller.getInstance().onPeerDisconnect(peer);
    }

    public void peerMisbehaved(Peer peer) {
        try {
            if (Class.forName("org.qortal.network.Peer").isInstance(peer)) {
                PeerData peerData = (PeerData) peer.getPeerData();
                peerData.setLastMisbehaved(NTP.getTime());
            }
        } catch(ClassNotFoundException e){
            LOGGER.error("class 'Peer' not found", e);
        }
    }

    /**
     * Called when a new message arrives for a peer. message can be null if called after connection
     */
    public void onMessage(Peer peer, Message message) {
        if (message != null) {
            LOGGER.trace("[{}} Processing {} message with ID {} from peer {}", peer.getPeerConnectionId(),
                    message.getType().name(), message.getId(), peer);
        }

        //RNSCommon.PeerType pType = PeerType.IP;
        //if (peer.hasActivePeerLink()) {
        //    LOGGER.trace("[{}] Message for ReticulumPeer");
        //    pType = PeerType.RETICULUM;
        //} else {
        //    Handshake handshakeStatus = peer.getHandshakeStatus();
        //    if (handshakeStatus != Handshake.COMPLETED) {
        //        onHandshakingMessage(peer, message, handshakeStatus);
        //        return;
        //    }
        //    //pType = PeerType.IP;
        //}
        Handshake handshakeStatus = peer.getHandshakeStatus();
        if (handshakeStatus != Handshake.COMPLETED) {
            onHandshakingMessage(peer, message, handshakeStatus);
            return;
        }

        // Should be non-handshaking messages from now on

        // Limit threads per message type and discard if there are already too many
        Integer maxThreadsForMessageType = Settings.getInstance().getMaxThreadsForMessageType(message.getType());
        if (maxThreadsForMessageType != null) {
            Integer threadCount = threadsPerMessageType.get(message.getType());
            if (threadCount != null && threadCount >= maxThreadsForMessageType) {
                LOGGER.warn("Discarding {} message as there are already {} active threads", message.getType().name(), threadCount);
                return;
            }
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

        PeerType pType = peer.getPeerData().getPeerType();

        // Ordered by message type value
        switch (message.getType()) {
            case GET_PEERS:
                onGetPeersMessage(peer, message);
                break;

            case PING:
                // Note: probably not necessary as Reticulu PINGs are done by RNS module (non-blocking)
                //if (pType == PeerType.RETICULUM) {
                //    rns.onPingMessage((ReticulumPeer) peer, message);
                //} else {
                //    onPingMessage(peer, message);
                //}
                onPingMessage(peer, message);
                break;

            case HELLO:
            case CHALLENGE:
            case RESPONSE:
                LOGGER.debug("[{}] Unexpected handshaking message {} from peer {}", peer.getPeerConnectionId(),
                        message.getType().name(), peer);
                peer.disconnect("unexpected handshaking message");
                return;

            case PEERS_V2:
                onPeersV2Message(peer, message);
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
            // Still handshaking
            LOGGER.trace("[{}] Handshake status {}, message {} from peer {}", peer.getPeerConnectionId(),
                    handshakeStatus.name(), (message != null ? message.getType().name() : "null"), peer);

            // Check message type is as expected
            if (handshakeStatus.expectedMessageType != null
                    && message.getType() != handshakeStatus.expectedMessageType) {
                LOGGER.debug("[{}] Unexpected {} message from {}, expected {}", peer.getPeerConnectionId(),
                        message.getType().name(), peer, handshakeStatus.expectedMessageType);
                peer.disconnect("unexpected message");
                return;
            }

            Handshake newHandshakeStatus = handshakeStatus.onMessage(peer, message);

            if (newHandshakeStatus == null) {
                // Handshake failure
                LOGGER.debug("[{}] Handshake failure with peer {} message {}", peer.getPeerConnectionId(), peer,
                        message.getType().name());
                peer.disconnect("handshake failure");
                return;
            }

            if (peer.isOutbound()) {
                // If we made outbound connection then we need to act first
                newHandshakeStatus.action(peer);
            } else {
                // We have inbound connection so we need to respond in kind with what we just received
                handshakeStatus.action(peer);
            }
            peer.setHandshakeStatus(newHandshakeStatus);

            if (newHandshakeStatus == Handshake.COMPLETED) {
                this.onHandshakeCompleted(peer);
            }
        } finally {
            peer.resetHandshakeMessagePending();
        }
    }

    private void onGetPeersMessage(Peer peer, Message message) {
        // Send our known peers
        if (!peer.sendMessage(this.buildPeersMessage(peer))) {
            peer.disconnect("failed to send peers list");
        }
    }

    private void onPingMessage(Peer peer, Message message) {
        PingMessage pingMessage = (PingMessage) message;

        // Generate 'pong' using same ID
        PingMessage pongMessage = new PingMessage();
        pongMessage.setId(pingMessage.getId());

        if (!peer.sendMessage(pongMessage)) {
            peer.disconnect("failed to send ping reply");
        }
    }

    private void onPeersV2Message(Peer peer, Message message) {
        PeersV2Message peersV2Message = (PeersV2Message) message;

        //if (pType == PeerType.RETICULUM) {
        //    rns.onPeersV2Message(peer, message);
        //}
        if (peer.hasActivePeerLink()) {
            RNS.getInstance().onPeersV2Message(peer, peersV2Message);
            return;
        }

        List<PeerAddress> peerV2Addresses = peersV2Message.getPeerAddresses();

        // First entry contains remote peer's listen port but empty address.
        int peerPort = peerV2Addresses.get(0).getPort();
        peerV2Addresses.remove(0);

        // If inbound peer, use listen port and socket address to recreate first entry
        if (!peer.isOutbound()) {
            try {
                String host = peer.getPeerData().getAddress().getHost();
                //PeerAddress sendingPeerAddress = PeerAddress.fromString(host + ":" + peerPort);
                PeerAddress sendingPeerAddress = PeerAddressFactory.create("host-port", host, peerPort);
                LOGGER.trace("PEERS_V2 sending peer's listen address: {}", sendingPeerAddress.toString());
                peerV2Addresses.add(0, sendingPeerAddress);
            } catch (ReflectiveOperationException e) {
                LOGGER.error("Can't create peer address: {}", e.getMessage());
            }
        }

        opportunisticMergePeers(peer.toString(), peerV2Addresses);
    }

    protected void onHandshakeCompleted(Peer peer) {
        LOGGER.debug("[{}] Handshake completed with peer {} on {}", peer.getPeerConnectionId(), peer,
                peer.getPeersVersionString());

        // Are we already connected to this peer?
        Peer existingPeer = getHandshakedPeerWithPublicKey(peer.getPeersPublicKey());
        // NOTE: actual object reference compare, not Peer.equals()
        if (existingPeer != peer) {
            LOGGER.info("[{}] We already have a connection with peer {} - discarding",
                    peer.getPeerConnectionId(), peer);
            peer.disconnect("existing connection");
            return;
        }

        // Add to handshaked peers cache
        this.addHandshakedPeer(peer);

        // Make a note that we've successfully completed handshake (and when)
        peer.getPeerData().setLastConnected(NTP.getTime());

        // Process any pending signature requests, as this peer may have been connected for this purpose only
        List<byte[]> pendingSignatureRequests = new ArrayList<>(peer.getPendingSignatureRequests());
        if (pendingSignatureRequests != null && !pendingSignatureRequests.isEmpty()) {
            for (byte[] signature : pendingSignatureRequests) {
                this.requestDataFromConnectedPeer(peer, signature);
                peer.removePendingSignatureRequest(signature);
            }
        }

        // FUTURE: we may want to disconnect from this peer if we've finished requesting data from it

        // Start regular pings
        // Note: For Reticulum peers regular ping is enabled the moment a Link is established
        peer.startPings();

        // Only the outbound side needs to send anything (after we've received handshake-completing response).
        // (If inbound sent anything here, it's possible it could be processed out-of-order with handshake message).

        if (peer.isOutbound()) {
            if (!Settings.getInstance().isLite()) {
                // Send our height / chain tip info
                Message message = this.buildHeightOrChainTipInfo(peer);

                if (message == null) {
                    peer.disconnect("Couldn't build our chain tip info");
                    return;
                }

                if (!peer.sendMessage(message)) {
                    peer.disconnect("failed to send height / chain tip info");
                    return;
                }
            }

            // Send our peers list
            Message peersMessage = this.buildPeersMessage(peer);
            if (!peer.sendMessage(peersMessage)) {
                peer.disconnect("failed to send peers list");
            }

            // Request their peers list
            Message getPeersMessage = new GetPeersMessage();
            if (!peer.sendMessage(getPeersMessage)) {
                peer.disconnect("failed to request peers list");
            }
        }

        // Ask Controller if they want to do anything
        Controller.getInstance().onPeerHandshakeCompleted(peer);
    }

    // Message-building calls

    /**
     * Returns PEERS message made from peers we've connected to recently, and this node's details
     */
    public Message buildPeersMessage(Peer peer) {
        List<PeerData> knownPeers = this.getAllKnownPeers();

        // Filter out peers that we've not connected to ever or within X milliseconds
        final long connectionThreshold = NTP.getTime() - RECENT_CONNECTION_THRESHOLD;
        Predicate<PeerData> notRecentlyConnected = peerData -> {
            final Long lastAttempted = peerData.getLastAttempted();
            final Long lastConnected = peerData.getLastConnected();

            if (lastAttempted == null || lastConnected == null) {
                return true;
            }

            if (lastConnected < lastAttempted) {
                return true;
            }

            if (lastConnected < connectionThreshold) {
                return true;
            }

            return false;
        };
        knownPeers.removeIf(notRecentlyConnected);

        List<PeerAddress> peerAddresses = new ArrayList<>();

        for (PeerData peerData : knownPeers) {
            try {
                InetAddress address = InetAddress.getByName(peerData.getAddress().getHost());

                // Don't send 'local' addresses if peer is not 'local'.
                // e.g. don't send localhost:9084 to node4.qortal.org
                //var p = PeerFactory.create("address", address);
                //if (!peer.isLocal() && p.isAddressLocal(address)) {
                if (!peer.isLocal()) {
                    continue;
                }

                peerAddresses.add(peerData.getAddress());
            } catch (UnknownHostException e) {
                // Couldn't resolve hostname to IP address so discard
            }
        }

        // New format PEERS_V2 message that supports hostnames, IPv6 and ports
        return new PeersV2Message(peerAddresses);
    }

    /** Builds either (legacy) HeightV2Message or (newer) BlockSummariesV2Message, depending on peer version.
     *
     *  @return Message, or null if DataException was thrown.
     */
    public Message buildHeightOrChainTipInfo(Peer peer) {
        if (peer.getPeersVersion() >= BlockSummariesV2Message.MINIMUM_PEER_VERSION) {
            int latestHeight = Controller.getInstance().getChainHeight();

            try (final Repository repository = RepositoryManager.getRepository()) {
                List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
                return new BlockSummariesV2Message(latestBlockSummaries);
            } catch (DataException e) {
                return null;
            }
        } else {
            // For older peers
            BlockData latestBlockData = Controller.getInstance().getChainTip();
            return new HeightV2Message(latestBlockData.getHeight(), latestBlockData.getSignature(),
                    latestBlockData.getTimestamp(), latestBlockData.getMinterPublicKey());
        }
    }

    public void broadcastOurChain() {
        BlockData latestBlockData = Controller.getInstance().getChainTip();
        int latestHeight = latestBlockData.getHeight();

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
            Message latestBlockSummariesMessage = new BlockSummariesV2Message(latestBlockSummaries);

            // For older peers
            Message heightMessage = new HeightV2Message(latestBlockData.getHeight(), latestBlockData.getSignature(),
                    latestBlockData.getTimestamp(), latestBlockData.getMinterPublicKey());

            Network.getInstance().broadcast(broadcastPeer -> broadcastPeer.getPeersVersion() >= BlockSummariesV2Message.MINIMUM_PEER_VERSION
                    ? latestBlockSummariesMessage
                    : heightMessage
            );
        } catch (DataException e) {
            LOGGER.warn("Couldn't broadcast our chain tip info", e);
        }
    }

    public Message buildNewTransactionMessage(Peer peer, TransactionData transactionData) {
        // In V2 we send out transaction signature only and peers can decide whether to request the full transaction
        return new TransactionSignaturesMessage(Collections.singletonList(transactionData.getSignature()));
    }

    public Message buildGetUnconfirmedTransactionsMessage(Peer peer) {
        return new GetUnconfirmedTransactionsMessage();
    }


    // External IP / peerAddress tracking

    public void ourPeerAddressUpdated(String peerAddress) {
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
        this.ourExternalPort = Integer.parseInt(parts[1]);

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

        // Count the number of consecutive IP address readings
        String lastReading = null;
        int consecutiveReadings = 0;
        for (int i = size-1; i >= 0; i--) {
            String reading = ipAddressHistory.get(i);
            if (lastReading != null) {
                 if (Objects.equals(reading, lastReading)) {
                    consecutiveReadings++;
                 }
                 else {
                     consecutiveReadings = 0;
                 }
            }
            lastReading = reading;
        }

        if (consecutiveReadings >= consecutiveReadingsRequired) {
            // Last 10 readings were the same - i.e. more than one peer agreed on the new IP address...
            String ip = ipAddressHistory.get(size - 1);
            if (ip != null && !Objects.equals(ip, "null")) {
                if (!Objects.equals(ip, this.ourExternalIpAddress)) {
                    // ... and the readings were different to our current recorded value, so
                    // update our external IP address value
                    this.ourExternalIpAddress = ip;
                    this.onExternalIpUpdate(ip);
                }
            }
        }
    }

    public void onExternalIpUpdate(String ipAddress) {
        LOGGER.info("External IP address updated to {}", ipAddress);
    }

    public String getOurExternalIpAddress() {
        // FUTURE: replace port if UPnP is active, as it will be more accurate
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
        LOGGER.info("[{}] No longer considering peer address {} as it connects to self",
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
            numDeleted = this.allKnownPeers.size();
            this.allKnownPeers.clear();
        }

        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.disconnect("to be forgotten");
        }

        return numDeleted;
    }

    private void disconnectPeer(PeerAddress peerAddress) {
        // Disconnect peer
        try {
            InetSocketAddress knownAddress = peerAddress.toSocketAddress();

            //List<Peer> peers = this.getImmutableConnectedPeers().stream()
            //        .filter(peer -> Peer.addressEquals(knownAddress, peer.getResolvedAddress()))
            //        .collect(Collectors.toList());
            List<Peer> peers = this.getImmutableConnectedPeers().stream()
                    .filter(peer -> knownAddress.equals(peer.getResolvedAddress()))
                    .collect(Collectors.toList());

            for (Peer peer : peers) {
                peer.disconnect("to be forgotten");
            }
        } catch (UnknownHostException e) {
            // Unknown host isn't going to match any of our connected peers so ignore
        }
    }

    // Network-wide calls

    public void prunePeers() throws DataException {
        final Long now = NTP.getTime();
        if (now == null) {
            return;
        }

        // Note: moved to Controller
        // prune ReticulumPeer(s), primarily any peer that has no ACTIVE link
        //RNS.getInstance().prunePeers();
        //rns.prunePeers();

        // Disconnect peers that are stuck during handshake
        // Needs a mutable copy of the unmodifiableList
        List<Peer> handshakePeers = new ArrayList<>(this.getImmutableConnectedPeers());

        // Disregard any ReticulumPeer (handled in RNS)
        Predicate<Peer> isReticulumPeer = peer -> {
            return this.getImmutableConnectedPeers().stream().anyMatch(p -> p.getPeerData().getPeerType() == PeerType.RETICULUM);
        };
        handshakePeers.removeIf(isReticulumPeer);

        // Disregard peers that have completed handshake or only connected recently
        handshakePeers.removeIf(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                || peer.getConnectionTimestamp() == null || peer.getConnectionTimestamp() > now - HANDSHAKE_TIMEOUT
                || peer.getPeerData().getPeerType() == PeerType.RETICULUM);

        for (Peer peer : handshakePeers) {
            peer.disconnect(String.format("handshake timeout at %s", peer.getHandshakeStatus().name()));
        }

        // Prune 'old' peers from repository...
        // Pruning peers isn't critical so no need to block for a repository instance.
        synchronized (this.allKnownPeers) {
            // Fetch all known peers
            List<PeerData> peers = new ArrayList<>(this.allKnownPeers);

            //// caveman debugging
            //for (PeerData pd: peers) {
            //    if (pd.getPeerType() == PeerType.RETICULUM) {
            //        LOGGER.debug("prunePeers - ReticuluPeer, peer data: {}", pd);
            //    } else {
            //        LOGGER.debug("prunePeers - NON-R, peer data: {}", pd);
            //    }
            //}

            // 'Old' peers:
            // We attempted to connect within the last day
            // but we last managed to connect over a week ago.
            Predicate<PeerData> isNotOldPeer = peerData -> {
                if (peerData.getLastAttempted() == null
                        || peerData.getLastAttempted() < now - OLD_PEER_ATTEMPTED_PERIOD) {
                    return true;
                }

                if (peerData.getLastConnected() == null
                        || peerData.getLastConnected() > now - OLD_PEER_CONNECTION_PERIOD) {
                    return true;
                }

                return false;
            };

            Predicate<PeerData> isReticulumPeerData = peerData -> {
                PeerType peerType = peerData.getPeerType();
                return this.getImmutableConnectedPeers().stream().anyMatch(Ppeer -> peerType == PeerType.RETICULUM);
            };

            // Disregard peers that are NOT 'old'
            peers.removeIf(isNotOldPeer);

            // Don't consider already connected peers (simple address match)
            peers.removeIf(isConnectedPeer);

            // Disregard any ReticulumPeer (handled in RNS)
            peers.removeIf(isReticulumPeerData);

            for (PeerData peerData : peers) {
                if (peerData.getPeerType() == PeerType.RETICULUM) {
                    // ignore ReticuluPeer (handled in RNS)
                    continue;
                } else {
                    // Delete from known peer cache too
                    this.allKnownPeers.remove(peerData);
                }
            }
        }
    }

    public boolean mergePeers(String addedBy, long addedWhen, List<PeerAddress> peerAddresses) throws DataException {
        mergePeersLock.lock();

        try{
            return this.mergePeersUnlocked(addedBy, addedWhen, peerAddresses);
        } finally {
            mergePeersLock.unlock();
        }
    }

    private void opportunisticMergePeers(String addedBy, List<PeerAddress> peerAddresses) {
        final Long addedWhen = NTP.getTime();
        if (addedWhen == null) {
            return;
        }

        // Serialize using lock to prevent repository deadlocks
        if (!mergePeersLock.tryLock()) {
            return;
        }

        try {
            // Merging peers isn't critical so don't block for a repository instance.

            this.mergePeersUnlocked(addedBy, addedWhen, peerAddresses);

        } catch (DataException e) {
            // Already logged by this.mergePeersUnlocked()
        } finally {
            mergePeersLock.unlock();
        }
    }

    private boolean mergePeersUnlocked(String addedBy, long addedWhen, List<PeerAddress> peerAddresses)
            throws DataException {
        List<String> fixedNetwork = Settings.getInstance().getFixedNetwork();
        if (fixedNetwork != null && !fixedNetwork.isEmpty()) {
            return false;
        }
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

    public void broadcast(Function<Peer, Message> peerMessageBuilder) {
        for (Peer peer : getImmutableHandshakedPeers()) {
            if (this.isShuttingDown)
                return;

            Message message = peerMessageBuilder.apply(peer);

            if (message == null) {
                continue;
            }

            if (!peer.sendMessage(message)) {
                if (peer.getPeerData().getPeerType() == PeerType.RETICULUM) {
                    LOGGER.debug("broadcast - ReticulumPeer without ACTIVE link");
                } else {
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

        //// shutdown Reticulum peers and instance
        //// gracefully close links of peers that point to us
        //for (ReticulumPeer p: RNS.getInstance().getIncomingPeers()) {
        //    var pl = p.getPeerLink();
        //    if (nonNull(pl) & (pl.getStatus() == ACTIVE)) {
        //        p.sendCloseToRemote(pl);
        //    }
        //}
        //// gracefully disconnect outgoing peers
        //for (ReticulumPeer p: RNS.getInstance().getLinkedPeers()) {
        //    LOGGER.info("shutting down peer: {}", encodeHexString(p.getDestinationHash()));
        //    p.shutdown();
        //    try {
        //        TimeUnit.SECONDS.sleep(1); // allow for peers to disconnect gracefully
        //    } catch (InterruptedException e) {
        //        LOGGER.error("exception: ", e);
        //    }
        //}
        //// shut down Reticulum
        //LOGGER.info("Shutting down Reticulum...");
        //RNS.getInstance().getReticulum().exitHandler();

        //// shutdown Reticulum, try to gracefully shut down peers (moved to Controller)
        //LOGGER.debug("Shutting down RNS");
        //rns.shutdown();
        //LOGGER.debug("RNS shutdown complete.");

        // Stop processing threads
        try {
            if (!this.networkEPC.shutdown(5000)) {
                LOGGER.warn("Network threads failed to terminate");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for networking threads to terminate");
        }

        try( Repository repository = RepositoryManager.getRepository() ){

            // reset all known peers in database
            int deletedCount = repository.getNetworkRepository().deleteAllPeers();

            LOGGER.debug("Deleted {} known peers", deletedCount);

            List<PeerData> knownPeersToProcess;
            synchronized (this.allKnownPeers) {
                knownPeersToProcess = new ArrayList<>(this.allKnownPeers);
            }

            int addedPeerCount = 0;

            // save all known peers for next start up
            for (PeerData knownPeerToProcess : knownPeersToProcess) {
                if (knownPeerToProcess.getPeerType() == PeerType.RETICULUM) {
                    // don't save any ReticulumPeer
                    continue;
                }
                repository.getNetworkRepository().save(knownPeerToProcess);
                addedPeerCount++;
            }

            repository.saveChanges();

            LOGGER.debug("Added {} known peers", addedPeerCount);
        } catch (DataException e) {
            LOGGER.error(e.getMessage(), e);
        }

        // Close all peer connections
        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.shutdown();
        }

        // shutdown Reticulum, try to gracefully shut down peers
        LOGGER.debug("Shutting down RNS");
        rns.shutdown();
        LOGGER.debug("RNS shutdown complete.");
    }

}
