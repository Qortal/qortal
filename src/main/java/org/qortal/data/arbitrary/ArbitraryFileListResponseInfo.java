package org.qortal.data.arbitrary;

import org.qortal.network.Peer;

/**
 * Stores response information for arbitrary file list requests.
 * 
 * Note: Inherits from ArbitraryRelayInfo which stores PeerData (lightweight) 
 * instead of Peer (heavy) to prevent memory leaks.
 */
public class ArbitraryFileListResponseInfo extends ArbitraryRelayInfo {

    public ArbitraryFileListResponseInfo(String hash58, String signature58, Peer peer, String nodeId, Long timestamp, Long requestTime, Integer requestHops, Boolean isDirectConnectable) {
        super(hash58, signature58, peer, nodeId, timestamp, requestTime, requestHops, isDirectConnectable);
        //    Chunk , File       , peer
    }

}
