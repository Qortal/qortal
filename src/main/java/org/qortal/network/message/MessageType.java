package org.qortal.network.message;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Map;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public enum MessageType {
    // Handshaking
    HELLO(0),
    GOODBYE(1),
    CHALLENGE(2),
    RESPONSE(3),

    // Status / notifications
    HEIGHT_V2(10),
    PING(11),
    PONG(12),

    // Requesting data
    PEERS_V2(20),
    GET_PEERS(21),

    TRANSACTION(30),
    GET_TRANSACTION(31),

    TRANSACTION_SIGNATURES(40),
    GET_UNCONFIRMED_TRANSACTIONS(41),

    BLOCK(50),
    GET_BLOCK(51),

    SIGNATURES(60),
    GET_SIGNATURES_V2(61),

    BLOCK_SUMMARIES(70),
    GET_BLOCK_SUMMARIES(71),

    ONLINE_ACCOUNTS(80),
    GET_ONLINE_ACCOUNTS(81),

    ARBITRARY_DATA(90),
    GET_ARBITRARY_DATA(91);

    public final int value;
    public final Method fromByteBufferMethod;

    private static final Map<Integer, MessageType> map = stream(MessageType.values())
            .collect(toMap(messageType -> messageType.value, messageType -> messageType));

    MessageType(int value) {
        this.value = value;

        String[] classNameParts = this.name().toLowerCase().split("_");

        for (int i = 0; i < classNameParts.length; ++i) {
            classNameParts[i] = classNameParts[i].substring(0, 1).toUpperCase().concat(classNameParts[i].substring(1));
        }

        String className = String.join("", classNameParts);

        Method method;
        try {
            Class<?> subclass = Class.forName(String.join("", Message.class.getPackage().getName(),
                    ".", className, "Message"));

            method = subclass.getDeclaredMethod("fromByteBuffer", int.class, ByteBuffer.class);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
            method = null;
        }

        this.fromByteBufferMethod = method;
    }

    public static MessageType valueOf(int value) {
        return map.get(value);
    }

    public Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
        if (this.fromByteBufferMethod == null) {
            throw new MessageException("Unsupported message type [" + value + "] during conversion from bytes");
        }

        try {
            return (Message) this.fromByteBufferMethod.invoke(null, id, byteBuffer);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            if (e.getCause() instanceof BufferUnderflowException) {
                throw new MessageException("Byte data too short for " + name() + " message");
            }
            throw new MessageException("Internal error with " + name() + " message during conversion from bytes");
        }
    }
}