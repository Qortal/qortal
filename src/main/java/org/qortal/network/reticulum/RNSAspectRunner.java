package org.qortal.network.reticulum;

import io.reticulum.Transport;
import io.reticulum.constant.TransportConstant;
import io.reticulum.destination.Destination;
import io.reticulum.identity.Identity;
import io.reticulum.identity.IdentityKnownDestination;
import io.reticulum.interfaces.ConnectionInterface;
import lombok.extern.slf4j.Slf4j;
import org.qortal.network.reticulum.RNSCommon.PeerAspect;
import org.qortal.utils.ExecuteProduceConsume;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.codec.binary.Hex.decodeHex;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * The mesh loop for one peer aspect: drain peer message/ping tasks, announce periodically, and
 * reconnect to known peers. Instantiated once per aspect (BASE, DATA).
 * <p>
 * This class exists because the BASE and DATA loops were near-identical copies that drifted: every
 * robustness fix applied to BASE — the announce/reconnect watchdogs, the backbone circuit breaker,
 * the one-outgoing-link-per-cycle throttle, skipping peers already ACTIVE as incoming — was missing
 * from DATA, where a wedged announce stopped DATA announces for the process lifetime. One class
 * instantiated twice is what stops that drift recurring.
 */
@Slf4j
final class RNSAspectRunner {

    /**
     * How often the loop triggers an announce and path recovery, independent of prunePeers(). This
     * ensures announces fire even when the Controller scheduler is slow/blocked (e.g. prunePeers()
     * waiting on a lock inside the Reticulum library).
     */
    private static final long ANNOUNCE_INTERVAL_MS = 30_000L;
    private static final long RECONNECT_INTERVAL_MS = 15_000L;   // reconnect independently of announce
    private static final long ANNOUNCE_TASK_TIMEOUT_MS = 60_000L; // watchdog: reset stuck announce after 60s
    private static final long RECONNECT_TASK_TIMEOUT_MS = 45_000L; // watchdog: reset stuck reconnect after 45s
    /**
     * Loop tick. Each tick walks both peer lists for this aspect to drain message and ping tasks;
     * at 10 ms that was ~100 traversals/s per runner while idle, for at most 10 ms of dispatch
     * latency. 50 ms cuts that by 5× and is still far below anything a peer can notice — messages
     * are dispatched to the worker pool, and the ping interval it also drives is 55 s.
     */
    private static final long LOOP_SLEEP_MS = 50L;
    private static final long THREAD_JOIN_MS = 5_000L;
    private static final long EXECUTOR_KEEPALIVE_S = 5L;
    /** Announce this soon after start when the peer store has hashes to reconnect to. */
    private static final long RESTART_ANNOUNCE_DELAY_MS = 15_000L;
    /** Peers with no failure for this long lose their backoff state (see ReconnectPolicy). */
    private static final long FAILURE_STATE_MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    /**
     * Consecutive stuck announce/reconnect tasks before the backbone TCP connection is force-closed.
     * When both keep timing out the connection is likely in a bad state; forcing the library's
     * built-in auto-reconnect clears it rather than spinning on a stuck jobsLock forever.
     */
    private static final int BACKBONE_FORCE_RECONNECT_THRESHOLD = 2;

    private final PeerAspect aspect;
    private final Destination destination;
    private final int minDesiredPeers;
    private final int messageTaskType;              // Peer.NETWORK | Peer.NETWORKDATA
    private final KnownPeerStore store;
    private final RNSPeerRegistry registry;
    private final ReconnectPolicy policy;
    private final RNSGatewayManager gateways;
    private final ExecutorService workerPool;       // shared with the other aspect
    private final Supplier<byte[]> appDataSupplier;
    private final BiConsumer<byte[], Identity> peerFactory;
    private final BooleanSupplier shuttingDown;
    private final boolean logInterfaceStatus;

    // Dedicated single-thread executors for announce and reconnect.
    // Root cause of prior failures: Transport.outbound() busy-waits on jobsLock (non-interruptible).
    // A full table cull triggered by link drops holds jobsLock for 30-60s. With a shared pool,
    // each watchdog reset spawns a new thread, creating 20+ threads all spinning on jobsLock
    // simultaneously — massively worsening contention and making the cull take even longer.
    // Solution: one dedicated thread per operation (bounded queue=1). At most 2 threads ever
    // spin on jobsLock; tasks queue up naturally and complete when the cull finishes.
    private final ThreadPoolExecutor announceExecutor;
    private final ThreadPoolExecutor reconnectExecutor;

    private Thread thread;
    private volatile long lastAnnounceMs = 0;
    private volatile long lastReconnectMs = 0;
    // Timestamp-based guards: 0 = no task running; non-zero = task started at that ms.
    // Timestamps (rather than booleans) allow a watchdog to force-reset after the timeout.
    // createLinkedPeerFromIdentity() and requestPath() call Reticulum transport code that can
    // acquire internal locks and block when the backbone degrades — both must run in the pool,
    // never inline on the loop thread.
    private final AtomicLong announceStartedMs = new AtomicLong(0L);
    private final AtomicLong reconnectStartedMs = new AtomicLong(0L);
    private final AtomicReference<Future<?>> announceFuture = new AtomicReference<>();
    private final AtomicReference<Future<?>> reconnectFuture = new AtomicReference<>();
    private volatile int consecutiveStuckTasks = 0;

    /**
     * @param messageTaskType    which queue the drain pass pulls from: {@code Peer.NETWORK} routes
     *                           to {@code Network.onMessage()}, {@code Peer.NETWORKDATA} routes to
     *                           {@code NetworkData.onMessage()}
     * @param peerFactory        creates and tracks an initiator peer of this aspect from a cached
     *                           identity; the side effects stay in {@code RNS}
     * @param logInterfaceStatus whether to log per-interface online status each reconnect cycle.
     *                           Interface state is transport-wide, not per-aspect, so only one
     *                           runner does it — otherwise the line rate simply doubles.
     */
    RNSAspectRunner(PeerAspect aspect,
                    Destination destination,
                    int minDesiredPeers,
                    int messageTaskType,
                    KnownPeerStore store,
                    RNSPeerRegistry registry,
                    ReconnectPolicy policy,
                    RNSGatewayManager gateways,
                    ExecutorService workerPool,
                    Supplier<byte[]> appDataSupplier,
                    BiConsumer<byte[], Identity> peerFactory,
                    BooleanSupplier shuttingDown,
                    boolean logInterfaceStatus,
                    int threadPriority) {
        this.aspect = Objects.requireNonNull(aspect);
        this.destination = Objects.requireNonNull(destination);
        this.minDesiredPeers = minDesiredPeers;
        this.messageTaskType = messageTaskType;
        this.store = Objects.requireNonNull(store);
        this.registry = Objects.requireNonNull(registry);
        this.policy = Objects.requireNonNull(policy);
        this.gateways = Objects.requireNonNull(gateways);
        this.workerPool = Objects.requireNonNull(workerPool);
        this.appDataSupplier = Objects.requireNonNull(appDataSupplier);
        this.peerFactory = Objects.requireNonNull(peerFactory);
        this.shuttingDown = Objects.requireNonNull(shuttingDown);
        this.logInterfaceStatus = logInterfaceStatus;

        // Bounded queue(1): at most one task running + one queued. A rejected submission just
        // means the next interval will retry — no unbounded thread growth.
        this.announceExecutor = new ThreadPoolExecutor(1, 1,
                EXECUTOR_KEEPALIVE_S, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new NamedThreadFactory("RNS-" + aspect + "-Announce", threadPriority));
        this.reconnectExecutor = new ThreadPoolExecutor(1, 1,
                EXECUTOR_KEEPALIVE_S, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new NamedThreadFactory("RNS-" + aspect + "-Reconnect", threadPriority));
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    void start() {
        seedAnnounceTimer();
        this.thread = new Thread(this::run, "rnsMesh-" + aspect);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * On restart (non-empty loaded hashes) fire path requests at t=15s; on first-ever start use the
     * full announce window.
     */
    void seedAnnounceTimer() {
        this.lastAnnounceMs = store.hasLoadedHashes()
                ? System.currentTimeMillis() - ANNOUNCE_INTERVAL_MS + RESTART_ANNOUNCE_DELAY_MS
                : System.currentTimeMillis();
    }

    /** Kick the announce/path-recovery cycle within ~5s.
     *  Uses a 5s delay rather than 0 to avoid tight reconnect loops when links close rapidly
     *  (e.g., Channel "retry count exceeded" tears down a link, immediate re-announce creates
     *  a new link, new link also fails → rapid churn). */
    void triggerImmediateAnnounce() {
        this.lastAnnounceMs = System.currentTimeMillis() - ANNOUNCE_INTERVAL_MS + 5_000L;
    }

    void shutdown() {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(THREAD_JOIN_MS);
                if (thread.isAlive()) {
                    log.warn("RNS {} thread did not terminate in time", aspect);
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for RNS {} thread", aspect);
            }
        }
        announceExecutor.shutdown();
        reconnectExecutor.shutdown();
        try {
            if (!announceExecutor.awaitTermination(2, TimeUnit.SECONDS)) announceExecutor.shutdownNow();
            if (!reconnectExecutor.awaitTermination(2, TimeUnit.SECONDS)) reconnectExecutor.shutdownNow();
        } catch (InterruptedException e) {
            announceExecutor.shutdownNow();
            reconnectExecutor.shutdownNow();
        }
    }

    // ── the loop ─────────────────────────────────────────────────────────────

    private void run() {
        while (!shuttingDown.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            try {
                drainPeerTasks();
                long nowMs = System.currentTimeMillis();
                announceTick(nowMs);
                reconnectTick(nowMs);
            } catch (Exception e) {
                log.error("{}: unexpected exception — loop continues", aspect, e);
            }

            // Sleep unconditionally at the end of every cycle to cap the loop at ~100 iterations/sec.
            if (!shuttingDown.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(LOOP_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.debug("Mesh loop for destination {} exiting.", destination.getName());
    }

    /**
     * Drain messages from both initiator peers (linked) and non-initiator/incoming peers so that
     * requests received by either side are processed, and send keepalive pings that are due.
     */
    private void drainPeerTasks() {
        final List<ReticulumPeer> peersThisRound = Stream.concat(
                registry.activeLinked(aspect).stream(),
                registry.activeIncoming(aspect).stream()
        ).collect(Collectors.toList());

        final Long now = NTP.getTime();
        for (ReticulumPeer peer : peersThisRound) {
            ExecuteProduceConsume.Task task;
            while ((task = peer.getMessageTask(messageTaskType)) != null) {
                if (!submitToWorkerPool(peer, task, "message")) {
                    break;
                }
            }

            // Send keepalive ping if due (initiator peers only, every 55s)
            ExecuteProduceConsume.Task pingTask = peer.getPingTask(now);
            if (pingTask != null) {
                submitToWorkerPool(peer, pingTask, "ping");
            }
        }
    }

    /** @return false when the pool rejected the task (full or shutting down). */
    private boolean submitToWorkerPool(ReticulumPeer peer, ExecuteProduceConsume.Task task, String kind) {
        try {
            workerPool.execute(() -> {
                try {
                    task.perform();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("{}: Reticulum {} task threw: {}", aspect, kind, e.getMessage(), e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("[{}] {}: Reticulum worker pool rejected {} task (pool full or shutting down)",
                    peer.getPeerConnectionId(), aspect, kind);
            return false;
        }
    }

    // ── announce ─────────────────────────────────────────────────────────────

    private void announceTick(long nowMs) {
        if (nowMs - lastAnnounceMs < ANNOUNCE_INTERVAL_MS) return;
        lastAnnounceMs = nowMs;

        watchdog("announce", announceStartedMs, announceFuture, announceExecutor,
                ANNOUNCE_TASK_TIMEOUT_MS, nowMs);
        if (!announceStartedMs.compareAndSet(0L, nowMs)) return;

        try {
            announceFuture.set(announceExecutor.submit(() -> {
                Thread.interrupted(); // clear any stale interrupt flag from prior cancel
                try {
                    announce();
                } catch (Exception e) {
                    log.warn("{}: exception in loop announce: {}", aspect, e.getMessage(), e);
                } finally {
                    taskFinished(announceStartedMs);
                }
            }));
        } catch (RejectedExecutionException e) {
            announceStartedMs.set(0L);
        }
    }

    private void announce() {
        int activePeers = registry.activeLinked(aspect).size();
        if (activePeers >= minDesiredPeers) return;

        log.info("Active {} peers ({}) < desired peers ({}). Announcing (dest={})",
                aspect, activePeers, minDesiredPeers, encodeHexString(destination.getHash()));
        long announceT0 = System.currentTimeMillis();
        destination.announce(appDataSupplier.get());
        long announceMs = System.currentTimeMillis() - announceT0;
        // destination.announce() always returns null when send=true — see Destination.java:675.
        // Real failures are logged by Packet.java as "No interfaces could process".
        log.info("{} announce attempt completed in {}ms", aspect, announceMs);
        if (announceMs > 5_000) {
            log.warn("{} announce took {}ms — possible jobsLock contention", aspect, announceMs);
        }
    }

    // ── reconnect ────────────────────────────────────────────────────────────

    private void reconnectTick(long nowMs) {
        if (nowMs - lastReconnectMs < RECONNECT_INTERVAL_MS) return;
        lastReconnectMs = nowMs;

        watchdog("reconnect", reconnectStartedMs, reconnectFuture, reconnectExecutor,
                RECONNECT_TASK_TIMEOUT_MS, nowMs);
        if (!reconnectStartedMs.compareAndSet(0L, nowMs)) return;

        // Read on the loop thread: the executor thread may be stuck behind jobsLock, and these are
        // the inputs the cycle was scheduled for.
        final int activePeers = registry.activeLinked(aspect).size();
        final Set<String> reconnectTargets = store.reconnectTargets();
        try {
            reconnectFuture.set(reconnectExecutor.submit(() -> {
                Thread.interrupted(); // clear any stale interrupt flag from prior cancel
                try {
                    reconnect(activePeers, reconnectTargets);
                } catch (Exception e) {
                    log.warn("{}: exception in loop reconnect: {}", aspect, e.getMessage(), e);
                } finally {
                    taskFinished(reconnectStartedMs);
                }
            }));
        } catch (RejectedExecutionException e) {
            reconnectStartedMs.set(0L);
        }
    }

    private void reconnect(int activePeers, Set<String> reconnectTargets) {
        if (logInterfaceStatus) {
            // Log interface online status for diagnostics
            for (ConnectionInterface iface : Transport.getInstance().getInterfaces()) {
                log.info("Interface '{}' online={}", iface.getInterfaceName(), iface.isOnline());
            }
        }
        policy.evictOlderThan(FAILURE_STATE_MAX_AGE_MS);

        if (activePeers >= minDesiredPeers || reconnectTargets.isEmpty()) return;

        log.info("Active {} peers {} < desired {}; requesting paths to {} known peers",
                aspect, activePeers, minDesiredPeers, reconnectTargets.size());
        // When fully disconnected, limit outgoing link creation to 1 per cycle.
        // Creating all peers simultaneously floods jobsLock (each new Link() sends
        // a LINKREQUEST via outbound(Packet)) and starves announce/reconnect tasks.
        // The PENDING-failure backoff naturally rotates through peers across cycles.
        int outgoingLinksCreated = 0;
        // Identity hashes of ACTIVE incoming peers of this aspect, computed ONCE per cycle
        // (see RNSPeerRegistry.activeIncomingHashes for why per-target is costly).
        final Set<String> activeIncomingHashes = registry.activeIncomingHashes(aspect);

        for (String hashHex : reconnectTargets) {
            try {
                byte[] dhash = decodeHex(hashHex);
                // Skip peers already tracked (PENDING or ACTIVE) as initiator links
                if (registry.isLinkedTracked(dhash)) continue;
                // Skip peers already ACTIVE as incoming — broadcast() covers them,
                // and creating a duplicate outgoing link doubles the Channel teardown
                // rate, driving more expirePath() culls and accumulating spurious
                // incoming connections on the remote end. (O(1) set lookup — see the
                // precomputed activeIncomingHashes above.)
                if (activeIncomingHashes.contains(hashHex)) continue;
                // hopsTo() is a ConcurrentHashMap.get() — no lock, always safe.
                // DEBUG, not INFO: this and the three requestPath lines below fire once per
                // known peer per 15s cycle — ~100 lines per cycle at 50 known peers, ~24k/h per
                // aspect. The per-cycle summary above carries the same signal at 1/N the volume.
                if (log.isDebugEnabled()) {
                    int hops = Transport.getInstance().hopsTo(dhash);   // ConcurrentHashMap.get() — no lock
                    log.debug("Path to {}: hops={}", hashHex,
                            hops == TransportConstant.PATHFINDER_M ? "unknown" : hops);
                }
                // Hybrid reconnect strategy:
                //
                // createLinkedPeerFromIdentity() creates an outgoing link immediately
                // from the locally-cached identity. This is how initial connections form.
                // If the LINKREQUEST send fails (no route in pathTable), the link is
                // CLOSED immediately and we record a failure right there.
                // If the peer is reachable but slow, RNSPeerPruner removes the
                // PENDING link after 60s and records a failure.
                // Either way we back off to requestPath() for the backoff window
                // so the backbone can provide a fresh path before we retry.
                //
                // requestPath() sends a single path-request packet (no PENDING link).
                // If the backbone responds with a fresh announce, QAnnounceHandler creates
                // the link. If the peer is unreachable nothing happens: no cull, no cascade.
                //
                // Strategy: use createLinkedPeerFromIdentity() for peers without a recent
                // PENDING failure; use requestPath() for peers in the backoff window.
                // When activePeers==0, limit outgoing link creation to 1 per cycle to
                // avoid flooding jobsLock; requestPath breaks the 0/0 deadlock for others.
                boolean recentlyFailed = policy.isBackingOff(hashHex);
                boolean outgoingSlotFree = activePeers > 0 || outgoingLinksCreated == 0;
                Identity cachedIdentity = (!recentlyFailed && outgoingSlotFree)
                        ? IdentityKnownDestination.recall(dhash) : null;
                if (cachedIdentity != null) {
                    peerFactory.accept(dhash, cachedIdentity);   // logs the connection attempt
                    outgoingLinksCreated++;
                } else {
                    if (recentlyFailed) {
                        log.debug("{}: backing off to requestPath for {} (recent PENDING failure)", aspect, hashHex);
                    } else if (!outgoingSlotFree) {
                        log.debug("{}: requestPath for {} (outgoing slot in use)", aspect, hashHex);
                    } else {
                        log.debug("{}: requestPath for {} (no cached identity)", aspect, hashHex);
                    }
                    Transport.getInstance().requestPath(dhash);
                }
            } catch (Exception e) {
                log.warn("{}: path request/reconnect failed for {}: {}", aspect, hashHex, e.getMessage());
            }
        }
    }

    // ── watchdog / circuit breaker ───────────────────────────────────────────

    /**
     * Interrupt a task that has outrun its timeout, so the next interval can schedule a fresh one.
     * Written once and used for both the announce and the reconnect task of both aspects — DATA had
     * no watchdog at all before, so a wedged DATA announce stopped DATA announces permanently.
     */
    private void watchdog(String label, AtomicLong startedMs, AtomicReference<Future<?>> future,
                          ThreadPoolExecutor executor, long timeoutMs, long nowMs) {
        long taskStart = startedMs.get();
        if (taskStart == 0L || nowMs - taskStart <= timeoutMs) return;

        log.warn("{}: {} task running for {}s — interrupting stuck task",
                aspect, label, (nowMs - taskStart) / 1000);
        Future<?> f = future.get();
        if (f != null && !f.isDone()) f.cancel(true);
        executor.purge();
        startedMs.set(0L);
        consecutiveStuckTasks++;
        maybeForceBackboneReconnect();
    }

    private void taskFinished(AtomicLong startedMs) {
        // Reset counter only if the watchdog didn't fire — the watchdog sets startedMs=0 before
        // incrementing consecutiveStuckTasks, so a non-zero value here means we completed
        // without intervention.
        if (startedMs.get() != 0L) {
            consecutiveStuckTasks = 0;
        }
        startedMs.set(0L);
    }

    /**
     * Called when a stuck task is interrupted. When the threshold is reached, force-closes
     * the backbone TCP channel so the library's built-in auto-reconnect fires, clearing any
     * jobsLock deadlock caused by a zombie-link cull cascade.
     */
    private void maybeForceBackboneReconnect() {
        if (consecutiveStuckTasks < BACKBONE_FORCE_RECONNECT_THRESHOLD) return;
        consecutiveStuckTasks = 0; // reset so we don't spam per-interval
        log.warn("{}: {} consecutive stuck tasks — forcing backbone TCP reconnect to clear deadlock",
                aspect, BACKBONE_FORCE_RECONNECT_THRESHOLD);
        gateways.forceBackboneReconnect();
    }
}
