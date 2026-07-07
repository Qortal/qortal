package org.qortal.network.message;

import org.qortal.transform.Transformer;

import java.nio.ByteBuffer;

final class GroupedMessageUtils {

	private GroupedMessageUtils() {
	}

	static int readInitialGroupCount(ByteBuffer bytes, long entrySize, String errorMessage) throws MessageException {
		int count = readGroupCount(bytes, true, errorMessage);
		if (count > 0)
			validateGroupCount(count, bytes, entrySize, errorMessage);
		return count;
	}

	static int readNextGroupCount(ByteBuffer bytes, long entrySize, String errorMessage) throws MessageException {
		int count = readGroupCount(bytes, false, errorMessage);
		validateGroupCount(count, bytes, entrySize, errorMessage);
		return count;
	}

	private static void validateGroupCount(int count, ByteBuffer bytes, long entrySize, String errorMessage) throws MessageException {
		long requiredBytes = Transformer.LONG_LENGTH + ((long) count * entrySize);
		if (requiredBytes > bytes.remaining())
			throw new MessageException(errorMessage);
	}

	private static int readGroupCount(ByteBuffer bytes, boolean allowZeroCount, String errorMessage) throws MessageException {
		if (bytes.remaining() < Transformer.INT_LENGTH)
			throw new MessageException(errorMessage);

		int count = bytes.getInt();
		if (count < 0 || (!allowZeroCount && count == 0))
			throw new MessageException(errorMessage);

		if (count == 0 && bytes.hasRemaining())
			throw new MessageException(errorMessage);

		return count;
	}

}
