package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Network;
import org.qortal.network.NetworkData;
import org.qortal.network.Peer;
import org.qortal.utils.DaemonThreadFactory;
import org.qortal.utils.ExecuteProduceConsume.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PeerConnectTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PeerConnectTask.class);
    private static final ExecutorService connectionExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory(8));

    private final Peer peer;
    private final String name;

    public PeerConnectTask(Peer peer) {
        this.peer = peer;
        this.name = "PeerConnectTask::" + peer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        // Submit connection task to a dedicated thread pool for non-blocking I/O
        connectionExecutor.submit(() -> {
            try {
                connectPeerAsync(peer);
            } catch (InterruptedException e) {
                LOGGER.error("Connection attempt interrupted for peer {}", peer, e);
                Thread.currentThread().interrupt();  // Reset interrupt flag
            }
        });
    }

    private void connectPeerAsync(Peer peer) throws InterruptedException {
        // Perform peer connection in a separate thread to avoid blocking main task execution
        try {
            switch (peer.getPeerType()) {
                case Peer.NETWORK:
                    Network.getInstance().connectPeer(peer);
                    LOGGER.trace("Called connectPeer {} on NETWORK", peer);
                    break;
                case Peer.NETWORKDATA:
                    NetworkData.getInstance().connectPeer(peer);
                    LOGGER.trace("Called connectPeer {} on QDN", peer);
                    break;
            }

        } catch (Exception e) {
            LOGGER.error("Error connecting to peer {}", peer, e);
        }
    }
}
