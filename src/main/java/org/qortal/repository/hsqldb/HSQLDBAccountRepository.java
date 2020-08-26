package org.qortal.repository.hsqldb;

import static org.qortal.utils.Amounts.prettyAmount;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.qortal.asset.Asset;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.EligibleQoraHolderData;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.QortFromQoraData;
import org.qortal.data.account.RewardShareData;
import org.qortal.repository.AccountRepository;
import org.qortal.repository.DataException;

public class HSQLDBAccountRepository implements AccountRepository {

	protected HSQLDBRepository repository;

	public HSQLDBAccountRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	// General account

	@Override
	public AccountData getAccount(String address) throws DataException {
		String sql = "SELECT reference, public_key, default_group_id, flags, level, blocks_minted, blocks_minted_adjustment FROM Accounts WHERE account = ?";

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

			return new AccountData(address, reference, publicKey, defaultGroupId, flags, level, blocksMinted, blocksMintedAdjustment);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account info from repository", e);
		}
	}

	@Override
	public List<AccountData> getFlaggedAccounts(int mask) throws DataException {
		String sql = "SELECT reference, public_key, default_group_id, flags, level, blocks_minted, blocks_minted_adjustment, account FROM Accounts WHERE BITAND(flags, ?) != 0";

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
				String address = resultSet.getString(8);

				accounts.add(new AccountData(address, reference, publicKey, defaultGroupId, flags, level, blocksMinted, blocksMintedAdjustment));
			} while (resultSet.next());

			return accounts;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch flagged accounts from repository", e);
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
		sql.append("SELECT account, Qora.balance, QortFromQora.balance, final_qort_from_qora, final_block_height ");
		sql.append("FROM AccountBalances AS Qora ");
		sql.append("LEFT OUTER JOIN AccountQortFromQoraInfo USING (account) ");
		sql.append("LEFT OUTER JOIN AccountBalances AS QortFromQora ON QortFromQora.account = Qora.account AND QortFromQora.asset_id = ");
		sql.append(Asset.QORT_FROM_QORA); // int is safe to use literally
		sql.append(" WHERE Qora.asset_id = ");
		sql.append(Asset.LEGACY_QORA); // int is safe to use literally
		sql.append(" AND (final_block_height IS NULL");

		if (blockHeight != null) {
			sql.append(" OR final_block_height >= ");
			sql.append(blockHeight);
		}

		sql.append(")");

		List<EligibleQoraHolderData> eligibleLegacyQoraHolders = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
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

}
