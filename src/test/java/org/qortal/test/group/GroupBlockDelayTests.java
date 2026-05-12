package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.data.transaction.UpdateGroupTransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.CreateGroupTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transaction.UpdateGroupTransaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class GroupBlockDelayTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCreateGroupBlockDelayValues() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			// Check we're starting with something valid
			Transaction transaction = buildCreateGroupWithDelays(repository, alice, 10, 40);
			assertEquals(ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, -1, 40);
			assertNotSame("Negative minimum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, 10, -1);
			assertNotSame("Negative maximum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, 10, 0);
			assertNotSame("Zero maximum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, 40, 10);
			assertNotSame("Maximum block delay smaller than minimum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, 40, 40);
			assertEquals("Maximum block delay same as minimum block delay should be OK", ValidationResult.OK, transaction.isValid());
		}
	}

	private CreateGroupTransaction buildCreateGroupWithDelays(Repository repository, PrivateKeyAccount account, int minimumBlockDelay, int maximumBlockDelay) throws DataException {
		String groupName = "test group";
		String description = "random test group";
		final boolean isOpen = false;
		ApprovalThreshold approvalThreshold = ApprovalThreshold.PCT40;

		CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(account), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay, 0);
		return new CreateGroupTransaction(repository, transactionData);
	}

	@Test
	public void testUpdateGroupBlockDelayValues() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, 10, 40);

			// Check we're starting with something valid
			Transaction transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 10, 40);
			assertEquals(ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, -1, 40);
			assertNotSame("Negative minimum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 10, -1);
			assertNotSame("Negative maximum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 10, 0);
			assertNotSame("Zero maximum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 40, 10);
			assertNotSame("Maximum block delay smaller than minimum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 40, 40);
			assertEquals("Maximum block delay same as minimum block delay should be OK", ValidationResult.OK, transaction.isValid());
		}
	}

	private UpdateGroupTransaction buildUpdateGroupWithDelays(Repository repository, PrivateKeyAccount account, int groupId, int newMinimumBlockDelay, int newMaximumBlockDelay) throws DataException {
		String newOwner = account.getAddress();
		String newDescription = "random test group";
		final boolean newIsOpen = false;
		ApprovalThreshold newApprovalThreshold = ApprovalThreshold.PCT40;

		UpdateGroupTransactionData transactionData = new UpdateGroupTransactionData(TestTransaction.generateBase(account), groupId, newOwner, newDescription, newIsOpen, newApprovalThreshold, newMinimumBlockDelay, newMaximumBlockDelay, 0L, (byte[]) null);
		return new UpdateGroupTransaction(repository, transactionData);
	}

}
