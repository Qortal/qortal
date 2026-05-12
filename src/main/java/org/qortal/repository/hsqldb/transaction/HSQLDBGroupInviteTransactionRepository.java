package org.qortal.repository.hsqldb.transaction;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBGroupInviteTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBGroupInviteTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT group_id, invitee, time_to_live, join_reference, previous_group_id, join_fee FROM GroupInviteTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int groupId = resultSet.getInt(1);
			String invitee = resultSet.getString(2);
			int timeToLive = resultSet.getInt(3);
			byte[] joinReference = resultSet.getBytes(4);

			Integer previousGroupId = resultSet.getInt(5);
			if (previousGroupId == 0 && resultSet.wasNull())
				previousGroupId = null;

			long joinFee = resultSet.getInt(6);

			return new GroupInviteTransactionData(baseTransactionData, groupId, invitee, timeToLive, joinFee, joinReference, previousGroupId);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group invite transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		GroupInviteTransactionData groupInviteTransactionData = (GroupInviteTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("GroupInviteTransactions");

		saveHelper.bind("signature", groupInviteTransactionData.getSignature()).bind("admin", groupInviteTransactionData.getAdminPublicKey())
				.bind("group_id", groupInviteTransactionData.getGroupId()).bind("invitee", groupInviteTransactionData.getInvitee())
				.bind("time_to_live", groupInviteTransactionData.getTimeToLive()).bind("join_fee", groupInviteTransactionData.getJoinFee())
				.bind("join_reference", groupInviteTransactionData.getJoinReference())
				.bind("previous_group_id", groupInviteTransactionData.getPreviousGroupId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save group invite transaction into repository", e);
		}
	}

}
