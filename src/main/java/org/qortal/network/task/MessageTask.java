package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Network;
import org.qortal.network.NetworkData;
import org.qortal.network.Peer;
import org.qortal.network.message.Message;
import org.qortal.utils.ExecuteProduceConsume.Task;

public class MessageTask implements Task {
    private final Peer peer;
    private final Message nextMessage;
    private final String name;
    private static final Logger LOGGER = LogManager.getLogger(MessageTask.class);

    public MessageTask(Peer peer, Message nextMessage, int network) {
        this.peer = peer;
        this.peer.setPeerType(network);
        this.nextMessage = nextMessage;
        this.name = "MessageTask::" + peer + "::" + nextMessage.getType();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        try {
            LOGGER.trace("Routing Message to network {}", peer.getPeerType());

            if (peer.getPeerType() == Peer.NETWORKDATA)
                NetworkData.getInstance().onMessage(peer, nextMessage);
            else
                Network.getInstance().onMessage(peer, nextMessage);
        } finally {
            // Defensive reset - onHandshakingMessage has its own finally, but this catches gaps
            // Safe to call multiple times (idempotent) and ensures we never leak a stuck flag
            peer.resetHandshakeMessagePending();
        }
    }
}
