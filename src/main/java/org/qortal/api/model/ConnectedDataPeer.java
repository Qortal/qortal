package org.qortal.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.qortal.data.network.PeerData;
import org.qortal.network.Handshake;
import org.qortal.network.Peer;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.*;
import java.util.concurrent.TimeUnit;

@XmlAccessorType(XmlAccessType.FIELD)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectedDataPeer {

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    public Direction direction;
    public Handshake handshakeStatus;
    public Long lastAccessed;
    public Long connectedWhen;
    public Long peersConnectedWhen;
    public String address;
    public String version;
    public String nodeId;
    public UUID connectionId;
    public String age;

    // Needed for DeSerialization
    public ConnectedDataPeer() {
    }

    public ConnectedDataPeer(Peer peer) {
        this.direction = peer.isOutbound() ? Direction.OUTBOUND : Direction.INBOUND;
        this.handshakeStatus = peer.getHandshakeStatus();
        this.lastAccessed = peer.getLastQDNUse();

        PeerData peerData = peer.getPeerData();
        this.connectedWhen = peer.getConnectionTimestamp();
        this.peersConnectedWhen = peer.getPeersConnectionTimestamp();

        this.address = peerData.getAddress().toString();

        this.version = peer.getPeersVersionString();
        this.nodeId = peer.getPeersNodeId();
        this.connectionId = peer.getPeerConnectionId();

        // Calculate connection age
        if (peer.getConnectionEstablishedTime() > 0) {
            long age = (System.currentTimeMillis() - peer.getConnectionEstablishedTime());
            long minutes = TimeUnit.MILLISECONDS.toMinutes(age);
            long seconds = TimeUnit.MILLISECONDS.toSeconds(age) - TimeUnit.MINUTES.toSeconds(minutes);
            this.age = String.format("%dm %ds", minutes, seconds);
        } else {
            this.age = "connecting...";
        }

    }

}
