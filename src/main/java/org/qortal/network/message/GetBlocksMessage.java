package org.qortal.network.message;

import com.google.common.primitives.Ints;
import org.qortal.transform.Transformer;
import org.qortal.transform.block.BlockTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class GetBlocksMessage extends Message {

    private static final int BLOCK_SIGNATURE_LENGTH = BlockTransformer.BLOCK_SIGNATURE_LENGTH;

    private final byte[] parentSignature;
    private final int numberRequested;

    public GetBlocksMessage(byte[] parentSignature, int numberRequested) {
        super(MessageType.GET_BLOCKS);

        this.parentSignature = parentSignature;
        this.numberRequested = numberRequested;

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            bytes.write(this.parentSignature);

            bytes.write(Ints.toByteArray(this.numberRequested));

            this.dataBytes = bytes.toByteArray();
        } catch (IOException e) {
            this.dataBytes = null;
            this.checksumBytes = null;
            return;
        }

        if (this.dataBytes.length > 0)
            this.checksumBytes = Message.generateChecksum(this.dataBytes);
        else
            this.checksumBytes = null;
    }

    private GetBlocksMessage(int id, byte[] parentSignature, int numberRequested) {
        super(id, MessageType.GET_BLOCKS);

        this.parentSignature = parentSignature;
        this.numberRequested = numberRequested;
    }

    public byte[] getParentSignature() {
        return this.parentSignature;
    }

    public int getNumberRequested() {
        return this.numberRequested;
    }

    public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
        if (bytes.remaining() != BLOCK_SIGNATURE_LENGTH + Transformer.INT_LENGTH)
            return null;

        byte[] parentSignature = new byte[BLOCK_SIGNATURE_LENGTH];
        bytes.get(parentSignature);

        int numberRequested = bytes.getInt();

        return new GetBlocksMessage(id, parentSignature, numberRequested);
    }

}
