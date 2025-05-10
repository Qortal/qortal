package org.qortal.network;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.ProofStrategy;
import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
//import io.reticulum.constant.LinkConstant;
//import static io.reticulum.constant.ReticulumConstant.MTU;
import io.reticulum.buffer.Buffer;
import io.reticulum.buffer.BufferedRWPair;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketReceipt;
import io.reticulum.packet.PacketReceiptStatus;
import io.reticulum.transport.AnnounceHandler;
//import static io.reticulum.link.TeardownSession.DESTINATION_CLOSED;
//import static io.reticulum.link.TeardownSession.INITIATOR_CLOSED;
import static io.reticulum.link.TeardownSession.TIMEOUT;
import static io.reticulum.link.LinkStatus.ACTIVE;
import static io.reticulum.link.LinkStatus.STALE;
import static io.reticulum.link.LinkStatus.CLOSED;
import static io.reticulum.link.LinkStatus.PENDING;
import static io.reticulum.link.LinkStatus.HANDSHAKE;
//import static io.reticulum.packet.PacketContextType.LINKCLOSE;
//import static io.reticulum.identity.IdentityKnownDestination.recall;
import static io.reticulum.utils.IdentityUtils.concatArrays;
//import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.constant.ReticulumConstant.CONFIG_FILE_NAME;
import lombok.Data;
//import lombok.Setter;
//import lombok.Getter;
import lombok.Synchronized;

import org.qortal.repository.DataException;
import org.qortal.settings.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.SelectionKey;

import static java.nio.charset.StandardCharsets.UTF_8;
//import static java.util.Objects.isNull;
//import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
//import static org.apache.commons.lang3.BooleanUtils.isTrue;
//import static org.apache.commons.lang3.BooleanUtils.isFalse;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Iterator;
//import java.util.Random;
//import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
//import java.util.concurrent.locks.Lock;
//import java.util.concurrent.locks.ReentrantLock;
import java.util.Objects;
import java.util.function.Function;
import java.time.Instant;

import org.apache.commons.codec.binary.Hex;
import org.qortal.utils.ExecuteProduceConsume;
import org.qortal.utils.ExecuteProduceConsume.StatsSnapshot;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;
import org.qortal.network.message.Message;
import org.qortal.network.message.BlockSummariesV2Message;
import org.qortal.network.message.TransactionSignaturesMessage;
import org.qortal.network.message.GetUnconfirmedTransactionsMessage;
import org.qortal.network.task.RNSBroadcastTask;
import org.qortal.network.task.RNSPrunePeersTask;
import org.qortal.data.network.RNSPeerData;
import org.qortal.controller.Controller;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.transaction.TransactionData;

// logging
import lombok.extern.slf4j.Slf4j;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

@Data
@Slf4j
public class RNSNetwork {

    Reticulum reticulum;
    //private static final String APP_NAME = "qortal";
    static final String APP_NAME = Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_APP_NAME: RNSCommon.MAINNET_APP_NAME;
    //static final String defaultConfigPath = ".reticulum"; // if empty will look in Reticulums default paths
    static final String defaultConfigPath = Settings.getInstance().isTestNet() ? RNSCommon.defaultRNSConfigPathTestnet: RNSCommon.defaultRNSConfigPath;
    private final int MAX_PEERS = Settings.getInstance().getReticulumMaxPeers();
    private final int MIN_DESIRED_PEERS = Settings.getInstance().getReticulumMinDesiredPeers();
    // How long [ms] between pruning of peers
	private long PRUNE_INTERVAL = 1 * 64 * 1000L; // ms;
    
    Identity serverIdentity;
    public Destination baseDestination;
    private volatile boolean isShuttingDown = false;

    /**
     * Maintain two lists for each subset of peers
     *  => a synchronizedList, modified when peers are added/removed
     *  => an immutable List, automatically rebuild to mirror synchronizedList, served to consumers
     *  linkedPeers are "initiators" (containing initiator reticulum Link), actively doing work.
     *  incomimgPeers are "non-initiators", the passive end of bidirectional Reticulum Buffers.
     */
    private final List<RNSPeer> linkedPeers = Collections.synchronizedList(new ArrayList<>());
    private List<RNSPeer> immutableLinkedPeers = Collections.emptyList();
    private final List<RNSPeer> incomingPeers = Collections.synchronizedList(new ArrayList<>());
    private List<RNSPeer> immutableIncomingPeers = Collections.emptyList();

    private final ExecuteProduceConsume rnsNetworkEPC;
    private static final long NETWORK_EPC_KEEPALIVE = 1000L; // 1 second
    private int totalThreadCount = 0;
    private final int reticulumMaxNetworkThreadPoolSize = Settings.getInstance().getReticulumMaxNetworkThreadPoolSize();

    // replicating a feature from Network.class needed in for base Message.java,
    // just in case the classic TCP/IP Networking is turned off.
    private static final byte[] MAINNET_MESSAGE_MAGIC = new byte[]{0x51, 0x4f, 0x52, 0x54}; // QORT
    private static final byte[] TESTNET_MESSAGE_MAGIC = new byte[]{0x71, 0x6f, 0x72, 0x54}; // qorT
    private static final int BROADCAST_CHAIN_TIP_DEPTH = 7; // (~1440 bytes)
    /**
     * How long between informational broadcasts to all ACTIVE peers, in milliseconds.
     */
    private static final long BROADCAST_INTERVAL = 30 * 1000L; // ms
    /**
     * Link low-level ping interval and timeout
     */
    private static final long LINK_PING_INTERVAL = 55 * 1000L; // ms
    private static final long LINK_UNREACHABLE_TIMEOUT = 3 * LINK_PING_INTERVAL;

    //private static final Logger logger = LoggerFactory.getLogger(RNSNetwork.class);
    
    // Constructor
    private RNSNetwork () {
        log.info("RNSNetwork constructor");
        try {
            //String configPath = new java.io.File(defaultConfigPath).getCanonicalPath();
            log.info("creating config from {}", defaultConfigPath);
            initConfig(defaultConfigPath);
            //reticulum = new Reticulum(configPath);
            reticulum = new Reticulum(defaultConfigPath);
            var identitiesPath = reticulum.getStoragePath().resolve("identities");
            if (Files.notExists(identitiesPath)) {
                Files.createDirectories(identitiesPath);
            }
        } catch (IOException e) {
            log.error("unable to create Reticulum network", e);
        }
        log.info("reticulum instance created");
        log.info("reticulum instance created: {}", reticulum);

        //        Settings.getInstance().getMaxRNSNetworkThreadPoolSize(),   // statically set to 5 below
        ExecutorService RNSNetworkExecutor = new ThreadPoolExecutor(1,
                reticulumMaxNetworkThreadPoolSize,
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new NamedThreadFactory("RNSNetwork-EPC", Settings.getInstance().getNetworkThreadPriority()));
        rnsNetworkEPC = new RNSNetworkProcessor(RNSNetworkExecutor);
    }

    // Note: potentially create persistent serverIdentity (utility rnid) and load it from file
    public void start() throws IOException, DataException {

        // create identity either from file or new (creating new keys)
        var serverIdentityPath = reticulum.getStoragePath().resolve("identities/"+APP_NAME);
        if (Files.isReadable(serverIdentityPath)) {
            serverIdentity = Identity.fromFile(serverIdentityPath);
            log.info("server identity loaded from file {}", serverIdentityPath);
        } else {
            serverIdentity = new Identity();
            log.info("APP_NAME: {}, storage path: {}", APP_NAME, serverIdentityPath);
            log.info("new server identity created dynamically.");
            // save it back to file by default for next start (possibly add setting to override)
            try {
                Files.write(serverIdentityPath, serverIdentity.getPrivateKey(), CREATE, WRITE);
                log.info("serverIdentity written back to file");
            } catch (IOException e) {
                log.error("Error while saving serverIdentity to {}", serverIdentityPath, e);
            }
        }
        log.debug("Server Identity: {}", serverIdentity.toString());

        // show the ifac_size of the configured interfaces (debug code)
        for (ConnectionInterface i: Transport.getInstance().getInterfaces() ) {
            log.info("interface {}, length: {}", i.getInterfaceName(), i.getIfacSize());
        }

        baseDestination = new Destination(
            serverIdentity,
            Direction.IN,
            DestinationType.SINGLE,
            APP_NAME,
            "core"
        );
        //// idea for other entry point (needs AnnounceHandler with appropriate aspect)
        //dataDestination = new Destination(
        //    serverIdentity,
        //    Direction.IN,
        //    DestinationType.SINGLE,
        //    APP_NAME,
        //    "qdn"
        //);
        log.info("Destination {} {} running", Hex.encodeHexString(baseDestination.getHash()), baseDestination.getName());
   
        baseDestination.setProofStrategy(ProofStrategy.PROVE_ALL);
        baseDestination.setAcceptLinkRequests(true);
        
        baseDestination.setLinkEstablishedCallback(this::clientConnected);
        Transport.getInstance().registerAnnounceHandler(new QAnnounceHandler());
        log.debug("announceHandlers: {}", Transport.getInstance().getAnnounceHandlers());
        // do a first announce
        baseDestination.announce();
        log.debug("Sent initial announce from {} ({})", Hex.encodeHexString(baseDestination.getHash()), baseDestination.getName());

        // Start up first networking thread (the "server loop", the "Tasks engine")
        rnsNetworkEPC.start();
    }

    private void initConfig(String configDir) throws IOException {
        File configDir1 = new File(configDir);
        if (!configDir1.exists()) {
            configDir1.mkdir();
        }
        var configPath = Path.of(configDir1.getAbsolutePath());
        Path configFile = configPath.resolve(CONFIG_FILE_NAME);

        if (Files.notExists(configFile)) {
            var defaultConfig = this.getClass().getClassLoader().getResourceAsStream(RNSCommon.defaultRNSConfig);
            if (Settings.getInstance().isTestNet()) {
                defaultConfig = this.getClass().getClassLoader().getResourceAsStream(RNSCommon.defaultRNSConfigTestnet);
            }
            Files.copy(defaultConfig, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void broadcast(Function<RNSPeer, Message> peerMessageBuilder) {
        for (RNSPeer peer : getImmutableActiveLinkedPeers()) {
            if (this.isShuttingDown) {
                return;
            }
    
            Message message = peerMessageBuilder.apply(peer);
    
            if (message == null) {
                continue;
            }
    
            var pl = peer.getPeerLink();
            if (nonNull(pl) && (pl.getStatus() == ACTIVE)) {
                peer.sendMessage(message);
            }
        }
    }

    public void broadcastOurChain() {
        BlockData latestBlockData = Controller.getInstance().getChainTip();
        int latestHeight = latestBlockData.getHeight();

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
            Message latestBlockSummariesMessage = new BlockSummariesV2Message(latestBlockSummaries);

            broadcast(broadcastPeer -> latestBlockSummariesMessage);
        } catch (DataException e) {
            log.warn("Couldn't broadcast our chain tip info", e);
        }
    }

    public Message buildNewTransactionMessage(RNSPeer peer, TransactionData transactionData) {
        // In V2 we send out transaction signature only and peers can decide whether to request the full transaction
        return new TransactionSignaturesMessage(Collections.singletonList(transactionData.getSignature()));
    }

    public Message buildGetUnconfirmedTransactionsMessage(RNSPeer peer) {
        return new GetUnconfirmedTransactionsMessage();
    }

    public void shutdown() {
        this.isShuttingDown = true;
        log.info("shutting down Reticulum");
        
        // gracefully close links of peers that point to us
        for (RNSPeer p: incomingPeers) {
            var pl = p.getPeerLink();
            if (nonNull(pl) & (pl.getStatus() == ACTIVE)) {
                p.sendCloseToRemote(pl);
            }
        }
        // Disconnect peers gracefully and terminate Reticulum
        for (RNSPeer p: linkedPeers) {
            log.info("shutting down peer: {}", Hex.encodeHexString(p.getDestinationHash()));
            //log.debug("peer: {}", p);
            p.shutdown();
            try {
                TimeUnit.SECONDS.sleep(1); // allow for peers to disconnect gracefully
            } catch (InterruptedException e) {
                log.error("exception: ", e);
            }
            //var pl = p.getPeerLink();
            //if (nonNull(pl) & (pl.getStatus() == ACTIVE)) {
            //    pl.teardown();
            //}
        }
        // Stop processing threads (the "server loop")
        try {
            if (!this.rnsNetworkEPC.shutdown(5000)) {
                log.warn("RNSNetwork threads failed to terminate");
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for RNS networking threads to terminate");
        }
        // Note: we still need to get the packet timeout callback to work...
        reticulum.exitHandler();
    }

    public void sendCloseToRemote(Link link) {
        if (nonNull(link)) {
            var data = concatArrays("close::".getBytes(UTF_8),link.getDestination().getHash());
            Packet closePacket = new Packet(link, data);
            var packetReceipt = closePacket.send();
            packetReceipt.setDeliveryCallback(this::closePacketDelivered);
            packetReceipt.setTimeoutCallback(this::packetTimedOut);
        } else {
            log.debug("can't send to null link");
        }
    }

    public void closePacketDelivered(PacketReceipt receipt) {
        var rttString = "";
        if (receipt.getStatus() == PacketReceiptStatus.DELIVERED) {
            var rtt = receipt.getRtt();    // rtt (Java) is in miliseconds
            //log.info("qqp - packetDelivered - rtt: {}", rtt);
            if (rtt >= 1000) {
                rtt = Math.round((float) rtt / 1000);
                rttString = String.format("%d seconds", rtt);
            } else {
                rttString = String.format("%d miliseconds", rtt);
            }
            log.info("Shutdown packet confirmation received from {}, round-trip time is {}",
                    Hex.encodeHexString(receipt.getDestination().getHash()), rttString);
        }
    }

    public void packetTimedOut(PacketReceipt receipt) {
        log.info("packet timed out, receipt status: {}", receipt.getStatus());
    }

    public void clientConnected(Link link) {
        //link.setLinkClosedCallback(this::clientDisconnected);
        //link.setPacketCallback(this::serverPacketReceived);
        log.info("clientConnected - link hash: {}, {}", link.getHash(), Hex.encodeHexString(link.getHash()));
        RNSPeer newPeer = new RNSPeer(link);
        newPeer.setPeerLinkHash(link.getHash());
        newPeer.setMessageMagic(getMessageMagic());
        // make sure the peer has a channel and buffer
        newPeer.getOrInitPeerBuffer();
        addIncomingPeer(newPeer);
        log.info("***> Client connected, link: {}", link);
    }

    public void clientDisconnected(Link link) {
        log.info("***> Client disconnected");
    }

    public void serverPacketReceived(byte[] message, Packet packet) {
        var msgText = new String(message, StandardCharsets.UTF_8);
        log.info("Received data on link - message: {}, destinationHash: {}", msgText, Hex.encodeHexString(packet.getDestinationHash()));
    }

    //public void announceBaseDestination () {
    //    getBaseDestination().announce();
    //}

    private class QAnnounceHandler implements AnnounceHandler {
        @Override
        public String getAspectFilter() {
            return "qortal.core";
        }

        @Override
        @Synchronized
        public void receivedAnnounce(byte[] destinationHash, Identity announcedIdentity, byte[] appData) {
            var peerExists = false;
            var activePeerCount = 0; 

            log.info("Received an announce from {}", Hex.encodeHexString(destinationHash));

            if (nonNull(appData)) {
                log.debug("The announce contained the following app data: {}", new String(appData, UTF_8));
            }

            // add to peer list if we can use more peers
            //synchronized (this) {
            var lps =  RNSNetwork.getInstance().getImmutableLinkedPeers();
            for (RNSPeer p: lps) {
                var pl = p.getPeerLink();
                if ((nonNull(pl) && (pl.getStatus() == ACTIVE))) {
                    activePeerCount = activePeerCount + 1;
                }
            }
            if (activePeerCount < MAX_PEERS) {
                for (RNSPeer p: lps) {
                    if (Arrays.equals(p.getDestinationHash(), destinationHash)) {
                        log.info("QAnnounceHandler - peer exists - found peer matching destinationHash");
                        if (nonNull(p.getPeerLink())) {
                            log.info("peer link: {}, status: {}", p.getPeerLink(), p.getPeerLink().getStatus());
                        }
                        peerExists = true;
                        if (p.getPeerLink().getStatus() != ACTIVE) {
                            p.getOrInitPeerLink();
                        }
                        break;
                    } else {
                        if (nonNull(p.getPeerLink())) {
                            log.info("QAnnounceHandler - other peer - link: {}, status: {}", p.getPeerLink(), p.getPeerLink().getStatus());
                        } else {
                            log.info("QAnnounceHandler - peer link is null");
                        }
                    }
                }
                if (!peerExists) {
                    RNSPeer newPeer = new RNSPeer(destinationHash);
                    newPeer.setServerIdentity(announcedIdentity);
                    newPeer.setIsInitiator(true);
                    newPeer.setMessageMagic(getMessageMagic());
                    addLinkedPeer(newPeer);
                    log.info("added new RNSPeer, destinationHash: {}", Hex.encodeHexString(destinationHash));
                }
            }
            // Chance to announce instead of waiting for next pruning.
            // Note: good in theory but leads to ping-pong of announces => not a good idea!
            //maybeAnnounce(getBaseDestination());
        }
    }

    // Main thread
    class RNSNetworkProcessor extends ExecuteProduceConsume {

        //private final Logger logger = LoggerFactory.getLogger(RNSNetworkProcessor.class);

        private final AtomicLong nextConnectTaskTimestamp = new AtomicLong(0L); // ms - try first connect once NTP syncs
        private final AtomicLong nextBroadcastTimestamp = new AtomicLong(0L); // ms - try first broadcast once NTP syncs
        private final AtomicLong nextPingTimestamp = new AtomicLong(0L); // ms - try first low-level Ping
        private final AtomicLong nextPruneTimestamp = new AtomicLong(0L); // ms - try first low-level Ping

        private Iterator<SelectionKey> channelIterator = null;

        RNSNetworkProcessor(ExecutorService executor) {
            super(executor);
            final Long now = NTP.getTime();
            nextPruneTimestamp.set(now + PRUNE_INTERVAL/2);
        }

        @Override
        protected void onSpawnFailure() {
            // For debugging:
            // ExecutorDumper.dump(this.executor, 3, ExecuteProduceConsume.class);
        }

        @Override
        protected Task produceTask(boolean canBlock) throws InterruptedException {
            Task task;

            //// TODO: Needed? Figure out how to add pending messages in RNSPeer
            ////        (RNSPeer: pendingMessages.offer(message))
            //task = maybeProducePeerMessageTask();
            //if (task != null) {
            //    return task;
            //}
            
            final Long now = NTP.getTime();

            //// Prune stuck/slow/old peers (moved from Controller)
            //task = maybeProduceRNSPrunePeersTask(now);
            //if (task != null) {
            //    return task;
            //}
            
            // ping task (Link+Channel+Buffer)
            task = maybeProducePeerPingTask(now);
            if (task != null) {
                return task;
            }
            
            task = maybeProduceBroadcastTask(now);
            if (task != null) {
                return task;
            }

            //// Prune stuck/slow/old peers (moved from Controller)
            //task = maybeProduceRNSPrunePeersTask(now);
            //if (task != null) {
            //    return task;
            //}

            return null;
        }

        ////private Task maybeProducePeerMessageTask() {
        ////    return getImmutableConnectedPeers().stream()
        ////            .map(Peer::getMessageTask)
        ////            .filter(Objects::nonNull)
        ////            .findFirst()
        ////            .orElse(null);
        ////}
        ////private Task maybeProducePeerMessageTask() {
        ////    return getImmutableIncomingPeers().stream()
        ////            .map(RNSPeer::getMessageTask)
        ////            .filter(RNSPeer::isAvailable)
        ////            .findFirst()
        ////            .orElse(null);
        ////}
        //// Note: we might not need this. All messages handled asynchronously in Reticulum
        ////       (RNSPeer peerBufferReady callback)
        //private Task maybeProducePeerMessageTask() {
        //    return getImmutableActiveLinkedPeers().stream()
        //            .map(RNSPeer::getMessageTask)
        //            .filter(Objects::nonNull)
        //            .findFirst()
        //            .orElse(null);
        //}

        //private Task maybeProducePeerPingTask(Long now) {
        //    return getImmutableHandshakedPeers().stream()
        //            .map(peer -> peer.getPingTask(now))
        //            .filter(Objects::nonNull)
        //            .findFirst()
        //            .orElse(null);
        //}
        private Task maybeProducePeerPingTask(Long now) {
            //var ilp = getImmutableLinkedPeers().stream()
            //        .map(peer -> peer.getPingTask(now))
            //        .filter(Objects::nonNull)
            //        .findFirst()
            //        .orElse(null);
            //if (nonNull(ilp)) {
            //    log.info("ilp - {}", ilp);
            //}
            //return ilp;
            return getImmutableActiveLinkedPeers().stream()
                    .map(peer -> peer.getPingTask(now))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        
        private Task maybeProduceBroadcastTask(Long now) {
            if (now == null || now < nextBroadcastTimestamp.get()) {
                return null;
            }
            
            nextBroadcastTimestamp.set(now + BROADCAST_INTERVAL);
            return new RNSBroadcastTask();
        }

        private Task maybeProduceRNSPrunePeersTask(Long now) {
            if (now == null || now < nextPruneTimestamp.get()) {
                return null;
            }
            
            nextPruneTimestamp.set(now + PRUNE_INTERVAL);
            return new RNSPrunePeersTask();
        }
    }

    private static class SingletonContainer {
        private static final RNSNetwork INSTANCE = new RNSNetwork();
    }

    public static RNSNetwork getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public List<RNSPeer> getImmutableActiveLinkedPeers() {
        List<RNSPeer> activePeers = Collections.synchronizedList(new ArrayList<>());
        for (RNSPeer p: this.immutableLinkedPeers) {
            if (nonNull(p.getPeerLink()) && (p.getPeerLink().getStatus() == ACTIVE)) {
                activePeers.add(p);
            }
        }
        return activePeers;
    }

    public List<RNSPeer> getImmutableLinkedPeers() {
        return this.immutableLinkedPeers;
    }

    public void addLinkedPeer(RNSPeer peer) {
        this.linkedPeers.add(peer);
        this.immutableLinkedPeers = List.copyOf(this.linkedPeers); // thread safe
    }

    public void removeLinkedPeer(RNSPeer peer) {
        //if (nonNull(peer.getPeerBuffer())) {
        //    peer.getPeerBuffer().close();
        //}
        if (nonNull(peer.getPeerLink())) {
            peer.getPeerLink().teardown();
        }
        this.linkedPeers.remove(peer); // thread safe
        this.immutableLinkedPeers = List.copyOf(this.linkedPeers);
    }

    public List<RNSPeer> getLinkedPeers() {
        //synchronized(this.linkedPeers) {
            //return new ArrayList<>(this.linkedPeers);
            return this.linkedPeers;
        //}
    }

    public void addIncomingPeer(RNSPeer peer) {
        this.incomingPeers.add(peer);
        this.immutableIncomingPeers = List.copyOf(this.incomingPeers);
    }

    public void removeIncomingPeer(RNSPeer peer) {
        if (nonNull(peer.getPeerLink())) {
            peer.getPeerLink().teardown();
        }
        this.incomingPeers.remove(peer);
        this.immutableIncomingPeers = List.copyOf(this.incomingPeers);
    }

    public List<RNSPeer> getIncomingPeers() {
        return this.incomingPeers;
    }

    public List<RNSPeer> getImmutableIncomingPeers() {
        return this.immutableIncomingPeers;
    }

    // TODO, methods for: getAvailablePeer

    private Boolean isUnreachable(RNSPeer peer) {
        var result = peer.getDeleteMe();
        var now = Instant.now();
        var peerLastAccessTimestamp = peer.getLastAccessTimestamp();
        if (peerLastAccessTimestamp.isBefore(now.minusMillis(LINK_UNREACHABLE_TIMEOUT))) {
            result = true;
        }
        return result;
    }

    public void peerMisbehaved(RNSPeer peer) {
        RNSPeerData peerData = peer.getPeerData();
        peerData.setLastMisbehaved(NTP.getTime());

        //// Only update repository if outbound/initiator peer
        //if (peer.getIsInitiator()) {
        //    try (Repository repository = RepositoryManager.getRepository()) {
        //        synchronized (this.allKnownPeers) {
        //            repository.getNetworkRepository().save(peerData);
        //            repository.saveChanges();
        //        }
        //    } catch (DataException e) {
        //        log.warn("Repository issue while updating peer synchronization info", e);
        //    }
        //}
    }

    public List<RNSPeer> incomingNonActivePeers() {
        var ips = getIncomingPeers();
        List<RNSPeer> result = Collections.synchronizedList(new ArrayList<>());
        Link pl;
        for (RNSPeer p: ips) {
            pl = p.getPeerLink();
            if (nonNull(pl)) {
                if (pl.getStatus() != ACTIVE) {
                    result.add(p);
                }
            } else {
                result.add(p);
            }
        }
        return result;
    }

    //@Synchronized
    public void prunePeers() throws DataException {
        // run periodically (by the Controller)
        var peerList = getImmutableLinkedPeers();
        var incomingPeerList = getImmutableIncomingPeers();
        //log.info("number of links (linkedPeers / incomingPeers) before prunig: {}, {}", peerList.size(),
        //        incomingPeerList.size());
        log.info("number of links (linkedPeers (active) / incomingPeers before prunig: {} ({}), {}",
                getImmutableLinkedPeers().size(), getImmutableActiveLinkedPeers().size(),
                getImmutableIncomingPeers().size());
        // prune initiator peers
        List<RNSPeer> lps =  getImmutableLinkedPeers();
        for (RNSPeer p : lps) {
            var pLink = p.getPeerLink();
            if (nonNull(pLink)) {
                if (pLink.getStatus() == PENDING) {
                    pLink.teardown();
                }
                if (p.getPeerTimedOut()) {
                    pLink.teardown();
                }
                log.info("peer link: {}, status: {}", pLink, pLink.getStatus());
                if (pLink.getStatus() == ACTIVE) {
                    p.pingRemote();
                } else {
                    removeLinkedPeer(p);
                }
            }
        }
        //Link pLink;
        //LinkStatus lStatus;
        //var now = Instant.now();
        //for (RNSPeer p: peerList) {
        //    pLink = p.getPeerLink();
        //    var peerLastAccessTimestamp = p.getLastAccessTimestamp();
        //    var peerLastPingResponseReceived = p.getLastPingResponseReceived();
        //    log.info("peerLink: {}, status: {}", pLink, pLink.getStatus());
        //    log.info("prunePeers - pLink: {}, destinationHash: {}",
        //        pLink, Hex.encodeHexString(p.getDestinationHash()));
        //    log.debug("peer: {}", p);
        //    if (nonNull(pLink)) {
        //        if ((p.getPeerTimedOut()) && (peerLastPingResponseReceived.isBefore(now.minusMillis(LINK_UNREACHABLE_TIMEOUT)))) {
        //            // close peer link for now
        //            pLink.teardown();
        //        }
        //        lStatus = pLink.getStatus();
        //        log.info("Link {} status: {}", pLink, lStatus);
        //        // lStatus in: PENDING, HANDSHAKE, ACTIVE, STALE, CLOSED
        //        if ((lStatus == STALE) || (pLink.getTeardownReason() == TIMEOUT) || (isUnreachable(p))) {
        //            //p.shutdown();
        //            //peerList.remove(p);
        //            removeLinkedPeer(p);
        //        } else if (lStatus == HANDSHAKE) {
        //            // stuck in handshake state (do we need to shutdown/remove it?)
        //            log.info("peer status HANDSHAKE");
        //            //p.shutdown();
        //            //peerList.remove(p);
        //            removeLinkedPeer(p);
        //        }
        //        // either reach peer or disable link
        //        p.pingRemote();
        //    } else {
        //        if (peerLastPingResponseReceived.isBefore(now.minusMillis(LINK_UNREACHABLE_TIMEOUT))) {
        //            //peerList.remove(p);
        //            removeLinkedPeer(p);
        //        }
        //    }
        //}
        List<RNSPeer> inaps = incomingNonActivePeers();
        //log.info("number of inactive incoming peers: {}", inaps.size());
        for (RNSPeer p: inaps) {
            //incomingPeerList.remove(incomingPeerList.indexOf(p));
            removeIncomingPeer(p);
        }
        log.info("number of links (linkedPeers (active) / incomingPeers after prunig: {} ({}), {}",
                getImmutableLinkedPeers().size(), getImmutableActiveLinkedPeers().size(),
                getImmutableIncomingPeers().size());
        maybeAnnounce(getBaseDestination());
    }

    public void maybeAnnounce(Destination d) {
        if (getImmutableActiveLinkedPeers().size() < MIN_DESIRED_PEERS) {
            d.announce();
        }
    }

    /**
     * Helper methods
     */

    public RNSPeer findPeerByLink(Link link) {
        //List<RNSPeer> lps =  RNSNetwork.getInstance().getLinkedPeers();
        List<RNSPeer> lps =  RNSNetwork.getInstance().getImmutableLinkedPeers();
        RNSPeer peer = null;
        for (RNSPeer p : lps) {
            var pLink = p.getPeerLink();
            if (nonNull(pLink)) {
                if (Arrays.equals(pLink.getDestination().getHash(),link.getDestination().getHash())) {
                    log.info("found peer matching destinationHash: {}", Hex.encodeHexString(link.getDestination().getHash()));
                    peer = p;
                    break;
                }
            }
        }
        return peer;
    }

    public RNSPeer findPeerByDestinationHash(byte[] dhash) {
        //List<RNSPeer> lps =  RNSNetwork.getInstance().getLinkedPeers();
        List<RNSPeer> lps =  RNSNetwork.getInstance().getImmutableLinkedPeers();
        RNSPeer peer = null;
        for (RNSPeer p : lps) {
            if (Arrays.equals(p.getDestinationHash(), dhash)) {
                log.info("found peer matching destinationHash: {}", Hex.encodeHexString(dhash));
                peer = p;
                break;
            }
        }
        return peer;
    }

    //public void removePeer(RNSPeer peer) {
    //    List<RNSPeer> peerList = this.linkedPeers;
    //    if (nonNull(peer)) {
    //        peerList.remove(peer);
    //    }
    //}

    public byte[] getMessageMagic() {
        return Settings.getInstance().isTestNet() ? TESTNET_MESSAGE_MAGIC : MAINNET_MESSAGE_MAGIC;
    }

    // Network methods Reticulum implementation

    /** Builds either (legacy) HeightV2Message or (newer) BlockSummariesV2Message, depending on peer version.
     *
     *  @return Message, or null if DataException was thrown.
     */
    public Message buildHeightOrChainTipInfo(RNSPeer peer) {
        // peer only used for version check
        int latestHeight = Controller.getInstance().getChainHeight();

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
            return new BlockSummariesV2Message(latestBlockSummaries);
        } catch (DataException e) {
            return null;
        }
    }

}

