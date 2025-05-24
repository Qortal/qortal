package org.qortal.data.arbitrary;

import org.qortal.network.RNSPeer;

import java.util.Objects;

public class RNSArbitraryRelayInfo {

    private final String hash58;
    private final String signature58;
    private final RNSPeer peer;
    private final Long timestamp;
    private final Long requestTime;
    private final Integer requestHops;

    public RNSArbitraryRelayInfo(String hash58, String signature58, RNSPeer peer, Long timestamp, Long requestTime, Integer requestHops) {
        this.hash58 = hash58;
        this.signature58 = signature58;
        this.peer = peer;
        this.timestamp = timestamp;
        this.requestTime = requestTime;
        this.requestHops = requestHops;
    }

    public boolean isValid() {
        return this.getHash58() != null && this.getSignature58() != null
                && this.getPeer() != null && this.getTimestamp() != null;
    }

    public String getHash58() {
        return this.hash58;
    }

    public String getSignature58() {
        return signature58;
    }

    public RNSPeer getPeer() {
        return peer;
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

    @Override
    public String toString() {
        return String.format("%s = %s, %s, %d", this.hash58, this.signature58, this.peer, this.timestamp);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this)
            return true;

        if (!(other instanceof RNSArbitraryRelayInfo))
            return false;

        RNSArbitraryRelayInfo otherRelayInfo = (RNSArbitraryRelayInfo) other;

        return this.peer == otherRelayInfo.getPeer()
                && Objects.equals(this.hash58, otherRelayInfo.getHash58())
                && Objects.equals(this.signature58, otherRelayInfo.getSignature58());
    }
}
