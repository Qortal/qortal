package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.network.PeerAddress;
import org.qortal.network.PeerAddressFactory;
import org.qortal.settings.Settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// NOTE: this message supports hostnames, literal IP addresses (IPv4 and IPv6) with port numbers
public class PeersV2Message extends Message {

	private List<PeerAddress> peerAddresses;

	public PeersV2Message(List<PeerAddress> peerAddresses) {
		super(MessageType.PEERS_V2);

		List<byte[]> addresses = new ArrayList<>();

		// First entry represents sending node but contains only port number with empty address.
		addresses.add(("0.0.0.0:" + Settings.getInstance().getListenPort()).getBytes(StandardCharsets.UTF_8));

		for (PeerAddress peerAddress : peerAddresses)
			addresses.add(peerAddress.toString().getBytes(StandardCharsets.UTF_8));

		// We can't send addresses that are longer than 255 bytes as length itself is encoded in one byte.
		addresses.removeIf(addressString -> addressString.length > 255);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			// Number of entries
			bytes.write(Ints.toByteArray(addresses.size()));

			for (byte[] address : addresses) {
				bytes.write(address.length);
				bytes.write(address);
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private PeersV2Message(int id, List<PeerAddress> peerAddresses) {
		super(id, MessageType.PEERS_V2);

		this.peerAddresses = peerAddresses;
	}

	public List<PeerAddress> getPeerAddresses() {
		return this.peerAddresses;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		// Read entry count
		int count = byteBuffer.getInt();

		List<PeerAddress> peerAddresses = new ArrayList<>();

		for (int i = 0; i < count; ++i) {
			byte addressSize = byteBuffer.get();

			byte[] addressBytes = new byte[addressSize & 0xff];
			byteBuffer.get(addressBytes);
			String addressString = new String(addressBytes, StandardCharsets.UTF_8);

			try {
				//PeerAddress peerAddress = PeerAddress.fromString(addressString);
                PeerAddress peerAddress = (PeerAddress) PeerAddressFactory.create("uri", addressString);
				peerAddresses.add(peerAddress);
			} catch (IllegalArgumentException e) {
				throw new MessageException("Invalid peer address in received PEERS_V2 message");
			} catch (ReflectiveOperationException e) {
                throw new MessageException("Failed to create peer address from received PEERS_V2 message");
            }

        }

		return new PeersV2Message(id, peerAddresses);
	}

}
