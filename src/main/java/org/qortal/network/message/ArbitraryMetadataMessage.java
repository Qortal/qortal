package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.network.Network;
import org.qortal.repository.DataException;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class ArbitraryMetadataMessage extends Message {

	private static final int MAGIC_LENGTH = 4;
	private static final int TYPE_LENGTH = 4;
	private static final int HAS_ID_LENGTH = 1;
	private static final int ID_LENGTH = 4;
	private static final int DATA_SIZE_LENGTH = 4;
	private static final int CHECKSUM_LENGTH = 4;
	private static final int MAX_DATA_SIZE = 10 * 1024 * 1024; // 10MB

	private byte[] signature;
	private ArbitraryDataFile arbitraryMetadataFile;

	/**
	 * Constructor for outgoing messages.
	 * Data is NOT loaded or serialized here - deferred until toBytes() is called.
	 * This prevents memory issues when queuing many large chunk messages.
	 */
	public ArbitraryMetadataMessage(byte[] signature, ArbitraryDataFile arbitraryMetadataFile) {
		super(MessageType.ARBITRARY_METADATA);
		this.signature = signature;
		this.arbitraryMetadataFile = arbitraryMetadataFile;
		// Set empty dataBytes to pass checkValidOutgoing() - actual serialization happens in toBytes()
		this.dataBytes = EMPTY_DATA_BYTES;
		this.checksumBytes = null;
	}

	private ArbitraryMetadataMessage(int id, byte[] signature, ArbitraryDataFile arbitraryMetadataFile) {
		super(id, MessageType.ARBITRARY_METADATA);

		this.signature = signature;
		this.arbitraryMetadataFile = arbitraryMetadataFile;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public ArbitraryDataFile getArbitraryMetadataFile() {
		return this.arbitraryMetadataFile;
	}

	/**
	 * Override checkValidOutgoing() to validate lazy-loaded message.
	 * We check for ArbitraryDataFile instead of dataBytes since serialization is deferred.
	 */
	@Override
	public void checkValidOutgoing() throws MessageException {
		if (this.arbitraryMetadataFile == null)
			throw new MessageException("Missing arbitrary metadata file");
		// dataBytes is set to EMPTY_DATA_BYTES for lazy loading, so base class check will pass
		super.checkValidOutgoing();
	}

	/**
	 * Override toBytes() to serialize on-demand, loading data only when actually sending.
	 * This prevents memory issues when queuing many large chunk messages.
	 */
	@Override
	public byte[] toBytes() throws MessageException {
		if (this.arbitraryMetadataFile == null) {
			throw new MessageException("Missing arbitrary metadata file");
		}

		// Load data only when actually sending (lazy loading)
		byte[] data = arbitraryMetadataFile.getBytes();
		
		// Clear fileContent after loading - we no longer need it in memory
		// The serialized message will be sent, and data can be reloaded from disk if needed
		arbitraryMetadataFile.clearFileContent();

		// Serialize data payload
		ByteArrayOutputStream dataBytesStream = new ByteArrayOutputStream();
		try {
			dataBytesStream.write(signature);
			dataBytesStream.write(Ints.toByteArray(data.length));
			dataBytesStream.write(data);
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		byte[] dataBytes = dataBytesStream.toByteArray();
		byte[] checksumBytes = Message.generateChecksum(dataBytes);

		// Calculate full message length
		int messageLength = MAGIC_LENGTH + TYPE_LENGTH + HAS_ID_LENGTH;
		messageLength += this.hasId() ? ID_LENGTH : 0;
		messageLength += DATA_SIZE_LENGTH + (dataBytes.length > 0 ? CHECKSUM_LENGTH + dataBytes.length : 0);

		if (messageLength > MAX_DATA_SIZE)
			throw new MessageException(String.format("About to send message with length %d larger than allowed %d", messageLength, MAX_DATA_SIZE));

		// Serialize full message
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(messageLength);
		try {
			// Magic
			bytes.write(Network.getInstance().getMessageMagic());

			bytes.write(Ints.toByteArray(this.type.value));

			if (this.hasId()) {
				bytes.write(1);
				bytes.write(Ints.toByteArray(this.id));
			} else {
				bytes.write(0);
			}

			bytes.write(Ints.toByteArray(dataBytes.length));

			if (dataBytes.length > 0) {
				bytes.write(checksumBytes);
				bytes.write(dataBytes);
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			throw new MessageException("Failed to serialize message", e);
		}
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		int dataLength = byteBuffer.getInt();

		if (byteBuffer.remaining() < dataLength)
			throw new BufferUnderflowException();

		byte[] data = new byte[dataLength];
		byteBuffer.get(data);

		try {
			ArbitraryDataFile arbitraryMetadataFile = new ArbitraryDataFile(data, signature, false);
			return new ArbitraryMetadataMessage(id, signature, arbitraryMetadataFile);
		} catch (DataException e) {
			throw new MessageException("Unable to process arbitrary metadata message: " + e.getMessage(), e);
		}
	}

}
