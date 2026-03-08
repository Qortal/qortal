package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Network;
import org.qortal.network.NetworkData;
import org.qortal.network.Peer;
import org.qortal.utils.ExecuteProduceConsume.Task;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ChannelWriteTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(ChannelWriteTask.class);

    private final SocketChannel socketChannel;
    private final Peer peer;
    private final String name;

    public ChannelWriteTask(SocketChannel socketChannel, Peer peer) {
        this.socketChannel = socketChannel;
        this.peer = peer;
        this.name = "ChannelWriteTask::" + peer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        try {
            // Call writeChannel() once - do NOT loop
            // In non-blocking IO, selector decides when to call us again
            boolean needsMoreWriting = peer.writeChannel();

            // ALWAYS remove from pending first (task is about to exit)
            // This allows new tasks to be created for this channel
            switch (peer.getPeerType()) {
                case Peer.NETWORKDATA:
                    NetworkData.getInstance().notifyChannelNotWriting(socketChannel);
                    break;
                default:
                    Network.getInstance().notifyChannelNotWriting(socketChannel);
                    break;
            }

            // Then re-arm OP_WRITE if we still have pending data
            // The wakeup() call in setInterestOps() ensures selector wakes immediately
            if (needsMoreWriting) {
                switch (peer.getPeerType()) {
                    case Peer.NETWORKDATA:
                        NetworkData.getInstance().setInterestOps(this.socketChannel, SelectionKey.OP_WRITE);
                        break;
                    default:
                        Network.getInstance().setInterestOps(this.socketChannel, SelectionKey.OP_WRITE);
                        break;
                }
            }
        } catch (IOException e) {
            // On error, ensure we remove from pending to avoid deadlock
            try {
                switch (peer.getPeerType()) {
                    case Peer.NETWORKDATA:
                        NetworkData.getInstance().notifyChannelNotWriting(socketChannel);
                        break;
                    default:
                        Network.getInstance().notifyChannelNotWriting(socketChannel);
                        break;
                }
            } catch (Exception cleanupError) {
                LOGGER.warn("Error during cleanup: {}", cleanupError.getMessage());
            }
            
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("connection reset")) {
                peer.disconnect("Connection reset");
                return;
            }

            String networkLabel = (peer.getPeerType() == Peer.NETWORKDATA) ? "NetworkData" : "Network";
            LOGGER.debug("[{}] {} thread {} encountered I/O error: {} - {}", peer.getPeerConnectionId(),
                    networkLabel, Thread.currentThread().getId(), e.getMessage(), peer, e);
            peer.disconnect("I/O error");
        }
    }
}