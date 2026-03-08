package org.qortal.network.message;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Map;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public enum MessageType {
    // Pseudo-message, not sent over the wire
    UNSUPPORTED(-1, UnsupportedMessage::fromByteBuffer),

    // Handshaking
    HELLO(0, HelloMessage::fromByteBuffer),
    GOODBYE(1, GoodbyeMessage::fromByteBuffer),
    CHALLENGE(2, ChallengeMessage::fromByteBuffer),
    RESPONSE(3, ResponseMessage::fromByteBuffer),
    HELLO_V2(4, HelloV2Message::fromByteBuffer),

    // Status / notifications
    HEIGHT_V2(10, HeightV2Message::fromByteBuffer),
    PING(11, PingMessage::fromByteBuffer),
    PONG(12, PongMessage::fromByteBuffer),
    GENERIC_UNKNOWN(13, GenericUnknownMessage::fromByteBuffer),

    // Requesting data
    PEERS_V2(20, PeersV2Message::fromByteBuffer),
    GET_PEERS(21, GetPeersMessage::fromByteBuffer),

    TRANSACTION(30, TransactionMessage::fromByteBuffer),
    GET_TRANSACTION(31, GetTransactionMessage::fromByteBuffer),

    TRANSACTION_SIGNATURES(40, TransactionSignaturesMessage::fromByteBuffer),
    GET_UNCONFIRMED_TRANSACTIONS(41, GetUnconfirmedTransactionsMessage::fromByteBuffer),

    BLOCK(50, BlockMessage::fromByteBuffer),
    GET_BLOCK(51, GetBlockMessage::fromByteBuffer),
    BLOCK_V2(52, BlockV2Message::fromByteBuffer),

    SIGNATURES(60, SignaturesMessage::fromByteBuffer),
    GET_SIGNATURES_V2(61, GetSignaturesV2Message::fromByteBuffer),

    BLOCK_SUMMARIES(70, BlockSummariesMessage::fromByteBuffer),
    GET_BLOCK_SUMMARIES(71, GetBlockSummariesMessage::fromByteBuffer),
    BLOCK_SUMMARIES_V2(72, BlockSummariesV2Message::fromByteBuffer),
    
    ONLINE_ACCOUNTS_V3(84, OnlineAccountsV3Message::fromByteBuffer),
    GET_ONLINE_ACCOUNTS_V3(85, GetOnlineAccountsV3Message::fromByteBuffer),

    ARBITRARY_DATA(90, ArbitraryDataMessage::fromByteBuffer),
    GET_ARBITRARY_DATA(91, GetArbitraryDataMessage::fromByteBuffer),

    BLOCKS(100, BlocksMessage::fromByteBuffer), 
    GET_BLOCKS(101, GetBlocksMessage::fromByteBuffer),

    ARBITRARY_DATA_FILE(110, ArbitraryDataFileMessage::fromByteBuffer),
    GET_ARBITRARY_DATA_FILE(111, GetArbitraryDataFileMessage::fromByteBuffer),

    GET_ARBITRARY_DATA_FILES(113, GetArbitraryDataFileMessage::fromByteBuffer),

    ARBITRARY_DATA_FILE_LIST(120, ArbitraryDataFileListMessage::fromByteBuffer),
    GET_ARBITRARY_DATA_FILE_LIST(121, GetArbitraryDataFileListMessage::fromByteBuffer),

    ARBITRARY_SIGNATURES(130, ArbitrarySignaturesMessage::fromByteBuffer),

    TRADE_PRESENCES(140, TradePresencesMessage::fromByteBuffer),
    GET_TRADE_PRESENCES(141, GetTradePresencesMessage::fromByteBuffer),

    ARBITRARY_METADATA(150, ArbitraryMetadataMessage::fromByteBuffer),
    GET_ARBITRARY_METADATA(151, GetArbitraryMetadataMessage::fromByteBuffer),

    // Lite node support
    ACCOUNT(160, AccountMessage::fromByteBuffer),
    GET_ACCOUNT(161, GetAccountMessage::fromByteBuffer),

    ACCOUNT_BALANCE(170, AccountBalanceMessage::fromByteBuffer),
    GET_ACCOUNT_BALANCE(171, GetAccountBalanceMessage::fromByteBuffer),

    NAMES(180, NamesMessage::fromByteBuffer),
    GET_ACCOUNT_NAMES(181, GetAccountNamesMessage::fromByteBuffer),
    GET_NAME(182, GetNameMessage::fromByteBuffer),

    TRANSACTIONS(190, TransactionsMessage::fromByteBuffer),
    GET_ACCOUNT_TRANSACTIONS(191, GetAccountTransactionsMessage::fromByteBuffer),

    FOREIGN_FEES( 200, ForeignFeesMessage::fromByteBuffer),
    GET_FOREIGN_FEES( 201, GetForeignFeesMessage::fromByteBuffer);



    public final int value;
    public final MessageProducer fromByteBufferMethod;

    private static final Map<Integer, MessageType> map = stream(MessageType.values())
            .collect(toMap(messageType -> messageType.value, messageType -> messageType));

    MessageType(int value, MessageProducer fromByteBufferMethod) {
        this.value = value;
        this.fromByteBufferMethod = fromByteBufferMethod;
    }

    public static MessageType valueOf(int value) {
        return map.get(value);
    }

    /**
     * Attempt to read a message from byte buffer.
     *
     * @param id message ID or -1
     * @param byteBuffer ByteBuffer source for message
     * @return null if no complete message can be read
     * @throws MessageException if message could not be decoded or is invalid
     * @throws BufferUnderflowException if not enough bytes in buffer to read message
     */
    public Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
        if (this.fromByteBufferMethod == null)
            throw new MessageException("Message type " + this.name() + " unsupported");

        return this.fromByteBufferMethod.fromByteBuffer(id, byteBuffer);
    }
}
