package org.qortal.network.reticulum;

import io.reticulum.Transport;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.backbone.BackboneClientInterface;
import io.reticulum.utils.InterfaceUtils;
import lombok.extern.slf4j.Slf4j;
import org.qortal.settings.Settings;
import org.qortal.utils.NamedThreadFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Gateway discovery: which host this node advertises, and whether to dial a gateway another node
 * announced.
 * <p>
 * When {@code reticulumAnnounceGateway} is enabled a node embeds its own backbone server endpoint
 * in every announce; receivers can then add a {@link BackboneClientInterface} dynamically instead
 * of every node needing gateways hardcoded in settings.
 */
@Slf4j
final class RNSGatewayManager {

    private static final Duration GATEWAY_COOLDOWN = Duration.ofMinutes(10);

    private final String appName;
    private final int targetPort;

    /** Local FQDN cached at first use (whatever JDK returns — used only for self-skip). */
    private volatile String localFqdn;
    /** Host this node will advertise (either explicit setting or validated auto-detect). null = don't advertise. */
    private volatile String advertiseHost;
    /** Guards one-time logging of the chosen advertise host. */
    private volatile boolean advertiseHostResolved = false;

    /** host:port → last time we considered adding (success or skip), to throttle churn. */
    private final Map<String, Instant> recentAttempts = new ConcurrentHashMap<>();

    /**
     * Dialling happens here, never on the caller's thread. maybeAddDynamicGateway() runs on
     * Reticulum's announce-delivery thread; launching an interface does a TCP connect, so an
     * unreachable announced gateway would stall announce processing for every peer.
     * Bounded queue with DiscardPolicy: a dial we drop is retried after the cooldown anyway.
     */
    private final ThreadPoolExecutor dialExecutor;

    RNSGatewayManager(String appName, int targetPort, int threadPriority) {
        this.appName = appName;
        this.targetPort = targetPort;
        this.dialExecutor = new ThreadPoolExecutor(1, 1, 5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8),
                new NamedThreadFactory("RNS-GatewayDial", threadPriority),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * Returns the host string this node will advertise (explicit setting, or validated
     * auto-detect), or null if no usable host could be determined. Logs the decision once at first
     * call. Cached for subsequent calls.
     */
    String getAdvertiseHost() {
        if (advertiseHostResolved) return advertiseHost;

        synchronized (this) {
            if (advertiseHostResolved) return advertiseHost;

            String explicit = Settings.getInstance().getReticulumAnnouncedHost();
            String chosen;
            String source;
            if (explicit != null && !explicit.trim().isEmpty()) {
                chosen = explicit.trim();
                source = "reticulumAnnouncedHost setting";
            } else {
                String detected = getLocalFqdn();
                if (RNSAnnounceCodec.isUsableAdvertiseHost(detected)) {
                    chosen = detected;
                    source = "auto-detected FQDN";
                } else {
                    chosen = null;
                    source = "auto-detected FQDN '" + detected + "' is not usable";
                }
            }

            if (chosen != null) {
                log.info("Reticulum gateway announce: will advertise host '{}:{}' (source: {})",
                        chosen, targetPort, source);
            } else {
                log.warn("Reticulum gateway announce: no usable host to advertise ({}); "
                        + "set reticulumAnnouncedHost to your publicly-resolvable FQDN to enable",
                        source);
            }
            advertiseHost = chosen;
            advertiseHostResolved = true;
            return chosen;
        }
    }

    /**
     * Cached on first use. Reads canonical hostname so a peer hearing our
     * announce echo (looped back via a relay) doesn't trigger a self-connect.
     */
    private String getLocalFqdn() {
        String fqdn = localFqdn;
        if (fqdn == null) {
            try {
                fqdn = InetAddress.getLocalHost().getCanonicalHostName();
            } catch (Exception e) {
                log.warn("Cannot resolve local FQDN for gateway announce: {}", e.getMessage());
                fqdn = "";
            }
            localFqdn = fqdn;
        }
        return fqdn;
    }

    /**
     * Decide whether to dial a peer-advertised gateway and, if yes, dynamically
     * register a {@link BackboneClientInterface}. Enforces:
     *   - self-skip (advertised host == our FQDN)
     *   - dedup against existing interfaces (same target host/port)
     *   - per-endpoint cooldown to prevent churn from repeated announces
     *   - total initiator backbone-client cap = reticulumDesiredClientInterfaces
     *   - IFAC setup using the configured passphrase and network name
     * <p>
     * Every check above is cheap and runs on the caller's thread, so cooldown stamping and
     * dedup stay deterministic; only the blocking interface launch is handed to the dial
     * executor. All failure paths log at WARN/DEBUG and return — never throw to caller.
     */
    void maybeAddDynamicGateway(String host, int port) {
        if (host == null || host.isEmpty() || port < 1 || port > 0xFFFF) return;
        String endpoint = host + ":" + port;

        // Reject misconfigured peer announces (localhost, loopback, single-label
        // names). Without this guard, a sender whose auto-detected FQDN was bad
        // would have every receiver pointlessly try to dial 127.0.0.1, its own
        // hostname, etc.
        if (!RNSAnnounceCodec.isUsableAdvertiseHost(host)) {
            log.debug("Gateway announce: dropping unusable host '{}' (likely misconfigured peer)", host);
            return;
        }

        // Self-skip: compare against both our advertised name (if any) and our
        // local FQDN (whatever JDK returned, even if not usable for advertising).
        // Belt-and-suspenders so we don't dial ourselves via either route.
        if (host.equalsIgnoreCase(getLocalFqdn())) {
            log.debug("Gateway announce: ignoring self via local FQDN ({}:{})", host, port);
            return;
        }
        String myAdvertise = getAdvertiseHost();
        if (myAdvertise != null && host.equalsIgnoreCase(myAdvertise)) {
            log.debug("Gateway announce: ignoring self via advertised host ({}:{})", host, port);
            return;
        }

        // Cooldown: skip if we've considered this endpoint recently.
        Instant now = Instant.now();
        // Evict entries that are past the cooldown: without this the map keeps one entry per
        // host:port ever announced, for the process lifetime, on a mesh-wide announce stream.
        // Entries older than the cooldown can no longer suppress anything, so dropping them is
        // behaviour-neutral.
        recentAttempts.values().removeIf(t -> Duration.between(t, now).compareTo(GATEWAY_COOLDOWN) >= 0);
        Instant last = recentAttempts.get(endpoint);
        if (last != null && Duration.between(last, now).compareTo(GATEWAY_COOLDOWN) < 0) {
            return; // silently — this is the steady-state path on repeated announces
        }
        recentAttempts.put(endpoint, now);

        // Already have an interface to this endpoint? (Static, prior-dynamic, or
        // matching by autoconnect hash.)
        for (ConnectionInterface iface : Transport.getInstance().getInterfaces()) {
            if (host.equalsIgnoreCase(iface.getTargetHost()) && port == iface.getTargetPort()) {
                log.debug("Gateway {} already has a configured interface; skipping", endpoint);
                return;
            }
        }

        // Cap: count initiator BackboneClientInterface instances (static + dynamic);
        // the user-configured cap caps the total, not just the dynamic share.
        int maxClients = Settings.getInstance().getReticulumDesiredClientInterfaces();
        long currentInitiators = Transport.getInstance().getInterfaces().stream()
                .filter(i -> i instanceof BackboneClientInterface
                        && ((BackboneClientInterface) i).isInitiator())
                .count();
        if (currentInitiators >= maxClients) {
            log.debug("Gateway {}: at client interface cap ({} >= {}); not adding",
                    endpoint, currentInitiators, maxClients);
            return;
        }

        log.info("Dynamically adding announced backbone gateway {} (initiators {}/{})",
                endpoint, currentInitiators + 1, maxClients);

        try {
            dialExecutor.execute(() -> dial(host, port, endpoint));
        } catch (RejectedExecutionException e) {
            log.debug("Gateway {}: dial executor unavailable; skipping", endpoint);
        }
    }

    /** Builds, IFAC-initialises and launches the interface. Blocking — dial executor only. */
    private void dial(String host, int port, String endpoint) {
        try {
            BackboneClientInterface iface = new BackboneClientInterface();
            iface.setInterfaceName("Backbone Client Interface qortal " + host + " (announced)");
            iface.setTargetHost(host);
            iface.setTargetPort(port);
            iface.setEnabled(true);

            // IFAC setup — same passphrase as the static interfaces, otherwise
            // the handshake against an IFAC-protected backbone server will fail.
            String networkName = Settings.getInstance().getReticulumNetworkName();
            if (networkName == null || networkName.isEmpty()) networkName = appName;
            iface.setIfacNetName(networkName);
            String passphrase = Settings.getInstance().getReticulumPassphrase();
            if (passphrase != null && !passphrase.isEmpty()) {
                iface.setIfacNetKey(passphrase);
            }
            if (!InterfaceUtils.initIFac(iface)) {
                log.warn("Gateway {}: IFAC init returned false; aborting dynamic add", endpoint);
                return;
            }

            Transport.getInstance().getInterfaces().add(iface);
            iface.launch();
        } catch (Exception e) {
            log.warn("Gateway {}: failed to register dynamic backbone client interface: {}",
                    endpoint, e.getMessage());
        }
    }

    /**
     * Force-close every backbone TCP channel so the library's built-in auto-reconnect fires.
     * Called by the aspect loops' circuit breaker when tasks keep wedging: a stuck jobsLock is
     * usually a zombie-link cull cascade that only a fresh connection clears.
     */
    void forceBackboneReconnect() {
        for (ConnectionInterface iface : Transport.getInstance().getInterfaces()) {
            if (iface instanceof BackboneClientInterface) {
                ((BackboneClientInterface) iface).forceReconnect();
            }
        }
    }

    void shutdown() {
        dialExecutor.shutdownNow();
    }
}
