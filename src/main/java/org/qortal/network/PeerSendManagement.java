package org.qortal.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PeerSendManagement {

    private static final Logger LOGGER = LogManager.getLogger(PeerSendManagement.class);

    private final Map<String, PeerSendManager> peerSendManagers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public PeerSendManager getOrCreateSendManager(Peer peer, boolean isNetworkDataPeer) {
        return peerSendManagers.computeIfAbsent(peer.toString(), p -> new PeerSendManager(peer, isNetworkDataPeer));
    }

    /**
     * Retrieves an existing PeerSendManager for the given peer without creating a new one.
     * 
     * @param peer the peer to look up
     * @return the PeerSendManager if it exists, null otherwise
     */
    public PeerSendManager getSendManager(Peer peer) {
        return peerSendManagers.get(peer.toString());
    }

    /**
     * Immediately removes and shuts down the PeerSendManager for the given peer.
     * 
     * <p>This method should be called when a peer disconnects to ensure immediate cleanup of:
     * <ul>
     *   <li>Background thread (PeerSendManager's executor)</li>
     *   <li>Queued messages (clearing memory)</li>
     *   <li>Any pending send operations</li>
     * </ul>
     * 
     * <p>Without this immediate cleanup, the PeerSendManager would remain in memory until
     * the periodic cleanup task detects it as idle (2-7 minutes later), wasting resources
     * and potentially attempting to send messages to a closed socket.
     * 
     * @param peer the peer that has disconnected
     */
    public void removeSendManager(Peer peer) {
        PeerSendManager manager = peerSendManagers.remove(peer.toString());
        if (manager != null) {
            manager.shutdown();
            LOGGER.debug("Immediately cleaned up PeerSendManager for disconnected peer {}", peer);
        }
    }

    //public PeerSendManager getOrCreateSendManager(ReticulumPeer peer) {
    //    return peerSendManagers.computeIfAbsent(peer.toString(), p -> new PeerSendManager(peer));
    //}

    private PeerSendManagement() {
        this.cleaner = Executors.newSingleThreadScheduledExecutor();

        // Clean up stale hash tracking every 60s
        cleaner.scheduleAtFixedRate(() -> {
            for (PeerSendManager manager : peerSendManagers.values()) {
                try {
                    manager.cleanupStaleHashTracking();
                } catch (Exception e) {
                    LOGGER.warn("Error cleaning stale hash tracking for peer {}: {}", manager.getPeer(), e.getMessage());
                }
            }
        }, 60, 60, TimeUnit.SECONDS);

        cleaner.scheduleAtFixedRate(() -> {
            long idleCutoff = TimeUnit.MINUTES.toMillis(2);
            Iterator<Map.Entry<String, PeerSendManager>> iterator = peerSendManagers.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<String, PeerSendManager> entry = iterator.next();

                PeerSendManager manager = entry.getValue();
                Peer peer = manager.getPeer();

                if (manager.isIdle(idleCutoff)) {
                    // Only shut down if peer is disconnected
                    // Keep PeerSendManager alive for connected peers even if idle
                    if (peer.getSocketChannel() == null || 
                        !peer.getSocketChannel().isOpen() || 
                        peer.isStopping()) {
                        iterator.remove(); // SAFE removal during iteration
                        manager.shutdown();
                        LOGGER.debug("Cleaned up PeerSendManager for disconnected peer {}", entry.getKey());
                    } else {
                        // Peer is still connected but idle - don't shut down, just log
                        LOGGER.trace("PeerSendManager for {} is idle but peer still connected - keeping alive", entry.getKey());
                    }
                }
            }
        }, 0, 5, TimeUnit.MINUTES);
    }

    /**
     * Shuts down the scheduler and all peer send managers. Call during application shutdown.
     */
    public void shutdown() {
        try {
            cleaner.shutdown();
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("PeerSendManagement cleaner did not terminate in time, forcing shutdown");
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while shutting down PeerSendManagement, forcing shutdown");
            Thread.currentThread().interrupt();
            cleaner.shutdownNow();
        }
        for (PeerSendManager manager : peerSendManagers.values()) {
            try {
                manager.shutdown();
            } catch (Exception e) {
                LOGGER.warn("Error shutting down PeerSendManager: {}", e.getMessage());
            }
        }
        peerSendManagers.clear();
        LOGGER.info("PeerSendManagement shutdown complete");
    }

    private static PeerSendManagement instance;

    public static PeerSendManagement getInstance() {

        if( instance == null ) {
            instance = new PeerSendManagement();
        }

        return instance;
    }
}
