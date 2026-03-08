package org.qortal.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.CommonBlockData;
import org.qortal.data.network.PeerData;
import org.qortal.network.helper.PeerCapabilities;
import org.qortal.network.helper.PeerDownloadSpeedTracker;
import org.qortal.network.message.ArbitraryDataFileMessage;
import org.qortal.network.RNSCommon.PeerMetaType;
import org.qortal.network.message.ChallengeMessage;
import org.qortal.network.message.GetArbitraryDataFileMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageException;
import org.qortal.network.message.MessageType;
import org.qortal.network.task.MessageTask;
import org.qortal.network.task.PingTask;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.ExecuteProduceConsume.Task;
import org.qortal.utils.NTP;

import com.google.common.hash.HashCode;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;

// For managing one peer
public class IPPeer implements Peer {

    public static final int NETWORK = 0;
    public static final int NETWORKDATA = 1;

    private static final Logger LOGGER = LogManager.getLogger(IPPeer.class);
    private PeerMetaType peerMetaType = PeerMetaType.IP;

    /**
     * Maximum time to allow <tt>connect()</tt> to remote peer to complete. (ms)
     */
    private static final int CONNECT_TIMEOUT = 2000; // ms

    /**
     * Maximum time to wait for a message reply to arrive from peer. (ms)
     */
	  private static final int RESPONSE_TIMEOUT = 10_000; // ms

	  /**
	   * Timeout for blockchain synchronization operations (ms)
	   * Shorter timeout to avoid blocking transaction processing during sync.
	   */
	  public static final int SYNC_RESPONSE_TIMEOUT = 4_000; // ms

	/**
	 * Interval between PING messages to a peer. (ms)
	 * <p>
	 * Just under every 30s is usually ideal to keep NAT mappings refreshed.
	 */
	private static final int PING_INTERVAL = 40_000; // ms

	/**
	 * Maximum time to wait for a peer to respond with blocks (ms)
	 */
	public static final int FETCH_BLOCKS_TIMEOUT = 10000;

    private volatile boolean isStopping = false;

    private SocketChannel socketChannel = null;
    private InetSocketAddress resolvedAddress = null;
    /**
     * True if remote address is loopback/link-local/site-local, false otherwise.
     */
    private boolean isLocal;

    /**
     * True if connected for the purposes of transfering specific QDN data
     */
    private boolean isDataPeer;
    private int peerType; // Type 0 is default, Type 1 is NetworkData

    private final UUID peerConnectionId = UUID.randomUUID();
    private final Object byteBufferLock = new Object();
    private ByteBuffer byteBuffer;
    private Map<Integer, BlockingQueue<Message>> replyQueues;
    private LinkedBlockingQueue<Message> pendingMessages;

	private BlockingQueue<Message> sendQueue;
	private ByteBuffer outputBuffer;
	private String outputMessageType;
	private int outputMessageId;
	private long lastWriteProgressTime = System.currentTimeMillis();
	
	// Shallow prefetch: track active prefetches to cap at 2-4 chunks per peer
	private static final int MAX_PREFETCH_COUNT = 3; // Cap at 3 prefetches per peer (2-4 range)
	private final AtomicInteger activePrefetchCount = new AtomicInteger(0);

    /**
     * True if we created connection to peer, false if we accepted incoming connection from peer.
     */
    private final boolean isOutbound;

    private final Object handshakingLock = new Object();
    private Handshake handshakeStatus = Handshake.STARTED;
    private volatile boolean handshakeMessagePending = false;
    private volatile long handshakeMessagePendingSince = 0;
    /** TX side: true when we have successfully sent OUR RESPONSE */
    private volatile boolean handshakeResponseSent = false;
    /** RX side: true when we have validated THEIR RESPONSE */
    private volatile boolean handshakeResponseValidated = false;
    private long handshakeComplete = -1L;
    private long maxConnectionAge = 0L;

    /**
     * Timestamp of when socket was accepted, or connected.
     */
    private Long connectionTimestamp = null;

    /**
     * Last PING message round-trip time (ms).
     */
    private Long lastPing = null;
    /**
     * When last PING message was sent, or null if pings not started yet.
     */
    private Long lastPingSent = null;

    /**
     * Track last response of QDN assets to find nodes that have useful/maximum data
     */
    private Long lastValidUse = null;

    /**
     * Tracks download speeds for chunks received from this peer.
     * Used to track when data was last received for peer selection optimization.
     */
    private PeerDownloadSpeedTracker downloadSpeedTracker = new PeerDownloadSpeedTracker();

    byte[] ourChallenge;

    private boolean syncInProgress = false;

    /* Pending signature requests */
    private List<byte[]> pendingSignatureRequests = Collections.synchronizedList(new ArrayList<>());

    // Versioning
    public static final Pattern VERSION_PATTERN = Pattern.compile(Controller.VERSION_PREFIX
            + "(\\d{1,3})\\.(\\d{1,5})\\.(\\d{1,5})");

    // Peer info

    private final Object peerInfoLock = new Object();
    private String peersNodeId;
    private byte[] peersPublicKey;
    private byte[] peersChallenge;

    private PeerData peerData = null;
    private PeerCapabilities peerCapabilities;
    private boolean awaitingHelloV2Response = false;
    /**
     * Peer's value of connectionTimestamp.
     */
    private Long peersConnectionTimestamp = null;

    /**
     * Version string as reported by peer.
     */
    private String peersVersionString = null;
    /**
     * Numeric version of peer.
     */
    private Long peersVersion = null;

    /**
     * Latest block info as reported by peer.
     */
    private List<BlockSummaryData> peersChainTipData = Collections.emptyList();

    /**
     * Our common block with this peer
     */
    private CommonBlockData commonBlockData;

    /**
     * Last time we detected this peer as TOO_DIVERGENT
     */
    private Long lastTooDivergentTime;

    // Message stats

    private static class MessageStats {
        public final LongAdder count = new LongAdder();
        public final LongAdder totalBytes = new LongAdder();
    }

    private final Map<MessageType, MessageStats> receivedMessageStats = new ConcurrentHashMap<>();
    private final Map<MessageType, MessageStats> sentMessageStats = new ConcurrentHashMap<>();

   
    // Constructors

    @PeerCtor("peer-network")
    private IPPeer(int network, boolean outbound) {
        this.peerType = network;
        this.isOutbound = outbound;
        this.sendQueue = new LinkedBlockingQueue<>(2000); // Bounded queue for bulk streaming
        this.replyQueues = new ConcurrentHashMap<>();
        this.pendingMessages = new LinkedBlockingQueue<>();
    }
    /**
     * Construct unconnected, outbound Peer using socket address in peer data
     */
    @PeerCtor("peer-data")
    public IPPeer(PeerData peerData, int network) {
        //this.isOutbound = true;
        this(network, true);    // Peer is outbound
        this.peerData = peerData;
        this.peerData.setPeerMetaType(this.peerMetaType);
    }

    /**
     * Construct Peer using existing, connected socket
     */
    @PeerCtor("socket")
    public IPPeer(SocketChannel socketChannel, int network) throws IOException {
        this.isOutbound = false;
        this.socketChannel = socketChannel;
        int port = socketChannel.socket().getPort();

        if (port == Settings.getInstance().getQDNListenPort() || network == Peer.NETWORKDATA)
            sharedSetup(Peer.NETWORKDATA);
        else
            sharedSetup(Peer.NETWORK);

        this.resolvedAddress = ((InetSocketAddress) socketChannel.socket().getRemoteSocketAddress());
        this.isLocal = isAddressLocal(this.resolvedAddress.getAddress());

        PeerAddress peerAddress = (PeerAddress) IPPeerAddress.fromSocket(socketChannel.socket());
        this.peerData = new PeerData(peerAddress);
        this.peerData.setPeerMetaType(this.peerMetaType);

        this.peerType = network;
    }

    ///**
    // * interface to instance
    // */
    //public IPPeer unwrap() {
    //    //return (actualClass) this;
    //    //return (T) this;
    //    return (IPPeer) this;
    //    //Class<?> actualClass = this.getClass();
    //    //return (actualClass) this;
    //}
    ////public <T> T unwrap(Class<T> clazz) {
    ////    return clazz.cast(this);
    ////}

    // Getters / setters

    public PeerMetaType getPeerMetaType() {
        return this.peerMetaType;
    }

    public boolean isStopping() {
        return this.isStopping;
    }

    public SocketChannel getSocketChannel() {
        return this.socketChannel;
    }

    public InetSocketAddress getResolvedAddress() {
        return this.resolvedAddress;
    }

    public boolean isLocal() {
        return this.isLocal;
    }

    public boolean isOutbound() {
        return this.isOutbound;
    }

    public boolean isDataPeer() {
        return isDataPeer;
    }

    public void setIsDataPeer(boolean isDataPeer) {
        this.isDataPeer = isDataPeer;
    }

    public void setPeerType (int type) {
        this.peerType = type;
    }

    public int getPeerType () {
        return this.peerType;
    }
    public Object getPeerCapability(String capName) {
        return peerCapabilities == null ? null : peerCapabilities.getCapability(capName);
    }

    public Handshake getHandshakeStatus() {
        synchronized (this.handshakingLock) {
            return this.handshakeStatus;
        }
    }




    /**
     * Checks if this peer has a write that appears stuck (no progress for a while).
     * 
     * @param timeoutMs threshold in milliseconds
     * @return true if outputBuffer has data but no write progress within timeout
     */
    public boolean hasStuckWrite(long timeoutMs) {
        // Only consider it stuck if there's actually data waiting to be written
        if (this.outputBuffer == null || !this.outputBuffer.hasRemaining()) {
            return false;
        }
        
        long elapsed = System.currentTimeMillis() - this.lastWriteProgressTime;
        return elapsed > timeoutMs;
    }

    /**
     * Returns info about the current stuck write, or null if not stuck.
     * Useful for logging.
     */
    public String getStuckWriteInfo() {
        if (this.outputBuffer == null) {
            return null;
        }
        return String.format("type=%s, id=%d, remaining=%d bytes, stalled for %dms",
                this.outputMessageType, 
                this.outputMessageId,
                this.outputBuffer.remaining(),
                System.currentTimeMillis() - this.lastWriteProgressTime);
    }

    public void setHandshakeStatus(Handshake handshakeStatus) {
        synchronized (this.handshakingLock) {
            // Never downgrade from COMPLETED - tryCompleteHandshake() is the sole authority for completion
            if (this.handshakeStatus == Handshake.COMPLETED) {
                return;
            }
            this.handshakeStatus = handshakeStatus;
        }
    }

    public boolean isHandshakeResponseSent() {
        synchronized (this.handshakingLock) {
            return this.handshakeResponseSent;
        }
    }

    public void setHandshakeResponseSent(boolean handshakeResponseSent) {
        synchronized (this.handshakingLock) {
            this.handshakeResponseSent = handshakeResponseSent;
        }
    }

    public boolean isHandshakeResponseValidated() {
        synchronized (this.handshakingLock) {
            return this.handshakeResponseValidated;
        }
    }

    public void setHandshakeResponseValidated(boolean handshakeResponseValidated) {
        synchronized (this.handshakingLock) {
            this.handshakeResponseValidated = handshakeResponseValidated;
        }
    }

    /**
     * Atomically tries to complete the handshake if BOTH conditions are met:
     * 1. RX side: Their RESPONSE has been validated (handshakeResponseValidated is true)
     * 2. TX side: Our RESPONSE has been sent (handshakeResponseSent is true)
     * 
     * This uses two explicit flags instead of relying on enum state, because:
     * - Enum state transitions happen OUTSIDE this lock
     * - Order of RX/TX completion is non-deterministic
     * - Both threads must call this method after setting their respective flag
     * - Exactly one thread will succeed in completing the handshake
     * 
     * This is the same pattern used in TLS, QUIC, and SSH handshakes.
     * 
     * @return true if handshake was completed by this call, false if already completed or conditions not met
     */
    public boolean tryCompleteHandshake() {
        synchronized (this.handshakingLock) {
            // Both conditions must be true: RX validated + TX sent
            if (this.handshakeResponseValidated && 
                this.handshakeResponseSent && 
                this.handshakeComplete < 0) {
                
                this.handshakeStatus = Handshake.COMPLETED;
                this.handshakeComplete = System.currentTimeMillis();
                this.generateRandomMaxConnectionAge();
                
                // Defensive sanity check - should never fail since we're inside the lock
                if (this.handshakeStatus != Handshake.COMPLETED) {
                    LOGGER.error("[{}] CRITICAL: handshakeStatus changed unexpectedly after completion! Expected COMPLETED, got {}",
                            this.peerConnectionId, this.handshakeStatus);
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Check if handshake has already been completed.
     * Used to prevent duplicate onHandshakeCompleted() calls.
     */
    public boolean isHandshakeCompleted() {
        synchronized (this.handshakingLock) {
            return this.handshakeComplete > 0;
        }
    }

    private void generateRandomMaxConnectionAge() {
        if (this.maxConnectionAge > 0L) {
            // Already generated, so we don't want to overwrite the existing value
            return;
        }

        // Retrieve the min and max connection time from the settings, and calculate the range
        final int minPeerConnectionTime = Settings.getInstance().getMinPeerConnectionTime();
        final int maxPeerConnectionTime = Settings.getInstance().getMaxPeerConnectionTime();
        final int peerConnectionTimeRange = maxPeerConnectionTime - minPeerConnectionTime;

        // Generate a random number between the min and the max
        Random random = new Random();
        // @ToDo : what the helly???  random age? MAx is default - 6 hrs in settings
        this.maxConnectionAge = (random.nextInt(peerConnectionTimeRange) + minPeerConnectionTime) * 1000L;
        LOGGER.debug("[{}] Generated max connection age for peer {}. Min: {}s, max: {}s, range: {}s, random max: {}ms", this.peerConnectionId, this, minPeerConnectionTime, maxPeerConnectionTime, peerConnectionTimeRange, this.maxConnectionAge);

    }

    public void resetHandshakeMessagePending() {
        this.handshakeMessagePending = false;
        this.handshakeMessagePendingSince = 0;
    }

    public PeerData getPeerData() {
        synchronized (this.peerInfoLock) {
            return this.peerData;
        }
    }

    public Long getConnectionTimestamp() {
        synchronized (this.peerInfoLock) {
            return this.connectionTimestamp;
        }
    }

    public String getPeersVersionString() {
        synchronized (this.peerInfoLock) {
            return this.peersVersionString;
        }
    }

    public Long getPeersVersion() {
        synchronized (this.peerInfoLock) {
            return this.peersVersion;
        }
    }

    public void setPeersVersion(String versionString, long version) {
        synchronized (this.peerInfoLock) {
            this.peersVersionString = versionString;
            this.peersVersion = version;
        }
    }

    public PeerCapabilities getPeersCapabilities() {
        return this.peerCapabilities;
    }

    public void setPeersCapabilities(PeerCapabilities capabilities) {
        synchronized (this.peerInfoLock) {
            this.peerCapabilities = capabilities;
        }
    }

    public boolean isAwaitingHelloV2Response() {
        synchronized (this.peerInfoLock) {
            return this.awaitingHelloV2Response;
        }
    }

    public void setAwaitingHelloV2Response(boolean awaitingHelloV2Response) {
        synchronized (this.peerInfoLock) {
            this.awaitingHelloV2Response = awaitingHelloV2Response;
        }
    }

    public Long getPeersConnectionTimestamp() {
        synchronized (this.peerInfoLock) {
            return this.peersConnectionTimestamp;
        }
    }

    public void setPeersConnectionTimestamp(Long peersConnectionTimestamp) {
        synchronized (this.peerInfoLock) {
            this.peersConnectionTimestamp = peersConnectionTimestamp;
        }
    }

    public Long getLastPing() {
        synchronized (this.peerInfoLock) {
            return this.lastPing;
        }
    }

    public String getPeerIndexString() {
        return getResolvedAddress().getHostString();
    }

    public void setLastPing(long lastPing) {
        synchronized (this.peerInfoLock) {
            this.lastPing = lastPing;
        }
    }

    public byte[] getOurChallenge() {
        return this.ourChallenge;
    }

    public String getPeersNodeId() {
        synchronized (this.peerInfoLock) {
            return this.peersNodeId;
        }
    }

    public void setPeersNodeId(String peersNodeId) {
        synchronized (this.peerInfoLock) {
            this.peersNodeId = peersNodeId;
        }
    }

    public byte[] getPeersPublicKey() {
        synchronized (this.peerInfoLock) {
            return this.peersPublicKey;
        }
    }

    public void setPeersPublicKey(byte[] peerPublicKey) {
        synchronized (this.peerInfoLock) {
            this.peersPublicKey = peerPublicKey;
        }
    }

    public String getHostName() {
        // Get the string representation of the PeerAddress
        String addressString = this.peerData.getAddress().toString();

        // Use HostAndPort to parse and extract the host part
        try {
            HostAndPort hostAndPort = HostAndPort.fromString(addressString);
            return hostAndPort.getHost();
        } catch (IllegalArgumentException e) {
            // This should ideally not happen if PeerAddress is correctly formed,
            // but return the full string as a fallback
            LOGGER.warn("[{}] Could not parse host/port from address string: {}", this.peerConnectionId, addressString);
            return addressString;
        }
    }

    public byte[] getPeersChallenge() {
        synchronized (this.peerInfoLock) {
            return this.peersChallenge;
        }
    }

    public void setPeersChallenge(byte[] peersChallenge) {
        synchronized (this.peerInfoLock) {
            this.peersChallenge = peersChallenge;
        }
    }

    public BlockSummaryData getChainTipData() {
        List<BlockSummaryData> chainTipSummaries = this.peersChainTipData;

        if (chainTipSummaries.isEmpty())
            return null;

        // Return last entry, which should have greatest height
        return chainTipSummaries.get(chainTipSummaries.size() - 1);
    }

    public void setChainTipData(BlockSummaryData chainTipData) {
        this.peersChainTipData = Collections.singletonList(chainTipData);
    }

    public List<BlockSummaryData> getChainTipSummaries() {
        return this.peersChainTipData;
    }

    public void setChainTipSummaries(List<BlockSummaryData> chainTipSummaries) {
        this.peersChainTipData = List.copyOf(chainTipSummaries);
    }

    public CommonBlockData getCommonBlockData() {
        return this.commonBlockData;
    }

    public void setCommonBlockData(CommonBlockData commonBlockData) {
        this.commonBlockData = commonBlockData;
    }

    public Long getLastTooDivergentTime() {
        return this.lastTooDivergentTime;
    }

    public void setLastTooDivergentTime(Long lastTooDivergentTime) {
        this.lastTooDivergentTime = lastTooDivergentTime;
    }

    public boolean isSyncInProgress() {
        return this.syncInProgress;
    }

    public void setSyncInProgress(boolean syncInProgress) {
        this.syncInProgress = syncInProgress;
    }


    // Pending signature requests

    public void addPendingSignatureRequest(byte[] signature) {
        // Check if we already have this signature in the list
        for (byte[] existingSignature : this.pendingSignatureRequests) {
            if (Arrays.equals(existingSignature, signature )) {
                return;
            }
        }
        this.pendingSignatureRequests.add(signature);
    }

    public void removePendingSignatureRequest(byte[] signature) {
        Iterator iterator = this.pendingSignatureRequests.iterator();
        while (iterator.hasNext()) {
            byte[] existingSignature = (byte[]) iterator.next();
            if (Arrays.equals(existingSignature, signature)) {
                iterator.remove();
            }
        }
    }

    public List<byte[]> getPendingSignatureRequests() {
        return this.pendingSignatureRequests;
    }

    @Override
    public String toString() {
        // Easier, and nicer output, than peer.getRemoteSocketAddress()
        return this.peerData.getAddress().toString();
    }

    @Override
    public boolean hasActivePeerLink() {
        return false;
    }


    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof Peer)) {
            return false;
        }

        Peer otherPeer = (Peer) other;

        // Compare based on the host and port combination from peerData
        // Peer.toString() returns this.peerData.getAddress().toString(),
        // which represents the HostAndPort for comparison.

        // Retrieve InetSocketAddress from this Peer's PeerData
        InetSocketAddress thisAddress;
        try {
            thisAddress = this.peerData.getAddress().toSocketAddress();
        } catch (UnknownHostException e) {
            LOGGER.error("Could not resolve own address for equals comparison: {}", this.peerData.getAddress().toString());
            return false;
        }

        // Retrieve InetSocketAddress from the other Peer's PeerData
        InetSocketAddress otherAddress;
        try {
            otherAddress = otherPeer.getPeerData().getAddress().toSocketAddress();
        } catch (UnknownHostException e) {
            LOGGER.error("Could not resolve other peer's address for equals comparison: {}", otherPeer.getPeerData().getAddress().toString());
            return false;
        }

        // Use the existing utility method for address comparison
        return Peer.addressEquals(thisAddress, otherAddress);
    }

    // Processing

    private void sharedSetup(int network) throws IOException {
        // Use NTP time if available, fall back to system time
        // This prevents null timestamps which would exempt peers from timeout enforcement
        Long ntpTime = NTP.getTime();
        long timestamp = (ntpTime != null) ? ntpTime : System.currentTimeMillis();
        
        this.connectionTimestamp = timestamp;
        this.lastValidUse = timestamp;
        this.socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        this.socketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

        this.socketChannel.configureBlocking(false);
        if (network == Peer.NETWORK)
            Network.getInstance().registerPeerChannel(this.socketChannel, this);
        else
            NetworkData.getInstance().registerPeerChannel(this.socketChannel, this);
        this.byteBuffer = null; // Defer allocation to when we need it, to save memory. Sorry GC!
        //this.sendQueue = new LinkedTransferQueue<>();
        //this.replyQueues = new ConcurrentHashMap<>();
        //this.pendingMessages = new LinkedBlockingQueue<>();

        Random random = new SecureRandom();
        this.ourChallenge = new byte[ChallengeMessage.CHALLENGE_LENGTH];
        random.nextBytes(this.ourChallenge);
    }

    public SocketChannel connect(int network) {
        LOGGER.trace("[{}] Connecting to peer {}", this.peerConnectionId, this);

        try {
            this.resolvedAddress = this.peerData.getAddress().toSocketAddress();
            this.isLocal = isAddressLocal(this.resolvedAddress.getAddress());

            this.socketChannel = SocketChannel.open();
            InetAddress bindAddr = InetAddress.getByName(Settings.getInstance().getBindAddress());
            this.socketChannel.socket().bind(new InetSocketAddress(bindAddr, 0));
            this.socketChannel.socket().connect(resolvedAddress, CONNECT_TIMEOUT);
        } catch (SocketTimeoutException e) {
            LOGGER.trace("[{}] Connection timed out to peer {}", this.peerConnectionId, this);
            return null;
        } catch (UnknownHostException e) {
            LOGGER.trace("[{}] Connection failed to unresolved peer {}", this.peerConnectionId, this);
            return null;
        } catch (IOException e) {
            LOGGER.trace("[{}] Connection failed to peer {}", this.peerConnectionId, this);
            return null;
        }

        try {
            LOGGER.trace("[{}] Connected to peer {}", this.peerConnectionId, this);
            sharedSetup(network);
            return socketChannel;
        } catch (IOException e) {
            LOGGER.trace("[{}] Post-connection setup failed, peer {}", this.peerConnectionId, this);
            try {
                LOGGER.trace("Closing Socket");
                socketChannel.close();
            } catch (IOException ce) {
                // Failed to close?
                LOGGER.trace("EXCEPTION closing socket");
            }
            return null;
        }
    }

    /**
     * Attempt to buffer bytes from socketChannel.
     *
     * @throws IOException If this channel is not yet connected
     */
    public void readChannel() throws IOException {
        synchronized (this.byteBufferLock) {
            while (true) {
                if (!this.socketChannel.isOpen() ) {
                    return;
                }
                if (this.socketChannel.socket().isClosed()) {
                    return;
                }

                // Do we need to allocate byteBuffer?
                if (this.byteBuffer == null) {
                    this.byteBuffer = ByteBuffer.allocate(Network.getInstance().getMaxMessageSize());
                }

                final int priorPosition = this.byteBuffer.position();
                long socketReadStart = System.nanoTime();
                final int bytesRead = this.socketChannel.read(this.byteBuffer);
                long socketReadTime = System.nanoTime() - socketReadStart;
                
               
                
                if (bytesRead == -1) {
                    if (priorPosition > 0) {
                        this.disconnect("EOF - read " + priorPosition + " bytes");
                    } else {
                        this.disconnect("EOF - failed to read any data");
                    }
                    return;
                }

                if (LOGGER.isTraceEnabled()) {
                    if (bytesRead > 0) {
                        byte[] leadingBytes = new byte[Math.min(bytesRead, 8)];
                        this.byteBuffer.asReadOnlyBuffer().position(priorPosition).get(leadingBytes);
                        String leadingHex = HashCode.fromBytes(leadingBytes).toString();

                        LOGGER.trace("[{}] Received {} bytes, starting {}, into byteBuffer[{}] from peer {}",
                                this.peerConnectionId, bytesRead, leadingHex, priorPosition, this);
                    } else {
                        LOGGER.trace("[{}] Received {} bytes into byteBuffer[{}] from peer {}", this.peerConnectionId,
                                bytesRead, priorPosition, this);
                    }
                }
                final boolean wasByteBufferFull = !this.byteBuffer.hasRemaining();

                while (true) {
                    final Message message;

                    // Can we build a message from buffer now?
                    ByteBuffer readOnlyBuffer = this.byteBuffer.asReadOnlyBuffer().flip();
                    try {
                        long deserializeStart = System.nanoTime();
                        message = Message.fromByteBuffer(readOnlyBuffer);
                        long deserializeTime = System.nanoTime() - deserializeStart;
                        
                        // Log deserialization timing for ARBITRARY_DATA_FILE messages
                        if (message != null && message.getType() == MessageType.ARBITRARY_DATA_FILE) {
                            long messageByteSize = readOnlyBuffer.position();
                            LOGGER.trace("[{}] ARBITRARY_DATA_FILE receiver: fromByteBuffer() took {} ms ({} bytes), message ID {}",
                                    this.peerConnectionId, deserializeTime / 1_000_000.0, messageByteSize, message.getId());
                        }
                    } catch (MessageException e) {
                        LOGGER.debug("[{}] {}, from peer {}", this.peerConnectionId, e.getMessage(), this);
                        this.disconnect(e.getMessage());
                        return;
                    }

                if (message == null && bytesRead == 0) {
                    // No complete message and no bytes available right now.
                    // Return so selector can re-arm OP_READ without busy looping.
                    if (!wasByteBufferFull) {
                        // If byteBuffer is completely empty, deallocate it to save memory
                        // This helps reduce memory usage when peers are idle
                        // The buffer will be reallocated on next read if needed
                        if (this.byteBuffer.remaining() == this.byteBuffer.capacity()) {
                            this.byteBuffer = null;
                            LOGGER.trace("[{}] Deallocated empty byteBuffer for peer {}", this.peerConnectionId, this);
                        }
                    //    */
                    //
                    //    return;
                    }
                    return;
                }

                if (message == null) {
                    // No complete message in buffer, but maybe more bytes to read from socket
                    break;
                }

                    LOGGER.trace("[{}] Received {} message with ID {} from peer {}", this.peerConnectionId,
                            message.getType().name(), message.getId(), this);

                    // Tidy up buffers:
                    this.byteBuffer.flip();
                    // Read-only, flipped buffer's position will be after end of message, so copy that
                    long messageByteSize = readOnlyBuffer.position();
                    this.byteBuffer.position(readOnlyBuffer.position());
                    // Copy bytes after read message to front of buffer,
                    // adjusting position accordingly, reset limit to capacity
                    this.byteBuffer.compact();

                    // Record message stats
                    MessageStats messageStats = this.receivedMessageStats.computeIfAbsent(message.getType(), k -> new MessageStats());
                    // Ideally these two operations would be atomic, we could pack 'count' in top X bits of the 64-bit long, but meh
                    messageStats.count.increment();
                    messageStats.totalBytes.add(messageByteSize);

                    // Unsupported message type? Discard with no further processing
                    if (message.getType() == MessageType.UNSUPPORTED)
                        continue;

                    BlockingQueue<Message> queue = this.replyQueues.get(message.getId());
                    if (queue != null) {
                        // Adding message to queue will unblock thread waiting for response
                        this.replyQueues.get(message.getId()).add(message);
                        // Consumed elsewhere
                        continue;
                    }

                    // No thread waiting for message so we need to pass it up to network layer

                    // Add message to pending queue
                    if (!this.pendingMessages.offer(message)) {
                        LOGGER.info("[{}] No room to queue message from peer {} - discarding",
                                this.peerConnectionId, this);
                        return;
                    }
                    
                    LOGGER.debug("[{}] Queued {} message from peer {} (handshake status: {}, pending: {})",
                            this.peerConnectionId, message.getType().name(), this, 
                            this.handshakeStatus, this.handshakeMessagePending);

                    // No wakeup needed here: readChannel() is always called from the IO thread itself,
                    // which is not in select() at this point. The queued message will be drained to the
                    // worker pool later in the same runIOLoop() iteration (the readPeersThisRound drain),
                    // so calling wakeup() would only cause the next select() to return immediately as a
                    // no-op, wasting a loop iteration.
                }
            }
        }
    }



    /** Maybe send some pending outgoing messages.
     *
     * @return true if more data is pending to be sent
     */
    public boolean writeChannel() throws IOException {
        // It is the responsibility of ChannelWriteTask's producer to produce only one call to writeChannel() at a time

        while (true) {
            if (this.outputBuffer != null) {
                LOGGER.trace("[{}] outputBuffer not null - skipping message processing, continuing to write existing buffer: type={}, id={}, remaining={} bytes",
                        this.peerConnectionId, this.outputMessageType, this.outputMessageId, 
                        this.outputBuffer != null ? this.outputBuffer.remaining() : 0);
            }
            // If output byte buffer is null, fetch next message from queue (if any)
            while (this.outputBuffer == null) {
                // Simple poll from bounded queue
                Message message = this.sendQueue.poll();

                // No message? No work to do - safe to clear OP_WRITE
                // OP_WRITE will be re-armed by sendMessageWithTimeout() when new messages arrive
                if (message == null)
                    return false; // No pending data

                try {
                    long startTime = System.nanoTime();
                    byte[] messageBytes = message.toBytes();
                    long toBytesTime = System.nanoTime() - startTime;
                    
                    this.outputBuffer = ByteBuffer.wrap(messageBytes);
                    this.outputMessageType = message.getType().name();
                    this.outputMessageId = message.getId();
                    
                    // Log only for ARBITRARY_DATA_FILE messages (actual chunks)
                    if (message.getType() == MessageType.ARBITRARY_DATA_FILE) {
                        LOGGER.trace("RESPONDER NETWORK PREP: messageId={}, toBytes={}ms, bytes={}, peer={}", 
                            this.outputMessageId, toBytesTime / 1_000_000.0, messageBytes.length, this);
                    }
                    // Decrement prefetch count when message is processed (data loaded, ready to send)
                    // This allows new prefetches to start as messages are consumed
                    if (message instanceof ArbitraryDataFileMessage) {
                        decrementPrefetchCount();
                    }

                    LOGGER.trace("[{}] Sending {} message with ID {} to peer {}",
                            this.peerConnectionId, this.outputMessageType, this.outputMessageId, this);

                    // Record message stats
                    MessageStats messageStats = this.sentMessageStats.computeIfAbsent(message.getType(), k -> new MessageStats());
                    // Ideally these two operations would be atomic, we could pack 'count' in top X bits of the 64-bit long, but meh
                    messageStats.count.increment();
                    messageStats.totalBytes.add(this.outputBuffer.limit());
                } catch (MessageException e) {
                    // Something went wrong converting message to bytes, so discard but allow another round
                    // Still decrement prefetch count if it was an ArbitraryDataFileMessage
                    if (message instanceof ArbitraryDataFileMessage) {
                        decrementPrefetchCount();
                    }
                    LOGGER.warn("[{}] Failed to send {} message with ID {} to peer {}: {}", this.peerConnectionId,
                            message.getType().name(), message.getId(), this, e.getMessage());
                }
            }

            // If output byte buffer is not null, send from that
            long socketWriteStart = System.nanoTime();
            int bytesWritten = this.socketChannel.write(outputBuffer);
            long socketWriteTime = System.nanoTime() - socketWriteStart;
            
            // Log for ARBITRARY_DATA_FILE
            if (this.outputMessageType != null && this.outputMessageType.equals("ARBITRARY_DATA_FILE")) {
                LOGGER.trace("RESPONDER NETWORK WRITE: messageId={}, socketWrite={}ms, wroteBytes={}, remainingBytes={}", 
                    this.outputMessageId, socketWriteTime / 1_000_000.0, bytesWritten, 
                    this.outputBuffer != null ? this.outputBuffer.remaining() : 0);
            }

            // Update progress tracking
            if (bytesWritten > 0) {
                this.lastWriteProgressTime = System.currentTimeMillis();
            } else {
                // Socket send buffer full — wait for next OP_WRITE
                // For bulk streaming, don't disconnect on write slowness - let TCP pace naturally
                // Only disconnect on IOException, connection reset, or explicit protocol failures
                return true;
            }

          

            // If we then exhaust the byte buffer, set it to null (otherwise loop and try to send more)
            if (!this.outputBuffer.hasRemaining()) {
                this.outputMessageType = null;
                this.outputMessageId = 0;
                this.outputBuffer = null;
            }
        }
    }

    public Task getMessageTask(int network) {
        /*
         * If we are still handshaking and there is a message yet to be processed then
         * don't produce another message task. This allows us to process handshake
         * messages sequentially.
         */
        if (this.handshakeMessagePending) {
            long pendingDuration = System.currentTimeMillis() - this.handshakeMessagePendingSince;
            if (pendingDuration > 5_000L) {
                // Safety timeout: handshake message processing stuck for too long
                LOGGER.warn("[{}] handshakeMessagePending stuck for {}ms at status {}, force resetting for peer {}",
                        this.peerConnectionId, pendingDuration, this.handshakeStatus, this);
                
                // Log what message was waiting to help diagnose the issue
                Message peek = this.pendingMessages.peek();
                if (peek != null) {
                    LOGGER.warn("[{}] ... had pending {} message waiting (queue size: {})", 
                            this.peerConnectionId, peek.getType().name(), this.pendingMessages.size());
                }
                
                this.handshakeMessagePending = false;
                this.handshakeMessagePendingSince = 0;
                // Don't return null - allow processing to continue
            } else {
                LOGGER.debug("[{}] handshakeMessagePending=true blocking message task for peer {} at status {} (pending {}ms)",
                        this.peerConnectionId, this, this.handshakeStatus, pendingDuration);
                return null;
            }
        }

        final Message nextMessage = this.pendingMessages.poll();

        if (nextMessage == null) {
            return null;
        }

        LOGGER.trace("[{}] Produced {} message task from peer {}", this.peerConnectionId,
                nextMessage.getType().name(), this);

        if (this.handshakeStatus != Handshake.COMPLETED) {
            this.handshakeMessagePending = true;
            this.handshakeMessagePendingSince = System.currentTimeMillis();
        }

        // Return a task to process message in queue
        LOGGER.trace("Generating getMessageTask for {}", peerType);

        return new MessageTask(this, nextMessage, network);

    }

    /**
     * Attempt to send Message to peer, using default RESPONSE_TIMEOUT.
     *
     * @param message message to be sent
     * @return <code>true</code> if message successfully sent; <code>false</code> otherwise
     */
    public boolean sendMessage(Message message) {
        try {
            return this.sendMessageWithTimeout(message, RESPONSE_TIMEOUT);
        } catch (IOException e) {
            LOGGER.debug("Failed to send message to peer {}: {}", this, e.getMessage());
            return false;
        }
    }

    /**
     * Attempt to send Message to peer, using custom timeout.
     *
     * @param message message to be sent
     * @return <code>true</code> if message successfully sent; <code>false</code> otherwise
     */
    public boolean sendMessageWithTimeout(Message message, int timeout) throws IOException {
        if (this.socketChannel == null ) {
            if (!isStopping) {
                this.disconnect("Socket channel is null");
            }
            throw new IOException("Socket channel is null");
        }
        if (!this.socketChannel.isOpen()) {
            if (!isStopping) {
                this.disconnect("Socket closed");
            }
            throw new IOException("Socket closed");
        }

        try {
            // Queue message, to be picked up by ChannelWriteTask and then peer.writeChannel()
            LOGGER.trace("[{}] Queuing {} message with ID {} to peer {}", this.peerConnectionId,
                    message.getType().name(), message.getId(), this);

          

            // Check message properly constructed
            message.checkValidOutgoing();
           
            // CRITICAL ORDERING: Enqueue FIRST, then set OP_WRITE
            // 
            // Invariant: OP_WRITE must be armed iff there is pending outbound data or new data may arrive.
            // 
            // This ordering ensures that when the selector thread processes OP_WRITE and calls writeChannel(),
            // the message is guaranteed to be in the queue. If we set OP_WRITE before enqueuing, there's a race:
            // 1. Set OP_WRITE → selector wakes up → ChannelWriteTask runs
            // 2. writeChannel() polls queue → still empty → returns false
            // 3. ChannelWriteTask clears OP_WRITE and exits
            // 4. Message is enqueued → but OP_WRITE is already cleared → no more writes
            //
            // This relies on sendQueue.offer() establishing a happens-before relationship such that
            // the selector thread will observe the queued message once OP_WRITE is set.
            
            // Simple bounded queue - always enqueue, let TCP handle backpressure
            // For bulk streaming workloads, we need deep buffering, not producer-side backpressure
            boolean offered = this.sendQueue.offer(message);
            if (!offered) {
                
                return false; // Queue full - peer truly overloaded
            }

            // Shallow prefetch: start async disk read for chunk messages to reduce blocking in writeChannel()
            // Only prefetch if under the cap (2-4 chunks per peer) to limit memory usage
            if (message instanceof ArbitraryDataFileMessage) {
                ArbitraryDataFileMessage adfMessage = (ArbitraryDataFileMessage) message;
                int currentPrefetchCount = activePrefetchCount.get();
                if (currentPrefetchCount < MAX_PREFETCH_COUNT) {
                    if (adfMessage.startPrefetch()) {
                        activePrefetchCount.incrementAndGet();
                        // Decrement when prefetch completes (handled in toBytes() or when message is processed)
                    }
                }
            }

          

            // NOW set OP_WRITE - message is guaranteed to be in queue
            switch (this.getPeerType()) {
                case Peer.NETWORK:
                    Network.getInstance().setInterestOps(this.socketChannel, SelectionKey.OP_WRITE);

                    break;
                case Peer.NETWORKDATA:
                    NetworkData.getInstance().setInterestOps(this.socketChannel, SelectionKey.OP_WRITE);

                    break;
            }

            return true;
        } catch (MessageException e) {
            LOGGER.error(e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get the current size of the send queue.
     *
     * @return number of messages currently queued for sending
     */
    public int getSendQueueSize() {
        return this.sendQueue.size();
    }

    /**
     * Get the capacity of the send queue.
     *
     * @return maximum number of messages that can be queued
     */
    public int getSendQueueCapacity() {
        return this.sendQueue.remainingCapacity() + this.sendQueue.size();
    }

    /**
     * Send message to peer and await response, using default RESPONSE_TIMEOUT.
     * <p>
     * Message is assigned a random ID and sent.
     * If a response with matching ID is received then it is returned to caller.
     * <p>
     * If no response with matching ID within timeout, or some other error/exception occurs,
     * then return <code>null</code>.<br>
     * (Assume peer will be rapidly disconnected after this).
     *
     * @param message message to send
     * @return <code>Message</code> if valid response received; <code>null</code> if not or error/exception occurs
     * @throws InterruptedException if interrupted while waiting
     */
    public Message getResponse(Message message) throws InterruptedException {
        return getResponseWithTimeout(message, RESPONSE_TIMEOUT);
    }

    /**
     * Send message to peer and await response.
     * <p>
     * Message is assigned a random ID and sent.
     * If a response with matching ID is received then it is returned to caller.
     * <p>
     * If no response with matching ID within timeout, or some other error/exception occurs,
     * then return <code>null</code>.<br>
     * (Assume peer will be rapidly disconnected after this).
     *
     * @param message message to send
     * @return <code>Message</code> if valid response received; <code>null</code> if not or error/exception occurs
     * @throws InterruptedException if interrupted while waiting
     */
    public Message getResponseWithTimeout(Message message, int timeout) throws InterruptedException {
        BlockingQueue<Message> blockingQueue = new ArrayBlockingQueue<>(1);

        // Assign random ID to this message
        Random random = new Random();
        int id;
        do {
            id = random.nextInt(Integer.MAX_VALUE - 1) + 1;

            // Put queue into map (keyed by message ID) so we can poll for a response
            // If putIfAbsent() doesn't return null, then this ID is already taken
        } while (this.replyQueues.putIfAbsent(id, blockingQueue) != null);
        message.setId(id);

        // Try to send message
        try {
            if (!this.sendMessageWithTimeout(message, timeout)) {
                this.replyQueues.remove(id);
                return null;
            }
        } catch (IOException e) {
            // Socket closed - clean up and return null
            this.replyQueues.remove(id);
            LOGGER.debug("Socket closed while sending request to peer {}: {}", this, e.getMessage());
            return null;
        }

        try {
            return blockingQueue.poll(timeout, TimeUnit.MILLISECONDS);
        } finally {
            this.replyQueues.remove(id);
        }
    }


    public void QDNUse() {
        this.lastValidUse = NTP.getTime();
    }

    public long getLastQDNUse() {
        return this.lastValidUse;
    }

    /**
     * Safely decrements the active prefetch count.
     * Used when messages are dropped to prevent prefetch count from drifting.
     * This ensures the prefetch cap continues to work correctly.
     */
    public void decrementPrefetchCount() {
        int count = activePrefetchCount.decrementAndGet();
        if (count < 0) {
            // Shouldn't happen, but reset to 0 if it does (prevents underflow)
            activePrefetchCount.set(0);
        }
    }
    
    /**
     * Returns the download speed tracker for this peer.
     * Used to track round-trip times for chunk downloads.
     *
     * @return the PeerDownloadSpeedTracker instance for download speed tracking
     */
    public PeerDownloadSpeedTracker getDownloadSpeedTracker() {
        return downloadSpeedTracker;
    }

    public void startPings() {
        // Replacing initial null value allows getPingTask() to start sending pings.
        LOGGER.trace("[{}] Enabling pings for peer {}", this.peerConnectionId, this);
        this.lastPingSent = NTP.getTime();
    }

    public Task getPingTask(Long now) {
        // Pings not enabled yet?
        if (now == null || this.lastPingSent == null) {
            return null;
        }

        // Time to send another ping?
        if (now < this.lastPingSent + PING_INTERVAL) {
            return null; // Not yet
        }

        // Not strictly true, but prevents this peer from being immediately chosen again
        this.lastPingSent = now;

        return new PingTask(this, now);
    }

    public void disconnect(String reason) {
        if (!isStopping) {
            LOGGER.debug("[{}] Disconnecting peer {} after {}: {}", this.peerConnectionId, this,
                    getConnectionAge(), reason);
        }
        LOGGER.trace("peer.disconnect because {} - peer : {} - on Network {}", reason, peersNodeId, peerType);
        try {
            this.shutdown();
        } catch (Exception e) {
            LOGGER.error("[{}] Exception during shutdown: {}", this.peerConnectionId, e.getMessage(), e);
        } finally {
            // CRITICAL: Use finally block to ensure onDisconnect() is ALWAYS called
            // This prevents zombie peers from remaining in connected/handshaked lists
            // even if shutdown() throws an exception or the thread is interrupted
            ensureDisconnectCleanup();
        }
    }

    /**
     * Ensures the peer is properly removed from network lists.
     * Called from disconnect() finally block to guarantee cleanup even if shutdown() fails.
     */
    private void ensureDisconnectCleanup() {
        try {
            switch (this.getPeerType()) {
                case Peer.NETWORK:
                    Network.getInstance().onDisconnect(this);
                    break;
                case Peer.NETWORKDATA:
                    NetworkData.getInstance().onDisconnect(this);
                    break;
                default:
                    LOGGER.error("[{}] Unknown peer type {} - attempting cleanup on both networks",
                            this.peerConnectionId, this.peerType);
                    // Try both networks as a safety measure
                    try {
                        Network.getInstance().onDisconnect(this);
                    } catch (Exception e) {
                        LOGGER.error("[{}] Failed Network.onDisconnect: {}", this.peerConnectionId, e.getMessage());
                    }
                    try {
                        NetworkData.getInstance().onDisconnect(this);
                    } catch (Exception e2) {
                        LOGGER.error("[{}] Failed NetworkData.onDisconnect: {}", this.peerConnectionId, e2.getMessage());
                    }
            }
        } catch (Exception e) {
            LOGGER.error("[{}] CRITICAL: onDisconnect failed, forcing direct removal: {}",
                    this.peerConnectionId, e.getMessage(), e);
            forceRemoveFromNetwork();
        }
    }

    /**
     * Last-resort cleanup when normal disconnect mechanisms fail.
     * Directly removes the peer from all network lists.
     */
    private void forceRemoveFromNetwork() {
        LOGGER.warn("[{}] Forcing direct peer removal from all networks", this.peerConnectionId);
        
        // Try to remove from Network
        try {
            Network.getInstance().removeConnectedPeer(this);
        } catch (Exception e) {
            LOGGER.error("[{}] Failed removeConnectedPeer from Network: {}", 
                    this.peerConnectionId, e.getMessage());
        }
        
        // Try to remove from NetworkData
        try {
            NetworkData.getInstance().removeConnectedPeer(this);
        } catch (Exception e) {
            LOGGER.error("[{}] Failed removeConnectedPeer from NetworkData: {}", 
                    this.peerConnectionId, e.getMessage());
        }
        
        // Clean up PeerSendManager
        try {
            PeerSendManagement.getInstance().removeSendManager(this);
        } catch (Exception e) {
            LOGGER.error("[{}] Failed to remove PeerSendManager: {}", 
                    this.peerConnectionId, e.getMessage());
        }
    }

    public void shutdown() {
        boolean logStats = false;

        if (!isStopping) {
            LOGGER.debug("[{}] Shutting down peer {}", this.peerConnectionId, this);
            logStats = true;
        }
        isStopping = true;
        
        // Reset prefetch count when peer disconnects
        // Messages in sendQueue will be cleared, so prefetch count should be reset
        // This prevents prefetch count from drifting if messages were dropped
        activePrefetchCount.set(0);
        
        // Clear pending messages to prevent memory leaks
        // These messages will never be processed since the peer is shutting down
        if (this.pendingMessages != null) {
            this.pendingMessages.clear();
        }

        if (this.socketChannel != null && this.socketChannel.isOpen()) {
            try {
                String networkType = (this.peerType == Peer.NETWORKDATA) ? "NETWORKDATA" : "NETWORK";
                String peerAddress = (this.resolvedAddress != null) ? this.resolvedAddress.toString() : "unknown";
                LOGGER.debug("[{}] CLOSING SOCKET - This is Intentional - peer {} (address: {}) on network {}", 
                        this.peerConnectionId, this, peerAddress, networkType);
                this.socketChannel.shutdownOutput();
                this.socketChannel.close();
            } catch (IOException e) {
                LOGGER.debug("[{}] IOException while trying to close peer {}", this.peerConnectionId, this);
            }
        }

        if (logStats && !this.receivedMessageStats.isEmpty()) {
            StringBuilder statsBuilder = new StringBuilder(1024);
            statsBuilder.append("peer ").append(this).append(" message stats:\n=received=");
            appendMessageStats(statsBuilder, this.receivedMessageStats);
            statsBuilder.append("\n=sent=");
            appendMessageStats(statsBuilder, this.sentMessageStats);

            LOGGER.debug(statsBuilder.toString());
        }
    }

    private static void appendMessageStats(StringBuilder statsBuilder, Map<MessageType, MessageStats> messageStats) {
        if (messageStats.isEmpty()) {
            statsBuilder.append("\n  none");
            return;
        }

        messageStats.keySet().stream()
                .sorted(Comparator.comparing(MessageType::name))
                .forEach(messageType -> {
                    MessageStats stats = messageStats.get(messageType);

                    statsBuilder.append("\n  ").append(messageType.name())
                            .append(": count=").append(stats.count.sum())
                            .append(", total bytes=").append(stats.totalBytes.sum());
                });
    }

    // Minimum version

    public boolean isAtLeastVersion(String minVersionString) {
        if (minVersionString == null) {
            return false;
        }

        // Add the version prefix
        minVersionString = Controller.VERSION_PREFIX + minVersionString;

        Matcher matcher = VERSION_PATTERN.matcher(minVersionString);
        if (!matcher.lookingAt()) {
            return false;
        }

        // We're expecting 3 positive shorts, so we can convert 1.2.3 into 0x0100020003
        long minVersion = 0;
        for (int g = 1; g <= 3; ++g) {
            long value = Long.parseLong(matcher.group(g));

            if (value < 0 || value > Short.MAX_VALUE) {
                return false;
            }

            minVersion <<= 16;
            minVersion |= value;
        }

        // CRITICAL: Check for null to prevent NPE when unboxing
        // peersVersion is null before HELLO message is received (outbound connections)
        Long peerVersion = this.getPeersVersion();
        return peerVersion != null && peerVersion >= minVersion;
    }


    // Common block data

    public boolean canUseCachedCommonBlockData() {
        BlockSummaryData peerChainTipData = this.getChainTipData();
        if (peerChainTipData == null || peerChainTipData.getSignature() == null)
            return false;

        CommonBlockData commonBlockData = this.getCommonBlockData();
        if (commonBlockData == null)
            return false;

        BlockSummaryData commonBlockChainTipData = commonBlockData.getChainTipData();
        if (commonBlockChainTipData == null || commonBlockChainTipData.getSignature() == null)
            return false;

        if (!Arrays.equals(peerChainTipData.getSignature(), commonBlockChainTipData.getSignature()))
            return false;

        return true;
    }


    // Utility methods

    /**
     * Returns true if ports and addresses (or hostnames) match
     */
    public static boolean addressEquals(InetSocketAddress knownAddress, InetSocketAddress peerAddress) {
        if (knownAddress.getPort() != peerAddress.getPort()) {
            return false;
        }

        return knownAddress.getHostString().equalsIgnoreCase(peerAddress.getHostString());
    }

    public static InetSocketAddress parsePeerAddress(String peerAddress) throws IllegalArgumentException {
        HostAndPort hostAndPort = HostAndPort.fromString(peerAddress).requireBracketsForIPv6();

        // HostAndPort doesn't try to validate host so we do extra checking here
        InetAddress address = InetAddresses.forString(hostAndPort.getHost());

        int defaultPort = Settings.getInstance().getDefaultListenPort();
        return new InetSocketAddress(address, hostAndPort.getPortOrDefault(defaultPort));
    }

    /**
     * Returns true if address is loopback/link-local/site-local, false otherwise.
     */
    public static boolean isAddressLocal(InetAddress address) {
        return address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress();
    }

    public UUID getPeerConnectionId() {
        return peerConnectionId;
    }

    public long getConnectionEstablishedTime() {
        return handshakeComplete;
    }

    public long getConnectionAge() {
        if (handshakeComplete > 0L) {
            return System.currentTimeMillis() - handshakeComplete;
        }
        return handshakeComplete;
    }

    public long getMaxConnectionAge() {
        return maxConnectionAge;
    }

    public void setMaxConnectionAge(long maxConnectionAge) {
        this.maxConnectionAge = maxConnectionAge;
    }

    public boolean hasReachedMaxConnectionAge() {
        return this.getConnectionAge() > this.getMaxConnectionAge();
    }
    
    /**
     * Send a pre-serialized message to this peer.
     * 
     * <p>This optimized method accepts pre-serialized message bytes, avoiding the
     * need to call toBytes() again in writeChannel(). This is critical for the
     * two-stage pipeline architecture where messages are pre-loaded from disk
     * and serialized in parallel disk I/O threads.
     * 
     * <p>Benefits:
     * <ul>
     *   <li>Eliminates redundant serialization (50-100ms saved per message)</li>
     *   <li>Prevents redundant disk reads in relay scenarios</li>
     *   <li>Enables true non-blocking network send path</li>
     * </ul>
     *
     * @param messageId the message ID for tracking
     * @param messageType the type of message
     * @param serializedBytes complete pre-serialized message bytes
     * @param timeout timeout in milliseconds (currently unused but kept for API consistency)
     * @return true if message was queued successfully, false if queue is full
     * @throws IOException if socket is closed or invalid
     * 
     * @since v5.0.9
     * @author Ice
     */
    public boolean sendPreSerializedMessage(int messageId, MessageType messageType, byte[] serializedBytes, int timeout) throws IOException {
        if (this.socketChannel == null) {
            if (!isStopping) {
                this.disconnect("Socket channel is null");
            }
            throw new IOException("Socket channel is null");
        }
        if (!this.socketChannel.isOpen()) {
            if (!isStopping) {
                this.disconnect("Socket closed");
            }
            throw new IOException("Socket closed");
        }

        try {
            // Create lightweight wrapper that returns pre-serialized bytes
            Message wrapper = new PreSerializedMessageWrapper(messageId, messageType, serializedBytes);
            
            // Queue message - will be picked up by ChannelWriteTask and writeChannel()
            LOGGER.trace("[{}] Queuing pre-serialized {} message with ID {} to peer {}", 
                        this.peerConnectionId, messageType.name(), messageId, this);
            
            // Enqueue FIRST, then set OP_WRITE (critical ordering)
            boolean offered = this.sendQueue.offer(wrapper);
            if (!offered) {
                return false; // Queue full
            }

            // NOW set OP_WRITE - message is guaranteed to be in queue
            switch (this.getPeerType()) {
                case Peer.NETWORK:
                    Network.getInstance().setInterestOps(this.socketChannel, SelectionKey.OP_WRITE);
                    break;
                case Peer.NETWORKDATA:
                    NetworkData.getInstance().setInterestOps(this.socketChannel, SelectionKey.OP_WRITE);
                    break;
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("Error queuing pre-serialized message: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Internal wrapper class for pre-serialized messages.
     * 
     * <p>This lightweight Message subclass holds pre-serialized bytes and returns
     * them directly from toBytes(), avoiding any disk I/O or serialization work.
     * 
     * <p>This is used by the two-stage pipeline architecture where messages are
     * pre-loaded and serialized in parallel disk I/O threads, then passed to
     * sender threads for immediate network transmission.
     *
     * @since v5.0.9
     * @author Ice
     */
    private static class PreSerializedMessageWrapper extends Message {
        private final byte[] preSerializedBytes;
        
        /**
         * Constructs a wrapper for pre-serialized message bytes.
         *
         * @param messageId the message ID
         * @param messageType the message type
         * @param preSerializedBytes complete pre-serialized message bytes
         */
        PreSerializedMessageWrapper(int messageId, MessageType messageType, byte[] preSerializedBytes) {
            super(messageId, messageType);
            this.preSerializedBytes = preSerializedBytes;
        }
        
        /**
         * Returns the pre-serialized bytes instantly without any disk I/O.
         * 
         * @return the pre-serialized message bytes
         */
        @Override
        public byte[] toBytes() throws MessageException {
            // Return pre-serialized bytes instantly - zero disk I/O!
            return preSerializedBytes;
        }
    }
}

