package org.qortal.repository.hsqldb;

import org.qortal.api.model.BlockSignerSummary;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.BlockTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.BlockRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.TransactionRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class HSQLDBBlockRepository implements BlockRepository {

	private static final String BLOCK_DB_COLUMNS = "version, reference, transaction_count, total_fees, "
			+ "transactions_signature, height, minted_when, minter, minter_signature, "
			+ "AT_count, AT_fees, online_accounts, online_accounts_count, online_accounts_timestamp, online_accounts_signatures";

	protected HSQLDBRepository repository;

	public HSQLDBBlockRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	private BlockData getBlockFromResultSet(ResultSet resultSet) throws DataException {
		if (resultSet == null)
			return null;

		try {
			int version = resultSet.getInt(1);
			byte[] reference = resultSet.getBytes(2);
			int transactionCount = resultSet.getInt(3);
			long totalFees = resultSet.getLong(4);
			byte[] transactionsSignature = resultSet.getBytes(5);
			int height = resultSet.getInt(6);
			long timestamp = resultSet.getLong(7);
			byte[] minterPublicKey = resultSet.getBytes(8);
			byte[] minterSignature = resultSet.getBytes(9);
			int atCount = resultSet.getInt(10);
			long atFees = resultSet.getLong(11);
			byte[] encodedOnlineAccounts = resultSet.getBytes(12);
			int onlineAccountsCount = resultSet.getInt(13);

			Long onlineAccountsTimestamp = resultSet.getLong(14);
			if (onlineAccountsTimestamp == 0 && resultSet.wasNull())
				onlineAccountsTimestamp = null;

			byte[] onlineAccountsSignatures = resultSet.getBytes(15);

			return new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp,
					minterPublicKey, minterSignature, atCount, atFees,
					encodedOnlineAccounts, onlineAccountsCount, onlineAccountsTimestamp, onlineAccountsSignatures);
		} catch (SQLException e) {
			throw new DataException("Error extracting data from result set", e);
		}
	}

	@Override
	public BlockData fromSignature(byte[] signature) throws DataException {
		String sql = "SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE signature = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, signature)) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching block by signature from repository", e);
		}
	}

	@Override
	public List<BlockData> fromSignatures(List<byte[]> signatures) throws DataException {
		StringBuffer sql = new StringBuffer();
		sql.append("SELECT " + BLOCK_DB_COLUMNS + " ");
		sql.append("FROM Blocks WHERE signature IN (");
		sql.append(String.join(", ", Collections.nCopies(signatures.size(), "?")));
		sql.append(")");

		List<BlockData> list;
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), signatures.toArray(new byte[0][]))) {
			if (resultSet == null) {
				return new ArrayList<>(0);
			}

			list = new ArrayList<>(signatures.size());
			do {
				BlockData blockFromResultSet = getBlockFromResultSet(resultSet);

				if( blockFromResultSet != null ) list.add(blockFromResultSet);
			} while( resultSet.next());

			return list;
		} catch (SQLException e) {
			throw new DataException("Error fetching block by signature from repository", e);
		} catch ( Exception e ) {
			throw new DataException(e.getMessage());
		}
	}

	@Override
	public BlockData fromReference(byte[] reference) throws DataException {
		String sql = "SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE reference = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, reference)) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching block by reference from repository", e);
		}
	}

	@Override
	public BlockData fromHeight(int height) throws DataException {
		String sql = "SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE height = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, height)) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching block by height from repository", e);
		}
	}

	@Override
	public boolean exists(byte[] signature) throws DataException {
		try {
			return this.repository.exists("Blocks", "signature = ?", signature);
		} catch (SQLException e) {
			throw new DataException("Unable to check for block in repository", e);
		}
	}

	@Override
	public int getHeightFromSignature(byte[] signature) throws DataException {
		String sql = "SELECT height FROM Blocks WHERE signature = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, signature)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Error obtaining block height by signature from repository", e);
		}
	}

	@Override
	public int getHeightFromTimestamp(long timestamp) throws DataException {
		// Uses (minted_when, height) index
		String sql = "SELECT height FROM Blocks WHERE minted_when <= ? ORDER BY minted_when DESC, height DESC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, timestamp)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Error obtaining block height by timestamp from repository", e);
		}
	}

	@Override
	public long getTimestampFromHeight(int height) throws DataException {
		String sql = "SELECT minted_when FROM Blocks WHERE height = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, height)) {
			if (resultSet == null)
				return 0;

			return resultSet.getLong(1);
		} catch (SQLException e) {
			throw new DataException("Error obtaining block timestamp by height from repository", e);
		}
	}

	@Override
	public int getBlockchainHeight() throws DataException {
		String sql = "SELECT height FROM Blocks ORDER BY height DESC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Error obtaining blockchain height from repository", e);
		}
	}

	@Override
	public BlockData getLastBlock() throws DataException {
		String sql = "SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks ORDER BY height DESC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching last block from repository", e);
		}
	}

	@Override
	public List<TransactionData> getTransactionsFromSignature(byte[] signature, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT transaction_signature FROM BlockTransactions WHERE block_signature = ? ORDER BY block_signature");
		if (reverse != null && reverse)
			sql.append(" DESC");

		sql.append(", sequence");
		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<byte[]> transactionSignatures = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), signature)) {
			if (resultSet == null)
				return new ArrayList<>(); // No transactions in this block

			do {
				transactionSignatures.add(resultSet.getBytes(1));
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch block's transactions from repository", e);
		}

		if (transactionSignatures.isEmpty()) {
			return new ArrayList<>();
		}

		TransactionRepository transactionRepo = this.repository.getTransactionRepository();
		List<TransactionData> fetchedTransactions = transactionRepo.fromSignatures(transactionSignatures);

		Map<ByteBuffer, TransactionData> txBySig = new HashMap<>();
		for (TransactionData tx : fetchedTransactions) {
			if (tx != null && tx.getSignature() != null) {
				txBySig.put(ByteBuffer.wrap(tx.getSignature()), tx);
			}
		}

		List<TransactionData> transactions = new ArrayList<>(transactionSignatures.size());
		for (byte[] sig : transactionSignatures) {
			TransactionData tx = txBySig.get(ByteBuffer.wrap(sig));
			if (tx != null) {
				transactions.add(tx);
			}
		}

		return transactions;
	}

	@Override
	public int countSignedBlocks(byte[] signerPublicKey) throws DataException {
		String directSql = "SELECT COUNT(*) FROM Blocks WHERE minter = ?";

		String rewardShareSql = "SELECT COUNT(*) FROM RewardShares "
		+ "JOIN Blocks ON minter = reward_share_public_key "
		+ "WHERE minter_public_key = ?";

		int totalCount = 0;

		try (ResultSet resultSet = this.repository.checkedExecute(directSql, signerPublicKey)) {
			totalCount += resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count minted blocks in repository", e);
		}

		try (ResultSet resultSet = this.repository.checkedExecute(rewardShareSql, signerPublicKey)) {
			totalCount += resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count reward-share minted blocks in repository", e);
		}

		return totalCount;
	}

	@Override
	public List<BlockSignerSummary> getBlockSigners(List<String> addresses, Integer limit, Integer offset, Boolean reverse) throws DataException {
		String subquerySql = "SELECT minter, COUNT(signature) FROM Blocks GROUP BY minter";

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT DISTINCT block_minter, n_blocks, minter_public_key, minter, recipient FROM (");
		sql.append(subquerySql);
		sql.append(") AS Minters (block_minter, n_blocks) LEFT OUTER JOIN RewardShares ON reward_share_public_key = block_minter ");

		if (addresses != null && !addresses.isEmpty()) {
			sql.append(" LEFT OUTER JOIN Accounts AS BlockMinterAccounts ON BlockMinterAccounts.public_key = block_minter ");
			sql.append(" LEFT OUTER JOIN Accounts AS RewardShareMinterAccounts ON RewardShareMinterAccounts.public_key = minter_public_key ");
			sql.append(" JOIN (VALUES ");

			final int addressesSize = addresses.size();
			for (int ai = 0; ai < addressesSize; ++ai) {
				if (ai != 0)
					sql.append(", ");

				sql.append("(?)");
			}

			sql.append(") AS FilterAccounts (account) ");
			sql.append(" ON FilterAccounts.account IN (recipient, BlockMinterAccounts.account, RewardShareMinterAccounts.account) ");
		} else {
			addresses = Collections.emptyList();
		}

		sql.append("ORDER BY n_blocks ");
		if (reverse != null && reverse)
			sql.append("DESC ");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<BlockSignerSummary> summaries = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), addresses.toArray())) {
			if (resultSet == null)
				return summaries;

			do {
				byte[] blockMinterPublicKey = resultSet.getBytes(1);
				int nBlocks = resultSet.getInt(2);

				// May not be present if no reward-share:
				byte[] mintingAccountPublicKey = resultSet.getBytes(3);
				String minterAccount = resultSet.getString(4);
				String recipientAccount = resultSet.getString(5);

				BlockSignerSummary blockSignerSummary;
				if (recipientAccount == null)
					blockSignerSummary = new BlockSignerSummary(blockMinterPublicKey, nBlocks);
				else
					blockSignerSummary = new BlockSignerSummary(blockMinterPublicKey, nBlocks, mintingAccountPublicKey, minterAccount, recipientAccount);

				summaries.add(blockSignerSummary);
			} while (resultSet.next());

			return summaries;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch block minters from repository", e);
		}
	}

	@Override
	public List<BlockSummaryData> getBlockSummariesBySigner(byte[] signerPublicKey, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		sql.append("SELECT signature, height, Blocks.minter, online_accounts_count, minted_when, transaction_count, Blocks.reference FROM ");

		// List of minter account's public key and reward-share public keys with minter's public key
		sql.append("(SELECT * FROM (VALUES (CAST(? AS QortalPublicKey))) UNION (SELECT reward_share_public_key FROM RewardShares WHERE minter_public_key = ?)) AS PublicKeys (public_key) ");

		// Match Blocks signed with public key from above list
		sql.append("JOIN Blocks ON Blocks.minter = public_key ");

		sql.append("ORDER BY Blocks.height ");
		if (reverse != null && reverse)
			sql.append("DESC ");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<BlockSummaryData> blockSummaries = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), signerPublicKey, signerPublicKey)) {
			if (resultSet == null)
				return blockSummaries;

			do {
				byte[] signature = resultSet.getBytes(1);
				int height = resultSet.getInt(2);
				byte[] blockMinterPublicKey = resultSet.getBytes(3);
				int onlineAccountsCount = resultSet.getInt(4);
				long timestamp = resultSet.getLong(5);
				int transactionCount = resultSet.getInt(6);
				byte[] reference = resultSet.getBytes(7);

				BlockSummaryData blockSummary = new BlockSummaryData(height, signature, blockMinterPublicKey, onlineAccountsCount,
						timestamp, transactionCount, reference);
				blockSummaries.add(blockSummary);
			} while (resultSet.next());

			return blockSummaries;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch minter's block summaries from repository", e);
		}
	}

	@Override
	public List<BlockData> getBlocks(int firstBlockHeight, int lastBlockHeight) throws DataException {
		String sql = "SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE height BETWEEN ? AND ?";

		List<BlockData> blockData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, firstBlockHeight, lastBlockHeight)) {
			if (resultSet == null)
				return blockData;

			do {
				blockData.add(getBlockFromResultSet(resultSet));
			} while (resultSet.next());

			return blockData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch height-ranged blocks from repository", e);
		}
	}

	@Override
	public Long getTotalFeesInBlockRange(int firstBlockHeight, int lastBlockHeight) throws DataException {
		String sql = "SELECT SUM(total_fees) AS sum_total_fees FROM Blocks WHERE height BETWEEN ? AND ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, firstBlockHeight, lastBlockHeight)) {
			if (resultSet == null)
				return null;

			long totalFees = resultSet.getLong(1);

			return totalFees;
		} catch (SQLException e) {
			throw new DataException("Error fetching total fees in block range from repository", e);
		}
	}

	@Override
	public BlockData getBlockInRangeWithHighestOnlineAccountsCount(int firstBlockHeight, int lastBlockHeight) throws DataException {
		String sql = "SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE height BETWEEN ? AND ? "
				+ "ORDER BY online_accounts_count DESC, height ASC "
				+ "LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, firstBlockHeight, lastBlockHeight)) {
			return getBlockFromResultSet(resultSet);

		} catch (SQLException e) {
			throw new DataException("Error fetching highest online accounts block in range from repository", e);
		}
	}

	@Override
	public List<BlockSummaryData> getBlockSummaries(int firstBlockHeight, int lastBlockHeight) throws DataException {
		String sql = "SELECT signature, height, minter, online_accounts_count, minted_when, transaction_count, reference "
				+ "FROM Blocks WHERE height BETWEEN ? AND ?";

		List<BlockSummaryData> blockSummaries = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, firstBlockHeight, lastBlockHeight)) {
			if (resultSet == null)
				return blockSummaries;

			do {
				byte[] signature = resultSet.getBytes(1);
				int height = resultSet.getInt(2);
				byte[] minterPublicKey = resultSet.getBytes(3);
				int onlineAccountsCount = resultSet.getInt(4);
				long timestamp = resultSet.getLong(5);
				int transactionCount = resultSet.getInt(6);
				byte[] reference = resultSet.getBytes(7);

				BlockSummaryData blockSummary = new BlockSummaryData(height, signature, minterPublicKey, onlineAccountsCount,
						timestamp, transactionCount, reference);
				blockSummaries.add(blockSummary);
			} while (resultSet.next());

			return blockSummaries;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch height-ranged block summaries from repository", e);
		}
	}

	@Override
	public int getOnlineAccountsSignaturesTrimHeight() throws DataException {
		String sql = "SELECT online_signatures_trim_height FROM DatabaseInfo";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch online accounts signatures trim height from repository", e);
		}
	}

	@Override
	public void setOnlineAccountsSignaturesTrimHeight(int trimHeight) throws DataException {
		// trimHeightsLock is to prevent concurrent update on DatabaseInfo
		// that could result in "transaction rollback: serialization failure"
		synchronized (this.repository.trimHeightsLock) {
			String updateSql = "UPDATE DatabaseInfo SET online_signatures_trim_height = ?";

			try {
				this.repository.executeCheckedUpdate(updateSql, trimHeight);
				this.repository.saveChanges();
			} catch (SQLException e) {
				repository.examineException(e);
				throw new DataException("Unable to set online accounts signatures trim height in repository", e);
			}
		}
	}

	@Override
	public int trimOldOnlineAccountsSignatures(int minHeight, int maxHeight) throws DataException {
		// We're often called so no need to trim all blocks in one go.
		// Limit updates to reduce CPU and memory load.
		String sql = "UPDATE Blocks SET online_accounts_signatures = NULL "
				+ "WHERE online_accounts_signatures IS NOT NULL "
				+ "AND height BETWEEN ? AND ?";

		try {
			return this.repository.executeCheckedUpdate(sql, minHeight, maxHeight);
		} catch (SQLException e) {
			repository.examineException(e);
			throw new DataException("Unable to trim old online accounts signatures in repository", e);
		}
	}


	@Override
	public int getBlockPruneHeight() throws DataException {
		String sql = "SELECT block_prune_height FROM DatabaseInfo";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch block prune height from repository", e);
		}
	}

	@Override
	public void setBlockPruneHeight(int pruneHeight) throws DataException {
		// trimHeightsLock is to prevent concurrent update on DatabaseInfo
		// that could result in "transaction rollback: serialization failure"
		synchronized (this.repository.trimHeightsLock) {
			String updateSql = "UPDATE DatabaseInfo SET block_prune_height = ?";

			try {
				this.repository.executeCheckedUpdate(updateSql, pruneHeight);
				this.repository.saveChanges();
			} catch (SQLException e) {
				repository.examineException(e);
				throw new DataException("Unable to set block prune height in repository", e);
			}
		}
	}

	@Override
	public int pruneBlocks(int minHeight, int maxHeight) throws DataException {
		// Don't prune the genesis block
		if (minHeight <= 1) {
			minHeight = 2;
		}

		try {
			return this.repository.delete("Blocks", "height BETWEEN ? AND ?", minHeight, maxHeight);
		} catch (SQLException e) {
			throw new DataException("Unable to prune blocks from repository", e);
		}
	}


	@Override
	public BlockData getDetachedBlockSignature(int startHeight) throws DataException {
		String sql = "SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks "
				+ "LEFT OUTER JOIN Blocks AS ParentBlocks "
				+ "ON ParentBlocks.signature = Blocks.reference "
				+ "WHERE ParentBlocks.signature IS NULL AND Blocks.height > ? "
				+ "ORDER BY Blocks.height ASC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, startHeight)) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching block by signature from repository", e);
		}
	}

	@Override
	public void save(BlockData blockData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Blocks");

		saveHelper.bind("signature", blockData.getSignature()).bind("version", blockData.getVersion()).bind("reference", blockData.getReference())
				.bind("transaction_count", blockData.getTransactionCount()).bind("total_fees", blockData.getTotalFees())
				.bind("transactions_signature", blockData.getTransactionsSignature()).bind("height", blockData.getHeight())
				.bind("minted_when", blockData.getTimestamp())
				.bind("minter", blockData.getMinterPublicKey()).bind("minter_signature", blockData.getMinterSignature())
				.bind("AT_count", blockData.getATCount()).bind("AT_fees", blockData.getATFees())
				.bind("online_accounts", blockData.getEncodedOnlineAccounts()).bind("online_accounts_count", blockData.getOnlineAccountsCount())
				.bind("online_accounts_timestamp", blockData.getOnlineAccountsTimestamp())
				.bind("online_accounts_signatures", blockData.getOnlineAccountsSignatures());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save Block into repository", e);
		}
	}

	@Override
	public void delete(BlockData blockData) throws DataException {
		try {
			this.repository.delete("Blocks", "signature = ?", blockData.getSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to delete Block from repository", e);
		}
	}

	@Override
	public void save(BlockTransactionData blockTransactionData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("BlockTransactions");

		saveHelper.bind("block_signature", blockTransactionData.getBlockSignature()).bind("sequence", blockTransactionData.getSequence())
				.bind("transaction_signature", blockTransactionData.getTransactionSignature());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save BlockTransaction into repository", e);
		}
	}

	@Override
	public void delete(BlockTransactionData blockTransactionData) throws DataException {
		try {
			this.repository.delete("BlockTransactions", "block_signature = ? AND sequence = ? AND transaction_signature = ?",
					blockTransactionData.getBlockSignature(), blockTransactionData.getSequence(), blockTransactionData.getTransactionSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to delete BlockTransaction from repository", e);
		}
	}

}
