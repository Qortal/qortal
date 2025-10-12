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
//import io.reticulum.link.LinkStatus;
//import io.reticulum.constant.LinkConstant;
//import static io.reticulum.constant.ReticulumConstant.MTU;
//import io.reticulum.buffer.Buffer;
//import io.reticulum.buffer.BufferedRWPair;
import io.reticulum.packet.Packet;
import io.reticulum.packet.PacketReceipt;
import io.reticulum.packet.PacketReceiptStatus;
import io.reticulum.transport.AnnounceHandler;
//import static io.reticulum.link.TeardownSession.DESTINATION_CLOSED;
//import static io.reticulum.link.TeardownSession.INITIATOR_CLOSED;
//import static io.reticulum.link.TeardownSession.TIMEOUT;
import static io.reticulum.link.LinkStatus.ACTIVE;
//import static io.reticulum.link.LinkStatus.STALE;
import static io.reticulum.link.LinkStatus.CLOSED;
import static io.reticulum.link.LinkStatus.PENDING;
//import static io.reticulum.link.LinkStatus.HANDSHAKE;
//import static io.reticulum.packet.PacketContextType.LINKCLOSE;
//import static io.reticulum.identity.IdentityKnownDestination.recall;
import static io.reticulum.utils.IdentityUtils.concatArrays;
//import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.constant.ReticulumConstant.CONFIG_FILE_NAME;
import lombok.Data;
//import lombok.Setter;
//import lombok.Getter;
import lombok.Synchronized;

//import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
//import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import java.nio.file.Files;
import java.nio.file.Path;
//import java.nio.channels.SelectionKey;

import static java.nio.charset.StandardCharsets.UTF_8;
//import static java.util.Objects.isNull;
//import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
//import static org.apache.commons.lang3.BooleanUtils.isTrue;
//import static org.apache.commons.lang3.BooleanUtils.isFalse;

import java.io.File;
import java.util.*;
//import java.util.Random;
//import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicLong;
//import java.util.concurrent.locks.Lock;
//import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.time.Instant;
import java.util.stream.Collectors;
//import java.net.InetAddress;
//import java.net.UnknownHostException;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
//import org.qortal.utils.ExecuteProduceConsume;
//import org.qortal.utils.ExecuteProduceConsume.StatsSnapshot;
import org.qortal.utils.NTP;
//import org.qortal.utils.NamedThreadFactory;
import org.qortal.network.message.Message;
import org.qortal.network.message.BlockSummariesV2Message;
import org.qortal.network.message.TransactionSignaturesMessage;
import org.qortal.network.message.GetUnconfirmedTransactionsMessage;
import org.qortal.data.network.PeerData;
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

// templates
import com.hubspot.jinjava.Jinjava;
import com.google.common.collect.Maps;

@Data
@Slf4j
public class RNS {
//public class RNS extends Thread {

    public Reticulum reticulum;
    //private static final String APP_NAME = "qortal";
    static final String APP_NAME = Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_APP_NAME: RNSCommon.MAINNET_APP_NAME;
    static final Integer TARGET_PORT = Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_IF_TCP_PORT: RNSCommon.MAINNET_IF_TCP_PORT;
    //static final String defaultConfigPath = ".reticulum"; // if empty will look in Reticulums default paths
    static final String defaultConfigPath = Settings.getInstance().isTestNet() ? RNSCommon.defaultRNSConfigPathTestnet: RNSCommon.defaultRNSConfigPath;
    private final int MAX_PEERS = Settings.getInstance().getReticulumMaxPeers();
    private final int MIN_DESIRED_CORE_PEERS = Settings.getInstance().getReticulumMinDesiredCorePeers();
    private final int MIN_DESIRED_DATA_PEERS = Settings.getInstance().getReticulumMinDesiredDataPeers();
    // How long [ms] between pruning of peers
	private long PRUNE_INTERVAL = 1 * 64 * 1000L; // ms;
    
    Identity serverIdentity;
    public Destination baseDestination;
    public Destination dataDestination;
    private volatile boolean isShuttingDown = false;

    /**
     * Maintain two lists for each subset of peers
     *  => a synchronizedList, modified when peers are added/removed
     *  => an immutable List, automatically rebuild to mirror synchronizedList, served to consumers
     *  linkedPeers are "initiators" (containing initiator reticulum Link), actively doing work.
     *  incomimgPeers are "non-initiators", the passive end of bidirectional Reticulum Buffers.
     */
    private final List<ReticulumPeer> linkedPeers = Collections.synchronizedList(new ArrayList<>());
    private List<ReticulumPeer> immutableLinkedPeers = Collections.emptyList();
    private final List<ReticulumPeer> incomingPeers = Collections.synchronizedList(new ArrayList<>());
    private List<ReticulumPeer> immutableIncomingPeers = Collections.emptyList();

    //private final ExecuteProduceConsume rnsNetworkEPC;
    private static final long NETWORK_EPC_KEEPALIVE = 1000L; // 1 second
    private int totalThreadCount = 0;
    //private final int reticulumMaxNetworkThreadPoolSize = Settings.getInstance().getReticulumMaxNetworkThreadPoolSize();

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

    //private static final Logger logger = LoggerFactory.getLogger(RNS.class);
    
    // Constructor
    public RNS () {
        log.info("RNS constructor");
        try {
            //String configPath = new java.io.File(defaultConfigPath).getCanonicalPath();
            log.info("creating config in {}", defaultConfigPath);
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
        log.debug("reticulum instance created: {}", reticulum);
    }

    // Note: potentially create persistent serverIdentity (utility rnid) and load it from file
    //public void start() throws IOException, DataException {
    public void start() {

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
        log.info("Destination {} {} running", encodeHexString(baseDestination.getHash()), baseDestination.getName());
        dataDestination = new Destination(
            serverIdentity,
            Direction.IN,
            DestinationType.SINGLE,
            APP_NAME,
            "qdn"
        );
        log.info("Destination {} {} running", encodeHexString(dataDestination.getHash()), dataDestination.getName());
   
        baseDestination.setProofStrategy(ProofStrategy.PROVE_ALL);
        baseDestination.setAcceptLinkRequests(true);
        dataDestination.setProofStrategy(ProofStrategy.PROVE_APP);
        dataDestination.setAcceptLinkRequests(true);
        
        baseDestination.setLinkEstablishedCallback(this::baseClientConnected);
        dataDestination.setLinkEstablishedCallback(this::dataClientConnected);
        Transport.getInstance().registerAnnounceHandler(new QAnnounceHandler());
        Transport.getInstance().registerAnnounceHandler(new QAnnounceHandler("qortal.qdn"));
        log.debug("announceHandlers: {}", Transport.getInstance().getAnnounceHandlers());
        // do a first announce
        baseDestination.announce();
        log.debug("Sent initial announce from {} ({})", encodeHexString(baseDestination.getHash()), baseDestination.getName());
        // announce QDN destination
        dataDestination.announce();
    }

    private void initConfig(String configDir) throws IOException {
        File configDir1 = new File(configDir);
        if (!configDir1.exists()) {
            configDir1.mkdir();
        }
        var configPath = Path.of(configDir1.getAbsolutePath());
        Path configFile = configPath.resolve(CONFIG_FILE_NAME);
        var localhost = InetAddress.getLocalHost();
        var fqdn = localhost.getCanonicalHostName();
        var isReticulumGateway = Settings.getInstance().getReticulumIsGateway();
        var reticulumDesiredClientInterfaces =  Settings.getInstance().getReticulumDesiredClientInterfaces();
        var reticulumTcpGatewayServers = Arrays.stream(Settings.getInstance().getReticulumTcpGatewayServers()).collect(Collectors.toList());
        reticulumTcpGatewayServers.remove(fqdn);
        Map<String, Object> context = Maps.newHashMap();

        //log.info("fqdn: {}, reticulumTcpGatewayServers: {}", fqdn, reticulumTcpGatewayServers);

        if (Files.notExists(configFile)) {
            try {
                // jinjava variables set in context:
                // * tcp_gateway_servers: list of nodes with a TCPServerInterface
                // * num_client_interfaces: number of client interfaces to gateways be configured
                // * host_fqdn: host FQDN
                // * qortal_network_name: either "qortal" or "qortaltest" (from isTestnet)
                // * is_reticulum_gateway: one of the instances (Qortal core or RNS) has
                //                         at least one Gateway interface
                // * is_test_net: String "true" or "false" (from isTestNet)
                // * target_port: target port for TCPServerInterface (only)
                // * use_python_rns: use local shared python rnsd (has to provide a gateway interface)
                // * python_rns_if_port: rnsd TCPServerInterface port (if rnsd gateway is a TCPServerInterface)
                var jnj = new Jinjava();
                var reticulumGateways = StringUtils.join(reticulumTcpGatewayServers, " ");
                log.info("reticulumGateways: {}", reticulumGateways);
                context.put("tcp_gateway_servers",  reticulumGateways);
                context.put("num_client_interfaces", reticulumDesiredClientInterfaces);
                context.put("host_fqdn", fqdn);
                context.put("qortal_network_name",  APP_NAME);
                context.put("target_port", TARGET_PORT);
                context.put("is_reticulum_gateway", isReticulumGateway ? "true" : "false");
                //context.put("is_test_net", Settings.getInstance().isTestNet() ? "true" : "false");
                context.put("use_python_rns", Settings.getInstance().getReticulumUsePythonRNS() ? "true" : "false");
                context.put("python_rns_if_port", Settings.getInstance().getReticulumPythonRNSGatewayPort());

                // render config.yml from template
                log.info("Rendering new Reticulum configuration file from resource {}", RNSCommon.jinjaConfigTemplateName  );
                var templateResourceInpuSteam = this.getClass().getClassLoader().getResourceAsStream(RNSCommon.jinjaConfigTemplateName);
                //var template = new Scanner(templateResourceInputSteam).useDelimiter("\n").next();
                var template = new BufferedReader(new InputStreamReader(templateResourceInpuSteam)).lines().parallel().collect(Collectors.joining("\n"));
                //log.info("template: {}", template);
                var renderedConfig = jnj.render(template, context);
                //log.info("rendered template - {}", renderedConfig);
                Files.write(configFile, renderedConfig.getBytes(), CREATE, WRITE);
            } catch (Exception e) {
                log.error("Failed to render config file - creating fallback default  config file", e);
                var defaultConfig = this.getClass().getClassLoader().getResourceAsStream(RNSCommon.defaultRNSConfig);
                if (Settings.getInstance().isTestNet()) {
                    defaultConfig = this.getClass().getClassLoader().getResourceAsStream(RNSCommon.defaultRNSConfigTestnet);
                }
                Files.copy(defaultConfig, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            log.debug("Reticulum config exists, skipping.");
        }
    }

    public void broadcast(Function<ReticulumPeer, Message> peerMessageBuilder) {
        for (ReticulumPeer peer : getActiveImmutableLinkedPeers()) {
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

        log.debug("broadcastOurChain latestHeight: {}", latestHeight);
        try (final Repository repository = RepositoryManager.getRepository()) {
            List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
            Message latestBlockSummariesMessage = new BlockSummariesV2Message(latestBlockSummaries);

            broadcast(broadcastPeer -> latestBlockSummariesMessage);
        } catch (DataException e) {
            log.warn("Couldn't broadcast our chain tip info", e);
        }
    }

    public Message buildNewTransactionMessage(ReticulumPeer peer, TransactionData transactionData) {
        // In V2 we send out transaction signature only and peers can decide whether to request the full transaction
        return new TransactionSignaturesMessage(Collections.singletonList(transactionData.getSignature()));
    }

    public Message buildGetUnconfirmedTransactionsMessage(ReticulumPeer peer) {
        return new GetUnconfirmedTransactionsMessage();
    }

    public void shutdown() {
        this.isShuttingDown = true;
        log.info("shutting down Reticulum");
        
        // gracefully close links of peers that point to us
        for (ReticulumPeer p: incomingPeers) {
            var pl = p.getPeerLink();
            if (nonNull(pl) & (pl.getStatus() == ACTIVE)) {
                p.sendCloseToRemote(pl);
            }
        }
        // Disconnect peers gracefully and terminate Reticulum
        for (ReticulumPeer p: linkedPeers) {
            log.info("shutting down peer: {}", encodeHexString(p.getDestinationHash()));
            //log.debug("peer: {}", p);
            p.shutdown();
            try {
                TimeUnit.MILLISECONDS.sleep(200); // allow for peers to disconnect gracefully
            } catch (InterruptedException e) {
                log.error("exception: ", e);
            }
            //var pl = p.getPeerLink();
            //if (nonNull(pl) & (pl.getStatus() == ACTIVE)) {
            //    pl.teardown();
            //}
        }
        //// Stop processing threads (the "server loop")
        //try {
        //    if (!this.rnsNetworkEPC.shutdown(5000)) {
        //        log.warn("RNSNetwork threads failed to terminate");
        //    }
        //} catch (InterruptedException e) {
        //    log.warn("Interrupted while waiting for RNS networking threads to terminate");
        //}
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
                    encodeHexString(receipt.getDestination().getHash()), rttString);
        }
    }

    public void packetTimedOut(PacketReceipt receipt) {
        log.info("packet timed out, receipt status: {}", receipt.getStatus());
    }

    public void baseClientConnected(Link link) {
        //link.setLinkClosedCallback(this::clientDisconnected);
        //link.setPacketCallback(this::serverPacketReceived);
        log.info("baseClientConnected - link hash: {}, {}", link.getHash(), encodeHexString(link.getHash()));
        ReticulumPeer newPeer = new ReticulumPeer(link);
        newPeer.setPeerLinkHash(link.getHash());
        newPeer.setPeerAspect(RNSCommon.PeerAspect.BASE);
        newPeer.setMessageMagic(getMessageMagic());
        // make sure the peer has a channel and buffer
        newPeer.getOrInitPeerBuffer();
        addIncomingPeer(newPeer);
        log.info("***> Base client connected, base link: {}", encodeHexString(link.getLinkId()));
    }

    public void dataClientConnected(Link link) {
        //link.setLinkClosedCallback(this::clientDisconnected);
        //link.setPacketCallback(this::serverPacketReceived);
        log.info("dataClientConnected - link hash: {}, {}", link.getHash(), encodeHexString(link.getHash()));
        ReticulumPeer newPeer = new ReticulumPeer(link);
        newPeer.setPeerLinkHash(link.getHash());
        newPeer.setPeerAspect(RNSCommon.PeerAspect.DATA);
        newPeer.setMessageMagic(getMessageMagic());
        // make sure the peer has a channel and buffer
        newPeer.getOrInitPeerBuffer();
        addIncomingPeer(newPeer);
        log.info("***> Data Client connected, data link: {}", encodeHexString(link.getLinkId()));
    }

    public void clientDisconnected(Link link) {
        log.info("***> Client disconnected");
    }

    public void serverPacketReceived(byte[] message, Packet packet) {
        var msgText = new String(message, StandardCharsets.UTF_8);
        log.info("Received data on link - message: {}, destinationHash: {}", msgText, encodeHexString(packet.getDestinationHash()));
    }

    //public void announceBaseDestination () {
    //    getBaseDestination().announce();
    //}

    private class QAnnounceHandler implements AnnounceHandler {
        String aspectFilter;

        QAnnounceHandler(String aspectFilter) {
            this.aspectFilter = aspectFilter;
        }

        QAnnounceHandler() {
            this.aspectFilter = "qortal.core";
        }

        @Override
        public String getAspectFilter() {
            //return "qortal.core";
            return this.aspectFilter;
        }

        @Override
        @Synchronized
        public void receivedAnnounce(byte[] destinationHash, Identity announcedIdentity, byte[] appData) {
            var peerExists = false;
            var activePeerCount = 0; 
            //var network = Network.getInstance();

            log.info("Received an announce from {}", encodeHexString(destinationHash));

            if (nonNull(appData)) {
                log.debug("The announce contained the following app data: {}", new String(appData, UTF_8));
            }

            // add to peer list if we can use more peers
            //synchronized (this) {
            var lps =  RNS.getInstance().getImmutableLinkedPeers();
            for (ReticulumPeer p: lps) {
                var pl = p.getPeerLink();
                if ((nonNull(pl) && (pl.getStatus() == ACTIVE))) {
                    activePeerCount = activePeerCount + 1;
                }
            }
            if (activePeerCount < MAX_PEERS) {
                for (ReticulumPeer p: lps) {
                    if (Arrays.equals(p.getDestinationHash(), destinationHash)) {
                        log.info("QAnnounceHandler - peer exists - found peer matching destinationHash");
                        if (nonNull(p.getPeerLink())) {
                            log.info("peer link: {}, status: {}",
                                    encodeHexString(p.getPeerLink().getLinkId()), p.getPeerLink().getStatus());
                        }
                        peerExists = true;
                        if (p.getPeerLink().getStatus() != ACTIVE) {
                            p.getOrInitPeerLink();
                        }
                        break;
                    } else {
                        if (nonNull(p.getPeerLink())) {
                            log.info("QAnnounceHandler - other peer - link: {}, status: {}",
                                    encodeHexString(p.getPeerLink().getLinkId()), p.getPeerLink().getStatus());
                            if (p.getPeerLink().getStatus() == CLOSED) {
                                // mark peer for deletion on nexe pruning
                                p.setDeleteMe(true);
                            }
                        } else {
                            log.info("QAnnounceHandler - peer link is null");
                        }
                    }
                }
                if (!peerExists) {
                    ReticulumPeer newPeer = getNewPeer(destinationHash, announcedIdentity);
                    addLinkedPeer(newPeer);
                    //network.addConnectedPeer(newPeer);
                    log.info("added new {} ReticulumPeer, destinationHash: {}",
                            newPeer.getPeerAspect(), encodeHexString(destinationHash));
                }
            }
        }

        private ReticulumPeer getNewPeer(byte[] destinationHash, Identity announcedIdentity) {
            ReticulumPeer newPeer = new ReticulumPeer(destinationHash);
            newPeer.setServerIdentity(announcedIdentity);
            newPeer.setIsInitiator(true);
            if (getAspectFilter() == "qortal.qdn") {
                // data peer
                newPeer.setPeerAspect(RNSCommon.PeerAspect.DATA);
                newPeer.setIsDataPeer(true);
            } else {
                // core peer
                newPeer.setPeerAspect(RNSCommon.PeerAspect.BASE);
                newPeer.setIsDataPeer(false);
            }
            newPeer.setMessageMagic(getMessageMagic());
            return newPeer;
        }
    }

    private static class SingletonContainer {
        private static final RNS INSTANCE = new RNS();
    }

    public static RNS getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public List<ReticulumPeer> getActiveImmutableLinkedPeers() {
        List<ReticulumPeer> activePeers = Collections.synchronizedList(new ArrayList<>());
        for (ReticulumPeer p: this.immutableLinkedPeers) {
            if (nonNull(p.getPeerLink()) && (p.getPeerLink().getStatus() == ACTIVE)) {
                activePeers.add(p);
            }
        }
        return activePeers;
    }

    // note: we already have a lobok getter for this
    //public List<ReticulumPeer> getImmutableLinkedPeers() {
    //    return this.immutableLinkedPeers;
    //}

    public void addLinkedPeer(ReticulumPeer peer) {
        this.linkedPeers.add(peer);
        this.immutableLinkedPeers = List.copyOf(this.linkedPeers); // thread safe
        //var network = Network.getInstance();
        //network.addHandshakedPeer(peer);
    }

    public void removePeer(ReticulumPeer peer) {
        if (peer.isInitiator) {
            removeLinkedPeer(peer);
        } else {
            removeIncomingPeer(peer);
        }
    }

    public void removeLinkedPeer(ReticulumPeer peer) {
        //if (nonNull(peer.getPeerBuffer())) {
        //    peer.getPeerBuffer().close();
        //}
        if (nonNull(peer.getPeerLink())) {
            peer.getPeerLink().teardown();
        }
        var p = this.linkedPeers.remove(this.linkedPeers.indexOf(peer)); // thread safe
        this.immutableLinkedPeers = List.copyOf(this.linkedPeers);
        // TODO: which list in network do we add ACTIVE ReticulumPeer ?
        //var network = Network.getInstance();
        //network.removeHandshakedPeer(peer);
    }

    // note: we already have a lobok getter for this
    //public List<ReticulumPeer> getLinkedPeers() {
    //    //synchronized(this.linkedPeers) {
    //        //return new ArrayList<>(this.linkedPeers);
    //        return this.linkedPeers;
    //    //}
    //}

    public void addIncomingPeer(ReticulumPeer peer) {
        this.incomingPeers.add(peer);
        this.immutableIncomingPeers = List.copyOf(this.incomingPeers);
    }

    public void removeIncomingPeer(ReticulumPeer peer) {
        if (nonNull(peer.getPeerLink())) {
            peer.getPeerLink().teardown();
        }
        var p = this.incomingPeers.remove(this.incomingPeers.indexOf(peer));
        this.immutableIncomingPeers = List.copyOf(this.incomingPeers);
    }

    // note: we already have a lobok getter for this
    //public List<ReticulumPeer> getIncomingPeers() {
    //    return this.incomingPeers;
    //}
    //public List<ReticulumPeer> getImmutableIncomingPeers() {
    //    return this.immutableIncomingPeers;
    //}

    // TODO, methods for: getAvailablePeer

    private Boolean isUnreachable(ReticulumPeer peer) {
        var result = peer.getDeleteMe();
        var now = Instant.now();
        var peerLastAccessTimestamp = peer.getLastAccessTimestamp();
        if (peerLastAccessTimestamp.isBefore(now.minusMillis(LINK_UNREACHABLE_TIMEOUT))) {
            result = true;
        }
        return result;
    }

    public void peerMisbehaved(Peer peer) {
        try {
            if (Class.forName("org.qortal.network.ReticulumPeer").isInstance(peer)) {
                PeerData peerData = peer.getPeerData();
                peerData.setLastMisbehaved(NTP.getTime());
            }
        } catch (ClassNotFoundException e) {
            log.error("class 'ReticulumPeer' not found", e);
        }

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

    public List<ReticulumPeer> getNonActiveIncomingPeers() {
        var ips = getIncomingPeers();
        List<ReticulumPeer> result = Collections.synchronizedList(new ArrayList<>());
        Link pl;
        for (ReticulumPeer p: ips) {
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
        // prune initiator peers
        //var peerList = getImmutableLinkedPeers();
        Link pLink;
        List<ReticulumPeer> initiatorPeerList = getImmutableLinkedPeers();
        List<ReticulumPeer> initiatorActivePeerList = getActiveImmutableLinkedPeers();
        List<ReticulumPeer> incomingPeerList = getImmutableIncomingPeers();
        int numActiveIncomingPeers = incomingPeerList.size() - getNonActiveIncomingPeers().size();
        List<PeerData> allKnownReticulumPeers = new ArrayList<>();
        //var network = Network.getInstance();
        log.info("number of links (linkedPeers (active) / incomingPeers (active) before prunig: {} ({}), {} ({})",
                initiatorPeerList.size(), getActiveImmutableLinkedPeers().size(),
                incomingPeerList.size(), numActiveIncomingPeers);
        for (ReticulumPeer p: initiatorActivePeerList) {
            //pLink = p.getOrInitPeerLink();
            p.pingRemote();
        }
        for (ReticulumPeer p : initiatorPeerList) {
            pLink = p.getPeerLink();
            if (nonNull(pLink)) {
                if (p.getPeerTimedOut()) {
                    // options: keep in case peer reconnects or remove => we'll remove it
                    removeLinkedPeer(p);
                    //network.removeHandshakedPeer(p);
                    continue;
                }
                if (pLink.getStatus() == ACTIVE) {
                    continue;
                }
                if ((pLink.getStatus() == CLOSED) || (p.getDeleteMe()))  {
                    removeLinkedPeer(p);
                    //network.removeHandshakedPeer(p);
                    continue;
                }
                if (pLink.getStatus() == PENDING) {
                    pLink.teardown();
                    removeLinkedPeer(p);
                    //network.removeOutboundHandshakedPeer(p);
                    continue;
                }
            }
        }
        // prune non-initiator peers
        List<ReticulumPeer> inaps = getNonActiveIncomingPeers();
        incomingPeerList = this.incomingPeers;
        for (ReticulumPeer p: incomingPeerList) {
            pLink = p.getOrInitPeerLink();
            if (nonNull(pLink) && (pLink.getStatus() == ACTIVE)) {
                // make false active links to timeout (and teardown in timeout callback)
                // note: actual removal of peer happens on the following pruning run.
                p.pingRemote();
            }
        }
        for (ReticulumPeer p: inaps) {
            pLink = p.getPeerLink();
            if (nonNull(pLink)) {
                // could be eg. PENDING
                pLink.teardown();
            }
            removeIncomingPeer(p);
        }
        initiatorPeerList = getImmutableLinkedPeers();
        initiatorActivePeerList = getActiveImmutableLinkedPeers();
        incomingPeerList = getImmutableIncomingPeers();
        numActiveIncomingPeers = incomingPeerList.size() - getNonActiveIncomingPeers().size();
        log.info("number of links (linkedPeers (active) / incomingPeers (active) after prunig: {} ({}), {} ({})",
                initiatorPeerList.size(), getActiveImmutableLinkedPeers().size(),
                incomingPeerList.size(), numActiveIncomingPeers);
        maybeAnnounce(getBaseDestination(), RNSCommon.PeerAspect.BASE);
        maybeAnnounce(getDataDestination(), RNSCommon.PeerAspect.DATA);
    }

    public void maybeAnnounce(Destination d, RNSCommon.PeerAspect pa) {
        var activePeers = getActiveImmutableLinkedPeers();
        int corePeerCount = 0;
        int dataPeerCount = 0;
        for (Peer p: activePeers) {
            if (p.isDataPeer()) {
                dataPeerCount++;
            } else {
                corePeerCount++;
            }
        }
        if ((corePeerCount <= MIN_DESIRED_CORE_PEERS) && (pa == RNSCommon.PeerAspect.BASE)) {
            log.info("Active core peers ({}) <= desired core peers ({}). Announcing", corePeerCount, MIN_DESIRED_CORE_PEERS);
            d.announce();
        }
        if ((dataPeerCount <= MIN_DESIRED_DATA_PEERS) && (pa == RNSCommon.PeerAspect.DATA)) {
            log.info("Active qdn peers ({}) <= desired data peers ({}). Announcing", dataPeerCount, MIN_DESIRED_CORE_PEERS);
            d.announce();
        }
    }

    /**
     * Helper methods
     */

    public List<PeerData> getAllKnownPeers() {
        return getImmutableIncomingPeers().stream()
                .map(ReticulumPeer::getPeerData)
                .collect(Collectors.toList());
    }

    public List<PeerData> getAllKnownCorePeers() {
        return getImmutableIncomingPeers().stream()
                .filter(p -> p.isDataPeer())
                .map(ReticulumPeer::getPeerData)
                .collect(Collectors.toList());
    }

    public List<PeerData> getAllKnownDataPeers() {
        return getImmutableIncomingPeers().stream()
                .filter(p -> !p.isDataPeer())
                .map(ReticulumPeer::getPeerData)
                .collect(Collectors.toList());
    }

    public ReticulumPeer findPeerByLink(Link link) {
        List<ReticulumPeer> lps =  RNS.getInstance().getImmutableLinkedPeers();
        ReticulumPeer peer = null;
        for (ReticulumPeer p : lps) {
            var pLink = p.getPeerLink();
            if (nonNull(pLink)) {
                if (Arrays.equals(pLink.getDestination().getHash(),link.getDestination().getHash())) {
                    log.info("found peer matching destinationHash: {}", encodeHexString(link.getDestination().getHash()));
                    peer = p;
                    break;
                }
            }
        }
        return peer;
    }

    public ReticulumPeer findPeerByDestinationHash(byte[] dhash) {
        List<ReticulumPeer> lps =  RNS.getInstance().getImmutableLinkedPeers();
        ReticulumPeer peer = null;
        for (ReticulumPeer p : lps) {
            if (Arrays.equals(p.getDestinationHash(), dhash)) {
                log.info("found peer matching destinationHash: {}", encodeHexString(dhash));
                peer = p;
                break;
            }
        }
        return peer;
    }

    //public void removePeer(ReticulumPeer peer) {
    //    List<ReticulumPeer> peerList = this.linkedPeers;
    //    if (nonNull(peer)) {
    //        peerList.remove(peer);
    //    }
    //}

    public byte[] getMessageMagic() {
        return Settings.getInstance().isTestNet() ? TESTNET_MESSAGE_MAGIC : MAINNET_MESSAGE_MAGIC;
    }

    public String getOurNodeId() {
        return this.serverIdentity.toString();
    }

    protected byte[] getOurPublicKey() {
        return this.serverIdentity.getPublicKey();
    }

    // Network methods Reticulum implementation

    /** Builds either (legacy) HeightV2Message or (newer) BlockSummariesV2Message, depending on peer version.
     *
     *  @return Message, or null if DataException was thrown.
     */
    public Message buildHeightOrChainTipInfo(ReticulumPeer peer) {
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

