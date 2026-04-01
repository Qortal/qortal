package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

/**
 * Information about the number of unique peers available for downloading
 * chunks of an arbitrary resource.
 * <p>
 * This is typically returned by API endpoints that query peer availability
 * for active file requests.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class PeerCountInfo {

	/**
	 * The number of unique peers available for downloading chunks
	 * for the specified resource.
	 * <p>
	 * Returns 0 if no active request is found or no peers are available.
	 */
	public int peerCount;

	/**
	 * Detailed information about each peer.
	 * <p>
	 * May be null for backward compatibility with older API calls.
	 */
	public List<PeerInfo> peers;

	/**
	 * Default constructor for JAXB serialization.
	 */
	public PeerCountInfo() {
	}

	/**
	 * Constructs a PeerCountInfo with the specified peer count (backward compatibility).
	 *
	 * @param peerCount the number of unique peers available
	 */
	public PeerCountInfo(int peerCount) {
		this.peerCount = peerCount;
		this.peers = null;
	}

	/**
	 * Constructs a PeerCountInfo with count and detailed peer list.
	 *
	 * @param peerCount the number of unique peers available
	 * @param peers detailed information about each peer
	 */
	public PeerCountInfo(int peerCount, List<PeerInfo> peers) {
		this.peerCount = peerCount;
		this.peers = peers;
	}

}

