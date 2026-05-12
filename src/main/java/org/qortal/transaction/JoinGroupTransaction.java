package org.qortal.transaction;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.Collections;
import java.util.List;

public class JoinGroupTransaction extends Transaction {

	// Properties
	private JoinGroupTransactionData joinGroupTransactionData;

	// Constructors

	public JoinGroupTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.joinGroupTransactionData = (JoinGroupTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getJoiner() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.joinGroupTransactionData.getGroupId();

		// Check group exists
		if (!this.repository.getGroupRepository().groupExists(groupId))
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account joiner = getJoiner();

		if (this.repository.getGroupRepository().memberExists(groupId, joiner.getAddress()))
			return ValidationResult.ALREADY_GROUP_MEMBER;

		// Check member is not banned
		if (this.repository.getGroupRepository().banExists(groupId, joiner.getAddress(), this.joinGroupTransactionData.getTimestamp()))
			return ValidationResult.BANNED_FROM_GROUP;

		// Check join request doesn't already exist
		if (this.repository.getGroupRepository().joinRequestExists(groupId, joiner.getAddress()))
			return ValidationResult.JOIN_REQUEST_EXISTS;

		// Check joiner has enough funds
		if (joiner.getConfirmedBalance(Asset.QORT) < this.joinGroupTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		// Check for join fee if feature trigger is active
		// Use current height + 1 since this transaction will be in the next block
		int currentHeight = this.repository.getBlockRepository().getBlockchainHeight();
		int nextHeight = currentHeight + 1;
		if (nextHeight >= BlockChain.getInstance().getGroupFeeHeight()) {
			GroupData groupData = this.repository.getGroupRepository().fromGroupId(groupId);
			if (groupData != null && groupData.getJoinFee() > 0) {
				// Check joiner has enough funds to pay join fee
				long totalRequired = this.joinGroupTransactionData.getFee() + groupData.getJoinFee();
				if (joiner.getConfirmedBalance(Asset.QORT) < totalRequired)
					return ValidationResult.NO_BALANCE;
			}
		}

		return ValidationResult.OK;
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, this.joinGroupTransactionData.getGroupId());
		group.join(this.joinGroupTransactionData);

		// Save this transaction with cached references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.joinGroupTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.joinGroupTransactionData.getGroupId());
		group.unjoin(this.joinGroupTransactionData);

		// Save this transaction with removed references
		this.repository.getTransactionRepository().save(this.joinGroupTransactionData);
	}

}
