package org.qortal.network;

import org.qortal.controller.Controller;

public class BroadcastTask implements Task {
    @Override
    public String getName() {
        return "BroadcastTask";
    }

    @Override
    public void perform() throws InterruptedException {
        Controller.getInstance().doNetworkBroadcast();
    }
}
