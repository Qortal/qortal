package org.qortal.account;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;
import org.qortal.controller.LiteNode;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import static org.qortal.utils.Amounts.prettyAmount;

@XmlAccessorType(XmlAccessType.NONE) // Stops JAX-RS errors when unmarshalling blockchain config
public class Account {

	private static final Logger LOGGER = LogManager.getLogger(Account.class);

	public static final int ADDRESS_LENGTH = 25;
	public static final int FOUNDER_FLAG = 0x1;

	protected Repository repository;
	protected String address;

	protected Account() {
	}

	/** Construct Account business object using account's address */
	public Account(Repository repository, String address) {
		this.repository = repository;
		this.address = address;
	}

	// Simple getters / setters

	public String getAddress() {
		return this.address;
	}

	/**
	 * Build AccountData object using available account information.
	 * <p>
	 * For example, PublicKeyAccount might override and add public key info.
	 * 
	 * @return
	 */
	protected AccountData buildAccountData() {
		return new AccountData(this.address);
	}

	public void ensureAccount() throws DataException {
		this.repository.getAccountRepository().ensureAccount(this.buildAccountData());
	}

	// Balance manipulations - assetId is 0 for QORT

	public long getConfirmedBalance(long assetId) throws DataException {
		AccountBalanceData accountBalanceData;

		if (Settings.getInstance().isLite()) {
			// Lite nodes request data from peers instead of the local db
			accountBalanceData = LiteNode.getInstance().fetchAccountBalance(this.address, assetId);
		}
		else {
			// All other node types fetch from the local db
			accountBalanceData = this.repository.getAccountRepository().getBalance(this.address, assetId);
		}

		if (accountBalanceData == null)
			return 0;

		return accountBalanceData.getBalance();
	}

	public void setConfirmedBalance(long assetId, long balance) throws DataException {
		// Safety feature!
		if (balance < 0) {
			String message = String.format("Refusing to set negative balance %s [assetId %d] for %s", prettyAmount(balance), assetId, this.address);
			LOGGER.error(message);
			throw new DataException(message);
		}

		// Delete account balance record instead of setting balance to zero
		if (balance == 0) {
			this.repository.getAccountRepository().delete(this.address, assetId);
			return;
		}

		// Can't have a balance without an account - make sure it exists!
		this.ensureAccount();

		AccountBalanceData accountBalanceData = new AccountBalanceData(this.address, assetId, balance);
		this.repository.getAccountRepository().save(accountBalanceData);

		LOGGER.trace(() -> String.format("%s balance now %s [assetId %s]", this.address, prettyAmount(balance), assetId));
	}

	// Convenience method
	public void modifyAssetBalance(long assetId, long deltaBalance) throws DataException {
		this.repository.getAccountRepository().modifyAssetBalance(this.getAddress(), assetId, deltaBalance);

		LOGGER.trace(() -> String.format("%s balance %s by %s [assetId %s]",
				this.address,
				(deltaBalance >= 0 ? "increased" : "decreased"),
				prettyAmount(Math.abs(deltaBalance)),
				assetId));
	}

	public void deleteBalance(long assetId) throws DataException {
		this.repository.getAccountRepository().delete(this.address, assetId);
	}

	// Reference manipulations

	/**
	 * Fetch last reference for account.
	 * 
	 * @return byte[] reference, or null if no reference or account not found.
	 * @throws DataException
	 */
	public byte[] getLastReference() throws DataException {
		byte[] reference = AccountRefCache.getLastReference(this.repository, this.address);
		LOGGER.trace(() -> String.format("Last reference for %s is %s", this.address, reference == null ? "null" : Base58.encode(reference)));
		return reference;
	}

	/**
	 * Set last reference for account.
	 * 
	 * @param reference
	 *            -- null allowed
	 * @throws DataException
	 */
	public void setLastReference(byte[] reference) throws DataException {
		LOGGER.trace(() -> String.format("Setting last reference for %s to %s", this.address, (reference == null ? "null" : Base58.encode(reference))));

		AccountData accountData = this.buildAccountData();
		accountData.setReference(reference);
		AccountRefCache.setLastReference(this.repository, accountData);
	}

	// Default groupID manipulations

	/** Returns account's default groupID or null if account doesn't exist. */
	public Integer getDefaultGroupId() throws DataException {
		return this.repository.getAccountRepository().getDefaultGroupId(this.address);
	}

	/**
	 * Sets account's default groupID and saves into repository.
	 * <p>
	 * Caller will need to call <tt>repository.saveChanges()</tt>.
	 * 
	 * @param defaultGroupId
	 * @throws DataException
	 */
	public void setDefaultGroupId(int defaultGroupId) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setDefaultGroupId(defaultGroupId);
		this.repository.getAccountRepository().setDefaultGroupId(accountData);

		LOGGER.trace(() -> String.format("Account %s defaultGroupId now %d", accountData.getAddress(), defaultGroupId));
	}

	// Account flags

	public Integer getFlags() throws DataException {
		return this.repository.getAccountRepository().getFlags(this.address);
	}

	public void setFlags(int flags) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setFlags(flags);
		this.repository.getAccountRepository().setFlags(accountData);
	}

	public static boolean isFounder(Integer flags) {
		return flags != null && (flags & FOUNDER_FLAG) != 0;
	}

	public boolean isFounder() throws DataException  {
		Integer flags = this.getFlags();
		return Account.isFounder(flags);
	}

	// Minting blocks

	/** Returns whether account can be considered a "minting account".
	 * <p>
	 * To be considered a "minting account", the account needs to pass at least one of these tests:<br>
	 * <ul>
	 * <li>account's level is at least <tt>minAccountLevelToMint</tt> from blockchain config</li>
	 * <li>account has 'founder' flag set</li>
	 * </ul>
	 * 
	 * @return true if account can be considered "minting account"
	 * @throws DataException
	 */
	public boolean canMint() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return false;

		Integer level = accountData.getLevel();
		if (level != null && level >= BlockChain.getInstance().getMinAccountLevelToMint())
			return true;

		// Founders can always mint, unless they have a penalty
		if (Account.isFounder(accountData.getFlags()) && accountData.getBlocksMintedPenalty() == 0)
			return true;

		return false;
	}

	/** Returns account's blockMinted (0+) or null if account not found in repository. */
	public Integer getBlocksMinted() throws DataException {
		return this.repository.getAccountRepository().getMintedBlockCount(this.address);
	}

	/** Returns account's blockMintedPenalty or null if account not found in repository. */
	public Integer getBlocksMintedPenalty() throws DataException {
		return this.repository.getAccountRepository().getBlocksMintedPenaltyCount(this.address);
	}


	/** Returns whether account can build reward-shares.
	 * <p>
	 * To be able to create reward-shares, the account needs to pass at least one of these tests:<br>
	 * <ul>
	 * <li>account's level is at least <tt>minAccountLevelToRewardShare</tt> from blockchain config</li>
	 * <li>account has 'founder' flag set</li>
	 * </ul>
	 * 
	 * @return true if account can be considered "minting account"
	 * @throws DataException
	 */
	public boolean canRewardShare() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return false;

		Integer level = accountData.getLevel();
		if (level != null && level >= BlockChain.getInstance().getMinAccountLevelToRewardShare())
			return true;

		if (Account.isFounder(accountData.getFlags()) && accountData.getBlocksMintedPenalty() == 0)
			return true;

		return false;
	}

	// Account level

	/** Returns account's level (0+) or null if account not found in repository. */
	public Integer getLevel() throws DataException {
		return this.repository.getAccountRepository().getLevel(this.address);
	}

	public void setLevel(int level) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setLevel(level);
		this.repository.getAccountRepository().setLevel(accountData);
	}

	public void setBlocksMintedAdjustment(int blocksMintedAdjustment) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setBlocksMintedAdjustment(blocksMintedAdjustment);
		this.repository.getAccountRepository().setBlocksMintedAdjustment(accountData);
	}

	/**
	 * Returns 'effective' minting level, or zero if account does not exist/cannot mint.
	 * <p>
	 * For founder accounts with no penalty, this returns "founderEffectiveMintingLevel" from blockchain config.
	 * 
	 * @return 0+
	 * @throws DataException
	 */
	public int getEffectiveMintingLevel() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return 0;

		// Founders are assigned a different effective minting level, as long as they have no penalty
		if (Account.isFounder(accountData.getFlags()) && accountData.getBlocksMintedPenalty() == 0)
			return BlockChain.getInstance().getFounderEffectiveMintingLevel();

		return accountData.getLevel();
	}

	/**
	 * Returns 'effective' minting level, or zero if account does not exist/cannot mint.
	 * <p>
	 * For founder accounts with no penalty, this returns 11, in order to list them separately.
	 * 
	 * @return 0+
	 * @throws DataException
	 */
	public int getEffectiveMintingLevelWithFounders() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return 0;

		// Founders are listed as level 11, as long as they have no penalty
		if (Account.isFounder(accountData.getFlags()) && accountData.getBlocksMintedPenalty() == 0)
			return 11;

		return accountData.getLevel();
	}

	/**
	 * Returns 'effective' minting level, or zero if reward-share does not exist.
	 * 
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return 0+
	 * @throws DataException
	 */
	public static int getRewardShareEffectiveMintingLevel(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		// Find actual minter and get their effective minting level
		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);
		if (rewardShareData == null)
			return 0;

		Account rewardShareMinter = new Account(repository, rewardShareData.getMinter());
		return rewardShareMinter.getEffectiveMintingLevel();
	}
	/**
	 * Returns 'effective' minting level, with a fix for the zero level.
	 * <p>
	 * For founder accounts with no penalty, this returns 11, in order to list them separately.
	 *
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return 0+
	 * @throws DataException
	 */
	public static int getRewardShareEffectiveMintingLevelIncludingLevelZero(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		// Find actual minter and get their effective minting level
		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);
		if (rewardShareData == null)
			return 0;

		else if (!rewardShareData.getMinter().equals(rewardShareData.getRecipient())) // Sponsorship reward share
			return 0;

		Account rewardShareMinter = new Account(repository, rewardShareData.getMinter());
		return rewardShareMinter.getEffectiveMintingLevelWithFounders();
	}
}
