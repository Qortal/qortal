package org.qortal.test.group;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.data.group.GroupAdminData;
import org.qortal.data.transaction.*;
import org.qortal.group.Group;
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

import java.util.List;

import static org.junit.Assert.*;

/**
 * Dev group admin tests
 *
 * The dev group (ID 1) is owned by the null account with public key 00000000000000000000000000000001
 * To regain access to otherwise blocked owner-based rules, it has different validation logic
 * which applies to groups with this same null owner.
 *
 * The main difference is that approval is required for certain transaction types relating to
 * null-owned groups. This allows existing admins to approve updates to the group (using group's
 * approval threshold) instead of these actions being performed by the owner.
 *
 * Since these apply to all null-owned groups, this allows anyone to update their group to
 * the null owner if they want to take advantage of this decentralized approval system.
 *
 * Currently, the affected transaction types are:
 * - AddGroupAdminTransaction
 * - RemoveGroupAdminTransaction
 *
 * This same approach could ultimately be applied to other group transactions too.
 */
public class DevGroupAdminTests extends Common {

	public static final int NULL_GROUP_MEMBERSHIP_HEIGHT = BlockChain.getInstance().getNullGroupMembershipHeight();
	private static final int DEV_GROUP_ID = 1;

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

	@Test
	public void testGroupKickMember() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			ValidationResult result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Alice to invite Bob, as it's a closed group
			groupInvite(repository, alice, groupId, bob.getAddress(), 3600);

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should not be OK, cannot kick member out of null owned group
			assertNotSame(ValidationResult.OK, result);

			// Confirm Bob remains a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testGroupKickAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Alice to invite Bob, as it's a closed group
			groupInvite(repository, alice, groupId, bob.getAddress(), 3600);

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Promote Bob to admin
			TransactionData addGroupAdminTransactionData = addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm transaction needs approval, and hasn't been approved
			Transaction.ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.PENDING, approvalStatus);

			// Have Alice approve Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, ALICE, addGroupAdminTransactionData.getSignature(), true);

			// Mint a block so that the transaction becomes approved
			BlockUtils.mintBlock(repository);

			// Confirm transaction is approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.APPROVED, approvalStatus);

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			ValidationResult result = groupKick(repository, alice, groupId, bob.getAddress());
			// Shouldn't be allowed
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, result);

			// Confirm Bob is still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Confirm Bob still an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer an admin (ADD_GROUP_ADMIN no longer approved)
			assertFalse(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice try to kick herself!
			result = groupKick(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to kick Alice
			result = groupKick(repository, bob, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testGroupBanMember() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to cancel non-existent Bob ban
			ValidationResult result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// Should not be OK, cannot ban someone from a null owned group
			assertNotSame(ValidationResult.OK, result);

			// Bob attempts to join
			result = joinGroup(repository, bob, groupId);
			// Should be OK, but won't actually get him in the group
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Alice to invite Bob, as it's a closed group
			groupInvite(repository, alice, groupId, bob.getAddress(), 3600);

			// Bob to join
			result = joinGroup(repository, bob, groupId);
			// Should not be OK, bob should already be a member, he joined before the invite and
			// the invite served as an approval
			assertEquals(ValidationResult.ALREADY_GROUP_MEMBER, result);

			// Confirm Bob now a member, now that he got an invite
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// Should not be OK, because you can ban a member of a null owned group
			assertNotSame(ValidationResult.OK, result);

			// Confirm Bob is still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK, because he is already a member
			assertNotSame(ValidationResult.OK, result);

			// Cancel Bob's ban
			result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should not be OK, because there was no ban to begin with
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testGroupBanAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Alice to invite Bob, as it's a closed group
			groupInvite(repository, alice, groupId, bob.getAddress(), 3600);

			// Bob to join
			ValidationResult result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Promote Bob to admin
			TransactionData addGroupAdminTransactionData = addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm transaction needs approval, and hasn't been approved
			Transaction.ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.PENDING, approvalStatus);

			// Have Alice approve Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, ALICE, addGroupAdminTransactionData.getSignature(), true);

			// Mint a block so that the transaction becomes approved
			BlockUtils.mintBlock(repository);

			// Confirm transaction is approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.APPROVED, approvalStatus);

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// .. but we can't, because Bob is an admin and the group has no owner
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, result);

			// Confirm Bob still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// ... and still an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice try to ban herself!
			result = groupBan(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to ban Alice
			result = groupBan(repository, bob, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testAddAdmin2of3() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// establish accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, DILBERT);

			// assert admin statuses
			assertEquals(2, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, Group.NULL_OWNER_ADDRESS, DEV_GROUP_ID));
			assertTrue(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// alice invites bob
			ValidationResult result = groupInvite(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600);
			assertSame(ValidationResult.OK, result);

			// bob joins
			joinGroup(repository, bob, DEV_GROUP_ID);

			// confirm Bob is a member now, but still not an admin
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob creates transaction to add himself as an admin
			TransactionData addGroupAdminTransactionData1 = addGroupAdmin(repository, bob, DEV_GROUP_ID, bob.getAddress());

			// bob creates add admin transaction for himself, alice signs which is 50% approval while 40% is needed
			signForGroupApproval(repository, addGroupAdminTransactionData1, List.of(alice));

			// assert 3 admins in group and bob is an admin now
			assertEquals(3, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue() );
			assertTrue(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob invites chloe
			result = groupInvite(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			assertSame(ValidationResult.OK, result);

			// chloe joins
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// confirm Chloe is a member now, but still not an admin
			assertTrue(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));

			// chloe creates transaction to add herself as an admin
			TransactionData addChloeAsGroupAdmin = addGroupAdmin(repository, chloe, DEV_GROUP_ID, chloe.getAddress());

			// no one has signed, so it should be pending
			Transaction.ApprovalStatus addChloeAsGroupAdminStatus1 = GroupUtils.getApprovalStatus(repository, addChloeAsGroupAdmin.getSignature());
			assertEquals( Transaction.ApprovalStatus.PENDING, addChloeAsGroupAdminStatus1);

			// signer 1
			Transaction.ApprovalStatus addChloeAsGroupAdminStatus2 = signForGroupApproval(repository, addChloeAsGroupAdmin, List.of(alice));

			// 1 out of 3 has signed, so it should be pending, because it is less than 40%
			assertEquals( Transaction.ApprovalStatus.PENDING, addChloeAsGroupAdminStatus2);

			// signer 2
			Transaction.ApprovalStatus addChloeAsGroupAdminStatus3 = signForGroupApproval(repository, addChloeAsGroupAdmin, List.of(bob));

			// 2 out of 3 has signed, so it should be approved, because it is more than 40%
			assertEquals( Transaction.ApprovalStatus.APPROVED, addChloeAsGroupAdminStatus3);
		}
	}

	@Test
	public void testOrphanSecondInviteApproval() throws DataException {

		try (final Repository repository = RepositoryManager.getRepository()) {

			Block block = BlockUtils.mintBlocks(repository, NULL_GROUP_MEMBERSHIP_HEIGHT);
			assertEquals(NULL_GROUP_MEMBERSHIP_HEIGHT + 1, block.getBlockData().getHeight().intValue());

			// establish accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, DILBERT);

			// assert admin statuses
			assertEquals(2, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, Group.NULL_OWNER_ADDRESS, DEV_GROUP_ID));
			assertTrue(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// alice invites bob, alice signs which is 50% approval while 40% is needed
			TransactionData createInviteTransactionData = createGroupInviteForGroupApproval(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600);
			Transaction.ApprovalStatus bobsInviteStatus = signForGroupApproval(repository, createInviteTransactionData, List.of(alice));

			// assert approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, bobsInviteStatus);

			// bob joins
			joinGroup(repository, bob, DEV_GROUP_ID);

			// confirm Bob is a member now, but still not an admin
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob creates transaction to add himself as an admin
			TransactionData addGroupAdminTransactionData1 = addGroupAdmin(repository, bob, DEV_GROUP_ID, bob.getAddress());

			// bob creates add admin transaction for himself, alice signs which is 50% approval while 40% is needed
			signForGroupApproval(repository, addGroupAdminTransactionData1, List.of(alice));

			// assert 3 admins in group and bob is an admin now
			assertEquals(3, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob invites chloe, bob signs which is 33% approval while 40% is needed
			TransactionData chloeInvite1 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			Transaction.ApprovalStatus chloeInvite1Status = signForGroupApproval(repository, chloeInvite1, List.of(bob));

			// assert invite 1 pending
			assertEquals(Transaction.ApprovalStatus.PENDING, chloeInvite1Status);

			// bob invites chloe again, bob signs which is 33% approval while 40% is needed
			// since chloe is not a member yet, this invite is valie
			TransactionData chloeInvite2 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			Transaction.ApprovalStatus chloeInvite2Status = signForGroupApproval(repository, chloeInvite2, List.of(bob));

			// assert invite 2 is pending
			assertEquals(Transaction.ApprovalStatus.PENDING, chloeInvite2Status);

			// alice signs which is 66% approval while 40% is needed
			chloeInvite1Status = signForGroupApproval(repository, chloeInvite1, List.of(alice));

			// assert invite 1 approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeInvite1Status);

			// chloe joins
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// assert chloe is in the group
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// alice signs invite 2 which is 66% approval while 40% is needed
			chloeInvite2Status = signForGroupApproval(repository, chloeInvite2, List.of(alice));

			// assert invite 2 approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeInvite2Status);

			boolean exceptionThrown = false;

			try {
				// confront the bug by orphaning the block of the second approval, approval after the join
				// prior to the fix, this would raise an exception
				BlockUtils.orphanLastBlock(repository);
			} catch (DataException e) {
				exceptionThrown = true;
			}

			Assert.assertFalse(exceptionThrown);

			// assert chloe is still a member
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));
		}
	}

	@Test
	public void testNullOwnershipMembership()  throws DataException{
		try (final Repository repository = RepositoryManager.getRepository()) {

			Block block = BlockUtils.mintBlocks(repository, NULL_GROUP_MEMBERSHIP_HEIGHT);
			assertEquals(NULL_GROUP_MEMBERSHIP_HEIGHT + 1, block.getBlockData().getHeight().intValue());

			// establish accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, DILBERT);

			// assert admin statuses
			assertEquals(2, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, Group.NULL_OWNER_ADDRESS, DEV_GROUP_ID));
			assertTrue(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// alice invites bob, alice signs which is 50% approval while 40% is needed
			TransactionData createInviteTransactionData = createGroupInviteForGroupApproval(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600);
			Transaction.ApprovalStatus bobsInviteStatus = signForGroupApproval(repository, createInviteTransactionData, List.of(alice));

			// assert approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, bobsInviteStatus);

			// bob joins
			joinGroup(repository, bob, DEV_GROUP_ID);

			// confirm Bob is a member now, but still not an admin
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob creates transaction to add himself as an admin
			TransactionData addGroupAdminTransactionData1 = addGroupAdmin(repository, bob, DEV_GROUP_ID, bob.getAddress());

			// bob creates add admin transaction for himself, alice signs which is 50% approval while 40% is needed
			signForGroupApproval(repository, addGroupAdminTransactionData1, List.of(alice));

			// assert 3 admins in group and bob is an admin now
			assertEquals(3, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob invites chloe, bob signs which is 33% approval while 40% is needed
			TransactionData chloeInvite = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			Transaction.ApprovalStatus chloeInviteStatus = signForGroupApproval(repository, chloeInvite, List.of(bob));

			// assert pending
			assertEquals(Transaction.ApprovalStatus.PENDING, chloeInviteStatus);

			// alice signs which is 66% approval while 40% is needed
			chloeInviteStatus = signForGroupApproval(repository, chloeInvite, List.of(alice));

			// assert approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeInviteStatus);

			// chloe joins
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// assert chloe is in the group
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// alice kicks chloe, alice signs which is 33% approval while 40% is needed
			TransactionData chloeKick = createGroupKickForGroupApproval(repository, alice, DEV_GROUP_ID, chloe.getAddress(),"testing chloe kick");
			Transaction.ApprovalStatus chloeKickStatus = signForGroupApproval(repository, chloeKick, List.of(alice));

			// assert pending
			assertEquals(Transaction.ApprovalStatus.PENDING, chloeKickStatus);

			// assert chloe is still in the group
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob signs which is 66% approval while 40% is needed
			chloeKickStatus = signForGroupApproval(repository, chloeKick, List.of(bob));

			// assert approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeKickStatus);

			// assert chloe is not in the group
			assertFalse(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));

			// bob invites chloe, alice and bob signs which is 66% approval while 40% is needed
			TransactionData chloeInviteAgain = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			Transaction.ApprovalStatus chloeInviteAgainStatus = signForGroupApproval(repository, chloeInviteAgain, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeInviteAgainStatus);

			// chloe joins again
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// assert chloe is in the group
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// alice bans chloe, alice signs which is 33% approval while 40% is needed
			TransactionData chloeBan = createGroupBanForGroupApproval(repository, alice, DEV_GROUP_ID, chloe.getAddress(), "testing group ban", 3600);
			Transaction.ApprovalStatus chloeBanStatus1 = signForGroupApproval(repository, chloeBan, List.of(alice));

			// assert pending
			assertEquals(Transaction.ApprovalStatus.PENDING, chloeBanStatus1);

			// bob signs which 66% approval while 40% is needed
			Transaction.ApprovalStatus chloeBanStatus2 = signForGroupApproval(repository, chloeBan, List.of(bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeBanStatus2);

			// assert chloe is not in the group
			assertFalse(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));

			// bob invites chloe, alice and bob signs which is 66% approval while 40% is needed
			ValidationResult chloeInviteValidation = signAndImportGroupInvite(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);

			// assert banned status on invite attempt
			assertEquals(ValidationResult.BANNED_FROM_GROUP, chloeInviteValidation);

			// bob cancel ban on chloe, bob signs which is 33% approval while 40% is needed
			TransactionData chloeCancelBan = createCancelGroupBanForGroupApproval( repository, bob, DEV_GROUP_ID, chloe.getAddress());
			Transaction.ApprovalStatus chloeCancelBanStatus1 = signForGroupApproval(repository, chloeCancelBan, List.of(bob));

			// assert pending
			assertEquals(Transaction.ApprovalStatus.PENDING, chloeCancelBanStatus1);

			// alice signs which is 66% approval while 40% is needed
			Transaction.ApprovalStatus chloeCancelBanStatus2 = signForGroupApproval(repository, chloeCancelBan, List.of(alice));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeCancelBanStatus2);

			// bob invites chloe, alice and bob signs which is 66% approval while 40% is needed
			TransactionData chloeInvite4 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			Transaction.ApprovalStatus chloeInvite4Status = signForGroupApproval(repository, chloeInvite4, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeInvite4Status);

			// chloe joins again
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// assert chloe is in the group
			assertTrue(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));

			// bob invites dilbert, alice and bob signs which is 66% approval while 40% is needed
			TransactionData dilbertInvite1 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, dilbert.getAddress(), 3600);
			Transaction.ApprovalStatus dibertInviteStatus1 = signForGroupApproval(repository, dilbertInvite1, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, dibertInviteStatus1);

			// alice cancels dilbert's invite, alice signs which is 33% approval while 40% is needed
			TransactionData cancelDilbertInvite = createCancelInviteForGroupApproval(repository, alice, DEV_GROUP_ID, dilbert.getAddress());
			Transaction.ApprovalStatus cancelDilbertInviteStatus1 = signForGroupApproval(repository, cancelDilbertInvite, List.of(alice));

			// assert pending
			assertEquals(Transaction.ApprovalStatus.PENDING, cancelDilbertInviteStatus1);

			// dilbert joins before the group approves cancellation
			joinGroup(repository, dilbert, DEV_GROUP_ID);

			// assert dilbert is in the group
			assertTrue(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// alice kicks out dilbert, alice and bob sign which is 66% approval while 40% is needed
			TransactionData kickDilbert = createGroupKickForGroupApproval(repository, alice, DEV_GROUP_ID, dilbert.getAddress(), "he is sneaky");
			Transaction.ApprovalStatus kickDilbertStatus = signForGroupApproval(repository, kickDilbert, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, kickDilbertStatus);

			// assert dilbert is out of the group
			assertFalse(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// bob invites dilbert again, alice and bob signs which is 66% approval while 40% is needed
			TransactionData dilbertInvite2 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, dilbert.getAddress(), 3600);
			Transaction.ApprovalStatus dibertInviteStatus2 = signForGroupApproval(repository, dilbertInvite2, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, dibertInviteStatus2);

			// alice cancels dilbert's invite, alice and bob signs which is 66% approval while 40% is needed
			TransactionData cancelDilbertInvite2 = createCancelInviteForGroupApproval(repository, alice, DEV_GROUP_ID, dilbert.getAddress());
			Transaction.ApprovalStatus cancelDilbertInviteStatus2 = signForGroupApproval(repository, cancelDilbertInvite2, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, cancelDilbertInviteStatus2);

			// dilbert tries to join after the group approves cancellation
			joinGroup(repository, dilbert, DEV_GROUP_ID);

			// assert dilbert is not in the group
			assertFalse(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));
		}
	}

	@Test
	public void testGetAdmin()  throws DataException{
		try (final Repository repository = RepositoryManager.getRepository()) {

			// establish accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			GroupAdminData aliceAdminData = repository.getGroupRepository().getAdmin(DEV_GROUP_ID, alice.getAddress());

			assertNotNull(aliceAdminData);
			assertEquals( alice.getAddress(), aliceAdminData.getAdmin() );
			assertEquals( DEV_GROUP_ID, aliceAdminData.getGroupId());

			GroupAdminData bobAdminData = repository.getGroupRepository().getAdmin(DEV_GROUP_ID, bob.getAddress());

			assertNull(bobAdminData);
		}
	}

	private Transaction.ApprovalStatus signForGroupApproval(Repository repository, TransactionData data, List<PrivateKeyAccount> signers) throws DataException {

		for (PrivateKeyAccount signer : signers) {
			signTransactionDataForGroupApproval(repository, signer, data);
		}

		BlockUtils.mintBlocks(repository, 2);

		// return approval status
		return GroupUtils.getApprovalStatus(repository, data.getSignature());
	}

	private static void signTransactionDataForGroupApproval(Repository repository, PrivateKeyAccount signer, TransactionData transactionData) throws DataException {
		byte[] reference = signer.getLastReference();
		long timestamp = repository.getTransactionRepository().fromSignature(reference).getTimestamp() + 1;

		BaseTransactionData baseTransactionData
				= new BaseTransactionData(timestamp, Group.NO_GROUP, reference, signer.getPublicKey(), GroupUtils.fee, null);
		TransactionData groupApprovalTransactionData
				= new GroupApprovalTransactionData(baseTransactionData, transactionData.getSignature(), true);

		TransactionUtils.signAndImportValid(repository, groupApprovalTransactionData, signer);
	}

	private ValidationResult joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, joiner);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private ValidationResult groupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin), groupId, invitee, timeToLive, 0L);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData createGroupInviteForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin, groupId), groupId, invitee, timeToLive, 0L);
		TransactionUtils.signAndMint(repository, transactionData, admin);
		return transactionData;
	}

	private TransactionData createCancelInviteForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String inviteeToCancel) throws  DataException {
		CancelGroupInviteTransactionData transactionData = new CancelGroupInviteTransactionData(TestTransaction.generateBase(admin, groupId), groupId, inviteeToCancel);
		TransactionUtils.signAndMint(repository, transactionData, admin);
		return transactionData;
	}

	private ValidationResult signAndImportGroupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin, groupId), groupId, invitee, timeToLive, 0L);
		return TransactionUtils.signAndImport(repository, transactionData, admin);
	}

	private ValidationResult groupKick(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		GroupKickTransactionData transactionData = new GroupKickTransactionData(TestTransaction.generateBase(admin), groupId, member, "testing");
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData createGroupKickForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String kicked, String reason) throws DataException {
		GroupKickTransactionData transactionData = new GroupKickTransactionData(TestTransaction.generateBase(admin, groupId), groupId, kicked, reason);
		TransactionUtils.signAndMint(repository, transactionData, admin);

		return transactionData;
	}

	private ValidationResult groupBan(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		GroupBanTransactionData transactionData = new GroupBanTransactionData(TestTransaction.generateBase(admin), groupId, member, "testing", 0);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData createGroupBanForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String banned, String reason, int timeToLive) throws DataException {
		GroupBanTransactionData transactionData = new GroupBanTransactionData(TestTransaction.generateBase(admin, groupId), groupId, banned, reason, timeToLive);
		TransactionUtils.signAndMint(repository, transactionData, admin);

		return transactionData;
	}

	private ValidationResult cancelGroupBan(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		CancelGroupBanTransactionData transactionData = new CancelGroupBanTransactionData(TestTransaction.generateBase(admin), groupId, member);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData createCancelGroupBanForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String unbanned ) throws  DataException {
		CancelGroupBanTransactionData transactionData = new CancelGroupBanTransactionData( TestTransaction.generateBase(admin, groupId), groupId, unbanned);

		TransactionUtils.signAndMint(repository, transactionData, admin);

		return transactionData;
	}

	private TransactionData addGroupAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String member) throws DataException {
		AddGroupAdminTransactionData transactionData = new AddGroupAdminTransactionData(TestTransaction.generateBase(owner), groupId, member);
		transactionData.setTxGroupId(groupId);
		TransactionUtils.signAndMint(repository, transactionData, owner);
		return transactionData;
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

	private boolean isAdmin(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().adminExists(groupId, address);
	}

}
