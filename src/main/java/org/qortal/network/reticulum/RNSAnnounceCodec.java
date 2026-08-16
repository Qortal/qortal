package org.qortal.network.reticulum;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;

import org.qortal.controller.Controller;
import org.qortal.network.Peer;

/**
 * Codec for the appData payload carried by Reticulum announces.
 * <p>
 * <b>QAN1</b> is a self-describing TLV container attached to every announce. It always carries the
 * node's version and optionally a gateway record; future capability records simply add new type
 * bytes, and decoders skip types they don't know. Decoding falls back to the legacy <b>QGW1</b>
 * gateway-only payload so announces from older peers still yield gateway info.
 *
 * <pre>
 * QAN1 container            QGW1 (legacy, decode only)
 *   off bytes meaning         off bytes meaning
 *    0   4    magic "QAN1"     0   4    magic "QGW1"
 *    4   1    type              4   1    host length n (1..255)
 *    5   1    length L          5   n    host UTF-8
 *    6   L    value           5+n   2    port (big-endian u16)
 *   ... repeated ...
 *
 * VERSION record (type 0x01): value = UTF-8 version string, no "qortal-" prefix
 * GATEWAY record (type 0x02): value = [hostLen:1][host][port:2] — the QGW1 body minus its magic,
 *                             which is what keeps the legacy decode path byte-compatible
 * </pre>
 *
 * Pure functions with no Reticulum, Settings or peer-state dependency, so the wire format can be
 * exercised directly in tests. Callers decide <i>whether</i> to advertise a gateway; this class
 * only decides how it is encoded.
 */
public final class RNSAnnounceCodec {

    static final byte[] QAN_MAGIC = { 'Q', 'A', 'N', '1' };
    static final byte TLV_VERSION = 0x01;
    static final byte TLV_GATEWAY = 0x02;

    static final byte[] QGW_MAGIC = { 'Q', 'G', 'W', '1' };
    private static final int QGW_MIN_LEN = QGW_MAGIC.length + 1 /*hostLen*/ + 2 /*port*/;

    /** A TLV length is one byte, so a gateway value ([hostLen][host][port:2]) caps the host here. */
    private static final int MAX_GATEWAY_HOST_BYTES = 252;
    private static final int MAX_TLV_VALUE_BYTES = 255;

    private RNSAnnounceCodec() {
    }

    /**
     * Build the appData for an outbound announce. The version record is always present; the gateway
     * record is added only when {@code gatewayHost} is usable and {@code gatewayPort} is in range.
     * Never returns null — the version alone is worth sending.
     *
     * @param version      node version without the "qortal-" prefix, e.g. "6.1.9-71cfe5b"
     * @param gatewayHost  host to advertise, or null when this node advertises no gateway
     * @param gatewayPort  port to advertise; ignored when gatewayHost is null
     */
    public static byte[] encode(String version, String gatewayHost, int gatewayPort) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(QAN_MAGIC, 0, QAN_MAGIC.length);

        byte[] ver = (version == null ? "" : version).getBytes(StandardCharsets.UTF_8);
        if (ver.length > MAX_TLV_VALUE_BYTES) ver = Arrays.copyOf(ver, MAX_TLV_VALUE_BYTES);
        out.write(TLV_VERSION);
        out.write(ver.length);
        out.write(ver, 0, ver.length);

        byte[] gw = encodeGatewayValue(gatewayHost, gatewayPort);
        if (gw != null) {
            out.write(TLV_GATEWAY);
            out.write(gw.length);
            out.write(gw, 0, gw.length);
        }
        return out.toByteArray();
    }

    /**
     * The gateway record's VALUE bytes: {@code [hostLen:1][host][port:2]}, or null when the host is
     * absent/unusable or either length/port is out of range.
     */
    static byte[] encodeGatewayValue(String host, int port) {
        if (host == null || host.isEmpty()) return null;
        if (port < 1 || port > 0xFFFF) return null;

        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length < 1 || hostBytes.length > MAX_GATEWAY_HOST_BYTES) return null;

        ByteBuffer buf = ByteBuffer.allocate(1 + hostBytes.length + 2);
        buf.put((byte) hostBytes.length);
        buf.put(hostBytes);
        buf.putShort((short) port);
        return buf.array();
    }

    /**
     * Decode announce appData. Never returns null and never throws: malformed, truncated or absent
     * payloads simply yield an {@link AnnounceInfo} with fewer fields set. Parsing stops at the
     * first truncated record and skips record types it doesn't recognise. When the QAN1 magic is
     * absent the payload is retried as legacy QGW1 (gateway only, version stays null).
     */
    public static AnnounceInfo decode(byte[] appData) {
        if (!hasMagic(appData, QAN_MAGIC)) {
            return decodeLegacyGateway(appData);
        }

        String version = null;
        String gwHost = null;
        int gwPort = 0;

        int p = QAN_MAGIC.length;
        while (p + 2 <= appData.length) {
            int type = appData[p] & 0xFF;
            int len = appData[p + 1] & 0xFF;
            int vStart = p + 2;
            if (vStart + len > appData.length) break; // truncated record
            if (type == TLV_VERSION) {
                version = new String(appData, vStart, len, StandardCharsets.UTF_8);
            } else if (type == TLV_GATEWAY && len >= 3) {
                int hostLen = appData[vStart] & 0xFF;
                if (1 + hostLen + 2 <= len) {
                    gwHost = new String(appData, vStart + 1, hostLen, StandardCharsets.UTF_8);
                    int port = ((appData[vStart + 1 + hostLen] & 0xFF) << 8)
                             | (appData[vStart + 2 + hostLen] & 0xFF);
                    if (port >= 1 && port <= 0xFFFF) gwPort = port;
                }
            }
            p = vStart + len; // skip unknown types too
        }
        return new AnnounceInfo(version, gwHost, gwPort);
    }

    /** Legacy QGW1 gateway-only payload, as sent by peers predating the QAN1 container. */
    private static AnnounceInfo decodeLegacyGateway(byte[] appData) {
        if (!hasMagic(appData, QGW_MAGIC) || appData.length < QGW_MIN_LEN) {
            return AnnounceInfo.EMPTY;
        }
        int hostLen = appData[QGW_MAGIC.length] & 0xFF;
        int hostStart = QGW_MAGIC.length + 1;
        if (hostLen < 1 || appData.length < hostStart + hostLen + 2) return AnnounceInfo.EMPTY;

        String host = new String(appData, hostStart, hostLen, StandardCharsets.UTF_8);
        int port = ((appData[hostStart + hostLen] & 0xFF) << 8)
                 | (appData[hostStart + hostLen + 1] & 0xFF);
        if (port < 1 || port > 0xFFFF) return AnnounceInfo.EMPTY;
        return new AnnounceInfo(null, host, port);
    }

    private static boolean hasMagic(byte[] appData, byte[] magic) {
        if (appData == null || appData.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (appData[i] != magic[i]) return false;
        }
        return true;
    }

    /**
     * Parse "x.y.z[-hash]" (with or without the "qortal-" prefix) to the 3-short packed long used
     * for min-version comparison (same scheme as IPPeer). Returns 0 if unparseable.
     */
    public static long parseVersionToLong(String versionString) {
        if (versionString == null) return 0L;
        String s = versionString.startsWith(Controller.VERSION_PREFIX)
                ? versionString : Controller.VERSION_PREFIX + versionString;
        Matcher m = Peer.VERSION_PATTERN.matcher(s);
        if (!m.lookingAt()) return 0L;
        long v = 0;
        for (int g = 1; g <= 3; g++) {
            long value = Long.parseLong(m.group(g));
            if (value < 0 || value > Short.MAX_VALUE) return 0L;
            v = (v << 16) | value;
        }
        return v;
    }

    /**
     * Whether a host string is suitable to advertise as a gateway or to dial as a
     * dynamically-announced gateway. Rejects:
     * <ul>
     *   <li>null/empty</li>
     *   <li>"localhost" (case-insensitive)</li>
     *   <li>loopback IPv4 (127.x.x.x) and IPv6 (::1)</li>
     *   <li>bare single-label names with no dot — not an FQDN, not resolvable for arbitrary peers</li>
     * </ul>
     * The check is best-effort: we cannot tell from inside the process whether a name actually
     * resolves for any given peer, only catch the obvious cases that the auto-detection commonly
     * produces on desktops, VMs and NAT'd hosts.
     */
    public static boolean isUsableAdvertiseHost(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.isEmpty()) return false;
        if (h.equalsIgnoreCase("localhost")) return false;
        if (h.startsWith("127.")) return false;
        if (h.equals("::1")) return false;
        // Require at least one dot. Catches "dev-vm-2-desktop" and similar local hostnames; both
        // real FQDNs and IPv4/IPv6 literals have dots (or colons — we accept ':' too for raw IPv6,
        // though that is unusual).
        return h.indexOf('.') >= 0 || h.indexOf(':') >= 0;
    }

    /** Decoded announce appData: any field may be absent (version null, gwHost null, gwPort 0). */
    public static final class AnnounceInfo {

        static final AnnounceInfo EMPTY = new AnnounceInfo(null, null, 0);

        private final String version;
        private final String gatewayHost;
        private final int gatewayPort;

        AnnounceInfo(String version, String gatewayHost, int gatewayPort) {
            this.version = version;
            this.gatewayHost = gatewayHost;
            this.gatewayPort = gatewayPort;
        }

        public String getVersion() {
            return version;
        }

        public String getGatewayHost() {
            return gatewayHost;
        }

        /** Announced gateway port, or 0 when no gateway record was present. */
        public int getGatewayPort() {
            return gatewayPort;
        }

        /** True when both host and port are present, i.e. there is something worth dialling. */
        public boolean hasGateway() {
            return gatewayHost != null && gatewayPort > 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AnnounceInfo)) return false;
            AnnounceInfo other = (AnnounceInfo) o;
            return gatewayPort == other.gatewayPort
                    && Objects.equals(version, other.version)
                    && Objects.equals(gatewayHost, other.gatewayHost);
        }

        @Override
        public int hashCode() {
            return Objects.hash(version, gatewayHost, gatewayPort);
        }

        @Override
        public String toString() {
            return "AnnounceInfo{version=" + version
                    + ", gateway=" + gatewayHost + ":" + gatewayPort + "}";
        }
    }
}
