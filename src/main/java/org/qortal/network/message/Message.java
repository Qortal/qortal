package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.crypto.Crypto;
import org.qortal.network.Network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Network message for sending over network, or unpacked data received from network.
 * <p></p>
 * <p>
 * For messages received from network, subclass's {@code fromByteBuffer()} method is used
 * to construct a subclassed instance. Original bytes from network are not retained.
 * Access to deserialized data should be via subclass's getters. Ideally there should be NO setters!
 * </p>
 * <p></p>
 * <p>
 * Each subclass's <b>public</b> constructor is for building a message to send <b>only</b>.
 * The constructor will serialize into byte form but <b>not</b> store the passed args.
 * Serialized bytes are saved into superclass (Message) {@code dataBytes} and, if not empty,
 * a checksum is created and saved into {@code checksumBytes}.
 * Therefore: <i>do not use subclass's getters after using constructor!</i>
 * </p>
 * <p></p>
 * <p>
 * For subclasses where outgoing versions might be usefully cached, they can implement Clonable
 * as long if they are safe to use {@link Object#clone()}.
 * </p>
 */
public abstract class Message {
    private static final Logger LOGGER = LogManager.getLogger(Message.class);

	// MAGIC(4) + TYPE(4) + HAS-ID(1) + ID?(4) + DATA-SIZE(4) + CHECKSUM?(4) + DATA?(*)
	private static final int MAGIC_LENGTH = 4;
	private static final int TYPE_LENGTH = 4;
	private static final int HAS_ID_LENGTH = 1;
	private static final int ID_LENGTH = 4;
	private static final int DATA_SIZE_LENGTH = 4;
	private static final int CHECKSUM_LENGTH = 4;

	private static final int MAX_DATA_SIZE = 10 * 1024 * 1024; // 10MB

	protected static final byte[] EMPTY_DATA_BYTES = new byte[0];
	private static final ByteBuffer EMPTY_READ_ONLY_BYTE_BUFFER = ByteBuffer.wrap(EMPTY_DATA_BYTES).asReadOnlyBuffer();

	protected int id;
	protected final MessageType type;

	/** Serialized outgoing message data. Expected to be written to by subclass. */
	protected byte[] dataBytes;
	/** Serialized outgoing message checksum. Expected to be written to by subclass. */
	protected byte[] checksumBytes;

	/** Typically called by subclass when constructing message from received network data. */
	protected Message(int id, MessageType type) {
		this.id = id;
		this.type = type;
	}

	/** Typically called by subclass when constructing outgoing message. */
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
	 * @param readOnlyBuffer ByteBuffer containing bytes read from network
	 * @return null if no complete message can be read
	 * @throws MessageException if message could not be decoded or is invalid
	 */
	public static Message fromByteBuffer(ByteBuffer readOnlyBuffer) throws MessageException {
		try {
			// Read only enough bytes to cover Message "magic" preamble
			byte[] messageMagic = new byte[MAGIC_LENGTH];
			readOnlyBuffer.get(messageMagic);

			if (!Arrays.equals(messageMagic, Network.getInstance().getMessageMagic())) {
				LOGGER.debug("Message - received mM: {}, Network mM: {}", messageMagic, Network.getInstance().getMessageMagic());
				// Didn't receive correct Message "magic"
				throw new MessageException("Received incorrect message 'magic'");
			}

			// Find supporting object
			int typeValue = readOnlyBuffer.getInt();
			MessageType messageType = MessageType.valueOf(typeValue);
			if (messageType == null)
				messageType = MessageType.UNSUPPORTED;

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

			ByteBuffer dataSlice = EMPTY_READ_ONLY_BYTE_BUFFER;
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

	public void checkValidOutgoing() throws MessageException {
		// We expect subclass to have initialized these
		if (this.dataBytes == null)
			throw new MessageException("Missing data payload");
		if (this.dataBytes.length > 0 && this.checksumBytes == null)
			throw new MessageException("Missing data checksum");
	}

	public byte[] toBytes() throws MessageException {
		checkValidOutgoing();

		// We can calculate exact length
		int messageLength = MAGIC_LENGTH + TYPE_LENGTH + HAS_ID_LENGTH;
		messageLength += this.hasId() ? ID_LENGTH : 0;
		messageLength += DATA_SIZE_LENGTH + this.dataBytes.length > 0 ? CHECKSUM_LENGTH + this.dataBytes.length : 0;

		if (messageLength > MAX_DATA_SIZE)
			throw new MessageException(String.format("About to send message with length %d larger than allowed %d", messageLength, MAX_DATA_SIZE));

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(messageLength);

			// Magic
			bytes.write(Network.getInstance().getMessageMagic());

			bytes.write(Ints.toByteArray(this.type.value));

			if (this.hasId()) {
				bytes.write(1);

				bytes.write(Ints.toByteArray(this.id));
			} else {
				bytes.write(0);
			}

			bytes.write(Ints.toByteArray(this.dataBytes.length));

			if (this.dataBytes.length > 0) {
				bytes.write(this.checksumBytes);
				bytes.write(this.dataBytes);
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			throw new MessageException("Failed to serialize message", e);
		}
	}

	public static <M extends Message> M cloneWithNewId(M message, int newId) {
		M clone;

		try {
			clone = (M) message.clone();
		} catch (CloneNotSupportedException e) {
			throw new UnsupportedOperationException("Message sub-class not cloneable");
		}

		clone.setId(newId);
		return clone;
	}

}
