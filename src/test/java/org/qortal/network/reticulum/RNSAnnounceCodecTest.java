package org.qortal.network.reticulum;

import org.junit.jupiter.api.Test;
import org.qortal.network.reticulum.RNSAnnounceCodec.AnnounceInfo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests for the announce appData codec.
 * <p>
 * These cover the QAN1 container and the legacy QGW1 fallback that peers on older releases still
 * send, so the compatibility path is pinned: a decode regression here silently stops gateway
 * discovery from older peers, which is invisible until the mesh stops forming.
 */
class RNSAnnounceCodecTest {

    private static final byte[] QAN1 = { 'Q', 'A', 'N', '1' };
    private static final byte[] QGW1 = { 'Q', 'G', 'W', '1' };

    @Test
    void roundTripVersionOnly() {
        AnnounceInfo info = RNSAnnounceCodec.decode(
                RNSAnnounceCodec.encode("6.1.9-71cfe5b", null, 4242));

        assertEquals("6.1.9-71cfe5b", info.getVersion());
        assertNull(info.getGatewayHost());
        assertEquals(0, info.getGatewayPort());
        assertFalse(info.hasGateway());
    }

    @Test
    void roundTripVersionAndGateway() {
        AnnounceInfo info = RNSAnnounceCodec.decode(
                RNSAnnounceCodec.encode("6.1.9", "node.example.com", 4242));

        assertEquals("6.1.9", info.getVersion());
        assertEquals("node.example.com", info.getGatewayHost());
        assertEquals(4242, info.getGatewayPort());
        assertTrue(info.hasGateway());
    }

    @Test
    void encodedPayloadStartsWithMagicAndVersionRecord() {
        byte[] appData = RNSAnnounceCodec.encode("6.1.9", null, 0);

        assertArrayEquals(QAN1, Arrays.copyOf(appData, 4));
        assertEquals(0x01, appData[4]);           // VERSION type
        assertEquals("6.1.9".length(), appData[5]); // value length
    }

    @Test
    void portIsBigEndian() {
        byte[] appData = RNSAnnounceCodec.encode("1.0.0", "h.example.com", 0xBEEF);
        // last two bytes of the payload are the port
        int hi = appData[appData.length - 2] & 0xFF;
        int lo = appData[appData.length - 1] & 0xFF;

        assertEquals(0xBE, hi);
        assertEquals(0xEF, lo);
        assertEquals(0xBEEF, RNSAnnounceCodec.decode(appData).getGatewayPort());
    }

    @Test
    void legacyQgw1PayloadStillDecodes() {
        byte[] host = "legacy.example.com".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(QGW1.length + 1 + host.length + 2);
        buf.put(QGW1);
        buf.put((byte) host.length);
        buf.put(host);
        buf.putShort((short) 4242);

        AnnounceInfo info = RNSAnnounceCodec.decode(buf.array());

        assertNull(info.getVersion(), "legacy payload carries no version");
        assertEquals("legacy.example.com", info.getGatewayHost());
        assertEquals(4242, info.getGatewayPort());
    }

    @Test
    void truncatedRecordStopsCleanly() {
        byte[] full = RNSAnnounceCodec.encode("6.1.9", "node.example.com", 4242);
        // Cut into the middle of the gateway record; the version record is complete.
        byte[] cut = Arrays.copyOf(full, full.length - 5);

        AnnounceInfo info = RNSAnnounceCodec.decode(cut);

        assertEquals("6.1.9", info.getVersion());
        assertFalse(info.hasGateway());
    }

    @Test
    void truncatedInsideVersionRecordYieldsNothing() {
        byte[] full = RNSAnnounceCodec.encode("6.1.9", null, 0);
        byte[] cut = Arrays.copyOf(full, full.length - 2);

        AnnounceInfo info = RNSAnnounceCodec.decode(cut);

        assertNull(info.getVersion());
        assertFalse(info.hasGateway());
    }

    @Test
    void unknownRecordTypeIsSkipped() {
        // QAN1 + unknown TLV (type 0x7F, 3 bytes) + version record
        byte[] version = "6.1.9".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + 3 + 2 + version.length);
        buf.put(QAN1);
        buf.put((byte) 0x7F).put((byte) 3).put(new byte[] { 1, 2, 3 });
        buf.put((byte) 0x01).put((byte) version.length).put(version);

        AnnounceInfo info = RNSAnnounceCodec.decode(buf.array());

        assertEquals("6.1.9", info.getVersion(), "unknown type must be skipped, not abort parsing");
    }

    @Test
    void oversizedHostOmitsGatewayRecord() {
        // 253 bytes: 1 (hostLen) + 253 + 2 (port) = 256, one over what a TLV length can express
        String host = "a".repeat(253);

        AnnounceInfo info = RNSAnnounceCodec.decode(RNSAnnounceCodec.encode("6.1.9", host, 4242));

        assertEquals("6.1.9", info.getVersion(), "version must survive an unusable gateway host");
        assertFalse(info.hasGateway());
    }

    @Test
    void maximumHostLengthStillEncodes() {
        String host = "a".repeat(252);

        AnnounceInfo info = RNSAnnounceCodec.decode(RNSAnnounceCodec.encode("6.1.9", host, 4242));

        assertEquals(host, info.getGatewayHost());
        assertEquals(4242, info.getGatewayPort());
    }

    @Test
    void outOfRangePortOmitsGatewayRecord() {
        assertFalse(RNSAnnounceCodec.decode(
                RNSAnnounceCodec.encode("6.1.9", "node.example.com", 0)).hasGateway());
        assertFalse(RNSAnnounceCodec.decode(
                RNSAnnounceCodec.encode("6.1.9", "node.example.com", 0x10000)).hasGateway());
        assertFalse(RNSAnnounceCodec.decode(
                RNSAnnounceCodec.encode("6.1.9", "node.example.com", -1)).hasGateway());
    }

    @Test
    void longVersionIsTruncatedNotRejected() {
        String version = "9".repeat(300);

        AnnounceInfo info = RNSAnnounceCodec.decode(RNSAnnounceCodec.encode(version, null, 0));

        assertEquals(255, info.getVersion().length());
    }

    @Test
    void nullEmptyAndForeignPayloadsDecodeToNothing() {
        for (byte[] appData : new byte[][] {
                null,
                new byte[0],
                new byte[] { 'Q' },
                "not ours at all".getBytes(StandardCharsets.UTF_8) }) {
            AnnounceInfo info = RNSAnnounceCodec.decode(appData);
            assertNull(info.getVersion());
            assertFalse(info.hasGateway());
        }
    }

    @Test
    void parseVersionToLongAcceptsBothPrefixForms() {
        long bare = RNSAnnounceCodec.parseVersionToLong("6.1.9");
        long prefixed = RNSAnnounceCodec.parseVersionToLong("qortal-6.1.9");
        long withHash = RNSAnnounceCodec.parseVersionToLong("6.1.9-71cfe5b");

        assertEquals(bare, prefixed);
        assertEquals(bare, withHash);
        assertEquals((6L << 32) | (1L << 16) | 9L, bare);
    }

    @Test
    void parseVersionToLongOrdersVersions() {
        assertTrue(RNSAnnounceCodec.parseVersionToLong("6.1.10")
                > RNSAnnounceCodec.parseVersionToLong("6.1.9"));
        assertTrue(RNSAnnounceCodec.parseVersionToLong("6.2.0")
                > RNSAnnounceCodec.parseVersionToLong("6.1.99"));
    }

    @Test
    void parseVersionToLongRejectsUnparseable() {
        assertEquals(0L, RNSAnnounceCodec.parseVersionToLong(null));
        assertEquals(0L, RNSAnnounceCodec.parseVersionToLong(""));
        assertEquals(0L, RNSAnnounceCodec.parseVersionToLong("garbage"));
        assertEquals(0L, RNSAnnounceCodec.parseVersionToLong("6.1"));
        // component above Short.MAX_VALUE would collide with a neighbouring field once packed
        assertEquals(0L, RNSAnnounceCodec.parseVersionToLong("40000.0.0"));
    }

    @Test
    void isUsableAdvertiseHostRejectsLocalAndUnqualifiedHosts() {
        assertFalse(RNSAnnounceCodec.isUsableAdvertiseHost(null));
        assertFalse(RNSAnnounceCodec.isUsableAdvertiseHost(""));
        assertFalse(RNSAnnounceCodec.isUsableAdvertiseHost("   "));
        assertFalse(RNSAnnounceCodec.isUsableAdvertiseHost("localhost"));
        assertFalse(RNSAnnounceCodec.isUsableAdvertiseHost("LocalHost"));
        assertFalse(RNSAnnounceCodec.isUsableAdvertiseHost("127.0.0.1"));
        assertFalse(RNSAnnounceCodec.isUsableAdvertiseHost("::1"));
        assertFalse(RNSAnnounceCodec.isUsableAdvertiseHost("dev-vm-2-desktop"));
    }

    @Test
    void isUsableAdvertiseHostAcceptsFqdnsAndLiterals() {
        assertTrue(RNSAnnounceCodec.isUsableAdvertiseHost("node.example.com"));
        assertTrue(RNSAnnounceCodec.isUsableAdvertiseHost(" node.example.com "));
        assertTrue(RNSAnnounceCodec.isUsableAdvertiseHost("192.0.2.10"));
        assertTrue(RNSAnnounceCodec.isUsableAdvertiseHost("2001:db8::1"));
    }
}
