package org.qortal.network.reticulum;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

import static io.reticulum.link.LinkStatus.CLOSED;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
//import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.time.Instant;
import java.util.*;
import java.io.IOException;

//import io.reticulum.Reticulum;
import io.reticulum.link.Link;
import io.reticulum.link.RequestReceipt;
import io.reticulum.packet.PacketReceiptStatus;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketReceipt;
import io.reticulum.identity.Identity;
import io.reticulum.channel.Channel;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.ProofStrategy;
import io.reticulum.resource.Resource;
import static io.reticulum.link.TeardownSession.INITIATOR_CLOSED;
import static io.reticulum.link.TeardownSession.DESTINATION_CLOSED;
import static io.reticulum.link.TeardownSession.TIMEOUT;
import static io.reticulum.link.LinkStatus.ACTIVE;
//import static io.reticulum.link.LinkStatus.CLOSED;
import static io.reticulum.identity.IdentityKnownDestination.recall;
//import static io.reticulum.identity.IdentityKnownDestination.recallAppData;
import io.reticulum.buffer.Buffer;
import io.reticulum.buffer.BufferedRWPair;
import org.qortal.network.reticulum.RNSCommon.PeerAspect;
import org.qortal.network.reticulum.RNSCommon.PeerMetaType;
import static io.reticulum.utils.IdentityUtils.concatArrays;

import lombok.Getter;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.CommonBlockData;
import org.qortal.data.network.PeerData;
import org.qortal.network.Handshake;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.PeerCtor;
import org.qortal.network.helper.PeerCapabilities;
import org.qortal.network.helper.PeerDownloadSpeedTracker;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageType;
import org.qortal.network.message.PingMessage;
import org.qortal.network.message.*;
import org.qortal.network.message.MessageException;
import org.qortal.network.task.MessageTask;
import org.qortal.network.task.ReticulumMessageTask;
import org.qortal.settings.Settings;
import org.qortal.utils.ExecuteProduceConsume.Task;
import org.qortal.utils.NTP;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.concurrent.*;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.apache.commons.lang3.ArrayUtils.subarray;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import lombok.extern.slf4j.Slf4j;
import lombok.Setter;
import lombok.Data;
import lombok.AccessLevel;
//import lombok.Synchronized;
//
//import org.qortal.network.message.Message;
//import org.qortal.network.message.MessageException;

import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.lang.IllegalStateException;

@Data
@Slf4j
public class ReticulumPeer implements Peer {

    /**
     * Resolved on use, not in a static initialiser: reading Settings during class initialisation
     * loads the settings file, which in turn initialises BlockChain and the crypto stack. That
     * makes the class impossible to touch (even to mock) without booting most of the node, and
     * turns any settings problem into a NoClassDefFoundError far from its cause. Settings is a
     * cached singleton, so resolving per call costs nothing.
     */
    private static String appName() {
        return Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_APP_NAME : RNSCommon.MAINNET_APP_NAME;
    }
    //static final String defaultConfigPath = new String(".reticulum");
    //static final String defaultConfigPath = RNSCommon.defaultRNSConfigPath;

    private PeerMetaType peerMetaType = PeerMetaType.RETICULUM;
    private byte[] destinationHash;   // remote destination hash
    Destination peerDestination;      // OUT destination created for this
    PeerAspect peerAspect;       // based on Destination
    //PeerMetaType peerKind = PeerMetaType.RETICULUM;
    private Identity serverIdentity;
    @Setter(AccessLevel.PACKAGE) private Instant creationTimestamp;
    @Setter(AccessLevel.PACKAGE) private Instant lastAccessTimestamp;
    @Setter(AccessLevel.PACKAGE) private Instant lastLinkProbeTimestamp;
    //@Setter(AccessLevel.PACKAGE) public boolean isPeerAvailable;
    private boolean isPeerAvailable;  // peer is available in Network lists
    Link peerLink;
    byte[] peerLinkHash;
    volatile BufferedRWPair peerBuffer;
    Channel channel;
    // Guards createPeerBuffer() so only one thread calls getChannel() at a time.
    private final java.util.concurrent.atomic.AtomicBoolean creatingBuffer = new java.util.concurrent.atomic.AtomicBoolean(false);
    // One-shot latches per link: every thread that tries to send to a dying peer discovers the
    // death independently, so without these a single teardown is announced (and queued) once per
    // concurrent sender — a live run showed 8 "buffer closed" warnings and 4 disconnects for one
    // peer within the same second.
    // Reset by initPeerLink(), so a peer whose link is re-initiated can be torn down again.
    // Inbound peers never call initPeerLink() and are never re-linked (they are built fresh per
    // Link, see removeIncomingPeer), so a one-shot latch is the whole story for them.
    private final java.util.concurrent.atomic.AtomicBoolean removalSubmitted = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean closedLinkHandled = new java.util.concurrent.atomic.AtomicBoolean(false);
    int receiveStreamId = 1001;
    int sendStreamId = 1001;
    ReticulumPeerAddress peerAddress;
    //private Boolean isInitiator;
    @Getter public Boolean isInitiator;
    private Boolean deleteMe = false;
    //private Boolean isVacant = true;
    private Long lastPacketRtt = null;
    //private byte[] emptyBuffer = {0,0,0,0,0};

    private Double requestResponseProgress;
    @Setter(AccessLevel.PACKAGE) private Boolean peerTimedOut = false;

    // for qortal networking
    private static final int RESPONSE_TIMEOUT = 10_000; // [ms]
    ///**
    // * Maximum time to wait for a message to be added to sendQueue (ms)
    // */
    //private static final int QUEUE_TIMEOUT = 1000; // ms

    /**
      * Timeout for blockchain synchronization operations (ms)
      * Shorter timeout to avoid blocking transaction processing during sync.
      */
    public static final int SYNC_RESPONSE_TIMEOUT = 4_000; // ms

    /**
     * Interval between PING messages to a peer. (ms)
     * <p>
     * Link Timeout is 3min. So under every 1min is good to keep Reticulum Links ACTIVE.
     */
    private static final int PING_INTERVAL = 55_000; // ms

    /**
     * Maximum time to wait for a peer to respond with blocks (ms)
     */
    public static final int FETCH_BLOCKS_TIMEOUT = 10000;

    //private static final long LINK_PING_INTERVAL = 55 * 1000L; // ms
    private byte[] messageMagic;  // set in message creating classes
    private Long lastPing = null;      // last (packet) ping roundtrip time [ms]
    private Long lastPingSent = null;  // time last (packet) ping was sent, or null if not started.
    @Setter(AccessLevel.PACKAGE) private Instant lastPingResponseReceived = null; // time last (packet) ping succeeded
    private Map<Integer, BlockingQueue<Message>> replyQueues;
    private UUID peerConnectionId = UUID.randomUUID();
    private LinkedBlockingQueue<Message> pendingMessages;
    private boolean syncInProgress = false;
    private final Object peerInfoLock = new Object();
    private PeerData peerData;
    private PeerCapabilities peerCapabilities;
    private long linkEstablishedTime = -1L; // equivalent of (tcpip) Peer 'handshakeComplete'
    private volatile boolean isStopping = false;
    // Versioning
    public static final Pattern VERSION_PATTERN = Pattern.compile(Controller.VERSION_PREFIX
            + "(\\d{1,3})\\.(\\d{1,5})\\.(\\d{1,5})");
    /* Pending signature requests */
    private List<byte[]> pendingSignatureRequests = Collections.synchronizedList(new ArrayList<>());
    // Note: Reticulum uses Buffer for send, not a bounded queue. These fields satisfy the Peer interface.
    /**
     * Latest block info as reported by peer.
     */
    private List<BlockSummaryData> peersChainTipData = Collections.emptyList();
    private int peerType = Peer.NETWORK;
    /**
     * Our common block with this peer
     */
    private CommonBlockData commonBlockData;
    /**
     * Last time we detected this peer as TOO_DIVERGENT
     */
    private Long lastTooDivergentTime;
    /**
     * Version string as reported by peer.
     */
    private String peersVersionString = null;
    /**
     * Numeric version of peer.
     */
    private Long peersVersion = null;
    /**
     * Peer's value of connectionTimestamp.
     */
    private Long peersConnectionTimestamp = null;
    /**
     * Timestamp of when (socket was accepted, or) connected.
     */
    private Long connectionTimestamp = null;
    /**
     * peer info
     */
    private String peersNodeId;
    ///**
    // * Known starting sequences for data received over buffer
    // */
    //private byte[] SEQ_REQUEST_CONFIRM_ID = new byte[]{0x53, 0x52, 0x65, 0x71, 0x43, 0x49, 0x44}; // SReqCID
    //private byte[] SEQ_RESPONSE_CONFIRM_ID = new byte[]{0x53, 0x52, 0x65, 0x73, 0x70, 0x43, 0x49, 0x44}; // SRespCID

    // Message stats
    private static class MessageStats {
        public final LongAdder count = new LongAdder();
        public final LongAdder totalBytes = new LongAdder();
    }

    private final Map<MessageType, ReticulumPeer.MessageStats> receivedMessageStats = new ConcurrentHashMap<>();
    private final Map<MessageType, ReticulumPeer.MessageStats> sentMessageStats = new ConcurrentHashMap<>();

    /**
     * Track last response of QDN assets to find nodes that have useful/maximum data
     */
    private Long lastValidUse = null;  // Note: not sure if we need this

    /**
     * Tracks download speeds for chunks received from this peer.
     * Used to track when data was last received for peer selection optimization.
     */
    private PeerDownloadSpeedTracker downloadSpeedTracker = new PeerDownloadSpeedTracker();
    private Long connectedTimestamp = null;

    /**
     * Constructor for initiator peers
     */
    @PeerCtor("destination-hash")
    public ReticulumPeer(byte[] dhash) {
        this(dhash, RNSCommon.PeerAspect.BASE);
    }

    public ReticulumPeer(byte[] dhash, RNSCommon.PeerAspect aspect) {
        this.peerAspect = aspect;
        this.destinationHash = dhash;
        this.serverIdentity = recall(dhash);
        //this.sendStreamId = getRandomStreamId();
        //this.receiveStreamId = sendStreamId;
        initPeerLink();
        //setCreationTimestamp(System.currentTimeMillis());
        this.creationTimestamp = Instant.now();
        //this.isVacant = true;
        this.replyQueues = new ConcurrentHashMap<>();
        this.pendingMessages = new LinkedBlockingQueue<>();
        this.peerAddress = new ReticulumPeerAddress(dhash);
        //this.peerData = new PeerData(peerAddress,NTP.getTime(),"ReticulumPeer");
        this.peerData = new PeerData(
          peerAddress,
          null, null, null,
          System.currentTimeMillis(),
          "ReticulumPeer"
        );
        this.peerData.setPeerMetaType(this.peerMetaType);

        Long ntpTime = NTP.getTime();
        long timestamp = (ntpTime != null) ? ntpTime : System.currentTimeMillis();
        this.connectionTimestamp = timestamp;
        this.lastValidUse = timestamp;
    }

    /**
     * Constructor for non-initiator peers
     */
    @PeerCtor("link")
    public ReticulumPeer(Link link) {
        this.peerLink = link;
        //this.peerLinkId = link.getLinkId();
        this.peerDestination = link.getDestination();
        this.destinationHash = link.getDestination().getHash();
        this.serverIdentity = link.getRemoteIdentity();
        //this.sendStreamId = getRandomStreamId();
        //this.receiveStreamId = sendStreamId;

        this.replyQueues = new ConcurrentHashMap<>();
        this.pendingMessages = new LinkedBlockingQueue<>();
        this.creationTimestamp = Instant.now();
        this.lastAccessTimestamp = Instant.now();
        this.lastLinkProbeTimestamp = null;
        this.isInitiator = false;
        //this.isVacant = false;

        // This constructor is only used for INBOUND links, which are already established when the
        // library hands them to us via baseClientConnected()/dataClientConnected(). Because the link
        // is already ACTIVE, the linkEstablished() callback registered below will never fire for it,
        // so linkEstablishedTime (which getConnectionEstablishedTime()/getConnectionAge() rely on)
        // would stay -1 and the peer's reported age would be stuck at "connecting...". Record the
        // establishment time here — accepting the inbound link is effectively its establishment.
        this.linkEstablishedTime = System.currentTimeMillis();

        this.peerLink.setLinkEstablishedCallback(this::linkEstablished);
        this.peerLink.setLinkClosedCallback(this::linkClosed);
        this.peerLink.setPacketCallback(this::linkPacketReceived);

        this.peerAddress = new ReticulumPeerAddress(this.destinationHash);
        //this.peerData = new PeerData(this.peerAddress, NTP.getTime(),"ReticulumPeer");
        this.peerData = new PeerData(
          this.peerAddress,
          null, null, null,
          System.currentTimeMillis(),
          "ReticulumPeer"
        );
        this.peerData.setPeerMetaType(this.peerMetaType);

        Long ntpTime = NTP.getTime();
        long timestamp = (ntpTime != null) ? ntpTime : System.currentTimeMillis();
        this.connectionTimestamp = timestamp;
        this.lastValidUse = timestamp;
    }

    /** 
     * interface to instance
     */
    public ReticulumPeer unwrap() {
        //Class<?> actualClass = myPeer.getClass();
        //return (actualClass) this;
        //return (T) this;
        return (ReticulumPeer) this;
    }
    //public <T> T unwrap(Class<T> clazz) {
    //    return clazz.cast(this);
    //}

    public void initPeerLink() {
        peerDestination = new Destination(
            this.serverIdentity,
            Direction.OUT,
            DestinationType.SINGLE,
            appName(),
            peerAspect == RNSCommon.PeerAspect.DATA ? "qdn" : "core"
        );
        peerDestination.setProofStrategy(ProofStrategy.PROVE_ALL);

        this.creationTimestamp = Instant.now();
        this.lastAccessTimestamp = Instant.now();
        this.lastLinkProbeTimestamp = null;
        this.isInitiator = true;

        this.peerLink = new Link(peerDestination);
        // Fresh link, fresh teardown state (see the two latches' declaration).
        this.removalSubmitted.set(false);
        this.closedLinkHandled.set(false);

        this.peerLink.setLinkEstablishedCallback(this::linkEstablished);
        this.peerLink.setLinkClosedCallback(this::linkClosed);
        this.peerLink.setPacketCallback(this::linkPacketReceived);
    }

    @Override
    public String toString() {
        // for messages we want an address-like string representation
        if (nonNull(this.peerLink)) {
            return encodeHexString(this.getPeerLink().getLinkId());
        } else {
            return encodeHexString(this.getDestinationHash());
        }
    }

    /**
     * How to name this peer in a log line.
     * <p>
     * Do <b>not</b> log {@code destinationHash} for an inbound peer: the {@link
     * #ReticulumPeer(Link)} constructor sets it from {@code link.getDestination()}, which is
     * <i>our</i> destination — identical for every inbound peer of that aspect, so it tells you
     * nothing about who connected and makes concurrent failures indistinguishable. Prefer the
     * remote node's destination hash, derived from its identity once it has identified; fall back
     * to the link id, which is at least unique per connection.
     */
    String remoteLogId() {
        if (Boolean.TRUE.equals(this.isInitiator)) {
            return encodeHexString(this.destinationHash);
        }
        String identityKey = RNSPeerRegistry.incomingIdentityKey(this);
        if (identityKey != null) {
            return identityKey;
        }
        return nonNull(this.peerLink) ? "link:" + encodeHexString(this.peerLink.getLinkId()) : "unidentified";
    }

    /**
     * Claim the right to run this peer's teardown, so only the first of N concurrent senders that
     * hit the dead link does the work and writes the log line.
     *
     * @return true for the caller that claimed it; false for everyone after
     */
    boolean claimRemoval() {
        return this.removalSubmitted.compareAndSet(false, true);
    }

    /** {@link #claimRemoval} for the closed-link disconnect path. */
    boolean claimClosedLinkHandling() {
        return this.closedLinkHandled.compareAndSet(false, true);
    }

    public int getRandomStreamId() {
        // Note: stream id must be between 0..16383
        return ThreadLocalRandom.current().nextInt(1, 16383);
    }

    public Object getPeerCapability(String capName) {
        return peerCapabilities == null ? null : peerCapabilities.getCapability(capName);
    }

    //// TODO (?): a way to determine stuck buffer (change 'outBuffer' for Reticulum). Ref. IPPeer post version 6.1
    //public boolean hasStuckWrite(long timeoutMs
    //    // Only consider it stuck if there's actually data waiting to be written
    //    if (this.outputBuffer == null || !this.outputBuffer.hasRemaining()) {
    //        return false;
    //    }

    //    long elapsed = System.currentTimeMillis() - this.lastWriteProgressTime;
    //    return elapsed > timeoutMs;
    //}
    //
    //public String getStuckWriteInfo() {
    //    if (this.outputBuffer == null) {
    //        return null;
    //    }
    //    return String.format("type=%s, id=%d, remaining=%d bytes, stalled for %dms",
    //            this.outputMessageType,
    //            this.outputMessageId,
    //            this.outputBuffer.remaining(),
    //            System.currentTimeMillis() - this.lastWriteProgressTime);
    //}
    public void QDNUse() {
        this.lastValidUse = NTP.getTime();
    }

    public long getLastQDNUse() {
        return this.lastValidUse;
    }

    public void shutdownChannel() {
        // Do NOT call channel.shutdown() — it deadlocks with Channel.receive():
        //   shutdown()  acquires synchronized(channel) first, then lock
        //   receive()   acquires lock first, then synchronized(channel) via runCallbacks()
        // Inverted lock order → ABBA deadlock between rnsWorkerPool and Reticulum receive thread.
        // The Reticulum library cleans up the Channel when the Link closes; we just drop our
        // references. peerBufferReady() has a null guard to safely ignore any late callbacks.
        this.channel = null;
        this.peerBuffer = null;
    }

    /**
     * Creates the RNS Channel and Buffer for this peer link.
     * Must only be called once per link, from linkEstablished() (initiator) or
     * baseClientConnected() (non-initiator). Never call this on the send/broadcast path —
     * getChannel() acquires synchronized(link) and multiple concurrent callers pile up if
     * the Reticulum library holds that lock during link setup (bug: blocked Network-Workers).
     */
    public void createPeerBuffer() {
        if (this.peerBuffer != null) return;  // already created (volatile read)
        // Only create a buffer once the link is fully established. If called while PENDING
        // (e.g. baseClientConnected fires early), return — linkEstablished() will retry.
        if (this.peerLink == null || this.peerLink.getStatus() != ACTIVE) {
            log.debug("createPeerBuffer - skipping: link not ACTIVE ({})",
                    this.peerLink != null ? this.peerLink.getStatus() : "null");
            return;
        }
        // CAS guard: only one thread calls getChannel() at a time. If another thread is
        // already creating the buffer, skip — the buffer will be visible shortly.
        if (!creatingBuffer.compareAndSet(false, true)) return;
        try {
            if (this.peerBuffer != null) return;  // double-check after CAS
            var ntpNow = NTP.getTime();
            channel = this.peerLink.getChannel();
            log.info("creating buffer - peerLink status: {}, channel: {}", this.peerLink.getStatus(), channel);
            this.peerBuffer = Buffer.createBidirectionalBuffer(receiveStreamId, sendStreamId, channel, this::peerBufferReady);
            this.lastAccessTimestamp = Instant.now();
            this.peerData.setLastAttempted(ntpNow);
            this.peerData.setLastConnected(ntpNow);
            this.deleteMe = false; // buffer is alive — clear any pending deletion flag
            this.startPings();
            makePeerAvailable();
            // Record this peer's hash as confirmed-active so it's saved for fast reconnect on restart.
            // Only initiator peers have the remote's destination hash; non-initiators have our own hash.
            if (Boolean.TRUE.equals(isInitiator)) {
                RNS.getInstance().confirmPeerHash(encodeHexString(destinationHash), this.peerAspect);
            }
            // Chain tip is NOT sent here — sending immediately on link establishment caused
            // Channel "retry count exceeded" teardowns. broadcastOurChain() delivers it shortly.
        } finally {
            creatingBuffer.set(false);
        }
    }

    public BufferedRWPair getOrInitPeerBuffer() {
        if (this.peerLink == null || this.peerLink.getStatus() != ACTIVE) {
            log.debug("getOrInitPeerBuffer - skipping: link not ACTIVE (status: {})",
                this.peerLink != null ? this.peerLink.getStatus() : "null");
            return null;
        }
        var rns = RNS.getInstance();
        var ntpNow = NTP.getTime();
        if (nonNull(this.peerBuffer)) {
            try {
                log.trace("peerBuffer exists: {}, link status: {}", this.peerBuffer, this.peerLink.getStatus());
                if (rns.isUnreachable(this)) {
                    makePeerUnavailable();
                    shutdownChannel();
                    return null;
                }
            } catch (IllegalStateException e) {
                // Exception thrown by Reticulum if the buffer is unusable (Channel, Link, etc)
                log.warn("can't establish Channel/Buffer (remote peer down?), closing link: {}");
                shutdownChannel();
                this.peerData.setLastAttempted(ntpNow);
                this.peerData.setLastMisbehaved(ntpNow);
            }
        } else {
            // Buffer is null: either createPeerBuffer() hasn't fired yet (link just became ACTIVE),
            // or shutdownChannel() was called (e.g., peer went unreachable) but the link is still
            // ACTIVE. Recreate it. createPeerBuffer() uses an AtomicBoolean so only one thread
            // calls getChannel() at a time — no synchronized(link) pile-up.
            createPeerBuffer();
        }
        return getPeerBuffer();
    }

    public Link getOrInitPeerLink() {
        if (this.peerLink.getStatus() == ACTIVE) {
            lastAccessTimestamp = Instant.now();
        } else {
            // Clear stale timeout flag so prunePeers() doesn't remove this peer on the
            // next cycle immediately after we re-initiate the link on a fresh announce.
            this.peerTimedOut = false;
            initPeerLink();
        }
        return this.peerLink;
    }

    public void disconnect(String reason) {
        log.info("Disconnecting peer {} after {} - reason: {}", this.toString(), getConnectionAge(), reason);
        var isShuttingDown = RNS.getInstance().isShuttingDown();
        log.debug("ReticulumPeer disconnect, RNS isShuttingDown: {}", isShuttingDown);
        if (!isShuttingDown) {
            makePeerUnavailable();
        }
        this.isPeerAvailable = false;
        // Close the underlying Link so its watchdog thread can exit. Previously teardown()
        // was left commented out to avoid the ABBA deadlock, which meant dead peers' Links
        // stayed ACTIVE with a live watchdog forever (test-14: thousands leaked → heap OOM).
        closePeerLinkNonBlocking();
    }

    /**
     * Closes this peer's Link without the blocking, synchronized {@link Link#teardown()} (which
     * sends a LINKCLOSE packet under the Link monitor and can deadlock with the Reticulum receive
     * thread — the ABBA lock inversion removed elsewhere in this class). Setting the status is a
     * plain volatile write with no lock: the Link's watchdog thread exits on its next wake, and
     * Transport's jobs loop then drops the CLOSED link from activeLinks/pendingLinks so it becomes
     * GC-eligible. This stops the watchdog-thread / Link-object accumulation seen in test-14.
     */
    public void closePeerLinkNonBlocking() {
        var link = this.peerLink;
        if (nonNull(link) && link.getStatus() != CLOSED) {
            link.setStatus(CLOSED);
        }
    }

    public void shutdown() {
        if (nonNull(this.peerLink)) {
            //log.info("shutdown - peerLink: {}, status: {}, channel: {}", peerLink.toString(), peerLink.getStatus(), peerBuffer);
            if (peerLink.getStatus() == ACTIVE) {
                disconnect("shutting down");
            } else {
                log.info("shutdown - status (non-ACTIVE): {}", peerLink.getStatus());
                // Even non-ACTIVE (PENDING/HANDSHAKE/STALE) links have a live watchdog thread —
                // close them too so every watchdog exits and the JVM can stop cleanly.
                closePeerLinkNonBlocking();
            }
        }
        this.deleteMe = true;
    }

    public Channel getChannel() {
        if (isNull(getPeerLink())) {
            //log.warn("getChannel - skipping: link is null.");
            return null;
        }
        setLastAccessTimestamp(Instant.now());
        return getPeerLink().getChannel();
    }

    //public Boolean getIsInitiator() {
    //    return this.isInitiator;
    //}

    public boolean isOutbound() {
        return this.isInitiator;
    }

    public String getPeerIndexString() {
        return encodeHexString(getDestinationHash());
    }

    public boolean hasActivePeerLink() {
        var result = false;
        if (nonNull(this.peerLink)) {
            if (this.peerLink.getStatus() != ACTIVE) {
                log.debug("hasActivePeerLink - peerLink status ({}) != ACTIVE", this.peerLink.getStatus());
                result = false;
            } else {
                result = true;
            }
        } else {
            log.debug("hasActivePeerLink - peer [{}] peerLink is null", this.toString());
            // make peer unavailable to Network
            makePeerUnavailable();
            result = false;
        }
        return result;
    }

    /**
     * True if this peer's Reticulum Link is gone — {@code null} or {@code CLOSED}. Used by
     * Network.prunePeers() to reconcile dead Reticulum peers out of the connected/handshaked
     * lists: a peer added via {@link #makePeerAvailable()} but never tracked in RNS's
     * linkedPeers/incomingPeers (e.g. a duplicate skipped by addLinkedPeer's dedup race) is never
     * removed by RNS teardown, so it leaks there and bloats the scheduler's ping scan. A CLOSED
     * link never recovers (reconnect creates a fresh Link), so removing such a peer cannot disrupt
     * a live connection. Deliberately NOT true for PENDING/HANDSHAKE/STALE — those may still be
     * establishing or recovering, and RNS owns their lifecycle.
     */
    public boolean isLinkClosed() {
        return this.peerLink == null || this.peerLink.getStatus() == CLOSED;
    }

    public void makePeerAvailable() {
        if (this.peerAspect != RNSCommon.PeerAspect.DATA) {
            // DATA peers are tracked by RNS's own linkedPeers/incomingPeers lists.
            // NetworkData accesses them via RNS.getActiveDataPeers() — no registration here.
            var network = Network.getInstance();
            network.addConnectedPeer(this);
            network.addHandshakedPeer(this);
            if (Boolean.TRUE.equals(this.isInitiator)) {
                network.addOutboundHandshakedPeer(this);
            }
        }
        this.isPeerAvailable = true;
    }

    public void makePeerUnavailable() {
        this.isPeerAvailable = false;
        if (this.peerAspect != RNSCommon.PeerAspect.DATA) {
            var network = Network.getInstance();
            network.removeHandshakedPeer(this);
            network.removeOutboundHandshakedPeer(this);
            network.removeConnectedPeer(this);
        }
        this.isPeerAvailable = false;
    }

    public ReticulumPeer getInstance() { return this; }
    //public Peer getInstance() { return null; }

    /** Link callbacks */
    public void linkEstablished(Link link) {
        this.linkEstablishedTime = System.currentTimeMillis();
        this.lastAccessTimestamp = Instant.now(); // reset from link-creation time to link-active time
        link.setLinkClosedCallback(this::linkClosed);
        // For incoming peers the constructor fires before the handshake, so getRemoteIdentity()
        // was null then. Resolve it now that the link is established.
        if (!Boolean.TRUE.equals(isInitiator)) {
            if (this.serverIdentity == null) {
                this.serverIdentity = link.getRemoteIdentity();
            }
            // Identity is known now, so drop any older incoming links from the same remote+aspect
            // immediately rather than waiting up to ~60s for the next prunePeers() dedup cycle.
            RNS.getInstance().dedupIncomingPeerByIdentity(this);
        }
        log.info("peerLink {} established (link: {}) with peer: hash - {}, link destination hash: {}",
            encodeHexString(peerLink.getLinkId()), encodeHexString(link.getLinkId()), encodeHexString(destinationHash),
            encodeHexString(link.getDestination().getHash()));
        var ntpNow = NTP.getTime();
        this.peerData.setLastConnected(ntpNow);

        // Create buffer for all peers once the link is ACTIVE. For initiators this is the
        // primary path. For non-initiators it is the fallback when baseClientConnected() fired
        // while the link was still PENDING and createPeerBuffer() returned early.
        // CAS guard inside createPeerBuffer() prevents double-creation.
        createPeerBuffer();
        if (Boolean.TRUE.equals(isInitiator)) {
            // Identify ourselves to the remote (server) side. link.identify() requires the link to be
            // the initiator's and ACTIVE — both hold here. This lets the server resolve our identity
            // via its remoteIdentified callback (see RNS.baseClientConnected/dataClientConnected);
            // without it, inbound links on the remote carry no identity and its identity-based dedup
            // (dedupIncomingPeerByIdentity / prunePeers) can never fire.
            try {
                Identity myIdentity = RNS.getInstance().getServerIdentity();
                if (myIdentity != null) {
                    link.identify(myIdentity);
                } else {
                    log.warn("linkEstablished - no local serverIdentity to identify() as for {}", encodeHexString(destinationHash));
                }
            } catch (Exception e) {
                log.warn("linkEstablished - identify() failed for {}: {}", encodeHexString(destinationHash), e.getMessage());
            }
            // Arm the ping timer: schedule first ping one interval from now.
            this.lastPingSent = ntpNow;
        }
    }
    
    public void linkClosed(Link link) {
        // The library invokes this callback more than once for a single Link: Link.teardown() sets
        // status=CLOSED and calls linkClosed() with no already-closed guard, and teardownPacket()
        // calls it too. A live run showed two full disconnect sequences 1ms apart for one link id
        // (same peer, connection ages 12175141/12175142) — each running shutdownChannel(), disconnect()
        // and a duplicate triggerImmediateAnnounce() kick. Claim the handling once; the removal itself
        // was already idempotent via markPeerForImmediateRemoval() → claimRemoval().
        // Safe as a one-shot per peer instance: peerLink is assigned only in the constructors and is
        // never reassigned, so a peer owns exactly one link for its lifetime. Shares the flag with
        // sendMessage's CLOSED branch on purpose — same event, whichever path notices first wins.
        if (!claimClosedLinkHandling()) return;
        if (isInitiator) {
            // Null the buffer immediately so createPeerBuffer() works correctly if this peer's
            // link is re-initiated before prunePeers() calls removeLinkedPeer() → shutdownChannel().
            // Without this, createPeerBuffer() sees peerBuffer != null (stale from the old link)
            // and returns early, leaving the re-established link with no working buffer.
            shutdownChannel();
            disconnect("link closed");
        }
        // Kick the announce/path-recovery cycle immediately rather than waiting up to 30s for
        // the next loop iteration — of THIS peer's aspect: kicking BASE when a DATA peer drops
        // leaves DATA to wait out its full window. Skip during shutdown (all links close then too).
        if (!RNS.getInstance().isShuttingDown()) {
            RNS.getInstance().triggerImmediateAnnounce(getPeerAspect());
        }
        if (link.getTeardownReason() == TIMEOUT) {
            log.info("linkClosed callback: The link timed out");
            this.peerTimedOut = true;
            //this.peerBuffer = null;
        } else if (link.getTeardownReason() == INITIATOR_CLOSED) {
            log.info("linkClosed callback: The initiator closed the link");
            log.info("peerLink {} closed (link: {}), link destination hash: {}",
                encodeHexString(peerLink.getLinkId()), encodeHexString(link.getLinkId()), encodeHexString(link.getDestination().getHash()));
            //this.peerBuffer = null;
            //peerLink.teardown();
        } else if (link.getTeardownReason() == DESTINATION_CLOSED) {
            log.info("linkClosed callback: The link was closed by the peer, removing peer");
            log.info("peerLink {} closed (link: {}), link destination hash: {}",
                encodeHexString(peerLink.getLinkId()), encodeHexString(link.getLinkId()), encodeHexString(link.getDestination().getHash()));
            //this.peerBuffer = null;
            //peerLink.teardown();
        } else {
            log.info("linkClosed callback: no handled standard reason");
        }
        // Remove incoming peers immediately when their link closes, so reconnecting nodes
        // don't accumulate stale-but-still-ACTIVE entries in incomingPeers between pruning
        // cycles (which run every ~60s). Submitted to rnsWorkerPool to avoid calling
        // removeIncomingPeer() directly from the Reticulum I/O thread while prunePeers()
        // may be iterating the list on the Controller thread.
        if (!Boolean.TRUE.equals(isInitiator) && !RNS.getInstance().isShuttingDown()) {
            RNS.getInstance().markPeerForImmediateRemoval(this);
        }
    }

    public void linkPacketReceived(byte[] message, Packet packet) {
        var msgText = new String(message, StandardCharsets.UTF_8);
        if (msgText.equals("ping")) {
            log.debug("received ping on link");
            this.lastLinkProbeTimestamp = Instant.now();
            this.peerData.setLastAttempted(NTP.getTime());
            setLastPing(NTP.getTime());
        } else if (msgText.startsWith("close::")) {
            var targetPeerHash = subarray(message, 7, message.length);
            log.info("received close on link - peer dest hash: {}, target hash: {}",
                encodeHexString(destinationHash),
                encodeHexString(targetPeerHash));
            if (isInitiator) {
                disconnect("close link packet received");
                //makePeerUnavailable();
            }
            if (Arrays.equals(destinationHash, targetPeerHash)) {
                log.info("closing link: {}", peerLink.getDestination().getHexHash());
                shutdownChannel();
                //this.peerLink.teardown();
            }
            // Link status CLOSED means network ignores it until pruned
        } else if (msgText.startsWith("open::")) {
            var targetPeerHash = subarray(message, 7, message.length);
            log.info("received open on link - peer dest hash: {}, target hash: {}",
                encodeHexString(destinationHash),
                encodeHexString(targetPeerHash));
            if (Arrays.equals(destinationHash, targetPeerHash)) {
                log.info("re-opening existing link: {}", peerLink.getDestination().getHexHash());
                getOrInitPeerLink();
            }
            this.peerData.setLastConnected(NTP.getTime());
        }
    }

    /*
     * Callback from buffer when buffer has data available
     *
     * :param readyBytes: The number of bytes ready to read
     */
    public void peerBufferReady(Integer readyBytes) {
        // Capture peerBuffer locally — shutdownChannel() can null it from another thread
        // (listener threads are started asynchronously by handleMessage() → new Thread(...))
        var buf = this.peerBuffer;
        if (buf == null) return;
        // get the message data
        byte[] data;
        try {
            data = buf.read(readyBytes);
        } catch (IllegalArgumentException e) {
            // Library bug: RawChannelReader.read() computes a negative-length array slice
            // (seen as "N > 0" from Arrays.copyOfRange). Mark for removal so prunePeers()
            // removes this peer from getActiveImmutableLinkedPeers() — otherwise the
            // Synchronizer keeps trying to use the dead buffer and the chain falls behind.
            shutdownChannel();
            this.deleteMe = true; // safety net if pool is full/shutting down
            if (RNS.getInstance().markPeerForImmediateRemoval(this)) {
                log.warn("peerBufferReady: read error for {} ({}), marking for immediate removal",
                        remoteLogId(), e.getMessage());
            }
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(data);
        //log.info("data length: {}, MAGIC: {}, data: {}, ByteBuffer: {}", data.length, this.messageMagic, data, bb);
        //log.info("data length: {}, MAGIC: {}, ByteBuffer: {}", data.length, this.messageMagic, bb);
        //log.trace("peerBufferReady - data bytes: {}", data.length);
        this.lastAccessTimestamp = Instant.now();

        //if (ByteBuffer.wrap(data, 0, emptyBuffer.length).equals(ByteBuffer.wrap(emptyBuffer, 0, emptyBuffer.length))) {
        //    log.info("peerBufferReady - empty buffer detected (length: {})", data.length);
        //}
        //else {
        //if (Arrays.equals(SEQ_REQUEST_CONFIRM_ID, Arrays.copyOfRange(data, 0, SEQ_REQUEST_CONFIRM_ID.length))) {
        //    // a non-initiator peer requested to confirm sending of a packet
        //    var messageId = subarray(data, SEQ_REQUEST_CONFIRM_ID.length + 1, data.length);
        //    log.info("received request to confirm message id, id: {}", messageId);
        //    var confirmData = concatArrays(SEQ_RESPONSE_CONFIRM_ID, "::",data.getBytes(UTF_8), messageId.getBytes(UTF_8));
        //    this.peerBuffer.write(confirmData);
        //    this.peerBuffer.flush();
        //} else if (Arrays.equals(SEQ_RESPONSE_CONFIRM_ID, Arrays.copyOfRange(data, 0, SEQ_RESPONSE_CONFIRM_ID.lenth))) {
        //    // an initiator peer receiving the confirmation
        //    var messageId = subarray(data, SEQ_RESPONSE_CONFIRM_ID.length + 1, data.length);
        //    this.replyQueues.remove(messageId);
        //} else {
            try {
                //log.info("***> creating message from {} bytes", data.length);
                Message message = Message.fromByteBuffer(bb);
                if (message == null) {
                    log.trace("peerBufferReady - null message from {} bytes (unrecognised magic/type?), skipping", data.length);
                    return;
                }
                log.debug("*=> type {} message received ({} bytes, id: {})", message.getType(), data.length, message.getId());

                // Handle message based on type
                switch (message.getType()) {
                    // Do we need this ? (seems like a TCP scenario only thing)
                    // Does any ReticulumPeer ever require an other ReticulumPeer's peer list?
                    //case GET_PEERS:
                    //    //onGetPeersMessage(peer, message);
                    //    onGetReticulumPeersMessage(peer, message);
                    //    break;

                    case PING:
                        this.lastPingResponseReceived = Instant.now();
                        if (isFalse(this.isInitiator)) {
                            onPingMessage(this, message);
                        }
                        break;

                    case PONG:
                        log.trace("PONG received");
                        addToQueue(message);  // as response in blocking queue for ping getResponse
                        break;

                    // Do we need this ? (no need to relay peer list...)
                    case PEERS_V2:
                        RNS.getInstance().onPeersV2Message(this, message);
                        break;

                    case BLOCK_SUMMARIES:
                        // from Synchronizer
                        addToQueue(message);
                        break;

                    case BLOCK_SUMMARIES_V2:
                        // from Synchronizer
                        addToQueue(message);
                        break;

                    case SIGNATURES:
                        // from Synchronizer
                        addToQueue(message);
                        break;

                    case BLOCK:
                        // from Synchronizer
                        addToQueue(message);
                        break;

                    case BLOCK_V2:
                        // from Synchronizer
                        addToQueue(message);
                        break;

                    default:
                        log.trace("default - type {} message received ({} bytes)", message.getType(), data.length);
                        // Route through pendingMessages for async processing (avoids blocking the Reticulum callback thread)
                        addToQueue(message);
                        break;
                }
            } catch (MessageException e) {
                log.error("peerBufferReady - {} from peer {}", e, this);
                // don't take any chances:
                // can happen if link is closed by peer in which case we close this side of the link
                this.peerData.setLastMisbehaved(NTP.getTime());
                //if (nonNull(this.peerBuffer)) {
                //    shutdownChannel();
                //    this.peerBuffer.close();
                //}
                //peerLink.teardown();
            }
        //}
    }

    /**
     * we need to queue all incoming messages that follow request/response
     * with explicit handling of the response message.
     */
    public void addToQueue(Message message) {
        if (message.getType() == MessageType.UNSUPPORTED) {
            log.trace("discarding/skipping UNSUPPORTED message");
            return;
        }
        BlockingQueue<Message> queue = this.replyQueues.get(message.getId());
        if (queue != null) {
            // Adding message to queue will unblock thread waiting for response
            this.replyQueues.get(message.getId()).add(message);
            // Consumed elsewhere (getResponseWithTimeout)
            log.info("addToQueue - queue size: {}, message type: {} (id: {})", queue.size(), message.getType(), message.getId());
        }
        else if (!this.pendingMessages.offer(message)) {
            log.info("[{}] Busy, no room to queue message from peer {} - discarding",
                    this.peerLink, this);
        }
    }

    /**
     * Send a packet to remote with the message format "close::<our_destination_hash>"
     * This method is only useful for non-initiator links to close the remote initiator.
     *
     * @param link
     */
    public void sendCloseToRemote(Link link) {
        var baseDestination = RNS.getInstance().getBaseDestination();
        if (nonNull(link) & (isFalse(link.isInitiator()))) {
            // Note: if part of link we need to get the baseDesitination hash
            //var data = concatArrays("close::".getBytes(UTF_8),link.getDestination().getHash());
            var data = concatArrays("close::".getBytes(UTF_8), baseDestination.getHash());
            Packet closePacket = new Packet(link, data);
            var packetReceipt = closePacket.send();
            // send() returns null when no interface can process the packet — common during
            // shutdown, when interfaces are already being torn down. Guard against it so the
            // shutdown sequence isn't aborted by an NPE (which left non-daemon threads alive
            // and prevented a clean stop in Qortal test-14).
            if (nonNull(packetReceipt)) {
                packetReceipt.setDeliveryCallback(this::closePacketDelivered);
                packetReceipt.setTimeout(1000L);
                packetReceipt.setTimeoutCallback(this::packetTimedOut);
            } else {
                log.debug("close packet could not be sent (no interface available) for {}", this);
            }
        } else {
            log.debug("can't send to null link");
        }
    }

    /** PacketReceipt callbacks */
    public void closePacketDelivered(PacketReceipt receipt) {
        var rttString = new String("");
        if (receipt.getStatus() == PacketReceiptStatus.DELIVERED) {
            var rtt = receipt.getRtt();    // rtt (Java) is in milliseconds
            this.lastPacketRtt = rtt;
            if (rtt >= 1000) {
                rtt = Math.round(rtt / 1000);
                rttString = String.format("%d seconds", rtt);
            } else {
                rttString = String.format("%d miliseconds", rtt);
            }
            log.info("Shutdown packet confirmation received from {}, round-trip time is {}",
                    encodeHexString(receipt.getDestination().getHash()), rttString);
        }
    }

    public void packetDelivered(PacketReceipt receipt) {
        var rttString = "";
        //log.info("packet delivered callback, receipt: {}", receipt);
        if (receipt.getStatus() == PacketReceiptStatus.DELIVERED) {
            var rtt = receipt.getRtt();    // rtt (Java) is in milliseconds
            this.lastPacketRtt = rtt;
            //log.info("qqp - packetDelivered - rtt: {}", rtt);
            if (rtt >= 1000) {
                rtt = Math.round((float) rtt / 1000);
                rttString = String.format("%d seconds", rtt);
            } else {
                rttString = String.format("%d milliseconds", rtt);
            }
            if (getIsInitiator()) {
                // reporting round trip time in one direction is enough
                log.info("Valid reply received from {}, round-trip time is {}",
                        encodeHexString(receipt.getDestination().getHash()), rttString);
            }
            this.lastAccessTimestamp = Instant.now();
        }
    }

    public void packetTimedOut(PacketReceipt receipt) {
        //log.info("packet timed out, receipt status: {}", receipt.getStatus());
        if (receipt.getStatus() == PacketReceiptStatus.FAILED) {
            log.info("packet timed out, receipt status: {}, isInitiator: {}", PacketReceiptStatus.FAILED, isInitiator);
            this.peerTimedOut = true;
            //shutdownChannel();
            //this.peerLink.teardown();
            //if (isInitiator) {
            //    this.peerLink.teardown();
            //}
        }
    }

    /** Link Request callbacks */ 
    public void linkRequestResponseReceived(RequestReceipt rr) {
        log.info("Response received");
    }

    public void linkRequestResponseProgress(RequestReceipt rr) {
        this.requestResponseProgress = rr.getProgress();
        log.debug("Response progress set");
    }

    public void linkRequestFailed(RequestReceipt rr) {
        log.error("Request failed");
    }

    /** Link Resource callbacks */
    // Resource: allow arbitrary amounts of data to be passed over a link with
    // sequencing, compression, coordination and checksumming handled automatically
    //public Boolean linkResourceAdvertised(Resource resource) {
    //    log.debug("Resource advertised");
    //}
    public void linkResourceTransferStarted(Resource resource) {
        log.debug("Resource transfer started");
    }
    public void linkResourceTransferConcluded(Resource resource) {
        log.debug("Resource transfer complete");
    }

    /** Utility methods */
    public void pingRemote() {
        var link = this.peerLink;
        if (nonNull(link)) {
            if (peerLink.getStatus() == ACTIVE) {
                log.debug("pinging remote (direct, 1 packet): {}", encodeHexString(link.getLinkId()));
                var data = "ping".getBytes(UTF_8);
                link.setPacketCallback(this::linkPacketReceived);
                Packet pingPacket = new Packet(link, data);
                //var tmout = pingPacket.getReceipt().getTimeout();
                PacketReceipt packetReceipt = pingPacket.send();
                packetReceipt.setDeliveryCallback(this::packetDelivered);
                // Note: don't setTimeout, we want it to timeout with FAIL if not deliverable
                packetReceipt.setTimeout(3000L);
                packetReceipt.setTimeoutCallback(this::packetTimedOut);
            } else {
                log.info("can't send ping to a peer {} with (link) status: {}",
                    encodeHexString(peerLink.getDestination().getHash()), peerLink.getStatus());
            }
        }
    }

    //public void shutdownLink(Link link) {
    //    var data = "shutdown".getBytes(UTF_8);
    //    Packet shutdownPacket = new Packet(link, data);
    //    PacketReceipt packetReceipt = shutdownPacket.send();
    //    packetReceipt.setTimeout(2000L);
    //    packetReceipt.setTimeoutCallback(this::packetTimedOut);
    //    packetReceipt.setDeliveryCallback(this::shutdownPacketDelivered);
    //}

    /** qortal networking specific (Tasks) */

    // Send Ping Message to peer through buffer.
    // Note: This keeps Buffer,Channel and Link alive and from timing out.
    private void onPingMessage(ReticulumPeer peer, Message message) {
        PingMessage pingMessage = (PingMessage) message;
        var buf = this.peerBuffer;
        if (buf == null) return;
        try {
            PongMessage pongMessage = new PongMessage();
            pongMessage.setId(message.getId());  // use the ping message id (for ping getResponse)
            buf.write(pongMessage.toBytes());
            buf.flush();
            this.lastAccessTimestamp = Instant.now();
            setLastPingSent(Instant.now().toEpochMilli());
        } catch (MessageException e) {
            //log.error("{} from peer {}", e.getMessage(), this);
            log.error("{} from peer {}", e, this);
        }
    }

    //public void onPingMessage(Peer peer, Message message) {
    //    onPingMessage(this, message);
    //}

    /**
     * Send message to peer and await response, using default RESPONSE_TIMEOUT.
     * <p>
     * Message is assigned a random ID and sent.
     * Responses are handled by registered callbacks.
     * <p>
     * Note: The method is called "get..." to match the original method name
     *
     * @param message message to send
     * @return <code>Message</code> if valid response received; <code>null</code> if not or error/exception occurs
     * @throws InterruptedException if interrupted while waiting
     */
    public Message getResponse(Message message) throws InterruptedException {
        //log.info("ReticulumPingTask action - pinging peer {}", encodeHexString(getDestinationHash()));
        Message response = null;
        try {
            response = getResponseWithTimeout(message, RESPONSE_TIMEOUT);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }
        return response;
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
        //log.info("getResponse - before send {} message, random id is {}", message.getType(), id);

        // Try to send message
        if (!this.sendMessageWithTimeout(message, timeout)) {
            this.replyQueues.remove(id);
            return null;
        }
        //log.info("getResponse - after send");

        try {
            return blockingQueue.poll(timeout, TimeUnit.MILLISECONDS);
        } finally {
            this.replyQueues.remove(id);
            //log.info("getResponse - regular - id removed from replyQueues");
        }
    }

    /**
     * Attempt to send Message to peer using the buffer and a custom timeout.
     *
     * @param message message to be sent
     * @return <code>true</code> if message successfully sent; <code>false</code> otherwise
     */
    public boolean sendMessageWithTimeout(Message message, int timeout) {
        try {
            if (nonNull(this.peerLink)) {
                if (this.peerLink.getStatus() != ACTIVE) {
                    log.debug("sendMessageWithTimeout - skipping: link not ready (status: {})", this.peerLink.getStatus());
                    return false;
                }
            } else {
                log.debug("sendMessageWithTimeout - skipping: peerLink is null)");
                return false;
            }
            // TODO: Review and rewrite using sendQueue (see IPPeer)
            // send the message
            log.trace("Sending {} message with ID {} to peer {}", message.getType().name(), message.getId(), this);
            var buf = getOrInitPeerBuffer();
            if (buf == null) {
                log.debug("sendMessageWithTimeout - buffer unavailable for {}", this);
                return false;
            }
            buf.write(message.toBytes());
            buf.flush();
            //// send a message to confirm receipt over the buffer
            //var messageId = message.getId();
            //var confirmData = concatArrays(SEQ_REQUEST_CONFIRM_ID,"::".getBytes(UTF_8), messageId.getBytes(UTF_8));
            //this.peerBuffer.write(confirmData);
            //this.peerBuffer.flush();
            return true;
        //} catch (InterruptedException e) {
        //    // Send failure
        //    return false;
        } catch (IllegalStateException e) {
            this.setDeleteMe(true);
            if (RNS.getInstance().markPeerForImmediateRemoval(this)) {
                log.warn("sendMessage (queued): buffer closed for {} (link tearing down), marking for removal",
                        remoteLogId());
            }
            return false;
        } catch (MessageException e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    public int getSendQueueSize() {
        return 0; // Reticulum uses Buffer for I/O, not a bounded queue
    }

    public int getSendQueueCapacity() {
        return Integer.MAX_VALUE; // Buffer has no fixed capacity limit
    }

    @Override
    public int getPeerType() { return this.peerType; }

    @Override
    public void setPeerType(int peerType) { this.peerType = peerType; }

    //public boolean sendMessageWithTimeoutNow(Message message, int timeout) {
    //    if (nonNull(this.peerLink)) {
    //        if (this.peerLink.getStatus() != ACTIVE) {
    //            log.debug("sendMessageWithTimeoutNow - skipping: link not ready (status: {})", this.peerLink.getStatus());
    //            return false;
    //        }
    //    } else {
    //        log.debug("sendMessageWithTimeoutNow - skipping: peerLink is null)");
    //        return false;
    //    }
    //    try {
    //        // Queue message, to be picked up by ChannelWriteTask and then peer.writeChannel()
    //        log.debug("Queuing {} message with ID {} to peer {}",
    //                message.getType().name(), message.getId(), this);
    //
    //        // Check message properly constructed
    //        message.checkValidOutgoing();
    //
    //        // Possible race condition:
    //        // We set OP_WRITE, EPC creates ChannelWriteTask which calls Peer.writeChannel, writeChannel's poll() finds no message to send
    //        // Avoided by poll-with-timeout in writeChannel() above.
    //        return this.sendQueue.tryTransfer(message, timeout, TimeUnit.MILLISECONDS);
    //    } catch (InterruptedException e) {
    //        // Send failure
    //        return false;
    //    } catch (MessageException e) {
    //        log.error(e.getMessage(), e);
    //        return false;
    //    }
    //}

    public Task getMessageTask(int network) {
        /*
         * If our peerLink is not in ACTIVE node and there is a message yet to be
         * processed then don't produce another message task.
         * This allows us to process remaining messages sequentially.
         */
        if (isNull(this.peerLink)) {
            return null;
        }

        final Message nextMessage = this.pendingMessages.poll();

        if (nextMessage == null) {
            return null;
        }

        log.trace("[{}] Produced {} message task from peer {}", this.peerConnectionId,
                nextMessage.getType().name(), this);

        // Return a task to process message in queue
        //return new ReticulumMessageTask(this, nextMessage);
        return new MessageTask(this, nextMessage, network);
    }

    /**
     * Send a Qortal message using a Reticulum Buffer
     * 
     * @param message message to be sent
     * @return <code>true</code> if message successfully sent; <code>false</code> otherwise
     */
    //@Synchronized
    public boolean sendMessage(Message message) {
        try {
            if (nonNull(this.peerLink)) {
                if (this.peerLink.getStatus() != ACTIVE) {
                    log.debug("sendMessage - skipping: link not ready (status: {})", this.peerLink.getStatus());
                    if (this.peerLink.getStatus() == CLOSED && claimClosedLinkHandling()) {
                        // prevent peer from being chosen for sending again. Only the first sender
                        // to notice does this — the rest just get false back.
                        disconnect("sendMessage - link closed");
                    }
                    return false;
                } else {
                    log.trace("Sending {} message with ID {} to peer {}",
                            message.getType().name(), message.getId(), encodeHexString(getDestinationHash()));
                    var peerBuffer = getOrInitPeerBuffer();
                    if (peerBuffer == null) {
                        log.trace("sendMessage - buffer not available for {}", this);
                        return false;
                    }
                    peerBuffer.write(message.toBytes());
                    peerBuffer.flush();
                    //return true;  // done at end of method
                }
            } else {
                log.debug("sendMessage - skipping: peerLink is null)");
                return false;
            }
        } catch (IllegalStateException e) {
            // Buffer is closed — the link is tearing down. Mark for removal so this peer
            // disappears from getActiveDataPeers() / getActiveImmutableLinkedPeers() on the
            // next loop iteration without waiting for prunePeers().
            // deleteMe is idempotent and must be set by every caller: it is what takes this peer
            // out of the active lists on the next loop pass. The teardown itself is claimed once,
            // so N concurrent senders queue one task and write one warning, not N.
            this.setDeleteMe(true);
            if (RNS.getInstance().markPeerForImmediateRemoval(this)) {
                log.warn("sendMessage: buffer closed for {} (link tearing down), marking for removal",
                        remoteLogId());
            }
            return false;
        } catch (MessageException e) {
            log.error(e.getMessage(), e);
            return false;
        }
        return true;
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

    @Override
    public Handshake getHandshakeStatus() {
        // Reticulum link establishment IS the handshake. Once the buffer is ready
        // and the peer is in Network's peer lists, all messages should be routed
        // as post-handshake (i.e. to Controller.onNetworkMessage).
        return Handshake.COMPLETED;
    }

    public void startPings() {
        Long ntpTime = NTP.getTime();
        this.lastPingSent = (ntpTime != null) ? ntpTime : System.currentTimeMillis();
        log.info("[{}] Enabling pings for peer {}, lastPingSent: {}",
                peerLink.getDestination().getHexHash(), this.toString(), this.lastPingSent);
    }

    public Task getPingTask(Long now) {
        // App-level Reticulum pings are DISABLED (test-28). Liveness now comes from the Reticulum
        // Link's native keepalive via the (library-fixed) lastInbound timestamp, evaluated by
        // RNS.isUnreachable — a lightweight link-level mechanism. This replaces the old synchronous
        // Channel PING/PONG (a blocking getResponse per initiator peer every 55s) which added
        // Channel load, tied up Network-Worker threads, and could itself trigger the Channel
        // 'retry count exceeded' teardowns we're trying to reduce. A wedged Channel still closes
        // the Link, so isUnreachable's CLOSED check covers the Channel-death case.
        return null;
    }

    //// low-level Link (packet) ping
    //protected Link getPingLinks(Long now) {
    //    if (now == null || this.lastPingSent == null) {
    //        return null;
    //    }
    //
    //    // ping only possible over ACTIVE link
    //    if (nonNull(this.peerLink)) {
    //        if (this.peerLink.getStatus() != ACTIVE) {
    //            return null;
    //        }
    //    } else {
    //        return null;
    //    }
    //
    //    if (now < this.lastPingSent + LINK_PING_INTERVAL) {
    //        return null;
    //    }
    //
    //    this.lastPingSent = now;
    //
    //    return this.peerLink;
    //
    //}

    // Peer methods reticulum implementations
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

    // Details used by API
    public long getConnectionEstablishedTime() {
        return linkEstablishedTime;
    }

    public long getConnectionAge() {
        if (linkEstablishedTime > 0L) {
            return System.currentTimeMillis() - linkEstablishedTime;
        }
        return linkEstablishedTime;
    }

    public long getMaxConnectionAge() {
        // We never want to get disconnected automatically
        return System.currentTimeMillis() - linkEstablishedTime + 1000L;
    }

    /**
     * legacy Peer compatibility
     */
    public Long getPeersVersion() {
        // Must be >= minPeerVersion (default "6.1.0" = 0x600010000L) so the
        // Synchronizer's hasOldVersion predicate does not filter out Reticulum peers.
        return 0x600010000L; // 6.1.0
    }

    public String getPeersVersionString() {
        // Real version comes from the peer's announce appData (QAN1), set via setPeersVersionString()
        // from RNS.getNewPeer()/onIncomingPeerIdentified(). Falls back to the historical floor until
        // that peer's announce has been seen. Display-only; getPeersVersion() (the numeric min-
        // version gate) is intentionally left at the floor.
        return this.peersVersionString != null ? this.peersVersionString : "6.1.0";
    }

    public void setPeersVersion(String versionString, long version) {
        //synchronized (this.peerInfoLock) {
            this.peersVersionString = versionString;
            this.peersVersion = version;
        //}
    }
    public PeerCapabilities getPeersCapabilities() {
        return this.peerCapabilities;
    }

    public void setPeersCapabilities(PeerCapabilities capabilities) {
        synchronized (this.peerInfoLock) {
            this.peerCapabilities = capabilities;
        }
    }

    public String getPeersNodeId() {
        //this.peersNodeId = RNS.getInstance().getServerIdentity().getHexHash();
        if (nonNull(this.peerLink)) {
            this.peersNodeId = this.peerLink.getDestination().getHexHash();
        }
        return this.peersNodeId;
    }

    public boolean isStopping() {
        return this.isStopping;
    }

    public UUID getPeerConnectionId() {
        return this.peerConnectionId;
    }

    public Long getPeersConnectionTimestamp() {
        //synchronized (this.peerInfoLock) {
            return this.peersConnectionTimestamp;
        //}
    }
    
    public void setPeersConnectionTimestamp(Long peersConnectionTimestamp) {
        //synchronized (this.peerInfoLock) {
            this.peersConnectionTimestamp = peersConnectionTimestamp;
        //}
    }

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

    public void setLastPing(long lastPing) {
        //synchronized (this.peerInfoLock) {
            this.lastPing = lastPing;
        //}
    }

    @Override
    public void setIsDataPeer(boolean b) {
        setPeerAspect(PeerAspect.BASE);
        if (isTrue(b)) {
            setPeerAspect(PeerAspect.DATA);
        }
    }

    public boolean isDataPeer () {
        var result = false;
        if (this.getPeerAspect() == RNSCommon.PeerAspect.DATA) {
            result = true;
        }
        return result;
    }

    public void setIsPeerAvailable(boolean b) {
        this.isPeerAvailable = b;
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
     * @author siddi
     */
    public boolean sendPreSerializedMessage(int messageId, MessageType messageType, byte[] serializedBytes, int timeout) throws IOException {
        // TODO: implement for Reticulum (compare IPPeer implementation)
        //...
        return false;
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
    // end legacy Peer compatibility

}
