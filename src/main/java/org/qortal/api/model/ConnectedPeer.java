package org.qortal.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.network.PeerData;
import org.qortal.network.Handshake;
import org.qortal.network.Peer;
import org.qortal.network.reticulum.RNS;
import org.qortal.network.reticulum.ReticulumPeer;
import org.qortal.network.helper.PeerCapabilities;

import io.reticulum.link.Link;

import static org.apache.commons.codec.binary.Hex.encodeHexString;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.stream.Collectors;
import java.util.*;
import java.util.concurrent.TimeUnit;

@XmlAccessorType(XmlAccessType.FIELD)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectedPeer {

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    public Direction direction;
    public Handshake handshakeStatus;
    public Long lastPing;
    public Long connectedWhen;
    public Long peersConnectedWhen;

    public String address;
    public String version;

    public String nodeId;

    public Integer lastHeight;
    @Schema(example = "base58")
    public byte[] lastBlockSignature;
    public Long lastBlockTimestamp;
    public UUID connectionId;

    @Schema(description = "Capabilities as an array of maps")
    public List<Map<String, Object>> capabilities;

    public String age;
    public Boolean isTooDivergent;

    // Reticulum-specific fields. Populated only when the underlying peer is a ReticulumPeer;
    // null (and hence absent from the JSON) for IP peers. Note: 'direction' above already
    // conveys initiator-vs-incoming — for a ReticulumPeer isOutbound()==isInitiator, so an
    // initiator maps to OUTBOUND and an incoming peer to INBOUND.
    public String linkStatus;      // ACTIVE / PENDING / HANDSHAKE / STALE / CLOSED
    public String aspect;          // BASE (core) / DATA (qdn)
    public Long lastInbound;       // epoch millis of last inbound link traffic (real liveness signal)
    public Long rtt;               // link round-trip time (ms)
    public String linkId;          // per-link unique id (hex) — distinguishes concurrent links, incl. inbound
    public String destinationHash; // remote peer's destination hash (hex); only set for OUTBOUND/initiator links
    public Boolean reachable;      // inverse of RNS.isUnreachable()

    // Needed for DeSerialization
    public ConnectedPeer() {
    }

    public ConnectedPeer(Peer peer) {

        this.direction = peer.isOutbound() ? Direction.OUTBOUND : Direction.INBOUND;
        //this.handshakeStatus = peer.getHandshakeStatus();
        //this.lastPing = peer.getLastPing();
        this.handshakeStatus = peer.getHandshakeStatus();
        this.lastPing = peer.getLastPing();

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

        if (peer.getPeersCapabilities() != null && peer.getPeersCapabilities().size() > 0) {
            capabilities = peer.getPeersCapabilities().getPeerCapabilities().entrySet().stream()
                    .map(entry -> {
                        Object value = entry.getValue();
                        // If value is a Map with "value" key, extract the actual value
                        if (value instanceof Map) {
                            Map<?, ?> valueMap = (Map<?, ?>) value;
                            if (valueMap.containsKey("value")) {
                                value = valueMap.get("value");
                            }
                        }
                        // Create a single-entry map with the capability key and unwrapped value
                        Map<String, Object> capabilityMap = new LinkedHashMap<>();
                        capabilityMap.put(entry.getKey(), value);
                        return capabilityMap;
                    })
                    .collect(Collectors.toList());
        }

        if (peer.getPeerType() == Peer.NETWORK) {
            BlockSummaryData peerChainTipData = peer.getChainTipData();
            if (peerChainTipData != null) {
                this.lastHeight = peerChainTipData.getHeight();
                this.lastBlockSignature = peerChainTipData.getSignature();
                this.lastBlockTimestamp = peerChainTipData.getTimestamp();
            }
        }

        // Only include isTooDivergent decision if we've had the opportunity to request block summaries this peer
        if (peer.getLastTooDivergentTime() != null) {
            this.isTooDivergent = Controller.wasRecentlyTooDivergent.test(peer);
        }

        // Surface Reticulum-specific link details for mesh peers. Wrapped in try/catch so a
        // link tearing down mid-read (e.g. peerLink nulled by another thread) can't break the
        // whole peer list — the affected peer simply reports whatever fields resolved.
        if (peer instanceof ReticulumPeer) {
            try {
                ReticulumPeer rnsPeer = (ReticulumPeer) peer;

                if (rnsPeer.getPeerAspect() != null) {
                    this.aspect = rnsPeer.getPeerAspect().name();
                }

                // destinationHash is only the REMOTE peer's hash for OUTBOUND/initiator links. For
                // INBOUND links ReticulumPeer.getDestinationHash() returns *our own* local aspect
                // destination (every inbound BASE peer would report the same hash, making distinct
                // links look like duplicates), and the remote's identity is unknown because the
                // initiator does not identify() over the link. So only surface it when we initiated.
                if (Boolean.TRUE.equals(rnsPeer.getIsInitiator()) && rnsPeer.getDestinationHash() != null) {
                    this.destinationHash = encodeHexString(rnsPeer.getDestinationHash());
                }

                // Real peer version, advertised in the announce appData (QAN1) and stored on the peer
                // (see RNS.getNewPeer / onIncomingPeerIdentified). Falls back to the "6.1.0" floor
                // until that peer's announce has been processed.
                this.version = rnsPeer.getPeersVersionString();

                Link link = rnsPeer.getPeerLink();
                if (link != null) {
                    if (link.getStatus() != null) {
                        this.linkStatus = link.getStatus().name();
                    }
                    if (link.getLastInbound() != null) {
                        this.lastInbound = link.getLastInbound().toEpochMilli();
                    }
                    this.rtt = link.getRtt();
                    // Per-link unique id — the reliable way to tell concurrent links apart, including
                    // multiple inbound links that all share our local destination hash above.
                    if (link.getLinkId() != null) {
                        this.linkId = encodeHexString(link.getLinkId());
                    }
                }

                // For OUTBOUND links the base constructor already set address/nodeId to the remote's
                // destination hash (== destinationHash above) — correct and useful. For INBOUND links
                // both were derived from getPeersNodeId()/peerData, which resolve to *our own* local
                // aspect destination, so every inbound peer of an aspect looked identical. The remote's
                // real node id/address is unknown (no identify() over the link), so surface the per-link
                // id instead: it keeps these fields populated and unique per inbound connection.
                if (!Boolean.TRUE.equals(rnsPeer.getIsInitiator()) && this.linkId != null) {
                    this.nodeId = this.linkId;
                    this.address = this.linkId;
                }

                this.reachable = !RNS.getInstance().isUnreachable(rnsPeer);
            } catch (Exception e) {
                // Best-effort enrichment only; leave any unresolved fields null.
            }
        }
    }

}
