package org.qortal.network;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
//import java.io.IOException;
import java.time.Instant;
import java.util.*;

//import io.reticulum.Reticulum;
//import org.qortal.network.RNSNetwork;
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
import static io.reticulum.utils.IdentityUtils.concatArrays;

import org.qortal.controller.Controller;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.CommonBlockData;
import org.qortal.data.network.RNSPeerData;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageType;
import org.qortal.network.message.PingMessage;
import org.qortal.network.message.*;
import org.qortal.network.message.MessageException;
import org.qortal.network.task.RNSMessageTask;
import org.qortal.network.task.RNSPingTask;
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
public class RNSPeer {

    static final String APP_NAME = Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_APP_NAME: RNSCommon.MAINNET_APP_NAME;
    //static final String defaultConfigPath = new String(".reticulum");
    //static final String defaultConfigPath = RNSCommon.defaultRNSConfigPath;

    private byte[] destinationHash;   // remote destination hash
    Destination peerDestination;      // OUT destination created for this
    private Identity serverIdentity;
    @Setter(AccessLevel.PACKAGE) private Instant creationTimestamp;
    @Setter(AccessLevel.PACKAGE) private Instant lastAccessTimestamp;
    @Setter(AccessLevel.PACKAGE) private Instant lastLinkProbeTimestamp;
    Link peerLink;
    byte[] peerLinkHash;
    BufferedRWPair peerBuffer;
    int receiveStreamId = 0;
    int sendStreamId = 0;
    private Boolean isInitiator;
    private Boolean deleteMe = false;
    //private Boolean isVacant = true;
    private Long lastPacketRtt = null;
    //private byte[] emptyBuffer = {0,0,0,0,0};

    private Double requestResponseProgress;
    @Setter(AccessLevel.PACKAGE) private Boolean peerTimedOut = false;

    // for qortal networking
    private static final int RESPONSE_TIMEOUT = 3000; // [ms]
    private static final int PING_INTERVAL = 55_000; // [ms]
    private static final long LINK_PING_INTERVAL = 55 * 1000L; // ms
    private byte[] messageMagic;  // set in message creating classes
    private Long lastPing = null;      // last (packet) ping roundtrip time [ms]
    private Long lastPingSent = null;  // time last (packet) ping was sent, or null if not started.
    @Setter(AccessLevel.PACKAGE) private Instant lastPingResponseReceived = null; // time last (packet) ping succeeded
    private Map<Integer, BlockingQueue<Message>> replyQueues;
    private LinkedBlockingQueue<Message> pendingMessages;
    private boolean syncInProgress = false;
    private RNSPeerData peerData = null;
    private long linkEstablishedTime = -1L; // equivalent of (tcpip) Peer 'handshakeComplete'
    // Versioning
    public static final Pattern VERSION_PATTERN = Pattern.compile(Controller.VERSION_PREFIX
            + "(\\d{1,3})\\.(\\d{1,5})\\.(\\d{1,5})");
    /* Pending signature requests */
    private List<byte[]> pendingSignatureRequests = Collections.synchronizedList(new ArrayList<>());
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

    private final Map<MessageType, RNSPeer.MessageStats> receivedMessageStats = new ConcurrentHashMap<>();
    private final Map<MessageType, RNSPeer.MessageStats> sentMessageStats = new ConcurrentHashMap<>();

    /**
     * Constructor for initiator peers
     */
    public RNSPeer(byte[] dhash) {
        this.destinationHash = dhash;
        this.serverIdentity = recall(dhash);
        initPeerLink();
        //setCreationTimestamp(System.currentTimeMillis());
        this.creationTimestamp = Instant.now();
        //this.isVacant = true;
        this.replyQueues = new ConcurrentHashMap<>();
        this.pendingMessages = new LinkedBlockingQueue<>();
        this.peerData = new RNSPeerData(dhash);
    }

    /**
     * Constructor for non-initiator peers
     */
    public RNSPeer(Link link) {
        this.peerLink = link;
        //this.peerLinkId = link.getLinkId();
        this.peerDestination = link.getDestination();
        this.destinationHash = link.getDestination().getHash();
        this.serverIdentity = link.getRemoteIdentity();

        this.creationTimestamp = Instant.now();
        this.lastAccessTimestamp = Instant.now();
        this.lastLinkProbeTimestamp = null;
        this.isInitiator = false;
        //this.isVacant = false;

        //this.peerLink.setLinkEstablishedCallback(this::linkEstablished);
        //this.peerLink.setLinkClosedCallback(this::linkClosed);
        //this.peerLink.setPacketCallback(this::linkPacketReceived);
        this.peerData = new RNSPeerData(this.destinationHash);
    }
    public void initPeerLink() {
        peerDestination = new Destination(
            this.serverIdentity,
            Direction.OUT, 
            DestinationType.SINGLE,
            APP_NAME,
            "core"
        );
        peerDestination.setProofStrategy(ProofStrategy.PROVE_ALL);

        this.creationTimestamp = Instant.now();
        this.lastAccessTimestamp = Instant.now();
        this.lastLinkProbeTimestamp = null;
        this.isInitiator = true;

        this.peerLink = new Link(peerDestination);

        this.peerLink.setLinkEstablishedCallback(this::linkEstablished);
        this.peerLink.setLinkClosedCallback(this::linkClosed);
        this.peerLink.setPacketCallback(this::linkPacketReceived);
    }

    @Override
    public String toString() {
        // for messages we want an address-like string representation
        if (nonNull(this.peerLink)) {
            return this.getPeerLink().toString();
        } else {
            return encodeHexString(this.getDestinationHash());
        }
    }

    public BufferedRWPair getOrInitPeerBuffer() {
        var channel = this.peerLink.getChannel();
        if (nonNull(this.peerBuffer)) {
            //log.info("peerBuffer exists: {}, link status: {}", this.peerBuffer, this.peerLink.getStatus());
            try {
                log.trace("peerBuffer exists: {}, link status: {}", this.peerBuffer, this.peerLink.getStatus());
            } catch (IllegalStateException e) {
                // Exception thrown by Reticulum if the buffer is unusable (Channel, Link, etc)
                // This is a chance to correct links status when doing a RNSPingTask
                log.warn("can't establish Channel/Buffer (remote peer down?), closing link: {}");
                this.peerBuffer.close();
                this.peerLink.teardown();
                this.peerLink = null;
                //log.error("(handled) IllegalStateException - can't establish Channel/Buffer: {}", e);
            }
        }
        else {
            log.info("creating buffer - peerLink status: {}, channel: {}", this.peerLink.getStatus(), channel);
            this.peerBuffer = Buffer.createBidirectionalBuffer(receiveStreamId, sendStreamId, channel, this::peerBufferReady);
        }
        return getPeerBuffer();
    }

    public Link getOrInitPeerLink() {
        if (this.peerLink.getStatus() == ACTIVE) {
            lastAccessTimestamp = Instant.now();
            //return this.peerLink;
        } else {
            initPeerLink();
        }
        return this.peerLink;
    }

    public void shutdown() {
        if (nonNull(this.peerLink)) {
            log.info("shutdown - peerLink: {}, status: {}", peerLink, peerLink.getStatus());
            if (peerLink.getStatus() == ACTIVE) {
                if (nonNull(this.peerBuffer)) {
                    this.peerBuffer.close();
                    this.peerBuffer = null;
                }
                this.peerLink.teardown();
            } else {
                log.info("shutdown - status (non-ACTIVE): {}", peerLink.getStatus());
            }
            this.peerLink = null;
        }
        this.deleteMe = true;
    }

    public Channel getChannel() {
        if (isNull(getPeerLink())) {
            log.warn("link is null.");
            return null;
        }
        setLastAccessTimestamp(Instant.now());
        return getPeerLink().getChannel();
    }

    public Boolean getIsInitiator() {
        return this.isInitiator;
    }

    /** Link callbacks */
    public void linkEstablished(Link link) {
        this.linkEstablishedTime = System.currentTimeMillis();
        link.setLinkClosedCallback(this::linkClosed);
        log.info("peerLink {} established (link: {}) with peer: hash - {}, link destination hash: {}", 
            encodeHexString(peerLink.getLinkId()), encodeHexString(link.getLinkId()), encodeHexString(destinationHash),
            encodeHexString(link.getDestination().getHash()));
        if (isInitiator) {
            startPings();
        }
    }
    
    public void linkClosed(Link link) {
        if (link.getTeardownReason() == TIMEOUT) {
            log.info("The link timed out");
            this.peerTimedOut = true;
            this.peerBuffer = null;
        } else if (link.getTeardownReason() == INITIATOR_CLOSED) {
            log.info("Link closed callback: The initiator closed the link");
            log.info("peerLink {} closed (link: {}), link destination hash: {}",
                encodeHexString(peerLink.getLinkId()), encodeHexString(link.getLinkId()), encodeHexString(link.getDestination().getHash()));
            this.peerBuffer = null;
        } else if (link.getTeardownReason() == DESTINATION_CLOSED) {
            log.info("Link closed callback: The link was closed by the peer, removing peer");
            log.info("peerLink {} closed (link: {}), link destination hash: {}",
                encodeHexString(peerLink.getLinkId()), encodeHexString(link.getLinkId()), encodeHexString(link.getDestination().getHash()));
            this.peerBuffer = null;
        } else {
            log.info("Link closed callback");
        }
    }
    
    public void linkPacketReceived(byte[] message, Packet packet) {
        var msgText = new String(message, StandardCharsets.UTF_8);
        if (msgText.equals("ping")) {
            log.info("received ping on link");
            this.lastLinkProbeTimestamp = Instant.now();
        } else if (msgText.startsWith("close::")) {
            var targetPeerHash = subarray(message, 7, message.length);
            log.info("peer dest hash: {}, target hash: {}",
                encodeHexString(destinationHash),
                encodeHexString(targetPeerHash));
            if (Arrays.equals(destinationHash, targetPeerHash)) {
                log.info("closing link: {}", peerLink.getDestination().getHexHash());
                if (nonNull(this.peerBuffer)) {
                    this.peerBuffer.close();
                    this.peerBuffer = null;
                }
                this.peerLink.teardown();
            }
        } else if (msgText.startsWith("open::")) {
            var targetPeerHash = subarray(message, 7, message.length);
            log.info("peer dest hash: {}, target hash: {}",
                encodeHexString(destinationHash),
                encodeHexString(targetPeerHash));
            if (Arrays.equals(destinationHash, targetPeerHash)) {
                log.info("closing link: {}", peerLink.getDestination().getHexHash());
                getOrInitPeerLink();
            }
        }
    }

    /*
     * Callback from buffer when buffer has data available
     *
     * :param readyBytes: The number of bytes ready to read
     */
    public void peerBufferReady(Integer readyBytes) {
        // get the message data
        byte[] data = this.peerBuffer.read(readyBytes);
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
                //log.info("*=> type {} message received ({} bytes): {}", message.getType(), data.length, message);
                log.info("*=> type {} message received ({} bytes, id: {})", message.getType(), data.length, message.getId());

                // Handle message based on type
                switch (message.getType()) {
                    // Do we need this ? (seems like a TCP scenario only thing)
                    // Does any RNSPeer ever require an other RNSPeer's peer list?
                    //case GET_PEERS:
                    //    //onGetPeersMessage(peer, message);
                    //    onGetRNSPeersMessage(peer, message);
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
                    //case PEERS_V2:
                    //    onPeersV2Message(peer, message);
                    //    break;

                    case BLOCK_SUMMARIES:
                        // from Synchronizer
                        addToQueue(message);

                    case BLOCK_SUMMARIES_V2:
                        // from Synchronizer
                        addToQueue(message);

                    case SIGNATURES:
                        // from Synchronizer
                        addToQueue(message);

                    case BLOCK:
                        // from Synchronizer
                        addToQueue(message);

                    case BLOCK_V2:
                        // from Synchronizer
                        addToQueue(message);

                    default:
                        log.info("default - type {} message received ({} bytes)", message.getType(), data.length);
                        // Bump up to controller for possible action
                        addToQueue(message);
                        Controller.getInstance().onRNSNetworkMessage(this, message);
                        break;
                }
            } catch (MessageException e) {
                //log.error("{} from peer {}", e.getMessage(), this);
                log.error("{} from peer {}, closing link", e, this);
                //log.info("{} from peer {}", e, this);
                // don't take any chances:
                // can happen if link is closed by peer in which case we close this side of the link
                this.peerData.setLastMisbehaved(NTP.getTime());
                shutdown();
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
     * Set a packet to remote with the message format "close::<our_destination_hash>"
     * This method is only useful for non-initiator links to close the remote initiator.
     *
     * @param link
     */
    public void sendCloseToRemote(Link link) {
        var baseDestination = RNSNetwork.getInstance().getBaseDestination();
        if (nonNull(link) & (isFalse(link.isInitiator()))) {
            // Note: if part of link we need to get the baseDesitination hash
            //var data = concatArrays("close::".getBytes(UTF_8),link.getDestination().getHash());
            var data = concatArrays("close::".getBytes(UTF_8), baseDestination.getHash());
            Packet closePacket = new Packet(link, data);
            var packetReceipt = closePacket.send();
            packetReceipt.setDeliveryCallback(this::closePacketDelivered);
            packetReceipt.setTimeout(1000L);
            packetReceipt.setTimeoutCallback(this::packetTimedOut);
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
            log.info("packet timed out, receipt status: {}", PacketReceiptStatus.FAILED);
            this.peerTimedOut = true;
            this.peerLink.teardown();
        }
        //this.peerTimedOut = true;
        //this.peerLink.teardown();
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
                log.info("pinging remote (direct, 1 packet): {}", encodeHexString(link.getLinkId()));
                var data = "ping".getBytes(UTF_8);
                link.setPacketCallback(this::linkPacketReceived);
                Packet pingPacket = new Packet(link, data);
                PacketReceipt packetReceipt = pingPacket.send();
                packetReceipt.setDeliveryCallback(this::packetDelivered);
                // Note: don't setTimeout, we want it to timeout with FAIL if not deliverable
                //packetReceipt.setTimeout(5000L);
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

    private void onPingMessage(RNSPeer peer, Message message) {
        PingMessage pingMessage = (PingMessage) message;
    
        try {
            PongMessage pongMessage = new PongMessage();
            pongMessage.setId(message.getId());  // use the ping message id (for ping getResponse)
            this.peerBuffer.write(pongMessage.toBytes());
            this.peerBuffer.flush();
            this.lastAccessTimestamp = Instant.now();
        } catch (MessageException e) {
            //log.error("{} from peer {}", e.getMessage(), this);
            log.error("{} from peer {}", e, this);
        }
    }

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
        //log.info("RNSPingTask action - pinging peer {}", encodeHexString(getDestinationHash()));
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
            // send the message
            log.trace("Sending {} message with ID {} to peer {}", message.getType().name(), message.getId(), this);
            var peerBuffer = getOrInitPeerBuffer();
            this.peerBuffer.write(message.toBytes());
            this.peerBuffer.flush();
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
            //log.warn("Can't write to buffer (remote buffer down?)");
            this.peerLink.teardown();
            this.peerBuffer = null;
            log.error("IllegalStateException - can't write to buffer: {}", e);
            return false;
        } catch (MessageException e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    protected Task getMessageTask() {
        /*
         * If our peerLink is not in ACTIVE node and there is a message yet to be
         * processed then don't produce another message task.
         * This allows us to process remaining messages sequentially.
         */
        if (this.peerLink.getStatus() != ACTIVE) {
            return null;
        }

        final Message nextMessage = this.pendingMessages.poll();

        if (nextMessage == null) {
            return null;
        }

        // Return a task to process message in queue
        return new RNSMessageTask(this, nextMessage);
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
            log.trace("Sending {} message with ID {} to peer {}", message.getType().name(), message.getId(), this.toString());
            //log.info("Sending {} message with ID {} to peer {}", message.getType().name(), message.getId(), this.toString());
            var peerBuffer = getOrInitPeerBuffer();
            peerBuffer.write(message.toBytes());
            peerBuffer.flush();
            return true;
        } catch (IllegalStateException e) {
            this.peerLink.teardown();
            this.peerBuffer = null;
            log.error("IllegalStateException - can't write to buffer: {}", e);
            return false;
        } catch (MessageException e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    protected void startPings() {
        log.trace("[{}] Enabling pings for peer {}",
                peerLink.getDestination().getHexHash(), this.toString());
        this.lastPingSent = NTP.getTime();
    }

    protected Task getPingTask(Long now) {
        // Pings not enabled yet?
        if (now == null || this.lastPingSent == null) {
            return null;
        }

        // ping only possible over ACTIVE Link
        if (nonNull(this.peerLink)) {
            if (this.peerLink.getStatus() != ACTIVE) {
                return null;
            }
        } else {
            return null;
        }

        // Time to send another ping?
        if (now < this.lastPingSent + PING_INTERVAL) {
            return null; // Not yet
        }

        // Not strictly true, but prevents this peer from being immediately chosen again
        this.lastPingSent = now;

        return new RNSPingTask(this, now);
    }

    // low-level Link (packet) ping
    protected Link getPingLinks(Long now) {
        if (now == null || this.lastPingSent == null) {
            return null;
        }

        // ping only possible over ACTIVE link
        if (nonNull(this.peerLink)) {
            if (this.peerLink.getStatus() != ACTIVE) {
                return null;
            }
        } else {
            return null;
        }

        if (now < this.lastPingSent + LINK_PING_INTERVAL) {
            return null;
        }

        this.lastPingSent = now;

        return this.peerLink;

    }

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
}
