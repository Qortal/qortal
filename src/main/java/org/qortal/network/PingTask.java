package org.qortal.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageType;
import org.qortal.network.message.PingMessage;
import org.qortal.utils.NTP;

public class PingTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PingTask.class);
    private final Peer peer;
    private final long now;

    public PingTask(Peer peer, Long now) {
        this.peer = peer;
        this.now = now;
    }

    @Override
    public String getName() {
        return "PingTask::" + peer;
    }

    @Override
    public void perform() throws InterruptedException {
        PingMessage pingMessage = new PingMessage();
        Message message = peer.getResponse(pingMessage);

        if (message == null || message.getType() != MessageType.PING) {
            LOGGER.debug("[{}] Didn't receive reply from {} for PING ID {}", peer.getPeerConnectionId(), peer,
                    pingMessage.getId());
            peer.disconnect("no ping received");
            return;
        }

        peer.setLastPing(NTP.getTime() - now);
    }
}
