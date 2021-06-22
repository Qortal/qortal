package org.qortal.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
import org.qortal.data.block.BlockData;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// For managing peers
public class Network {
    private static final Logger LOGGER = LogManager.getLogger(Network.class);
    private static Network instance;

    private static final int LISTEN_BACKLOG = 10;
    /**
     * How long before retrying after a connection failure, in milliseconds.
     */
    private static final long CONNECT_FAILURE_BACKOFF = 5 * 60 * 1000L; // ms
    /**
     * How long between informational broadcasts to all connected peers, in milliseconds.
     */
    private static final long BROADCAST_INTERVAL = 60 * 1000L; // ms
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
            "node.qortal.ru", "node2.qortal.ru", "node3.qortal.ru", "node.qortal.uk", "node22.qortal.org",
            "cinfu1.crowetic.com", "node.cwd.systems"
    };

    private static final long NETWORK_EPC_KEEPALIVE = 10L; // seconds

    public static final int MAX_SIGNATURES_PER_REPLY = 500;
    public static final int MAX_BLOCK_SUMMARIES_PER_REPLY = 500;

    private static final long DISCONNECTION_CHECK_INTERVAL = 10 * 1000L; // milliseconds

    // Generate our node keys / ID
    private final Ed25519PrivateKeyParameters edPrivateKeyParams = new Ed25519PrivateKeyParameters(new SecureRandom());
    private final Ed25519PublicKeyParameters edPublicKeyParams = edPrivateKeyParams.generatePublicKey();
    private final String ourNodeId = Crypto.toNodeAddress(edPublicKeyParams.getEncoded());

    private final int maxMessageSize;
    private final int minOutboundPeers;
    private final int maxPeers;

    private long nextDisconnectionCheck = 0L;

    private final List<PeerData> allKnownPeers = new ArrayList<>();
    private final List<Peer> connectedPeers = new ArrayList<>();
    private final List<PeerAddress> selfPeers = new ArrayList<>();

    private final ExecuteProduceConsume networkEPC;
    private Selector channelSelector;
    private ServerSocketChannel serverChannel;
    private Iterator<SelectionKey> channelIterator = null;

    // volatile because value is updated inside any one of the EPC threads
    private volatile long nextConnectTaskTimestamp = 0L; // ms - try first connect once NTP syncs

    private final ExecutorService broadcastExecutor = Executors.newCachedThreadPool();
    // volatile because value is updated inside any one of the EPC threads
    private volatile long nextBroadcastTimestamp = 0L; // ms - try first broadcast once NTP syncs

    private final Lock mergePeersLock = new ReentrantLock();

    // Constructors

    private Network() {
        maxMessageSize = 4 + 1 + 4 + BlockChain.getInstance().getMaxBlockSize();

        minOutboundPeers = Settings.getInstance().getMinOutboundPeers();
        maxPeers = Settings.getInstance().getMaxPeers();

        // We'll use a cached thread pool but with more aggressive timeout.
        ExecutorService networkExecutor = new ThreadPoolExecutor(1,
                Settings.getInstance().getMaxNetworkThreadPoolSize(),
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new NamedThreadFactory("Network-EPC"));
        networkEPC = new NetworkProcessor(networkExecutor);
    }

    public void start() throws IOException, DataException {
        // Grab P2P port from settings
        int listenPort = Settings.getInstance().getListenPort();

        // Grab P2P bind address from settings
        try {
            InetAddress bindAddr = InetAddress.getByName(Settings.getInstance().getBindAddress());
            InetSocketAddress endpoint = new InetSocketAddress(bindAddr, listenPort);

            channelSelector = Selector.open();

            // Set up listen socket
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(endpoint, LISTEN_BACKLOG);
            serverChannel.register(channelSelector, SelectionKey.OP_ACCEPT);
        } catch (UnknownHostException e) {
            LOGGER.error("Can't bind listen socket to address {}", Settings.getInstance().getBindAddress());
            throw new IOException("Can't bind listen socket to address", e);
        } catch (IOException e) {
            LOGGER.error("Can't create listen socket: {}", e.getMessage());
            throw new IOException("Can't create listen socket", e);
        }

        // Load all known peers from repository
        try (Repository repository = RepositoryManager.getRepository()) {
            synchronized (this.allKnownPeers) {
                this.allKnownPeers.addAll(repository.getNetworkRepository().getAllPeers());
            }
        }

        // Start up first networking thread
        networkEPC.start();
    }

    // Getters / setters

    public static synchronized Network getInstance() {
        if (instance == null) {
            instance = new Network();
        }

        return instance;
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
            return new ArrayList<>(this.allKnownPeers);
        }
    }

    public List<Peer> getConnectedPeers() {
        synchronized (this.connectedPeers) {
            return new ArrayList<>(this.connectedPeers);
        }
    }

    public List<PeerAddress> getSelfPeers() {
        synchronized (this.selfPeers) {
            return new ArrayList<>(this.selfPeers);
        }
    }

    /**
     * Returns list of connected peers that have completed handshaking.
     */
    public List<Peer> getHandshakedPeers() {
        synchronized (this.connectedPeers) {
            return this.connectedPeers.stream()
                    .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Returns list of peers we connected to that have completed handshaking.
     */
    public List<Peer> getOutboundHandshakedPeers() {
        synchronized (this.connectedPeers) {
            return this.connectedPeers.stream()
                    .filter(peer -> peer.isOutbound() && peer.getHandshakeStatus() == Handshake.COMPLETED)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Returns first peer that has completed handshaking and has matching public key.
     */
    public Peer getHandshakedPeerWithPublicKey(byte[] publicKey) {
        synchronized (this.connectedPeers) {
            return this.connectedPeers.stream()
                    .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                            && Arrays.equals(peer.getPeersPublicKey(), publicKey))
                    .findFirst().orElse(null);
        }
    }

    // Peer list filters

    /**
     * Must be inside <tt>synchronized (this.selfPeers) {...}</tt>
     */
    private final Predicate<PeerData> isSelfPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.selfPeers.stream().anyMatch(selfPeer -> selfPeer.equals(peerAddress));
    };

    /**
     * Must be inside <tt>synchronized (this.connectedPeers) {...}</tt>
     */
    private final Predicate<PeerData> isConnectedPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.connectedPeers.stream().anyMatch(peer -> peer.getPeerData().getAddress().equals(peerAddress));
    };

    /**
     * Must be inside <tt>synchronized (this.connectedPeers) {...}</tt>
     */
    private final Predicate<PeerData> isResolvedAsConnectedPeer = peerData -> {
        try {
            InetSocketAddress resolvedSocketAddress = peerData.getAddress().toSocketAddress();
            return this.connectedPeers.stream()
                    .anyMatch(peer -> peer.getResolvedAddress().equals(resolvedSocketAddress));
        } catch (UnknownHostException e) {
            // Can't resolve - no point even trying to connect
            return true;
        }
    };

    // Initial setup

    public static void installInitialPeers(Repository repository) throws DataException {
        for (String address : INITIAL_PEERS) {
            PeerAddress peerAddress = PeerAddress.fromString(address);

            PeerData peerData = new PeerData(peerAddress, System.currentTimeMillis(), "INIT");
            repository.getNetworkRepository().save(peerData);
        }

        repository.saveChanges();
    }

    // Main thread

    class NetworkProcessor extends ExecuteProduceConsume {

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
            for (Peer peer : getConnectedPeers()) {
                Task peerTask = peer.getMessageTask();
                if (peerTask != null) {
                    return peerTask;
                }
            }

            return null;
        }

        private Task maybeProducePeerPingTask(Long now) {
            // Ask connected peers whether they need a ping
            for (Peer peer : getHandshakedPeers()) {
                Task peerTask = peer.getPingTask(now);
                if (peerTask != null) {
                    return peerTask;
                }
            }

            return null;
        }

        class PeerConnectTask implements Task {
            private final Peer peer;

            PeerConnectTask(Peer peer) {
                this.peer = peer;
            }

            @Override
            public String getName() {
                return "PeerConnectTask";
            }

            @Override
            public void perform() throws InterruptedException {
                connectPeer(peer);
            }
        }

        private Task maybeProduceConnectPeerTask(Long now) throws InterruptedException {
            if (now == null || now < nextConnectTaskTimestamp) {
                return null;
            }

            if (getOutboundHandshakedPeers().size() >= minOutboundPeers) {
                return null;
            }

            nextConnectTaskTimestamp = now + 1000L;

            Peer targetPeer = getConnectablePeer(now);
            if (targetPeer == null) {
                return null;
            }

            // Create connection task
            return new PeerConnectTask(targetPeer);
        }

        private Task maybeProduceBroadcastTask(Long now) {
            if (now == null || now < nextBroadcastTimestamp) {
                return null;
            }

            nextBroadcastTimestamp = now + BROADCAST_INTERVAL;
            return new BroadcastTask();
        }

        private Task maybeProduceChannelTask(boolean canBlock) throws InterruptedException {
            final SelectionKey nextSelectionKey;

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
                }

                if (channelIterator.hasNext()) {
                    nextSelectionKey = channelIterator.next();
                    channelIterator.remove();
                } else {
                    nextSelectionKey = null;
                    channelIterator = null; // Nothing to do so reset iterator to cause new select
                }

                LOGGER.trace("Thread {}, nextSelectionKey {}, channelIterator now {}",
                        Thread.currentThread().getId(), nextSelectionKey, channelIterator);
            }

            if (nextSelectionKey == null) {
                return null;
            }

            return new ChannelTask(nextSelectionKey);
        }
    }

    protected void acceptConnection(ServerSocketChannel serverSocketChannel) throws InterruptedException {
        SocketChannel socketChannel;

        try {
            socketChannel = serverSocketChannel.accept();
        } catch (IOException e) {
            return;
        }

        // No connection actually accepted?
        if (socketChannel == null) {
            return;
        }

        final Long now = NTP.getTime();
        Peer newPeer;

        try {
            if (now == null) {
                LOGGER.debug("Connection discarded from peer {} due to lack of NTP sync",
                        PeerAddress.fromSocket(socketChannel.socket()));
                socketChannel.close();
                return;
            }

            synchronized (this.connectedPeers) {
                if (connectedPeers.size() >= maxPeers) {
                    // We have enough peers
                    LOGGER.debug("Connection discarded from peer {}", PeerAddress.fromSocket(socketChannel.socket()));
                    socketChannel.close();
                    return;
                }

                LOGGER.debug("Connection accepted from peer {}", PeerAddress.fromSocket(socketChannel.socket()));

                newPeer = new Peer(socketChannel, channelSelector);
                this.connectedPeers.add(newPeer);
            }
        } catch (IOException e) {
            if (socketChannel.isOpen()) {
                try {
                    socketChannel.close();
                } catch (IOException ce) {
                    // Couldn't close?
                }
            }
            return;
        }

        this.onPeerReady(newPeer);
    }

    private Peer getConnectablePeer(final Long now) throws InterruptedException {
        // We can't block here so use tryRepository(). We don't NEED to connect a new peer.
        try (Repository repository = RepositoryManager.tryRepository()) {
            if (repository == null) {
                return null;
            }

            // Find an address to connect to
            List<PeerData> peers = this.getAllKnownPeers();

            // Don't consider peers with recent connection failures
            final long lastAttemptedThreshold = now - CONNECT_FAILURE_BACKOFF;
            peers.removeIf(peerData -> peerData.getLastAttempted() != null
                    && (peerData.getLastConnected() == null
                    || peerData.getLastConnected() < peerData.getLastAttempted())
                    && peerData.getLastAttempted() > lastAttemptedThreshold);

            // Don't consider peers that we know loop back to ourself
            synchronized (this.selfPeers) {
                peers.removeIf(isSelfPeer);
            }

            synchronized (this.connectedPeers) {
                // Don't consider already connected peers (simple address match)
                peers.removeIf(isConnectedPeer);

                // Don't consider already connected peers (resolved address match)
                // XXX This might be too slow if we end up waiting a long time for hostnames to resolve via DNS
                peers.removeIf(isResolvedAsConnectedPeer);

                this.checkLongestConnection(now);
            }

            // Any left?
            if (peers.isEmpty()) {
                return null;
            }

            // Pick random peer
            int peerIndex = new Random().nextInt(peers.size());

            // Pick candidate
            PeerData peerData = peers.get(peerIndex);
            Peer newPeer = new Peer(peerData);

            // Update connection attempt info
            peerData.setLastAttempted(now);
            synchronized (this.allKnownPeers) {
                repository.getNetworkRepository().save(peerData);
                repository.saveChanges();
            }

            return newPeer;
        } catch (DataException e) {
            LOGGER.error("Repository issue while finding a connectable peer", e);
            return null;
        }
    }

    private void connectPeer(Peer newPeer) throws InterruptedException {
        SocketChannel socketChannel = newPeer.connect(this.channelSelector);
        if (socketChannel == null) {
            return;
        }

        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        synchronized (this.connectedPeers) {
            this.connectedPeers.add(newPeer);
        }

        this.onPeerReady(newPeer);
    }

    protected Peer getPeerFromChannel(SocketChannel socketChannel) {
        synchronized (this.connectedPeers) {
            for (Peer peer : this.connectedPeers) {
                if (peer.getSocketChannel() == socketChannel) {
                    return peer;
                }
            }
        }

        return null;
    }

    private void checkLongestConnection(Long now) {
        if (now == null || now < nextDisconnectionCheck) {
            return;
        }

        // Find peers that have reached their maximum connection age, and disconnect them
        List<Peer> peersToDisconnect = this.connectedPeers.stream()
                .filter(peer -> !peer.isSyncInProgress())
                .filter(peer -> peer.hasReachedMaxConnectionAge())
                .collect(Collectors.toList());

        if (peersToDisconnect != null && peersToDisconnect.size() > 0) {
            for (Peer peer : peersToDisconnect) {
                LOGGER.info("Forcing disconnection of peer {} because connection age ({} ms) " +
                        "has reached the maximum ({} ms)", peer, peer.getConnectionAge(), peer.getMaxConnectionAge());
                peer.disconnect("Connection age too old");
            }
        }

        // Check again after a minimum fixed interval
        nextDisconnectionCheck = now + DISCONNECTION_CHECK_INTERVAL;
    }

    // Peer callbacks

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
        // Notify Controller
        Controller.getInstance().onPeerDisconnect(peer);
        if (peer.getConnectionEstablishedTime() > 0L) {
            LOGGER.debug("[{}] Disconnected from peer {}", peer.getPeerConnectionId(), peer);
        } else {
            LOGGER.debug("[{}] Failed to connect to peer {}", peer.getPeerConnectionId(), peer);
        }

        synchronized (this.connectedPeers) {
            this.connectedPeers.remove(peer);
        }
    }

    public void peerMisbehaved(Peer peer) {
        PeerData peerData = peer.getPeerData();
        peerData.setLastMisbehaved(NTP.getTime());

        // Only update repository if outbound peer
        if (peer.isOutbound()) {
            try (Repository repository = RepositoryManager.getRepository()) {
                synchronized (this.allKnownPeers) {
                    repository.getNetworkRepository().save(peerData);
                    repository.saveChanges();
                }
            } catch (DataException e) {
                LOGGER.warn("Repository issue while updating peer synchronization info", e);
            }
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

        Handshake handshakeStatus = peer.getHandshakeStatus();
        if (handshakeStatus != Handshake.COMPLETED) {
            onHandshakingMessage(peer, message, handshakeStatus);
            return;
        }

        // Should be non-handshaking messages from now on

        // Ordered by message type value
        switch (message.getType()) {
            case GET_PEERS:
                onGetPeersMessage(peer, message);
                break;

            case PING:
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

        List<PeerAddress> peerV2Addresses = peersV2Message.getPeerAddresses();

        // First entry contains remote peer's listen port but empty address.
        int peerPort = peerV2Addresses.get(0).getPort();
        peerV2Addresses.remove(0);

        // If inbound peer, use listen port and socket address to recreate first entry
        if (!peer.isOutbound()) {
            String host = peer.getPeerData().getAddress().getHost();
            PeerAddress sendingPeerAddress = PeerAddress.fromString(host + ":" + peerPort);
            LOGGER.trace("PEERS_V2 sending peer's listen address: {}", sendingPeerAddress.toString());
            peerV2Addresses.add(0, sendingPeerAddress);
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

        // Make a note that we've successfully completed handshake (and when)
        peer.getPeerData().setLastConnected(NTP.getTime());

        // Update connection info for outbound peers only
        if (peer.isOutbound()) {
            try (Repository repository = RepositoryManager.getRepository()) {
                synchronized (this.allKnownPeers) {
                    repository.getNetworkRepository().save(peer.getPeerData());
                    repository.saveChanges();
                }
            } catch (DataException e) {
                LOGGER.error("[{}] Repository issue while trying to update outbound peer {}",
                        peer.getPeerConnectionId(), peer, e);
            }
        }

        // Start regular pings
        peer.startPings();

        // Only the outbound side needs to send anything (after we've received handshake-completing response).
        // (If inbound sent anything here, it's possible it could be processed out-of-order with handshake message).

        if (peer.isOutbound()) {
            // Send our height
            Message heightMessage = buildHeightMessage(peer, Controller.getInstance().getChainTip());
            if (!peer.sendMessage(heightMessage)) {
                peer.disconnect("failed to send height/info");
                return;
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
                if (!peer.isLocal() && Peer.isAddressLocal(address)) {
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

    public Message buildHeightMessage(Peer peer, BlockData blockData) {
        // HEIGHT_V2 contains way more useful info
        return new HeightV2Message(blockData.getHeight(), blockData.getSignature(),
                blockData.getTimestamp(), blockData.getMinterPublicKey());
    }

    public Message buildNewTransactionMessage(Peer peer, TransactionData transactionData) {
        // In V2 we send out transaction signature only and peers can decide whether to request the full transaction
        return new TransactionSignaturesMessage(Collections.singletonList(transactionData.getSignature()));
    }

    public Message buildGetUnconfirmedTransactionsMessage(Peer peer) {
        return new GetUnconfirmedTransactionsMessage();
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
        int numDeleted;

        synchronized (this.allKnownPeers) {
            this.allKnownPeers.removeIf(peerData -> peerData.getAddress().equals(peerAddress));

            try (Repository repository = RepositoryManager.getRepository()) {
                numDeleted = repository.getNetworkRepository().delete(peerAddress);
                repository.saveChanges();
            }
        }

        disconnectPeer(peerAddress);

        return numDeleted != 0;
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

        for (Peer peer : this.getConnectedPeers()) {
            peer.disconnect("to be forgotten");
        }

        return numDeleted;
    }

    private void disconnectPeer(PeerAddress peerAddress) {
        // Disconnect peer
        try {
            InetSocketAddress knownAddress = peerAddress.toSocketAddress();

            List<Peer> peers = this.getConnectedPeers();
            peers.removeIf(peer -> !Peer.addressEquals(knownAddress, peer.getResolvedAddress()));

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

        // Disconnect peers that are stuck during handshake
        List<Peer> handshakePeers = this.getConnectedPeers();

        // Disregard peers that have completed handshake or only connected recently
        handshakePeers.removeIf(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                || peer.getConnectionTimestamp() == null || peer.getConnectionTimestamp() > now - HANDSHAKE_TIMEOUT);

        for (Peer peer : handshakePeers) {
            peer.disconnect(String.format("handshake timeout at %s", peer.getHandshakeStatus().name()));
        }

        // Prune 'old' peers from repository...
        // Pruning peers isn't critical so no need to block for a repository instance.
        try (Repository repository = RepositoryManager.tryRepository()) {
            if (repository == null) {
                return;
            }

            synchronized (this.allKnownPeers) {
                // Fetch all known peers
                List<PeerData> peers = new ArrayList<>(this.allKnownPeers);

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

                // Disregard peers that are NOT 'old'
                peers.removeIf(isNotOldPeer);

                // Don't consider already connected peers (simple address match)
                synchronized (this.connectedPeers) {
                    peers.removeIf(isConnectedPeer);
                }

                for (PeerData peerData : peers) {
                    LOGGER.debug("Deleting old peer {} from repository", peerData.getAddress().toString());
                    repository.getNetworkRepository().delete(peerData.getAddress());

                    // Delete from known peer cache too
                    this.allKnownPeers.remove(peerData);
                }

                repository.saveChanges();
            }
        }
    }

    public boolean mergePeers(String addedBy, long addedWhen, List<PeerAddress> peerAddresses) throws DataException {
        mergePeersLock.lock();

        try (Repository repository = RepositoryManager.getRepository()) {
            return this.mergePeers(repository, addedBy, addedWhen, peerAddresses);
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
            try (Repository repository = RepositoryManager.tryRepository()) {
                if (repository == null) {
                    return;
                }

                this.mergePeers(repository, addedBy, addedWhen, peerAddresses);

            } catch (DataException e) {
                // Already logged by this.mergePeers()
            }
        } finally {
            mergePeersLock.unlock();
        }
    }

    private boolean mergePeers(Repository repository, String addedBy, long addedWhen, List<PeerAddress> peerAddresses)
            throws DataException {
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

            try {
                // Save new peers into database
                for (PeerData peerData : newPeers) {
                    LOGGER.info("Adding new peer {} to repository", peerData.getAddress());
                    repository.getNetworkRepository().save(peerData);
                }

                repository.saveChanges();
            } catch (DataException e) {
                LOGGER.error("Repository issue while merging peers list from {}", addedBy, e);
                throw e;
            }

            return true;
        }
    }

    public void broadcast(Function<Peer, Message> peerMessageBuilder) {
        class Broadcaster implements Runnable {
            private final Random random = new Random();

            private List<Peer> targetPeers;
            private Function<Peer, Message> peerMessageBuilder;

            Broadcaster(List<Peer> targetPeers, Function<Peer, Message> peerMessageBuilder) {
                this.targetPeers = targetPeers;
                this.peerMessageBuilder = peerMessageBuilder;
            }

            @Override
            public void run() {
                Thread.currentThread().setName("Network Broadcast");

                for (Peer peer : targetPeers) {
                    // Very short sleep to reduce strain, improve multi-threading and catch interrupts
                    try {
                        Thread.sleep(random.nextInt(20) + 20L);
                    } catch (InterruptedException e) {
                        break;
                    }

                    Message message = peerMessageBuilder.apply(peer);

                    if (message == null) {
                        continue;
                    }

                    if (!peer.sendMessage(message)) {
                        peer.disconnect("failed to broadcast message");
                    }
                }

                Thread.currentThread().setName("Network Broadcast (dormant)");
            }
        }

        try {
            broadcastExecutor.execute(new Broadcaster(this.getHandshakedPeers(), peerMessageBuilder));
        } catch (RejectedExecutionException e) {
            // Can't execute - probably because we're shutting down, so ignore
        }
    }

    // Shutdown

    public void shutdown() {
        // Close listen socket to prevent more incoming connections
        if (this.serverChannel.isOpen()) {
            try {
                this.serverChannel.close();
            } catch (IOException e) {
                // Not important
            }
        }

        // Stop processing threads
        try {
            if (!this.networkEPC.shutdown(5000)) {
                LOGGER.warn("Network threads failed to terminate");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for networking threads to terminate");
        }

        // Stop broadcasts
        this.broadcastExecutor.shutdownNow();
        try {
            if (!this.broadcastExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("Broadcast threads failed to terminate");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for broadcast threads failed to terminate");
        }

        // Close all peer connections
        for (Peer peer : this.getConnectedPeers()) {
            peer.shutdown();
        }
    }

}
