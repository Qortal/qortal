package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.network.Network;
import org.qortal.repository.DataException;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class ArbitraryDataFileMessage extends Message {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileMessage.class);

	private static final int MAGIC_LENGTH = 4;
	private static final int TYPE_LENGTH = 4;
	private static final int HAS_ID_LENGTH = 1;
	private static final int ID_LENGTH = 4;
	private static final int DATA_SIZE_LENGTH = 4;
	private static final int CHECKSUM_LENGTH = 4;
	private static final int MAX_DATA_SIZE = 10 * 1024 * 1024; // 10MB

	private byte[] signature;
	private ArbitraryDataFile arbitraryDataFile;
	
	// Shallow prefetch: async disk read when message enters sendQueue
	// Reduces blocking in writeChannel() by having data ready when needed
	private volatile byte[] prefetchedData = null;
	private final AtomicBoolean prefetchInProgress = new AtomicBoolean(false);
	private volatile boolean prefetchComplete = false;

	/**
	 * Constructor for outgoing messages.
	 * Data is NOT loaded or serialized here - deferred until toBytes() is called.
	 * This prevents memory issues when queuing many large chunk messages.
	 */
	public ArbitraryDataFileMessage(byte[] signature, ArbitraryDataFile arbitraryDataFile) {
		super(MessageType.ARBITRARY_DATA_FILE);
		this.signature = signature;
		this.arbitraryDataFile = arbitraryDataFile;
		// Set empty dataBytes to pass checkValidOutgoing() - actual serialization happens in toBytes()
		this.dataBytes = EMPTY_DATA_BYTES;
		this.checksumBytes = null;
	}

	private ArbitraryDataFileMessage(int id, byte[] signature, ArbitraryDataFile arbitraryDataFile) {
		super(id, MessageType.ARBITRARY_DATA_FILE);

		this.signature = signature;
		this.arbitraryDataFile = arbitraryDataFile;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public ArbitraryDataFile getArbitraryDataFile() {
		return this.arbitraryDataFile;
	}

	/**
	 * Override checkValidOutgoing() to validate lazy-loaded message.
	 * We check for ArbitraryDataFile instead of dataBytes since serialization is deferred.
	 */
	@Override
	public void checkValidOutgoing() throws MessageException {
		if (this.arbitraryDataFile == null)
			throw new MessageException("Missing arbitrary data file");
		// dataBytes is set to EMPTY_DATA_BYTES for lazy loading, so base class check will pass
		super.checkValidOutgoing();
	}

	/**
	 * Starts async prefetch of chunk data from disk.
	 * Called when message enters sendQueue to reduce blocking in writeChannel().
	 * 
	 * @return true if prefetch was started, false if already in progress or complete
	 */
	public boolean startPrefetch() {
		// Check if already complete
		if (prefetchComplete) {
			return false; // Already prefetched
		}
		
		// Skip prefetch if fileContent is already loaded in memory (from byte array constructor)
		// This prevents double-loading: fileContent + prefetchedData = 2x memory
		// Prefetch is only needed when data needs to be loaded from disk (filePath exists, fileContent is null)
		// We can detect this by checking if getBytes() returns data immediately without disk I/O
		// If arbitraryDataFile was created from byte array, filePath is null and fileContent is set
		// In that case, getBytes() returns fileContent immediately - no need to prefetch
		if (arbitraryDataFile != null) {
			try {
				// Try to get data - if it returns immediately, it's already in memory
				// This happens when ArbitraryDataFile was created from byte array (relay cache)
				byte[] existingData = arbitraryDataFile.getBytes();
				if (existingData != null) {
					// Data is already in memory (fileContent) - skip prefetch to avoid double copy
					// Mark as complete so toBytes() knows data is ready, but don't copy to prefetchedData
					// toBytes() will use getBytes() which returns fileContent directly
					prefetchComplete = true;
					// Don't set prefetchedData - let toBytes() use fileContent directly
					return false; // No prefetch needed, data already in memory
				}
			} catch (Exception e) {
				// If getBytes() fails, fall through to normal prefetch
				LOGGER.trace("Could not check existing data for prefetch: {}", e.getMessage());
			}
		}
		
		// Try to start prefetch (atomic check-and-set)
		if (!prefetchInProgress.compareAndSet(false, true)) {
			return false; // Already in progress
		}
		
		// Start async prefetch
		CompletableFuture.supplyAsync(() -> {
			try {
				if (arbitraryDataFile != null) {
					byte[] data = arbitraryDataFile.getBytes();
					prefetchedData = data;
					prefetchComplete = true;
					return true;
				}
			} catch (Exception e) {
				LOGGER.warn("Prefetch failed for message {}: {}", getId(), e.getMessage());
			} finally {
				prefetchInProgress.set(false);
			}
			return false;
		});
		return true;
	}
	
	/**
	 * Returns true if prefetch is complete and data is ready.
	 */
	public boolean isPrefetchReady() {
		return prefetchComplete && prefetchedData != null;
	}

	/**
	 * Override toBytes() to serialize on-demand, loading data only when actually sending.
	 * Uses prefetched data if available to avoid blocking disk I/O.
	 * This prevents memory issues when queuing many large chunk messages.
	 */
	@Override
	public byte[] toBytes() throws MessageException {
		if (this.arbitraryDataFile == null) {
			throw new MessageException("Missing arbitrary data file");
		}

		// Use prefetched data if available, otherwise load synchronously
		byte[] data;
		if (prefetchComplete && prefetchedData != null) {
			// Prefetch succeeded - use prefetched data
			data = prefetchedData;
			// Clear prefetched data immediately after use to free memory
			// The data is now in the serialized message, so we don't need the prefetched copy
			prefetchedData = null;
		} else {
			// Prefetch not ready or failed - fall back to blocking read
			// This is the original behavior, so no performance regression
			data = arbitraryDataFile.getBytes();
		}
		
		// Clear fileContent after loading - we no longer need it in memory
		// The serialized message will be sent, and data can be reloaded from disk if needed
		arbitraryDataFile.clearFileContent();

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
			ArbitraryDataFile arbitraryDataFile = new ArbitraryDataFile(data, signature, false);
			return new ArbitraryDataFileMessage(id, signature, arbitraryDataFile);
		} catch (DataException e) {
			LOGGER.info("Unable to process received file: {}", e.getMessage());
			throw new MessageException("Unable to process received file: " + e.getMessage(), e);
		}
	}

}
