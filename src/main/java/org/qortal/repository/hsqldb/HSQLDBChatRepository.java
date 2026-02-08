package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;
import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ActiveChats.DirectChat;
import org.qortal.data.chat.ActiveChats.GroupChat;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.group.GroupMemberData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.repository.ChatRepository;
import org.qortal.repository.DataException;
import org.qortal.transaction.Transaction.TransactionType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.qortal.data.chat.ChatMessage.Encoding;

public class HSQLDBChatRepository implements ChatRepository {

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBChatRepository.class);

	protected HSQLDBRepository repository;

	public HSQLDBChatRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}
	
	@Override
	public List<ChatMessage> getMessagesMatchingCriteria(Long before, Long after, Integer txGroupId, byte[] referenceBytes,
														 byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress,
														 Encoding encoding, Integer limit, Integer offset, Boolean reverse) throws DataException {
		// Check args meet expectations
		if ((txGroupId != null && involving != null && !involving.isEmpty())
				|| (txGroupId == null && (involving == null || involving.size() != 2)))
			throw new DataException("Invalid criteria for fetching chat messages from repository");

		StringBuilder sql = new StringBuilder(1024);

		String tableName;

		// if the PrimaryTable is available, then use it
		if( this.repository.getBlockRepository().getBlockchainHeight() > BlockChain.getInstance().getMultipleNamesPerAccountHeight()) {
			tableName = "PrimaryNames";
		}
		else {
			tableName = "Names";
		}

		sql.append("SELECT created_when, tx_group_id, Transactions.reference, creator, "
				+ "sender, SenderNames.name, recipient, RecipientNames.name, "
				+ "chat_reference, data, is_text, is_encrypted, signature "
				+ "FROM ChatTransactions "
				+ "JOIN Transactions USING (signature) "
				+ "LEFT OUTER JOIN " + tableName + " AS SenderNames ON SenderNames.owner = sender "
				+ "LEFT OUTER JOIN " + tableName + " AS RecipientNames ON RecipientNames.owner = recipient ");

		// WHERE clauses

		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		// Timestamp range
		if (before != null) {
			whereClauses.add("created_when < ?");
			bindParams.add(before);
		}

		if (after != null) {
			whereClauses.add("created_when > ?");
			bindParams.add(after);
		}

		if (referenceBytes != null) {
			whereClauses.add("reference = ?");
			bindParams.add(referenceBytes);
		}

		if (chatReferenceBytes != null) {
			whereClauses.add("chat_reference = ?");
			bindParams.add(chatReferenceBytes);
		}

		if (hasChatReference != null && hasChatReference) {
			whereClauses.add("chat_reference IS NOT NULL");
		}
		else if (hasChatReference != null && !hasChatReference) {
			whereClauses.add("chat_reference IS NULL");
		}

		if (senderAddress != null) {
			whereClauses.add("sender = ?");
			bindParams.add(senderAddress);
		}

		if (txGroupId != null) {
			whereClauses.add("tx_group_id = " + txGroupId); // int safe to use literally
			whereClauses.add("recipient IS NULL");
		} else {
			whereClauses.add("((sender = ? AND recipient = ?) OR (recipient = ? AND sender = ?))");
			bindParams.addAll(involving);
			bindParams.addAll(involving);
		}

		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		sql.append(" ORDER BY Transactions.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ChatMessage> chatMessages = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return chatMessages;

			do {
				long timestamp = resultSet.getLong(1);
				int groupId = resultSet.getInt(2);
				byte[] reference = resultSet.getBytes(3);
				byte[] senderPublicKey = resultSet.getBytes(4);
				String sender = resultSet.getString(5);
				String senderName = resultSet.getString(6);
				String recipient = resultSet.getString(7);
				String recipientName = resultSet.getString(8);
				byte[] chatReference = resultSet.getBytes(9);
				byte[] data = resultSet.getBytes(10);
				boolean isText = resultSet.getBoolean(11);
				boolean isEncrypted = resultSet.getBoolean(12);
				byte[] signature = resultSet.getBytes(13);

				ChatMessage chatMessage = new ChatMessage(timestamp, groupId, reference, senderPublicKey, sender,
						senderName, recipient, recipientName, chatReference, encoding, data, isText, isEncrypted, signature);

				chatMessages.add(chatMessage);
			} while (resultSet.next());

			// if this is a group chat, then ensure that the sender is in the group
			if( txGroupId != null && txGroupId > 0 ) {
				List<String> members
					= this.repository.getGroupRepository()
						.getGroupMembers(txGroupId).stream()
						.map(GroupMemberData::getMember)
						.collect(Collectors.toList());

				chatMessages.removeIf( data -> !members.contains(data.getSender()) );
			}

			return chatMessages;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching chat transactions from repository", e);
		}
	}

	@Override
	public ChatMessage toChatMessage(ChatTransactionData chatTransactionData, Encoding encoding) throws DataException {

		String tableName;

		// if the PrimaryTable is available, then use it
		if( this.repository.getBlockRepository().getBlockchainHeight() > BlockChain.getInstance().getMultipleNamesPerAccountHeight()) {
			tableName = "PrimaryNames";
		}
		else {
			tableName = "Names";
		}

		String sql = "SELECT SenderNames.name, RecipientNames.name "
				+ "FROM ChatTransactions "
				+ "LEFT OUTER JOIN " + tableName + " AS SenderNames ON SenderNames.owner = sender "
				+ "LEFT OUTER JOIN " + tableName + " AS RecipientNames ON RecipientNames.owner = recipient "
				+ "WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, chatTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String senderName = resultSet.getString(1);
			String recipientName = resultSet.getString(2);

			long timestamp = chatTransactionData.getTimestamp();
			int groupId = chatTransactionData.getTxGroupId();
			byte[] reference = chatTransactionData.getReference();
			byte[] senderPublicKey = chatTransactionData.getSenderPublicKey();
			String sender = chatTransactionData.getSender();
			String recipient = chatTransactionData.getRecipient();
			byte[] chatReference = chatTransactionData.getChatReference();
			byte[] data = chatTransactionData.getData();
			boolean isText = chatTransactionData.getIsText();
			boolean isEncrypted = chatTransactionData.getIsEncrypted();
			byte[] signature = chatTransactionData.getSignature();

			return new ChatMessage(timestamp, groupId, reference, senderPublicKey, sender,
					senderName, recipient, recipientName, chatReference, encoding, data,
					isText, isEncrypted, signature);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch convert chat transaction from repository", e);
		}
	}

	@Override
	public ActiveChats getActiveChats(String address, Encoding encoding, Boolean hasChatReference) throws DataException {
		List<GroupChat> groupChats = getActiveGroupChats(address, encoding, hasChatReference);
		List<DirectChat> directChats = getActiveDirectChats(address, hasChatReference);

		return new ActiveChats(groupChats, directChats);
	}
	
	private List<GroupChat> getActiveGroupChats(String address, Encoding encoding, Boolean hasChatReference) throws DataException {
		String tableName;

		// if the PrimaryTable is available, then use it
		if( this.repository.getBlockRepository().getBlockchainHeight() > BlockChain.getInstance().getMultipleNamesPerAccountHeight()) {
			tableName = "PrimaryNames";
		}
		else {
			tableName = "Names";
		}

		// Find groups where address is a member and potential latest message details
		String groupsSql = "SELECT group_id, group_name, latest_timestamp, sender, sender_name, signature, data "
				+ "FROM GroupMembers "
				+ "JOIN Groups USING (group_id) "
				+ "LEFT OUTER JOIN LATERAL("
					+ "SELECT created_when AS latest_timestamp, sender, name AS sender_name, signature, data "
					+ "FROM ChatTransactions "
					+ "JOIN Transactions USING (signature) "
					+ "LEFT OUTER JOIN " + tableName + " AS SenderNames ON SenderNames.owner = sender "
					// NOTE: We need to qualify "Groups.group_id" here to avoid "General error" bug in HSQLDB v2.5.0
					+ "WHERE tx_group_id = Groups.group_id AND type = " + TransactionType.CHAT.value + " ";

					if (hasChatReference != null) {
						if (hasChatReference) {
							groupsSql += "AND chat_reference IS NOT NULL ";
						} else {
							groupsSql += "AND chat_reference IS NULL ";
						}
					}
					groupsSql += "ORDER BY created_when DESC "
					+ "LIMIT 1"
					+ ") AS LatestMessages ON TRUE "
					+ "WHERE address = ?";

		List<GroupChat> groupChats = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(groupsSql, address)) {
			if (resultSet != null) {
				do {
					int groupId = resultSet.getInt(1);
					String groupName = resultSet.getString(2);

					Long timestamp = resultSet.getLong(3);
					if (timestamp == 0 && resultSet.wasNull())
						timestamp = null;

					String sender = resultSet.getString(4);
					String senderName = resultSet.getString(5);
					byte[] signature = resultSet.getBytes(6);
					byte[] data = resultSet.getBytes(7);

					GroupChat groupChat = new GroupChat(groupId, groupName, timestamp, sender, senderName, signature, encoding, data);
					groupChats.add(groupChat);
				} while (resultSet.next());
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch active group chats from repository", e);
		}

		// We need different SQL to handle group-less chat
		String grouplessSql = "SELECT created_when, sender, SenderNames.name, signature, data "
				+ "FROM ChatTransactions "
				+ "JOIN Transactions USING (signature) "
				+ "LEFT OUTER JOIN " + tableName + " AS SenderNames ON SenderNames.owner = sender "
				+ "WHERE tx_group_id = 0 "
				+ "AND recipient IS NULL ";

				if (hasChatReference != null) {
					if (hasChatReference) {
						grouplessSql += "AND chat_reference IS NOT NULL ";
					} else {
						grouplessSql += "AND chat_reference IS NULL ";
					}
				}
				grouplessSql += "ORDER BY created_when DESC "
				+ "LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(grouplessSql)) {
			Long timestamp = null;
			String sender = null;
			String senderName = null;
			byte[] signature = null;
			byte[] data = null;

			if (resultSet != null) {
				// We found a recipient-less, group-less CHAT message, so report its details
				timestamp = resultSet.getLong(1);
				sender = resultSet.getString(2);
				senderName = resultSet.getString(3);
				signature = resultSet.getBytes(4);
				data = resultSet.getBytes(5);
			}

			GroupChat groupChat = new GroupChat(0, null, timestamp, sender, senderName, signature, encoding, data);
			groupChats.add(groupChat);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch active group chats from repository", e);
		}

		return groupChats;
	}

	private List<DirectChat> getActiveDirectChats(String address, Boolean hasChatReference) throws DataException {
		String tableName;

		// if the PrimaryTable is available, then use it
		if( this.repository.getBlockRepository().getBlockchainHeight() > BlockChain.getInstance().getMultipleNamesPerAccountHeight()) {
			tableName = "PrimaryNames";
		}
		else {
			tableName = "Names";
		}

		// Find chat messages involving address
		String directSql = "SELECT other_address, name, latest_timestamp, sender, sender_name "
				+ "FROM ("
					+ "SELECT recipient FROM ChatTransactions "
					+ "WHERE sender = ? AND recipient IS NOT NULL "
					+ "UNION "
					+ "SELECT sender FROM ChatTransactions "
					+ "WHERE recipient = ?"
				+ ") AS OtherParties (other_address) "
				+ "CROSS JOIN LATERAL("
					+ "SELECT created_when AS latest_timestamp, sender, name AS sender_name "
					+ "FROM ChatTransactions "
					+ "NATURAL JOIN Transactions "
					+ "LEFT OUTER JOIN " + tableName + " AS SenderNames ON SenderNames.owner = sender "
					+ "WHERE (sender = other_address AND recipient = ?) "
					+ "OR (sender = ? AND recipient = other_address) ";

			    // Apply hasChatReference filter
				if (hasChatReference != null) {
					if (hasChatReference) {
						directSql += "AND chat_reference IS NOT NULL ";
					} else {
						directSql += "AND chat_reference IS NULL ";
					}
				}
			
				directSql += "ORDER BY created_when DESC "
						+ "LIMIT 1"
						+ ") AS LatestMessages "
						+ "LEFT OUTER JOIN " + tableName + " ON owner = other_address";

		Object[] bindParams = new Object[] { address, address, address, address };

		List<DirectChat> directChats = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(directSql, bindParams)) {
			if (resultSet == null)
				return directChats;

			do {
				String otherAddress = resultSet.getString(1);
				String name = resultSet.getString(2);
				long timestamp = resultSet.getLong(3);
				String sender = resultSet.getString(4);
				String senderName = resultSet.getString(5);

				DirectChat directChat = new DirectChat(otherAddress, name, timestamp, sender, senderName);
				directChats.add(directChat);
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch active direct chats from repository", e);
		}

		return directChats;
	}

}