package org.qortal.repository.hsqldb.transaction;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBJoinGroupTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBJoinGroupTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT group_id, join_fee, invite_reference, previous_group_id FROM JoinGroupTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int groupId = resultSet.getInt(1);
			Long joinFee = resultSet.getLong(2);
			if (joinFee == 0 && resultSet.wasNull())
				joinFee = null;
			byte[] inviteReference = resultSet.getBytes(3);

			Integer previousGroupId = resultSet.getInt(4);
			if (previousGroupId == 0 && resultSet.wasNull())
				previousGroupId = null;

			return new JoinGroupTransactionData(baseTransactionData, groupId, joinFee, inviteReference, previousGroupId);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch join group transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		JoinGroupTransactionData joinGroupTransactionData = (JoinGroupTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("JoinGroupTransactions");

		saveHelper.bind("signature", joinGroupTransactionData.getSignature()).bind("joiner", joinGroupTransactionData.getJoinerPublicKey())
				.bind("group_id", joinGroupTransactionData.getGroupId()).bind("join_fee", joinGroupTransactionData.getJoinFee())
				.bind("invite_reference", joinGroupTransactionData.getInviteReference())
				.bind("previous_group_id", joinGroupTransactionData.getPreviousGroupId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save join group transaction into repository", e);
		}
	}

}
