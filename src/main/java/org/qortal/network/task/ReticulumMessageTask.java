package org.qortal.network.task;

import org.qortal.network.Network;
import org.qortal.network.reticulum.ReticulumPeer;
import org.qortal.network.message.Message;
import org.qortal.utils.ExecuteProduceConsume.Task;

public class ReticulumMessageTask implements Task {
    private final ReticulumPeer peer;
    private final Message nextMessage;
    private final String name;

    public ReticulumMessageTask(ReticulumPeer peer, Message nextMessage) {
        this.peer = peer;
        this.nextMessage = nextMessage;
        this.name = "MessageTask::" + peer + "::" + nextMessage.getType();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        //RNSNetwork.getInstance().onMessage(peer, nextMessage);
        // TODO: what do we do in the Reticulum case?
        // Note: this is automatically handled (asynchronously) by the RNSPeer peerBufferReady callback
    }
}
