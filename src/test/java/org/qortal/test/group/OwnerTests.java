package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.AddGroupAdminTransactionData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.RemoveGroupAdminTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction.ValidationResult;

import static org.junit.Assert.*;

public class OwnerTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testAddAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = GroupUtils.createGroup(repository, alice, "open-group", true);

			// Attempt to promote non-member
			ValidationResult result = addGroupAdmin(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Promote Bob to admin
			addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to re-promote admin
			result = addGroupAdmin(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer an admin
			assertFalse(isAdmin(repository, bob.getAddress(), groupId));

			// Confirm Bob is still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Have Alice try to promote herself
			result = addGroupAdmin(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testRemoveAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = GroupUtils.createGroup(repository, alice, "open-group", true);

			// Attempt to demote non-member
			ValidationResult result = removeGroupAdmin(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Attempt to demote non-admin member
			result = removeGroupAdmin(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Promote Bob to admin
			addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to demote admin
			result = removeGroupAdmin(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer an admin
			assertFalse(isAdmin(repository, bob.getAddress(), groupId));

			// Confirm Bob is still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice (owner) try to demote herself
			result = removeGroupAdmin(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to demote Alice (owner)
			result = removeGroupAdmin(repository, bob, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	private ValidationResult joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, joiner);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private ValidationResult addGroupAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String member) throws DataException {
		AddGroupAdminTransactionData transactionData = new AddGroupAdminTransactionData(TestTransaction.generateBase(owner), groupId, member);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, owner);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private ValidationResult removeGroupAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String member) throws DataException {
		RemoveGroupAdminTransactionData transactionData = new RemoveGroupAdminTransactionData(TestTransaction.generateBase(owner), groupId, member);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, owner);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

	private boolean isAdmin(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().adminExists(groupId, address);
	}

}
