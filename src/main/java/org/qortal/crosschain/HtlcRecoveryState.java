package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Persisted before an explicitly approved broadcast, in the existing 64-byte field. */
public final class HtlcRecoveryState {
    private static final byte[] MAGIC = {'H','T','L','C','F','E','E','1'};
    public final String txid;
    public final long attemptedAt, fee;
    public final boolean confirmed;
    public HtlcRecoveryState(String txid, long attemptedAt, long fee, boolean confirmed) {
        if (txid == null || !txid.matches("[0-9a-f]{64}") || attemptedAt < 0 || fee < 0)
            throw new IllegalArgumentException("Invalid HTLC recovery checkpoint");
        this.txid = txid; this.attemptedAt = attemptedAt; this.fee = fee; this.confirmed = confirmed;
    }
    public byte[] encode() {
        return ByteBuffer.allocate(64).put(MAGIC).put(HashCode.fromString(txid).asBytes())
                .putLong(attemptedAt).putLong(fee).put((byte)(confirmed ? 1 : 0)).array();
    }
    public static HtlcRecoveryState decode(byte[] bytes) {
        if (bytes == null || bytes.length != 64 || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, 8))) return null;
        ByteBuffer b = ByteBuffer.wrap(bytes); b.position(8);
        byte[] hash = new byte[32]; b.get(hash);
        long time = b.getLong(), fee = b.getLong(); int confirmed = b.get();
        if (time < 0 || fee < 0 || confirmed < 0 || confirmed > 1) return null;
        return new HtlcRecoveryState(HashCode.fromBytes(hash).toString(), time, fee, confirmed == 1);
    }
}
