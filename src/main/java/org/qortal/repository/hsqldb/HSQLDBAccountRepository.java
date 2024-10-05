package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.asset.Asset;
import org.qortal.data.account.*;
import org.qortal.repository.AccountRepository;
import org.qortal.repository.DataException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.qortal.utils.Amounts.prettyAmount;

public class HSQLDBAccountRepository implements AccountRepository {

	public static final String SELL = "sell";
	public static final String BUY = "buy";
	protected HSQLDBRepository repository;

	public HSQLDBAccountRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	protected static final Logger LOGGER = LogManager.getLogger(HSQLDBAccountRepository.class);
	// General account

	@Override
	public AccountData getAccount(String address) throws DataException {
		String sql = "SELECT reference, public_key, default_group_id, flags, level, blocks_minted, blocks_minted_adjustment, blocks_minted_penalty FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			byte[] reference = resultSet.getBytes(1);
			byte[] publicKey = resultSet.getBytes(2);
			int defaultGroupId = resultSet.getInt(3);
			int flags = resultSet.getInt(4);
			int level = resultSet.getInt(5);
			int blocksMinted = resultSet.getInt(6);
			int blocksMintedAdjustment = resultSet.getInt(7);
			int blocksMintedPenalty = resultSet.getInt(8);

			return new AccountData(address, reference, publicKey, defaultGroupId, flags, level, blocksMinted, blocksMintedAdjustment, blocksMintedPenalty);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account info from repository", e);
		}
	}

	@Override
	public List<AccountData> getFlaggedAccounts(int mask) throws DataException {
		String sql = "SELECT reference, public_key, default_group_id, flags, level, blocks_minted, blocks_minted_adjustment, blocks_minted_penalty, account FROM Accounts WHERE BITAND(flags, ?) != 0";

		List<AccountData> accounts = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, mask)) {
			if (resultSet == null)
				return accounts;

			do {
				byte[] reference = resultSet.getBytes(1);
				byte[] publicKey = resultSet.getBytes(2);
				int defaultGroupId = resultSet.getInt(3);
				int flags = resultSet.getInt(4);
				int level = resultSet.getInt(5);
				int blocksMinted = resultSet.getInt(6);
				int blocksMintedAdjustment = resultSet.getInt(7);
				int blocksMintedPenalty = resultSet.getInt(8);
				String address = resultSet.getString(9);

				accounts.add(new AccountData(address, reference, publicKey, defaultGroupId, flags, level, blocksMinted, blocksMintedAdjustment, blocksMintedPenalty));
			} while (resultSet.next());

			return accounts;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch flagged accounts from repository", e);
		}
	}

	@Override
	public List<AccountData> getPenaltyAccounts() throws DataException {
		String sql = "SELECT reference, public_key, default_group_id, flags, level, blocks_minted, blocks_minted_adjustment, blocks_minted_penalty, account FROM Accounts WHERE blocks_minted_penalty != 0";

		List<AccountData> accounts = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return accounts;

			do {
				byte[] reference = resultSet.getBytes(1);
				byte[] publicKey = resultSet.getBytes(2);
				int defaultGroupId = resultSet.getInt(3);
				int flags = resultSet.getInt(4);
				int level = resultSet.getInt(5);
				int blocksMinted = resultSet.getInt(6);
				int blocksMintedAdjustment = resultSet.getInt(7);
				int blocksMintedPenalty = resultSet.getInt(8);
				String address = resultSet.getString(9);

				accounts.add(new AccountData(address, reference, publicKey, defaultGroupId, flags, level, blocksMinted, blocksMintedAdjustment, blocksMintedPenalty));
			} while (resultSet.next());

			return accounts;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch penalty accounts from repository", e);
		}
	}

	@Override
	public byte[] getLastReference(String address) throws DataException {
		String sql = "SELECT reference FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			return resultSet.getBytes(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's last reference from repository", e);
		}
	}

	@Override
	public Integer getDefaultGroupId(String address) throws DataException {
		String sql = "SELECT default_group_id FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			// Column is NOT NULL so this should never implicitly convert to 0
			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's default groupID from repository", e);
		}
	}

	@Override
	public Integer getFlags(String address) throws DataException {
		String sql = "SELECT flags FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's flags from repository", e);
		}
	}

	@Override
	public Integer getLevel(String address) throws DataException {
		String sql = "SELECT level FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's level from repository", e);
		}
	}

	@Override
	public boolean accountExists(String address) throws DataException {
		try {
			return this.repository.exists("Accounts", "account = ?", address);
		} catch (SQLException e) {
			throw new DataException("Unable to check for account in repository", e);
		}
	}

	@Override
	public void ensureAccount(AccountData accountData) throws DataException {
		String sql = "INSERT IGNORE INTO Accounts (account, public_key) VALUES (?, ?)"; // MySQL syntax
		try {
			this.repository.executeCheckedUpdate(sql, accountData.getAddress(), accountData.getPublicKey());
		} catch (SQLException e) {
			throw new DataException("Unable to ensure minimal account in repository", e);
		}
	}

	@Override
	public void setLastReference(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("reference", accountData.getReference());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's last reference into repository", e);
		}
	}

	@Override
	public void setDefaultGroupId(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("default_group_id", accountData.getDefaultGroupId());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's default group ID into repository", e);
		}
	}

	@Override
	public void setFlags(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("flags", accountData.getFlags());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's flags into repository", e);
		}
	}

	@Override
	public void setLevel(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("level", accountData.getLevel());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's level into repository", e);
		}
	}

	@Override
	public void setBlocksMintedAdjustment(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress())
			.bind("blocks_minted_adjustment", accountData.getBlocksMintedAdjustment());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's blocks minted adjustment into repository", e);
		}
	}

	@Override
	public Integer getMintedBlockCount(String address) throws DataException {
		String sql = "SELECT blocks_minted FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's minted block count from repository", e);
		}
	}

	@Override
	public void setMintedBlockCount(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("blocks_minted", accountData.getBlocksMinted());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's minted block count into repository", e);
		}
	}

	@Override
	public int modifyMintedBlockCount(String address, int delta) throws DataException {
		String sql = "INSERT INTO Accounts (account, blocks_minted) VALUES (?, ?) " +
			"ON DUPLICATE KEY UPDATE blocks_minted = blocks_minted + ?";

		try {
			return this.repository.executeCheckedUpdate(sql, address, delta, delta);
		} catch (SQLException e) {
			throw new DataException("Unable to modify account's minted block count in repository", e);
		}
	}

	@Override
	public void modifyMintedBlockCounts(List<String> addresses, int delta) throws DataException {
		String sql = "INSERT INTO Accounts (account, blocks_minted) VALUES (?, ?) " +
				"ON DUPLICATE KEY UPDATE blocks_minted = blocks_minted + ?";

		List<Object[]> bindParamRows = addresses.stream().map(address -> new Object[] { address, delta, delta }).collect(Collectors.toList());

		try {
			this.repository.executeCheckedBatchUpdate(sql, bindParamRows);
		} catch (SQLException e) {
			throw new DataException("Unable to modify many account minted block counts in repository", e);
		}
	}

	@Override
	public Integer getBlocksMintedPenaltyCount(String address) throws DataException {
		String sql = "SELECT blocks_minted_penalty FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's block minted penalty count from repository", e);
		}
	}
	public void updateBlocksMintedPenalties(Set<AccountPenaltyData> accountPenalties) throws DataException {
		// Nothing to do?
		if (accountPenalties == null || accountPenalties.isEmpty())
			return;

		// Map balance changes into SQL bind params, filtering out no-op changes
		List<Object[]> updateBlocksMintedPenaltyParams = accountPenalties.stream()
				.map(accountPenalty -> new Object[] { accountPenalty.getAddress(), accountPenalty.getBlocksMintedPenalty(), accountPenalty.getBlocksMintedPenalty() })
				.collect(Collectors.toList());

		// Perform actual balance changes
		String sql = "INSERT INTO Accounts (account, blocks_minted_penalty) VALUES (?, ?) " +
				"ON DUPLICATE KEY UPDATE blocks_minted_penalty = blocks_minted_penalty + ?";
		try {
			this.repository.executeCheckedBatchUpdate(sql, updateBlocksMintedPenaltyParams);
		} catch (SQLException e) {
			throw new DataException("Unable to set blocks minted penalties in repository", e);
		}
	}

	@Override
	public void delete(String address) throws DataException {
		// NOTE: Account balances are deleted automatically by the database thanks to "ON DELETE CASCADE" in AccountBalances' FOREIGN KEY
		// definition.
		try {
			this.repository.delete("Accounts", "account = ?", address);
		} catch (SQLException e) {
			throw new DataException("Unable to delete account from repository", e);
		}
	}

	@Override
	public void tidy() throws DataException {
		try {
			this.repository.delete("AccountBalances", "balance = 0");
		} catch (SQLException e) {
			throw new DataException("Unable to tidy zero account balances from repository", e);
		}
	}

	// Account balances

	@Override
	public AccountBalanceData getBalance(String address, long assetId) throws DataException {
		String sql = "SELECT balance FROM AccountBalances WHERE account = ? AND asset_id = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address, assetId)) {
			if (resultSet == null)
				return null;

			long balance = resultSet.getLong(1);

			return new AccountBalanceData(address, assetId, balance);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account balance from repository", e);
		}
	}

	@Override
	public List<AccountBalanceData> getAssetBalances(long assetId, Boolean excludeZero) throws DataException {
		StringBuilder sql = new StringBuilder(1024);

		sql.append("SELECT account, balance FROM AccountBalances WHERE asset_id = ?");

		if (excludeZero != null && excludeZero)
			sql.append(" AND balance != 0");

		List<AccountBalanceData> accountBalances = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), assetId)) {
			if (resultSet == null)
				return accountBalances;

			do {
				String address = resultSet.getString(1);
				long balance = resultSet.getLong(2);

				accountBalances.add(new AccountBalanceData(address, assetId, balance));
			} while (resultSet.next());

			return accountBalances;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch asset balances from repository", e);
		}
	}

	@Override
	public List<AccountBalanceData> getAssetBalances(List<String> addresses, List<Long> assetIds, BalanceOrdering balanceOrdering, Boolean excludeZero,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(1024);

		sql.append("SELECT account, asset_id, balance, asset_name FROM ");

		final boolean haveAddresses = addresses != null && !addresses.isEmpty();
		final boolean haveAssetIds = assetIds != null && !assetIds.isEmpty();

		// Fill temporary table with filtering addresses/assetIDs
		if (haveAddresses)
			HSQLDBRepository.temporaryValuesTableSql(sql, addresses.size(), "TmpAccounts", "account");

		if (haveAssetIds) {
			if (haveAddresses)
				sql.append("CROSS JOIN ");

			HSQLDBRepository.temporaryValuesTableSql(sql, assetIds, "TmpAssetIds", "asset_id");
		}

		if (haveAddresses || haveAssetIds) {
			// Now use temporary table to filter AccountBalances (using index) and optional zero balance exclusion
			sql.append("JOIN AccountBalances ON ");

			if (haveAddresses)
				sql.append("AccountBalances.account = TmpAccounts.account ");

			if (haveAssetIds) {
				if (haveAddresses)
					sql.append("AND ");

				sql.append("AccountBalances.asset_id = TmpAssetIds.asset_id ");
			}

			if (!haveAddresses || (excludeZero != null && excludeZero))
				sql.append("AND AccountBalances.balance != 0 ");
		} else {
			// Simpler form if no filtering
			sql.append("AccountBalances ");

			// Zero balance exclusion comes later
		}

		// Join for asset name
		sql.append("JOIN Assets ON Assets.asset_id = AccountBalances.asset_id ");

		// Zero balance exclusion if no filtering
		if (!haveAddresses && !haveAssetIds && excludeZero != null && excludeZero)
			sql.append("WHERE AccountBalances.balance != 0 ");

		if (balanceOrdering != null) {
			String[] orderingColumns;
			switch (balanceOrdering) {
				case ACCOUNT_ASSET:
					orderingColumns = new String[] { "account", "asset_id" };
					break;

				case ASSET_ACCOUNT:
					orderingColumns = new String[] { "asset_id", "account" };
					break;

				case ASSET_BALANCE_ACCOUNT:
					orderingColumns = new String[] { "asset_id", "balance", "account" };
					break;

				default:
					throw new DataException(String.format("Unsupported asset balance result ordering: %s", balanceOrdering.name()));
			}

			sql.append("ORDER BY ");
			for (int oi = 0; oi < orderingColumns.length; ++oi) {
				if (oi != 0)
					sql.append(", ");

				sql.append(orderingColumns[oi]);
				if (reverse != null && reverse)
					sql.append(" DESC");
			}
		}

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		String[] addressesArray = addresses == null ? new String[0] : addresses.toArray(new String[addresses.size()]);
		List<AccountBalanceData> accountBalances = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), (Object[]) addressesArray)) {
			if (resultSet == null)
				return accountBalances;

			do {
				String address = resultSet.getString(1);
				long assetId = resultSet.getLong(2);
				long balance = resultSet.getLong(3);
				String assetName = resultSet.getString(4);

				accountBalances.add(new AccountBalanceData(address, assetId, balance, assetName));
			} while (resultSet.next());

			return accountBalances;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch asset balances from repository", e);
		}
	}

	@Override
	public void modifyAssetBalance(String address, long assetId, long deltaBalance) throws DataException {
		// If deltaBalance is zero then do nothing
		if (deltaBalance == 0)
			return;

		// If deltaBalance is negative then we assume AccountBalances & parent Accounts rows exist
		if (deltaBalance < 0) {
			// Perform actual balance change
			String sql = "UPDATE AccountBalances set balance = balance + ? WHERE account = ? AND asset_id = ?";
			try {
				this.repository.executeCheckedUpdate(sql, deltaBalance, address, assetId);
			} catch (SQLException e) {
				throw new DataException("Unable to reduce account balance in repository", e);
			}
		} else {
			// We have to ensure parent row exists to satisfy foreign key constraint
			try {
				String sql = "INSERT IGNORE INTO Accounts (account) VALUES (?)"; // MySQL syntax
				this.repository.executeCheckedUpdate(sql, address);
			} catch (SQLException e) {
				throw new DataException("Unable to ensure minimal account in repository", e);
			}

			// Perform actual balance change
			String sql = "INSERT INTO AccountBalances (account, asset_id, balance) VALUES (?, ?, ?) " +
				"ON DUPLICATE KEY UPDATE balance = balance + ?";
			try {
				this.repository.executeCheckedUpdate(sql, address, assetId, deltaBalance, deltaBalance);
			} catch (SQLException e) {
				throw new DataException("Unable to increase account balance in repository", e);
			}
		}
	}

	public void modifyAssetBalances(List<AccountBalanceData> accountBalanceDeltas) throws DataException {
		// Nothing to do?
		if (accountBalanceDeltas == null || accountBalanceDeltas.isEmpty())
			return;

		// Map balance changes into SQL bind params, filtering out no-op changes
		List<Object[]> modifyBalanceParams = accountBalanceDeltas.stream()
				.filter(accountBalance -> accountBalance.getBalance() != 0L)
				.map(accountBalance -> new Object[] { accountBalance.getAddress(), accountBalance.getAssetId(), accountBalance.getBalance(), accountBalance.getBalance() })
				.collect(Collectors.toList());

		// Before we modify balances, ensure parent accounts exist
		String ensureSql = "INSERT IGNORE INTO Accounts (account) VALUES (?)"; // MySQL syntax
		try {
			this.repository.executeCheckedBatchUpdate(ensureSql, modifyBalanceParams.stream().map(objects -> new Object[] { objects[0] }).collect(Collectors.toList()));
		} catch (SQLException e) {
			throw new DataException("Unable to ensure minimal accounts in repository", e);
		}

		// Perform actual balance changes
		String sql = "INSERT INTO AccountBalances (account, asset_id, balance) VALUES (?, ?, ?) " +
			"ON DUPLICATE KEY UPDATE balance = balance + ?";
		try {
			this.repository.executeCheckedBatchUpdate(sql, modifyBalanceParams);
		} catch (SQLException e) {
			throw new DataException("Unable to modify account balances in repository", e);
		}
	}


	@Override
	public void setAssetBalances(List<AccountBalanceData> accountBalances) throws DataException {
		// Nothing to do?
		if (accountBalances == null || accountBalances.isEmpty())
			return;

		/*
		 * Split workload into zero and non-zero balances,
		 * checking for negative balances as we progress.
		 */

		List<Object[]> zeroAccountBalanceParams = new ArrayList<>();
		List<Object[]> nonZeroAccountBalanceParams = new ArrayList<>();

		for (AccountBalanceData accountBalanceData : accountBalances) {
			final long balance = accountBalanceData.getBalance();

			if (balance < 0)
				throw new DataException(String.format("Refusing to set negative balance %s [assetId %d] for %s",
						prettyAmount(balance), accountBalanceData.getAssetId(), accountBalanceData.getAddress()));

			if (balance == 0)
				zeroAccountBalanceParams.add(new Object[] { accountBalanceData.getAddress(), accountBalanceData.getAssetId() });
			else
				nonZeroAccountBalanceParams.add(new Object[] { accountBalanceData.getAddress(), accountBalanceData.getAssetId(), balance, balance });
		}

		// Batch update (actually delete) of zero balances
		try {
			this.repository.deleteBatch("AccountBalances", "account = ? AND asset_id = ?", zeroAccountBalanceParams);
		} catch (SQLException e) {
			throw new DataException("Unable to delete account balances from repository", e);
		}

		// Before we set new balances, ensure parent accounts exist
		String ensureSql = "INSERT IGNORE INTO Accounts (account) VALUES (?)"; // MySQL syntax
		try {
			this.repository.executeCheckedBatchUpdate(ensureSql, nonZeroAccountBalanceParams.stream().map(objects -> new Object[] { objects[0] }).collect(Collectors.toList()));
		} catch (SQLException e) {
			throw new DataException("Unable to ensure minimal accounts in repository", e);
		}

		// Now set all balances in one go
		String setSql = "INSERT INTO AccountBalances (account, asset_id, balance) VALUES (?, ?, ?) " +
				"ON DUPLICATE KEY UPDATE balance = ?";
		try {
			this.repository.executeCheckedBatchUpdate(setSql, nonZeroAccountBalanceParams);
		} catch (SQLException e) {
			throw new DataException("Unable to set account balances in repository", e);
		}
	}

	@Override
	public void save(AccountBalanceData accountBalanceData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("AccountBalances");

		saveHelper.bind("account", accountBalanceData.getAddress()).bind("asset_id", accountBalanceData.getAssetId())
				.bind("balance", accountBalanceData.getBalance());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account balance into repository", e);
		}
	}

	@Override
	public void delete(String address, long assetId) throws DataException {
		try {
			this.repository.delete("AccountBalances", "account = ? AND asset_id = ?", address, assetId);
		} catch (SQLException e) {
			throw new DataException("Unable to delete account balance from repository", e);
		}
	}

	// Reward-Share

	@Override
	public RewardShareData getRewardShare(byte[] minterPublicKey, String recipient) throws DataException {
		String sql = "SELECT minter, reward_share_public_key, share_percent FROM RewardShares WHERE minter_public_key = ? AND recipient = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, minterPublicKey, recipient)) {
			if (resultSet == null)
				return null;

			String minter = resultSet.getString(1);
			byte[] rewardSharePublicKey = resultSet.getBytes(2);
			int sharePercent = resultSet.getInt(3);

			return new RewardShareData(minterPublicKey, minter, recipient, rewardSharePublicKey, sharePercent);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-share info from repository", e);
		}
	}

	@Override
	public RewardShareData getRewardShare(byte[] rewardSharePublicKey) throws DataException {
		String sql = "SELECT minter_public_key, minter, recipient, share_percent FROM RewardShares WHERE reward_share_public_key = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, rewardSharePublicKey)) {
			if (resultSet == null)
				return null;

			byte[] minterPublicKey = resultSet.getBytes(1);
			String minter = resultSet.getString(2);
			String recipient = resultSet.getString(3);
			int sharePercent = resultSet.getInt(4);

			return new RewardShareData(minterPublicKey, minter, recipient, rewardSharePublicKey, sharePercent);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-share info from repository", e);
		}
	}

	@Override
	public List<byte[]> getRewardSharePublicKeys() throws DataException {
		String sql = "SELECT reward_share_public_key FROM RewardShares ORDER BY reward_share_public_key";

		List<byte[]> rewardSharePublicKeys = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return null;

			do {
				byte[] rewardSharePublicKey = resultSet.getBytes(1);
				rewardSharePublicKeys.add(rewardSharePublicKey);
			} while (resultSet.next());

			return rewardSharePublicKeys;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-share public keys from repository", e);
		}
	}

	@Override
	public boolean isRewardSharePublicKey(byte[] publicKey) throws DataException {
		try {
			return this.repository.exists("RewardShares", "reward_share_public_key = ?", publicKey);
		} catch (SQLException e) {
			throw new DataException("Unable to check for reward-share public key in repository", e);
		}
	}

	@Override
	public int countRewardShares(byte[] minterPublicKey) throws DataException {
		String sql = "SELECT COUNT(*) FROM RewardShares WHERE minter_public_key = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, minterPublicKey)) {
			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count reward-shares in repository", e);
		}
	}

	@Override
	public int countSelfShares(byte[] minterPublicKey) throws DataException {
		String sql = "SELECT COUNT(*) FROM RewardShares WHERE minter_public_key = ? AND minter = recipient";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, minterPublicKey)) {
			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count self-shares in repository", e);
		}
	}

	@Override
	public List<RewardShareData> getRewardShares() throws DataException {
		String sql = "SELECT minter_public_key, minter, recipient, share_percent, reward_share_public_key FROM RewardShares";

		List<RewardShareData> rewardShares = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return rewardShares;

			do {
				byte[] minterPublicKey = resultSet.getBytes(1);
				String minter = resultSet.getString(2);
				String recipient = resultSet.getString(3);
				int sharePercent = resultSet.getInt(4);
				byte[] rewardSharePublicKey = resultSet.getBytes(5);

				rewardShares.add(new RewardShareData(minterPublicKey, minter, recipient, rewardSharePublicKey, sharePercent));
			} while (resultSet.next());

			return rewardShares;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-shares from repository", e);
		}
	}

	@Override
	public List<RewardShareData> findRewardShares(List<String> minters, List<String> recipients, List<String> involvedAddresses,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT DISTINCT minter_public_key, minter, recipient, share_percent, reward_share_public_key FROM RewardShares ");

		List<Object> args = new ArrayList<>();

		final boolean hasRecipients = recipients != null && !recipients.isEmpty();
		final boolean hasMinters = minters != null && !minters.isEmpty();
		final boolean hasInvolved = involvedAddresses != null && !involvedAddresses.isEmpty();

		if (hasMinters || hasInvolved)
			sql.append("JOIN Accounts ON Accounts.public_key = RewardShares.minter_public_key ");

		if (hasRecipients) {
			sql.append("JOIN (VALUES ");

			final int recipientsSize = recipients.size();
			for (int ri = 0; ri < recipientsSize; ++ri) {
				if (ri != 0)
					sql.append(", ");

				sql.append("(?)");
			}

			sql.append(") AS Recipients (address) ON RewardShares.recipient = Recipients.address ");
			args.addAll(recipients);
		}

		if (hasMinters) {
			sql.append("JOIN (VALUES ");

			final int mintersSize = minters.size();
			for (int fi = 0; fi < mintersSize; ++fi) {
				if (fi != 0)
					sql.append(", ");

				sql.append("(?)");
			}

			sql.append(") AS Minters (address) ON Accounts.account = Minters.address ");
			args.addAll(minters);
		}

		if (hasInvolved) {
			sql.append("JOIN (VALUES ");

			final int involvedAddressesSize = involvedAddresses.size();
			for (int iai = 0; iai < involvedAddressesSize; ++iai) {
				if (iai != 0)
					sql.append(", ");

				sql.append("(?)");
			}

			sql.append(") AS Involved (address) ON Involved.address IN (RewardShares.recipient, Accounts.account) ");
			args.addAll(involvedAddresses);
		}

		sql.append("ORDER BY recipient, share_percent");
		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<RewardShareData> rewardShares = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), args.toArray())) {
			if (resultSet == null)
				return rewardShares;

			do {
				byte[] minterPublicKey = resultSet.getBytes(1);
				String minter = resultSet.getString(2);
				String recipient = resultSet.getString(3);
				int sharePercent = resultSet.getInt(4);
				byte[] rewardSharePublicKey = resultSet.getBytes(5);

				rewardShares.add(new RewardShareData(minterPublicKey, minter, recipient, rewardSharePublicKey, sharePercent));
			} while (resultSet.next());

			return rewardShares;
		} catch (SQLException e) {
			throw new DataException("Unable to find reward-shares in repository", e);
		}
	}

	@Override
	public Integer getRewardShareIndex(byte[] rewardSharePublicKey) throws DataException {
		if (!this.rewardShareExists(rewardSharePublicKey))
			return null;

		String sql = "SELECT COUNT(*) FROM RewardShares WHERE reward_share_public_key < ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, rewardSharePublicKey)) {
			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to determine reward-share index in repository", e);
		}
	}

	@Override
	public RewardShareData getRewardShareByIndex(int index) throws DataException {
		String sql = "SELECT minter_public_key, minter, recipient, share_percent, reward_share_public_key FROM RewardShares "
				+ "ORDER BY reward_share_public_key ASC "
				+ "OFFSET ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, index)) {
			if (resultSet == null)
				return null;

			byte[] minterPublicKey = resultSet.getBytes(1);
			String minter = resultSet.getString(2);
			String recipient = resultSet.getString(3);
			int sharePercent = resultSet.getInt(4);
			byte[] rewardSharePublicKey = resultSet.getBytes(5);

			return new RewardShareData(minterPublicKey, minter, recipient, rewardSharePublicKey, sharePercent);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch indexed reward-share from repository", e);
		}
	}

	@Override
	public List<RewardShareData> getRewardSharesByIndexes(int[] indexes) throws DataException {
		String sql = "SELECT minter_public_key, minter, recipient, share_percent, reward_share_public_key FROM RewardShares "
				+ "ORDER BY reward_share_public_key ASC";

		if (indexes == null)
			return null;

		List<RewardShareData> rewardShares = new ArrayList<>();
		if (indexes.length == 0)
			return rewardShares;

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return null;

			int rowNum = 1;
			for (int i = 0; i < indexes.length; ++i) {
				final int index = indexes[i];

				while (rowNum < index + 1) { // +1 because in JDBC, first row is row 1
					if (!resultSet.next())
						// Index is out of bounds
						return null;

					++rowNum;
				}

				byte[] minterPublicKey = resultSet.getBytes(1);
				String minter = resultSet.getString(2);
				String recipient = resultSet.getString(3);
				int sharePercent = resultSet.getInt(4);
				byte[] rewardSharePublicKey = resultSet.getBytes(5);

				RewardShareData rewardShareData = new RewardShareData(minterPublicKey, minter, recipient, rewardSharePublicKey, sharePercent);

				rewardShares.add(rewardShareData);
			}

			return rewardShares;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch indexed reward-shares from repository", e);
		}
	}

	@Override
	public boolean rewardShareExists(byte[] rewardSharePublicKey) throws DataException {
		try {
			return this.repository.exists("RewardShares", "reward_share_public_key = ?", rewardSharePublicKey);
		} catch (SQLException e) {
			throw new DataException("Unable to check reward-share exists in repository", e);
		}
	}

	@Override
	public void save(RewardShareData rewardShareData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("RewardShares");

		saveHelper.bind("minter_public_key", rewardShareData.getMinterPublicKey()).bind("minter", rewardShareData.getMinter())
			.bind("recipient", rewardShareData.getRecipient()).bind("reward_share_public_key", rewardShareData.getRewardSharePublicKey())
			.bind("share_percent", rewardShareData.getSharePercent());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save reward-share info into repository", e);
		}
	}

	@Override
	public void delete(byte[] minterPublickey, String recipient) throws DataException {
		try {
			this.repository.delete("RewardShares", "minter_public_key = ? and recipient = ?", minterPublickey, recipient);
		} catch (SQLException e) {
			throw new DataException("Unable to delete reward-share info from repository", e);
		}
	}

	// Minting accounts used by BlockMinter

	@Override
	public List<MintingAccountData> getMintingAccounts() throws DataException {
		List<MintingAccountData> mintingAccounts = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute("SELECT minter_private_key, minter_public_key FROM MintingAccounts")) {
			if (resultSet == null)
				return mintingAccounts;

			do {
				byte[] minterPrivateKey = resultSet.getBytes(1);
				byte[] minterPublicKey = resultSet.getBytes(2);

				mintingAccounts.add(new MintingAccountData(minterPrivateKey, minterPublicKey));
			} while (resultSet.next());

			return mintingAccounts;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch minting accounts from repository", e);
		}
	}

	@Override
	public MintingAccountData getMintingAccount(byte[] mintingAccountKey) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT minter_private_key, minter_public_key " +
				"FROM MintingAccounts WHERE minter_private_key = ? OR minter_public_key = ?",
				mintingAccountKey, mintingAccountKey)) {

			if (resultSet == null)
				return null;

				byte[] minterPrivateKey = resultSet.getBytes(1);
				byte[] minterPublicKey = resultSet.getBytes(2);

				return new MintingAccountData(minterPrivateKey, minterPublicKey);

		} catch (SQLException e) {
			throw new DataException("Unable to fetch minting accounts from repository", e);
		}
	}

	@Override
	public void save(MintingAccountData mintingAccountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("MintingAccounts");

		saveHelper.bind("minter_private_key", mintingAccountData.getPrivateKey())
			.bind("minter_public_key", mintingAccountData.getPublicKey());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save minting account into repository", e);
		}
	}

	@Override
	public int delete(byte[] minterKey) throws DataException {
		try {
			return this.repository.delete("MintingAccounts", "minter_private_key = ? OR minter_public_key = ?", minterKey, minterKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete minting account from repository", e);
		}
	}

	// Managing QORT from legacy QORA

	@Override
	public List<EligibleQoraHolderData> getEligibleLegacyQoraHolders(Integer blockHeight) throws DataException {
		StringBuilder sql = new StringBuilder(1024);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT account, Qora.balance, QortFromQora.balance, final_qort_from_qora, final_block_height ");
		sql.append("FROM AccountBalances AS Qora ");
		sql.append("LEFT OUTER JOIN AccountQortFromQoraInfo USING (account) ");
		sql.append("LEFT OUTER JOIN AccountBalances AS QortFromQora ON QortFromQora.account = Qora.account AND QortFromQora.asset_id = ");
		sql.append(Asset.QORT_FROM_QORA); // int is safe to use literally
		sql.append(" WHERE Qora.asset_id = ");
		sql.append(Asset.LEGACY_QORA); // int is safe to use literally
		sql.append(" AND (final_block_height IS NULL");

		if (blockHeight != null) {
			sql.append(" OR final_block_height >= ?");
			bindParams.add(blockHeight);
		}

		sql.append(")");

		List<EligibleQoraHolderData> eligibleLegacyQoraHolders = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return eligibleLegacyQoraHolders;

			do {
				String address = resultSet.getString(1);
				long qoraBalance = resultSet.getLong(2);
				long qortFromQoraBalance = resultSet.getLong(3);

				Long finalQortFromQora = resultSet.getLong(4);
				if (finalQortFromQora == 0 && resultSet.wasNull())
					finalQortFromQora = null;

				Integer finalBlockHeight = resultSet.getInt(5);
				if (finalBlockHeight == 0 && resultSet.wasNull())
					finalBlockHeight = null;

				eligibleLegacyQoraHolders.add(new EligibleQoraHolderData(address, qoraBalance, qortFromQoraBalance, finalQortFromQora, finalBlockHeight));
			} while (resultSet.next());

			return eligibleLegacyQoraHolders;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch eligible legacy QORA holders from repository", e);
		}
	}

	@Override
	public QortFromQoraData getQortFromQoraInfo(String address) throws DataException {
		String sql = "SELECT final_qort_from_qora, final_block_height FROM AccountQortFromQoraInfo WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			long finalQortFromQora = resultSet.getLong(1);
			Integer finalBlockHeight = resultSet.getInt(2);
			if (finalBlockHeight == 0 && resultSet.wasNull())
				finalBlockHeight = null;

			return new QortFromQoraData(address, finalQortFromQora, finalBlockHeight);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account qort-from-qora info from repository", e);
		}
	}

	@Override
	public void save(QortFromQoraData qortFromQoraData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("AccountQortFromQoraInfo");

		saveHelper.bind("account", qortFromQoraData.getAddress())
		.bind("final_qort_from_qora", qortFromQoraData.getFinalQortFromQora())
		.bind("final_block_height", qortFromQoraData.getFinalBlockHeight());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account qort-from-qora info into repository", e);
		}
	}

	@Override
	public int deleteQortFromQoraInfo(String address) throws DataException {
		try {
			return this.repository.delete("AccountQortFromQoraInfo", "account = ?", address);
		} catch (SQLException e) {
			throw new DataException("Unable to delete qort-from-qora info from repository", e);
		}
	}

	@Override
	public SponsorshipReport getSponsorshipReport(String address, String[] realRewardShareRecipients) throws DataException {

		List<String> sponsees = getSponseeAddresses(address, realRewardShareRecipients);

		return getMintershipReport(address, account -> sponsees);
	}

	@Override
	public SponsorshipReport getMintershipReport(String account, Function<String, List<String>> addressFetcher) throws DataException {

		try {
			ResultSet accountResultSet = getAccountResultSet(account);

			if( accountResultSet == null ) throw new DataException("Unable to fetch account info from repository");

			int level = accountResultSet.getInt(2);
			int blocksMinted = accountResultSet.getInt(3);
			int adjustments = accountResultSet.getInt(4);
			int penalties = accountResultSet.getInt(5);
			boolean transferPrivs = accountResultSet.getBoolean(6);

			List<String> sponseeAddresses = addressFetcher.apply(account);

			if( sponseeAddresses.isEmpty() ){
				return new SponsorshipReport(account, level, blocksMinted, adjustments, penalties, transferPrivs, new String[0], 0,  0,0, 0, 0, 0, 0, 0, 0, 0);
			}
			else {
				return produceSponsorShipReport(account, level, blocksMinted, adjustments, penalties, sponseeAddresses, transferPrivs);
			}
		}
		 catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			throw new DataException("Unable to fetch account info from repository", e);
		}
	}

	@Override
	public List<String> getSponseeAddresses(String account, String[] realRewardShareRecipients) throws DataException {
		StringBuffer sponseeSql = new StringBuffer();

		sponseeSql.append( "SELECT DISTINCT t.recipient sponsees " );
		sponseeSql.append( "FROM REWARDSHARETRANSACTIONS t ");
		sponseeSql.append( "INNER JOIN ACCOUNTS a on t.minter_public_key = a.public_key ");
		sponseeSql.append( "WHERE account = ? and t.recipient != a.account");

		try {
			ResultSet sponseeResultSet;

			// if there are real reward share recipeints to exclude
			if (realRewardShareRecipients != null && realRewardShareRecipients.length > 0) {

				// add constraint to where clause
				sponseeSql.append(" and t.recipient NOT IN (");
				sponseeSql.append(String.join(", ", Collections.nCopies(realRewardShareRecipients.length, "?")));
				sponseeSql.append(")");

				// Create a new array to hold both
				String[] combinedArray = new String[realRewardShareRecipients.length + 1];

				// Add the single string to the first position
				combinedArray[0] = account;

				// Copy the elements from realRewardShareRecipients to the combinedArray starting from index 1
				System.arraycopy(realRewardShareRecipients, 0, combinedArray, 1, realRewardShareRecipients.length);

				sponseeResultSet = this.repository.checkedExecute(sponseeSql.toString(), combinedArray);
			}
			else {
				sponseeResultSet = this.repository.checkedExecute(sponseeSql.toString(), account);
			}

			List<String> sponseeAddresses;

			if( sponseeResultSet == null ) {
				sponseeAddresses = new ArrayList<>(0);
			}
			else {
				sponseeAddresses = new ArrayList<>();

				do {
					sponseeAddresses.add(sponseeResultSet.getString(1));
				} while (sponseeResultSet.next());
			}

			return sponseeAddresses;
		}
		catch (SQLException e) {
			throw new DataException("can't get sponsees from blockchain data", e);
		}
	}

	@Override
	public Optional<String> getSponsor(String address) throws DataException {

		StringBuffer sponsorSql = new StringBuffer();

		sponsorSql.append( "SELECT DISTINCT account, level, blocks_minted, blocks_minted_adjustment, blocks_minted_penalty ");
		sponsorSql.append( "FROM REWARDSHARETRANSACTIONS t ");
		sponsorSql.append( "INNER JOIN ACCOUNTS a on a.public_key = t.minter_public_key ");
		sponsorSql.append( "WHERE recipient = ? and recipient != account ");

		try {
			ResultSet sponseeResultSet = this.repository.checkedExecute(sponsorSql.toString(), address);

			if( sponseeResultSet == null ){
				return Optional.empty();
			}
			else {
				return Optional.ofNullable( sponseeResultSet.getString(1));
			}
		} catch (SQLException e) {
			throw new DataException("can't get sponsor from blockchain data", e);
		}
	}

	@Override
	public List<AddressLevelPairing> getAddressLevelPairings(int minLevel) throws DataException {

		StringBuffer accLevelSql = new StringBuffer(51);

		accLevelSql.append( "SELECT account,level FROM ACCOUNTS WHERE level >= ?" );

		try {
			ResultSet accountLevelResultSet = this.repository.checkedExecute(accLevelSql.toString(),minLevel);

			List<AddressLevelPairing> addressLevelPairings;

			if( accountLevelResultSet == null ) {
				addressLevelPairings = new ArrayList<>(0);
			}
			else {
				addressLevelPairings = new ArrayList<>();

				do {
					AddressLevelPairing pairing
						= new AddressLevelPairing(
							accountLevelResultSet.getString(1),
							accountLevelResultSet.getInt(2)
					);
					addressLevelPairings.add(pairing);
				} while (accountLevelResultSet.next());
			}
			return addressLevelPairings;
		} catch (SQLException e) {
			throw new DataException("Can't get addresses for this level from blockchain data", e);
		}
	}

	/**
	 * Produce Sponsorship Report
	 *
	 * @param address                the account address for the sponsor
	 * @param level                  the sponsor's level
	 * @param blocksMinted           the blocks minted by the sponsor
	 * @param blocksMintedAdjustment
	 * @param blocksMintedPenalty
	 * @param sponseeAddresses
	 * @param transferPrivs true if this account was involved in a TRANSFER_PRIVS transaction
	 * @return the report
	 * @throws SQLException
	 */
	private SponsorshipReport produceSponsorShipReport(
			String address,
			int level,
			int blocksMinted,
			int blocksMintedAdjustment,
			int blocksMintedPenalty,
			List<String> sponseeAddresses,
			boolean transferPrivs) throws SQLException, DataException {

		int sponseeCount = sponseeAddresses.size();

		// get the registered names of the sponsees
		ResultSet namesResultSet = getNamesResultSet(sponseeAddresses, sponseeCount);

		List<String> sponseeNames;

		if( namesResultSet != null ) {
			sponseeNames = getNames(namesResultSet, sponseeCount);
		}
		else {
			sponseeNames = new ArrayList<>(0);
		}

		// get the average balance of the sponsees
		ResultSet avgBalanceResultSet = getAverageBalanceResultSet(sponseeAddresses, sponseeCount);
		int avgBalance = avgBalanceResultSet.getInt(1);

		// count the arbitrary and transfer asset transactions for all sponsees
		ResultSet txTypeResultSet = getTxTypeResultSet(sponseeAddresses, sponseeCount);

		int arbitraryCount;
		int transferAssetCount;
		int transferPrivsCount;

		if( txTypeResultSet != null) {

			Map<Integer, Integer> countsByType = new HashMap<>(2);

			do{
				Integer type = txTypeResultSet.getInt(1);

				if( type != null ) {
					countsByType.put(type, txTypeResultSet.getInt(2));
				}
			} while( txTypeResultSet.next());

			arbitraryCount = countsByType.getOrDefault(10, 0);
			transferAssetCount = countsByType.getOrDefault(12, 0);
			transferPrivsCount = countsByType.getOrDefault(40, 0);
		}
		// no rows -> no counts
		else {
			arbitraryCount = 0;
			transferAssetCount = 0;
			transferPrivsCount = 0;
		}

		ResultSet sellResultSet = getSellResultSet(sponseeAddresses, sponseeCount);

		int sellCount;
		int	sellAmount;

		// if there are sell results, then fill in the sell amount/counts
		if( sellResultSet != null ) {
			sellCount = sellResultSet.getInt(1);
			sellAmount = sellResultSet.getInt(2);
		}
		// no rows -> no counts/amounts
		else {
			sellCount = 0;
			sellAmount = 0;
		}

		ResultSet buyResultSet = getBuyResultSet(sponseeAddresses, sponseeCount);

		int buyCount;
		int buyAmount;

		// if there are buy results, then fill in the buy amount/counts
		if( buyResultSet != null ) {
			buyCount = buyResultSet.getInt(1);
			buyAmount = buyResultSet.getInt(2);
		}
		// no rows -> no counts/amounts
		else {
			buyCount = 0;
			buyAmount = 0;
		}

		return new SponsorshipReport(
				address,
				level,
				blocksMinted,
				blocksMintedAdjustment,
				blocksMintedPenalty,
				transferPrivs,
				sponseeNames.toArray(new String[sponseeNames.size()]),
				sponseeCount,
				sponseeCount - sponseeNames.size(),
				avgBalance,
				arbitraryCount,
				transferAssetCount,
				transferPrivsCount,
				sellCount,
				sellAmount,
				buyCount,
				buyAmount);
	}

	private ResultSet getBuyResultSet(List<String> addresses, int addressCount) throws SQLException {

		StringBuffer sql = new StringBuffer();
		sql.append("SELECT COUNT(*) count, SUM(amount)/100000000 amount ");
		sql.append("FROM ACCOUNTS a ");
		sql.append("INNER JOIN ATTRANSACTIONS tx ON tx.recipient = a.account ");
		sql.append("INNER JOIN ATS ats ON ats.at_address = tx.at_address ");
		sql.append("WHERE a.account IN ( ");
		sql.append(String.join(", ", Collections.nCopies(addressCount, "?")));
		sql.append(") ");
		sql.append("AND a.account = tx.recipient AND a.public_key != ats.creator AND asset_id = 0 ");
		String[] sponsees = addresses.toArray(new String[addressCount]);
		ResultSet buySellResultSet = this.repository.checkedExecute(sql.toString(), sponsees);

		return buySellResultSet;
	}

	private ResultSet getSellResultSet(List<String> addresses, int addressCount) throws SQLException {

		StringBuffer sql = new StringBuffer();
		sql.append("SELECT COUNT(*) count, SUM(amount)/100000000 amount ");
		sql.append("FROM ATS ats ");
		sql.append("INNER JOIN ACCOUNTS a ON a.public_key = ats.creator ");
		sql.append("INNER JOIN ATTRANSACTIONS tx ON tx.at_address = ats.at_address ");
		sql.append("WHERE a.account IN ( ");
		sql.append(String.join(", ", Collections.nCopies(addressCount, "?")));
		sql.append(") ");
		sql.append("AND a.account != tx.recipient AND asset_id = 0 ");
		String[] sponsees = addresses.toArray(new String[addressCount]);

		return this.repository.checkedExecute(sql.toString(), sponsees);
	}

	private ResultSet getAccountResultSet(String account) throws SQLException {

		StringBuffer accountSql = new StringBuffer();

		accountSql.append( "SELECT DISTINCT a.account, a.level, a.blocks_minted, a.blocks_minted_adjustment, a.blocks_minted_penalty, tx.sender IS NOT NULL as transfer ");
		accountSql.append( "FROM ACCOUNTS a ");
		accountSql.append( "LEFT JOIN TRANSFERPRIVSTRANSACTIONS tx on a.public_key = tx.sender or a.account = tx.recipient ");
		accountSql.append( "WHERE account = ? ");

		ResultSet accountResultSet = this.repository.checkedExecute( accountSql.toString(), account);

		return accountResultSet;
	}


	private ResultSet getTxTypeResultSet(List<String> sponseeAddresses, int sponseeCount) throws SQLException {
		StringBuffer txTypeTotalsSql = new StringBuffer();
		// Transaction Types, int values
		// ARBITRARY = 10
		// TRANSFER_ASSET = 12
		// txTypeTotalsSql.append("
		txTypeTotalsSql.append("SELECT type, count(*) ");
		txTypeTotalsSql.append("FROM TRANSACTIONPARTICIPANTS ");
		txTypeTotalsSql.append("INNER JOIN TRANSACTIONS USING (signature) ");
		txTypeTotalsSql.append("where participant in ( ");
		txTypeTotalsSql.append(String.join(", ", Collections.nCopies(sponseeCount, "?")));
		txTypeTotalsSql.append(") and type in (10, 12, 40) ");
		txTypeTotalsSql.append("group by type order by type");

		String[] sponsees = sponseeAddresses.toArray(new String[sponseeCount]);
		ResultSet txTypeResultSet = this.repository.checkedExecute(txTypeTotalsSql.toString(), sponsees);
		return txTypeResultSet;
	}

	private ResultSet getAverageBalanceResultSet(List<String> sponseeAddresses, int sponseeCount) throws SQLException {
		StringBuffer avgBalanceSql = new StringBuffer();
		avgBalanceSql.append("SELECT avg(balance)/100000000 FROM ACCOUNTBALANCES ");
		avgBalanceSql.append("WHERE account in (");
		avgBalanceSql.append(String.join(", ", Collections.nCopies(sponseeCount, "?")));
		avgBalanceSql.append(") and ASSET_ID = 0");

		String[] sponsees = sponseeAddresses.toArray(new String[sponseeCount]);
		return this.repository.checkedExecute(avgBalanceSql.toString(), sponsees);
	}

	/**
	 * Get Names
	 *
	 * @param namesResultSet the result set to get the names from, can't be null
	 * @param count the number of potential names
	 *
	 * @return the names
	 *
	 * @throws SQLException
	 */
	private static List<String> getNames(ResultSet namesResultSet, int count) throws SQLException {

		List<String> names = new ArrayList<>(count);

		do{
			String name = namesResultSet.getString(1);

			if( name != null ) {
				names.add(name);
			}
		} while( namesResultSet.next() );

		return names;
	}

	private ResultSet getNamesResultSet(List<String> sponseeAddresses, int sponseeCount) throws SQLException {
		StringBuffer namesSql = new StringBuffer();
		namesSql.append("SELECT name FROM NAMES ");
		namesSql.append("WHERE owner in (");
		namesSql.append(String.join(", ", Collections.nCopies(sponseeCount, "?")));
		namesSql.append(")");

		String[] sponsees = sponseeAddresses.toArray(new String[sponseeCount]);
		ResultSet namesResultSet = this.repository.checkedExecute(namesSql.toString(), sponsees);
		return namesResultSet;
	}
}