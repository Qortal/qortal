package org.qortal.crosschain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ChainableServerConnectionRecorder {

    private List<ChainableServerConnection> connections;
    private int limit;

    public ChainableServerConnectionRecorder(int limit) {
        this.connections = Collections.synchronizedList(new ArrayList<>(limit));
        this.limit = limit;
    }

    public ChainableServerConnection recordConnection(
            ChainableServer server, String requestedBy, boolean open, boolean success, String notes) {

        ChainableServerConnection connection
                = new ChainableServerConnection(server, requestedBy, open, success, System.currentTimeMillis(), notes);

        connections.add(connection);

        if( connections.size() > limit) {
            ChainableServerConnection firstConnection
                    = connections.stream().sorted(Comparator.comparing(ChainableServerConnection::getCurrentTimeMillis))
                    .findFirst().get();
            connections.remove(firstConnection);
        }
        return connection;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public List<ChainableServerConnection> getConnections() {
        return this.connections;
    }
}
