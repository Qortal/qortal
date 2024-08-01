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
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketReceipt;
import io.reticulum.packet.PacketReceiptStatus;
import io.reticulum.transport.AnnounceHandler;
import static io.reticulum.link.TeardownSession.DESTINATION_CLOSED;
import static io.reticulum.link.TeardownSession.INITIATOR_CLOSED;
import static io.reticulum.link.TeardownSession.TIMEOUT;
import static io.reticulum.link.LinkStatus.ACTIVE;
import static io.reticulum.link.LinkStatus.STALE;
import static io.reticulum.link.LinkStatus.PENDING;
import static io.reticulum.link.LinkStatus.HANDSHAKE;
//import static io.reticulum.packet.PacketContextType.LINKCLOSE;
import static io.reticulum.identity.IdentityKnownDestination.recall;
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

import static java.nio.charset.StandardCharsets.UTF_8;
//import static java.util.Objects.isNull;
//import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
//import static org.apache.commons.lang3.BooleanUtils.isFalse;

import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
//import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Hex;

// logging
import lombok.extern.slf4j.Slf4j;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

@Data
@Slf4j
public class RNSNetwork {

    Reticulum reticulum;
    //private static final String APP_NAME = "qortal";
    static final String APP_NAME = RNSCommon.APP_NAME;
    static final String defaultConfigPath = new String(".reticulum"); // if empty will look in Reticulums default paths
    //static final String defaultConfigPath = RNSCommon.defaultRNSConfigPath;
    //private final String defaultConfigPath = Settings.getInstance().getDefaultRNSConfigPathForReticulum();
    private static Integer MAX_PEERS = 12;
    //private final Integer MAX_PEERS = Settings.getInstance().getMaxReticulumPeers();
    private static Integer MIN_DESIRED_PEERS = 3;
    //private final Integer MIN_DESIRED_PEERS = Settings.getInstance().getMinDesiredPeers();
    Identity serverIdentity;
    public Destination baseDestination;
    private volatile boolean isShuttingDown = false;
    private final List<RNSPeer> linkedPeers = Collections.synchronizedList(new ArrayList<>());
    private final List<Link> incomingLinks = Collections.synchronizedList(new ArrayList<>());

    ////private final ExecuteProduceConsume rnsNetworkEPC;
    //private static final long NETWORK_EPC_KEEPALIVE = 1000L; // 1 second
    //private volatile boolean isShuttingDown = false;
    //private int totalThreadCount = 0;
    //// TODO: settings - MaxReticulumPeers, MaxRNSNetworkThreadPoolSize (if needed)

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
        } catch (IOException e) {
            log.error("unable to create Reticulum network", e);
        }
        log.info("reticulum instance created");
        log.info("reticulum instance created: {}", reticulum);

        //        Settings.getInstance().getMaxRNSNetworkThreadPoolSize(),   // statically set to 5 below
        //ExecutorService RNSNetworkExecutor = new ThreadPoolExecutor(1,
        //        5,
        //        NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
        //        new SynchronousQueue<Runnable>(),
        //        new NamedThreadFactory("RNSNetwork-EPC"));
        //rnsNetworkEPC = new RNSNetworkProcessor(RNSNetworkExecutor);
    }

    // Note: potentially create persistent serverIdentity (utility rnid) and load it from file
    public void start() throws IOException, DataException {

        // create identity either from file or new (creating new keys)
        var serverIdentityPath = reticulum.getStoragePath().resolve("identities/"+APP_NAME);
        if (Files.isReadable(serverIdentityPath)) {
            serverIdentity = Identity.fromFile(serverIdentityPath);
            log.info("server identity loaded from file {}", serverIdentityPath.toString());
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
        //// idea for other entry point
        //dataDestination = new Destination(
        //    serverIdentity,
        //    Direction.IN,
        //    DestinationType.SINGLE,
        //    APP_NAME,
        //    "core",
        //    "qdn"
        //);
        log.info("Destination "+Hex.encodeHexString(baseDestination.getHash())+" "+baseDestination.getName()+" running.");
   
        baseDestination.setProofStrategy(ProofStrategy.PROVE_ALL);
        baseDestination.setAcceptLinkRequests(true);

        baseDestination.setLinkEstablishedCallback(this::clientConnected);
        
        Transport.getInstance().registerAnnounceHandler(new QAnnounceHandler());
        log.debug("announceHandlers: {}", Transport.getInstance().getAnnounceHandlers());

        // do a first announce
        baseDestination.announce();
        log.debug("Sent initial announce from {} ({})", Hex.encodeHexString(baseDestination.getHash()), baseDestination.getName());
   
        // Start up first networking thread (the "server loop")
        //rnsNetworkEPC.start();
    }

    private void initConfig(String configDir) throws IOException {
        File configDir1 = new File(defaultConfigPath);
        if (!configDir1.exists()) {
            configDir1.mkdir();
        }
        var configPath = Path.of(configDir1.getAbsolutePath());
        Path configFile = configPath.resolve(CONFIG_FILE_NAME);

        if (Files.notExists(configFile)) {
            var defaultConfig = this.getClass().getClassLoader().getResourceAsStream("reticulum_default_config.yml");
            Files.copy(defaultConfig, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void shutdown() {
        isShuttingDown = true;
        log.info("shutting down Reticulum");
        
        // Stop processing threads (the "server loop")
        //try {
        //    if (!this.rnsNetworkEPC.shutdown(5000)) {
        //        logger.warn("RNSNetwork threads failed to terminate");
        //    }
        //} catch (InterruptedException e) {
        //    logger.warn("Interrupted while waiting for RNS networking threads to terminate");
        //}

        // Disconnect peers gracefully and terminate Reticulum
        for (RNSPeer p: linkedPeers) {
            log.info("shutting down peer: {}", Hex.encodeHexString(p.getDestinationHash()));
            log.debug("peer: {}", p);
            p.shutdown();
            try {
                TimeUnit.SECONDS.sleep(1); // allow for peers to disconnect gracefully
            } catch (InterruptedException e) {
                log.error("exception: {}", e);
            }
        }
        // gracefully close links of peers that point to us
        for (Link l: incomingLinks) {
            sendCloseToRemote(l);
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
        var rttString = new String("");
        if (receipt.getStatus() == PacketReceiptStatus.DELIVERED) {
            var rtt = receipt.getRtt();    // rtt (Java) is in miliseconds
            //log.info("qqp - packetDelivered - rtt: {}", rtt);
            if (rtt >= 1000) {
                rtt = Math.round(rtt / 1000);
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
        link.setLinkClosedCallback(this::clientDisconnected);
        link.setPacketCallback(this::serverPacketReceived);
        var peer = findPeerByLink(link);
        if (nonNull(peer)) {
            log.info("initiator peer {} opened link (link lookup: {}), link destination hash: {}",
                Hex.encodeHexString(peer.getDestinationHash()), link, Hex.encodeHexString(link.getDestination().getHash()));
                // make sure the peerLink is actvive.
                peer.getOrInitPeerLink();
        } else {
            log.info("non-initiator closed link (link lookup: {}), link destination hash (initiator): {}",
                peer, link, Hex.encodeHexString(link.getDestination().getHash()));
        }
        incomingLinks.add(link);
        log.info("***> Client connected, link: {}", link);
    }

    public void clientDisconnected(Link link) {
        var peer = findPeerByLink(link);
        if (nonNull(peer)) {
            log.info("initiator peer {} closed link (link lookup: {}), link destination hash: {}",
                Hex.encodeHexString(peer.getDestinationHash()), link, Hex.encodeHexString(link.getDestination().getHash()));
        } else {
            log.info("non-initiator closed link (link lookup: {}), link destination hash (initiator): {}",
                peer, link, Hex.encodeHexString(link.getDestination().getHash()));
        }
        // if we have a peer pointing to that destination, we can close and remove it
        peer = findPeerByDestinationHash(link.getDestination().getHash());
        if (nonNull(peer)) {
            // Note: no shutdown as the remobe peer could be only rebooting.
            //       keep it to reopen link later if possible.
            peer.getPeerLink().teardown();
        }
        incomingLinks.remove(link);
        log.info("***> Client disconnected");
    }

    public void serverPacketReceived(byte[] message, Packet packet) {
        var msgText = new String(message, StandardCharsets.UTF_8);
        log.info("Received data on link - message: {}, destinationHash: {}", msgText, Hex.encodeHexString(packet.getDestinationHash()));
        //var peer = findPeerByDestinationHash(packet.getDestinationHash());
        //if (msgText.equals("ping")) {
        //    log.info("received ping");
        //    //if (nonNull(peer)) {
        //    //  String replyText = "pong";
        //    //  byte[] replyData = replyText.getBytes(StandardCharsets.UTF_8);
        //    //  Packet reply = new Packet(peer.getPeerLink(), replyData);
        //    //}
        //}
        //if (msgText.equals("shutdown")) {
        //    log.info("shutdown packet received");
        //    var link = recall(packet.getDestinationHash());
        //    log.info("recalled destinationHash: {}", link);
        //    //...
        //}
        // TODO: process packet.... 
    }

    //public void announceBaseDestination () {
    //    getBaseDestination().announce();
    //}

    //@Slf4j
    private class QAnnounceHandler implements AnnounceHandler {
        @Override
        public String getAspectFilter() {
            // handle all announces
            return null;
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
            var lps =  RNSNetwork.getInstance().getLinkedPeers();
            for (RNSPeer p: lps) {
                var pl = p.getPeerLink();
                if ((nonNull(pl) && (pl.getStatus() == ACTIVE))) {
                    activePeerCount = activePeerCount + 1;
                }
            }
            if (activePeerCount < MAX_PEERS) {
                //if (!peerExists) { 
                //var peer = findPeerByDestinationHash(destinationHash);
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
                            log.info("QAnnounceHandler - null link peer - link: {}", p.getPeerLink());
                        }
                    }
                }
                if (!peerExists) {
                    RNSPeer newPeer = new RNSPeer(destinationHash);
                    newPeer.setServerIdentity(announcedIdentity);
                    newPeer.setIsInitiator(true);
                    lps.add(newPeer);
                    log.info("added new RNSPeer, destinationHash: {}", Hex.encodeHexString(destinationHash));
                }
            }
            //}
        }
    }

    // Main thread
    //class RNSNetworkProcessor extends ExecuteProduceConsume {
    //
    //    //private final Logger logger = LoggerFactory.getLogger(RNSNetworkProcessor.class);
    //
    //    private final AtomicLong nextConnectTaskTimestamp = new AtomicLong(0L); // ms - try first connect once NTP syncs
    //    private final AtomicLong nextBroadcastTimestamp = new AtomicLong(0L); // ms - try first broadcast once NTP syncs
    //
    //    private Iterator<SelectionKey> channelIterator = null;
    //
    //    RNSNetworkProcessor(ExecutorService executor) {
    //        super(executor);
    //    }
    //
    //    @Override
    //    protected void onSpawnFailure() {
    //        // For debugging:
    //        // ExecutorDumper.dump(this.executor, 3, ExecuteProduceConsume.class);
    //    }
    //
    //    @Override
    //    protected Task produceTask(boolean canBlock) throws InterruptedException {
    //        Task task;
    //
    //        //task = maybeProducePeerMessageTask();
    //        //if (task != null) {
    //        //    return task;
    //        //}
    //        //
    //        //final Long now = NTP.getTime();
    //        //
    //        //task = maybeProducePeerPingTask(now);
    //        //if (task != null) {
    //        //    return task;
    //        //}
    //        //
    //        //task = maybeProduceConnectPeerTask(now);
    //        //if (task != null) {
    //        //    return task;
    //        //}
    //        //
    //        //task = maybeProduceBroadcastTask(now);
    //        //if (task != null) {
    //        //    return task;
    //        //}
    //        //
    //        // Only this method can block to reduce CPU spin
    //        //return maybeProduceChannelTask(canBlock);
    //
    //        // TODO: flesh out the tasks handled by Reticulum
    //        return null;
    //    }
    //    //...TODO: implement abstract methods...
    //}


    // getter / setter
    private static class SingletonContainer {
        private static final RNSNetwork INSTANCE = new RNSNetwork();
    }

    public static RNSNetwork getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public Identity getServerIdentity() {
        return this.serverIdentity;
    }

    public Reticulum getReticulum() {
        return this.reticulum;
    }

    public List<RNSPeer> getLinkedPeers() {
        synchronized(this.linkedPeers) {
            //return new ArrayList<>(this.linkedPeers);
            return this.linkedPeers;
        }
    }

    public Integer getTotalPeers() {
        synchronized (this) {
            return linkedPeers.size();
        }
    }

    public Destination getBaseDestination() {
        return baseDestination;
    }

    // maintenance
    //public void removePeer(RNSPeer peer) {
    //    synchronized(this) {
    //        List<RNSPeer> peerList = this.linkedPeers;
    //        log.info("removing peer {} on peer shutdown", peer);
    //        peerList.remove(peer);
    //    }
    //}

    //public void pingPeer(RNSPeer peer) {
    //    if (nonNull(peer)) {
    //        peer.pingRemote();
    //    } else {
    //        log.error("peer argument is null");
    //    }
    //}

    @Synchronized
    public void prunePeers() throws DataException {
        // run periodically (by the Controller)
        //List<Link> linkList = getLinkedPeers();
        var peerList = getLinkedPeers();
        log.info("number of links (linkedPeers) before prunig: {}", peerList.size());
        Link pLink;
        LinkStatus lStatus;
        for (RNSPeer p: peerList) {
            pLink = p.getPeerLink();
            log.info("prunePeers - pLink: {}, destinationHash: {}",
                pLink, Hex.encodeHexString(p.getDestinationHash()));
            log.debug("peer: {}", p);
            if (nonNull(pLink)) {
                if (p.getPeerTimedOut()) {
                    // close peer link for now
                    pLink.teardown();
                }
                lStatus = pLink.getStatus();
                log.info("Link {} status: {}", pLink, lStatus);
                // lStatus in: PENDING, HANDSHAKE, ACTIVE, STALE, CLOSED
                if ((lStatus == STALE) || (pLink.getTeardownReason() == TIMEOUT) || (p.getDeleteMe())) {
                    p.shutdown();
                    peerList.remove(p);
                } else if (lStatus == HANDSHAKE) {
                    // stuck in handshake state (do we need to shutdown/remove it?)
                    log.info("peer status HANDSHAKE");
                    p.shutdown();
                    peerList.remove(p);
                }
            } else {
                peerList.remove(p);
            }
        }
        //removeExpiredPeers(this.linkedPeers);
        log.info("number of links (linkedPeers) after prunig: {}", peerList.size());
        //log.info("we have {} non-initiator links, list: {}", incomingLinks.size(), incomingLinks);
        var activePeerCount = 0;
        var lps =  RNSNetwork.getInstance().getLinkedPeers();
        for (RNSPeer p: lps) {
            pLink = p.getPeerLink();
            p.pingRemote();
            try {
                TimeUnit.SECONDS.sleep(2); // allow for peers to disconnect gracefully
            } catch (InterruptedException e) {
                log.error("exception: {}", e);
            }
            if ((nonNull(pLink) && (pLink.getStatus() == ACTIVE))) {
                activePeerCount = activePeerCount + 1;
            }
        }
        log.info("we have {} active peers", activePeerCount);
        maybeAnnounce(getBaseDestination());
    }

    //public void removeExpiredPeers(List<RNSPeer> peerList) {
    //    //List<RNSPeer> peerList = this.linkedPeers;
    //    for (RNSPeer p: peerList) {
    //        if (p.getPeerLink() == null) {
    //            peerList.remove(p);
    //        } else if (p.getPeerLink().getStatus() == STALE) {
    //            peerList.remove(p);
    //        }
    //    }
    //}

    public void maybeAnnounce(Destination d) {
        if (getLinkedPeers().size() < MIN_DESIRED_PEERS) {
            d.announce();
        }
    }

    /**
     * Helper methods
     */

    //@Synchronized
    //public RNSPeer getPeerIfExists(RNSPeer peer) {
    //    List<RNSPeer> lps =  RNSNetwork.getInstance().getLinkedPeers();
    //    RNSPeer result = null;
    //    for (RNSPeer p: lps) {
    //        if (nonNull(p.getDestinationHash()) && Arrays.equals(p.getDestinationHash(), peer.getDestinationHash())) {
    //            log.info("found match by destinationHash");
    //            result = p;
    //            //break;
    //        }
    //        if (nonNull(p.getPeerDestinationHash()) && Arrays.equals(p.getPeerDestinationHash(), peer.getPeerDestinationHash())) {
    //            log.info("found match by peerDestinationHash");
    //            result = p;
    //            //break;
    //        }
    //        if (nonNull(p.getPeerBaseDestinationHash()) && Arrays.equals(p.getPeerBaseDestinationHash(), peer.getPeerBaseDestinationHash())) {
    //            log.info("found match by peerBaseDestinationHash");
    //            result = p;
    //            //break;
    //        }
    //        if (nonNull(p.getRemoteTestHash()) && Arrays.equals(p.getRemoteTestHash(), peer.getRemoteTestHash())) {
    //            log.info("found match by remoteTestHash");
    //            result = p;
    //            //break;
    //        }
    //    }
    //    return result;
    //}

    public RNSPeer findPeerByLink(Link link) {
        List<RNSPeer> lps =  RNSNetwork.getInstance().getLinkedPeers();
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
        List<RNSPeer> lps =  RNSNetwork.getInstance().getLinkedPeers();
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

    public void removePeer(RNSPeer peer) {
        List<RNSPeer> peerList = this.linkedPeers;
        if (nonNull(peer)) {
            peerList.remove(peer);
        }
    }

}

