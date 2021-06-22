package org.qortal.network;

import org.qortal.network.message.Message;

public class MessageTask implements Task {
    private final Peer peer;
    private final Message nextMessage;

    public MessageTask(Peer peer, Message nextMessage) {
        this.peer = peer;
        this.nextMessage = nextMessage;
    }

    @Override
    public String getName() {
        return "MessageTask::" + peer + "::" + nextMessage.getType();
    }

    @Override
    public void perform() throws InterruptedException {
        Network.getInstance().onMessage(peer, nextMessage);
    }
}
