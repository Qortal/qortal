package org.qortal.network.message;

import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class GetArbitraryDataFilesMessage extends Message {

	private byte[] signature;
	private int numberHashes;
	private List<byte[]> hashes;

	public GetArbitraryDataFilesMessage(byte[] signature, List<byte[]> hashes) {
		super(MessageType.GET_ARBITRARY_DATA_FILES);

		int numberHashes = hashes.size();

		// Signature + (Number of Hashes Request * HashSize)
		int size = signature.length + numberHashes * Transformer.SHA256_LENGTH ;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);

		try {
			bytes.write(signature);

			for (byte[] hash : hashes) {
				// Loop and write the hashes
				bytes.write(hash);
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	public GetArbitraryDataFilesMessage(String signature, List<byte[]> hashes) {
		this(signature.getBytes(StandardCharsets.UTF_8), hashes);
	}

	private GetArbitraryDataFilesMessage(int id, byte[] signature, List<byte[]> hashes) {
		super(id, MessageType.GET_ARBITRARY_DATA_FILES);

		this.signature = signature;
		this.hashes = hashes;
		this.numberHashes = hashes.size();
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public List<byte[]> getHashes() {
		return this.hashes;
	}

	public int getHashCount() { return this.numberHashes; }

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		bytes.get(signature);

		//loop list to build a serialized list of hashes
		List<byte[]> hashes = null;
		while (bytes.hasRemaining()) {
			byte[] hash = new byte[Transformer.SHA256_LENGTH];
			bytes.get(hash);
            hashes.add(hash);
		}

        return new GetArbitraryDataFilesMessage(id, signature, hashes);
	}

}
