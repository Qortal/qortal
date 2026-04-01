package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.PeerData;
import org.qortal.network.NetworkData;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ArbitraryDataFileListMessage extends Message {

	private byte[] signature;
	private List<byte[]> hashes;
	private Long requestTime;
	private Integer requestHops;
	private String peerAddress;
	private String nodeId;  // used for the cache map, IMPORTANT for multiple nodes behind a single IP
	private Boolean isRelayPossible;
    private Boolean isDirectConnectable;

    private static final int NO_RELAY = 0;
    private static final int RELAY_ONLY = 1;
    private static final int DC_ONLY = 7;
    private static final int DC_AND_RELAY = 8;

//    /** Legacy version v3.2 **/
//    public ArbitraryDataFileListMessage(byte[] signature, List<byte[]> hashes) {
//        this(signature, hashes, null, null, null, null);
//    }

    /** Pre v6.0.0 version **/
    public ArbitraryDataFileListMessage(byte[] signature, List<byte[]> hashes, Long requestTime,
                                        Integer requestHops, String peerAddress, String nodeId, Boolean isRelayPossible) {
        this(signature, hashes,  requestTime, requestHops,  peerAddress, nodeId, isRelayPossible, false);
    }

    // Add in Split Network to denote ability to accept direct connect
	public ArbitraryDataFileListMessage(byte[] signature, List<byte[]> hashes, Long requestTime,
										Integer requestHops, String peerAddress, String nodeId, Boolean isRelayPossible, Boolean isDirectConnectable) {
		super(MessageType.ARBITRARY_DATA_FILE_LIST);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(signature);

			bytes.write(Ints.toByteArray(hashes.size()));

			for (byte[] hash : hashes) {
				bytes.write(hash);
			}

            bytes.write(Longs.toByteArray(requestTime));

            bytes.write(Ints.toByteArray(requestHops));

            Serialization.serializeSizedStringV2(bytes, peerAddress);
			Serialization.serializeSizedStringV2(bytes, nodeId);
            /* Integer mapping for possible values in the connectivity field
             * 0 = no relay is available
             * 1 = relay is available
             * 7 = ip direct connection is available only
             * 8 = ip direct connection is available and relay
             */
            int txValue = (Boolean.TRUE.equals(isRelayPossible) ? RELAY_ONLY : NO_RELAY);
            if(isDirectConnectable) txValue = txValue + DC_ONLY;
            bytes.write(Ints.toByteArray(txValue));

		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private ArbitraryDataFileListMessage(int id, byte[] signature, List<byte[]> hashes, Long requestTime,
										Integer requestHops, String peerAddress, String nodeId, boolean isRelayPossible, boolean isDirectConnectable) {
		super(id, MessageType.ARBITRARY_DATA_FILE_LIST);

		this.signature = signature;
		this.hashes = hashes;
		this.requestTime = requestTime;
		this.requestHops = requestHops;
		this.peerAddress = peerAddress;
		this.nodeId = nodeId;
		this.isRelayPossible = isRelayPossible;
        this.isDirectConnectable = isDirectConnectable;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public List<byte[]> getHashes() {
		return this.hashes;
	}

	public Long getRequestTime() {
		return this.requestTime;
	}

	public Integer getRequestHops() {
		return this.requestHops;
	}

	public String getPeerAddress() {
		return this.peerAddress;
	}

	public String getNodeId() {
		return this.nodeId;
	}

	public Boolean isRelayPossible() {
		return this.isRelayPossible;
	}

    public Boolean isDirectConnectable() {
        return this.isDirectConnectable;
    }

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		bytes.get(signature);

		int count = bytes.getInt();

		List<byte[]> hashes = new ArrayList<>();
		for (int i = 0; i < count; ++i) {
			byte[] hash = new byte[Transformer.SHA256_LENGTH];
			bytes.get(hash);
			hashes.add(hash);
		}

		Long requestTime = null;
		Integer requestHops = null;
		String peerAddress = null;
		String nodeId = null;
		boolean isRelayPossible = true; // Legacy versions only send this message when relaying is possible
        boolean isDirectConnectable = false;
		// The remaining fields are optional
		if (bytes.hasRemaining()) {
			try {
				requestTime = bytes.getLong();

				requestHops = bytes.getInt();

				peerAddress = Serialization.deserializeSizedStringV2(bytes, PeerData.MAX_PEER_ADDRESS_SIZE);
				nodeId = Serialization.deserializeSizedStringV2(bytes, NetworkData.MAX_NODEID_SIZE);
                int connData = bytes.getInt();
				isRelayPossible = (connData == RELAY_ONLY || connData == DC_AND_RELAY);
                isDirectConnectable = (connData == DC_AND_RELAY || connData == DC_ONLY) ;

			} catch (TransformationException e) {
				throw new MessageException(e.getMessage(), e);
			}
		}

		return new ArbitraryDataFileListMessage(id, signature, hashes, requestTime, requestHops, peerAddress, nodeId, isRelayPossible, isDirectConnectable);
	}

}
