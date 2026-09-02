package org.qortal.transaction;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.CancelGroupBanTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class CancelGroupBanTransaction extends Transaction {

	// Properties

	private CancelGroupBanTransactionData groupUnbanTransactionData;
	private Account memberAccount = null;

	// Constructors

	public CancelGroupBanTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupUnbanTransactionData = (CancelGroupBanTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.groupUnbanTransactionData.getMember());
	}

	// Navigation

	public Account getAdmin() {
		return this.getCreator();
	}

	public Account getMember() {
		if (this.memberAccount == null)
			this.memberAccount = new Account(this.repository, this.groupUnbanTransactionData.getMember());

		return this.memberAccount;
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.groupUnbanTransactionData.getGroupId();

		// Check member address is valid
		if (!Crypto.isValidAddress(this.groupUnbanTransactionData.getMember()))
			return ValidationResult.INVALID_ADDRESS;

		GroupData groupData = this.repository.getGroupRepository().fromGroupId(groupId);

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();

		// Can't unban if not an admin
		if (!this.repository.getGroupRepository().adminExists(groupId, admin.getAddress()))
			return ValidationResult.NOT_GROUP_ADMIN;

		int blockchainHeight = this.repository.getBlockRepository().getBlockchainHeight();

		// Before adminCanKickBan trigger: only the group owner can unban members (old behavior)
		if (blockchainHeight < BlockChain.getInstance().getAdminCanKickBanHeight()) {
			// Can't unban if not group's current owner
			if (!admin.getAddress().equals(groupData.getOwner()))
				return ValidationResult.INVALID_GROUP_OWNER;
		}
		// At/after adminCanKickBan trigger: any admin can unban regular members
		else {
			// Null-ownership groups only exist after nullGroupMembershipHeight trigger.
			// For those decentralized groups, unban operations require group approval.
			if (blockchainHeight >= BlockChain.getInstance().getNullGroupMembershipHeight()) {
				String groupOwner = this.repository.getGroupRepository().getOwner(groupId);
				boolean groupOwnedByNullAccount = Objects.equals(groupOwner, Group.NULL_OWNER_ADDRESS);

				// if null ownership group, then check for admin approval
				if (groupOwnedByNullAccount) {
					// Require approval if transaction relates to a group owned by the null account
					if (!this.needsGroupApproval())
						return ValidationResult.GROUP_APPROVAL_REQUIRED;
				}
			}
			// For regular groups, any admin can unban regular members.
			// Owner and fellow-admin protections are enforced below.
		}

Account member = getMember();

		// Can't unban group owner
		if (member.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Note: We don't check if the banned person was an admin because:
		// 1. Non-owner admins can't ban other admins (protected in GroupBanTransaction)
		// 2. Owner CAN ban admins - in that case, only owner should reverse it
		// 3. However, tracking "banned admin" state isn't available here
		// 4. The admin status is removed during ban, so adminExists() would return false anyway
		// For consistency with kick/ban, we allow any admin to cancel a ban after the feature trigger.

		// Check ban actually exists
		if (!this.repository.getGroupRepository().banExists(groupId, member.getAddress(), this.groupUnbanTransactionData.getTimestamp()))
			return ValidationResult.BAN_UNKNOWN;

		// Check admin has enough funds
		if (admin.getConfirmedBalance(Asset.QORT) < this.groupUnbanTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, this.groupUnbanTransactionData.getGroupId());
		group.cancelBan(this.groupUnbanTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.groupUnbanTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.groupUnbanTransactionData.getGroupId());
		group.uncancelBan(this.groupUnbanTransactionData);

		// Save this transaction with removed member/admin references
		this.repository.getTransactionRepository().save(this.groupUnbanTransactionData);
	}

}
