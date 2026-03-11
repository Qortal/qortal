package org.qortal.repository.hsqldb;

import com.google.common.primitives.Longs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.repository.ATRepository;
import org.qortal.repository.DataException;
import org.qortal.utils.ByteArray;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.qortal.data.account.AccountData;

public class HSQLDBATRepository implements ATRepository {

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBATRepository.class);

	protected HSQLDBRepository repository;

	public HSQLDBATRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	// ATs

	@Override
	public ATData fromATAddress(String atAddress) throws DataException {
		String sql = "SELECT creator, created_when, version, asset_id, code_bytes, code_hash, "
				+ "is_sleeping, sleep_until_height, is_finished, had_fatal_error, "
				+ "is_frozen, frozen_balance, sleep_until_message_timestamp, final_height "
				+ "FROM ATs "
				+ "WHERE AT_address = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
			if (resultSet == null)
				return null;

			byte[] creatorPublicKey = resultSet.getBytes(1);
			long created = resultSet.getLong(2);
			int version = resultSet.getInt(3);
			long assetId = resultSet.getLong(4);
			byte[] codeBytes = resultSet.getBytes(5); // Actually BLOB
			byte[] codeHash = resultSet.getBytes(6);
			boolean isSleeping = resultSet.getBoolean(7);

			Integer sleepUntilHeight = resultSet.getInt(8);
			if (sleepUntilHeight == 0 && resultSet.wasNull())
				sleepUntilHeight = null;

			boolean isFinished = resultSet.getBoolean(9);
			boolean hadFatalError = resultSet.getBoolean(10);
			boolean isFrozen = resultSet.getBoolean(11);

			Long frozenBalance = resultSet.getLong(12);
			if (frozenBalance == 0 && resultSet.wasNull())
				frozenBalance = null;

			Long sleepUntilMessageTimestamp = resultSet.getLong(13);
			if (sleepUntilMessageTimestamp == 0 && resultSet.wasNull())
				sleepUntilMessageTimestamp = null;

			Integer finalHeight = resultSet.getInt(14);
			if (finalHeight == 0 && resultSet.wasNull())
				finalHeight = null;

			ATData atData = new ATData(atAddress, creatorPublicKey, created, version, assetId, codeBytes, codeHash,
					isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance,
					sleepUntilMessageTimestamp);
			atData.setFinalHeight(finalHeight);
			return atData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT from repository", e);
		}
	}

	@Override
	public List<ATData> fromATAddresses(List<String> atAddresses) throws DataException {
		String sql = "SELECT creator, created_when, version, asset_id, code_bytes, code_hash, "
				+ "is_sleeping, sleep_until_height, is_finished, had_fatal_error, "
				+ "is_frozen, frozen_balance, sleep_until_message_timestamp, AT_address, final_height "
				+ "FROM ATs "
				+ "WHERE AT_address IN ("
				+ String.join(", ", Collections.nCopies(atAddresses.size(), "?"))
				+ ")"
				;

		List<ATData> list;
		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddresses.toArray(new String[atAddresses.size()]))) {
			if (resultSet == null) {
				return new ArrayList<>(0);
			}

			list = new ArrayList<>(atAddresses.size());

			do {
				byte[] creatorPublicKey = resultSet.getBytes(1);
				long created = resultSet.getLong(2);
				int version = resultSet.getInt(3);
				long assetId = resultSet.getLong(4);
				byte[] codeBytes = resultSet.getBytes(5); // Actually BLOB
				byte[] codeHash = resultSet.getBytes(6);
				boolean isSleeping = resultSet.getBoolean(7);

				Integer sleepUntilHeight = resultSet.getInt(8);
				if (sleepUntilHeight == 0 && resultSet.wasNull())
					sleepUntilHeight = null;

				boolean isFinished = resultSet.getBoolean(9);
				boolean hadFatalError = resultSet.getBoolean(10);
				boolean isFrozen = resultSet.getBoolean(11);

				Long frozenBalance = resultSet.getLong(12);
				if (frozenBalance == 0 && resultSet.wasNull())
					frozenBalance = null;

				Long sleepUntilMessageTimestamp = resultSet.getLong(13);
				if (sleepUntilMessageTimestamp == 0 && resultSet.wasNull())
					sleepUntilMessageTimestamp = null;

				String atAddress = resultSet.getString(14);

				Integer finalHeight = resultSet.getInt(15);
				if (finalHeight == 0 && resultSet.wasNull())
					finalHeight = null;

				ATData atData = new ATData(atAddress, creatorPublicKey, created, version, assetId, codeBytes, codeHash,
						isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance,
						sleepUntilMessageTimestamp);
				atData.setFinalHeight(finalHeight);
				list.add(atData);
			} while ( resultSet.next());

			return list;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT from repository", e);
		}
	}

	@Override
	public boolean exists(String atAddress) throws DataException {
		try {
			return this.repository.exists("ATs", "AT_address = ?", atAddress);
		} catch (SQLException e) {
			throw new DataException("Unable to check for AT in repository", e);
		}
	}

	@Override
	public byte[] getCreatorPublicKey(String atAddress) throws DataException {
		String sql = "SELECT creator FROM ATs WHERE AT_address = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
			if (resultSet == null)
				return null;

			return resultSet.getBytes(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT creator's public key from repository", e);
		}
	}

	@Override
	public List<ATData> getAllExecutableATs() throws DataException {
		String sql = "SELECT AT_address, creator, created_when, version, asset_id, code_bytes, code_hash, "
				+ "is_sleeping, sleep_until_height, had_fatal_error, "
				+ "is_frozen, frozen_balance, sleep_until_message_timestamp "
				+ "FROM ATs "
				+ "WHERE is_finished = false "
				+ "ORDER BY created_when ASC, AT_address DESC";

		List<ATData> executableATs = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return executableATs;

			boolean isFinished = false;

			do {
				String atAddress = resultSet.getString(1);
				byte[] creatorPublicKey = resultSet.getBytes(2);
				long created = resultSet.getLong(3);
				int version = resultSet.getInt(4);
				long assetId = resultSet.getLong(5);
				byte[] codeBytes = resultSet.getBytes(6); // Actually BLOB
				byte[] codeHash = resultSet.getBytes(7);
				boolean isSleeping = resultSet.getBoolean(8);

				Integer sleepUntilHeight = resultSet.getInt(9);
				if (sleepUntilHeight == 0 && resultSet.wasNull())
					sleepUntilHeight = null;

				boolean hadFatalError = resultSet.getBoolean(10);
				boolean isFrozen = resultSet.getBoolean(11);

				Long frozenBalance = resultSet.getLong(12);
				if (frozenBalance == 0 && resultSet.wasNull())
					frozenBalance = null;

				Long sleepUntilMessageTimestamp = resultSet.getLong(13);
				if (sleepUntilMessageTimestamp == 0 && resultSet.wasNull())
					sleepUntilMessageTimestamp = null;

				ATData atData = new ATData(atAddress, creatorPublicKey, created, version, assetId, codeBytes, codeHash,
						isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance,
						sleepUntilMessageTimestamp);

				executableATs.add(atData);
			} while (resultSet.next());

			return executableATs;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch executable ATs from repository", e);
		}
	}

	@Override
	public List<ATData> getATsByFunctionality(byte[] codeHash, Boolean isExecutable, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT AT_address, creator, created_when, version, asset_id, code_bytes, ")
				.append("is_sleeping, sleep_until_height, is_finished, had_fatal_error, ")
				.append("is_frozen, frozen_balance, sleep_until_message_timestamp, final_height ")
				.append("FROM ATs ")
				.append("WHERE code_hash = ? ");
		bindParams.add(codeHash);

		if (isExecutable != null) {
			sql.append("AND is_finished != ? ");
			bindParams.add(isExecutable);
		}

		sql.append("ORDER BY created_when ");
		if (reverse != null && reverse)
			sql.append("DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ATData> matchingATs = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return matchingATs;

			do {
				String atAddress = resultSet.getString(1);
				byte[] creatorPublicKey = resultSet.getBytes(2);
				long created = resultSet.getLong(3);
				int version = resultSet.getInt(4);
				long assetId = resultSet.getLong(5);
				byte[] codeBytes = resultSet.getBytes(6); // Actually BLOB
				boolean isSleeping = resultSet.getBoolean(7);

				Integer sleepUntilHeight = resultSet.getInt(8);
				if (sleepUntilHeight == 0 && resultSet.wasNull())
					sleepUntilHeight = null;

				boolean isFinished = resultSet.getBoolean(9);

				boolean hadFatalError = resultSet.getBoolean(10);
				boolean isFrozen = resultSet.getBoolean(11);

				Long frozenBalance = resultSet.getLong(12);
				if (frozenBalance == 0 && resultSet.wasNull())
					frozenBalance = null;

				Long sleepUntilMessageTimestamp = resultSet.getLong(13);
				if (sleepUntilMessageTimestamp == 0 && resultSet.wasNull())
					sleepUntilMessageTimestamp = null;

				Integer finalHeight = resultSet.getInt(14);
				if (finalHeight == 0 && resultSet.wasNull())
					finalHeight = null;

				ATData atData = new ATData(atAddress, creatorPublicKey, created, version, assetId, codeBytes, codeHash,
						isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance,
						sleepUntilMessageTimestamp);
				atData.setFinalHeight(finalHeight);
				matchingATs.add(atData);
			} while (resultSet.next());

			return matchingATs;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching ATs from repository", e);
		}
	}

	@Override
	public List<ATData> getAllATsByFunctionality(Set<ByteArray> codeHashes, Boolean isExecutable) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT AT_address, creator, created_when, version, asset_id, code_bytes, ")
				.append("is_sleeping, sleep_until_height, is_finished, had_fatal_error, ")
				.append("is_frozen, frozen_balance, code_hash, sleep_until_message_timestamp, final_height ")
				.append("FROM ");

		// (VALUES (?), (?), ...) AS ATCodeHashes (code_hash)
		sql.append("(VALUES ");

		boolean isFirst = true;
		for (ByteArray codeHash : codeHashes) {
			if (!isFirst)
				sql.append(", ");
			else
				isFirst = false;

			sql.append("(CAST(? AS VARBINARY(256)))");
			bindParams.add(codeHash.value);
		}
		sql.append(") AS ATCodeHashes (code_hash) ");

		sql.append("JOIN ATs ON ATs.code_hash = ATCodeHashes.code_hash ");

		if (isExecutable != null) {
			sql.append("AND is_finished != ? ");
			bindParams.add(isExecutable);
		}

		List<ATData> matchingATs = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return matchingATs;

			do {
				String atAddress = resultSet.getString(1);
				byte[] creatorPublicKey = resultSet.getBytes(2);
				long created = resultSet.getLong(3);
				int version = resultSet.getInt(4);
				long assetId = resultSet.getLong(5);
				byte[] codeBytes = resultSet.getBytes(6); // Actually BLOB
				boolean isSleeping = resultSet.getBoolean(7);

				Integer sleepUntilHeight = resultSet.getInt(8);
				if (sleepUntilHeight == 0 && resultSet.wasNull())
					sleepUntilHeight = null;

				boolean isFinished = resultSet.getBoolean(9);

				boolean hadFatalError = resultSet.getBoolean(10);
				boolean isFrozen = resultSet.getBoolean(11);

				Long frozenBalance = resultSet.getLong(12);
				if (frozenBalance == 0 && resultSet.wasNull())
					frozenBalance = null;

				byte[] codeHash = resultSet.getBytes(13);
				Long sleepUntilMessageTimestamp = resultSet.getLong(14);

				Integer finalHeight = resultSet.getInt(15);
				if (finalHeight == 0 && resultSet.wasNull())
					finalHeight = null;

				ATData atData = new ATData(atAddress, creatorPublicKey, created, version, assetId, codeBytes, codeHash,
						isSleeping, sleepUntilHeight, isFinished, hadFatalError, isFrozen, frozenBalance, sleepUntilMessageTimestamp);
				atData.setFinalHeight(finalHeight);
				matchingATs.add(atData);
			} while (resultSet.next());

			return matchingATs;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching ATs from repository", e);
		}
	}

	@Override
	public Integer getATCreationBlockHeight(String atAddress) throws DataException {
		String sql = "SELECT block_height "
				+ "FROM DeployATTransactions "
				+ "JOIN Transactions USING (signature) "
				+ "WHERE AT_address = ? "
				+ "LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT's creation block height from repository", e);
		}
	}

	@Override
	public void save(ATData atData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("ATs");

		saveHelper.bind("AT_address", atData.getATAddress()).bind("creator", atData.getCreatorPublicKey()).bind("created_when", atData.getCreation())
				.bind("version", atData.getVersion()).bind("asset_id", atData.getAssetId())
				.bind("code_bytes", atData.getCodeBytes()).bind("code_hash", atData.getCodeHash())
				.bind("is_sleeping", atData.getIsSleeping()).bind("sleep_until_height", atData.getSleepUntilHeight())
				.bind("is_finished", atData.getIsFinished()).bind("final_height", atData.getFinalHeight())
				.bind("had_fatal_error", atData.getHadFatalError()).bind("is_frozen", atData.getIsFrozen())
				.bind("frozen_balance", atData.getFrozenBalance()).bind("sleep_until_message_timestamp", atData.getSleepUntilMessageTimestamp());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save AT into repository", e);
		}
	}

	@Override
	public void delete(String atAddress) throws DataException {
		try {
			this.repository.delete("ATs", "AT_address = ?", atAddress);
			// AT States also deleted via ON DELETE CASCADE
		} catch (SQLException e) {
			throw new DataException("Unable to delete AT from repository", e);
		}
	}

	// AT State

	@Override
	public ATStateData getATStateAtHeight(String atAddress, int height) throws DataException {
		String sql = "SELECT state_data, state_hash, fees, is_initial, sleep_until_message_timestamp "
				+ "FROM ATStates "
				+ "LEFT OUTER JOIN ATStatesData USING (AT_address, height) "
				+ "WHERE ATStates.AT_address = ? AND ATStates.height = ? "
				+ "LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress, height)) {
			if (resultSet == null)
				return null;

			byte[] stateData = resultSet.getBytes(1); // Actually BLOB
			byte[] stateHash = resultSet.getBytes(2);
			long fees = resultSet.getLong(3);
			boolean isInitial = resultSet.getBoolean(4);

			Long sleepUntilMessageTimestamp = resultSet.getLong(5);
			if (sleepUntilMessageTimestamp == 0 && resultSet.wasNull())
				sleepUntilMessageTimestamp = null;

			return new ATStateData(atAddress, height, stateData, stateHash, fees, isInitial, sleepUntilMessageTimestamp);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT state from repository", e);
		}
	}

	@Override
	public ATStateData getLatestATState(String atAddress) throws DataException {
		String sql = "SELECT height, state_data, state_hash, fees, is_initial, sleep_until_message_timestamp "
				+ "FROM ATStates "
				+ "JOIN ATStatesData USING (AT_address, height) "
				+ "WHERE ATStates.AT_address = ? "
				// Order by AT_address and height to use compound primary key as index
				// Both must be the same direction (DESC) also
				+ "ORDER BY ATStates.AT_address DESC, ATStates.height DESC "
				+ "LIMIT 1 ";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
			if (resultSet == null)
				return null;

			int height = resultSet.getInt(1);
			byte[] stateData = resultSet.getBytes(2); // Actually BLOB
			byte[] stateHash = resultSet.getBytes(3);
			long fees = resultSet.getLong(4);
			boolean isInitial = resultSet.getBoolean(5);

			Long sleepUntilMessageTimestamp = resultSet.getLong(6);
			if (sleepUntilMessageTimestamp == 0 && resultSet.wasNull())
				sleepUntilMessageTimestamp = null;

			return new ATStateData(atAddress, height, stateData, stateHash, fees, isInitial, sleepUntilMessageTimestamp);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest AT state from repository", e);
		}
	}

	@Override
	public List<ATStateData> getLatestATStates(List<String> atAddresses) throws DataException{
		if (atAddresses.isEmpty())
			return new ArrayList<>(0);

		// Issue one query per AT address, reusing the same PreparedStatement (HSQLDB caches by SQL string).
		// HSQLDB cannot use the ATStates PK efficiently inside a VALUES+LATERAL subquery — the planner
		// treats the correlated subquery as a full scan regardless of indexes. Per-address queries with
		// ORDER BY AT_address DESC, height DESC LIMIT 1 let HSQLDB walk the PK in reverse, giving an
		// O(1) index seek per AT instead of a full scan.
		// All queries run on the same session so they see the same consistent read snapshot — no stale data.
		final String sql = "SELECT height, state_data, state_hash, fees, is_initial, sleep_until_message_timestamp "
				+ "FROM ATStates "
				+ "JOIN ATStatesData USING (AT_address, height) "
				+ "WHERE ATStates.AT_address = ? "
				+ "ORDER BY ATStates.AT_address DESC, ATStates.height DESC "
				+ "LIMIT 1";

		List<ATStateData> results = new ArrayList<>(atAddresses.size());

		try {
			for (String atAddress : atAddresses) {
				try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
					if (resultSet == null)
						continue;

					int height = resultSet.getInt(1);
					byte[] stateData = resultSet.getBytes(2); // Actually BLOB
					byte[] stateHash = resultSet.getBytes(3);
					long fees = resultSet.getLong(4);
					boolean isInitial = resultSet.getBoolean(5);

					Long sleepUntilMessageTimestamp = resultSet.getLong(6);
					if (sleepUntilMessageTimestamp == 0 && resultSet.wasNull())
						sleepUntilMessageTimestamp = null;

					results.add(new ATStateData(atAddress, height, stateData, stateHash, fees, isInitial, sleepUntilMessageTimestamp));
				}
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest AT states from repository", e);
		}

		return results;
	}

	@Override
	public List<ATStateData> getMatchingFinalATStates(byte[] codeHash, byte[] buyerPublicKey, byte[] sellerPublicKey, Boolean isFinished,
			  Integer dataByteOffset, Long expectedValue, Integer minimumFinalHeight,
			  Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(1024);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT AT_address, height, state_data, state_hash, fees, is_initial, FinalATStates.sleep_until_message_timestamp "
				+ "FROM ATs "
				+ "CROSS JOIN LATERAL("
					+ "SELECT height, state_data, state_hash, fees, is_initial, sleep_until_message_timestamp "
					+ "FROM ATStates "
					+ "JOIN ATStatesData USING (AT_address, height) "
					+ "WHERE ATStates.AT_address = ATs.AT_address ");

		// For non-finished ATs we still need to filter inside the LATERAL since final_height isn't set.
		// For finished ATs the outer WHERE on ATs.final_height handles the filter more efficiently.
		if (minimumFinalHeight != null && (isFinished == null || !isFinished)) {
			sql.append("AND ATStates.height >= ? ");
			bindParams.add(minimumFinalHeight);
		}

		// Order by AT_address and height to use compound primary key as index
		// Both must be the same direction (DESC) also
		sql.append("ORDER BY ATStates.height DESC LIMIT 1) AS FinalATStates ");

		sql.append("WHERE ATs.code_hash = ? ");
		bindParams.add(codeHash);

		if (isFinished != null) {
			sql.append("AND is_finished = ? ");
			bindParams.add(isFinished);
		}

		// When querying finished ATs with a minimum height, use ATs.final_height directly.
		// This allows the planner to filter the AT candidate set before running the LATERAL,
		// instead of running the LATERAL for all finished ATs and discarding most.
		// Falls back to FinalATStates.height for ATs without final_height (pre-migration rows).
		if (minimumFinalHeight != null && isFinished != null && isFinished) {
			sql.append("AND (ATs.final_height >= ? OR (ATs.final_height IS NULL AND FinalATStates.height >= ?)) ");
			bindParams.add(minimumFinalHeight);
			bindParams.add(minimumFinalHeight);
		}

		// For finished ATs, apply the mode filter in SQL via SUBSTRING — the candidate set is already
		// small (bounded by minimumFinalHeight + ATFinalHeightIndex), so the BLOB scan is cheap.
		// For non-finished ATs there is no height bound, so a SUBSTRING scan over every active AT's
		// BLOB is catastrophically slow (O(N) BLOB reads). Instead we skip the SQL filter here and
		// apply it in Java below after the LATERAL has fetched the state data rows.
		final boolean applySubstringInSql = dataByteOffset != null && expectedValue != null
				&& (isFinished == null || isFinished);

		if (applySubstringInSql) {
			sql.append("AND SUBSTRING(state_data FROM ? FOR 8) = ? ");

			// We convert our long on Java-side to control endian
			byte[] rawExpectedValue = Longs.toByteArray(expectedValue);

			// SQL binary data offsets start at 1
			bindParams.add(dataByteOffset + 1);
			bindParams.add(rawExpectedValue);
		}

		if (buyerPublicKey != null && buyerPublicKey.length > 0) {
			// Restrict to ATs that have paid this buyer using a subquery so the planner
			// can narrow the AT candidate set before running the LATERAL, rather than
			// joining ATTRANSACTIONS after the LATERAL has already run for every AT.
			// Uses ATTransactionsRecipientATAddressIndex (recipient, at_address) for an index-only scan.
			sql.append("AND ATs.AT_address IN (SELECT at_address FROM ATTransactions WHERE recipient = ?) ");
			sql.append("AND ATs.creator != ? ");

			bindParams.add(Crypto.toAddress(buyerPublicKey));
			bindParams.add(buyerPublicKey);
		}


		if (sellerPublicKey != null && sellerPublicKey.length > 0) {
			sql.append("AND ATs.creator = ? ");
			bindParams.add(sellerPublicKey);
		}

		sql.append(" ORDER BY FinalATStates.height ");
		if (reverse != null && reverse)
			sql.append("DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ATStateData> atStates = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return atStates;

			do {
				String atAddress = resultSet.getString(1);
				int height = resultSet.getInt(2);
				byte[] stateData = resultSet.getBytes(3); // Actually BLOB
				byte[] stateHash = resultSet.getBytes(4);
				long fees = resultSet.getLong(5);
				boolean isInitial = resultSet.getBoolean(6);

				Long sleepUntilMessageTimestamp = resultSet.getLong(7);
				if (sleepUntilMessageTimestamp == 0 && resultSet.wasNull())
					sleepUntilMessageTimestamp = null;

				ATStateData atStateData = new ATStateData(atAddress, height, stateData, stateHash, fees, isInitial, sleepUntilMessageTimestamp);

				atStates.add(atStateData);
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching AT states from repository", e);
		}

		// When SUBSTRING was skipped in SQL (non-finished ATs), apply the mode filter here in Java.
		// This avoids a full BLOB scan on every active AT inside the HSQLDB query planner,
		// and is safe because state_data is already fetched into memory.
		if (!applySubstringInSql && dataByteOffset != null && expectedValue != null) {
			final byte[] rawExpectedValue = Longs.toByteArray(expectedValue);
			final int offset0 = dataByteOffset; // 0-based byte offset into state_data

			atStates.removeIf(atState -> {
				byte[] sd = atState.getStateData();
				if (sd == null || sd.length < offset0 + 8)
					return true; // missing data — exclude

				for (int i = 0; i < 8; i++) {
					if (sd[offset0 + i] != rawExpectedValue[i])
						return true; // mismatch — exclude
				}
				return false; // passes filter — keep
			});
		}

		return atStates;
	}

	@Override
	public List<ATStateData> getMatchingFinalATStatesQuorum(byte[] codeHash, Boolean isFinished,
			Integer dataByteOffset, Long expectedValue,
			int minimumCount, int maximumCount, long minimumPeriod) throws DataException {
		// We need most recent entry first so we can use its timestamp to slice further results
		List<ATStateData> mostRecentStates = this.getMatchingFinalATStates(codeHash, null, null, isFinished,
				dataByteOffset, expectedValue, null,
				1, 0, true);

		if (mostRecentStates == null)
			return null;

		if (mostRecentStates.isEmpty())
			return mostRecentStates;

		ATStateData mostRecentState = mostRecentStates.get(0);

		StringBuilder sql = new StringBuilder(1024);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT AT_address, height, state_data, state_hash, fees, is_initial, sleep_until_message_timestamp "
				+ "FROM ATs "
				+ "CROSS JOIN LATERAL("
					+ "SELECT height, state_data, state_hash, fees, is_initial "
					+ "FROM ATStates "
					+ "JOIN ATStatesData USING (AT_address, height) "
					+ "WHERE ATStates.AT_address = ATs.AT_address ");

		// Order by AT_address and height to use compound primary key as index
		// Both must be the same direction (DESC) also
		sql.append("ORDER BY ATStates.AT_address DESC, ATStates.height DESC "
					+ "LIMIT 1 "
				+ ") AS FinalATStates "
				+ "WHERE code_hash = ? ");
		bindParams.add(codeHash);

		if (isFinished != null) {
			sql.append("AND is_finished = ? ");
			bindParams.add(isFinished);
		}

		if (dataByteOffset != null && expectedValue != null) {
			sql.append("AND SUBSTRING(state_data FROM ? FOR 8) = ? ");

			// We convert our long on Java-side to control endian
			byte[] rawExpectedValue = Longs.toByteArray(expectedValue);

			// SQL binary data offsets start at 1
			bindParams.add(dataByteOffset + 1);
			bindParams.add(rawExpectedValue);
		}

		// Slice so that we meet both minimumCount and minimumPeriod
		int minimumHeight = mostRecentState.getHeight() - (int) (minimumPeriod / 60 * 1000L); // XXX assumes 60 second blocks

		sql.append("AND (FinalATStates.height >= ? OR ROWNUM() < ?) ");
		bindParams.add(minimumHeight);
		bindParams.add(minimumCount);

		sql.append("ORDER BY FinalATStates.height DESC LIMIT ?");
		bindParams.add(maximumCount);

		List<ATStateData> atStates = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return atStates;

			do {
				String atAddress = resultSet.getString(1);
				int height = resultSet.getInt(2);
				byte[] stateData = resultSet.getBytes(3); // Actually BLOB
				byte[] stateHash = resultSet.getBytes(4);
				long fees = resultSet.getLong(5);
				boolean isInitial = resultSet.getBoolean(6);
				Long sleepUntilMessageTimestamp = resultSet.getLong(7);

				ATStateData atStateData = new ATStateData(atAddress, height, stateData, stateHash, fees, isInitial,
						sleepUntilMessageTimestamp);

				atStates.add(atStateData);
			} while (resultSet.next());

			return atStates;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching AT states from repository", e);
		}
	}

	@Override
	public List<ATStateData> getBlockATStatesAtHeight(int height) throws DataException {
		String sql = "SELECT AT_address, state_hash, fees, is_initial "
				+ "FROM ATs "
				+ "JOIN ATStates "
				+ "ON ATStates.AT_address = ATs.AT_address "
				+ "WHERE height = ? "
				+ "ORDER BY created_when ASC, AT_address DESC";

		List<ATStateData> atStates = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, height)) {
			if (resultSet == null)
				return atStates; // No atStates in this block

			// NB: do-while loop because .checkedExecute() implicitly calls ResultSet.next() for us
			do {
				String atAddress = resultSet.getString(1);
				byte[] stateHash = resultSet.getBytes(2);
				long fees = resultSet.getLong(3);
				boolean isInitial = resultSet.getBoolean(4);

				ATStateData atStateData = new ATStateData(atAddress, height, stateHash, fees, isInitial);
				atStates.add(atStateData);
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT states for this height from repository", e);
		}

		return atStates;
	}


	@Override
	public void rebuildLatestAtStates(int maxHeight) throws DataException {
		// latestATStatesLock is to prevent concurrent updates on LatestATStates
		// that could result in one process using a partial or empty dataset
		// because it was in the process of being rebuilt by another thread
		synchronized (this.repository.latestATStatesLock) {
			LOGGER.trace("Rebuilding latest AT states...");

			// Rebuild cache of latest AT states that we can't trim
			String deleteSql = "DELETE FROM LatestATStates";
			try {
				this.repository.executeCheckedUpdate(deleteSql);
			} catch (SQLException e) {
				repository.examineException(e);
				throw new DataException("Unable to delete temporary latest AT states cache from repository", e);
			}

			String insertSql = "INSERT INTO LatestATStates ("
					+ "SELECT AT_address, height FROM ATs "
					+ "CROSS JOIN LATERAL("
					+ "SELECT height FROM ATStates "
					+ "WHERE ATStates.AT_address = ATs.AT_address "
					+ "AND height <= ?"
					+ "ORDER BY AT_address DESC, height DESC LIMIT 1"
					+ ") "
					+ ")";
			try {
				this.repository.executeCheckedUpdate(insertSql, maxHeight);
			} catch (SQLException e) {
				repository.examineException(e);
				throw new DataException("Unable to populate temporary latest AT states cache in repository", e);
			}
			this.repository.saveChanges();
			LOGGER.trace("Rebuilt latest AT states");
		}
	}


	@Override
	public int getAtTrimHeight() throws DataException {
		String sql = "SELECT AT_trim_height FROM DatabaseInfo";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT state trim height from repository", e);
		}
	}

	@Override
	public void setAtTrimHeight(int trimHeight) throws DataException {
		// trimHeightsLock is to prevent concurrent update on DatabaseInfo
		// that could result in "transaction rollback: serialization failure"
		synchronized (this.repository.trimHeightsLock) {
			String updateSql = "UPDATE DatabaseInfo SET AT_trim_height = ?";

			try {
				this.repository.executeCheckedUpdate(updateSql, trimHeight);
				this.repository.saveChanges();
			} catch (SQLException e) {
				this.repository.examineException(e);
				throw new DataException("Unable to set AT state trim height in repository", e);
			}
		}
	}

	@Override
	public int trimAtStates(int minHeight, int maxHeight, int limit) throws DataException {
		if (minHeight >= maxHeight)
			return 0;

		// latestATStatesLock is to prevent concurrent updates on LatestATStates
		// that could result in one process using a partial or empty dataset
		// because it was in the process of being rebuilt by another thread
		synchronized (this.repository.latestATStatesLock) {

			// We're often called so no need to trim all states in one go.
			// Limit updates to reduce CPU and memory load.
			String sql = "DELETE FROM ATStatesData "
					+ "WHERE height BETWEEN ? AND ? "
					+ "AND NOT EXISTS("
					+ "SELECT TRUE FROM LatestATStates "
					+ "WHERE LatestATStates.AT_address = ATStatesData.AT_address "
					+ "AND LatestATStates.height = ATStatesData.height"
					+ ") "
					+ "LIMIT ?";

			try {
				int modifiedRows = this.repository.executeCheckedUpdate(sql, minHeight, maxHeight, limit);
				this.repository.saveChanges();
				return modifiedRows;

			} catch (SQLException e) {
				repository.examineException(e);
				throw new DataException("Unable to trim AT states in repository", e);
			}
		}
	}


	@Override
	public int getAtPruneHeight() throws DataException {
		String sql = "SELECT AT_prune_height FROM DatabaseInfo";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch AT state prune height from repository", e);
		}
	}

	@Override
	public void setAtPruneHeight(int pruneHeight) throws DataException {
		// trimHeightsLock is to prevent concurrent update on DatabaseInfo
		// that could result in "transaction rollback: serialization failure"
		synchronized (this.repository.trimHeightsLock) {
			String updateSql = "UPDATE DatabaseInfo SET AT_prune_height = ?";

			try {
				this.repository.executeCheckedUpdate(updateSql, pruneHeight);
				this.repository.saveChanges();
			} catch (SQLException e) {
				repository.examineException(e);
				throw new DataException("Unable to set AT state prune height in repository", e);
			}
		}
	}

	@Override
	public int pruneAtStates(int minHeight, int maxHeight) throws DataException {
		// latestATStatesLock is to prevent concurrent updates on LatestATStates
		// that could result in one process using a partial or empty dataset
		// because it was in the process of being rebuilt by another thread
		synchronized (this.repository.latestATStatesLock) {

			int deletedCount = 0;

			for (int height = minHeight; height <= maxHeight; height++) {

				// Give up if we're stopping
				if (Controller.isStopping()) {
					return deletedCount;
				}

				// Get latest AT states for this height
				List<String> atAddresses = new ArrayList<>();
				String updateSql = "SELECT AT_address FROM LatestATStates WHERE height = ?";
				try (ResultSet resultSet = this.repository.checkedExecute(updateSql, height)) {
					if (resultSet != null) {
						do {
							String atAddress = resultSet.getString(1);
							atAddresses.add(atAddress);

						} while (resultSet.next());
					}
				} catch (SQLException e) {
					throw new DataException("Unable to fetch latest AT states from repository", e);
				}

				List<ATStateData> atStates = this.getBlockATStatesAtHeight(height);
				for (ATStateData atState : atStates) {
					//LOGGER.info("Found atState {} at height {}", atState.getATAddress(), atState.getHeight());

					// Give up if we're stopping
					if (Controller.isStopping()) {
						return deletedCount;
					}

					if (atAddresses.contains(atState.getATAddress())) {
						// We don't want to delete this AT state because it is still active
						LOGGER.trace("Skipping atState {} at height {}", atState.getATAddress(), atState.getHeight());
						continue;
					}

					// Safe to delete everything else for this height
					try {
						this.repository.delete("ATStates", "AT_address = ? AND height = ?",
								atState.getATAddress(), atState.getHeight());
						deletedCount++;
					} catch (SQLException e) {
						throw new DataException("Unable to delete AT state data from repository", e);
					}
				}
			}
			this.repository.saveChanges();

			return deletedCount;
		}
	}


	@Override
	public boolean hasAtStatesHeightIndex() throws DataException {
		String sql = "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.SYSTEM_INDEXINFO where INDEX_NAME='ATSTATESHEIGHTINDEX'";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			return resultSet != null;

		} catch (SQLException e) {
			throw new DataException("Unable to check for ATStatesHeightIndex in repository", e);
		}
	}


	@Override
	public void save(ATStateData atStateData) throws DataException {
		// We shouldn't ever save partial ATStateData
		if (atStateData.getStateHash() == null || atStateData.getHeight() == null)
			throw new IllegalArgumentException("Refusing to save partial AT state into repository!");

		HSQLDBSaver atStatesSaver = new HSQLDBSaver("ATStates");

		atStatesSaver.bind("AT_address", atStateData.getATAddress()).bind("height", atStateData.getHeight())
				.bind("state_hash", atStateData.getStateHash())
				.bind("fees", atStateData.getFees()).bind("is_initial", atStateData.isInitial())
				.bind("sleep_until_message_timestamp", atStateData.getSleepUntilMessageTimestamp());

		try {
			atStatesSaver.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save AT state into repository", e);
		}

		if (atStateData.getStateData() != null) {
			HSQLDBSaver atStatesDataSaver = new HSQLDBSaver("ATStatesData");

			atStatesDataSaver.bind("AT_address", atStateData.getATAddress()).bind("height", atStateData.getHeight())
					.bind("state_data", atStateData.getStateData());

			try {
				atStatesDataSaver.execute(this.repository);
			} catch (SQLException e) {
				throw new DataException("Unable to save AT state data into repository", e);
			}
		} else {
			try {
				this.repository.delete("ATStatesData", "AT_address = ? AND height = ?",
						atStateData.getATAddress(), atStateData.getHeight());
			} catch (SQLException e) {
				throw new DataException("Unable to delete AT state data from repository", e);
			}
		}
	}

	@Override
	public void delete(String atAddress, int height) throws DataException {
		try {
			this.repository.delete("ATStates", "AT_address = ? AND height = ?", atAddress, height);
			this.repository.delete("ATStatesData", "AT_address = ? AND height = ?", atAddress, height);
		} catch (SQLException e) {
			throw new DataException("Unable to delete AT state from repository", e);
		}
	}

	@Override
	public void deleteATStates(int height) throws DataException {
		try {
			this.repository.delete("ATStates", "height = ?", height);
			this.repository.delete("ATStatesData", "height = ?", height);
		} catch (SQLException e) {
			throw new DataException("Unable to delete AT states from repository", e);
		}
	}

	// Finding transactions for ATs to process

	public NextTransactionInfo findNextTransaction(String recipient, int height, int sequence) throws DataException {
		// We only need to search for a subset of transaction types: MESSAGE, PAYMENT or AT

		String sql = "SELECT block_height, block_sequence, Transactions.signature "
				+ "FROM ("
					+ "SELECT signature FROM PaymentTransactions WHERE recipient = ? "
					+ "UNION "
					+ "SELECT signature FROM MessageTransactions WHERE recipient = ? "
					+ "UNION "
					+ "SELECT signature FROM ATTransactions WHERE recipient = ?"
				+ ") AS SelectedTransactions "
				+ "JOIN Transactions USING (signature)"
				+ "WHERE (block_height > ? OR (block_height = ? AND block_sequence > ?)) "
				+ "ORDER BY block_height ASC, block_sequence ASC "
				+ "LIMIT 1";

		Object[] bindParams = new Object[] { recipient, recipient, recipient, height, height, sequence };

		try (ResultSet resultSet = this.repository.checkedExecute(sql, bindParams)) {
			if (resultSet == null)
				return null;

			int nextHeight = resultSet.getInt(1);
			int nextSequence = resultSet.getInt(2);
			byte[] nextSignature = resultSet.getBytes(3);

			return new NextTransactionInfo(nextHeight, nextSequence, nextSignature);
		} catch (SQLException e) {
			throw new DataException("Unable to find next transaction to AT from repository", e);
		}
	}

	// Other

	public void checkConsistency() throws DataException {
		String sql = "SELECT COUNT(*) FROM ATs "
				+ "CROSS JOIN LATERAL("
					+ "SELECT height FROM ATStates "
					+ "WHERE ATStates.AT_address = ATs.AT_address "
					+ "ORDER BY AT_address DESC, height DESC "
					+ "LIMIT 1"
				+ ") AS LatestATState (height) "
				+ "LEFT OUTER JOIN ATStatesData "
				+ "ON ATStatesData.AT_address = ATs.AT_address AND ATStatesData.height = LatestATState.height "
				+ "WHERE ATStatesData.AT_address IS NULL";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				throw new DataException("Unable to check AT repository consistency");

			int atCount = resultSet.getInt(1);

			if (atCount > 0) {
				LOGGER.warn(() -> String.format("Missing %d latest AT state data row%s!", atCount, (atCount != 1 ? "s" : "")));
				LOGGER.warn("Export key data then resync using bootstrap as soon as possible");
			}
		} catch (SQLException e) {
			throw new DataException("Unable to check AT repository consistency", e);
		}
	}

}
