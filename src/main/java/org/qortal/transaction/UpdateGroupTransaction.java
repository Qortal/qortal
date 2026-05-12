package org.qortal.transaction;

import com.google.common.base.Utf8;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdateGroupTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.Collections;
import java.util.List;

public class UpdateGroupTransaction extends Transaction {

	// Properties
	private UpdateGroupTransactionData updateGroupTransactionData;

	// Constructors

	public UpdateGroupTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.updateGroupTransactionData = (UpdateGroupTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.updateGroupTransactionData.getNewOwner());
	}

	// Navigation

	public Account getOwner() {
		return this.getCreator();
	}

	public Account getNewOwner() throws DataException {
		return new Account(this.repository, this.updateGroupTransactionData.getNewOwner());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check new owner address is valid
		if (!Crypto.isValidAddress(this.updateGroupTransactionData.getNewOwner()))
			return ValidationResult.INVALID_ADDRESS;

		// Check new approval threshold is valid
		if (this.updateGroupTransactionData.getNewApprovalThreshold() == null)
			return ValidationResult.INVALID_GROUP_APPROVAL_THRESHOLD;

		// Check min/max block delay values
		if (this.updateGroupTransactionData.getNewMinimumBlockDelay() < 0)
			return ValidationResult.INVALID_GROUP_BLOCK_DELAY;

		if (this.updateGroupTransactionData.getNewMaximumBlockDelay() < 1)
			return ValidationResult.INVALID_GROUP_BLOCK_DELAY;

		if (this.updateGroupTransactionData.getNewMaximumBlockDelay() < this.updateGroupTransactionData.getNewMinimumBlockDelay())
			return ValidationResult.INVALID_GROUP_BLOCK_DELAY;

		// Check new join fee is not negative
		if (this.updateGroupTransactionData.getNewJoinFee() < 0)
			return ValidationResult.INVALID_GROUP_JOIN_FEE;

		// Check new description size bounds
		int newDescriptionLength = Utf8.encodedLength(this.updateGroupTransactionData.getNewDescription());
		if (newDescriptionLength < 1 || newDescriptionLength > Group.MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		GroupData groupData = this.repository.getGroupRepository().fromGroupId(this.updateGroupTransactionData.getGroupId());

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		// As this transaction type could require approval, check txGroupId matches groupID at creation
		if (groupData.getCreationGroupId() != this.updateGroupTransactionData.getTxGroupId())
			return ValidationResult.TX_GROUP_ID_MISMATCH;

		Account owner = getOwner();

		// Check creator is group's current owner
		if (!owner.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Check creator has enough funds
		if (owner.getConfirmedBalance(Asset.QORT) < this.updateGroupTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		GroupData groupData = this.repository.getGroupRepository().fromGroupId(this.updateGroupTransactionData.getGroupId());
		Account owner = getOwner();

		// Check transaction's public key matches group's current owner
		if (!owner.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		Account newOwner = getNewOwner();

		// Check new owner is not banned
		if (this.repository.getGroupRepository().banExists(this.updateGroupTransactionData.getGroupId(), newOwner.getAddress(), this.updateGroupTransactionData.getTimestamp()))
			return ValidationResult.BANNED_FROM_GROUP;

		return ValidationResult.OK;
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public void process() throws DataException {
		// Update Group
		Group group = new Group(this.repository, this.updateGroupTransactionData.getGroupId());
		group.updateGroup(this.updateGroupTransactionData);

		// Save this transaction, now with updated "group reference" to previous transaction that updated group
		this.repository.getTransactionRepository().save(this.updateGroupTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert Group update
		Group group = new Group(this.repository, this.updateGroupTransactionData.getGroupId());
		group.unupdateGroup(this.updateGroupTransactionData);

		// Save this transaction, now with removed "group reference"
		this.repository.getTransactionRepository().save(this.updateGroupTransactionData);
	}

}
