package org.qortal.repository;

import org.qortal.data.account.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public interface AccountRepository {

	// General account

	/** Returns all general information about account, e.g. public key, last reference, default group ID. */
	public AccountData getAccount(String address) throws DataException;

	/** Returns accounts with <b>any</b> bit set in given mask. */
	public List<AccountData> getFlaggedAccounts(int mask) throws DataException;

	/** Returns accounts with a blockedMintedPenalty */
	public List<AccountData> getPenaltyAccounts() throws DataException;

	/** Returns account's last reference or null if not set or account not found. */
	public byte[] getLastReference(String address) throws DataException;

	/** Returns account's default groupID or null if account not found. */
	public Integer getDefaultGroupId(String address) throws DataException;

	/** Returns account's flags or null if account not found. */
	public Integer getFlags(String address) throws DataException;

	/** Returns account's level or null if account not found. */
	public Integer getLevel(String address) throws DataException;

	/** Returns whether account exists. */
	public boolean accountExists(String address) throws DataException;

	/**
	 * Ensures at least minimal account info in repository.
	 * <p>
	 * Saves account address, and public key if present.
	 */
	public void ensureAccount(AccountData accountData) throws DataException;

	/**
	 * Saves account's last reference, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like default group ID.
	 */
	public void setLastReference(AccountData accountData) throws DataException;

	/**
	 * Saves account's default groupID, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference.
	 */
	public void setDefaultGroupId(AccountData accountData) throws DataException;

	/**
	 * Saves account's flags, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setFlags(AccountData accountData) throws DataException;

	/**
	 * Saves account's level, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setLevel(AccountData accountData) throws DataException;

	/**
	 * Saves account's blocks-minted adjustment, and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setBlocksMintedAdjustment(AccountData accountData) throws DataException;

	/** Returns account's minted block count or null if account not found. */
	public Integer getMintedBlockCount(String address) throws DataException;

	/**
	 * Saves account's minted block count and public key if present, in repository.
	 * <p>
	 * Note: ignores other fields like last reference, default groupID.
	 */
	public void setMintedBlockCount(AccountData accountData) throws DataException;

	/**
	 * Modifies account's minted block count only.
	 * <p>
	 * @return 2 if minted block count updated, 1 if block count set to delta, 0 if address not found.
	 */
	public int modifyMintedBlockCount(String address, int delta) throws DataException;

	/**
	 * Modifies batch of accounts' minted block count only.
	 * <p>
	 * This is a one-shot, batch version of modifyMintedBlockCount(String, int) above.
	 */
	public void modifyMintedBlockCounts(List<String> addresses, int delta) throws DataException;

	/** Returns account's block minted penalty count or null if account not found. */
	public Integer getBlocksMintedPenaltyCount(String address) throws DataException;

	/**
	 * Sets blocks minted penalties for given list of accounts.
	 * This replaces the existing values rather than modifying them by a delta.
	 *
	 * @param accountPenalties
	 * @throws DataException
	 */
	public void updateBlocksMintedPenalties(Set<AccountPenaltyData> accountPenalties) throws DataException;

	/** Delete account from repository. */
	public void delete(String address) throws DataException;

	/** Generic opportunistic tidy. */
	public void tidy() throws DataException;

	// Account balances

	/**
	 * Returns account's balance for specific assetID.
	 * <p>
	 * Note: returns <tt>null</tt> if account has no/zero balance for
	 * <i>that specific assetID</i>. This does not mean
	 * the account itself does not exist.
	 */
	public AccountBalanceData getBalance(String address, long assetId) throws DataException;

	/** Returns all account balances for given assetID, optionally excluding zero balances. */
	public List<AccountBalanceData> getAssetBalances(long assetId, Boolean excludeZero) throws DataException;

	public SponsorshipReport getSponsorshipReport(String address, String[] realRewardShareRecipients) throws DataException;

	/**
	 * Get Sponsorship Report
	 *
	 * @param address        the account address
	 * @param addressFetcher fetches the addresses that this method will aggregate
	 * @return the report
	 * @throws DataException
	 */
	public SponsorshipReport getMintershipReport(String address, Function<String, List<String>> addressFetcher) throws DataException;

	/**
	 * Get Sponsee Addresses
	 *
	 * @param account                   the sponsor's account address
	 * @param realRewardShareRecipients the recipients that get real reward shares, not sponsorship
	 * @return the sponsee addresses
	 * @throws DataException
	 */
	public List<String> getSponseeAddresses(String account, String[] realRewardShareRecipients) throws DataException;

	/**
	 * Get Sponsor
	 *
	 * @param address the address of the account
	 *
	 * @return the address of accounts sponsor, empty if not sponsored
	 *
	 * @throws DataException
	 */
	public Optional<String> getSponsor(String address) throws DataException;

	public List<AddressLevelPairing> getAddressLevelPairings(int minLevel) throws DataException;

    /** How to order results when fetching asset balances. */
	public enum BalanceOrdering {
		/** assetID first, then balance, then account address */
		ASSET_BALANCE_ACCOUNT,
		/** account address first, then assetID */
		ACCOUNT_ASSET,
		/** assetID first, then account address */
		ASSET_ACCOUNT
	}

	/** Returns account balances for matching addresses / assetIDs, optionally excluding zero balances, with pagination, used by API. */
	public List<AccountBalanceData> getAssetBalances(List<String> addresses, List<Long> assetIds, BalanceOrdering balanceOrdering, Boolean excludeZero, Integer limit, Integer offset, Boolean reverse) throws DataException;

	/** Modifies account's asset balance by <tt>deltaBalance</tt>. */
	public void modifyAssetBalance(String address, long assetId, long deltaBalance) throws DataException;

	/** Modifies a batch of account asset balances, treating AccountBalanceData.balance as <tt>deltaBalance</tt>. */
	public void modifyAssetBalances(List<AccountBalanceData> accountBalanceDeltas) throws DataException;

	/** Batch update of account asset balances. */
	public void setAssetBalances(List<AccountBalanceData> accountBalances) throws DataException;

	public void save(AccountBalanceData accountBalanceData) throws DataException;

	public void delete(String address, long assetId) throws DataException;

	// Reward-shares

	public RewardShareData getRewardShare(byte[] mintingAccountPublicKey, String recipientAccount) throws DataException;

	public RewardShareData getRewardShare(byte[] rewardSharePublicKey) throws DataException;

	public List<byte[]> getRewardSharePublicKeys() throws DataException;

	public boolean isRewardSharePublicKey(byte[] publicKey) throws DataException;

	/** Returns number of active reward-shares involving passed public key as the minting account only. */
	public int countRewardShares(byte[] mintingAccountPublicKey) throws DataException;

	/** Returns number of active self-shares involving passed public key as the minting account only. */
	public int countSelfShares(byte[] mintingAccountPublicKey) throws DataException;

	public List<RewardShareData> getRewardShares() throws DataException;

	public List<RewardShareData> findRewardShares(List<String> mintingAccounts, List<String> recipientAccounts, List<String> involvedAddresses, Integer limit, Integer offset, Boolean reverse) throws DataException;

	/**
	 * Returns index in list of reward-shares (sorted by reward-share public key).
	 * <p>
	 * @return index (from 0) or null if publicKey not found in repository.
	 */
	public Integer getRewardShareIndex(byte[] rewardSharePublicKey) throws DataException;

	/**
	 * Returns reward-share data using index into list of reward-shares (sorted by reward-share public key).
	 */
	public RewardShareData getRewardShareByIndex(int index) throws DataException;

	/**
	 * Returns list of reward-share data using array of indexes into list of reward-shares (sorted by reward-share public key).
	 * <p>
	 * This is a one-shot, batch form of the above <tt>getRewardShareByIndex(int)</tt> call.
	 * 
	 * @return list of reward-share data, or null if one (or more) index is invalid
	 * @throws DataException
	 */
	public List<RewardShareData> getRewardSharesByIndexes(int[] indexes) throws DataException;

	public boolean rewardShareExists(byte[] rewardSharePublicKey) throws DataException;

	public void save(RewardShareData rewardShareData) throws DataException;

	/** Delete reward-share from repository using passed minting account's public key and recipient's address. */
	public void delete(byte[] mintingAccountPublickey, String recipient) throws DataException;

	// Minting accounts used by BlockMinter, potentially includes reward-shares

	public List<MintingAccountData> getMintingAccounts() throws DataException;

	public MintingAccountData getMintingAccount(byte[] mintingAccountKey) throws DataException;

	public void save(MintingAccountData mintingAccountData) throws DataException;

	/** Delete minting account info, used by BlockMinter, from repository using passed public or private key. */
	public int delete(byte[] mintingAccountKey) throws DataException;

	// Managing QORT from legacy QORA

	/**
	 * Returns full info for accounts with legacy QORA asset that are eligible
	 * for more block reward (block processing) or for block reward removal (block orphaning).
	 * <p>
	 * For block processing, accounts that have already received their final QORT reward for owning
	 * legacy QORA are omitted from the results. <tt>blockHeight</tt> should be <tt>null</tt>.
	 * <p>
	 * For block orphaning, accounts that did not receive a QORT reward at <tt>blockHeight</tt>
	 * are omitted from the results.
	 * 
	 * @param blockHeight QORT reward must have be present at this height (for orphaning only)
	 * @throws DataException
	 */
	public List<EligibleQoraHolderData> getEligibleLegacyQoraHolders(Integer blockHeight) throws DataException;

	public QortFromQoraData getQortFromQoraInfo(String address) throws DataException;

	public void save(QortFromQoraData qortFromQoraData) throws DataException;

	public int deleteQortFromQoraInfo(String address) throws DataException;

}
