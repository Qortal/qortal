package org.qortal.crosschain;

public class ElectrumServerResponse {

    private ElectrumServer electrumServer;

    private Object response;

    public ElectrumServerResponse(ElectrumServer electrumServer, Object response) {
        this.electrumServer = electrumServer;
        this.response = response;
    }

    public ElectrumServer getElectrumServer() {
        return electrumServer;
    }

    public Object getResponse() {
        return response;
    }
}
