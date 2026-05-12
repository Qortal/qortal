package org.qortal.repository.hsqldb.transaction;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBCreateGroupTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBCreateGroupTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT group_name, description, is_open, approval_threshold, min_block_delay, max_block_delay, join_fee, group_id, reduced_group_name "
				+ "FROM CreateGroupTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String groupName = resultSet.getString(1);
			String description = resultSet.getString(2);
			boolean isOpen = resultSet.getBoolean(3);

			ApprovalThreshold approvalThreshold = ApprovalThreshold.valueOf(resultSet.getInt(4));

			int minBlockDelay = resultSet.getInt(5);
			int maxBlockDelay = resultSet.getInt(6);
			long joinFee = resultSet.getLong(7);

			Integer groupId = resultSet.getInt(8);
			if (groupId == 0 && resultSet.wasNull())
				groupId = null;

			String reducedGroupName = resultSet.getString(9);

			return new CreateGroupTransactionData(baseTransactionData, groupName, description, isOpen, approvalThreshold,
					minBlockDelay, maxBlockDelay, joinFee, groupId, reducedGroupName);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch create group transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		CreateGroupTransactionData createGroupTransactionData = (CreateGroupTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("CreateGroupTransactions");

		saveHelper.bind("signature", createGroupTransactionData.getSignature()).bind("creator", createGroupTransactionData.getCreatorPublicKey())
				.bind("group_name", createGroupTransactionData.getGroupName()).bind("reduced_group_name", createGroupTransactionData.getReducedGroupName())
				.bind("description", createGroupTransactionData.getDescription()).bind("is_open", createGroupTransactionData.isOpen())
				.bind("approval_threshold", createGroupTransactionData.getApprovalThreshold().value)
				.bind("min_block_delay", createGroupTransactionData.getMinimumBlockDelay())
				.bind("max_block_delay", createGroupTransactionData.getMaximumBlockDelay()).bind("join_fee", createGroupTransactionData.getJoinFee())
				.bind("group_id", createGroupTransactionData.getGroupId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save create group transaction into repository", e);
		}
	}

}
