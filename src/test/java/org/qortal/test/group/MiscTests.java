package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.LeaveGroupTransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction.ValidationResult;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MiscTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCreateGroupWithExistingName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			// Create group
			createGroup(repository, alice, "test-group", false);

			// duplicate
			String duplicateGroupName = "TEST-gr0up";
			String description = duplicateGroupName + " (description)";

			boolean isOpen = false;
			ApprovalThreshold approvalThreshold = ApprovalThreshold.ONE;
			int minimumBlockDelay = 10;
			int maximumBlockDelay = 1440;

			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(alice), duplicateGroupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay, 0);
			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	@Test
	public void testJoinOpenGroup() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testJoinClosedGroup() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "closed-group", false);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob still not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Have Alice 'invite' Bob to confirm membership
			groupInvite(repository, alice, groupId, bob.getAddress(), 0); // non-expiring invite

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testJoinGroupViaInvite() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "closed-group", false);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Have Alice 'invite' Bob to join
			groupInvite(repository, alice, groupId, bob.getAddress(), 0); // non-expiring invite

			// Confirm Bob still not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob uses invite to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testLeaveGroup() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Bob leaves
			leaveGroup(repository, bob, groupId);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	private Integer createGroup(Repository repository, PrivateKeyAccount owner, String groupName, boolean isOpen) throws DataException {
		String description = groupName + " (description)";

		ApprovalThreshold approvalThreshold = ApprovalThreshold.ONE;
		int minimumBlockDelay = 10;
		int maximumBlockDelay = 1440;

		CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(owner), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay, 0);
		TransactionUtils.signAndMint(repository, transactionData, owner);

		return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
	}

	private void joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
		TransactionUtils.signAndMint(repository, transactionData, joiner);
	}

	private void groupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin), groupId, invitee, timeToLive, 0L);
		TransactionUtils.signAndMint(repository, transactionData, admin);
	}

	private void leaveGroup(Repository repository, PrivateKeyAccount leaver, int groupId) throws DataException {
		LeaveGroupTransactionData transactionData = new LeaveGroupTransactionData(TestTransaction.generateBase(leaver), groupId);
		TransactionUtils.signAndMint(repository, transactionData, leaver);
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

}
