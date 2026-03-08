package org.qortal.network.message;

import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class GetArbitraryDataFileMessage extends Message {

	private byte[] signature;
	private byte[] hash;

	public GetArbitraryDataFileMessage(byte[] signature, byte[] hash) {
		super(MessageType.GET_ARBITRARY_DATA_FILE);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(signature.length + hash.length);

		try {
			bytes.write(signature);

			bytes.write(hash);
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}
		this.signature = signature;
		this.hash = hash;
		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private GetArbitraryDataFileMessage(int id, byte[] signature, byte[] hash) {
		super(id, MessageType.GET_ARBITRARY_DATA_FILE);

		this.signature = signature;
		this.hash = hash;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public byte[] getHash() {
		return this.hash;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		bytes.get(signature);

		byte[] hash = new byte[Transformer.SHA256_LENGTH];
		bytes.get(hash);

		return new GetArbitraryDataFileMessage(id, signature, hash);
	}

}
