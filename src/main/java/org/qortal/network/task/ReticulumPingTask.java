package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.ReticulumPeer;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageType;
import org.qortal.network.message.PingMessage;
import org.qortal.network.message.MessageException;
import org.qortal.utils.ExecuteProduceConsume.Task;
import org.qortal.utils.NTP;

public class ReticulumPingTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PingTask.class);

    private final ReticulumPeer peer;
    private final Long now;
    private final String name;

    public ReticulumPingTask(ReticulumPeer peer, Long now) {
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
        //// only do a link-level ping
        //peer.pingRemote();

        PingMessage pingMessage = new PingMessage();

        // Note: Even though getResponse works, we can also use
        //       peer.sendMessage(pingMessage) for ReticulumPeer.
        //       More efficient as it is asynchronous.

        // Approach 1: getResponse()
        Message message = peer.getResponse(pingMessage);
        if (message == null || message.getType() != MessageType.PONG) {
            LOGGER.debug("[{}] Didn't receive reply from {} for PING ID {}",
                    peer.getPeerConnectionId(), peer, pingMessage.getId());
            // for Reticulum, ping is a keep-alive, not to steer connectivity.
            //peer.disconnect("no pong received");
            return;
        }
        peer.setLastPing(NTP.getTime() - now);


        //// Approach 2: sendMessage(), asynchronous the ReticulumPeer case.
        //// Note: We chose asynchronous for ping, no need to block queues for this.
        ////       However we'll have to artificially delay to avoid rapid firing pings.
        //peer.sendMessage(pingMessage);
        //peer.setLastPing(NTP.getTime() - now);
    }
}
