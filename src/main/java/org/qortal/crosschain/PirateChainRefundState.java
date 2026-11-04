package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Versioned refund checkpoint in the existing 64-byte last-transaction field. No schema change. */
public final class PirateChainRefundState {
    private static final byte[] MAGIC = new byte[] { 'A', 'R', 'R', 'R', 'R', 'F', '0', '1' };
    public final int retryHeight;
    public final long attemptedAt;
    public final String txid;
    public final boolean confirmed;

    public PirateChainRefundState(int retryHeight, long attemptedAt, String txid) {
        this(retryHeight, attemptedAt, txid, false);
    }

    public PirateChainRefundState(int retryHeight, long attemptedAt, String txid, boolean confirmed) {
        this.retryHeight = retryHeight;
        this.attemptedAt = attemptedAt;
        this.txid = txid;
        this.confirmed = confirmed;
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(64).put(MAGIC).putInt(retryHeight).putLong(attemptedAt);
        b.put((byte) (txid == null ? 0 : 1));
        if (txid != null) {
            byte[] hash = HashCode.fromString(txid).asBytes();
            if (hash.length != 32) throw new IllegalArgumentException("Invalid refund txid");
            b.put(hash);
        }
        b.position(53);
        b.put((byte) (confirmed ? 1 : 0));
        return b.array();
    }

    public static PirateChainRefundState decode(byte[] value) {
        if (value == null || value.length != 64 || !Arrays.equals(MAGIC, Arrays.copyOf(value, MAGIC.length))) return null;
        ByteBuffer b = ByteBuffer.wrap(value);
        b.position(MAGIC.length);
        int height = b.getInt();
        long time = b.getLong();
        int present = b.get();
        if (height < 0 || time < 0 || present < 0 || present > 1) return null;
        byte[] txid = new byte[32];
        b.get(txid);
        int confirmed = b.get();
        if (confirmed < 0 || confirmed > 1) return null;
        return new PirateChainRefundState(height, time, present == 0 ? null : HashCode.fromBytes(txid).toString(), confirmed == 1);
    }

    public static boolean isConfirmed(byte[] value) {
        PirateChainRefundState state = decode(value);
        return state != null && state.confirmed;
    }

    /** JNI returns display-order hex; lightwalletd TxFilter expects the internal byte order. */
    public byte[] transactionHash() {
        byte[] hash = HashCode.fromString(txid).asBytes();
        for (int i=0; i<hash.length/2; i++) { byte value=hash[i]; hash[i]=hash[hash.length-1-i]; hash[hash.length-1-i]=value; }
        return hash;
    }

    public boolean mayRetry(int height, long now) {
        return height > retryHeight && now - attemptedAt >= 10 * 60_000L;
    }
}
