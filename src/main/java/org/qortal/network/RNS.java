package org.qortal.network;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.ProofStrategy;
import io.reticulum.identity.Identity;
import io.reticulum.identity.IdentityKnownDestination;
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
import static io.reticulum.utils.DestinationUtils.hashFromNameAndIdentity;
//import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.constant.ReticulumConstant.CONFIG_FILE_NAME;
import lombok.Data;
//import lombok.Setter;
//import lombok.Getter;
import lombok.Synchronized;

//import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.qortal.network.message.*;
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
import java.nio.channels.SelectionKey;

import static java.nio.charset.StandardCharsets.UTF_8;
//import static java.util.Objects.isNull;
//import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
//import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

import java.io.File;
import java.util.*;
import java.util.HashSet;
//import java.util.Random;
//import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
//import java.util.concurrent.locks.Lock;
//import java.util.concurrent.locks.ReentrantLock;
//import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
//import java.net.InetAddress;
//import java.net.UnknownHostException;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import org.qortal.utils.ExecuteProduceConsume;
//import org.qortal.utils.ExecuteProduceConsume.StatsSnapshot;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;
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

    //private static RNS instance;
    Reticulum reticulum;
    //private static final String APP_NAME = "qortal";
    static final String APP_NAME = Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_APP_NAME: RNSCommon.MAINNET_APP_NAME;
    static final Integer TARGET_PORT = Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_IF_TCP_PORT: RNSCommon.MAINNET_IF_TCP_PORT;
    //static final String defaultConfigPath = ".reticulum"; // if empty will look in Reticulums default paths
    static final String defaultConfigPath = Settings.getInstance().isTestNet() ? RNSCommon.defaultRNSConfigPathTestnet: RNSCommon.defaultRNSConfigPath;
    static final String CORE_ASPECT = "qortal.core";
    static final String QDN_ASPECT  = "qortal.qdn";
    private final int MAX_PEERS = Settings.getInstance().getReticulumMaxPeers();
    private final int MIN_DESIRED_CORE_PEERS = Settings.getInstance().getReticulumMinDesiredCorePeers();
    private final int MIN_DESIRED_DATA_PEERS = Settings.getInstance().getReticulumMinDesiredDataPeers();
    // How long [ms] between pruning of peers
	private long PRUNE_INTERVAL = 1 * 64 * 1000L; // ms;
    
    public Identity serverIdentity;
    public Destination baseDestination;
    public Destination dataDestination;
    private volatile boolean isShuttingDown = false;
    private volatile boolean meshStarted = false;

    // Confirmed-active peer destination hashes — only added when a peer's buffer is successfully
    // created (ACTIVE confirmed). Persisted to disk on shutdown so the next restart reconnects
    // immediately without waiting for announces. Never includes transient/failed-only peers.
    private final Set<String> knownPeerHashes = Collections.synchronizedSet(new HashSet<>());
    // Hashes loaded from disk on startup (may include stale entries from previous sessions).
    // Used alongside knownPeerHashes for path recovery. Not saved back directly; knownPeerHashes
    // (confirmed this session) is saved instead, which naturally drops stale entries over time.
    private final Set<String> loadedPeerHashes = Collections.synchronizedSet(new HashSet<>());
    private static final String KNOWN_PEERS_FILE = "known_peer_hashes.txt";

    // Tracks hashes of peers whose PENDING links were pruned as stuck (>60 s without establishing).
    // When a peer is unreachable, createLinkedPeerFromIdentity() creates a PENDING link that the
    // Reticulum library times out at ~75 s → expirePath() → 60-120 s cull → cascade.
    // After a stuck-PENDING failure or immediate send failure, we back off to requestPath() for
    // PENDING_FAILURE_BACKOFF_MS so the backbone can provide a fresh announce path.
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingLinkFailureMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PENDING_FAILURE_BACKOFF_MS = 60_000L; // 60s; cull is now fast so 5min is too conservative

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

    /** Produces Connect tasks for the baseDestination and submits to worker pool. */
    private Thread rnsBaseThread;
    private Thread rnsDataThread;
    //private final ExecuteProduceConsume rnsEPC;
    private ExecutorService rnsWorkerPool;
    // Dedicated single-thread executors for announce and reconnect (BASE and DATA).
    // Root cause of prior failures: Transport.outbound() busy-waits on jobsLock (non-interruptibly).
    // A full table cull triggered by link drops holds jobsLock for 30-60s. With a shared pool,
    // each watchdog reset spawns a new thread, creating 20+ threads all spinning on jobsLock
    // simultaneously — massively worsening contention and making the cull take even longer.
    // Solution: one dedicated thread per operation (bounded queue=1). At most 2 threads ever
    // spin on jobsLock; tasks queue up naturally and complete when the cull finishes.
    private ExecutorService announceExecutor;
    private ExecutorService reconnectExecutor;
    private ExecutorService dataAnnounceExecutor;
    private ExecutorService dataReconnectExecutor;
    private static final long NETWORK_EPC_KEEPALIVE = 5L; // 1 second
    //private int totalThreadCount = 0;
    private final int reticulumMaxNetworkThreadPoolSize = Settings.getInstance().getReticulumMaxNetworkThreadPoolSize();

    // replicating a feature from Network.class needed in for base Message.java,
    // just in case the classic TCP/IP Networking is turned off.
    private static final byte[] MAINNET_MESSAGE_MAGIC = new byte[]{0x51, 0x4f, 0x52, 0x54}; // QORT
    private static final byte[] TESTNET_MESSAGE_MAGIC = new byte[]{0x71, 0x6f, 0x72, 0x54}; // qort
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
    /**
     * How often runBaseLoop() triggers maybeAnnounce() and path recovery, independent
     * of prunePeers(). This ensures announces fire even when the Controller scheduler is
     * slow/blocked (e.g., prunePeers() waiting on a lock inside the Reticulum library).
     */
    private static final long BASE_LOOP_ANNOUNCE_INTERVAL_MS = 30_000L; // 30 seconds
    private static final long BASE_LOOP_RECONNECT_INTERVAL_MS = 15_000L; // reconnect independently of announce
    private static final long ANNOUNCE_TASK_TIMEOUT_MS = 60_000L; // watchdog: reset stuck announce after 60s
    private static final long RECONNECT_TASK_TIMEOUT_MS = 45_000L; // watchdog: reset stuck reconnect after 45s
    private volatile long lastBaseLoopAnnounceMs = 0;
    private volatile long lastBaseLoopReconnectMs = 0;
    // Timestamp-based guards: 0 = no task running; non-zero = task started at that ms.
    // Timestamps (rather than booleans) allow a watchdog to force-reset after the timeout.
    // createLinkedPeerFromIdentity() and requestPath() call Reticulum transport code that can
    // acquire internal locks and block when the backbone degrades — both must run in the pool,
    // never inline on the runBaseLoop thread.
    private volatile long announceTaskStartedMs = 0L;
    private volatile long reconnectTaskStartedMs = 0L;
    private volatile java.util.concurrent.Future<?> announceTaskFuture = null;
    private volatile java.util.concurrent.Future<?> reconnectTaskFuture = null;
    // Circuit breaker: when both announce and reconnect tasks keep timing out consecutively,
    // the backbone TCP connection is likely in a bad state. Force-close it to trigger the
    // library's built-in auto-reconnect rather than spinning on a stuck jobsLock forever.
    private volatile int consecutiveStuckTasks = 0;
    private static final int BACKBONE_FORCE_RECONNECT_THRESHOLD = 2;

    // DATA loop timing — mirrors BASE, separate so DATA and BASE don't interfere
    private static final long DATA_LOOP_ANNOUNCE_INTERVAL_MS  = 30_000L;
    private static final long DATA_LOOP_RECONNECT_INTERVAL_MS = 15_000L;
    private volatile long lastDataLoopAnnounceMs  = 0;
    private volatile long lastDataLoopReconnectMs = 0;
    private volatile long dataAnnounceTaskStartedMs  = 0L;
    private volatile long dataReconnectTaskStartedMs = 0L;
    private volatile java.util.concurrent.Future<?> dataAnnounceTaskFuture  = null;
    private volatile java.util.concurrent.Future<?> dataReconnectTaskFuture = null;

    // Persisted DATA peer hashes — same semantics as knownPeerHashes / loadedPeerHashes for BASE
    private static final String KNOWN_DATA_PEERS_FILE = "known_data_peer_hashes.txt";
    private final Set<String> knownDataPeerHashes  = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> loadedDataPeerHashes = Collections.synchronizedSet(new HashSet<>());
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingDataLinkFailureMs =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Called by ReticulumPeer.linkClosed() to kick the announce/path-recovery cycle soon.
     *  Uses a 5s delay rather than 0 to avoid tight reconnect loops when links close rapidly
     *  (e.g., Channel "retry count exceeded" tears down a link, immediate re-announce creates
     *  a new link, new link also fails → rapid churn). */
    public void triggerImmediateAnnounce() {
        this.lastBaseLoopAnnounceMs = System.currentTimeMillis() - BASE_LOOP_ANNOUNCE_INTERVAL_MS + 5_000L;
    }

    /**
     * Called when a stuck task is interrupted. When the threshold is reached, force-closes
     * the backbone TCP channel so the library's built-in auto-reconnect fires, clearing any
     * jobsLock deadlock caused by a zombie-link cull cascade.
     */
    private void maybeForceBackboneReconnect() {
        if (consecutiveStuckTasks < BACKBONE_FORCE_RECONNECT_THRESHOLD) return;
        consecutiveStuckTasks = 0; // reset so we don't spam per-interval
        log.warn("runBaseLoop: {} consecutive stuck tasks — forcing backbone TCP reconnect to clear deadlock",
                BACKBONE_FORCE_RECONNECT_THRESHOLD);
        for (io.reticulum.interfaces.ConnectionInterface iface : Transport.getInstance().getInterfaces()) {
            if (iface instanceof io.reticulum.interfaces.backbone.BackboneClientInterface) {
                ((io.reticulum.interfaces.backbone.BackboneClientInterface) iface).forceReconnect();
            }
        }
    }

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
        //        Settings.getInstance().getMaxRNSNetworkThreadPoolSize(),
        var rnsThreadPriority = Settings.getInstance().getNetworkThreadPriority(); // default: 7
        ////// if possible one higher than NetworkThreadPriority
        ////if (rnsThreadPriority < 10) {
        ////    rnsThreadPriority++;
        ////}
        //ExecutorService RNSExecutor = new ThreadPoolExecutor(1,
        //        Settings.getInstance().getReticulumMaxNetworkThreadPoolSize(),  // we don't need many max threads
        //        NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
        //        new SynchronousQueue<Runnable>(),
        //        new NamedThreadFactory("RNS-EPC", rnsThreadPriority));
        //rnsEPC = new RNSProcessor(RNSExecutor);        // Worker pool: message handling only (MessageTask, ConnectTask). I/O runs on dedicated ioThread.
        this.rnsWorkerPool = new ThreadPoolExecutor(
                3, Settings.getInstance().getReticulumMaxNetworkThreadPoolSize(),
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("RNS-Worker", rnsThreadPriority));
        // Bounded queue(1): at most one task running + one queued. A rejected submission just
        // means the next interval will retry — no unbounded thread growth.
        this.announceExecutor = new ThreadPoolExecutor(1, 1,
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new NamedThreadFactory("RNS-Announce", rnsThreadPriority));
        this.reconnectExecutor = new ThreadPoolExecutor(1, 1,
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new NamedThreadFactory("RNS-Reconnect", rnsThreadPriority));
        this.dataAnnounceExecutor = new ThreadPoolExecutor(1, 1,
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new NamedThreadFactory("RNS-DataAnnounce", rnsThreadPriority));
        this.dataReconnectExecutor = new ThreadPoolExecutor(1, 1,
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new NamedThreadFactory("RNS-DataReconnect", rnsThreadPriority));
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
            log.debug("interface {}, length: {}", i.getInterfaceName(), i.getIfacSize());
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
        dataDestination.setProofStrategy(ProofStrategy.PROVE_ALL);
        dataDestination.setAcceptLinkRequests(true);

        baseDestination.setLinkEstablishedCallback(this::baseClientConnected);
        dataDestination.setLinkEstablishedCallback(this::dataClientConnected);
        //Transport.getInstance().registerAnnounceHandler(new QAnnounceHandler());
        Transport.getInstance().registerAnnounceHandler(new QAnnounceHandler("qortal.core"));
        Transport.getInstance().registerAnnounceHandler(new QAnnounceHandler("qortal.qdn"));
        log.debug("announceHandlers: {}", Transport.getInstance().getAnnounceHandlers());
        // Load peer hashes persisted from previous run so we can call requestPath() fast on restart.
        loadKnownPeerHashes();
        loadKnownDataPeerHashes();
        // do a first announce (across all configured interfaces)
        baseDestination.announce();
        log.info("Sent initial announce from {} ({})", encodeHexString(baseDestination.getHash()), baseDestination.getName());
        dataDestination.announce();
        log.info("Sent initial announce from {} ({})", encodeHexString(dataDestination.getHash()), dataDestination.getName());
        // Seed loop announce timers. On restart (non-empty loaded hashes) fire path requests
        // at t=15s; on first-ever start use the full 30s window.
        this.lastBaseLoopAnnounceMs = loadedPeerHashes.isEmpty()
                ? System.currentTimeMillis()
                : System.currentTimeMillis() - BASE_LOOP_ANNOUNCE_INTERVAL_MS + 15_000L;
        this.lastDataLoopAnnounceMs = loadedDataPeerHashes.isEmpty()
                ? System.currentTimeMillis()
                : System.currentTimeMillis() - DATA_LOOP_ANNOUNCE_INTERVAL_MS + 15_000L;

        // Start up "main" threads, one per destination / peer aspect.
        this.rnsBaseThread = new Thread(this::runBaseLoop, "rnsMesh-BASE");
        this.rnsBaseThread.setDaemon(true);
        this.rnsBaseThread.start();
        this.rnsDataThread = new Thread(this::runDataLoop, "rnsMesh-DATA");
        this.rnsDataThread.setDaemon(true);
        this.rnsDataThread.start();

        this.meshStarted = true;
        log.info("RNS mesh started, baseDestination: {}", encodeHexString(baseDestination.getHash()));
    }

    public boolean isMeshStarted() {
        return meshStarted;
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
        var reticulumBackboneGatewayServers = Arrays.stream(Settings.getInstance().getReticulumBackboneGatewayServers()).collect(Collectors.toList());
        reticulumTcpGatewayServers.remove(fqdn);
        reticulumBackboneGatewayServers.remove(fqdn);
        Map<String, Object> context = Maps.newHashMap();

        //log.info("fqdn: {}, reticulumTcpGatewayServers: {}", fqdn, reticulumTcpGatewayServers);

        if (Files.notExists(configFile) || Settings.getInstance().isReticulumRegenerateConfigOnRestart()) {
            try {
                // jinjava variables set in context:
                // * tcp_gateway_servers: list of nodes with a TCPServerInterface
                // * tcp_backbone_servers: list of nodes with a BackboneServerInterface
                // * num_client_interfaces: number of client interfaces to gateways be configured
                // * host_fqdn: host FQDN
                // * qortal_network_name: either "qortal" or "qortaltest" (from isTestnet)
                // * is_reticulum_gateway: one of the instances (Qortal core or RNS) has
                //                         at least one Gateway interface
                // * is_test_net: String "true" or "false" (from isTestNet)
                // * target_port: target port for TCPServerInterface or BackboneServerInterface (only)
                // * use_python_rns: use local shared python rnsd (has to provide a gateway interface)
                // * python_rns_if_port: rnsd TCPServerInterface port (if rnsd gateway is a TCPServerInterface)
                var jnj = new Jinjava();
                var reticulumTcpGateways = StringUtils.join(reticulumTcpGatewayServers, " ");
                var reticulumBackboneGateways = StringUtils.join(reticulumBackboneGatewayServers, " ");
                log.info("reticulumTcpGateways: {}, reticulumBackboneGateways", reticulumTcpGateways);
                context.put("tcp_gateway_servers",  reticulumTcpGateways);
                context.put("backbone_gateway_servers",  reticulumBackboneGateways);
                context.put("num_client_interfaces", reticulumDesiredClientInterfaces);
                context.put("host_fqdn", fqdn);
                String networkName = Settings.getInstance().getReticulumNetworkName();
                context.put("qortal_network_name", networkName.isEmpty() ? APP_NAME : networkName);
                context.put("target_port", TARGET_PORT);
                context.put("is_reticulum_gateway", isReticulumGateway ? "true" : "false");
                //context.put("is_test_net", Settings.getInstance().isTestNet() ? "true" : "false");
                context.put("use_python_rns", Settings.getInstance().getReticulumUsePythonRNS() ? "true" : "false");
                context.put("python_rns_if_port", Settings.getInstance().getReticulumPythonRNSGatewayPort());
                context.put("passphrase", Settings.getInstance().getReticulumPassphrase());

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

    //private Predicate<ReticulumPeer> isBasePeer = peer -> {
    //    return this.getActiveImmutableLinkedPeers().stream().anyMatch(p -> p.getPeerAspect() == RNSCommon.PeerAspect.BASE);
    //};

    // "main" loop for baseDestination (chain tasks)
    private void runBaseLoop() {
        while (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
            try {
                // Drain messages from both initiator peers (linkedPeers) and
                // non-initiator/incoming peers (incomingPeers) so that requests
                // received by either side are processed.
                final List<ReticulumPeer> peersThisRound = Stream.concat(
                        this.getActiveImmutableLinkedPeers().stream()
                                .filter(p -> p.getPeerAspect() == RNSCommon.PeerAspect.BASE),
                        this.getImmutableIncomingPeers().stream()
                                .filter(p -> p.getPeerAspect() == RNSCommon.PeerAspect.BASE)
                                .filter(p -> {
                                    var pl = p.getPeerLink();
                                    return nonNull(pl) && pl.getStatus() == ACTIVE;
                                })
                ).collect(Collectors.toList());

                final Long now = NTP.getTime();
                for (ReticulumPeer peer : peersThisRound) {
                    ExecuteProduceConsume.Task task;
                    while ((task = peer.getMessageTask(Peer.NETWORK)) != null) {
                        final ExecuteProduceConsume.Task t = task;
                        try {
                            rnsWorkerPool.execute(() -> {
                                try {
                                    t.perform();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } catch (Exception e) {
                                    log.warn("Reticulum worker task threw: {}", e.getMessage(), e);
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            log.warn("[{}] Reticulum worker pool rejected message task (pool full or shutting down)",
                                    peer.getPeerConnectionId());
                            break;
                        }
                    }

                    // Send keepalive ping if due (initiator peers only, every 55s)
                    ExecuteProduceConsume.Task pingTask = peer.getPingTask(now);
                    if (pingTask != null) {
                        final ExecuteProduceConsume.Task pt = pingTask;
                        try {
                            rnsWorkerPool.execute(() -> {
                                try {
                                    pt.perform();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } catch (Exception e) {
                                    log.warn("Reticulum ping task threw: {}", e.getMessage(), e);
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            log.warn("[{}] Reticulum worker pool rejected ping task", peer.getPeerConnectionId());
                        }
                    }
                }

                // Periodic announce — dedicated single-thread executor with bounded queue(1).
                // Transport.outbound() busy-waits on jobsLock (non-interruptibly); a table cull
                // triggered by link drops can hold jobsLock for 30-60s.  With a single thread,
                // at most 1 task spins on the lock at a time; a queued task runs as soon as the
                // running one completes.  Rejected = queue full = there is already one waiting.
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastBaseLoopAnnounceMs >= BASE_LOOP_ANNOUNCE_INTERVAL_MS) {
                    lastBaseLoopAnnounceMs = nowMs;
                    long taskStart = announceTaskStartedMs;
                    if (taskStart != 0 && (nowMs - taskStart > ANNOUNCE_TASK_TIMEOUT_MS)) {
                        log.warn("runBaseLoop: announce task running for {}s — interrupting stuck task",
                                (nowMs - taskStart) / 1000);
                        java.util.concurrent.Future<?> f = announceTaskFuture;
                        if (f != null && !f.isDone()) f.cancel(true);
                        ((ThreadPoolExecutor) announceExecutor).purge();
                        announceTaskStartedMs = 0L;
                        consecutiveStuckTasks++;
                        maybeForceBackboneReconnect();
                    }
                    if (announceTaskStartedMs == 0L) {
                        announceTaskStartedMs = nowMs;
                        try {
                            announceTaskFuture = announceExecutor.submit(() -> {
                                Thread.interrupted(); // clear any stale interrupt flag from prior cancel
                                try {
                                    maybeAnnounce(getBaseDestination(), RNSCommon.PeerAspect.BASE);
                                } catch (Exception e) {
                                    log.warn("Exception in base loop announce: {}", e.getMessage(), e);
                                } finally {
                                    // Reset counter only if watchdog didn't fire — watchdog sets
                                    // announceTaskStartedMs=0 before incrementing consecutiveStuckTasks,
                                    // so a non-zero value here means we completed without intervention.
                                    if (announceTaskStartedMs != 0L) {
                                        consecutiveStuckTasks = 0;
                                    }
                                    announceTaskStartedMs = 0L;
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            announceTaskStartedMs = 0L;
                        }
                    }
                }

                // Periodic path recovery — submitted to rnsWorkerPool so that createLinkedPeerFromIdentity()
                // and requestPath() (which call Reticulum transport code that acquires internal locks and can
                // block on backbone I/O) do not freeze the runBaseLoop thread.
                if (nowMs - lastBaseLoopReconnectMs >= BASE_LOOP_RECONNECT_INTERVAL_MS) {
                    lastBaseLoopReconnectMs = nowMs;
                    long rTaskStart = reconnectTaskStartedMs;
                    if (rTaskStart != 0 && (nowMs - rTaskStart > RECONNECT_TASK_TIMEOUT_MS)) {
                        log.warn("runBaseLoop: reconnect task running for {}s — interrupting stuck task",
                                (nowMs - rTaskStart) / 1000);
                        java.util.concurrent.Future<?> rf = reconnectTaskFuture;
                        if (rf != null && !rf.isDone()) rf.cancel(true);
                        ((ThreadPoolExecutor) reconnectExecutor).purge();
                        reconnectTaskStartedMs = 0L;
                        consecutiveStuckTasks++;
                        maybeForceBackboneReconnect();
                    }
                    if (reconnectTaskStartedMs == 0L) {
                        reconnectTaskStartedMs = nowMs;
                        final int activeLinked = getActiveImmutableLinkedPeers().size();
                        final List<ReticulumPeer> currentLinked = getImmutableLinkedPeers();
                        final Set<String> reconnectTargets = new HashSet<>(knownPeerHashes);
                        reconnectTargets.addAll(loadedPeerHashes);
                        try {
                            reconnectTaskFuture = reconnectExecutor.submit(() -> {
                                Thread.interrupted(); // clear any stale interrupt flag from prior cancel
                                try {
                                    // Log interface online status for diagnostics
                                    for (io.reticulum.interfaces.ConnectionInterface iface : Transport.getInstance().getInterfaces()) {
                                        log.info("Interface '{}' online={}", iface.getInterfaceName(), iface.isOnline());
                                    }
                                    if (activeLinked < MIN_DESIRED_CORE_PEERS && !reconnectTargets.isEmpty()) {
                                        log.info("Active linked peers {} < desired {} (base loop); requesting paths to {} known peers",
                                                activeLinked, MIN_DESIRED_CORE_PEERS, reconnectTargets.size());
                                        // When fully disconnected, limit outgoing link creation to 1 per cycle.
                                        // Creating all peers simultaneously floods jobsLock (each new Link() sends
                                        // a LINKREQUEST via outbound(Packet)) and starves announce/reconnect tasks.
                                        // The PENDING-failure backoff naturally rotates through peers across cycles.
                                        int outgoingLinksCreated = 0;
                                        for (String hashHex : reconnectTargets) {
                                            try {
                                                byte[] dhash = org.apache.commons.codec.binary.Hex.decodeHex(hashHex);
                                                // Skip peers already tracked (PENDING or ACTIVE) as initiator links
                                                boolean tracked = currentLinked.stream()
                                                        .anyMatch(p -> Arrays.equals(p.getDestinationHash(), dhash));
                                                if (tracked) continue;
                                                // Skip peers already ACTIVE as incoming — broadcast() covers them,
                                                // and creating a duplicate outgoing link doubles the Channel teardown
                                                // rate, driving more expirePath() culls and accumulating spurious
                                                // incoming connections on the remote end.
                                                boolean alreadyIncoming = getImmutableIncomingPeers().stream()
                                                        .filter(p -> {
                                                            Link pl = p.getPeerLink();
                                                            return nonNull(pl) && pl.getStatus() == ACTIVE;
                                                        })
                                                        .anyMatch(p -> {
                                                            Identity remoteId = p.getServerIdentity();
                                                            if (remoteId == null) return false;
                                                            return Arrays.equals(hashFromNameAndIdentity(CORE_ASPECT, remoteId), dhash);
                                                        });
                                                if (alreadyIncoming) continue;
                                                // hopsTo() is a ConcurrentHashMap.get() — no lock, always safe.
                                                int hops = Transport.getInstance().hopsTo(dhash);
                                                log.info("Path to {}: hops={}", hashHex,
                                                        hops == io.reticulum.constant.TransportConstant.PATHFINDER_M ? "unknown" : hops);
                                                // Hybrid reconnect strategy:
                                                //
                                                // createLinkedPeerFromIdentity() creates an outgoing link immediately
                                                // from the locally-cached identity. This is how initial connections form.
                                                // If the LINKREQUEST send fails (no route in pathTable), the link is
                                                // CLOSED immediately and we record pendingLinkFailureMs right there.
                                                // If the peer is reachable but slow, the RNS.java pruner removes the
                                                // PENDING link after 60s and records pendingLinkFailureMs.
                                                // Either way we back off to requestPath() for PENDING_FAILURE_BACKOFF_MS
                                                // so the backbone can provide a fresh path before we retry.
                                                //
                                                // requestPath() sends a single path-request packet (no PENDING link).
                                                // If the backbone responds with a fresh announce, QAnnounceHandler creates
                                                // the link. If the peer is unreachable nothing happens: no cull, no cascade.
                                                //
                                                // Strategy: use createLinkedPeerFromIdentity() for peers without a recent
                                                // PENDING failure; use requestPath() for peers in the backoff window.
                                                // When activeLinked==0, limit outgoing link creation to 1 per cycle to
                                                // avoid flooding jobsLock; requestPath breaks the 0/0 deadlock for others.
                                                long lastFailure = pendingLinkFailureMs.getOrDefault(hashHex, 0L);
                                                boolean recentlyFailed = (System.currentTimeMillis() - lastFailure) < PENDING_FAILURE_BACKOFF_MS;
                                                boolean outgoingSlotFree = activeLinked > 0 || outgoingLinksCreated == 0;
                                                Identity cachedIdentity = (!recentlyFailed && outgoingSlotFree)
                                                        ? IdentityKnownDestination.recall(dhash) : null;
                                                if (cachedIdentity != null) {
                                                    log.info("Proactively connecting to {} via cached identity", hashHex);
                                                    createLinkedPeerFromIdentity(dhash, cachedIdentity);
                                                    outgoingLinksCreated++;
                                                } else {
                                                    if (recentlyFailed) {
                                                        log.info("Backing off to requestPath for {} (recent PENDING failure)", hashHex);
                                                    } else if (!outgoingSlotFree) {
                                                        log.info("requestPath for {} (outgoing slot in use)", hashHex);
                                                    } else {
                                                        log.info("requestPath for {} (no cached identity)", hashHex);
                                                    }
                                                    Transport.getInstance().requestPath(dhash);
                                                }
                                            } catch (Exception e) {
                                                log.warn("Path request/reconnect failed for {}: {}", hashHex, e.getMessage());
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("Exception in base loop reconnect: {}", e.getMessage(), e);
                                } finally {
                                    // Reset counter only if watchdog didn't fire (same logic as announce task).
                                    if (reconnectTaskStartedMs != 0L) {
                                        consecutiveStuckTasks = 0;
                                    }
                                    reconnectTaskStartedMs = 0L;
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            reconnectTaskStartedMs = 0L;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("runBaseLoop: unexpected exception — loop continues", e);
            }

            // Sleep unconditionally at the end of every cycle to cap the loop at ~100 iterations/sec.
            if (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.debug("Mesh loop for destination {} exiting.", baseDestination.getName());
    }

    /** Kick the DATA announce/reconnect cycle within ~5s (mirrors triggerImmediateAnnounce()). */
    public void triggerImmediateDataAnnounce() {
        this.lastDataLoopAnnounceMs = System.currentTimeMillis() - DATA_LOOP_ANNOUNCE_INTERVAL_MS + 5_000L;
    }

    // "main" loop for dataDestination (QDN tasks) — mirrors runBaseLoop() for DATA aspect
    private void runDataLoop() {
        while (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
            try {
                final List<ReticulumPeer> peersThisRound = Stream.concat(
                        this.getActiveImmutableLinkedPeers().stream()
                                .filter(p -> p.getPeerAspect() == RNSCommon.PeerAspect.DATA),
                        this.getImmutableIncomingPeers().stream()
                                .filter(p -> p.getPeerAspect() == RNSCommon.PeerAspect.DATA)
                                .filter(p -> {
                                    var pl = p.getPeerLink();
                                    return nonNull(pl) && pl.getStatus() == ACTIVE;
                                })
                ).collect(Collectors.toList());

                final Long now = NTP.getTime();
                for (ReticulumPeer peer : peersThisRound) {
                    ExecuteProduceConsume.Task task;
                    // DATA messages are routed to NetworkData.onMessage() via MessageTask(NETWORKDATA)
                    while ((task = peer.getMessageTask(Peer.NETWORKDATA)) != null) {
                        final ExecuteProduceConsume.Task t = task;
                        try {
                            rnsWorkerPool.execute(() -> {
                                try {
                                    t.perform();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } catch (Exception e) {
                                    log.warn("Reticulum DATA worker task threw: {}", e.getMessage(), e);
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            log.warn("[{}] Reticulum DATA worker pool rejected message task", peer.getPeerConnectionId());
                            break;
                        }
                    }

                    ExecuteProduceConsume.Task pingTask = peer.getPingTask(now);
                    if (pingTask != null) {
                        final ExecuteProduceConsume.Task pt = pingTask;
                        try {
                            rnsWorkerPool.execute(() -> {
                                try {
                                    pt.perform();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } catch (Exception e) {
                                    log.warn("Reticulum DATA ping task threw: {}", e.getMessage(), e);
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            log.warn("[{}] Reticulum DATA worker pool rejected ping task", peer.getPeerConnectionId());
                        }
                    }
                }

                long nowMs = System.currentTimeMillis();

                // Periodic DATA announce
                if (nowMs - lastDataLoopAnnounceMs >= DATA_LOOP_ANNOUNCE_INTERVAL_MS) {
                    lastDataLoopAnnounceMs = nowMs;
                    if (dataAnnounceTaskStartedMs == 0L) {
                        dataAnnounceTaskStartedMs = nowMs;
                        try {
                            dataAnnounceTaskFuture = dataAnnounceExecutor.submit(() -> {
                                Thread.interrupted();
                                try {
                                    maybeAnnounce(dataDestination, RNSCommon.PeerAspect.DATA);
                                } catch (Exception e) {
                                    log.warn("Exception in data loop announce: {}", e.getMessage(), e);
                                } finally {
                                    if (dataAnnounceTaskStartedMs != 0L) {
                                        dataAnnounceTaskStartedMs = 0L;
                                    }
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            dataAnnounceTaskStartedMs = 0L;
                        }
                    }
                }

                // Periodic DATA peer reconnect
                if (nowMs - lastDataLoopReconnectMs >= DATA_LOOP_RECONNECT_INTERVAL_MS) {
                    lastDataLoopReconnectMs = nowMs;
                    if (dataReconnectTaskStartedMs == 0L) {
                        dataReconnectTaskStartedMs = nowMs;
                        final int activeData = (int) getActiveImmutableLinkedPeers().stream()
                                .filter(p -> p.getPeerAspect() == RNSCommon.PeerAspect.DATA).count();
                        final List<ReticulumPeer> currentDataLinked = getImmutableLinkedPeers().stream()
                                .filter(p -> p.getPeerAspect() == RNSCommon.PeerAspect.DATA)
                                .collect(Collectors.toList());
                        final Set<String> dataTargets = new HashSet<>(knownDataPeerHashes);
                        dataTargets.addAll(loadedDataPeerHashes);
                        try {
                            dataReconnectTaskFuture = dataReconnectExecutor.submit(() -> {
                                Thread.interrupted();
                                try {
                                    if (activeData < MIN_DESIRED_DATA_PEERS && !dataTargets.isEmpty()) {
                                        log.info("Active DATA peers {} < desired {} (data loop); requesting paths to {} known peers",
                                                activeData, MIN_DESIRED_DATA_PEERS, dataTargets.size());
                                        for (String hashHex : dataTargets) {
                                            try {
                                                byte[] dhash = org.apache.commons.codec.binary.Hex.decodeHex(hashHex);
                                                boolean tracked = currentDataLinked.stream()
                                                        .anyMatch(p -> Arrays.equals(p.getDestinationHash(), dhash));
                                                if (tracked) continue;
                                                long lastFailure = pendingDataLinkFailureMs.getOrDefault(hashHex, 0L);
                                                boolean recentlyFailed = (System.currentTimeMillis() - lastFailure) < PENDING_FAILURE_BACKOFF_MS;
                                                Identity cachedIdentity = recentlyFailed ? null
                                                        : IdentityKnownDestination.recall(dhash);
                                                if (cachedIdentity != null) {
                                                    log.info("DATA: proactively connecting to {} via cached identity", hashHex);
                                                    createLinkedDataPeerFromIdentity(dhash, cachedIdentity);
                                                } else {
                                                    if (recentlyFailed)
                                                        log.info("DATA: backing off to requestPath for {} (recent PENDING failure)", hashHex);
                                                    else
                                                        log.info("DATA: requestPath for {} (no cached identity)", hashHex);
                                                    Transport.getInstance().requestPath(dhash);
                                                }
                                            } catch (Exception e) {
                                                log.warn("DATA path request/reconnect failed for {}: {}", hashHex, e.getMessage());
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("Exception in data loop reconnect: {}", e.getMessage(), e);
                                } finally {
                                    if (dataReconnectTaskStartedMs != 0L) {
                                        dataReconnectTaskStartedMs = 0L;
                                    }
                                }
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            dataReconnectTaskStartedMs = 0L;
                        }
                    }
                }

            } catch (Exception e) {
                log.error("runDataLoop: unexpected exception — loop continues", e);
            }

            if (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.debug("Data mesh loop for destination {} exiting.", dataDestination.getName());
    }

    public void broadcast(Function<ReticulumPeer, Message> peerMessageBuilder) {
        List<ReticulumPeer> allPeers = Stream.concat(
                getActiveImmutableLinkedPeers().stream(),
                getImmutableIncomingPeers().stream()
                        .filter(p -> nonNull(p.getPeerLink()) && p.getPeerLink().getStatus() == ACTIVE)
        ).collect(Collectors.toList());

        for (ReticulumPeer peer : allPeers) {
            if (this.isShuttingDown) {
                return;
            }

            Message message = peerMessageBuilder.apply(peer);

            if (message == null) {
                continue;
            }

            peer.sendMessage(message);
        }
    }

    //public void broadcastOurChain() {
    //    BlockData latestBlockData = Controller.getInstance().getChainTip();
    //    int latestHeight = latestBlockData.getHeight();
    //
    //    log.debug("broadcastOurChain latestHeight: {}", latestHeight);
    //    try (final Repository repository = RepositoryManager.getRepository()) {
    //        List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
    //        Message latestBlockSummariesMessage = new BlockSummariesV2Message(latestBlockSummaries);
    //
    //        broadcast(broadcastPeer -> latestBlockSummariesMessage);
    //    } catch (DataException e) {
    //        log.warn("Couldn't broadcast our chain tip info", e);
    //    }
    //}
    //
    //public Message buildNewTransactionMessage(ReticulumPeer peer, TransactionData transactionData) {
    //    // In V2 we send out transaction signature only and peers can decide whether to request the full transaction
    //    return new TransactionSignaturesMessage(Collections.singletonList(transactionData.getSignature()));
    //}
    //
    //public Message buildGetUnconfirmedTransactionsMessage(ReticulumPeer peer) {
    //    return new GetUnconfirmedTransactionsMessage();
    //}

    public void shutdown() {
        this.isShuttingDown = true;
        saveKnownPeerHashes();
        saveKnownDataPeerHashes();
        log.info("shutting down Reticulum");
        baseDestination.setProofStrategy(ProofStrategy.PROVE_NONE);
        dataDestination.setProofStrategy(ProofStrategy.PROVE_NONE);

        if (this.rnsBaseThread != null && this.rnsBaseThread.isAlive()) {
            this.rnsBaseThread.interrupt();
            try {
                this.rnsBaseThread.join(5000);
                if (this.rnsBaseThread.isAlive())
                    log.warn("RNS base thread did not terminate in time");
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for RNS base thread");
            }
        }
        if (this.rnsDataThread != null && this.rnsDataThread.isAlive()) {
            this.rnsDataThread.interrupt();
            try {
                this.rnsDataThread.join(5000);
                if (this.rnsDataThread.isAlive())
                    log.warn("RNS data thread did not terminate in time");
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for RNS data thread");
            }
        }
        
        // gracefully close links of peers that point to us
        for (ReticulumPeer p: incomingPeers) {
            var pl = p.getPeerLink();
            if (nonNull(pl) & (pl.getStatus() == ACTIVE)) {
                p.sendCloseToRemote(pl);
            }
        }
        log.debug("Shutdown of incomingPeers completed");
        // Disconnect peers gracefully and terminate Reticulum
        for (ReticulumPeer p: linkedPeers) {
            log.info("shutting down peer: {}", encodeHexString(p.getDestinationHash()));
            p.shutdown();
            //try {
            //    TimeUnit.MILLISECONDS.sleep(200); // allow for peers to disconnect gracefully
            //} catch (InterruptedException e) {
            //    log.error("exception: ", e);
            //}
        }
        log.debug("Shutdown of linkedPeers completed");
        // Shut down worker pool so its threads don't prevent JVM exit
        this.rnsWorkerPool.shutdown();
        this.announceExecutor.shutdown();
        this.reconnectExecutor.shutdown();
        this.dataAnnounceExecutor.shutdown();
        this.dataReconnectExecutor.shutdown();
        try {
            if (!this.rnsWorkerPool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
                this.rnsWorkerPool.shutdownNow();
            if (!this.announceExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
                this.announceExecutor.shutdownNow();
            if (!this.reconnectExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
                this.reconnectExecutor.shutdownNow();
            if (!this.dataAnnounceExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
                this.dataAnnounceExecutor.shutdownNow();
            if (!this.dataReconnectExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
                this.dataReconnectExecutor.shutdownNow();
        } catch (InterruptedException e) {
            this.rnsWorkerPool.shutdownNow();
            this.announceExecutor.shutdownNow();
            this.reconnectExecutor.shutdownNow();
            this.dataAnnounceExecutor.shutdownNow();
            this.dataReconnectExecutor.shutdownNow();
        }

        // exitHandler() can block indefinitely if a zombie link's channel holds a lock
        // (library ABBA deadlock). Run it on a daemon thread with a timeout so the JVM
        // can exit even if the library gets stuck.
        Thread exitThread = new Thread(reticulum::exitHandler, "rns-exit");
        exitThread.setDaemon(true);
        exitThread.start();
        try {
            exitThread.join(5000);
            if (exitThread.isAlive()) {
                log.warn("exitHandler did not complete in 5s — zombie channel likely; forcing shutdown");
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for exitHandler");
        }
        log.info("shutdown of Reticulum complete");
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
        // createPeerBuffer() rather than getOrInitPeerBuffer() — avoids synchronized(link)
        // contention on the broadcast path (see ReticulumPeer.createPeerBuffer javadoc).
        newPeer.createPeerBuffer();
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
        newPeer.createPeerBuffer();
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
            this.aspectFilter = new String(aspectFilter);
        }

        QAnnounceHandler() {
            this.aspectFilter = new String("qortal.core");
        }

        @Override
        public String getAspectFilter() {
            // Return null so Transport fires this handler for ALL received announces.
            // Transport's hash-based filter (hashFromNameAndIdentity(aspectFilter, recall(hash)))
            // fails whenever recall() returns null for the incoming announce identity — the
            // computed hash (no identity component) never matches the actual destination hash,
            // so receivedAnnounce() is never called. We filter by name inside the handler instead.
            return null;
        }

        @Override
        @Synchronized
        public void receivedAnnounce(byte[] destinationHash,
                                     Identity announcedIdentity,
                                     byte[] appData,
                                     byte[] announcePacketHash,
                                     boolean isPathResponse) {
            var peerExists = false;
            var activePeerCount = 0; 
            //var network = Network.getInstance();

            log.debug("Received an announce from {}", encodeHexString(destinationHash));

            // Since getAspectFilter() returns null (match-all), we must verify manually.
            // Recompute the expected hash for "qortal.core" + the announced identity and
            // compare; skip announces that belong to other apps/aspects.
            var expectedHash = hashFromNameAndIdentity(this.aspectFilter, announcedIdentity);
            if (!Arrays.equals(destinationHash, expectedHash)) {
                log.debug("Announce hash mismatch — identity={}, dest={}, expected={}",
                        announcedIdentity != null ? encodeHexString(announcedIdentity.getHash()) : "null",
                        encodeHexString(destinationHash),
                        encodeHexString(expectedHash));
                return;
            }

            if (nonNull(appData)) {
                log.debug("The announce contained the following app data: {}", new String(appData, UTF_8));
            }

            // add to peer list if we can use more peers
            boolean isDataAspect = "qortal.qdn".equals(this.aspectFilter);
            int peerLimit = isDataAspect ? MIN_DESIRED_DATA_PEERS : MIN_DESIRED_CORE_PEERS;
            RNSCommon.PeerAspect matchAspect = isDataAspect ? RNSCommon.PeerAspect.DATA : RNSCommon.PeerAspect.BASE;
            var lps =  RNS.getInstance().getImmutableLinkedPeers();
            for (ReticulumPeer p: lps) {
                var pl = p.getPeerLink();
                if (nonNull(pl) && pl.getStatus() == ACTIVE && p.getPeerAspect() == matchAspect) {
                    activePeerCount = activePeerCount + 1;
                }
            }
            if (activePeerCount < peerLimit) {
                for (ReticulumPeer p: lps) {
                    if (Arrays.equals(p.getDestinationHash(), destinationHash)) {
                        log.info("QAnnounceHandler - peer exists - found peer matching destinationHash");
                        if (nonNull(p.getPeerLink())) {
                            log.info("peer link: {}, status: {}",
                                    encodeHexString(p.getPeerLink().getLinkId()), p.getPeerLink().getStatus());
                        }
                        peerExists = true;
                        if (nonNull(p.getPeerLink()) && (p.getPeerLink().getStatus() == CLOSED)) {
                            // Only re-initiate for CLOSED links. PENDING links are already
                            // connecting — creating a second link would race with the first
                            // and the first's TIMEOUT callback would set peerTimedOut=true,
                            // poisoning the peer and triggering premature pruning.
                            p.getOrInitPeerLink();
                        }
                        break;
                    } else {
                        if (nonNull(p.getPeerLink())) {
                            log.debug("QAnnounceHandler - other peer - link: {}, status: {}",
                                    encodeHexString(p.getPeerLink().getLinkId()), p.getPeerLink().getStatus());
                            if (p.getPeerLink().getStatus() == CLOSED) {
                                // mark peer for deletion on next pruning
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
                    log.info("added new {} ReticulumPeer, destinationHash: {}",
                            newPeer.getPeerAspect(), encodeHexString(destinationHash));
                }
            }
        }

        private ReticulumPeer getNewPeer(byte[] destinationHash, Identity announcedIdentity) {
            boolean isDataAspect = "qortal.qdn".equals(this.aspectFilter);
            RNSCommon.PeerAspect aspect = isDataAspect ? RNSCommon.PeerAspect.DATA : RNSCommon.PeerAspect.BASE;
            ReticulumPeer newPeer = new ReticulumPeer(destinationHash, aspect);
            newPeer.setServerIdentity(announcedIdentity);
            newPeer.setIsInitiator(true);
            newPeer.setIsDataPeer(isDataAspect);
            newPeer.setMessageMagic(getMessageMagic());
            log.debug(">>> ReticulumPeer created - PeerData: {} - {}", newPeer.getPeerData().toString(), newPeer.getPeerAddress().getDestinationHash());
            return newPeer;
        }
    }

    // Create and add a BASE ReticulumPeer directly from a cached identity (no announce needed).
    // Called from runBaseLoop() when recall() finds the identity in the local known-destinations DB.
    //
    // The ReticulumPeer constructor calls initPeerLink() (which sends the LINK OPEN via outbound()).
    // Do NOT call getOrInitPeerLink() here: the peer's link is already PENDING, so getOrInitPeerLink()
    // would call initPeerLink() a second time — creating a zombie PENDING link in the Reticulum library.
    // The zombie establishes on the remote end (adding a spurious incoming peer there), and when it
    // times out it fires expirePath() → tablesLastCulled=EPOCH → cascading 60-120s cull cycles.
    private void createLinkedPeerFromIdentity(byte[] destinationHash, Identity identity) {
        ReticulumPeer newPeer = new ReticulumPeer(destinationHash);
        newPeer.setServerIdentity(identity);
        newPeer.setIsInitiator(true);
        newPeer.setPeerAspect(RNSCommon.PeerAspect.BASE);
        newPeer.setIsDataPeer(false);
        newPeer.setMessageMagic(getMessageMagic());
        addLinkedPeer(newPeer);
        log.info("Proactively connecting to known peer {} via cached identity", encodeHexString(destinationHash));
        // Link already created in constructor — do NOT call getOrInitPeerLink() here.
        // Detect immediate send failure: ReticulumPeer() → initPeerLink() → new Link() → packet.send()
        // → outbound() is synchronous; if the LINKREQUEST couldn't be sent (no route, backbone down),
        // the link is already CLOSED by the time we get here. Record a failure so the reconnect loop
        // backs off to requestPath() rather than creating a new CLOSED link on every 15s cycle.
        Link lnk = newPeer.getPeerLink();
        if (lnk != null && lnk.getStatus() == CLOSED) {
            log.warn("createLinkedPeerFromIdentity: LINKREQUEST to {} failed immediately — switching to requestPath backoff",
                    encodeHexString(destinationHash));
            pendingLinkFailureMs.put(encodeHexString(destinationHash), System.currentTimeMillis());
        }
    }

    // Mirror of createLinkedPeerFromIdentity() for DATA-aspect peers.
    private void createLinkedDataPeerFromIdentity(byte[] destinationHash, Identity identity) {
        ReticulumPeer newPeer = new ReticulumPeer(destinationHash, RNSCommon.PeerAspect.DATA);
        newPeer.setServerIdentity(identity);
        newPeer.setIsInitiator(true);
        newPeer.setIsDataPeer(true);
        newPeer.setMessageMagic(getMessageMagic());
        addLinkedPeer(newPeer);
        log.info("DATA: proactively connecting to known peer {} via cached identity", encodeHexString(destinationHash));
        Link lnk = newPeer.getPeerLink();
        if (lnk != null && lnk.getStatus() == CLOSED) {
            log.warn("createLinkedDataPeerFromIdentity: LINKREQUEST to {} failed immediately — switching to requestPath backoff",
                    encodeHexString(destinationHash));
            pendingDataLinkFailureMs.put(encodeHexString(destinationHash), System.currentTimeMillis());
        }
    }

    //class RNSProcessor extends ExecuteProduceConsume {
    //
    //    //private final Logger logger = LoggerFactory.getLogger(RNSProcessor.class);
    //
    //    private final AtomicLong nextConnectTaskTimestamp = new AtomicLong(0L); // ms - try first connect once NTP syncs
    //    private final AtomicLong nextBroadcastTimestamp = new AtomicLong(0L); // ms - try first broadcast once NTP syncs
    //    private final AtomicLong nextPingTimestamp = new AtomicLong(0L); // ms - try first low-level Ping
    //    private final AtomicLong nextPruneTimestamp = new AtomicLong(0L); // ms - try first low-level Ping
    //
    //    private Iterator<SelectionKey> channelIterator = null;
    //
    //    RNSProcessor(ExecutorService executor) {
    //        super(executor);
    //        final Long now = NTP.getTime();
    //        nextPruneTimestamp.set(now + PRUNE_INTERVAL/2);
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
    //        //// TODO: Needed? Figure out how to add pending messages in RNSPeer
    //        ////        (RNSPeer: pendingMessages.offer(message))
    //        //task = maybeProducePeerMessageTask();
    //        //if (task != null) {
    //        //    return task;
    //         //}
    //
    //        //final Long now = NTP.getTime();
    //        //
    //        //// ping task (Link+Channel+Buffer)
    //        //task = maybeProducePeerPingTask(now);
    //        //if (task != null) {
    //        //    return task;
    //        //}
    //        // we'll just wait instead of producing tasks
    //        try {
    //            TimeUnit.MILLISECONDS.sleep(100);
    //        } catch (InterruptedException e) {
    //            log.error("exception: {}", e);
    //        }
    //
    //
    //        //task = maybeProduceBroadcastTask(now);
    //        //if (task != null) {
    //        //    return task;
    //        //}
    //
    //        //// Prune stuck/slow/old peers (moved from Controller)
    //        //task = maybeProduceRNSPrunePeersTask(now);
    //        //if (task != null) {
    //        //    return task;
    //        //}
    //
    //        return null;
    //    }
    //
    //    //private Task maybeProducePeerPingTask(Long now) {
    //    //    //var ilp = getImmutableLinkedPeers().stream()
    //    //    //        .map(peer -> peer.getPingTask(now))
    //    //    //        .filter(Objects::nonNull)
    //    //    //        .findFirst()
    //    //    //        .orElse(null);
    //    //    //if (nonNull(ilp)) {
    //    //    //    log.info("ilp - {}", ilp);
    //    //    //}
    //    //    //return ilp;
    //    //    return getActiveImmutableLinkedPeers().stream()
    //    //            .map(peer -> peer.getPingTask(now))
    //    //            .filter(Objects::nonNull)
    //    //            .findFirst()
    //    //            .orElse(null);
    //    //}
    //
    //    //private Task maybeProduceBroadcastTask(Long now) {
    //    //    if (now == null || now < nextBroadcastTimestamp.get()) {
    //    //        return null;
    //    //    }
    //    //
    //    //    nextBroadcastTimestamp.set(now + BROADCAST_INTERVAL);
    //    //    return new RNSBroadcastTask();
    //    //}
    //    //
    //    //private Task maybeProduceRNSPrunePeersTask(Long now) {
    //    //    if (now == null || now < nextPruneTimestamp.get()) {
    //    //        return null;
    //    //    }
    //    //
    //    //    nextPruneTimestamp.set(now + PRUNE_INTERVAL);
    //    //    return new RNSPrunePeersTask();
    //    //}
    //}

    private static class SingletonContainer {
        private static final RNS INSTANCE = new RNS();
    }

    public static RNS getInstance() {
        //if (isNull(instance)) instance = new RNS();
        return SingletonContainer.INSTANCE;
    }

    public List<ReticulumPeer> getActiveImmutableLinkedPeers() {
        List<ReticulumPeer> activePeers = Collections.synchronizedList(new ArrayList<>());
        for (ReticulumPeer p: this.immutableLinkedPeers) {
            // Exclude peers marked for removal (deleteMe=true): their buffer is dead even if
            // the library-level link is still ACTIVE. Excluding them lets runBaseLoop() see
            // the real active count and trigger reconnect without waiting for prunePeers().
            if (nonNull(p.getPeerLink()) && (p.getPeerLink().getStatus() == ACTIVE) && !p.getDeleteMe()) {
                activePeers.add(p);
            }
        }
        return activePeers;
    }

    /**
     * Immediately remove a peer from the peer list and kick reconnect, rather than waiting
     * for the next prunePeers() cycle (~60s). Called from ReticulumPeer.peerBufferReady()
     * on read error. Runs on the rnsWorkerPool to avoid blocking the Reticulum callback thread.
     */
    void markPeerForImmediateRemoval(ReticulumPeer peer) {
        if (this.isShuttingDown) return;
        try {
            rnsWorkerPool.submit(() -> {
                peer.makePeerUnavailable();
                if (Boolean.TRUE.equals(peer.getIsInitiator())) {
                    removeLinkedPeer(peer);
                } else {
                    removeIncomingPeer(peer);
                }
                triggerImmediateAnnounce(); // kick runBaseLoop to reconnect within ~5s
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Pool shut down — prunePeers() will clean up on next cycle
        }
    }

    // recovery from disconnected interfaces (eg. restart disconnected interfaces, etc)
    public void maybeRecoverInstance() {
        // TODO: check interfaces <=> if none available, shutdown and reintialize RNS
    }

    // note: we already have a lombok getter for this
    //public List<ReticulumPeer> getImmutableLinkedPeers() {
    //    return this.immutableLinkedPeers;
    //}

    //@Synchronized
    //public void makePeerAvailable(ReticulumPeer peer) {
    //    var network = Network.getInstance();
    //    network.addConnectedPeer(peer);
    //    network.addOutboundHandshakedPeer(peer);
    //    network.addHandshakedPeer(peer);
    //}

    public void addLinkedPeer(ReticulumPeer peer) {
        // Atomic dedup: receivedAnnounce() and runBaseLoop() can both call this concurrently
        // when a peer drops and reconnects — both see an empty slot and race to fill it.
        synchronized (this.linkedPeers) {
            boolean duplicate = this.linkedPeers.stream()
                    .anyMatch(p -> Arrays.equals(p.getDestinationHash(), peer.getDestinationHash()));
            if (duplicate) {
                log.debug("addLinkedPeer: skipping duplicate for {}", encodeHexString(peer.getDestinationHash()));
                return;
            }
            this.linkedPeers.add(peer);
            this.immutableLinkedPeers = List.copyOf(this.linkedPeers);
        }
        // Hash is added to knownPeerHashes only once the peer's buffer is confirmed ACTIVE
        // (see confirmPeerHash(), called from ReticulumPeer.createPeerBuffer()). This prevents
        // transient/failed connections from accumulating in the persisted peer list.
    }

    public void removePeer(ReticulumPeer peer) {
        if (peer.isInitiator) {
            removeLinkedPeer(peer);
        } else {
            removeIncomingPeer(peer);
        }
    }

    //@Synchronized
    //public void makePeerUnavailable(ReticulumPeer peer) {
    //    var network = Network.getInstance();
    //    network.removeHandshakedPeer(peer);
    //    network.removeOutboundHandshakedPeer(peer);
    //    network.removeConnectedPeer(peer);
    //}

    public void removeLinkedPeer(ReticulumPeer peer) {
        peer.shutdownChannel(); // clears channel + nulls peerBuffer; no close() to avoid deadlock
        this.linkedPeers.remove(peer); // single synchronized operation on the list
        this.immutableLinkedPeers = List.copyOf(this.linkedPeers);
        //var network = Network.getInstance();
        //network.removeHandshakedPeer(peer);
        //network.removeOutboundHandshakedPeer(peer);
        //network.removeConnectedPeer(peer);
    }

    // note: we already have a lombok getter for this
    //public List<ReticulumPeer> getLinkedPeers() {
    //    //synchronized(this.linkedPeers) {
    //        //return new ArrayList<>(this.linkedPeers);
    //        return this.linkedPeers;
    //    //}
    //}

    public void addIncomingPeer(ReticulumPeer peer) {
        // Dedup by remote identity + aspect: evict any existing incoming peer from the same
        // node with the same aspect. Called from linkEstablished() where identity is known.
        // Using CORE_ASPECT for both aspects would incorrectly match CORE/DATA peer pairs
        // from the same remote node and evict the wrong one.
        Identity newId = peer.getServerIdentity();
        String newAspect = (peer.getPeerAspect() == RNSCommon.PeerAspect.DATA) ? QDN_ASPECT : CORE_ASPECT;
        synchronized (this.incomingPeers) {
            if (newId != null) {
                byte[] newHash = hashFromNameAndIdentity(newAspect, newId);
                Iterator<ReticulumPeer> it = this.incomingPeers.iterator();
                while (it.hasNext()) {
                    ReticulumPeer existing = it.next();
                    Identity existingId = existing.getServerIdentity();
                    String existingAspect = (existing.getPeerAspect() == RNSCommon.PeerAspect.DATA) ? QDN_ASPECT : CORE_ASPECT;
                    if (existingId != null && existingAspect.equals(newAspect)
                            && Arrays.equals(hashFromNameAndIdentity(existingAspect, existingId), newHash)) {
                        log.info("addIncomingPeer: replacing stale {} incoming peer from {}",
                                newAspect, encodeHexString(newHash));
                        it.remove();
                        existing.shutdownChannel();
                    }
                }
            }
            this.incomingPeers.add(peer);
            this.immutableIncomingPeers = List.copyOf(this.incomingPeers);
        }
    }

    public void removeIncomingPeer(ReticulumPeer peer) {
        peer.shutdownChannel(); // clears channel + nulls peerBuffer; no close() to avoid deadlock
        this.incomingPeers.remove(peer); // single synchronized operation on the list
        this.immutableIncomingPeers = List.copyOf(this.incomingPeers);
    }

    // note: we already have a lombok getter for this
    //public List<ReticulumPeer> getIncomingPeers() {
    //    return this.incomingPeers;
    //}
    //public List<ReticulumPeer> getImmutableIncomingPeers() {
    //    return this.immutableIncomingPeers;
    //}

    public Boolean isUnreachable(ReticulumPeer peer) {
        var result = peer.getDeleteMe();
        var now = Instant.now();
        var peerLastAccessTimestamp = peer.getLastAccessTimestamp();
        if (peerLastAccessTimestamp.isBefore(now.minusMillis(LINK_UNREACHABLE_TIMEOUT))) {
            log.debug("RNS - link is unreachable");
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
        log.info("number of links (linkedPeers (active) / incomingPeers (active) before pruning: {} ({}), {} ({})",
                initiatorPeerList.size(), getActiveImmutableLinkedPeers().size(),
                incomingPeerList.size(), numActiveIncomingPeers);
        //for (ReticulumPeer p: initiatorActivePeerList) {
        //    //pLink = p.getOrInitPeerLink();
        //    p.pingRemote();
        //}
        for (ReticulumPeer p : initiatorPeerList) {
            pLink = p.getPeerLink();
            if (nonNull(pLink)) {
                if (p.getPeerTimedOut()) {
                    // options: keep in case peer reconnects or remove => we'll remove it
                    p.makePeerUnavailable();
                    //p.setPeerTimedOut(false);
                    removeLinkedPeer(p);
                    continue;
                }
                if (pLink.getStatus() == ACTIVE) {
                    // Even ACTIVE links can be zombie: buffer dead (deleteMe=true from
                    // peerBufferReady read error) or silent (no data received for >165s).
                    // Without this check, the ACTIVE continue below bypasses deleteMe entirely.
                    if (isUnreachable(p)) {
                        log.info("Removing unreachable ACTIVE peer ({}): {}",
                                p.getDeleteMe() ? "deleteMe" : "data timeout",
                                encodeHexString(p.getDestinationHash()));
                        p.makePeerUnavailable();
                        removeLinkedPeer(p);
                    }
                    continue;
                }
                if ((pLink.getStatus() == CLOSED) || (p.getDeleteMe()))  {
                    p.makePeerUnavailable();
                    p.setDeleteMe(false);
                    removeLinkedPeer(p);
                    continue;
                }
                if (pLink.getStatus() == PENDING) {
                    // Give PENDING links 60s to establish before removing them.
                    // Removing too early races with QAnnounceHandler (which creates a
                    // new link and then finds peerTimedOut=true from the old teardown).
                    // Keeping them forever blocks QAnnounceHandler (peerExists=true,
                    // status != CLOSED, so the announce is silently ignored).
                    long pendingSeconds = java.time.Duration.between(
                            p.getCreationTimestamp(), Instant.now()).getSeconds();
                    if (pendingSeconds > 60) {
                        log.info("Removing PENDING link stuck for {}s: {}", pendingSeconds, p);
                        p.makePeerUnavailable();
                        p.setIsPeerAvailable(false);
                        // Record failure so the reconnect loop backs off to requestPath() for this
                        // peer for PENDING_FAILURE_BACKOFF_MS, avoiding the cull cascade.
                        String phex = encodeHexString(p.getDestinationHash());
                        if (p.getPeerAspect() == RNSCommon.PeerAspect.DATA) {
                            pendingDataLinkFailureMs.put(phex, System.currentTimeMillis());
                        } else {
                            pendingLinkFailureMs.put(phex, System.currentTimeMillis());
                        }
                        removeLinkedPeer(p);
                        // Do NOT call pLink.teardown() here.
                        // teardown() sets status=CLOSED → jobs() finds CLOSED link in pendingLinks
                        // → calls expirePath() → tablesLastCulled=EPOCH → next jobs() does a full
                        // routing table cull (60-120s when announce-flooded). Multiple teardowns
                        // chain into cascading culls that hold the Transport lock for 22+ minutes,
                        // blocking all outbound() / requestPath() calls during that window.
                        // We remove the peer from our own tracking only; the library's zombie PENDING
                        // links have a 774000s (8.9 day) timeout (hopsTo=PATHFINDER_M → no path),
                        // which is harmless compared to the cull cascade.
                    }
                    continue;
                }
            }
        }
        // prune non-initiator peers
        List<ReticulumPeer> inaps = getNonActiveIncomingPeers();
        incomingPeerList = this.incomingPeers;
        //for (ReticulumPeer p: incomingPeerList) {
        //    pLink = p.getOrInitPeerLink();
        //    if (nonNull(pLink) && (pLink.getStatus() == ACTIVE)) {
        //        // make false active links to timeout (and teardown in timeout callback)
        //        // note: actual removal of peer happens on the following pruning run.
        //        p.pingRemote();
        //    }
        //}
        for (ReticulumPeer p: inaps) {
            // Don't call pLink.teardown() — synchronized(link) can block the Controller
            // scheduler if the Reticulum library is processing on this link. The library
            // handles non-active link cleanup via its own keepalive/watchdog mechanism.
            removeIncomingPeer(p);
        }
        // Dedup ACTIVE incoming peers by remote identity. linkEstablished() resolves the identity
        // (null at construction time because the handshake wasn't complete yet), so by prune time
        // (~60s later) it is available. Keep the newest peer per identity; remove the rest.
        {
            Map<String, List<ReticulumPeer>> byIdentity = new java.util.HashMap<>();
            for (ReticulumPeer p : getImmutableIncomingPeers()) {
                Link pl = p.getPeerLink();
                if (nonNull(pl) && pl.getStatus() == ACTIVE) {
                    Identity remoteId = p.getServerIdentity();
                    if (remoteId != null) {
                        String aspect = (p.getPeerAspect() == RNSCommon.PeerAspect.DATA) ? QDN_ASPECT : CORE_ASPECT;
                        String key = encodeHexString(hashFromNameAndIdentity(aspect, remoteId));
                        byIdentity.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
                    }
                }
            }
            for (Map.Entry<String, List<ReticulumPeer>> entry : byIdentity.entrySet()) {
                List<ReticulumPeer> dupes = entry.getValue();
                if (dupes.size() > 1) {
                    // Keep the one with the most recent data; remove the rest
                    dupes.sort((a, b) -> b.getLastAccessTimestamp().compareTo(a.getLastAccessTimestamp()));
                    for (int i = 1; i < dupes.size(); i++) {
                        log.info("prunePeers: removing duplicate ACTIVE incoming peer from {}", entry.getKey());
                        removeIncomingPeer(dupes.get(i));
                    }
                }
            }
        }
        // Prune ACTIVE incoming peers that have gone silent: the initiator moved to a new
        // link so pings stopped flowing, but the old library-level link is still ACTIVE.
        // 165s = 3 missed pings.
        for (ReticulumPeer p : getImmutableIncomingPeers()) {
            Link pl = p.getPeerLink();
            if (nonNull(pl) && pl.getStatus() == ACTIVE && isUnreachable(p)) {
                log.info("Removing stale ACTIVE incoming peer (data timeout): {}", encodeHexString(p.getDestinationHash()));
                removeIncomingPeer(p);
            }
        }
        initiatorPeerList = getImmutableLinkedPeers();
        initiatorActivePeerList = getActiveImmutableLinkedPeers();
        incomingPeerList = getImmutableIncomingPeers();
        numActiveIncomingPeers = incomingPeerList.size() - getNonActiveIncomingPeers().size();
        log.info("number of links (linkedPeers (active) / incomingPeers (active) after pruning: {} ({}), {} ({})",
                initiatorPeerList.size(), getActiveImmutableLinkedPeers().size(),
                incomingPeerList.size(), numActiveIncomingPeers);
        // announce() and requestPath() are intentionally NOT called here — both involve
        // Reticulum library calls that can block if the library holds a lock. The Controller
        // thread must not block (node hangs, stop.sh hangs). runBaseLoop() handles both on
        // its own thread every 30 seconds.
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
            log.info("Active core peers ({}) <= desired core peers ({}). Announcing (dest={})",
                    corePeerCount, MIN_DESIRED_CORE_PEERS, d != null ? encodeHexString(d.getHash()) : "null");
            if (nonNull(d)) {
                long announceT0 = System.currentTimeMillis();
                d.announce();
                long announceMs = System.currentTimeMillis() - announceT0;
                // d.announce() always returns null when send=true — see Destination.java:675.
                // Real failures are logged by Packet.java as "No interfaces could process".
                log.info("Announce attempt completed in {}ms", announceMs);
                if (announceMs > 5_000) {
                    log.warn("Announce took {}ms — possible jobsLock contention", announceMs);
                }
            } else {
                log.error("Cannot announce - destination is null");
            }
        }
        if ((dataPeerCount <= MIN_DESIRED_DATA_PEERS) && (pa == RNSCommon.PeerAspect.DATA)) {
            log.info("Active DATA peers ({}) <= desired data peers ({}). Announcing (dest={})",
                    dataPeerCount, MIN_DESIRED_DATA_PEERS, d != null ? encodeHexString(d.getHash()) : "null");
            if (nonNull(d)) {
                long announceT0 = System.currentTimeMillis();
                d.announce();
                long announceMs = System.currentTimeMillis() - announceT0;
                log.info("DATA announce attempt completed in {}ms", announceMs);
                if (announceMs > 5_000) {
                    log.warn("DATA announce took {}ms — possible jobsLock contention", announceMs);
                }
            } else {
                log.error("Cannot announce DATA - destination is null");
            }
        }
    }

    /**
     * Persist known peer destination hashes so a restarted node can call requestPath()
     * immediately rather than waiting up to 15 minutes for a natural announce.
     */
    private void saveKnownPeerHashes() {
        if (reticulum == null) return;
        try {
            Path file = reticulum.getStoragePath().resolve(KNOWN_PEERS_FILE);
            // Prefer confirmed-active hashes; fall back to loaded hashes only if nothing was
            // confirmed this session (e.g., very short startup before any peer became ACTIVE).
            Set<String> toSave = knownPeerHashes.isEmpty() ? loadedPeerHashes : knownPeerHashes;
            Files.write(file, toSave, UTF_8);
            log.debug("Saved {} known peer hashes to {}", toSave.size(), file);
        } catch (IOException e) {
            log.warn("Failed to save known peer hashes: {}", e.getMessage());
        }
    }

    // Called from ReticulumPeer.createPeerBuffer() when a peer's buffer is confirmed ACTIVE.
    // Only initiator peers call this (non-initiators have our own destination hash, not the remote's).
    void confirmPeerHash(String hashHex, RNSCommon.PeerAspect aspect) {
        if (aspect == RNSCommon.PeerAspect.DATA) {
            boolean isNew = this.knownDataPeerHashes.add(hashHex);
            if (isNew) {
                saveKnownDataPeerHashes();
                log.debug("Confirmed ACTIVE DATA peer hash {}", hashHex);
            }
        } else {
            boolean isNew = this.knownPeerHashes.add(hashHex);
            if (isNew) {
                saveKnownPeerHashes();
                log.debug("Confirmed ACTIVE peer hash {}", hashHex);
            }
        }
    }

    private void loadKnownPeerHashes() {
        if (reticulum == null) return;
        try {
            Path file = reticulum.getStoragePath().resolve(KNOWN_PEERS_FILE);
            if (!Files.isReadable(file)) return;
            List<String> lines = Files.readAllLines(file, UTF_8);
            int loaded = 0;
            for (String line : lines) {
                String hex = line.trim();
                if (!hex.isEmpty()) {
                    loadedPeerHashes.add(hex); // loaded into separate set; confirmed-active entries go to knownPeerHashes
                    loaded++;
                }
            }
            if (loaded > 0) {
                log.info("Loaded {} known peer hashes from {}", loaded, file);
            }
        } catch (IOException e) {
            log.warn("Failed to load known peer hashes: {}", e.getMessage());
        }
    }

    private void saveKnownDataPeerHashes() {
        if (reticulum == null) return;
        try {
            Path file = reticulum.getStoragePath().resolve(KNOWN_DATA_PEERS_FILE);
            Set<String> toSave = knownDataPeerHashes.isEmpty() ? loadedDataPeerHashes : knownDataPeerHashes;
            Files.write(file, toSave, UTF_8);
            log.debug("Saved {} known DATA peer hashes to {}", toSave.size(), file);
        } catch (IOException e) {
            log.warn("Failed to save known DATA peer hashes: {}", e.getMessage());
        }
    }

    private void loadKnownDataPeerHashes() {
        if (reticulum == null) return;
        try {
            Path file = reticulum.getStoragePath().resolve(KNOWN_DATA_PEERS_FILE);
            if (!Files.isReadable(file)) return;
            List<String> lines = Files.readAllLines(file, UTF_8);
            int loaded = 0;
            for (String line : lines) {
                String hex = line.trim();
                if (!hex.isEmpty()) {
                    loadedDataPeerHashes.add(hex);
                    loaded++;
                }
            }
            if (loaded > 0) {
                log.info("Loaded {} known DATA peer hashes from {}", loaded, file);
            }
        } catch (IOException e) {
            log.warn("Failed to load known DATA peer hashes: {}", e.getMessage());
        }
    }

    /**
     * Helper methods
     */

    // Send Ping Message to peer through buffer.
    // Note: This keeps Buffer,Channel and Link alive and from timing out.
    public void onPingMessage(ReticulumPeer peer, Message message) {
        PingMessage pingMessage = (PingMessage) message;

        if (isFalse(peer.getIsInitiator())) {
            return;
        }

        try {
            var pb = peer.getPeerBuffer();
            PongMessage pongMessage = new PongMessage();
            pongMessage.setId(message.getId());  // use the ping message id (for ping getResponse)
            pb.write(pongMessage.toBytes());
            pb.flush();
            peer.setLastAccessTimestamp(Instant.now());
            peer.setLastPingSent(Instant.now().toEpochMilli());
        } catch (MessageException e) {
            //log.error("{} from peer {}", e.getMessage(), this);
            log.error("{} from peer {}", e, this);
        }
    }

    public void onPeersV2Message (Peer peer, Message message) {
        // TODO: Do we do anything for ReticulumPeer (?)
        log.debug("PeersV2Message - received {} message: {}", message.getType(), message);
    }

    public List<PeerData> getAllKnownPeers() {
        return getActiveImmutableLinkedPeers().stream()
                .map(ReticulumPeer::getPeerData)
                .collect(Collectors.toList());
    }

    public List<PeerData> getAllKnownCorePeers() {
        return getActiveImmutableLinkedPeers().stream()
                .filter(p -> !p.isDataPeer())
                .map(ReticulumPeer::getPeerData)
                .collect(Collectors.toList());
    }

    public List<PeerData> getAllKnownDataPeers() {
        return getActiveImmutableLinkedPeers().stream()
                .filter(p -> p.isDataPeer())
                .map(ReticulumPeer::getPeerData)
                .collect(Collectors.toList());
    }

    // Returns all active DATA-aspect ReticulumPeers (both initiator and incoming).
    // Used by NetworkData for outbound QDN dispatch over Reticulum.
    public List<ReticulumPeer> getActiveDataPeers() {
        return Stream.concat(
                getActiveImmutableLinkedPeers().stream()
                        .filter(p -> p.getPeerAspect() == RNSCommon.PeerAspect.DATA),
                getImmutableIncomingPeers().stream()
                        .filter(p -> p.getPeerAspect() == RNSCommon.PeerAspect.DATA)
                        .filter(p -> {
                            var pl = p.getPeerLink();
                            return nonNull(pl) && pl.getStatus() == ACTIVE;
                        })
        ).collect(Collectors.toList());
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

