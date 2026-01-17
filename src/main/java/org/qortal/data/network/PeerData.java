package org.qortal.data.network;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.network.PeerAddress;
import org.qortal.network.PeerAddressFactory;
import org.qortal.network.RNSCommon.PeerType;
import org.qortal.network.ReticulumPeerAddress;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class PeerData {

	public static final int MAX_PEER_ADDRESS_SIZE = 255;

	// Properties

	// Don't expose this via JAXB - use pretty getter instead
	@XmlTransient
	@Schema(hidden = true)
	private PeerAddress peerAddress;

	private Long lastAttempted;
	private Long lastConnected;
	private Long lastMisbehaved;
	private Long addedWhen;
	private String addedBy;
    private PeerData peerData;
    private PeerType peerType;

	/** The number of consecutive times we failed to sync with this peer */
	private int failedSyncCount = 0;

	// Constructors

	// necessary for JAXB serialization
	protected PeerData() {
	}

	public PeerData(PeerAddress peerAddress, Long lastAttempted, Long lastConnected, Long lastMisbehaved, Long addedWhen, String addedBy) {
		this.peerAddress = peerAddress;
		this.lastAttempted = lastAttempted;
		this.lastConnected = lastConnected;
		this.lastMisbehaved = lastMisbehaved;
		this.addedWhen = addedWhen;
		this.addedBy = addedBy;
	}

	public PeerData(PeerAddress peerAddress, Long addedWhen, String addedBy) {
		this(peerAddress, null, null, null, addedWhen, addedBy);
	}

	public PeerData(PeerAddress peerAddress) {
		this(peerAddress, null, null, null, null, null);
        //this.peerType = PeerType.IP;
	}

    // Getters / setters

	// Don't let JAXB use this getter
	@XmlTransient
	@Schema(hidden = true)
	public PeerAddress getAddress() {
		return this.peerAddress;
	}

	public Long getLastAttempted() {
		return this.lastAttempted;
	}

	public void setLastAttempted(Long lastAttempted) {
		this.lastAttempted = lastAttempted;
	}

	public Long getLastConnected() {
		return this.lastConnected;
	}

	public void setLastConnected(Long lastConnected) {
		this.lastConnected = lastConnected;
	}

	public Long getLastMisbehaved() {
		return this.lastMisbehaved;
	}

	public void setLastMisbehaved(Long lastMisbehaved) {
		this.lastMisbehaved = lastMisbehaved;
	}

	public Long getAddedWhen() {
		return this.addedWhen;
	}

	public String getAddedBy() {
		return this.addedBy;
	}

	public int getFailedSyncCount() {
		return this.failedSyncCount;
	}

	public void setFailedSyncCount(int failedSyncCount) {
		this.failedSyncCount = failedSyncCount;
	}

	public void incrementFailedSyncCount() {
		this.failedSyncCount++;
	}

    public PeerType getPeerType() {
        return this.peerType;
    }

    public void setPeerType(PeerType pt) {
      this.peerType = pt;
    }

	// Pretty peerAddress getter for JAXB
	@XmlElement(name = "address")
	protected String getPrettyAddress() {
		return this.peerAddress.toString();
	}

}
