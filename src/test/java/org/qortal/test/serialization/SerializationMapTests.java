package org.qortal.test.serialization;

import com.google.common.primitives.Longs;
import org.junit.Test;
import org.qortal.network.Peer;
import org.qortal.network.message.HelloV2Message;
import org.qortal.network.message.Message;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link Serialization#serializeMap(ByteArrayOutputStream, Map)}
 * / {@link Serialization#deserializeMap(ByteBuffer)}, and for the
 * {@link HelloV2Message} round-trip that depends on them.
 *
 * <p>These tests need no repository, settings or network: they exercise pure
 * serialization code. They exist because {@code deserializeMap()} used to read
 * from the start of the backing array (ignoring {@code position()} and
 * {@code arrayOffset()}), so any map not at offset 0 of its buffer -- e.g. the
 * capabilities map of a {@code HelloV2Message}, which follows the timestamp,
 * version, address and peer-type fields, and arrives as a slice of a larger
 * peer read buffer -- was parsed from the wrong bytes (see Qortal/qortal#122).
 */
public class SerializationMapTests {

	private static Map<String, Object> sampleCaps() {
		Map<String, Object> caps = new HashMap<>();
		caps.put("QDN", 8080);
		caps.put("LITE", true);
		caps.put("NOTE", "hello");
		return caps;
	}

	private static byte[] serializeCaps(Map<String, Object> caps) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Serialization.serializeMap(bytes, caps);
		return bytes.toByteArray();
	}

	/** Serializes a HelloV2 payload exactly as {@link HelloV2Message}'s constructor does. */
	private static byte[] helloV2DataBytes(long timestamp, String versionString, String senderPeerAddress,
			Map<String, Object> caps, int peerType) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(Longs.toByteArray(timestamp));
		Serialization.serializeSizedString(bytes, versionString);
		Serialization.serializeSizedString(bytes, senderPeerAddress);
		bytes.write(ByteBuffer.allocate(4).putInt(peerType).array());
		if (caps != null)
			Serialization.serializeMap(bytes, caps);
		return bytes.toByteArray();
	}

	@Test
	public void testDeserializeMapRespectsPosition() throws IOException {
		byte[] mapBytes = serializeCaps(sampleCaps());

		// Junk prefix bytes, as if the map followed other fields in the same buffer
		byte[] prefixed = new byte[39 + mapBytes.length];
		for (int i = 0; i < 39; ++i)
			prefixed[i] = (byte) 0x51; // 'Q', like message-magic bytes
		System.arraycopy(mapBytes, 0, prefixed, 39, mapBytes.length);

		ByteBuffer buffer = ByteBuffer.wrap(prefixed);
		buffer.position(39);

		assertEquals(sampleCaps(), Serialization.deserializeMap(buffer));
	}

	@Test
	public void testDeserializeMapRespectsArrayOffset() throws IOException {
		byte[] mapBytes = serializeCaps(sampleCaps());

		// Mirror Peer.readChannel()/Message.fromByteBuffer(): a slice of a larger heap read buffer
		ByteBuffer readBuffer = ByteBuffer.allocate(4096);
		readBuffer.put(new byte[17]); // magic + type + id + size + checksum bytes
		readBuffer.put(mapBytes);
		readBuffer.flip();
		readBuffer.position(17);
		ByteBuffer slice = readBuffer.slice();
		slice.limit(mapBytes.length);

		assertEquals(sampleCaps(), Serialization.deserializeMap(slice));
	}

	@Test
	public void testDeserializeMapDirectBuffer() throws IOException {
		byte[] mapBytes = serializeCaps(sampleCaps());

		ByteBuffer buffer = ByteBuffer.allocateDirect(mapBytes.length);
		buffer.put(mapBytes);
		buffer.flip();

		assertEquals(sampleCaps(), Serialization.deserializeMap(buffer));
	}

	@Test
	public void testDeserializeMapEmptyMap() throws IOException {
		byte[] mapBytes = serializeCaps(new HashMap<>());

		assertEquals(new HashMap<>(), Serialization.deserializeMap(ByteBuffer.wrap(mapBytes)));
	}

	@Test(expected = IOException.class)
	public void testDeserializeMapRejectsNegativeSize() throws IOException {
		// Corrupt/truncated input must fail cleanly with IOException,
		// not with NegativeArraySizeException or an unchecked allocation attempt.
		ByteBuffer buffer = ByteBuffer.allocate(4).putInt(-1);
		buffer.flip();

		Serialization.deserializeMap(buffer);
	}

	@Test(expected = IOException.class)
	public void testDeserializeMapRejectsTruncatedData() throws IOException {
		byte[] mapBytes = serializeCaps(sampleCaps());
		byte[] truncated = new byte[mapBytes.length - 3];
		System.arraycopy(mapBytes, 0, truncated, 0, truncated.length);

		Serialization.deserializeMap(ByteBuffer.wrap(truncated));
	}

	@Test
	public void testHelloV2RoundTripWithCapabilities() throws Exception {
		Map<String, Object> caps = new HashMap<>();
		caps.put("QDN", 8080);

		byte[] dataBytes = helloV2DataBytes(1794927000000L, "6.1.9", "127.0.0.1:9084", caps, Peer.NETWORK);

		// Before the fix this threw NegativeArraySizeException: deserializeMap()
		// read the map size from the timestamp bytes at the start of the array.
		Message message = HelloV2Message.fromByteBuffer(-1, ByteBuffer.wrap(dataBytes));
		assertTrue(message instanceof HelloV2Message);

		HelloV2Message hello = (HelloV2Message) message;
		assertEquals(1794927000000L, hello.getTimestamp());
		assertEquals("6.1.9", hello.getVersionString());
		assertEquals("127.0.0.1:9084", hello.getSenderPeerAddress());
		assertEquals(8080, hello.getCapabilities().getCapability("QDN"));
	}

	@Test
	public void testHelloV2RoundTripFromSlice() throws Exception {
		Map<String, Object> caps = new HashMap<>();
		caps.put("QDN", 8080);

		byte[] dataBytes = helloV2DataBytes(1794927000000L, "6.1.9", "127.0.0.1:9084", caps, Peer.NETWORK);

		// Exact production shape: dataBytes embedded at a non-zero offset of a
		// larger heap read buffer, passed on as a slice (arrayOffset > 0).
		ByteBuffer readBuffer = ByteBuffer.allocate(4096);
		readBuffer.put(new byte[13]); // message framing bytes before the payload
		readBuffer.put(dataBytes);
		readBuffer.flip();
		readBuffer.position(13);
		ByteBuffer slice = readBuffer.slice();
		slice.limit(dataBytes.length);

		Message message = HelloV2Message.fromByteBuffer(-1, slice);
		assertTrue(message instanceof HelloV2Message);

		HelloV2Message hello = (HelloV2Message) message;
		assertEquals(8080, hello.getCapabilities().getCapability("QDN"));
		assertEquals("6.1.9", hello.getVersionString());
	}
}
