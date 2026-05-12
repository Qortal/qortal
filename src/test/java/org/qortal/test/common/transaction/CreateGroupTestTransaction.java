package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.Random;

public class CreateGroupTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		Random random = new Random();

		String groupName = "test group " + random.nextInt(1_000_000);
		String description = "random test group";
		final boolean isOpen = false;
		ApprovalThreshold approvalThreshold = ApprovalThreshold.PCT40;
		final int minimumBlockDelay = 5;
		final int maximumBlockDelay = 20;

		return new CreateGroupTransactionData(generateBase(account), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay, 0);
	}

}
