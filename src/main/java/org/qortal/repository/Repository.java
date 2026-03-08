package org.qortal.repository;

import java.io.IOException;
import java.sql.Connection;
import java.util.concurrent.TimeoutException;

public interface Repository extends AutoCloseable {

	public ATRepository getATRepository();

	public AccountRepository getAccountRepository();

	public ArbitraryRepository getArbitraryRepository();

	public AssetRepository getAssetRepository();

	public BlockRepository getBlockRepository();

	public BlockArchiveRepository getBlockArchiveRepository();

	public ChatRepository getChatRepository();

	public CrossChainRepository getCrossChainRepository();

	public GroupRepository getGroupRepository();

	public MessageRepository getMessageRepository();

	public NameRepository getNameRepository();

	public NetworkRepository getNetworkRepository();

	public TransactionRepository getTransactionRepository();

	public VotingRepository getVotingRepository();

	public void saveChanges() throws DataException;

	public void discardChanges() throws DataException;

	/**
	 * Commit changes and immediately checkpoint to ensure durability.
	 * Use this ONLY for critical state that must survive restarts (e.g., DatabaseInfo flags).
	 * Regular transactions should use saveChanges() which relies on periodic checkpoints.
	 */
	public void saveChangesAndCheckpoint() throws DataException;

	public void setSavepoint() throws DataException;

	public void rollbackToSavepoint() throws DataException;

	@Override
	public void close() throws DataException;

	public void rebuild() throws DataException;

	public boolean getDebug();

	public void setDebug(boolean debugState);

	public void backup(boolean quick, String name, Long timeout) throws DataException, TimeoutException;

	public void performPeriodicMaintenance(Long timeout) throws DataException, TimeoutException;

	public void exportNodeLocalData() throws DataException;

	public void importDataFromFile(String filename) throws DataException, IOException;

	public void checkConsistency() throws DataException;

	public static void attemptRecovery(String connectionUrl, String name) throws DataException {}

    public Connection getConnection();
}
