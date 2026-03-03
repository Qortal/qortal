package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Peer;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageType;
import org.qortal.network.message.PingMessage;
import org.qortal.utils.ExecuteProduceConsume.Task;
import org.qortal.utils.NTP;

import static org.qortal.network.Peer.SYNC_RESPONSE_TIMEOUT;

public class PingTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PingTask.class);

    private final Peer peer;
    private final Long now;
    private final String name;

    public PingTask(Peer peer, Long now) {
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
        LOGGER.trace("[{}] Sending PING to peer {}", peer.getPeerConnectionId(), peer);
        
        PingMessage pingMessage = new PingMessage();
        // Use shorter timeout - if peer doesn't respond to ping quickly, disconnect
        Message message = peer.getResponseWithTimeout(pingMessage, SYNC_RESPONSE_TIMEOUT);

        if (message == null || message.getType() != MessageType.PING) {
            LOGGER.trace("[{}] Didn't receive reply from {} for PING ID {}",
                    peer.getPeerConnectionId(), peer, pingMessage.getId());
            peer.disconnect("no ping received");
            return;
        }

        long rtt = NTP.getTime() - now;
        LOGGER.trace("[{}] Received PONG from peer {} (RTT: {}ms)", peer.getPeerConnectionId(), peer, rtt);
        peer.setLastPing(rtt);
    }
}
