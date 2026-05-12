package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdateGroupTransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class UpdateGroupTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int groupId = 1;
		String newOwner = account.getAddress();
		String newDescription = "updated random test group";
		final boolean newIsOpen = false;
		ApprovalThreshold newApprovalThreshold = ApprovalThreshold.PCT20;
		final int newMinimumBlockDelay = 10;
		final int newMaximumBlockDelay = 60;

		return new UpdateGroupTransactionData(generateBase(account), groupId, newOwner, newDescription, newIsOpen, newApprovalThreshold, newMinimumBlockDelay, newMaximumBlockDelay, 0L, (byte[]) null);
	}

}
