package org.qortal.utils;

import com.google.common.primitives.Ints;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Serialization {

	/**
	 * Convert BigDecimal, unscaled, to byte[] then prepend with zero bytes to specified length.
	 * 
	 * @param amount
	 * @param length
	 * @return byte[]
	 * @throws IOException
	 */
	public static byte[] serializeBigDecimal(BigDecimal amount, int length) throws IOException {
		// Note: we call .setScale(8) here to normalize values, especially values from API as they can have varying scale
		// (At least until the BigDecimal XmlAdapter works - see data/package-info.java)
		byte[] amountBytes = amount.setScale(8).unscaledValue().toByteArray();
		byte[] output = new byte[length];

		// To retain sign of 'amount', we might need to explicitly fill 'output' with leading 1s
		if (amount.signum() == -1)
			// Negative values: fill output with 1s
			Arrays.fill(output, (byte) 0xff);

		System.arraycopy(amountBytes, 0, output, length - amountBytes.length, amountBytes.length);

		return output;
	}

	/**
	 * Convert BigDecimal, unscaled, to byte[] then prepend with zero bytes to fixed length of 8.
	 * 
	 * @param amount
	 * @return byte[]
	 * @throws IOException
	 */
	public static byte[] serializeBigDecimal(BigDecimal amount) throws IOException {
		return serializeBigDecimal(amount, 8);
	}

	/**
	 * Write to ByteBuffer a BigDecimal, unscaled, prepended with zero bytes to specified length.
	 * 
	 * @param bytes
	 * @param amount
	 * @param length
	 * @throws IOException
	 */
	public static void serializeBigDecimal(ByteArrayOutputStream bytes, BigDecimal amount, int length) throws IOException {
		bytes.write(serializeBigDecimal(amount, length));
	}

	/**
	 * Write to ByteBuffer a BigDecimal, unscaled, prepended with zero bytes to fixed length of 8.
	 * 
	 * @param bytes
	 * @param amount
	 * @throws IOException
	 */
	public static void serializeBigDecimal(ByteArrayOutputStream bytes, BigDecimal amount) throws IOException {
		serializeBigDecimal(bytes, amount, 8);
	}

	public static BigDecimal deserializeBigDecimal(ByteBuffer byteBuffer, int length) {
		byte[] bytes = new byte[length];
		byteBuffer.get(bytes);
		return new BigDecimal(new BigInteger(bytes), 8);
	}

	public static BigDecimal deserializeBigDecimal(ByteBuffer byteBuffer) {
		return Serialization.deserializeBigDecimal(byteBuffer, 8);
	}

	public static void serializeAddress(ByteArrayOutputStream bytes, String address) throws IOException {
		bytes.write(Base58.decode(address));
	}

	public static String deserializeAddress(ByteBuffer byteBuffer) {
		byte[] bytes = new byte[Transformer.ADDRESS_LENGTH];
		byteBuffer.get(bytes);
		return Base58.encode(bytes);
	}

	public static byte[] deserializePublicKey(ByteBuffer byteBuffer) {
		byte[] bytes = new byte[Transformer.PUBLIC_KEY_LENGTH];
		byteBuffer.get(bytes);
		return bytes;
	}

	/**
	 * Original serializeSizedString() method used in various transaction types
	 * @param bytes
	 * @param string
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
	public static void serializeSizedString(ByteArrayOutputStream bytes, String string) throws UnsupportedEncodingException, IOException {
		byte[] stringBytes = string.getBytes(StandardCharsets.UTF_8);
		bytes.write(Ints.toByteArray(stringBytes.length));
		bytes.write(stringBytes);
	}

	/**
	 * Original deserializeSizedString() method used in various transaction types
	 * @param byteBuffer
	 * @param maxSize
	 * @return
	 * @throws TransformationException
	 */
	public static String deserializeSizedString(ByteBuffer byteBuffer, int maxSize) throws TransformationException {
		int size = byteBuffer.getInt();
		if (size > maxSize)
			throw new TransformationException("Serialized string too long");

		if (size > byteBuffer.remaining())
			throw new TransformationException("Byte data too short for serialized string");

		byte[] bytes = new byte[size];
		byteBuffer.get(bytes);

		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * Alternate version of serializeSizedString() added for ARBITRARY transactions.
	 * These two methods can ultimately be merged together once unit tests can
	 * confirm that they process data identically.
	 * @param bytes
	 * @param string
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
	public static void serializeSizedStringV2(ByteArrayOutputStream bytes, String string) throws UnsupportedEncodingException, IOException {
		byte[] stringBytes = null;
		int stringBytesLength = 0;

		if (string != null) {
			stringBytes = string.getBytes(StandardCharsets.UTF_8);
			stringBytesLength = stringBytes.length;
		}
		bytes.write(Ints.toByteArray(stringBytesLength));
		if (stringBytesLength > 0) {
			bytes.write(stringBytes);
		}
	}

	/**
	 * Alternate version of serializeSizedString() added for ARBITRARY transactions.
	 * The main difference is that blank strings are returned as null.
	 * These two methods can ultimately be merged together once any differences are
	 * solved, and unit tests can confirm that they process data identically.
	 * @param byteBuffer
	 * @param maxSize length in bytes for the string
	 * @return a String object from deserialization
	 * @throws TransformationException
	 */
	public static String deserializeSizedStringV2(ByteBuffer byteBuffer, int maxSize) throws TransformationException {
		int size = byteBuffer.getInt();
		if (size > maxSize)
			throw new TransformationException("Serialized string too long");

		if (size > byteBuffer.remaining())
			throw new TransformationException("Byte data too short for serialized string");

		if (size == 0)
			return null;

		byte[] bytes = new byte[size];
		byteBuffer.get(bytes);

		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * Serializes a Map<String, Object> into a binary stream.
	 * Values are converted to String for simplicity.
	 *
	 * @param bytes ByteArrayOutputStream
	 * @param map The map to serialize
	 * @throws IOException if write fails
	 *
	 * @author Ice
	 * @since v5.1.0
	 */
	public static void serializeMap(ByteArrayOutputStream bytes, Map<String, Object> map) throws IOException {
		// Write the size of the map
		DataOutputStream dos = new DataOutputStream(bytes);
		dos.writeInt(map.size());

		// Serialize each entry
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			serializeSizedString(dos, entry.getKey());

			// We need to pass the type so deserialize can properly store
			String type = entry.getValue().getClass().getSimpleName();  // e.g., "String", "Boolean", "Integer"
			serializeSizedString(dos, type);

			String valueString = (entry.getValue() != null) ? entry.getValue().toString() : "";
			serializeSizedString(dos, valueString);
		}
	}

	/**
	 * Deserializes a Map<String, String> from a binary stream where strings were written
	 * as sized UTF-8 encoded values.
	 *
	 * @param bytes ByteArrayInputStream to read from
	 * @return Deserialized map
	 * @throws IOException if read fails
	 *
	 * @author Ice
	 * @since v5.1.0
	 */
	public static Map<String, Object> deserializeMap(ByteBuffer bytes) throws IOException {
		byte[] byteArray;

		if (bytes.hasArray()) {
			// Use the backing array directly if available
			byteArray = bytes.array();
		} else {
			// Otherwise, copy remaining bytes into new array
			byteArray = new byte[bytes.remaining()];
			bytes.get(byteArray);
		}

		try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(byteArray))) {
			int mapSize = dis.readInt();
			Map<String, Object> map = new HashMap<>(mapSize);

			for (int i = 0; i < mapSize; i++) {
				String key = deserializeSizedString(dis);
				String type = deserializeSizedString(dis);
				String rawValue = deserializeSizedString(dis);
				// @ ToDo: Add additional types for deserialization as needed
				switch (type) {
					case "Boolean":
						map.put(key, (Boolean.parseBoolean(rawValue)) );
						break;
					case "Integer":
						map.put(key, Integer.parseInt(rawValue));
						break;
					default:
						map.put(key, rawValue); // fallback to String
				};
			}
			return map;
		}
	}

	/**
	 * Serializes a string to a binary output stream by first writing its length (as an {@code int}),
	 * followed by its UTF-8 encoded bytes. This ensures the string can be deserialized later with the
	 * exact content and encoding.
	 *
	 * <p>This method is commonly used in network protocols or file formats where the receiver must know
	 * the size of the string in advance to read it properly.</p>
	 *
	 * @param out the {@link DataOutput} stream to write the serialized string to
	 * @param value the {@code String} to serialize; must not be {@code null}
	 * @throws IOException if an I/O error occurs while writing to the stream
	 *
	 * @author Ice
	 * @since v5.1.0
	 */
	public static void serializeSizedString(DataOutput out, String value) throws IOException {
		byte[] utf = value.getBytes(StandardCharsets.UTF_8);
		out.writeInt(utf.length);
		out.write(utf);
	}

	/**
	 * Deserializes a UTF-8 encoded string from a binary input stream.
	 * <p>
	 * This method reads an integer indicating the number of bytes,
	 * then reads that many bytes and constructs a string from them using UTF-8 encoding.
	 * It is the inverse of {@link #serializeSizedString(DataOutput, String)}.
	 * </p>
	 *
	 * @param in the {@link DataInput} stream to read the serialized string from
	 * @return the deserialized {@code String}
	 * @throws IOException if an I/O error occurs or if the stream does not contain enough bytes
	 *
	 * @author Ice
	 * @since v5.1.0
	 */
	public static String deserializeSizedString(DataInput in) throws IOException {
		int length = in.readInt();
		byte[] utf = new byte[length];
		in.readFully(utf);
		return new String(utf, StandardCharsets.UTF_8);
	}
}
