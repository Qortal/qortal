package org.qortal.data.arbitrary;

import org.qortal.data.network.PeerData;
import org.qortal.network.Peer;
import org.qortal.network.PeerList;

import java.util.Objects;

/**
 * Stores relay information for arbitrary data file requests.
 * 
 * Note: Stores PeerData (lightweight) instead of Peer (heavy) to prevent memory leaks.
 * The Peer object can be resolved on-demand from connected peers when needed.
 */
public class ArbitraryRelayInfo {

    private final String hash58;
    private final String signature58;
    private final PeerData peerData;  // Store lightweight PeerData instead of heavy Peer object
    private final String nodeId;
    private final Long timestamp;
    private final Long requestTime;
    private final Integer requestHops;
    private final Boolean isDirectConnectable;

    public ArbitraryRelayInfo(String hash58, String signature58, Peer peer, String nodeId, Long timestamp, Long requestTime, Integer requestHops, Boolean isDirectConnectable) {
        this.hash58 = hash58;
        this.signature58 = signature58;
        this.peerData = peer != null ? peer.getPeerData() : null;  // Extract PeerData immediately
        this.nodeId = nodeId;
        this.timestamp = timestamp;
        this.requestTime = requestTime;
        this.requestHops = requestHops;
        this.isDirectConnectable = isDirectConnectable;
    }

    public boolean isValid() {
        return this.getHash58() != null && this.getSignature58() != null
                && this.getPeerData() != null && this.getTimestamp() != null;
    }

    public String getHash58() {
        return this.hash58;
    }

    public String getSignature58() {
        return signature58;
    }

    public String getNodeId() {
        return this.nodeId;
    }

    /**
     * Returns the stored PeerData (lightweight).
     * Use getPeer(PeerList) to resolve the actual Peer object when needed.
     */
    public PeerData getPeerData() {
        return peerData;
    }

    /**
     * Resolves and returns the Peer object from connected peers.
     * Returns null if the peer is no longer connected.
     * 
     * @param connectedPeers the list of currently connected peers
     * @return the Peer object if connected, null otherwise
     */
    public Peer getPeer(PeerList connectedPeers) {
        if (peerData == null || connectedPeers == null) {
            return null;
        }
        return connectedPeers.get(peerData);
    }

    /**
     * @deprecated Use getPeer(PeerList) instead to resolve Peer on-demand.
     * This method is kept for backwards compatibility but returns null.
     */
    @Deprecated
    public Peer getPeer() {
        // Return null to force callers to use getPeer(PeerList) instead
        // This prevents holding stale Peer references
        return null;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public Long getRequestTime() {
        return this.requestTime;
    }

    public Integer getRequestHops() {
        return this.requestHops;
    }

    public Boolean isDirectConnectable() { return this.isDirectConnectable; }

    @Override
    public String toString() {
        return String.format("%s = %s, %s, %d", this.hash58, this.signature58, this.peerData, this.timestamp);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this)
            return true;

        if (!(other instanceof ArbitraryRelayInfo))
            return false;

        ArbitraryRelayInfo otherRelayInfo = (ArbitraryRelayInfo) other;

        // Compare by PeerData instead of Peer object identity
        return Objects.equals(this.peerData, otherRelayInfo.getPeerData())
                && Objects.equals(this.hash58, otherRelayInfo.getHash58())
                && Objects.equals(this.signature58, otherRelayInfo.getSignature58());
    }
}
