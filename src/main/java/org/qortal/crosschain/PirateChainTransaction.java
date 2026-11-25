package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.qortal.transform.TransformationException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Reads the transparent prefix only, including CompactSize fields and V5/V6 headers. */
public final class PirateChainTransaction {
    public final BitcoinyTransaction transaction;
    public final int expiryHeight;

    private PirateChainTransaction(BitcoinyTransaction transaction, int expiryHeight) {
        this.transaction = transaction;
        this.expiryHeight = expiryHeight;
    }

    public static PirateChainTransaction read(byte[] raw) throws TransformationException {
        try {
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int header = b.getInt();
            int version = header & 0x7fffffff;
            boolean overwinter = header < 0;
            int group = overwinter ? b.getInt() : 0;
            if (version < 1 || version > 6 || (version >= 3 && !overwinter)
                    || (overwinter && !((version == 3 && group == 0x03c48270)
                    || (version == 4 && group == 0x892f2085)
                    || (version == 5 && group == 0x26a7270a)
                    || (version == 6 && group == 0xd884b698))))
                throw new IllegalArgumentException("Unsupported Pirate transaction header");
            int locktime = 0;
            int expiry = 0;
            if (version >= 5) {
                int branch = b.getInt(); // Current Ironwood V6 header; future NU7 adds another field.
                if (version == 6 && branch != 0x37a5165b) throw new IllegalArgumentException("Unsupported V6 consensus branch");
                locktime = b.getInt();
                expiry = b.getInt();
            }
            List<BitcoinyTransaction.Input> inputs = new ArrayList<>();
            int count = compactSize(b);
            if (count > b.remaining() / 41) throw new IllegalArgumentException("Invalid input count");
            for (int i = 0; i < count; i++) {
                byte[] hash = bytes(b, 32);
                int index = b.getInt();
                byte[] script = bytes(b, compactSize(b));
                int sequence = b.getInt();
                inputs.add(new BitcoinyTransaction.Input(HashCode.fromBytes(script).toString(), sequence,
                        HashCode.fromBytes(hash).toString(), index));
            }
            List<BitcoinyTransaction.Output> outputs = new ArrayList<>();
            count = compactSize(b);
            if (count > b.remaining() / 9) throw new IllegalArgumentException("Invalid output count");
            for (int i = 0; i < count; i++) {
                long value = b.getLong();
                if (value < 0) throw new IllegalArgumentException("Negative output value");
                outputs.add(new BitcoinyTransaction.Output(HashCode.fromBytes(bytes(b, compactSize(b))).toString(), value));
            }
            if (version < 5) {
                locktime = b.getInt();
                if (overwinter) expiry = b.getInt();
            }
            if (expiry < 0) throw new IllegalArgumentException("Invalid expiry height");
            return new PirateChainTransaction(new BitcoinyTransaction(null, raw.length, locktime, null, inputs, outputs), expiry);
        } catch (RuntimeException e) {
            throw new TransformationException("Invalid Pirate transaction prefix: " + e.getMessage());
        }
    }

    private static byte[] bytes(ByteBuffer b, int length) {
        if (length < 0 || length > b.remaining()) throw new IllegalArgumentException("Truncated field");
        byte[] value = new byte[length];
        b.get(value);
        return value;
    }

    private static int compactSize(ByteBuffer b) {
        int first = Byte.toUnsignedInt(b.get());
        long value = first < 253 ? first : first == 253 ? Short.toUnsignedInt(b.getShort())
                : first == 254 ? Integer.toUnsignedLong(b.getInt()) : b.getLong();
        if (value < 0 || value > Integer.MAX_VALUE || (first == 253 && value < 253)
                || (first == 254 && value <= 65535) || (first == 255 && value <= 0xffffffffL))
            throw new IllegalArgumentException("Invalid CompactSize");
        return (int) value;
    }
}
