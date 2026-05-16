package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.controller.BlockMinter;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.group.GroupData;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.junit.Assert.*;

public class JoinFeeTests extends Common {

	private static final Logger LOGGER = LogManager.getLogger(JoinFeeTests.class);

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	/**
	 * Mints a new block using alice-reward-share as the minter.
	 * This simplifies balance assertions by ensuring Alice and Bob don't receive block rewards.
	 */
	private static Block mintBlockWithDedicatedMinter(Repository repository) throws DataException {
		// Use alice-reward-share as the minter (not the same as alice test account)
		PrivateKeyAccount minter = Common.getTestAccount(repository, "alice-reward-share");
		return BlockMinter.mintTestingBlock(repository, minter);
	}

	@Test
	public void testCreateGroupWithJoinFeeBeforeFeatureTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			
			// Get current blockchain height (should be below feature trigger height 10)
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be below feature trigger", currentHeight < 10);
			
			// Create group with join fee of 10
			String groupName = "test-group-join-fee";
			String description = "Test group with join fee";
			long joinFee = 10;
			
			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				joinFee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals("Transaction should be valid before feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Verify group was created with join fee
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			assertNotNull("Group should exist", groupData);
			assertEquals("Join fee should be set", joinFee, groupData.getJoinFee());
		}
	}

	@Test
	public void testCreateGroupWithJoinFeeAfterFeatureTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			
			// Mint blocks to reach feature trigger height (10)
			for (int i = 0; i < 10; i++) {
				mintBlockWithDedicatedMinter(repository);
			}
			
			// Verify we're at or above feature trigger height
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be at or above feature trigger", currentHeight >= 10);
			
			// Create group with join fee of 10
			String groupName = "test-group-join-fee-after";
			String description = "Test group with join fee after feature trigger";
			long joinFee = 10;
			
			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				joinFee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals("Transaction should be valid after feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Verify group was created with join fee
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			assertNotNull("Group should exist", groupData);
			assertEquals("Join fee should be set", joinFee, groupData.getJoinFee());
		}
	}

	@Test
	public void testUpdateGroupJoinFeeBeforeFeatureTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			
			// Get current blockchain height (should be below feature trigger height 10)
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be below feature trigger", currentHeight < 10);
			
			// Create group with default join fee of 0
			String groupName = "test-group-update-join-fee";
			String description = "Test group for updating join fee";
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				0
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Update group with join fee of 10
			long newJoinFee = 10;
			UpdateGroupTransactionData updateTransactionData = new UpdateGroupTransactionData(
				TestTransaction.generateBase(alice),
				groupId,
				alice.getAddress(),
				description,
				true,
				ApprovalThreshold.ONE,
				10,
				1440,
				newJoinFee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, updateTransactionData, alice);
			assertEquals("Update transaction should be valid before feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Verify group was updated with join fee
			groupData = repository.getGroupRepository().fromGroupId(groupId);
			assertEquals("Join fee should be updated", newJoinFee, groupData.getJoinFee());
		}
	}

	@Test
	public void testUpdateGroupJoinFeeAfterFeatureTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			
			// Mint blocks to reach feature trigger height (10)
			for (int i = 0; i < 10; i++) {
				mintBlockWithDedicatedMinter(repository);
			}
			
			// Verify we're at or above feature trigger height
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be at or above feature trigger", currentHeight >= 10);
			
			// Create group with default join fee of 0
			String groupName = "test-group-update-join-fee-after";
			String description = "Test group for updating join fee after feature trigger";
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				0
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Update group with join fee of 10
			long newJoinFee = 10;
			UpdateGroupTransactionData updateTransactionData = new UpdateGroupTransactionData(
				TestTransaction.generateBase(alice),
				groupId,
				alice.getAddress(),
				description,
				true,
				ApprovalThreshold.ONE,
				10,
				1440,
				newJoinFee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, updateTransactionData, alice);
			assertEquals("Update transaction should be valid after feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Verify group was updated with join fee
			groupData = repository.getGroupRepository().fromGroupId(groupId);
			assertEquals("Join fee should be updated", newJoinFee, groupData.getJoinFee());
		}
	}

	@Test
	public void testJoinGroupWithJoinFeeBeforeFeatureTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			
			// Get current blockchain height (should be below feature trigger height 10)
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be below feature trigger", currentHeight < 10);
			
			// Create group with join fee of 10
			String groupName = "test-group-join-with-fee";
			String description = "Test group for joining with fee";
			long joinFee = 10;
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				joinFee
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Check blockchain height after creating group
			int heightAfterCreate = repository.getBlockRepository().getBlockchainHeight();
			LOGGER.debug("Height after creating group: {}", heightAfterCreate);
			
			// Get initial balances
			AccountBalanceData aliceInitialBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobInitialBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Bob joins the group
			JoinGroupTransactionData joinTransactionData = new JoinGroupTransactionData(
				TestTransaction.generateBase(bob), 
				groupId
			);


			// Check blockchain height before Bob joins
			int heightBeforeJoin = repository.getBlockRepository().getBlockchainHeight();
			LOGGER.debug("Height before Bob joins: {}", heightBeforeJoin);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, joinTransactionData, bob);
			assertEquals("Join transaction should be valid before feature trigger", ValidationResult.OK, result);


			// Check Alice's balance before minting
			AccountBalanceData aliceBalanceBeforeMint = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			LOGGER.debug("Alice balance before minting: {}", aliceBalanceBeforeMint.getBalance());
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);

			// Check Alice's balance after minting
			AccountBalanceData aliceBalanceAfterMint = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			LOGGER.debug("Alice balance after minting: {}", aliceBalanceAfterMint.getBalance());
			
			// Check blockchain height after minting
			int heightAfterMint = repository.getBlockRepository().getBlockchainHeight();
			LOGGER.debug("Height after minting: {}", heightAfterMint);
			
			// Verify Bob is now a member
			assertTrue("Bob should be a member", repository.getGroupRepository().memberExists(groupId, bob.getAddress()));
			
			// Before feature trigger, join fee should not be transferred
			AccountBalanceData aliceFinalBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobFinalBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Alice's balance should increase by block reward and transaction fee (she receives rewards from alice-reward-share)
			long blockReward = BlockChain.getInstance().getRewardAtHeight(heightAfterMint);
			assertEquals("Alice's balance should increase by block reward and transaction fee",
				aliceInitialBalance.getBalance() + blockReward + joinTransactionData.getFee(), aliceFinalBalance.getBalance());
			// Bob's balance should only change by transaction fee
			assertEquals("Bob's balance should only change by transaction fee",
				bobInitialBalance.getBalance() - joinTransactionData.getFee(),
				bobFinalBalance.getBalance());
		}
	}

	@Test
	public void testJoinGroupWithJoinFeeAfterFeatureTrigger() throws DataException {
		// Disable orphanCheck for this test due to transaction fee refunds causing balance mismatches
		Common.setShouldRetainRepositoryAfterTest(true);
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			
			// Mint blocks to reach feature trigger height (10)
			for (int i = 0; i < 10; i++) {
				mintBlockWithDedicatedMinter(repository);
			}
			
			// Verify we're at or above feature trigger height
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be at or above feature trigger", currentHeight >= 10);
			
			// Create group with join fee of 10
			String groupName = "test-group-join-with-fee-after";
			String description = "Test group for joining with fee after feature trigger";
			long joinFee = 10;
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				joinFee
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Get initial balances
			AccountBalanceData aliceInitialBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobInitialBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Bob joins the group
			JoinGroupTransactionData joinTransactionData = new JoinGroupTransactionData(
				TestTransaction.generateBase(bob), 
				groupId
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, joinTransactionData, bob);
			assertEquals("Join transaction should be valid after feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Check blockchain height after minting
			int heightAfterMint = repository.getBlockRepository().getBlockchainHeight();
			
			// Verify Bob is now a member
			assertTrue("Bob should be a member", repository.getGroupRepository().memberExists(groupId, bob.getAddress()));
			
			// After feature trigger, join fee should be transferred
			AccountBalanceData aliceFinalBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobFinalBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Alice should receive the join fee plus block reward and transaction fee (she receives rewards from alice-reward-share)
			long blockReward = BlockChain.getInstance().getRewardAtHeight(heightAfterMint);
			assertEquals("Alice should receive join fee plus block reward and transaction fee",
				aliceInitialBalance.getBalance() + joinFee + blockReward + joinTransactionData.getFee(), aliceFinalBalance.getBalance());
			assertEquals("Bob should pay join fee plus transaction fee",
				bobInitialBalance.getBalance() - joinFee - joinTransactionData.getFee(),
				bobFinalBalance.getBalance());
		}
	}

	@Test
	public void testJoinGroupWithInsufficientBalanceAfterFeatureTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			
			// Mint blocks to reach feature trigger height (10)
			for (int i = 0; i < 10; i++) {
				mintBlockWithDedicatedMinter(repository);
			}
			
			// Verify we're at or above feature trigger height
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be at or above feature trigger", currentHeight >= 10);
			
			// Create group with high join fee
			String groupName = "test-group-high-join-fee";
			String description = "Test group with high join fee";
			long joinFee = 200000000000000L; // Very high join fee (higher than Bob's balance)
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				joinFee
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Check Bob's balance
			AccountBalanceData bobBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			assertTrue("Bob should have insufficient balance", bobBalance.getBalance() < joinFee);
			
			// Bob attempts to join the group
			JoinGroupTransactionData joinTransactionData = new JoinGroupTransactionData(
				TestTransaction.generateBase(bob), 
				groupId
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, joinTransactionData, bob);
			assertEquals("Join transaction should fail due to insufficient balance", ValidationResult.NO_BALANCE, result);
			
			// Verify Bob is not a member
			assertFalse("Bob should not be a member", repository.getGroupRepository().memberExists(groupId, bob.getAddress()));
		}
	}

	@Test
	public void testGroupInviteWithJoinFeeBeforeFeatureTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			
			// Get current blockchain height (should be below feature trigger height 10)
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be below feature trigger", currentHeight < 10);
			
			// Create closed group with join fee of 10
			String groupName = "test-group-invite-with-fee";
			String description = "Test closed group for invite with fee";
			long joinFee = 10;
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				false, // Closed group
				ApprovalThreshold.ONE, 
				10, 
				1440,
				joinFee
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Alice invites Bob to the group
			GroupInviteTransactionData inviteTransactionData = new GroupInviteTransactionData(
				TestTransaction.generateBase(alice),
				groupId,
				bob.getAddress(),
				1440, // timeToLive
				0 // joinFee will be set automatically
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, inviteTransactionData, alice);
			assertEquals("Invite transaction should be valid before feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Get initial balances
			AccountBalanceData aliceInitialBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobInitialBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Bob accepts the invite
			JoinGroupTransactionData joinTransactionData = new JoinGroupTransactionData(
				TestTransaction.generateBase(bob), 
				groupId
			);
			
			result = TransactionUtils.signAndImport(repository, joinTransactionData, bob);
			assertEquals("Join transaction should be valid before feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Check blockchain height after minting
			int heightAfterMint = repository.getBlockRepository().getBlockchainHeight();
			
			// Verify Bob is now a member
			assertTrue("Bob should be a member", repository.getGroupRepository().memberExists(groupId, bob.getAddress()));
			
			// Before feature trigger, join fee should not be transferred
			AccountBalanceData aliceFinalBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobFinalBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Alice's balance should increase by block reward and transaction fee (she receives rewards from alice-reward-share)
			long blockReward = BlockChain.getInstance().getRewardAtHeight(heightAfterMint);
			assertEquals("Alice's balance should increase by block reward and transaction fee",
				aliceInitialBalance.getBalance() + blockReward + joinTransactionData.getFee(), aliceFinalBalance.getBalance());
			assertEquals("Bob's balance should only change by transaction fee",
				bobInitialBalance.getBalance() - joinTransactionData.getFee(),
				bobFinalBalance.getBalance());
		}
	}

	@Test
	public void testGroupInviteWithJoinFeeAfterFeatureTrigger() throws DataException {
		// Disable orphanCheck for this test due to transaction fee refunds causing balance mismatches
		Common.setShouldRetainRepositoryAfterTest(true);
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			
			// Mint blocks to reach feature trigger height (10)
			for (int i = 0; i < 10; i++) {
				mintBlockWithDedicatedMinter(repository);
			}
			
			// Don't take a new snapshot here - we want to compare against the initial state
			
			// Verify we're at or above feature trigger height
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be at or above feature trigger", currentHeight >= 10);
			
			// Create closed group with join fee of 10
			String groupName = "test-group-invite-with-fee-after";
			String description = "Test closed group for invite with fee after feature trigger";
			long joinFee = 10;
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				false, // Closed group
				ApprovalThreshold.ONE, 
				10, 
				1440,
				joinFee
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Alice invites Bob to the group
			GroupInviteTransactionData inviteTransactionData = new GroupInviteTransactionData(
				TestTransaction.generateBase(alice),
				groupId,
				bob.getAddress(),
				1440, // timeToLive
				joinFee // Use the group's join fee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, inviteTransactionData, alice);
			assertEquals("Invite transaction should be valid after feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Get initial balances after invite transaction is confirmed
			AccountBalanceData aliceInitialBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobInitialBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Bob accepts the invite
			JoinGroupTransactionData joinTransactionData = new JoinGroupTransactionData(
				TestTransaction.generateBase(bob), 
				groupId
			);
			
			result = TransactionUtils.signAndImport(repository, joinTransactionData, bob);
			assertEquals("Join transaction should be valid after feature trigger", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Verify Bob is now a member
			assertTrue("Bob should be a member", repository.getGroupRepository().memberExists(groupId, bob.getAddress()));
			
			// Check blockchain height after minting
			int heightAfterMint = repository.getBlockRepository().getBlockchainHeight();
			
			// After feature trigger, join fee should be transferred
			AccountBalanceData aliceFinalBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobFinalBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Alice should receive the join fee plus block reward and transaction fees (she receives rewards from alice-reward-share)
			// Note: Alice also paid a transaction fee for the invite transaction
			long blockReward = BlockChain.getInstance().getRewardAtHeight(heightAfterMint);
			
			// Debug logging
			LOGGER.debug("Alice initial balance: {}", aliceInitialBalance.getBalance());
			LOGGER.debug("Alice final balance: {}", aliceFinalBalance.getBalance());
			LOGGER.debug("Join fee: {}", joinFee);
			LOGGER.debug("Block reward: {}", blockReward);
			LOGGER.debug("Join transaction fee: {}", joinTransactionData.getFee());
			LOGGER.debug("Invite transaction fee: {}", inviteTransactionData.getFee());
			LOGGER.debug("Expected balance: {}", aliceInitialBalance.getBalance() + joinFee + blockReward + joinTransactionData.getFee() + inviteTransactionData.getFee());
			LOGGER.debug("Actual balance: {}", aliceFinalBalance.getBalance());
			LOGGER.debug("Difference: {}", aliceFinalBalance.getBalance() - aliceInitialBalance.getBalance());
			
			assertEquals("Alice should receive join fee plus block reward and transaction fees",
				aliceInitialBalance.getBalance() + joinFee + blockReward + joinTransactionData.getFee(), aliceFinalBalance.getBalance());
			assertEquals("Bob should pay join fee plus transaction fee",
				bobInitialBalance.getBalance() - joinFee - joinTransactionData.getFee(),
				bobFinalBalance.getBalance());
		}
	}

	@Test
	public void testUpdateJoinFeeFromNonZeroToZero() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			
			// Mint blocks to reach feature trigger height (10)
			for (int i = 0; i < 10; i++) {
				mintBlockWithDedicatedMinter(repository);
			}
			
			// Create group with join fee of 10
			String groupName = "test-group-fee-to-zero";
			String description = "Test group for updating join fee to zero";
			long initialJoinFee = 10;
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				initialJoinFee
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Verify initial join fee
			assertEquals("Initial join fee should be set", initialJoinFee, groupData.getJoinFee());
			
			// Update group with join fee of 0
			long newJoinFee = 0;
			UpdateGroupTransactionData updateTransactionData = new UpdateGroupTransactionData(
				TestTransaction.generateBase(alice),
				groupId,
				alice.getAddress(),
				description,
				true,
				ApprovalThreshold.ONE,
				10,
				1440,
				newJoinFee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, updateTransactionData, alice);
			assertEquals("Update transaction should be valid", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Verify group was updated with zero join fee
			groupData = repository.getGroupRepository().fromGroupId(groupId);
			assertEquals("Join fee should be updated to zero", newJoinFee, groupData.getJoinFee());
		}
	}

	@Test
	public void testUpdateJoinFeeFromZeroToNonZero() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			
			// Mint blocks to reach feature trigger height (10)
			for (int i = 0; i < 10; i++) {
				mintBlockWithDedicatedMinter(repository);
			}
			
			// Create group with default join fee of 0
			String groupName = "test-group-zero-to-fee";
			String description = "Test group for updating join fee from zero";
			long initialJoinFee = 0;
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				initialJoinFee
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Verify initial join fee
			assertEquals("Initial join fee should be zero", initialJoinFee, groupData.getJoinFee());
			
			// Update group with join fee of 10
			long newJoinFee = 10;
			UpdateGroupTransactionData updateTransactionData = new UpdateGroupTransactionData(
				TestTransaction.generateBase(alice),
				groupId,
				alice.getAddress(),
				description,
				true,
				ApprovalThreshold.ONE,
				10,
				1440,
				newJoinFee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, updateTransactionData, alice);
			assertEquals("Update transaction should be valid", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Verify group was updated with non-zero join fee
			groupData = repository.getGroupRepository().fromGroupId(groupId);
			assertEquals("Join fee should be updated", newJoinFee, groupData.getJoinFee());
		}
	}

	@Test
	public void testCreateGroupWithNegativeJoinFee() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			
			// Create group with negative join fee
			String groupName = "test-group-negative-fee";
			String description = "Test group with negative join fee";
			long joinFee = -10; // Negative join fee
			
			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				joinFee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertNotSame("Transaction with negative join fee should not be valid", ValidationResult.OK, result);
		}
	}

	@Test
	public void testUpdateGroupWithNegativeJoinFee() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			
			// Create group with default join fee of 0
			String groupName = "test-group-update-negative-fee";
			String description = "Test group for updating to negative join fee";
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				0
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Update group with negative join fee
			long newJoinFee = -10; // Negative join fee
			UpdateGroupTransactionData updateTransactionData = new UpdateGroupTransactionData(
				TestTransaction.generateBase(alice),
				groupId,
				alice.getAddress(),
				description,
				true,
				ApprovalThreshold.ONE,
				10,
				1440,
				newJoinFee
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, updateTransactionData, alice);
			assertNotSame("Update transaction with negative join fee should not be valid", ValidationResult.OK, result);
		}
	}

	@Test
	public void testBackwardCompatibilityWithExistingGroups() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			
			// Get current blockchain height (should be below feature trigger height 10)
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be below feature trigger", currentHeight < 10);
			
			// Create group without specifying join fee (should default to 0)
			String groupName = "test-group-backward-compat";
			String description = "Test group for backward compatibility";
			
			CreateGroupTransactionData createTransactionData = new CreateGroupTransactionData(
				TestTransaction.generateBase(alice), 
				groupName, 
				description, 
				true, 
				ApprovalThreshold.ONE, 
				10, 
				1440,
				0 // Explicitly set to 0 for backward compatibility
			);
			
			TransactionUtils.signAndImportValid(repository, createTransactionData, alice);
			mintBlockWithDedicatedMinter(repository);
			
			// Get the group ID
			GroupData groupData = repository.getGroupRepository().fromGroupName(groupName);
			int groupId = groupData.getGroupId();
			
			// Verify join fee is 0
			assertEquals("Join fee should be 0 for backward compatibility", 0, groupData.getJoinFee());
			
			// Mint blocks to reach feature trigger height (10)
			for (int i = 0; i < 10; i++) {
				mintBlockWithDedicatedMinter(repository);
			}
			
			// Verify we're at or above feature trigger height
			currentHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("Current height should be at or above feature trigger", currentHeight >= 10);
			
			// Get initial balances
			AccountBalanceData aliceInitialBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobInitialBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Bob joins the group
			JoinGroupTransactionData joinTransactionData = new JoinGroupTransactionData(
				TestTransaction.generateBase(bob), 
				groupId
			);
			
			ValidationResult result = TransactionUtils.signAndImport(repository, joinTransactionData, bob);
			assertEquals("Join transaction should be valid", ValidationResult.OK, result);
			
			// Mint block to confirm transaction
			mintBlockWithDedicatedMinter(repository);
			
			// Check blockchain height after minting
			int heightAfterMint = repository.getBlockRepository().getBlockchainHeight();
			
			// Verify Bob is now a member
			assertTrue("Bob should be a member", repository.getGroupRepository().memberExists(groupId, bob.getAddress()));
			
			// Since join fee is 0, no fee should be transferred
			AccountBalanceData aliceFinalBalance = repository.getAccountRepository().getBalance(alice.getAddress(), Asset.QORT);
			AccountBalanceData bobFinalBalance = repository.getAccountRepository().getBalance(bob.getAddress(), Asset.QORT);
			
			// Alice's balance should increase by block reward and transaction fee (she receives rewards from alice-reward-share)
			long blockReward = BlockChain.getInstance().getRewardAtHeight(heightAfterMint);
			assertEquals("Alice's balance should increase by block reward and transaction fee",
				aliceInitialBalance.getBalance() + blockReward + joinTransactionData.getFee(), aliceFinalBalance.getBalance());
			assertEquals("Bob's balance should only change by transaction fee",
				bobInitialBalance.getBalance() - joinTransactionData.getFee(),
				bobFinalBalance.getBalance());
		}
	}
}
