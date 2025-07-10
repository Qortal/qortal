package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Network;
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
			
			boolean isSocketClogged;
			int clogCounter = 0;
            do {
                isSocketClogged = peer.writeChannel();

				if (clogCounter > 9) {
					LOGGER.warn("10 Socket Clogs - GIVING UP");
					break;
				}
                if (isSocketClogged) {
                    LOGGER.info(
                            "socket is clogged: peer = {} {}, retrying",
                            peer.getPeerData().getAddress().toString(),
                            Thread.currentThread().getName()
                    );
                    Thread.sleep(1000);
					clogCounter++;
                }
				
            } while( isSocketClogged );
			
            // Tell Network that we've finished
            Network.getInstance().notifyChannelNotWriting(socketChannel);

            if (isSocketClogged)
                Network.getInstance().setInterestOps(this.socketChannel, SelectionKey.OP_WRITE);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("connection reset")) {
                peer.disconnect("Connection reset");
                return;
            }

            LOGGER.trace("[{}] Network thread {} encountered I/O error: {}", peer.getPeerConnectionId(),
                    Thread.currentThread().getId(), e.getMessage(), e);
            peer.disconnect("I/O error");
        }
    }
}