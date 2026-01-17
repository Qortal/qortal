package org.qortal.test.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.resource.GroupsResource;
import org.qortal.data.group.GroupInviteData;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.repository.*;
import org.qortal.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.test.common.Common;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GroupInviteFilteringApiTests extends ApiCommon {
	private GroupsResource groupsResource;

	@Before
	public void buildResource() {
		this.groupsResource = (GroupsResource) ApiCommon.buildResource(GroupsResource.class);
	}

	@After
	public void restoreRepositoryFactory() throws DataException {
		// Ensure we restore the real factory even if a test swaps it out
		if (!(RepositoryManager.getRepositoryFactory() instanceof HSQLDBRepositoryFactory)) {
			Common.useDefaultSettings();
		}
	}

	@Test
	public void testInviteFilteringByChainTip() throws DataException {
		System.out.println("TEST START: testInviteFilteringByChainTip - expired invites filtered, TTL=0/unexpired retained.");
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createGroup(repository, alice, "api-filter-group", false);

			// Expired invite for Bob (TTL 1s, timestamp in the past)
			GroupInviteTransactionData expiredInvite = new GroupInviteTransactionData(TestTransaction.generateBase(alice), groupId, bob.getAddress(), 1);
			long expiredTimestamp = System.currentTimeMillis() - 60_000;
			expiredInvite.setTimestamp(expiredTimestamp);
			TransactionUtils.signAndMint(repository, expiredInvite, alice);

			// Non-expiring invite for Bob
			GroupInviteTransactionData nonExpiringInvite = new GroupInviteTransactionData(TestTransaction.generateBase(alice), groupId, bob.getAddress(), 0);
			TransactionUtils.signAndMint(repository, nonExpiringInvite, alice);

			// Unexpired invite for Chloe
			GroupInviteTransactionData activeInvite = new GroupInviteTransactionData(TestTransaction.generateBase(alice), groupId, chloe.getAddress(), 3_600);
			TransactionUtils.signAndMint(repository, activeInvite, alice);

			List<GroupInviteData> bobInvites = this.groupsResource.getInvitesByInvitee(bob.getAddress());
			assertEquals("expected Bob to see only non-expiring invite (expired filtered out)", 1, bobInvites.size());

			List<GroupInviteData> groupInvites = this.groupsResource.getInvitesByGroupId(groupId);
			assertEquals("expected group to show only non-expiring and unexpired invites", 2, groupInvites.size());
			System.out.println("TEST PASS: testInviteFilteringByChainTip - expected bobInvites size=1, actual=" + bobInvites.size() + "; expected groupInvites size=2, actual=" + groupInvites.size());
		}
	}

	@Test
	public void testInviteFilteringSkippedWhenNoChainTip() throws DataException {
		System.out.println("TEST START: testInviteFilteringSkippedWhenNoChainTip - filtering is skipped without a chain tip.");
		RepositoryFactory originalFactory = RepositoryManager.getRepositoryFactory();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "api-filter-no-tip", false);

			// Expired invite for Bob (would normally be filtered)
			GroupInviteTransactionData expiredInvite = new GroupInviteTransactionData(TestTransaction.generateBase(alice), groupId, bob.getAddress(), 1);
			long expiredTimestamp = System.currentTimeMillis() - 60_000;
			expiredInvite.setTimestamp(expiredTimestamp);
			TransactionUtils.signAndMint(repository, expiredInvite, alice);
		}

		try {
			RepositoryManager.setRepositoryFactory(new NullTipRepositoryFactory(originalFactory));

			List<GroupInviteData> bobInvites = this.groupsResource.getInvitesByInvitee(this.bobAddress);
			assertTrue("expected expired invite to remain when no chain tip", bobInvites.stream().anyMatch(invite -> invite.getInvitee().equals(this.bobAddress)));
			System.out.println("TEST PASS: testInviteFilteringSkippedWhenNoChainTip - expired invite present=" + bobInvites.stream().anyMatch(invite -> invite.getInvitee().equals(this.bobAddress)));
		} finally {
			RepositoryManager.setRepositoryFactory(originalFactory);
		}
	}

	private int createGroup(Repository repository, PrivateKeyAccount owner, String groupName, boolean isOpen) throws DataException {
		String description = groupName + " (description)";
		CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(owner), groupName, description, isOpen, org.qortal.group.Group.ApprovalThreshold.ONE, 10, 1440);
		TransactionUtils.signAndMint(repository, transactionData, owner);
		return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
	}

	private static class NullTipRepositoryFactory implements RepositoryFactory {
		private final RepositoryFactory delegate;

		private NullTipRepositoryFactory(RepositoryFactory delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean wasPristineAtOpen() {
			return this.delegate.wasPristineAtOpen();
		}

		@Override
		public RepositoryFactory reopen() throws DataException {
			return new NullTipRepositoryFactory(this.delegate.reopen());
		}

		@Override
		public Repository getRepository() throws DataException {
			return new NullTipRepository(this.delegate.getRepository());
		}

		@Override
		public Repository tryRepository() throws DataException {
			return new NullTipRepository(this.delegate.tryRepository());
		}

		@Override
		public void close() throws DataException {
			this.delegate.close();
		}

		@Override
		public boolean isDeadlockException(java.sql.SQLException e) {
			return this.delegate.isDeadlockException(e);
		}
	}

	private static class NullTipRepository implements Repository {
		private final Repository delegate;

		private NullTipRepository(Repository delegate) {
			this.delegate = delegate;
		}

		@Override
		public ATRepository getATRepository() {
			return delegate.getATRepository();
		}

		@Override
		public AccountRepository getAccountRepository() {
			return delegate.getAccountRepository();
		}

		@Override
		public ArbitraryRepository getArbitraryRepository() {
			return delegate.getArbitraryRepository();
		}

		@Override
		public AssetRepository getAssetRepository() {
			return delegate.getAssetRepository();
		}

		@Override
		public BlockRepository getBlockRepository() {
			return new NullTipBlockRepository(delegate.getBlockRepository());
		}

		@Override
		public BlockArchiveRepository getBlockArchiveRepository() {
			return delegate.getBlockArchiveRepository();
		}

		@Override
		public ChatRepository getChatRepository() {
			return delegate.getChatRepository();
		}

		@Override
		public CrossChainRepository getCrossChainRepository() {
			return delegate.getCrossChainRepository();
		}

		@Override
		public GroupRepository getGroupRepository() {
			return delegate.getGroupRepository();
		}

		@Override
		public MessageRepository getMessageRepository() {
			return delegate.getMessageRepository();
		}

		@Override
		public NameRepository getNameRepository() {
			return delegate.getNameRepository();
		}

		@Override
		public NetworkRepository getNetworkRepository() {
			return delegate.getNetworkRepository();
		}

		@Override
		public TransactionRepository getTransactionRepository() {
			return delegate.getTransactionRepository();
		}

		@Override
		public VotingRepository getVotingRepository() {
			return delegate.getVotingRepository();
		}

		@Override
		public void saveChanges() throws DataException {
			delegate.saveChanges();
		}

		@Override
		public void discardChanges() throws DataException {
			delegate.discardChanges();
		}

		@Override
		public void setSavepoint() throws DataException {
			delegate.setSavepoint();
		}

		@Override
		public void rollbackToSavepoint() throws DataException {
			delegate.rollbackToSavepoint();
		}

		@Override
		public void close() throws DataException {
			delegate.close();
		}

		@Override
		public void rebuild() throws DataException {
			delegate.rebuild();
		}

		@Override
		public boolean getDebug() {
			return delegate.getDebug();
		}

		@Override
		public void setDebug(boolean debugState) {
			delegate.setDebug(debugState);
		}

		@Override
		public void backup(boolean quick, String name, Long timeout) throws DataException, TimeoutException {
			delegate.backup(quick, name, timeout);
		}

		@Override
		public void performPeriodicMaintenance(Long timeout) throws DataException, TimeoutException {
			delegate.performPeriodicMaintenance(timeout);
		}

		@Override
		public void exportNodeLocalData() throws DataException {
			delegate.exportNodeLocalData();
		}

		@Override
		public void importDataFromFile(String filename) throws DataException, IOException {
			delegate.importDataFromFile(filename);
		}

		@Override
		public void checkConsistency() throws DataException {
			delegate.checkConsistency();
		}

		@Override
		public Connection getConnection() {
			return delegate.getConnection();
		}
	}

	private static class NullTipBlockRepository implements BlockRepository {
		private final BlockRepository delegate;

		private NullTipBlockRepository(BlockRepository delegate) {
			this.delegate = delegate;
		}

		@Override
		public org.qortal.data.block.BlockData fromSignature(byte[] signature) throws DataException {
			return delegate.fromSignature(signature);
		}

		@Override
		public org.qortal.data.block.BlockData fromReference(byte[] reference) throws DataException {
			return delegate.fromReference(reference);
		}

		@Override
		public org.qortal.data.block.BlockData fromHeight(int height) throws DataException {
			return delegate.fromHeight(height);
		}

		@Override
		public boolean exists(byte[] signature) throws DataException {
			return delegate.exists(signature);
		}

		@Override
		public int getHeightFromSignature(byte[] signature) throws DataException {
			return delegate.getHeightFromSignature(signature);
		}

		@Override
		public int getHeightFromTimestamp(long timestamp) throws DataException {
			return delegate.getHeightFromTimestamp(timestamp);
		}

		@Override
		public long getTimestampFromHeight(int height) throws DataException {
			return delegate.getTimestampFromHeight(height);
		}

		@Override
		public int getBlockchainHeight() throws DataException {
			return delegate.getBlockchainHeight();
		}

		@Override
		public org.qortal.data.block.BlockData getLastBlock() throws DataException {
			return null;
		}

		@Override
		public List<org.qortal.data.transaction.TransactionData> getTransactionsFromSignature(byte[] signature, Integer limit, Integer offset, Boolean reverse) throws DataException {
			return delegate.getTransactionsFromSignature(signature, limit, offset, reverse);
		}

		@Override
		public int countSignedBlocks(byte[] publicKey) throws DataException {
			return delegate.countSignedBlocks(publicKey);
		}

		@Override
		public int getOnlineAccountsSignaturesTrimHeight() throws DataException {
			return delegate.getOnlineAccountsSignaturesTrimHeight();
		}

		@Override
		public void setOnlineAccountsSignaturesTrimHeight(int trimHeight) throws DataException {
			delegate.setOnlineAccountsSignaturesTrimHeight(trimHeight);
		}

		@Override
		public int trimOldOnlineAccountsSignatures(int minHeight, int maxHeight) throws DataException {
			return delegate.trimOldOnlineAccountsSignatures(minHeight, maxHeight);
		}

		@Override
		public org.qortal.data.block.BlockData getDetachedBlockSignature(int startHeight) throws DataException {
			return delegate.getDetachedBlockSignature(startHeight);
		}

		@Override
		public java.util.List<org.qortal.data.block.BlockSummaryData> getBlockSummaries(int startHeight, int endHeight) throws DataException {
			return delegate.getBlockSummaries(startHeight, endHeight);
		}

		@Override
		public org.qortal.data.block.BlockData getBlockInRangeWithHighestOnlineAccountsCount(int startHeight, int endHeight) throws DataException {
			return delegate.getBlockInRangeWithHighestOnlineAccountsCount(startHeight, endHeight);
		}

		@Override
		public Long getTotalFeesInBlockRange(int startHeight, int endHeight) throws DataException {
			return delegate.getTotalFeesInBlockRange(startHeight, endHeight);
		}

		@Override
		public java.util.List<org.qortal.data.block.BlockData> getBlocks(int startHeight, int endHeight) throws DataException {
			return delegate.getBlocks(startHeight, endHeight);
		}

		@Override
		public java.util.List<org.qortal.data.block.BlockSummaryData> getBlockSummariesBySigner(byte[] publicKey, Integer limit, Integer offset, Boolean reverse) throws DataException {
			return delegate.getBlockSummariesBySigner(publicKey, limit, offset, reverse);
		}

		@Override
		public java.util.List<org.qortal.api.model.BlockSignerSummary> getBlockSigners(java.util.List<String> addresses, Integer limit, Integer offset, Boolean reverse) throws DataException {
			return delegate.getBlockSigners(addresses, limit, offset, reverse);
		}

		@Override
		public int getBlockPruneHeight() throws DataException {
			return delegate.getBlockPruneHeight();
		}

		@Override
		public void setBlockPruneHeight(int pruneHeight) throws DataException {
			delegate.setBlockPruneHeight(pruneHeight);
		}

		@Override
		public int pruneBlocks(int minHeight, int maxHeight) throws DataException {
			return delegate.pruneBlocks(minHeight, maxHeight);
		}

		@Override
		public void save(org.qortal.data.block.BlockData blockData) throws DataException {
			delegate.save(blockData);
		}

		@Override
		public void delete(org.qortal.data.block.BlockData blockData) throws DataException {
			delegate.delete(blockData);
		}

		@Override
		public void save(org.qortal.data.block.BlockTransactionData blockTransactionData) throws DataException {
			delegate.save(blockTransactionData);
		}

		@Override
		public void delete(org.qortal.data.block.BlockTransactionData blockTransactionData) throws DataException {
			delegate.delete(blockTransactionData);
		}
	}
}
