package org.qortal.network.message;

import com.google.common.primitives.Longs;
import org.qortal.network.Peer;
import org.qortal.network.helper.PeerCapabilities;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class HelloV2Message extends Message {

	private long timestamp;
	private String versionString;
	private String senderPeerAddress;
	private PeerCapabilities capabilities = new PeerCapabilities();
	private int peerType = Peer.NETWORK;

	public HelloV2Message(long timestamp, String versionString, String senderPeerAddress, Map<String, Object> caps, int peerType) {
		super(MessageType.HELLO_V2);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(Longs.toByteArray(timestamp));
			Serialization.serializeSizedString(bytes, versionString);
			Serialization.serializeSizedString(bytes, senderPeerAddress);
			bytes.write(ByteBuffer.allocate(4).putInt(peerType).array());

			if (caps != null) {
				Serialization.serializeMap(bytes, caps);
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}
		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);

		this.timestamp = timestamp;
		this.versionString = versionString;
		this.senderPeerAddress = senderPeerAddress;
		this.capabilities = new PeerCapabilities(caps != null ? caps : new HashMap<>());
		this.peerType = peerType;
	}

	private HelloV2Message(int id, long timestamp, String versionString, String senderPeerAddress, PeerCapabilities caps, int peerType) {
		super(id, MessageType.HELLO_V2);

		this.timestamp = timestamp;
		this.versionString = versionString;
		this.senderPeerAddress = senderPeerAddress;
		this.capabilities = (caps != null ? caps : new PeerCapabilities(new HashMap<>()));
		this.peerType = peerType;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public String getVersionString() {
		return this.versionString;
	}

	public String getSenderPeerAddress() {
		return this.senderPeerAddress;
	}

	public PeerCapabilities getCapabilities() {
		return this.capabilities;
	}

	public int getPeerType() {
		return this.peerType;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		long timestamp = byteBuffer.getLong();

		String versionString;
		String senderPeerAddress = null;
		int peerType = Peer.NETWORK;
		Map<String, Object> capabilities = new HashMap<>();
		try {
			versionString = Serialization.deserializeSizedString(byteBuffer, 255);

			if (byteBuffer.hasRemaining()) {
				senderPeerAddress = Serialization.deserializeSizedString(byteBuffer, 255);
			}

			if (byteBuffer.hasRemaining()) {
				peerType = byteBuffer.getInt();
			}

			if (byteBuffer.hasRemaining()) {
				capabilities = Serialization.deserializeMap(byteBuffer);
			}
		} catch (TransformationException e) {
			throw new MessageException(e.getMessage(), e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return new HelloV2Message(id, timestamp, versionString, senderPeerAddress, new PeerCapabilities(capabilities), peerType);
	}
}
