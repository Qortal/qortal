package org.qortal.data.arbitrary;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class ArbitraryDirectConnectionInfo {

    private final byte[] signature;
    private final String peerAddress;
    private final String nodeId;
    private final List<byte[]> hashes;
    private final long timestamp;

    public ArbitraryDirectConnectionInfo(byte[] signature, String peerAddress, String nodeId, List<byte[]> hashes, long timestamp) {
        this.signature = signature;
        this.peerAddress = peerAddress;
        this.nodeId = nodeId;
        this.hashes = hashes;
        this.timestamp = timestamp;
    }

    public byte[] getSignature() {
        return this.signature;
    }

    public String getPeerAddress() {
        return this.peerAddress;
    }

    public String getNodeId() {
        return this.nodeId;
    }

    public List<byte[]> getHashes() {
        return this.hashes;
    }
    
    public long getTimestamp() {
        return this.timestamp;
    }

    public int getHashCount() {
        if (this.hashes == null) {
            return 0;
        }
        return this.hashes.size();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this)
            return true;

        if (!(other instanceof ArbitraryDirectConnectionInfo))
            return false;

        ArbitraryDirectConnectionInfo otherDirectConnectionInfo = (ArbitraryDirectConnectionInfo) other;

        return Arrays.equals(this.signature, otherDirectConnectionInfo.getSignature())
                && Objects.equals(this.peerAddress, otherDirectConnectionInfo.getPeerAddress())
                && Objects.equals(this.nodeId, otherDirectConnectionInfo.getNodeId())
                && Objects.equals(this.hashes, otherDirectConnectionInfo.getHashes())
                && Objects.equals(this.timestamp, otherDirectConnectionInfo.getTimestamp());
    }
}
