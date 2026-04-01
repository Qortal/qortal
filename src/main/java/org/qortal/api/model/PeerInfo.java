package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/**
 * Information about a single peer available for downloading chunks.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class PeerInfo {
    
    public enum Speed {
        HIGH,   // RTT < 5000ms
        LOW,    // RTT between 5000ms and 10000ms
        IDLE    // RTT > 10000ms or no RTT data
    }
    
    /**
     * Last 10 digits of the peer's node ID
     */
    public String id;
    
    /**
     * Speed classification based on round-trip time
     */
    public Speed speed;
    
    /**
     * True if peer is directly connected (requestHops == 0 or isDirectConnectable)
     */
    public boolean isDirect;
    
    /**
     * Number of chunks this peer has available for the current request.
     */
    public int chunksAvailable;
    
    /**
     * Default constructor for JAXB serialization.
     */
    public PeerInfo() {
    }
    
    public PeerInfo(String id, Speed speed, boolean isDirect) {
        this(id, speed, isDirect, 0);
    }
    
    public PeerInfo(String id, Speed speed, boolean isDirect, int chunksAvailable) {
        this.id = id;
        this.speed = speed;
        this.isDirect = isDirect;
        this.chunksAvailable = chunksAvailable;
    }
}

