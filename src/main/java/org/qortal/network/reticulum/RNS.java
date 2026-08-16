package org.qortal.network.reticulum;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.ProofStrategy;
import io.reticulum.identity.Identity;
import static io.reticulum.link.LinkStatus.ACTIVE;
import lombok.Getter;

import org.qortal.network.Peer;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import org.qortal.utils.NamedThreadFactory;
import org.qortal.data.network.PeerData;
import org.qortal.controller.Controller;

// logging
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class RNS {

    private Reticulum reticulum;
    static final String APP_NAME = Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_APP_NAME: RNSCommon.MAINNET_APP_NAME;
    static final Integer TARGET_PORT = Settings.getInstance().isTestNet() ? RNSCommon.TESTNET_IF_TCP_PORT: RNSCommon.MAINNET_IF_TCP_PORT;
    static final String defaultConfigPath = Settings.getInstance().isTestNet() ? RNSCommon.defaultRNSConfigPathTestnet: RNSCommon.defaultRNSConfigPath;
    static final String CORE_ASPECT = "qortal.core";
    static final String QDN_ASPECT  = "qortal.qdn";
    private final int MIN_DESIRED_CORE_PEERS = Settings.getInstance().getReticulumMinDesiredCorePeers();
    private final int MIN_DESIRED_DATA_PEERS = Settings.getInstance().getReticulumMinDesiredDataPeers();

    // Only the accessors below are exported. This class used to carry Lombok @Data, which
    // generated ~100 public accessors (including setters for every field and getters handing out
    // the live peer lists) to serve a handful of real call sites. Everything else is internal.
    @Getter private Identity serverIdentity;
    @Getter private Destination baseDestination;
    @Getter private Destination dataDestination;
    @Getter private volatile boolean isShuttingDown = false;
    private volatile boolean meshStarted = false;   // exported via isMeshStarted()

    // Persisted destination hashes of peers we have talked to, one store per aspect, so a restart
    // reconnects immediately instead of waiting for announces. See KnownPeerStore for why the
    // confirmed and loaded sets are kept apart. Created in start(), once the storage path is known.
    private static final String KNOWN_PEERS_FILE = "known_peer_hashes.txt";
    private static final String KNOWN_DATA_PEERS_FILE = "known_data_peer_hashes.txt";
    private KnownPeerStore basePeerStore;
    private KnownPeerStore dataPeerStore;

    // Per-peer link-failure state and its capped exponential backoff — see ReconnectPolicy.
    // One instance per aspect: BASE and DATA destination hashes are distinct, so a hash can only
    // ever appear in one of them.
    private final ReconnectPolicy basePolicy = new ReconnectPolicy();
    private final ReconnectPolicy dataPolicy = new ReconnectPolicy();

    /** Owns the linked (initiator) and incoming (non-initiator) peer lists and their snapshots. */
    private final RNSPeerRegistry registry = new RNSPeerRegistry();

    /** Accepting, creating and tearing down peers — the side effects the registry must not run. */
    private final RNSPeerLifecycle peers;

    /** The four prunePeers() passes. Removal side effects stay in RNSPeerLifecycle, behind the
     *  two callbacks. */
    private final RNSPeerPruner peerPruner;

    // Gateway announce (reticulumAnnounceGateway): advertise-host resolution and dialling of
    // peer-announced gateways live in RNSGatewayManager; the announce payload that carries them
    // (QAN1 container, legacy QGW1 fallback) lives in RNSAnnounceCodec.
    private final RNSGatewayManager gatewayManager;

    /** Versions learned from announces, so inbound peers can resolve theirs once they identify. */
    private final AnnouncedVersionCache announcedVersions = new AnnouncedVersionCache();

    /** One mesh loop per aspect: drain, announce, reconnect. Both created in start(). */
    private RNSAspectRunner baseRunner;
    private RNSAspectRunner dataRunner;
    /** Thread priority for the runners' executors; the constructor reads it from Settings. */
    private final int rnsThreadPriority;
    /** Shared by both runners: peer message and ping tasks from either aspect run here. */
    private ExecutorService rnsWorkerPool;
    private static final long NETWORK_EPC_KEEPALIVE = 5L; // 1 second

    // replicating a feature from Network.class needed in for base Message.java,
    // just in case the classic TCP/IP Networking is turned off.
    private static final byte[] MAINNET_MESSAGE_MAGIC = new byte[]{0x51, 0x4f, 0x52, 0x54}; // QORT
    private static final byte[] TESTNET_MESSAGE_MAGIC = new byte[]{0x71, 0x6f, 0x72, 0x54}; // qort

    /** The failure/backoff state for an aspect. */
    private ReconnectPolicy policyFor(RNSCommon.PeerAspect aspect) {
        return aspect == RNSCommon.PeerAspect.DATA ? dataPolicy : basePolicy;
    }

    /** The runner for an aspect. Null-safe on aspect: an inbound peer's is set just after
     *  construction, so a link closing in that window is treated as BASE, as it always was. */
    private RNSAspectRunner runnerFor(RNSCommon.PeerAspect aspect) {
        return aspect == RNSCommon.PeerAspect.DATA ? dataRunner : baseRunner;
    }

    /**
     * Called when a peer's link closes, to kick that aspect's announce/path-recovery cycle within
     * ~5s rather than waiting out its full 30s window.
     * <p>
     * The aspect matters: both call sites used to kick BASE unconditionally, and the DATA
     * equivalent (a {@code triggerImmediateDataAnnounce()} that existed but had no callers) meant a
     * dropped DATA peer accelerated nothing and woke the wrong loop.
     */
    void triggerImmediateAnnounce(RNSCommon.PeerAspect aspect) {
        RNSAspectRunner runner = runnerFor(aspect);
        if (runner != null) {   // null until start() has run
            runner.triggerImmediateAnnounce();
        }
    }

    // Constructor
    private RNS () {
        log.info("RNS constructor");
        try {
            RNSConfigWriter.ensureConfig(defaultConfigPath, APP_NAME, TARGET_PORT);
            reticulum = new Reticulum(defaultConfigPath);
        } catch (IOException e) {
            log.error("unable to create Reticulum network", e);
        }
        log.info("reticulum instance created");
        log.debug("reticulum instance created: {}", reticulum);
        var rnsThreadPriority = Settings.getInstance().getNetworkThreadPriority(); // default: 7
        this.gatewayManager = new RNSGatewayManager(APP_NAME, TARGET_PORT, rnsThreadPriority);
        this.rnsWorkerPool = new ThreadPoolExecutor(
                3, Settings.getInstance().getReticulumMaxNetworkThreadPoolSize(),
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("RNS-Worker", rnsThreadPriority));
        this.rnsThreadPriority = rnsThreadPriority;
        // Built here rather than as field initialisers: both need rnsWorkerPool, which is assigned
        // just above, and the pruner's callbacks are the lifecycle's methods.
        this.peers = new RNSPeerLifecycle(registry, rnsWorkerPool, this::isShuttingDown,
                this::triggerImmediateAnnounce,
                (hashHex, aspect) -> policyFor(aspect).recordFailure(hashHex),
                announcedVersions, getMessageMagic());
        this.peerPruner = new RNSPeerPruner(
                registry, peers::removeLinkedPeer, peers::removeIncomingPeer,
                (hashHex, aspect) -> policyFor(aspect).recordFailure(hashHex));
    }

    public void start() {
        // The constructor logs and continues when the Reticulum stack can't be built, so the
        // singleton is published half-built. Dereferencing reticulum here would then NPE inside
        // Network's startup. Refuse instead: meshStarted stays false and every consumer already
        // guards on isMeshStarted(), so the node runs without the mesh rather than failing.
        if (reticulum == null) {
            log.error("Reticulum stack unavailable (see construction error above) — mesh will not start");
            return;
        }

        serverIdentity = RNSIdentityStore.loadOrCreate(reticulum.getStoragePath(), APP_NAME);
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

        baseDestination.setLinkEstablishedCallback(link -> peers.clientConnected(link, RNSCommon.PeerAspect.BASE));
        dataDestination.setLinkEstablishedCallback(link -> peers.clientConnected(link, RNSCommon.PeerAspect.DATA));
        registerAnnounceHandler(CORE_ASPECT, RNSCommon.PeerAspect.BASE, MIN_DESIRED_CORE_PEERS);
        registerAnnounceHandler(QDN_ASPECT, RNSCommon.PeerAspect.DATA, MIN_DESIRED_DATA_PEERS);
        log.debug("announceHandlers: {}", Transport.getInstance().getAnnounceHandlers());
        // Load peer hashes persisted from previous run so we can call requestPath() fast on restart.
        this.basePeerStore = new KnownPeerStore(reticulum.getStoragePath(), KNOWN_PEERS_FILE, "BASE");
        this.dataPeerStore = new KnownPeerStore(reticulum.getStoragePath(), KNOWN_DATA_PEERS_FILE, "DATA");
        this.basePeerStore.load();
        this.dataPeerStore.load();
        // do a first announce (across all configured interfaces)
        byte[] initialAppData = buildAnnounceAppData();
        baseDestination.announce(initialAppData);
        log.info("Sent initial announce from {} ({})", encodeHexString(baseDestination.getHash()), baseDestination.getName());
        dataDestination.announce(initialAppData);
        log.info("Sent initial announce from {} ({})", encodeHexString(dataDestination.getHash()), dataDestination.getName());
        // Start up "main" threads, one per destination / peer aspect. Each runner seeds its own
        // announce timer from its peer store (full window on a first-ever start, 15s on a restart
        // with known hashes to reconnect to).
        //
        // Only BASE logs per-interface online status: interface state is transport-wide, not
        // per-aspect, so logging it from both runners would just double the line rate.
        this.baseRunner = newRunner(RNSCommon.PeerAspect.BASE, baseDestination, MIN_DESIRED_CORE_PEERS,
                Peer.NETWORK, basePeerStore, basePolicy, true);
        this.dataRunner = newRunner(RNSCommon.PeerAspect.DATA, dataDestination, MIN_DESIRED_DATA_PEERS,
                Peer.NETWORKDATA, dataPeerStore, dataPolicy, false);
        this.baseRunner.start();
        this.dataRunner.start();

        this.meshStarted = true;
        log.info("RNS mesh started, baseDestination: {}", encodeHexString(baseDestination.getHash()));
    }

    /** One runner per aspect; everything that differs between the two is an argument here. */
    private RNSAspectRunner newRunner(RNSCommon.PeerAspect aspect, Destination destination,
                                      int minDesiredPeers, int messageTaskType, KnownPeerStore store,
                                      ReconnectPolicy policy, boolean logInterfaceStatus) {
        return new RNSAspectRunner(aspect, destination, minDesiredPeers, messageTaskType, store,
                registry, policy, gatewayManager, rnsWorkerPool, this::buildAnnounceAppData,
                (dhash, identity) -> peers.createLinkedPeerFromIdentity(dhash, identity, aspect),
                this::isShuttingDown, logInterfaceStatus, rnsThreadPriority);
    }

    /** One announce handler per aspect, registered with Transport for the process lifetime. */
    private void registerAnnounceHandler(String aspectFilter, RNSCommon.PeerAspect aspect, int minDesiredPeers) {
        Transport.getInstance().registerAnnounceHandler(new RNSAnnounceHandler(
                aspectFilter, aspect, minDesiredPeers, registry, gatewayManager,
                announcedVersions, getMessageMagic(), peers::addLinkedPeer));
    }

    public boolean isMeshStarted() {
        return meshStarted;
    }

    public void broadcast(Function<ReticulumPeer, Message> peerMessageBuilder) {
        List<ReticulumPeer> allPeers = Stream.concat(
                registry.activeLinked().stream(),
                registry.activeIncoming().stream()
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

    public void shutdown() {
        this.isShuttingDown = true;
        // Controller calls this unconditionally, so it must tolerate a mesh that never started
        // (no Reticulum stack, or start() refused): the destinations are null in that case, but
        // the executors were still created by the constructor and must be closed either way.
        boolean meshWasStarted = reticulum != null && baseDestination != null;
        if (meshWasStarted) {
            basePeerStore.save();
            dataPeerStore.save();
            log.info("shutting down Reticulum");
            baseDestination.setProofStrategy(ProofStrategy.PROVE_NONE);
            dataDestination.setProofStrategy(ProofStrategy.PROVE_NONE);
        } else {
            log.info("Reticulum mesh was not started — closing worker threads only");
        }

        // Stops each loop thread and its announce/reconnect executors together, before the peer
        // teardown below — a reconnect task must not create fresh links while we close them.
        if (this.baseRunner != null) {
            this.baseRunner.shutdown();
        }
        if (this.dataRunner != null) {
            this.dataRunner.shutdown();
        }
        
        // gracefully close links of peers that point to us.
        // Iterate the immutable snapshots, not the live synchronized lists: a concurrent
        // add/remove during shutdown would otherwise throw ConcurrentModificationException here
        // and skip the rest of the shutdown (executors, exitHandler).
        for (ReticulumPeer p: getImmutableIncomingPeers()) {
            var pl = p.getPeerLink();
            // && (not &): the status read must not happen when the link is null, or shutdown
            // dies on an NPE before it can stop the executors and run exitHandler().
            if (nonNull(pl) && (pl.getStatus() == ACTIVE)) {
                p.sendCloseToRemote(pl);
            }
        }
        log.debug("Shutdown of incomingPeers completed");
        // Disconnect peers gracefully and terminate Reticulum
        for (ReticulumPeer p: getImmutableLinkedPeers()) {
            log.info("shutting down peer: {}", encodeHexString(p.getDestinationHash()));
            p.shutdown();
        }
        log.debug("Shutdown of linkedPeers completed");
        // Shut down worker pool so its threads don't prevent JVM exit
        this.rnsWorkerPool.shutdown();
        this.gatewayManager.shutdown();
        try {
            if (!this.rnsWorkerPool.awaitTermination(2, TimeUnit.SECONDS))
                this.rnsWorkerPool.shutdownNow();
        } catch (InterruptedException e) {
            this.rnsWorkerPool.shutdownNow();
        }

        if (!meshWasStarted) {
            return;
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

    // ── reticulumAnnounceGateway: send / receive / dispatch ──────────────────

    /**
     * Build the appData attached to every announce: this node's version, plus a gateway record when
     * this node advertises one. Encoding lives in {@link RNSAnnounceCodec}; the policy decision of
     * <i>whether</i> to advertise stays here, with the settings it depends on.
     */
    private byte[] buildAnnounceAppData() {
        return RNSAnnounceCodec.encode(
                Controller.getInstance().getVersionStringWithoutPrefix(),
                gatewayHostToAdvertise(),
                TARGET_PORT);
    }

    /** The gateway host to put in our announces, or null when this node advertises no gateway. */
    private String gatewayHostToAdvertise() {
        if (!Settings.getInstance().getReticulumAnnounceGateway()) return null;
        if (!Settings.getInstance().getReticulumIsGateway())       return null;

        String host = gatewayManager.getAdvertiseHost();
        // The codec drops a host it cannot encode; say so, or the gateway would silently never be
        // advertised. +1 (hostLen) +2 (port) must fit a single-byte TLV length.
        if (host != null && host.getBytes(StandardCharsets.UTF_8).length > 252) {
            log.warn("Skipping gateway appData: host '{}' is too long to encode (max 252 bytes)", host);
            return null;
        }
        return host;
    }

    private static class SingletonContainer {
        private static final RNS INSTANCE = new RNS();
    }

    public static RNS getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public List<ReticulumPeer> getImmutableLinkedPeers() {
        return registry.linked();
    }

    public List<ReticulumPeer> getImmutableIncomingPeers() {
        return registry.incoming();
    }

    List<ReticulumPeer> getActiveImmutableLinkedPeers() {
        return registry.activeLinked();
    }

    /** @see RNSPeerLifecycle#markPeerForImmediateRemoval — called from ReticulumPeer. */
    boolean markPeerForImmediateRemoval(ReticulumPeer peer) {
        return peers.markPeerForImmediateRemoval(peer);
    }

    /** @see RNSPeerLifecycle#dedupIncomingPeerByIdentity — called from ReticulumPeer. */
    void dedupIncomingPeerByIdentity(ReticulumPeer keep) {
        peers.dedupIncomingPeerByIdentity(keep);
    }

    /** Whether a peer's link is dead, marked for removal, or has gone silent. */
    public boolean isUnreachable(ReticulumPeer peer) {
        return RNSPeerPruner.isUnreachable(peer);
    }

    /**
     * Periodic peer-list garbage collection, called from Controller every 90 seconds.
     * <p>
     * Keeps {@code throws DataException} because Controller.java wraps the call in a
     * {@code catch (DataException)} — Java rejects a catch clause for a checked exception the body
     * cannot throw, so dropping it here would force an unrelated Controller edit.
     */
    public void prunePeers() throws DataException {
        peerPruner.prune();
    }

    // Called from ReticulumPeer.createPeerBuffer() when a peer's buffer is confirmed ACTIVE.
    // Only initiator peers call this (non-initiators have our own destination hash, not the remote's).
    void confirmPeerHash(String hashHex, RNSCommon.PeerAspect aspect) {
        // Peer is ACTIVE — clear any failure/backoff state so a future transient drop starts fresh
        // rather than inheriting a long exponential-backoff window from earlier.
        policyFor(aspect).clear(hashHex);
        KnownPeerStore store = (aspect == RNSCommon.PeerAspect.DATA) ? dataPeerStore : basePeerStore;
        if (store != null) {   // null until start() has run
            store.confirm(hashHex);
        }
    }

    /**
     * Helper methods
     */

    public void onPeersV2Message (Peer peer, Message message) {
        // TODO: Do we do anything for ReticulumPeer (?)
        log.debug("PeersV2Message - received {} message: {}", message.getType(), message);
    }

    public List<PeerData> getAllKnownPeers() {
        return getActiveImmutableLinkedPeers().stream()
                .map(ReticulumPeer::getPeerData)
                .collect(Collectors.toList());
    }

    // Returns all active DATA-aspect ReticulumPeers (both initiator and incoming).
    // Used by NetworkData for outbound QDN dispatch over Reticulum.
    public List<ReticulumPeer> getActiveDataPeers() {
        return Stream.concat(
                registry.activeLinked(RNSCommon.PeerAspect.DATA).stream(),
                registry.activeIncoming(RNSCommon.PeerAspect.DATA).stream()
        ).collect(Collectors.toList());
    }

    byte[] getMessageMagic() {
        return Settings.getInstance().isTestNet() ? TESTNET_MESSAGE_MAGIC : MAINNET_MESSAGE_MAGIC;
    }

}

