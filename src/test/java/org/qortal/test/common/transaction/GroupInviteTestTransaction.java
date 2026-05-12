package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class GroupInviteTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int groupId = 1;
		String invitee = account.getAddress();
		final int timeToLive = 3600;

		return new GroupInviteTransactionData(generateBase(account), groupId, invitee, timeToLive, 0L);
	}

}
