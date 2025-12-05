package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.LeaveGroupTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.api.resource.GroupsResource;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction.ValidationResult;

import org.qortal.block.BlockChain;
import org.qortal.data.group.GroupInviteData;
import org.qortal.data.group.GroupJoinRequestData;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.apache.commons.lang3.reflect.FieldUtils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(alice), duplicateGroupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
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
	public void testInviteFirstValidBeforeExpiryAddsMember() throws DataException {
		final String tn = "testInviteFirstValidBeforeExpiryAddsMember";
		try (final Repository repository = RepositoryManager.getRepository()) {
			log(tn, "START");
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "invite-first-valid", false);

			int ttlSeconds = 2;
			GroupInviteTransactionData inviteTx = buildInviteWithTtl(alice, groupId, bob.getAddress(), ttlSeconds);
			TransactionUtils.signAndMint(repository, inviteTx, alice);

			long joinTimestamp = inviteTx.getTimestamp() + 500; // before expiry
			log(tn, "Join timestamp " + joinTimestamp + " before expiry " + (inviteTx.getTimestamp() + ttlSeconds * 1000L));
			JoinGroupTransactionData joinTx = buildJoinWithTimestamp(bob, groupId, joinTimestamp);
			TransactionUtils.signAndMint(repository, joinTx, bob);

			log(tn, "Membership? " + isMember(repository, bob.getAddress(), groupId));
			assertTrue("Invite-first before expiry should add member", isMember(repository, bob.getAddress(), groupId));
			log(tn, "Invite should be consumed -> " + getInvite(repository, groupId, bob.getAddress()));
			assertNull("Invite should be consumed", getInvite(repository, groupId, bob.getAddress()));
			log(tn, "Join request should be absent -> " + getJoinRequest(repository, groupId, bob.getAddress()));
			assertNull("Join request should not persist", getJoinRequest(repository, groupId, bob.getAddress()));
			log(tn, "PASS");
		}
	}

	@Test
	public void testInviteFirstExpiredCreatesRequest() throws DataException {
		final String tn = "testInviteFirstExpiredCreatesRequest";
		try (final Repository repository = RepositoryManager.getRepository()) {
			log(tn, "START");
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "invite-first-expired", false);

			int ttlSeconds = 1;
			GroupInviteTransactionData inviteTx = buildInviteWithTtl(alice, groupId, bob.getAddress(), ttlSeconds);
			TransactionUtils.signAndMint(repository, inviteTx, alice);

			long expiredJoinTimestamp = inviteTx.getTimestamp() + (ttlSeconds * 1000L) + 1;
			log(tn, "Join timestamp " + expiredJoinTimestamp + " after expiry " + (inviteTx.getTimestamp() + ttlSeconds * 1000L));
			JoinGroupTransactionData joinTx = buildJoinWithTimestamp(bob, groupId, expiredJoinTimestamp);
			TransactionUtils.signAndMint(repository, joinTx, bob);

			log(tn, "Membership? " + isMember(repository, bob.getAddress(), groupId));
			assertFalse("Expired invite should not add member", isMember(repository, bob.getAddress(), groupId));
			log(tn, "Join request stored? " + (getJoinRequest(repository, groupId, bob.getAddress()) != null));
			assertNotNull("Join request should be stored when invite expired", getJoinRequest(repository, groupId, bob.getAddress()));
			log(tn, "Expired invite retained? " + (getInvite(repository, groupId, bob.getAddress()) != null));
			assertNotNull("Expired invite should remain stored", getInvite(repository, groupId, bob.getAddress()));
			log(tn, "PASS");
		}
	}

	@Test
	public void testInviteFirstBackdatedJoinWithinExpiry() throws DataException {
		final String tn = "testInviteFirstBackdatedJoinWithinExpiry";
		try (final Repository repository = RepositoryManager.getRepository()) {
			log(tn, "START");
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "invite-first-backdated", false);

			int ttlSeconds = 2;
			GroupInviteTransactionData inviteTx = buildInviteWithTtl(alice, groupId, bob.getAddress(), ttlSeconds);
			TransactionUtils.signAndMint(repository, inviteTx, alice);

			// Backdate join within expiry window even if block time is later
			long backdatedJoinTimestamp = inviteTx.getTimestamp() + (ttlSeconds * 1000L) - 500;
			log(tn, "Join timestamp " + backdatedJoinTimestamp + " relative to expiry " + (inviteTx.getTimestamp() + ttlSeconds * 1000L));
			JoinGroupTransactionData joinTx = buildJoinWithTimestamp(bob, groupId, backdatedJoinTimestamp);
			TransactionUtils.signAndMint(repository, joinTx, bob);

			log(tn, "Membership? " + isMember(repository, bob.getAddress(), groupId));
			assertTrue("Backdated join within expiry should add member", isMember(repository, bob.getAddress(), groupId));
			log(tn, "PASS");
		}
	}

	@Test
	public void testPrePostTriggerActivation() throws DataException {
		final String tn = "testPrePostTriggerActivation";
		try (final Repository repository = RepositoryManager.getRepository()) {
			log(tn, "START");
			Map<String, Long> originalTriggers = copyFeatureTriggers();
			try {
				Map<String, Long> highTrigger = new HashMap<>(originalTriggers);
				highTrigger.put(BlockChain.FeatureTrigger.groupInviteExpiryHeight.name(), Long.valueOf(Integer.MAX_VALUE));
				FieldUtils.writeField(BlockChain.getInstance(), "featureTriggers", highTrigger, true);

				PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
				PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

				int groupId = createGroup(repository, alice, "pre-trigger-allow", false);

				GroupInviteTransactionData inviteTx = buildInviteWithTtl(alice, groupId, bob.getAddress(), 1);
				TransactionUtils.signAndMint(repository, inviteTx, alice);

				long expiredJoinTimestamp = inviteTx.getTimestamp() + 2_000L;
				log(tn, "Pre-trigger join timestamp " + expiredJoinTimestamp + " relative to expiry " + (inviteTx.getTimestamp() + 1_000L));
				JoinGroupTransactionData joinTx = buildJoinWithTimestamp(bob, groupId, expiredJoinTimestamp);
				TransactionUtils.signAndMint(repository, joinTx, bob);

				log(tn, "Pre-trigger membership? " + isMember(repository, bob.getAddress(), groupId));
				assertTrue("Pre-trigger expired invite still adds member", isMember(repository, bob.getAddress(), groupId));
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} finally {
				try {
					FieldUtils.writeField(BlockChain.getInstance(), "featureTriggers", originalTriggers, true);
				} catch (IllegalAccessException e) {
					throw new RuntimeException(e);
				}
			}

			// Post-trigger should ignore expired invite (uses restored trigger)
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "post-trigger-ignore", false);

			GroupInviteTransactionData inviteTx = buildInviteWithTtl(alice, groupId, bob.getAddress(), 1);
			TransactionUtils.signAndMint(repository, inviteTx, alice);

			long expiredJoinTimestamp = inviteTx.getTimestamp() + 2_000L;
			log(tn, "Post-trigger join timestamp " + expiredJoinTimestamp + " relative to expiry " + (inviteTx.getTimestamp() + 1_000L));
			JoinGroupTransactionData joinTx = buildJoinWithTimestamp(bob, groupId, expiredJoinTimestamp);
			TransactionUtils.signAndMint(repository, joinTx, bob);

			log(tn, "Post-trigger membership? " + isMember(repository, bob.getAddress(), groupId));
			assertFalse("Post-trigger expired invite should not add member", isMember(repository, bob.getAddress(), groupId));
			assertNotNull("Join request should be stored post-trigger", getJoinRequest(repository, groupId, bob.getAddress()));
			assertNotNull("Expired invite should remain stored post-trigger", getInvite(repository, groupId, bob.getAddress()));
			log(tn, "Post-trigger request stored and invite retained");
			log(tn, "PASS");
		}
	}
@Test
	public void testApiFiltersExpiredInvites() throws DataException {
		final String tn = "testApiFiltersExpiredInvites";
		try (final Repository repository = RepositoryManager.getRepository()) {
			log(tn, "START");
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createGroup(repository, alice, "api-filter-group", false);

			// Expired invite (timestamp in the past with short TTL)
			long expiredTimestamp = System.currentTimeMillis() - 5_000L;
			log(tn, "Minting expired invite at " + expiredTimestamp + " for bob");
			GroupInviteTransactionData expiredInvite = buildInviteWithTimestamp(alice, groupId, bob.getAddress(), expiredTimestamp, 1);
			TransactionUtils.signAndMint(repository, expiredInvite, alice);

			// Non-expiring invite
			log(tn, "Minting TTL=0 invite for chloe");
			GroupInviteTransactionData openInvite = buildInviteWithTimestamp(alice, groupId, chloe.getAddress(), System.currentTimeMillis(), 0);
			TransactionUtils.signAndMint(repository, openInvite, alice);

			GroupsResource groupsResource = new GroupsResource();
			List<GroupInviteData> invitesByGroup = groupsResource.getInvitesByGroupId(groupId);

			log(tn, "Group invites returned: " + invitesByGroup.size());
			assertTrue(invitesByGroup.stream().anyMatch(inv -> inv.getInvitee().equals(chloe.getAddress())));
			assertFalse(invitesByGroup.stream().anyMatch(inv -> inv.getInvitee().equals(bob.getAddress())));

			List<GroupInviteData> invitesForChloe = groupsResource.getInvitesByInvitee(chloe.getAddress());
			log(tn, "Invites for Chloe: " + invitesForChloe.size());
			assertFalse(invitesForChloe.isEmpty());

			List<GroupInviteData> invitesForBob = groupsResource.getInvitesByInvitee(bob.getAddress());
			log(tn, "Invites for Bob (expired should be filtered): " + invitesForBob.size());
			assertTrue(invitesForBob.isEmpty());
			log(tn, "PASS");
		}
	}

	@Test
	public void testJoinFirstInviteLaterAutoAddsIgnoringTtl() throws DataException {
		final String tn = "testJoinFirstInviteLaterAutoAddsIgnoringTtl";
		try (final Repository repository = RepositoryManager.getRepository()) {
			log(tn, "START");
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "join-first-auto", false);

			// Bob requests to join (no invite yet)
			joinGroup(repository, bob, groupId);
			log(tn, "Stored join request? " + (getJoinRequest(repository, groupId, bob.getAddress()) != null));
			assertNotNull("Join request should be stored", getJoinRequest(repository, groupId, bob.getAddress()));
			assertFalse("Should not be member yet", isMember(repository, bob.getAddress(), groupId));

			// Alice sends an invite with very short TTL; TTL is ignored in join-first auto-approval
			int ttlSeconds = 1;
			GroupInviteTransactionData inviteTx = buildInviteWithTtl(alice, groupId, bob.getAddress(), ttlSeconds);
			TransactionUtils.signAndMint(repository, inviteTx, alice);

			log(tn, "Membership after invite? " + isMember(repository, bob.getAddress(), groupId));
			assertTrue("Invite after request should auto-add member", isMember(repository, bob.getAddress(), groupId));
			assertNull("Join request should be consumed", getJoinRequest(repository, groupId, bob.getAddress()));
			assertNull("Invite should be consumed", getInvite(repository, groupId, bob.getAddress()));
			log(tn, "PASS");
		}
	}

	@Test
	public void testJoinFirstInviteLaterWithBackdatedJoinStillAdds() throws DataException {
		final String tn = "testJoinFirstInviteLaterWithBackdatedJoinStillAdds";
		try (final Repository repository = RepositoryManager.getRepository()) {
			log(tn, "START");
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "join-first-backdated", false);

			long joinTimestamp = System.currentTimeMillis() - 10_000L; // backdated within expiry window
			JoinGroupTransactionData joinTx = buildJoinWithTimestamp(bob, groupId, joinTimestamp);
			TransactionUtils.signAndMint(repository, joinTx, bob);

			log(tn, "Stored join request? " + (getJoinRequest(repository, groupId, bob.getAddress()) != null));
			assertNotNull("Join request should be stored", getJoinRequest(repository, groupId, bob.getAddress()));
			assertFalse("Should not be member yet", isMember(repository, bob.getAddress(), groupId));

			// Invite later with short TTL; TTL ignored for join-first path
			GroupInviteTransactionData inviteTx = buildInviteWithTtl(alice, groupId, bob.getAddress(), 1);
			TransactionUtils.signAndMint(repository, inviteTx, alice);

			log(tn, "Membership after invite? " + isMember(repository, bob.getAddress(), groupId));
			assertTrue("Join-first backdated request should be approved by later invite", isMember(repository, bob.getAddress(), groupId));
			assertNull("Join request should be consumed", getJoinRequest(repository, groupId, bob.getAddress()));
			log(tn, "PASS");
		}
	}

	@Test
	public void testJoinFirstInviteLaterTtlZero() throws DataException {
		final String tn = "testJoinFirstInviteLaterTtlZero";
		try (final Repository repository = RepositoryManager.getRepository()) {
			log(tn, "START");
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "join-first-ttl-zero", false);

			joinGroup(repository, bob, groupId);
			log(tn, "Stored join request? " + (getJoinRequest(repository, groupId, bob.getAddress()) != null));
			assertNotNull(getJoinRequest(repository, groupId, bob.getAddress()));

			GroupInviteTransactionData inviteTx = buildInviteWithTtl(alice, groupId, bob.getAddress(), 0);
			TransactionUtils.signAndMint(repository, inviteTx, alice);

			log(tn, "Membership after TTL=0 invite? " + isMember(repository, bob.getAddress(), groupId));
			assertTrue(isMember(repository, bob.getAddress(), groupId));
			assertNull(getJoinRequest(repository, groupId, bob.getAddress()));
			assertNull(getInvite(repository, groupId, bob.getAddress()));
			log(tn, "PASS");
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

		CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(owner), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
		TransactionUtils.signAndMint(repository, transactionData, owner);

		return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
	}

	private void joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
		TransactionUtils.signAndMint(repository, transactionData, joiner);
	}

	private void groupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin), groupId, invitee, timeToLive);
		TransactionUtils.signAndMint(repository, transactionData, admin);
	}

	private GroupInviteTransactionData buildInviteWithTtl(PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin), groupId, invitee, timeToLive);
		return transactionData;
	}

	private void leaveGroup(Repository repository, PrivateKeyAccount leaver, int groupId) throws DataException {
		LeaveGroupTransactionData transactionData = new LeaveGroupTransactionData(TestTransaction.generateBase(leaver), groupId);
		TransactionUtils.signAndMint(repository, transactionData, leaver);
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

	private GroupInviteData getInvite(Repository repository, int groupId, String invitee) throws DataException {
		return repository.getGroupRepository().getInvite(groupId, invitee);
	}

	private GroupJoinRequestData getJoinRequest(Repository repository, int groupId, String address) throws DataException {
		return repository.getGroupRepository().getJoinRequest(groupId, address);
	}

	private JoinGroupTransactionData buildJoinWithTimestamp(PrivateKeyAccount joiner, int groupId, long timestamp) throws DataException {
		long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, joiner.getLastReference(), joiner.getPublicKey(), fee, null);
		return new JoinGroupTransactionData(baseTransactionData, groupId);
	}

	private GroupInviteTransactionData buildInviteWithTimestamp(PrivateKeyAccount admin, int groupId, String invitee, long timestamp, int timeToLive) throws DataException {
		long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, admin.getLastReference(), admin.getPublicKey(), fee, null);
		return new GroupInviteTransactionData(baseTransactionData, groupId, invitee, timeToLive);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Long> copyFeatureTriggers() {
		try {
			Map<String, Long> current = (Map<String, Long>) FieldUtils.readField(BlockChain.getInstance(), "featureTriggers", true);
			return new HashMap<>(current);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private void log(String testName, String message) {
		System.out.println("[" + testName + "] " + message);
	}

}
