package org.qortal.utils;

import org.qortal.controller.ChatTransactionDelegate;
import org.qortal.data.chat.ChatStat;
import org.qortal.data.chat.GroupChatStat;
import org.qortal.data.transaction.ChatTransactionData;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class ChatAnalysisUtils
 */
public class ChatAnalysisUtils {

    /**
     * Get Stats
     *
     * Get the most active users in the chat.
     *
     * @param limit the maximum number of users stats to return
     *
     * @return the chat stats
     */
    public static List<ChatStat> getStats(long limit) {

        List<ChatTransactionData> chatTransactions
                = ChatTransactionDelegate.getInstance().getValidatedChatTransactions();

        Map<String, Long> sizeBySender
            = chatTransactions.stream()
                .collect(
                    Collectors.toMap(
                            ChatTransactionData::getSender, chat -> estimateSize(chat), (a, b) -> a + b
                    )
                );

        Map<String, String> primaryByOwner = ChatTransactionDelegate.getInstance().getPrimaryNameByOwner();

        final long adjustedLimit = limit > 0 ? limit : Long.MAX_VALUE;

        List<ChatStat> stats
            = sizeBySender.entrySet().stream()
                .map( entry -> new ChatStat(entry.getKey(), primaryByOwner.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparingLong(ChatStat::getSize).reversed())
                .limit(adjustedLimit)
                .collect(Collectors.toList());

        return stats;
    }

    /**
     * Group Stats
     *
     * Get the most active groups in the chat.
     *
     * @param limit the maximum number of group stats to return
     *
     * @return the group stats
     */
    public static List<GroupChatStat> getGroupStats(long limit) {

        List<ChatTransactionData> chatTransactions
                = ChatTransactionDelegate.getInstance().getValidatedChatTransactions();

        Map<Integer, Long> sizeBySender
                = chatTransactions.stream()
                .collect(
                        Collectors.toMap(
                                ChatTransactionData::getTxGroupId, chat -> estimateSize(chat), (a, b) -> a + b
                        )
                );

        final long adjustedLimit = limit > 0 ? limit : Long.MAX_VALUE;

        List<GroupChatStat> stats
                = sizeBySender.entrySet().stream()
                .map( entry -> new GroupChatStat(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(GroupChatStat::getSize).reversed())
                .limit(adjustedLimit)
                .collect(Collectors.toList());

        return stats;
    }

    /**
     * Get Size
     *
     * The amount of memory allocated by chat data.
     *
     * @return the amount in bytes
     */
    public static long getSize() {

        return
            ChatTransactionDelegate.getInstance()
                .getValidatedChatTransactions().stream()
                .collect(Collectors.summingLong( chat -> estimateSize(chat)));
    }

    /**
     * Estimate Size
     *
     * Calculate the size of a chat transaction data object.
     *
     * @param chatTransactionData the chat data
     *
     * @return the size in bytes
     */
    public static long estimateSize(ChatTransactionData chatTransactionData) {
        // Header size (typically 12 or 16 bytes)
        long size = 16;

        // Add field sizes for all references (assuming compressed oops)
        size += 19 * 4;  // 19 reference fields

        // Add primitive field sizes
        size += 4;  // nonce
        size += 8;  // timestamp
        size += 4;  // txGroupId
        size += 2;  // isText and isEncrypted

        // Add padding to 8-byte boundary
        size = (size + 7) & ~7;

        // Add referenced object sizes if not null
        if (chatTransactionData.getSenderPublicKey() != null) {
            size += estimateByteArraySize(chatTransactionData.getSenderPublicKey());
        }
        if (chatTransactionData.getSender() != null) {
            size += estimateStringSize(chatTransactionData.getSender());
        }
        if (chatTransactionData.getChatReference() != null) {
            size += estimateByteArraySize(chatTransactionData.getChatReference());
        }
        if (chatTransactionData.getData() != null) {
            size += estimateByteArraySize(chatTransactionData.getData());
        }
        if (chatTransactionData.getType() != null) {
            size += 24;  // Approximate enum size
        }
        if (chatTransactionData.getCreatorPublicKey() != null) {
            size += estimateByteArraySize(chatTransactionData.getCreatorPublicKey());
        }
        if (chatTransactionData.getReference() != null) {
            size += estimateByteArraySize(chatTransactionData.getReference());
        }
        if (chatTransactionData.getFee() != null) {
            size += 24;  // Approximate Long object size
        }
        if (chatTransactionData.getSignature() != null) {
            size += estimateByteArraySize(chatTransactionData.getSignature());
        }
        if (chatTransactionData.getRecipient() != null) {
            size += estimateStringSize(chatTransactionData.getRecipient());
        }
        if (chatTransactionData.getBlockHeight() != null) {
            size += 24;  // Approximate Integer object size
        }
        if (chatTransactionData.getBlockSequence() != null) {
            size += 24;
        }
        if (chatTransactionData.getApprovalStatus() != null) {
            size += 24;  // Approximate enum size
        }
        if (chatTransactionData.getApprovalHeight() != null) {
            size += 24;
        }

        return size;
    }

    /**
     * Estimate Byte Array Size
     *
     * @param array the array
     *
     * @return the size in bytes
     */
    private static long estimateByteArraySize(byte[] array) {
        // Array header (16 bytes) + data
        return 16 + array.length;
    }

    /**
     * Estimate String Size
     *
     * @param str the string
     *
     * @return the size in bytes
     */
    private static long estimateStringSize(String str) {
        // String object (16 bytes) + char array (16 bytes + 2 * length)
        return 32 + (str.length() * 2);
    }
}
