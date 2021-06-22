package org.qortal.network;

import com.google.common.hash.HashCode;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.block.CommonBlockData;
import org.qortal.data.network.PeerChainTipData;
import org.qortal.data.network.PeerData;
import org.qortal.network.message.ChallengeMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageException;
import org.qortal.network.message.MessageType;
import org.qortal.network.message.PingMessage;
import org.qortal.settings.Settings;
import org.qortal.utils.ExecuteProduceConsume;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// For managing one peer
public class Peer {
    private static final Logger LOGGER = LogManager.getLogger(Peer.class);

    /**
     * Maximum time to allow <tt>connect()</tt> to remote peer to complete. (ms)
     */
    private static final int CONNECT_TIMEOUT = 2000; // ms

    /**
     * Maximum time to wait for a message reply to arrive from peer. (ms)
     */
    private static final int RESPONSE_TIMEOUT = 3000; // ms

    /**
     * Interval between PING messages to a peer. (ms)
     * <p>
     * Just under every 30s is usually ideal to keep NAT mappings refreshed.
     */
    private static final int PING_INTERVAL = 20_000; // ms

    private volatile boolean isStopping = false;

    private SocketChannel socketChannel = null;
    private InetSocketAddress resolvedAddress = null;
    /**
     * True if remote address is loopback/link-local/site-local, false otherwise.
     */
    private boolean isLocal;

    private final UUID peerConnectionId = UUID.randomUUID();
    private final Object byteBufferLock = new Object();
    private ByteBuffer byteBuffer;

    private Map<Integer, BlockingQueue<Message>> replyQueues;
    private LinkedBlockingQueue<Message> pendingMessages;

    /**
     * True if we created connection to peer, false if we accepted incoming connection from peer.
     */
    private final boolean isOutbound;

    private final Object handshakingLock = new Object();
    private Handshake handshakeStatus = Handshake.STARTED;
    private volatile boolean handshakeMessagePending = false;
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
    private Long lastPingSent;

    byte[] ourChallenge;

    private boolean syncInProgress = false;

    // Versioning
    public static final Pattern VERSION_PATTERN = Pattern.compile(Controller.VERSION_PREFIX
            + "(\\d{1,3})\\.(\\d{1,5})\\.(\\d{1,5})");

    // Peer info

    private final Object peerInfoLock = new Object();

    private String peersNodeId;
    private byte[] peersPublicKey;
    private byte[] peersChallenge;

    private PeerData peerData = null;

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
    private PeerChainTipData peersChainTipData;

    /**
     * Our common block with this peer
     */
    private CommonBlockData commonBlockData;

    // Constructors

    /**
     * Construct unconnected, outbound Peer using socket address in peer data
     */
    public Peer(PeerData peerData) {
        this.isOutbound = true;
        this.peerData = peerData;
    }

    /**
     * Construct Peer using existing, connected socket
     */
    public Peer(SocketChannel socketChannel, Selector channelSelector) throws IOException {
        this.isOutbound = false;
        this.socketChannel = socketChannel;
        sharedSetup(channelSelector);

        this.resolvedAddress = ((InetSocketAddress) socketChannel.socket().getRemoteSocketAddress());
        this.isLocal = isAddressLocal(this.resolvedAddress.getAddress());

        PeerAddress peerAddress = PeerAddress.fromSocket(socketChannel.socket());
        this.peerData = new PeerData(peerAddress);
    }

    // Getters / setters

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

    public Handshake getHandshakeStatus() {
        synchronized (this.handshakingLock) {
            return this.handshakeStatus;
        }
    }

    protected void setHandshakeStatus(Handshake handshakeStatus) {
        synchronized (this.handshakingLock) {
            this.handshakeStatus = handshakeStatus;
            if (handshakeStatus.equals(Handshake.COMPLETED)) {
                this.handshakeComplete = System.currentTimeMillis();
                this.generateRandomMaxConnectionAge();
            }
        }
    }

    private void generateRandomMaxConnectionAge() {
        // Retrieve the min and max connection time from the settings, and calculate the range
        final int minPeerConnectionTime = Settings.getInstance().getMinPeerConnectionTime();
        final int maxPeerConnectionTime = Settings.getInstance().getMaxPeerConnectionTime();
        final int peerConnectionTimeRange = maxPeerConnectionTime - minPeerConnectionTime;

        // Generate a random number between the min and the max
        Random random = new Random();
        this.maxConnectionAge = (random.nextInt(peerConnectionTimeRange) + minPeerConnectionTime) * 1000L;
        LOGGER.debug(String.format("[%s] Generated max connection age for peer %s. Min: %ds, max: %ds, range: %ds, random max: %dms", this.peerConnectionId, this, minPeerConnectionTime, maxPeerConnectionTime, peerConnectionTimeRange, this.maxConnectionAge));

    }

    protected void resetHandshakeMessagePending() {
        this.handshakeMessagePending = false;
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

    protected void setPeersVersion(String versionString, long version) {
        synchronized (this.peerInfoLock) {
            this.peersVersionString = versionString;
            this.peersVersion = version;
        }
    }

    public Long getPeersConnectionTimestamp() {
        synchronized (this.peerInfoLock) {
            return this.peersConnectionTimestamp;
        }
    }

    protected void setPeersConnectionTimestamp(Long peersConnectionTimestamp) {
        synchronized (this.peerInfoLock) {
            this.peersConnectionTimestamp = peersConnectionTimestamp;
        }
    }

    public Long getLastPing() {
        synchronized (this.peerInfoLock) {
            return this.lastPing;
        }
    }

    protected void setLastPing(long lastPing) {
        synchronized (this.peerInfoLock) {
            this.lastPing = lastPing;
        }
    }

    protected byte[] getOurChallenge() {
        return this.ourChallenge;
    }

    public String getPeersNodeId() {
        synchronized (this.peerInfoLock) {
            return this.peersNodeId;
        }
    }

    protected void setPeersNodeId(String peersNodeId) {
        synchronized (this.peerInfoLock) {
            this.peersNodeId = peersNodeId;
        }
    }

    public byte[] getPeersPublicKey() {
        synchronized (this.peerInfoLock) {
            return this.peersPublicKey;
        }
    }

    protected void setPeersPublicKey(byte[] peerPublicKey) {
        synchronized (this.peerInfoLock) {
            this.peersPublicKey = peerPublicKey;
        }
    }

    public byte[] getPeersChallenge() {
        synchronized (this.peerInfoLock) {
            return this.peersChallenge;
        }
    }

    protected void setPeersChallenge(byte[] peersChallenge) {
        synchronized (this.peerInfoLock) {
            this.peersChallenge = peersChallenge;
        }
    }

    public PeerChainTipData getChainTipData() {
        synchronized (this.peerInfoLock) {
            return this.peersChainTipData;
        }
    }

    public void setChainTipData(PeerChainTipData chainTipData) {
        synchronized (this.peerInfoLock) {
            this.peersChainTipData = chainTipData;
        }
    }

    public CommonBlockData getCommonBlockData() {
        synchronized (this.peerInfoLock) {
            return this.commonBlockData;
        }
    }

    public void setCommonBlockData(CommonBlockData commonBlockData) {
        synchronized (this.peerInfoLock) {
            this.commonBlockData = commonBlockData;
        }
    }

    protected void queueMessage(Message message) {
        if (!this.pendingMessages.offer(message)) {
            LOGGER.info("[{}] No room to queue message from peer {} - discarding", this.peerConnectionId, this);
        }
    }

    public boolean isSyncInProgress() {
        return this.syncInProgress;
    }

    public void setSyncInProgress(boolean syncInProgress) {
        this.syncInProgress = syncInProgress;
    }

    @Override
    public String toString() {
        // Easier, and nicer output, than peer.getRemoteSocketAddress()
        return this.peerData.getAddress().toString();
    }

    // Processing

    private void sharedSetup(Selector channelSelector) throws IOException {
        this.connectionTimestamp = NTP.getTime();
        this.socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        this.socketChannel.configureBlocking(false);
        this.socketChannel.register(channelSelector, SelectionKey.OP_READ);
        this.byteBuffer = null; // Defer allocation to when we need it, to save memory. Sorry GC!
        this.replyQueues = Collections.synchronizedMap(new HashMap<Integer, BlockingQueue<Message>>());
        this.pendingMessages = new LinkedBlockingQueue<>();

        Random random = new SecureRandom();
        this.ourChallenge = new byte[ChallengeMessage.CHALLENGE_LENGTH];
        random.nextBytes(this.ourChallenge);
    }

    public SocketChannel connect(Selector channelSelector) {
        LOGGER.trace("[{}] Connecting to peer {}", this.peerConnectionId, this);

        try {
            this.resolvedAddress = this.peerData.getAddress().toSocketAddress();
            this.isLocal = isAddressLocal(this.resolvedAddress.getAddress());

            this.socketChannel = SocketChannel.open();
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
            LOGGER.debug("[{}] Connected to peer {}", this.peerConnectionId, this);
            sharedSetup(channelSelector);
            return socketChannel;
        } catch (IOException e) {
            LOGGER.trace("[{}] Post-connection setup failed, peer {}", this.peerConnectionId, this);
            try {
                socketChannel.close();
            } catch (IOException ce) {
                // Failed to close?
            }
            return null;
        }
    }

    /**
     * Attempt to buffer bytes from socketChannel.
     *
     * @throws IOException If this channel is not yet connected
     */
    protected void readChannel() throws IOException {
        synchronized (this.byteBufferLock) {
            while (true) {
                if (!this.socketChannel.isOpen() || this.socketChannel.socket().isClosed()) {
                    return;
                }

                // Do we need to allocate byteBuffer?
                if (this.byteBuffer == null) {
                    this.byteBuffer = ByteBuffer.allocate(Network.getInstance().getMaxMessageSize());
                }

                final int priorPosition = this.byteBuffer.position();
                final int bytesRead = this.socketChannel.read(this.byteBuffer);
                if (bytesRead == -1) {
                    if (priorPosition > 0) {
                        this.disconnect("EOF - read " + priorPosition + " bytes");
                    } else {
                        this.disconnect("EOF - failed to read any data");
                    }
                    return;
                }

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
                final boolean wasByteBufferFull = !this.byteBuffer.hasRemaining();

                while (true) {
                    final Message message;

                    // Can we build a message from buffer now?
                    ByteBuffer readOnlyBuffer = this.byteBuffer.asReadOnlyBuffer().flip();
                    try {
                        message = Message.fromByteBuffer(readOnlyBuffer);
                    } catch (MessageException e) {
                        LOGGER.debug("[{}] {}, from peer {}", this.peerConnectionId, e.getMessage(), this);
                        this.disconnect(e.getMessage());
                        return;
                    }

                    if (message == null && bytesRead == 0 && !wasByteBufferFull) {
                        // No complete message in buffer, no more bytes to read from socket
                        // even though there was room to read bytes

                        /* DISABLED
                        // If byteBuffer is empty then we can deallocate it, to save memory, albeit costing GC
                        if (this.byteBuffer.remaining() == this.byteBuffer.capacity()) {
                            this.byteBuffer = null;
                        }
                        */

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
                    this.byteBuffer.position(readOnlyBuffer.position());
                    // Copy bytes after read message to front of buffer,
                    // adjusting position accordingly, reset limit to capacity
                    this.byteBuffer.compact();

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

                    // Prematurely end any blocking channel select so that new messages can be processed.
                    // This might cause this.socketChannel.read() above to return zero into bytesRead.
                    Network.getInstance().wakeupChannelSelector();
                }
            }
        }
    }

    protected Task getMessageTask() {
        /*
         * If we are still handshaking and there is a message yet to be processed then
         * don't produce another message task. This allows us to process handshake
         * messages sequentially.
         */
        if (this.handshakeMessagePending) {
            return null;
        }

        final Message nextMessage = this.pendingMessages.poll();

        if (nextMessage == null) {
            return null;
        }

        LOGGER.trace("[{}] Produced {} message task from peer {}", this.peerConnectionId,
                nextMessage.getType().name(), this);

        if (this.handshakeStatus != Handshake.COMPLETED) {
            this.handshakeMessagePending = true;
        }

        // Return a task to process message in queue
        return new MessageTask(this, nextMessage);
    }

    /**
     * Attempt to send Message to peer.
     *
     * @param message message to be sent
     * @return <code>true</code> if message successfully sent; <code>false</code> otherwise
     */
    public boolean sendMessage(Message message) {
        if (!this.socketChannel.isOpen()) {
            return false;
        }

        try {
            // Send message
            LOGGER.trace("[{}] Sending {} message with ID {} to peer {}", this.peerConnectionId,
                    message.getType().name(), message.getId(), this);

            ByteBuffer outputBuffer = ByteBuffer.wrap(message.toBytes());

            synchronized (this.socketChannel) {
                final long sendStart = System.currentTimeMillis();

                while (outputBuffer.hasRemaining()) {
                    int bytesWritten = this.socketChannel.write(outputBuffer);

                    LOGGER.trace("[{}] Sent {} bytes of {} message with ID {} to peer {}", this.peerConnectionId,
                            bytesWritten, message.getType().name(), message.getId(), this);

                    if (bytesWritten == 0) {
                        // Underlying socket's internal buffer probably full,
                        // so wait a short while for bytes to actually be transmitted over the wire

                        /*
                         * NOSONAR squid:S2276 - we don't want to use this.socketChannel.wait()
                         * as this releases the lock held by synchronized() above
                         * and would allow another thread to send another message,
                         * potentially interleaving them on-the-wire, causing checksum failures
                         * and connection loss.
                         */
                        Thread.sleep(1L); //NOSONAR squid:S2276

                        if (System.currentTimeMillis() - sendStart > RESPONSE_TIMEOUT) {
                            // We've taken too long to send this message
                            return false;
                        }
                    }
                }
            }
        } catch (MessageException e) {
            LOGGER.warn("[{}] Failed to send {} message with ID {} to peer {}: {}", this.peerConnectionId,
                    message.getType().name(), message.getId(), this, e.getMessage());
            return false;
        } catch (IOException | InterruptedException e) {
            // Send failure
            return false;
        }

        // Sent OK
        return true;
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
    public Message getResponse(Message message) throws InterruptedException {
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
        if (!this.sendMessage(message)) {
            this.replyQueues.remove(id);
            return null;
        }

        try {
            return blockingQueue.poll(RESPONSE_TIMEOUT, TimeUnit.MILLISECONDS);
        } finally {
            this.replyQueues.remove(id);
        }
    }

    protected void startPings() {
        // Replacing initial null value allows getPingTask() to start sending pings.
        LOGGER.trace("[{}] Enabling pings for peer {}", this.peerConnectionId, this);
        this.lastPingSent = NTP.getTime();
    }

    protected Task getPingTask(Long now) {
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
        this.shutdown();

        Network.getInstance().onDisconnect(this);
    }

    public void shutdown() {
        if (!isStopping) {
            LOGGER.debug("[{}] Shutting down peer {}", this.peerConnectionId, this);
        }
        isStopping = true;

        if (this.socketChannel.isOpen()) {
            try {
                this.socketChannel.shutdownOutput();
                this.socketChannel.close();
            } catch (IOException e) {
                LOGGER.debug("[{}] IOException while trying to close peer {}", this.peerConnectionId, this);
            }
        }
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

        return this.getPeersVersion() >= minVersion;
    }


    // Common block data

    public boolean canUseCachedCommonBlockData() {
        PeerChainTipData peerChainTipData = this.getChainTipData();
        CommonBlockData commonBlockData = this.getCommonBlockData();

        if (peerChainTipData != null && commonBlockData != null) {
            PeerChainTipData commonBlockChainTipData = commonBlockData.getChainTipData();
            if (peerChainTipData.getLastBlockSignature() != null && commonBlockChainTipData != null
                    && commonBlockChainTipData.getLastBlockSignature() != null) {
                if (Arrays.equals(peerChainTipData.getLastBlockSignature(),
                        commonBlockChainTipData.getLastBlockSignature())) {
                    return true;
                }
            }
        }
        return false;
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

    public boolean hasReachedMaxConnectionAge() {
        return this.getConnectionAge() > this.getMaxConnectionAge();
    }
}
