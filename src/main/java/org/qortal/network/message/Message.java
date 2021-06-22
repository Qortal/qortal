package org.qortal.network.message;

import org.qortal.crypto.Crypto;
import org.qortal.network.Network;
import org.qortal.transform.TransformationException;

import com.google.common.primitives.Ints;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public abstract class Message {

	// MAGIC(4) + TYPE(4) + HAS-ID(1) + ID?(4) + DATA-SIZE(4) + CHECKSUM?(4) + DATA?(*)
	private static final int MAGIC_LENGTH = 4;
	private static final int CHECKSUM_LENGTH = 4;

	private static final int MAX_DATA_SIZE = 1024 * 1024; // 1MB

	private int id;
	private MessageType type;

	protected Message(int id, MessageType type) {
		this.id = id;
		this.type = type;
	}

	protected Message(MessageType type) {
		this(-1, type);
	}

	public boolean hasId() {
		return this.id != -1;
	}

	public int getId() {
		return this.id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public MessageType getType() {
		return this.type;
	}

	/**
	 * Attempt to read a message from byte buffer.
	 *
	 * @param readOnlyBuffer
	 * @return null if no complete message can be read
	 * @throws MessageException
	 */
	public static Message fromByteBuffer(ByteBuffer readOnlyBuffer) throws MessageException {
		try {
			// Read only enough bytes to cover Message "magic" preamble
			byte[] messageMagic = new byte[MAGIC_LENGTH];
			readOnlyBuffer.get(messageMagic);

			if (!Arrays.equals(messageMagic, Network.getInstance().getMessageMagic()))
				// Didn't receive correct Message "magic"
				throw new MessageException("Received incorrect message 'magic'");

			// Find supporting object
			int typeValue = readOnlyBuffer.getInt();
			MessageType messageType = MessageType.valueOf(typeValue);
			if (messageType == null)
				// Unrecognised message type
				throw new MessageException(String.format("Received unknown message type [%d]", typeValue));

			// Optional message ID
			byte hasId = readOnlyBuffer.get();
			int id = -1;
			if (hasId != 0) {
				id = readOnlyBuffer.getInt();

				if (id <= 0)
					// Invalid ID
					throw new MessageException("Invalid negative ID");
			}

			int dataSize = readOnlyBuffer.getInt();

			if (dataSize > MAX_DATA_SIZE)
				// Too large
				throw new MessageException(String.format("Declared data length %d larger than max allowed %d", dataSize, MAX_DATA_SIZE));

			// Don't have all the data yet?
			if (dataSize > 0 && dataSize + CHECKSUM_LENGTH > readOnlyBuffer.remaining())
				return null;

			ByteBuffer dataSlice = null;
			if (dataSize > 0) {
				byte[] expectedChecksum = new byte[CHECKSUM_LENGTH];
				readOnlyBuffer.get(expectedChecksum);

				// Slice data in readBuffer so we can pass to Message subclass
				dataSlice = readOnlyBuffer.slice();
				dataSlice.limit(dataSize);

				// Test checksum
				byte[] actualChecksum = generateChecksum(dataSlice);
				if (!Arrays.equals(expectedChecksum, actualChecksum))
					throw new MessageException("Message checksum incorrect");

				// Reset position after being consumed by generateChecksum
				dataSlice.position(0);
				// Update position in readOnlyBuffer
				readOnlyBuffer.position(readOnlyBuffer.position() + dataSize);
			}

			return messageType.fromByteBuffer(id, dataSlice);
		} catch (BufferUnderflowException e) {
			// Not enough bytes to fully decode message...
			return null;
		}
	}

	protected static byte[] generateChecksum(byte[] data) {
		return Arrays.copyOfRange(Crypto.digest(data), 0, CHECKSUM_LENGTH);
	}

	protected static byte[] generateChecksum(ByteBuffer dataBuffer) {
		return Arrays.copyOfRange(Crypto.digest(dataBuffer), 0, CHECKSUM_LENGTH);
	}

	public byte[] toBytes() throws MessageException {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);

			// Magic
			bytes.write(Network.getInstance().getMessageMagic());

			bytes.write(Ints.toByteArray(this.type.value));

			if (this.hasId()) {
				bytes.write(1);

				bytes.write(Ints.toByteArray(this.id));
			} else {
				bytes.write(0);
			}

			byte[] data = this.toData();
			if (data == null)
				throw new MessageException("Missing data payload");

			bytes.write(Ints.toByteArray(data.length));

			if (data.length > 0) {
				bytes.write(generateChecksum(data));
				bytes.write(data);
			}

			if (bytes.size() > MAX_DATA_SIZE)
				throw new MessageException(String.format("About to send message with length %d larger than allowed %d", bytes.size(), MAX_DATA_SIZE));

			return bytes.toByteArray();
		} catch (IOException | TransformationException e) {
			throw new MessageException("Failed to serialize message", e);
		}
	}

	protected abstract byte[] toData() throws IOException, TransformationException;

}
