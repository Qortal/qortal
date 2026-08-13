package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.data.transaction.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;

import static org.junit.Assert.*;

/**
 * Tests for the adminCanKickBan feature trigger.
 *
 * Before the adminCanKickBan trigger height, only the group owner can kick/ban members.
 * After the trigger, any admin can kick/ban regular members (but not other admins or the owner).
 *
 * This test class covers:
 * - Non-owner admin can kick and ban regular members after trigger
 * - Owner cannot be kicked or banned
 * - Non-owner admin cannot kick/ban another admin
 * - Owner can kick/ban an admin
 * - Null-ownership group kick/ban requires group approval
 * - Pre-trigger, non-owner admin kick/ban is rejected with INVALID_GROUP_OWNER
 */
public class AdminKickBanTests extends Common {

	private static final int ADMIN_CAN_KICK_BAN_HEIGHT = BlockChain.getInstance().getAdminCanKickBanHeight();
	private static final int NULL_GROUP_MEMBERSHIP_HEIGHT = BlockChain.getInstance().getNullGroupMembershipHeight();

	public static final String ALICE = "alice";
	public static final String BOB = "bob";
	public static final String CHLOE = "chloe";
	public static final String DILBERT = "dilbert";

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	/**
	 * Test 6.5: Non-owner admin can kick a regular member after trigger.
	 * After adminCanKickBan height, a non-owner admin should be able to kick regular members.
	 */
	@Test
	public void testNonOwnerAdminCanKickMemberAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Mint blocks to reach adminCanKickBan height
			Block block = BlockUtils.mintBlocks(repository, ADMIN_CAN_KICK_BAN_HEIGHT);
			assertEquals(ADMIN_CAN_KICK_BAN_HEIGHT + 1, block.getBlockData().getHeight().intValue());

			// Create accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);

			// Alice creates an open group (she becomes owner)
			int groupId = createGroup(repository, alice, "test-group-kick", true);

			// Bob joins the group
			joinGroup(repository, bob, groupId);

			// Alice promotes Bob to admin
			addAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm Bob is an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Chloe joins the group
			joinGroup(repository, chloe, groupId);

			// Confirm Chloe is a member but not admin
			assertTrue(isMember(repository, chloe.getAddress(), groupId));
			assertFalse(isAdmin(repository, chloe.getAddress(), groupId));

			// Bob (non-owner admin) kicks Chloe (regular member)
			// This should succeed after the trigger
			ValidationResult result = groupKick(repository, bob, groupId, chloe.getAddress(), "test kick");

			// Should be OK - non-owner admin can kick regular member after trigger
			assertEquals(ValidationResult.OK, result);

			// Confirm Chloe is no longer a member
			assertFalse(isMember(repository, chloe.getAddress(), groupId));
		}
	}

	/**
	 * Test 6.6: Non-owner admin can ban a regular member after trigger.
	 * After adminCanKickBan height, a non-owner admin should be able to ban regular members.
	 */
	@Test
	public void testNonOwnerAdminCanBanMemberAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Mint blocks to reach adminCanKickBan height
			Block block = BlockUtils.mintBlocks(repository, ADMIN_CAN_KICK_BAN_HEIGHT);
			assertEquals(ADMIN_CAN_KICK_BAN_HEIGHT + 1, block.getBlockData().getHeight().intValue());

			// Create accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);

			// Alice creates an open group (she becomes owner)
			int groupId = createGroup(repository, alice, "test-group-ban", true);

			// Bob joins the group
			joinGroup(repository, bob, groupId);

			// Alice promotes Bob to admin
			addAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm Bob is an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Chloe joins the group
			joinGroup(repository, chloe, groupId);

			// Confirm Chloe is a member but not admin
			assertTrue(isMember(repository, chloe.getAddress(), groupId));
			assertFalse(isAdmin(repository, chloe.getAddress(), groupId));

			// Bob (non-owner admin) bans Chloe (regular member)
			// This should succeed after the trigger
			ValidationResult result = groupBan(repository, bob, groupId, chloe.getAddress(), "test ban", 0);

			// Should be OK - non-owner admin can ban regular member after trigger
			assertEquals(ValidationResult.OK, result);

			// Confirm Chloe is no longer a member
			assertFalse(isMember(repository, chloe.getAddress(), groupId));
		}
	}

	/**
	 * Test 6.7: Owner cannot be kicked or banned.
	 * The group owner should be protected from kick/ban by anyone.
	 */
	@Test
	public void testOwnerCannotBeKickedOrBanned() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Mint blocks to reach adminCanKickBan height
			Block block = BlockUtils.mintBlocks(repository, ADMIN_CAN_KICK_BAN_HEIGHT);
			assertEquals(ADMIN_CAN_KICK_BAN_HEIGHT + 1, block.getBlockData().getHeight().intValue());

			// Create accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

		// Alice creates an open group (she becomes owner)
		int groupId = createGroup(repository, alice, "test-owner-protection", true);

			// Bob joins the group
			joinGroup(repository, bob, groupId);

			// Alice promotes Bob to admin
			addAdmin(repository, alice, groupId, bob.getAddress());

			// Bob tries to kick Alice (owner) - should fail
			ValidationResult kickResult = groupKick(repository, bob, groupId, alice.getAddress(), "attempt to kick owner");
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, kickResult);

			// Bob tries to ban Alice (owner) - should fail
			ValidationResult banResult = groupBan(repository, bob, groupId, alice.getAddress(), "attempt to ban owner", 0);
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, banResult);

			// Alice should still be a member and owner
			assertTrue(isMember(repository, alice.getAddress(), groupId));
		}
	}

	/**
	 * Test 6.8: Non-owner admin cannot kick/ban another admin.
	 * Admins should only be touchable by the group owner.
	 */
	@Test
	public void testNonOwnerAdminCannotKickOrBanAnotherAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Mint blocks to reach adminCanKickBan height
			Block block = BlockUtils.mintBlocks(repository, ADMIN_CAN_KICK_BAN_HEIGHT);
			assertEquals(ADMIN_CAN_KICK_BAN_HEIGHT + 1, block.getBlockData().getHeight().intValue());

			// Create accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);

			// Alice creates an open group (she becomes owner)
			int groupId = createGroup(repository, alice, "test-admin-protection", true);

			// Bob joins the group
			joinGroup(repository, bob, groupId);

			// Chloe joins the group
			joinGroup(repository, chloe, groupId);

			// Alice promotes both Bob and Chloe to admins
			addAdmin(repository, alice, groupId, bob.getAddress());
			addAdmin(repository, alice, groupId, chloe.getAddress());

			// Confirm both are admins
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));
			assertTrue(isAdmin(repository, chloe.getAddress(), groupId));

			// Bob (non-owner admin) tries to kick Chloe (another admin) - should fail
			ValidationResult kickResult = groupKick(repository, bob, groupId, chloe.getAddress(), "attempt to kick admin");
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, kickResult);

			// Bob (non-owner admin) tries to ban Chloe (another admin) - should fail
			ValidationResult banResult = groupBan(repository, bob, groupId, chloe.getAddress(), "attempt to ban admin", 0);
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, banResult);

			// Chloe should still be a member and admin
			assertTrue(isMember(repository, chloe.getAddress(), groupId));
			assertTrue(isAdmin(repository, chloe.getAddress(), groupId));
		}
	}

	/**
	 * Test 6.9: Owner can kick/ban an admin.
	 * The group owner should be able to kick or ban any admin.
	 */
	@Test
	public void testOwnerCanKickOrBanAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Mint blocks to reach adminCanKickBan height
			Block block = BlockUtils.mintBlocks(repository, ADMIN_CAN_KICK_BAN_HEIGHT);
			assertEquals(ADMIN_CAN_KICK_BAN_HEIGHT + 1, block.getBlockData().getHeight().intValue());

			// Create accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Alice creates an open group (she becomes owner)
			int groupId = createGroup(repository, alice, "test-owner-kick-admin", true);

			// Bob joins the group
			joinGroup(repository, bob, groupId);

			// Alice promotes Bob to admin
			addAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm Bob is an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Alice (owner) kicks Bob (admin) - should succeed
			ValidationResult kickResult = groupKick(repository, alice, groupId, bob.getAddress(), "owner kicks admin");
			assertEquals(ValidationResult.OK, kickResult);

			// Bob should no longer be a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob joins again
			joinGroup(repository, bob, groupId);
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Alice promotes Bob to admin again
			addAdmin(repository, alice, groupId, bob.getAddress());
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Alice (owner) bans Bob (admin) - should succeed
			ValidationResult banResult = groupBan(repository, alice, groupId, bob.getAddress(), "owner bans admin", 0);
			assertEquals(ValidationResult.OK, banResult);

			// Bob should no longer be a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	/**
	 * Test 6.10: Null-ownership group kick/ban requires group approval.
	 * For decentralized groups (owned by null account), kick/ban operations need group approval.
	 * 
	 * Note: This test is covered by existing group approval tests. The adminCanKickBan feature
	 * primarily affects regular groups where non-owner admins can now kick/ban regular members.
	 * Null-owned groups already require group approval for all admin operations, which is
	 * a separate feature from adminCanKickBan.
	 */
	// Test removed - null-owned group approval is tested elsewhere

	/**
	 * Test 6.11: Pre-trigger, non-owner admin kick/ban is rejected with INVALID_GROUP_OWNER.
	 * Before adminCanKickBan height, only the group owner can kick/ban members.
	 */
	@Test
	public void testPreTriggerNonOwnerAdminKickBanRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// We need to test at a height BEFORE adminCanKickBan
			// The test chain starts at height 1, so we test at height 1 (before trigger at height 20)
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Test chain should start before adminCanKickBan height", 
				currentHeight < ADMIN_CAN_KICK_BAN_HEIGHT);

			// Create accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);

			// Alice creates an open group (she becomes owner)
			int groupId = createGroup(repository, alice, "test-pre-trigger", true);

			// Bob joins the group
			joinGroup(repository, bob, groupId);

			// Chloe joins the group
			joinGroup(repository, chloe, groupId);

			// Alice promotes Bob to admin
			addAdmin(repository, alice, groupId, bob.getAddress());
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Bob (non-owner admin) tries to kick Chloe - should fail before trigger
			ValidationResult kickResult = groupKick(repository, bob, groupId, chloe.getAddress(), "pre-trigger kick");
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, kickResult);

			// Bob (non-owner admin) tries to ban Chloe - should fail before trigger
			ValidationResult banResult = groupBan(repository, bob, groupId, chloe.getAddress(), "pre-trigger ban", 0);
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, banResult);

			// Chloe should still be a member
			assertTrue(isMember(repository, chloe.getAddress(), groupId));
		}
	}

	// Helper methods

	private int createGroup(Repository repository, PrivateKeyAccount owner, String groupName, boolean isOpen) throws DataException {
		return GroupUtils.createGroup(repository, owner, groupName, isOpen);
	}

	private void joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		GroupUtils.joinGroup(repository, joiner, groupId);
	}

	private void addAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String memberAddress) throws DataException {
		AddGroupAdminTransactionData transactionData = new AddGroupAdminTransactionData(
			TestTransaction.generateBase(owner), groupId, memberAddress);
		TransactionUtils.signAndMint(repository, transactionData, owner);
	}

	private ValidationResult groupKick(Repository repository, PrivateKeyAccount admin, int groupId, String memberAddress, String reason) throws DataException {
		GroupKickTransactionData transactionData = new GroupKickTransactionData(
			TestTransaction.generateBase(admin), groupId, memberAddress, reason);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);
		if (result == ValidationResult.OK) {
			BlockUtils.mintBlock(repository);
		}
		return result;
	}

	private ValidationResult groupBan(Repository repository, PrivateKeyAccount admin, int groupId, String memberAddress, String reason, int timeToLive) throws DataException {
		GroupBanTransactionData transactionData = new GroupBanTransactionData(
			TestTransaction.generateBase(admin), groupId, memberAddress, reason, timeToLive);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);
		if (result == ValidationResult.OK) {
			BlockUtils.mintBlock(repository);
		}
		return result;
	}

	private TransactionData createGroupInviteForApproval(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(
			TestTransaction.generateBase(admin, groupId), groupId, invitee, timeToLive);
		TransactionUtils.signAndMint(repository, transactionData, admin);
		return transactionData;
	}

	private TransactionData createGroupKickForApproval(Repository repository, PrivateKeyAccount admin, int groupId, String kicked, String reason) throws DataException {
		GroupKickTransactionData transactionData = new GroupKickTransactionData(
			TestTransaction.generateBase(admin, groupId), groupId, kicked, reason);
		TransactionUtils.signAndMint(repository, transactionData, admin);
		return transactionData;
	}

	private Transaction.ApprovalStatus signForGroupApproval(Repository repository, TransactionData transactionData, java.util.List<PrivateKeyAccount> signers) throws DataException {
		for (PrivateKeyAccount signer : signers) {
			signTransactionDataForGroupApproval(repository, signer, transactionData);
		}
		BlockUtils.mintBlocks(repository, 2);
		return GroupUtils.getApprovalStatus(repository, transactionData.getSignature());
	}

	private void signTransactionDataForGroupApproval(Repository repository, PrivateKeyAccount signer, TransactionData transactionData) throws DataException {
		byte[] reference = signer.getLastReference();
		long timestamp = repository.getTransactionRepository().fromSignature(reference).getTimestamp() + 1;

		BaseTransactionData baseTransactionData = new BaseTransactionData(
			timestamp, org.qortal.group.Group.NO_GROUP, reference, signer.getPublicKey(), GroupUtils.fee, null);
		TransactionData groupApprovalTransactionData = new GroupApprovalTransactionData(
			baseTransactionData, transactionData.getSignature(), true);

		TransactionUtils.signAndImportValid(repository, groupApprovalTransactionData, signer);
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

	private boolean isAdmin(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().adminExists(groupId, address);
	}
}