package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.RNSPeer;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageType;
import org.qortal.network.message.PingMessage;
import org.qortal.network.message.MessageException;
import org.qortal.utils.ExecuteProduceConsume.Task;
import org.qortal.utils.NTP;

public class RNSPingTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PingTask.class);

    private final RNSPeer peer;
    private final Long now;
    private final String name;

    public RNSPingTask(RNSPeer peer, Long now) {
        this.peer = peer;
        this.now = now;
        this.name = "PingTask::" + peer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        PingMessage pingMessage = new PingMessage();

        // Note: Even though getResponse would work, we can use
        //       peer.sendMessage(pingMessage) using Reticulum buffer instead.
        //       More efficient and saves room for other request/response tasks.
        peer.getResponse(pingMessage);
        //peer.sendMessage(pingMessage);

        //// task is not over here (Reticulum is asynchronous)
        //peer.setLastPing(NTP.getTime() - now);
    }
}
