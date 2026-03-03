package org.qortal.network.message;

import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ChallengeMessage extends Message {

	public static final int CHALLENGE_LENGTH = 32;

	private byte[] publicKey;
	private byte[] challenge;

	public ChallengeMessage(byte[] publicKey, byte[] challenge) {
		super(MessageType.CHALLENGE);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(publicKey.length + challenge.length);
		try {
			bytes.write(publicKey);
			bytes.write(challenge);
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private ChallengeMessage(int id, byte[] publicKey, byte[] challenge) {
		super(id, MessageType.CHALLENGE);

		this.publicKey = publicKey;
		this.challenge = challenge;
	}

	public byte[] getPublicKey() {
		return this.publicKey;
	}

	public byte[] getChallenge() {
		return this.challenge;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer)  {
		byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
		byteBuffer.get(publicKey);

		byte[] challenge = new byte[CHALLENGE_LENGTH];
		byteBuffer.get(challenge);

		return new ChallengeMessage(id, publicKey, challenge);
	}

}
