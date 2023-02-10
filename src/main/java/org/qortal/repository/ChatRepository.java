package org.qortal.repository;

import java.util.List;

import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.transaction.ChatTransactionData;

public interface ChatRepository {

	/**
	 * Returns CHAT messages matching criteria.
	 */
	public List<ChatMessage> getMessagesMatchingCriteria(Long before, Long after,
			Integer txGroupId, byte[] reference, byte[] chatReferenceBytes, Boolean hasChatReference,
			List<String> involving, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public ActiveChats getActiveChats(String address) throws DataException;

	public ChatMessage getChatMessageBySignature(byte[] signature) throws DataException;

	public void save(ChatMessage chatMessage) throws DataException;

}
