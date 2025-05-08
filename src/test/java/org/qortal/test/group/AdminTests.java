package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.*;
import org.qortal.group.Group.ApprovalThreshold;
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

public class AdminTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testGroupKickMember() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			ValidationResult result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testGroupKickAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Bob to join
			GroupUtils.joinGroup(repository, bob, groupId);

			// Promote Bob to admin
			addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			ValidationResult result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob now an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice (owner) try to kick herself!
			result = groupKick(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to kick Alice (owner)
			result = groupKick(repository, bob, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testGroupBanMember() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to cancel non-existent Bob ban
			ValidationResult result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Attempt to ban Bob
			int timeToLive = 0;
			result = groupBan(repository, alice, groupId, bob.getAddress(), timeToLive);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Orphan last block (Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed group-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Bob to join
			result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress(), timeToLive);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Cancel Bob's ban
			result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Orphan last block (Bob join)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed join-group transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Orphan last block (Cancel Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed cancel-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Orphan last block (Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed group-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testGroupBanMemberWithExpiry() throws DataException, InterruptedException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to cancel non-existent Bob ban
			ValidationResult result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Attempt to ban Bob for 2 seconds
			int timeToLive = 2;
			result = groupBan(repository, alice, groupId, bob.getAddress(), timeToLive);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Wait for 2 seconds to pass
			Thread.sleep(2000L);

			// Bob attempts to rejoin again
			result = joinGroup(repository, bob, groupId);
			// Should be OK, as the ban has expired
			assertSame(ValidationResult.OK, result);

			// Confirm Bob is now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block (Bob join)
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block (Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed group-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Bob to join
			result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));


			// Attempt to ban Bob for 2 seconds
			result = groupBan(repository, alice, groupId, bob.getAddress(), 2);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Wait for 2 seconds to pass
			Thread.sleep(2000L);

			// Cancel Bob's ban
			result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK, as ban has already expired
			assertNotSame(ValidationResult.OK, result);

			// Confirm Bob still not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should be OK, as no longer banned
			assertSame(ValidationResult.OK, result);

			// Confirm Bob is now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));


			// Attempt to ban Bob for 10 seconds
			result = groupBan(repository, alice, groupId, bob.getAddress(), 10);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK, as ban still exists
			assertNotSame(ValidationResult.OK, result);

			// Cancel Bob's ban
			result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should be OK, as ban still exists
			assertEquals(ValidationResult.OK, result);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should be OK, as no longer banned
			assertEquals(ValidationResult.OK, result);

			// Orphan last block (Bob join)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed join-group transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Orphan last block (Cancel Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed cancel-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Orphan last block (Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed group-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testGroupBanAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Bob to join
			ValidationResult result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Promote Bob to admin
			addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to ban Bob
			int timeToLive = 0;
			result = groupBan(repository, alice, groupId, bob.getAddress(), timeToLive);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Cancel Bob's ban
			result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Orphan last block (Bob join)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed join-group transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Orphan last block (Cancel Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed cancel-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Orphan last block (Bob ban)
			BlockUtils.orphanLastBlock(repository);
			// Delete unconfirmed group-ban transaction
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice (owner) try to ban herself!
			result = groupBan(repository, alice, groupId, alice.getAddress(), timeToLive);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to ban Alice (owner)
			result = groupBan(repository, bob, groupId, alice.getAddress(), timeToLive);
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	private Integer createGroup(Repository repository, PrivateKeyAccount owner, String groupName, boolean isOpen) throws DataException {
		String description = groupName + " (description)";

		ApprovalThreshold approvalThreshold = ApprovalThreshold.ONE;
		int minimumBlockDelay = 10;
		int maximumBlockDelay = 1440;

		CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(owner), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
		TransactionUtils.signAndMint(repository, transactionData, owner);

		return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
	}

	private ValidationResult joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, joiner);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private ValidationResult groupKick(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		GroupKickTransactionData transactionData = new GroupKickTransactionData(TestTransaction.generateBase(admin), groupId, member, "testing");
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private ValidationResult groupBan(Repository repository, PrivateKeyAccount admin, int groupId, String member, int timeToLive) throws DataException {
		GroupBanTransactionData transactionData = new GroupBanTransactionData(TestTransaction.generateBase(admin), groupId, member, "testing", timeToLive);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private ValidationResult cancelGroupBan(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		CancelGroupBanTransactionData transactionData = new CancelGroupBanTransactionData(TestTransaction.generateBase(admin), groupId, member);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private void addGroupAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String member) throws DataException {
		AddGroupAdminTransactionData transactionData = new AddGroupAdminTransactionData(TestTransaction.generateBase(owner), groupId, member);
		TransactionUtils.signAndMint(repository, transactionData, owner);
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

	private boolean isAdmin(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().adminExists(groupId, address);
	}

}
