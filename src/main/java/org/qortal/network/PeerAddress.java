package org.qortal.network;

import org.qortal.repository.DataException;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

public interface PeerAddress {

    InetSocketAddress toSocketAddress() throws UnknownHostException;

    // ReticulumPeer "address"
    default byte[] getDestinationHash() { return null; }
    default void setDestinationHash(byte[] hash) { return; }

    // IPPeer "address" components
    default String getHost() { return null; }
    default int getPort() { return -1; }

    static PeerAddress fromString(String addressString) { return null;}
}

