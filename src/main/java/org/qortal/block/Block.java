package org.qortal.block;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import io.druid.extendedset.intset.ConciseSet;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.AccountRefCache;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.at.AT;
import org.qortal.block.BlockChain.AccountLevelShareBin;
import org.qortal.block.BlockChain.BlockTimingByHeight;
import org.qortal.controller.OnlineAccountsManager;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.Qortal25519Extras;
import org.qortal.data.account.*;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.BlockTransactionData;
import org.qortal.data.group.GroupAdminData;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.*;
import org.qortal.settings.Settings;
import org.qortal.transaction.AtTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ApprovalStatus;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.block.BlockTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;
import org.qortal.utils.Groups;
import org.qortal.utils.NTP;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public class Block {

	// Validation results
	public enum ValidationResult {
		OK(1),
		REFERENCE_MISSING(10),
		PARENT_DOES_NOT_EXIST(11),
		BLOCKCHAIN_NOT_EMPTY(12),
		PARENT_HAS_EXISTING_CHILD(13),
		TIMESTAMP_OLDER_THAN_PARENT(20),
		TIMESTAMP_IN_FUTURE(21),
		TIMESTAMP_MS_INCORRECT(22),
		TIMESTAMP_TOO_SOON(23),
		TIMESTAMP_INCORRECT(24),
		VERSION_INCORRECT(30),
		FEATURE_NOT_YET_RELEASED(31),
		MINTER_NOT_ACCEPTED(41),
		GENESIS_TRANSACTIONS_INVALID(50),
		TRANSACTION_TIMESTAMP_INVALID(51),
		TRANSACTION_INVALID(52),
		TRANSACTION_PROCESSING_FAILED(53),
		TRANSACTION_ALREADY_PROCESSED(54),
		TRANSACTION_NEEDS_APPROVAL(55),
		TRANSACTION_NOT_CONFIRMABLE(56),
		AT_STATES_MISMATCH(61),
		ONLINE_ACCOUNTS_INVALID(70),
		ONLINE_ACCOUNT_UNKNOWN(71),
		ONLINE_ACCOUNT_SIGNATURES_MISSING(72),
		ONLINE_ACCOUNT_SIGNATURES_MALFORMED(73),
		ONLINE_ACCOUNT_SIGNATURE_INCORRECT(74),
		ONLINE_ACCOUNT_NONCE_INCORRECT(75);

		public final int value;

		private static final Map<Integer, ValidationResult> map = stream(ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

		ValidationResult(int value) {
			this.value = value;
		}

		public static ValidationResult valueOf(int value) {
			return map.get(value);
		}
	}

	// Properties
	protected Repository repository;
	protected BlockData blockData;
	protected PublicKeyAccount minter;
	boolean isTestnet = Settings.getInstance().isTestNet();

	// Other properties
	private static final Logger LOGGER = LogManager.getLogger(Block.class);

	/** Number of left-shifts to apply to block's online accounts count when calculating block's weight. */
	private static final int ACCOUNTS_COUNT_SHIFT = Transformer.PUBLIC_KEY_LENGTH * 8;
	/** Number of left-shifts to apply to previous block's weight when calculating a chain's weight. */
	private static final int CHAIN_WEIGHT_SHIFT = 8;

	/** Sorted list of transactions attached to this block */
	protected List<Transaction> transactions;

	/** Remote/imported/loaded AT states */
	protected List<ATStateData> atStates;
	/** Remote hash of AT states - in lieu of full AT state data in {@code atStates} */
	protected byte[] atStatesHash;
	/** Locally-generated AT states */
	protected List<ATStateData> ourAtStates;
	/** Locally-generated AT fees */
	protected long ourAtFees; // Generated locally

	/** Cached online accounts validation decision, to avoid revalidating when true */
	private boolean onlineAccountsAlreadyValid = false;

	@FunctionalInterface
	private interface BlockRewardDistributor {
		long distribute(long amount, Map<String, Long> balanceChanges) throws DataException;
	}

	/** Lazy-instantiated expanded info on block's online accounts. */
	public static class ExpandedAccount {
		private final RewardShareData rewardShareData;
		private final int sharePercent;
		private final boolean isRecipientAlsoMinter;

		private final Account mintingAccount;
		private final AccountData mintingAccountData;
		private final boolean isMinterFounder;
		private final boolean isMinterMember;

		private final Account recipientAccount;
		private final AccountData recipientAccountData;

		final BlockChain blockChain = BlockChain.getInstance();

		ExpandedAccount(Repository repository, RewardShareData rewardShareData, int blockHeight) throws DataException {
			this.rewardShareData = rewardShareData;
			this.sharePercent = this.rewardShareData.getSharePercent();

			this.mintingAccount = new Account(repository, this.rewardShareData.getMinter());
			this.mintingAccountData = repository.getAccountRepository().getAccount(this.mintingAccount.getAddress());
			this.isMinterFounder = Account.isFounder(mintingAccountData.getFlags());

			this.isRecipientAlsoMinter = this.rewardShareData.getRecipient().equals(this.mintingAccount.getAddress());
			this.isMinterMember
				= Groups.memberExistsInAnyGroup(
					repository.getGroupRepository(),
					Groups.getGroupIdsToMint(BlockChain.getInstance(), blockHeight),
					this.mintingAccount.getAddress()
			);

			if (this.isRecipientAlsoMinter) {
				// Self-share: minter is also recipient
				this.recipientAccount = this.mintingAccount;
				this.recipientAccountData = this.mintingAccountData;
			} else {
				// Recipient differs from minter
				this.recipientAccount = new Account(repository, this.rewardShareData.getRecipient());
				this.recipientAccountData = repository.getAccountRepository().getAccount(this.recipientAccount.getAddress());
			}
		}

		/**
		 * Get Effective Minting Level
		 *
		 * @return the effective minting level, if a data exception is thrown, it catches the exception and returns a zero
		 */
		public int getEffectiveMintingLevel() {
			try {
				return this.mintingAccount.getEffectiveMintingLevel();
			} catch (DataException e) {
				return 0;
			}
		}

		public Account getMintingAccount() {
			return this.mintingAccount;
		}
		public Account getRecipientAccount() {
			return this.recipientAccount;
		}

		/**
		 * Returns share bin for expanded account.
		 * <p>
		 * This is a method, not a final variable, because account's level can change between construction and call,
		 * e.g. during Block.process() where account levels are bumped right before Block.distributeBlockReward().
		 *
		 *  @return account-level share "bin" from blockchain config, or null if founder / none found
		 */
		public AccountLevelShareBin getShareBin(int blockHeight) {
			if (this.isMinterFounder && blockHeight < BlockChain.getInstance().getAdminsReplaceFoundersHeight())
				return null;

			final int accountLevel = this.mintingAccountData.getLevel();
			if (accountLevel <= 0)
				return null; // level 0 isn't included in any share bins

			if (blockHeight >= blockChain.getFixBatchRewardHeight()) {
				if (!this.isMinterMember)
					return null; // not member of minter group isn't included in any share bins
			}

			// Select the correct set of share bins based on block height
			final AccountLevelShareBin[] shareBinsByLevel = (blockHeight >= blockChain.getSharesByLevelV2Height()) ?
					blockChain.getShareBinsByAccountLevelV2() : blockChain.getShareBinsByAccountLevelV1();

			if (accountLevel > shareBinsByLevel.length)
				return null;

			if (blockHeight < blockChain.getShareBinFixHeight())
				// Off-by-one bug still in effect
				return shareBinsByLevel[accountLevel];

			// level 1 stored at index 0, level 2 stored at index 1, etc.
			return shareBinsByLevel[accountLevel-1];

		}

		public boolean hasShareBin(AccountLevelShareBin shareBin, int blockHeight) {
			AccountLevelShareBin ourShareBin = this.getShareBin(blockHeight);
			return ourShareBin != null && shareBin.id == ourShareBin.id;
		}

		public long distribute(long accountAmount, Map<String, Long> balanceChanges) {
			if (this.isRecipientAlsoMinter) {
				// minter & recipient the same - simpler case
				LOGGER.trace(() -> String.format("Minter/recipient account %s share: %s", this.mintingAccount.getAddress(), Amounts.prettyAmount(accountAmount)));
				if (accountAmount != 0)
					balanceChanges.merge(this.mintingAccount.getAddress(), accountAmount, Long::sum);
			} else {
				// minter & recipient different - extra work needed
				long recipientAmount = (accountAmount * this.sharePercent) / 100L / 100L; // because scaled by 2dp and 'percent' means "per 100"
				long minterAmount = accountAmount - recipientAmount;

				LOGGER.trace(() -> String.format("Minter account %s share: %s", this.mintingAccount.getAddress(), Amounts.prettyAmount(minterAmount)));
				if (minterAmount != 0)
					balanceChanges.merge(this.mintingAccount.getAddress(), minterAmount, Long::sum);

				LOGGER.trace(() -> String.format("Recipient account %s share: %s", this.recipientAccount.getAddress(), Amounts.prettyAmount(recipientAmount)));
				if (recipientAmount != 0)
					balanceChanges.merge(this.recipientAccount.getAddress(), recipientAmount, Long::sum);
			}

			// We always distribute all of the amount
			return accountAmount;
		}
	}

	/** Always use getExpandedAccounts() to access this, as it's lazy-instantiated. */
	private List<ExpandedAccount> cachedExpandedAccounts = null;

	/** Opportunistic cache of this block's valid online reward-shares. Only created by call to isValid(). */
	private List<RewardShareData> cachedOnlineRewardShares = null;

	// Other useful constants

	public static final BigInteger MAX_DISTANCE;
	static {
		byte[] maxValue = new byte[Transformer.PUBLIC_KEY_LENGTH];
		Arrays.fill(maxValue, (byte) 0xFF);
		MAX_DISTANCE = new BigInteger(1, maxValue);
	}

	public static final ConciseSet EMPTY_ONLINE_ACCOUNTS = new ConciseSet();

	// Constructors

	/**
	 * Constructs new Block without loading transactions and AT states.
	 * <p>
	 * Transactions and AT states are loaded on first call to getTransactions() or getATStates() respectively.
	 *
	 * @param repository
	 * @param blockData
	 */
	public Block(Repository repository, BlockData blockData) {
		this.repository = repository;
		this.blockData = blockData;
		this.minter = new PublicKeyAccount(repository, blockData.getMinterPublicKey());
	}

	/**
	 * Constructs new Block using passed transaction and AT states.
	 * <p>
	 * This constructor typically used when receiving a serialized block over the network.
	 *
	 * @param repository
	 * @param blockData
	 * @param transactions
	 * @param atStates
	 */
	public Block(Repository repository, BlockData blockData, List<TransactionData> transactions, List<ATStateData> atStates) {
		this(repository, blockData);

		this.transactions = new ArrayList<>();

		long totalFees = 0;

		// We have to sum fees too
		for (TransactionData transactionData : transactions) {
			this.transactions.add(Transaction.fromData(repository, transactionData));
			totalFees += transactionData.getFee();
		}

		this.atStates = atStates;
		for (ATStateData atState : atStates)
			totalFees += atState.getFees();

		this.blockData.setTotalFees(totalFees);
	}

	/**
	 * Constructs new Block using passed transaction and minimal AT state info.
	 * <p>
	 * This constructor typically used when receiving a serialized block over the network.
	 *
	 * @param repository
	 * @param blockData
	 * @param transactions
	 * @param atStatesHash
	 */
	public Block(Repository repository, BlockData blockData, List<TransactionData> transactions, byte[] atStatesHash) {
		this(repository, blockData);

		this.transactions = new ArrayList<>();

		long totalFees = 0;

		// We have to sum fees too
		for (TransactionData transactionData : transactions) {
			this.transactions.add(Transaction.fromData(repository, transactionData));
			totalFees += transactionData.getFee();
		}

		this.atStatesHash = atStatesHash;
		totalFees += this.blockData.getATFees();

		this.blockData.setTotalFees(totalFees);
	}

	/**
	 * Constructs new Block with empty transaction list, using passed minter account.
	 *
	 * @param repository
	 * @param blockData
	 * @param minter
	 */
	private Block(Repository repository, BlockData blockData, PrivateKeyAccount minter) {
		this(repository, blockData);

		this.minter = minter;
		this.transactions = new ArrayList<>();
	}

	/**
	 * Mints new Block with basic, initial values.
	 * <p>
	 * This constructor typically used when minting a new block.
	 * <p>
	 * Note that CIYAM ATs will be executed and AT-Transactions prepended to this block, along with AT state data and fees.
	 *
	 * @param repository
	 * @param parentBlockData
	 * @param minter
	 * @throws DataException
	 */
	public static Block mint(Repository repository, BlockData parentBlockData, PrivateKeyAccount minter) throws DataException {
		Block parentBlock = new Block(repository, parentBlockData);

		int version = parentBlock.getNextBlockVersion();
		byte[] reference = parentBlockData.getSignature();

		// Qortal: minter is always a reward-share, so find actual minter and get their effective minting level
		int minterLevel = Account.getRewardShareEffectiveMintingLevel(repository, minter.getPublicKey());
		if (minterLevel == 0) {
			LOGGER.error("Minter effective level returned zero?");
			return null;
		}

		int height = parentBlockData.getHeight() + 1;
		long timestamp = calcTimestamp(parentBlockData, minter.getPublicKey(), minterLevel);

		Long onlineAccountsTimestamp = OnlineAccountsManager.getCurrentOnlineAccountTimestamp();
		byte[] encodedOnlineAccounts = new byte[0];
		int onlineAccountsCount = 0;
		byte[] onlineAccountsSignatures = null;

		if (isBatchRewardDistributionBlock(height)) {
			// Batch reward distribution block - copy online accounts from recent block with highest online accounts count

			int firstBlock = height - BlockChain.getInstance().getBlockRewardBatchAccountsBlockCount();
			int lastBlock = height - 1;
			BlockData highOnlineAccountsBlock = repository.getBlockRepository().getBlockInRangeWithHighestOnlineAccountsCount(firstBlock, lastBlock);
			encodedOnlineAccounts = highOnlineAccountsBlock.getEncodedOnlineAccounts();
			onlineAccountsCount = highOnlineAccountsBlock.getOnlineAccountsCount();
			// No point in copying signatures since these aren't revalidated, and because of this onlineAccountsTimestamp must be null too
			onlineAccountsSignatures = null;
			onlineAccountsTimestamp = null;
		}
		else if (isOnlineAccountsBlock(height)) {
			// Standard online accounts block - add online accounts in regular way

			// Fetch our list of online accounts, removing any that are missing a nonce
			List<OnlineAccountData> onlineAccounts = OnlineAccountsManager.getInstance().getOnlineAccounts(onlineAccountsTimestamp);
			onlineAccounts.removeIf(a -> a.getNonce() == null || a.getNonce() < 0);

			// After feature trigger, remove any online accounts that are level 0
			// but only if they are before the ignore level feature trigger
			if (height < BlockChain.getInstance().getIgnoreLevelForRewardShareHeight() &&
					height >= BlockChain.getInstance().getOnlineAccountMinterLevelValidationHeight()) {
				onlineAccounts.removeIf(a -> {
					try {
						return Account.getRewardShareEffectiveMintingLevel(repository, a.getPublicKey()) == 0;
					} catch (DataException e) {
						// Something went wrong, so remove the account
						return true;
					}
				});
			}

			// After feature trigger, remove any online accounts that are not minter group member
			if (height >= BlockChain.getInstance().getGroupMemberCheckHeight()) {
				onlineAccounts.removeIf(a -> {
					try {
						List<Integer> groupIdsToMint = Groups.getGroupIdsToMint(BlockChain.getInstance(), height);
						String address = Account.getRewardShareMintingAddress(repository, a.getPublicKey());
						boolean isMinterGroupMember = Groups.memberExistsInAnyGroup(repository.getGroupRepository(), groupIdsToMint, address);
						return !isMinterGroupMember;
					} catch (DataException e) {
						// Something went wrong, so remove the account
						return true;
					}
				});
			}

			if (onlineAccounts.isEmpty()) {
				LOGGER.debug("No online accounts - not even our own?");
				return null;
			}

			// Load sorted list of reward share public keys into memory, so that the indexes can be obtained.
			// This is up to 100x faster than querying each index separately. For 4150 reward share keys, it
			// was taking around 5000ms to query individually, vs 50ms using this approach.
			List<byte[]> allRewardSharePublicKeys = repository.getAccountRepository().getRewardSharePublicKeys();

			// Map using index into sorted list of reward-shares as key
			Map<Integer, OnlineAccountData> indexedOnlineAccounts = new HashMap<>();
			for (OnlineAccountData onlineAccountData : onlineAccounts) {
				Integer accountIndex = getRewardShareIndex(onlineAccountData.getPublicKey(), allRewardSharePublicKeys);
				if (accountIndex == null)
					// Online account (reward-share) with current timestamp but reward-share cancelled
					continue;

				indexedOnlineAccounts.put(accountIndex, onlineAccountData);
			}
			List<Integer> accountIndexes = new ArrayList<>(indexedOnlineAccounts.keySet());
			accountIndexes.sort(null);

			// Convert to compressed integer set
			ConciseSet onlineAccountsSet = new ConciseSet();
			onlineAccountsSet = onlineAccountsSet.convert(accountIndexes);
			encodedOnlineAccounts = BlockTransformer.encodeOnlineAccounts(onlineAccountsSet);
			onlineAccountsCount = onlineAccountsSet.size();

			// Collate all signatures
			Collection<byte[]> signaturesToAggregate = indexedOnlineAccounts.values()
					.stream()
					.map(OnlineAccountData::getSignature)
					.collect(Collectors.toList());

			// Aggregated, single signature
			onlineAccountsSignatures = Qortal25519Extras.aggregateSignatures(signaturesToAggregate);

			// Add nonces to the end of the online accounts signatures
			try {
				// Create ordered list of nonce values
				List<Integer> nonces = new ArrayList<>();
				for (int i = 0; i < onlineAccountsCount; ++i) {
					Integer accountIndex = accountIndexes.get(i);
					OnlineAccountData onlineAccountData = indexedOnlineAccounts.get(accountIndex);
					nonces.add(onlineAccountData.getNonce());
				}

				// Encode the nonces to a byte array
				byte[] encodedNonces = BlockTransformer.encodeOnlineAccountNonces(nonces);

				// Append the encoded nonces to the encoded online account signatures
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				outputStream.write(onlineAccountsSignatures);
				outputStream.write(encodedNonces);
				onlineAccountsSignatures = outputStream.toByteArray();
			} catch (TransformationException | IOException e) {
				return null;
			}

		}
		else {
			// No online accounts should be included in this block
			onlineAccountsTimestamp = null;
		}

		byte[] minterSignature = minter.sign(BlockTransformer.getBytesForMinterSignature(parentBlockData,
				minter.getPublicKey(), encodedOnlineAccounts));

		int transactionCount = 0;
		byte[] transactionsSignature = null;

		int atCount = 0;
		long atFees = 0;
		long totalFees = 0;

		// This instance used for AT processing
		BlockData preAtBlockData = new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp,
				minter.getPublicKey(), minterSignature, atCount, atFees,
				encodedOnlineAccounts, onlineAccountsCount, onlineAccountsTimestamp, onlineAccountsSignatures);

		Block newBlock = new Block(repository, preAtBlockData, minter);

		// Requires blockData and transactions, sets ourAtStates and ourAtFees
		newBlock.executeATs();

		atCount = newBlock.ourAtStates.size();
		newBlock.atStates = newBlock.ourAtStates;
		atFees = newBlock.ourAtFees;
		totalFees = atFees;

		// Rebuild blockData using post-AT-execute data
		newBlock.blockData = new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp,
				minter.getPublicKey(), minterSignature, atCount, atFees,
				encodedOnlineAccounts, onlineAccountsCount, onlineAccountsTimestamp, onlineAccountsSignatures);

		return newBlock;
	}

	/**
	 * Mints new block using this block as template, but with different minting account.
	 * <p>
	 * NOTE: uses the same transactions list, AT states, etc.
	 *
	 * @param minter
	 * @return
	 * @throws DataException
	 */
	public Block remint(PrivateKeyAccount minter) throws DataException {
		Block newBlock = new Block(this.repository, this.blockData);
		newBlock.minter = minter;

		BlockData parentBlockData = this.getParent();

		// Copy AT state data
		newBlock.ourAtStates = this.ourAtStates;
		newBlock.atStates = newBlock.ourAtStates;
		newBlock.ourAtFees = this.ourAtFees;

		// Calculate new block timestamp
		int version = this.blockData.getVersion();
		byte[] reference = this.blockData.getReference();

		byte[] minterSignature = minter.sign(BlockTransformer.getBytesForMinterSignature(parentBlockData,
				minter.getPublicKey(), this.blockData.getEncodedOnlineAccounts()));

		// Qortal: minter is always a reward-share, so find actual minter and get their effective minting level
		int minterLevel = Account.getRewardShareEffectiveMintingLevel(repository, minter.getPublicKey());
		if (minterLevel == 0){
			LOGGER.error("Minter effective level returned zero?");
			return null;
		}

		long timestamp = calcTimestamp(parentBlockData, minter.getPublicKey(), minterLevel);

		newBlock.transactions = this.transactions;
		int transactionCount = this.blockData.getTransactionCount();
		long totalFees = this.blockData.getTotalFees();
		byte[] transactionsSignature = null; // We'll calculate this later
		Integer height = this.blockData.getHeight();

		int atCount = newBlock.ourAtStates.size();
		long atFees = newBlock.ourAtFees;

		byte[] encodedOnlineAccounts = this.blockData.getEncodedOnlineAccounts();
		int onlineAccountsCount = this.blockData.getOnlineAccountsCount();
		Long onlineAccountsTimestamp = this.blockData.getOnlineAccountsTimestamp();
		byte[] onlineAccountsSignatures = this.blockData.getOnlineAccountsSignatures();

		newBlock.blockData = new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp,
				minter.getPublicKey(), minterSignature, atCount, atFees, encodedOnlineAccounts, onlineAccountsCount, onlineAccountsTimestamp, onlineAccountsSignatures);

		// Resign to update transactions signature
		newBlock.sign();

		return newBlock;
	}

	// Getters/setters

	public BlockData getBlockData() {
		return this.blockData;
	}

	public PublicKeyAccount getMinter() {
		return this.minter;
	}


	public void setRepository(Repository repository) throws DataException {
		this.repository = repository;

		for (Transaction transaction : this.getTransactions()) {
			transaction.setRepository(repository);
		}
	}


	/**
	 * Force online accounts to be revalidated, e.g. at final stage of block minting.
	 */
	public void clearOnlineAccountsValidationCache() {
		this.onlineAccountsAlreadyValid = false;
	}

	// More information

	/**
	 * Return composite block signature (minterSignature + transactionsSignature).
	 *
	 * @return byte[], or null if either component signature is null.
	 */
	public byte[] getSignature() {
		if (this.blockData.getMinterSignature() == null || this.blockData.getTransactionsSignature() == null)
			return null;

		return Bytes.concat(this.blockData.getMinterSignature(), this.blockData.getTransactionsSignature());
	}

	/**
	 * Return the next block's version.
	 * <p>
	 * We're starting with version 4 as a nod to being newer than successor Qora,
	 * whose latest block version was 3.
	 *
	 * @return 1, 2, 3 or 4
	 */
	public int getNextBlockVersion() {
		if (this.blockData.getHeight() == null)
			throw new IllegalStateException("Can't determine next block's version as this block has no height set");

		return 4;
	}

	/**
	 * Return block's transactions.
	 * <p>
	 * If the block was loaded from repository then it's possible this method will call the repository to fetch the transactions if not done already.
	 *
	 * @return
	 * @throws DataException
	 */
	public List<Transaction> getTransactions() throws DataException {
		// Already loaded?
		if (this.transactions != null)
			return this.transactions;

		// Allocate cache for results
		List<TransactionData> transactionsData = this.repository.getBlockRepository().getTransactionsFromSignature(this.blockData.getSignature());

		long nonAtTransactionCount = transactionsData.stream().filter(transactionData -> transactionData.getType() != TransactionType.AT).count();

		// The number of non-AT transactions fetched from repository should correspond with Block's transactionCount
		if (nonAtTransactionCount != this.blockData.getTransactionCount()) {
			LOGGER.error(() -> String.format("Block's transactions from repository (%d) do not match block's transaction count (%d)", nonAtTransactionCount, this.blockData.getTransactionCount()));
			throw new IllegalStateException("Block's transactions from repository do not match block's transaction count");
		}

		this.transactions = new ArrayList<>();

		for (TransactionData transactionData : transactionsData)
			this.transactions.add(Transaction.fromData(this.repository, transactionData));

		return this.transactions;
	}

	/**
	 * Return block's AT states.
	 * <p>
	 * If the block was loaded from repository then it's possible this method will call the repository to fetch the AT states if not done already.
	 * <p>
	 * <b>Note:</b> AT states fetched from repository only contain summary info, not actual data like serialized state data or AT creation timestamps!
	 *
	 * @return
	 * @throws DataException
	 */
	public List<ATStateData> getATStates() throws DataException {
		// Already loaded?
		if (this.atStates != null)
			return this.atStates;

		// If loading from repository, this block must have a height
		if (this.blockData.getHeight() == null)
			throw new IllegalStateException("Can't fetch block's AT states from repository without a block height");

		// Allocate cache for results
		List<ATStateData> atStateData = this.repository.getATRepository().getBlockATStatesAtHeight(this.blockData.getHeight());

		// The number of non-initial AT states fetched from repository should correspond with Block's atCount.
		// We exclude initial AT states created by processing DEPLOY_AT transactions as they are never serialized and so not included in block's AT count.
		int nonInitialCount = (int) atStateData.stream().filter(atState -> !atState.isInitial()).count();
		if (nonInitialCount != this.blockData.getATCount())
			throw new IllegalStateException("Block's AT states from repository do not match block's AT count");

		this.atStates = atStateData;

		return this.atStates;
	}

	public byte[] getAtStatesHash() {
		return this.atStatesHash;
	}

	/**
	 * Return expanded info on block's online accounts.
	 * <p>
	 * Typically called as part of Block.process() or Block.orphan()
	 * so ideally after any calls to Block.isValid().
	 *
	 * @throws DataException
	 */
	public List<ExpandedAccount> getExpandedAccounts() throws DataException {
		if (this.cachedExpandedAccounts != null)
			return this.cachedExpandedAccounts;

		// We might already have a cache of online, reward-shares thanks to isValid()
		if (this.cachedOnlineRewardShares == null) {
			ConciseSet accountIndexes = BlockTransformer.decodeOnlineAccounts(this.blockData.getEncodedOnlineAccounts());
			this.cachedOnlineRewardShares = repository.getAccountRepository().getRewardSharesByIndexes(accountIndexes.toArray());

			if (this.cachedOnlineRewardShares == null)
				throw new DataException("Online accounts invalid?");
		}

		List<ExpandedAccount> expandedAccounts = new ArrayList<>();

		for (RewardShareData rewardShare : this.cachedOnlineRewardShares) {
			expandedAccounts.add(new ExpandedAccount(repository, rewardShare, this.blockData.getHeight()));
		}

		this.cachedExpandedAccounts = expandedAccounts;
		LOGGER.trace(() -> String.format("Online reward-shares after expanded accounts %s", this.cachedOnlineRewardShares));

		return this.cachedExpandedAccounts;
	}

	// Navigation

	/**
	 * Load parent block's data from repository via this block's reference.
	 *
	 * @return parent's BlockData, or null if no parent found
	 * @throws DataException
	 */
	public BlockData getParent() throws DataException {
		byte[] reference = this.blockData.getReference();
		if (reference == null)
			return null;

		return this.repository.getBlockRepository().fromSignature(reference);
	}

	/**
	 * Load child block's data from repository via this block's signature.
	 *
	 * @return child's BlockData, or null if no parent found
	 * @throws DataException
	 */
	public BlockData getChild() throws DataException {
		byte[] signature = this.blockData.getSignature();
		if (signature == null)
			return null;

		return this.repository.getBlockRepository().fromReference(signature);
	}

	// Processing

	/**
	 * Add a transaction to the block.
	 * <p>
	 * Used when constructing a new block during minting.
	 * <p>
	 * Requires block's {@code minter} being a {@code PrivateKeyAccount} so block's transactions signature can be recalculated.
	 *
	 * @param transactionData
	 * @return true if transaction successfully added to block, false otherwise
	 * @throws IllegalStateException
	 *             if block's {@code minter} is not a {@code PrivateKeyAccount}.
	 */
	public boolean addTransaction(TransactionData transactionData) {
		// Can't add to transactions if we haven't loaded existing ones yet
		if (this.transactions == null)
			throw new IllegalStateException("Attempted to add transaction to partially loaded database Block");

		if (!(this.minter instanceof PrivateKeyAccount))
			throw new IllegalStateException("Block's minter is not PrivateKeyAccount - can't sign!");

		if (this.blockData.getMinterSignature() == null)
			throw new IllegalStateException("Cannot calculate transactions signature as block has no minter signature");

		// Already added? (Check using signature)
		if (this.transactions.stream().anyMatch(transaction -> Arrays.equals(transaction.getTransactionData().getSignature(), transactionData.getSignature())))
			return true;

		// Check there is space in block
		try {
			if (BlockTransformer.getDataLength(this) + TransactionTransformer.getDataLength(transactionData) > BlockChain.getInstance().getMaxBlockSize())
				return false;
		} catch (TransformationException e) {
			return false;
		}

		// Add to block
		this.transactions.add(Transaction.fromData(this.repository, transactionData));

		// Re-sort
		this.transactions.sort(Transaction.getComparator());

		// Update transaction count
		this.blockData.setTransactionCount(this.blockData.getTransactionCount() + 1);

		// Update totalFees
		this.blockData.setTotalFees(this.blockData.getTotalFees() + transactionData.getFee());

		// We've added a transaction, so recalculate transactions signature
		calcTransactionsSignature();

		return true;
	}

	/**
	 * Remove a transaction from the block.
	 * <p>
	 * Used when constructing a new block during minting.
	 * <p>
	 * Requires block's {@code minter} being a {@code PrivateKeyAccount} so block's transactions signature can be recalculated.
	 *
	 * @param transactionData
	 * @throws IllegalStateException
	 *             if block's {@code minter} is not a {@code PrivateKeyAccount}.
	 */
	public void deleteTransaction(TransactionData transactionData) {
		// Can't add to transactions if we haven't loaded existing ones yet
		if (this.transactions == null)
			throw new IllegalStateException("Attempted to add transaction to partially loaded database Block");

		if (!(this.minter instanceof PrivateKeyAccount))
			throw new IllegalStateException("Block's minter is not a PrivateKeyAccount - can't sign!");

		if (this.blockData.getMinterSignature() == null)
			throw new IllegalStateException("Cannot calculate transactions signature as block has no minter signature");

		// Attempt to remove from block (Check using signature)
		boolean wasElementRemoved = this.transactions.removeIf(transaction -> Arrays.equals(transaction.getTransactionData().getSignature(), transactionData.getSignature()));
		if (!wasElementRemoved)
			// Wasn't there - nothing more to do
			return;

		// Re-sort
		this.transactions.sort(Transaction.getComparator());

		// Update transaction count
		this.blockData.setTransactionCount(this.blockData.getTransactionCount() - 1);

		// Update totalFees
		this.blockData.setTotalFees(this.blockData.getTotalFees() - transactionData.getFee());

		// We've removed a transaction, so recalculate transactions signature
		calcTransactionsSignature();
	}

	/**
	 * Recalculate block's minter signature.
	 * <p>
	 * Requires block's {@code minter} being a {@code PrivateKeyAccount}.
	 * <p>
	 * Minter signature is made by the minter signing the following data:
	 * <p>
	 * previous block's minter signature + minter's public key + (encoded) online-accounts data
	 * <p>
	 * (Previous block's minter signature is extracted from this block's reference).
	 *
	 * @throws IllegalStateException
	 *             if block's {@code minter} is not a {@code PrivateKeyAccount}.
	 * @throws RuntimeException
	 *             if somehow the minter signature cannot be calculated
	 */
	protected void calcMinterSignature() {
		if (!(this.minter instanceof PrivateKeyAccount))
			throw new IllegalStateException("Block's minter is not a PrivateKeyAccount - can't sign!");

		this.blockData.setMinterSignature(((PrivateKeyAccount) this.minter).sign(BlockTransformer.getBytesForMinterSignature(this.blockData)));
	}

	/**
	 * Recalculate block's transactions signature.
	 * <p>
	 * Requires block's {@code minter} being a {@code PrivateKeyAccount}.
	 *
	 * @throws IllegalStateException
	 *             if block's {@code minter} is not a {@code PrivateKeyAccount}.
	 * @throws RuntimeException
	 *             if somehow the transactions signature cannot be calculated
	 */
	protected void calcTransactionsSignature() {
		if (!(this.minter instanceof PrivateKeyAccount))
			throw new IllegalStateException("Block's minter is not a PrivateKeyAccount - can't sign!");

		try {
			this.blockData.setTransactionsSignature(((PrivateKeyAccount) this.minter).sign(BlockTransformer.getBytesForTransactionsSignature(this)));
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to calculate block's transactions signature", e);
		}
	}

	public static byte[] calcIdealMinterPublicKey(int parentBlockHeight, byte[] parentBlockSignature) {
		return Crypto.digest(Bytes.concat(Longs.toByteArray(parentBlockHeight), parentBlockSignature));
	}

	public static byte[] calcHeightPerturbedPublicKey(int height, byte[] publicKey) {
		return Crypto.digest(Bytes.concat(Longs.toByteArray(height), publicKey));
	}

	public static BigInteger calcKeyDistance(int parentHeight, byte[] parentBlockSignature, byte[] publicKey, int accountLevel) {
		byte[] idealKey = calcIdealMinterPublicKey(parentHeight, parentBlockSignature);
		byte[] perturbedKey = calcHeightPerturbedPublicKey(parentHeight + 1, publicKey);

		return MAX_DISTANCE.subtract(new BigInteger(idealKey).subtract(new BigInteger(perturbedKey)).abs()).divide(BigInteger.valueOf(accountLevel));
	}

	public static BigInteger calcBlockWeight(int parentHeight, byte[] parentBlockSignature, BlockSummaryData blockSummaryData) {
		BigInteger keyDistance = calcKeyDistance(parentHeight, parentBlockSignature, blockSummaryData.getMinterPublicKey(), blockSummaryData.getMinterLevel());
		return BigInteger.valueOf(blockSummaryData.getOnlineAccountsCount()).shiftLeft(ACCOUNTS_COUNT_SHIFT).add(keyDistance);
	}

	public static BigInteger calcChainWeight(int commonBlockHeight, byte[] commonBlockSignature, List<BlockSummaryData> blockSummaries, int maxHeight) {
		BigInteger cumulativeWeight = BigInteger.ZERO;
		int parentHeight = commonBlockHeight;
		byte[] parentBlockSignature = commonBlockSignature;
		NumberFormat formatter = new DecimalFormat("0.###E0");
		boolean isLogging = LOGGER.getLevel().isLessSpecificThan(Level.TRACE);

		int blockCount = 0;
		for (BlockSummaryData blockSummaryData : blockSummaries) {
			blockCount++;
			StringBuilder stringBuilder = isLogging ? new StringBuilder(512) : null;

			if (isLogging)
				stringBuilder.append(formatter.format(cumulativeWeight)).append(" -> ");

			cumulativeWeight = cumulativeWeight.shiftLeft(CHAIN_WEIGHT_SHIFT);
			if (isLogging)
				stringBuilder.append(formatter.format(cumulativeWeight)).append(" + ");

			BigInteger blockWeight = calcBlockWeight(parentHeight, parentBlockSignature, blockSummaryData);
			if (isLogging)
				stringBuilder.append("(height: ")
						.append(parentHeight + 1)
						.append(", online: ")
						.append(blockSummaryData.getOnlineAccountsCount())
						.append(") ")
						.append(formatter.format(blockWeight));

			cumulativeWeight = cumulativeWeight.add(blockWeight);
			if (isLogging)
				stringBuilder.append(" -> ").append(formatter.format(cumulativeWeight));

			if (isLogging && blockSummaries.size() > 1)
				LOGGER.debug(() -> stringBuilder.toString()); //NOSONAR S1612 (false positive?)

			parentHeight = blockSummaryData.getHeight();
			parentBlockSignature = blockSummaryData.getSignature();

			// After this timestamp, we only compare the same number of blocks
			if (NTP.getTime() >= BlockChain.getInstance().getCalcChainWeightTimestamp() && parentHeight >= maxHeight)
				break;
		}
		LOGGER.trace(String.format("Chain weight calculation was based on %d blocks", blockCount));

		return cumulativeWeight;
	}

	/**
	 * Returns timestamp based on previous block and this block's minter.
	 * <p>
	 * Uses distance of this block's minter from 'ideal' minter,
	 * along with min to max target block periods,
	 * added to previous block's timestamp.
	 * <p>
	 * Example:<br>
	 * This block's minter is 20% of max distance from 'ideal' minter.<br>
	 * Min/Max block periods are 30s and 90s respectively.<br>
	 * 20% of (90s - 30s) is 12s<br>
	 * So this block's timestamp is previous block's timestamp + 30s + 12s.
	 */
	public static long calcTimestamp(BlockData parentBlockData, byte[] minterPublicKey, int minterAccountLevel) {
		BigInteger distance = calcKeyDistance(parentBlockData.getHeight(), parentBlockData.getSignature(), minterPublicKey, minterAccountLevel);
		final int thisHeight = parentBlockData.getHeight() + 1;
		BlockTimingByHeight blockTiming = BlockChain.getInstance().getBlockTimingByHeight(thisHeight);

		double ratio = new BigDecimal(distance).divide(new BigDecimal(MAX_DISTANCE), 40, RoundingMode.DOWN).doubleValue();

		// Use power transform on ratio to spread out smaller values for bigger effect
		double transformed = Math.pow(ratio, blockTiming.power);

		long timeOffset = (long) (blockTiming.deviation * 2.0 * transformed);

		return parentBlockData.getTimestamp() + blockTiming.target - blockTiming.deviation + timeOffset;
	}

	public static long calcMinimumTimestamp(BlockData parentBlockData) {
		final int thisHeight = parentBlockData.getHeight() + 1;
		BlockTimingByHeight blockTiming = BlockChain.getInstance().getBlockTimingByHeight(thisHeight);
		return parentBlockData.getTimestamp() + blockTiming.target - blockTiming.deviation;
	}

	/**
	 * Recalculate block's minter and transactions signatures, thus giving block full signature.
	 * <p>
	 * Note: Block instance must have been constructed with a <tt>PrivateKeyAccount</tt> minter or this call will throw an <tt>IllegalStateException</tt>.
	 *
	 * @throws IllegalStateException
	 *             if block's {@code minter} is not a {@code PrivateKeyAccount}.
	 */
	public void sign() {
		this.calcMinterSignature();
		this.calcTransactionsSignature();

		this.blockData.setSignature(this.getSignature());
	}

	/**
	 * Returns whether this block's signatures are valid.
	 *
	 * @return true if both minter and transaction signatures are valid, false otherwise
	 */
	public boolean isSignatureValid() {
		try {
			// Check minter's signature first
			if (!this.minter.verify(this.blockData.getMinterSignature(), BlockTransformer.getBytesForMinterSignature(this.blockData)))
				return false;

			// Check transactions signature
			if (!this.minter.verify(this.blockData.getTransactionsSignature(), BlockTransformer.getBytesForTransactionsSignature(this)))
				return false;
		} catch (TransformationException e) {
			return false;
		}

		return true;
	}

	/**
	 * Returns whether Block's timestamp is valid.
	 * <p>
	 * Used by BlockMinter to check whether it's time to mint a new block,
	 * and also used by Block.isValid for checks (if not a testchain).
	 *
	 * @return ValidationResult.OK if timestamp valid, or some other ValidationResult otherwise.
	 * @throws DataException
	 */
	public ValidationResult isTimestampValid() throws DataException {
		BlockData parentBlockData = this.repository.getBlockRepository().fromSignature(this.blockData.getReference());
		if (parentBlockData == null)
			return ValidationResult.PARENT_DOES_NOT_EXIST;

		// Check timestamp is newer than parent timestamp
		if (this.blockData.getTimestamp() <= parentBlockData.getTimestamp())
			return ValidationResult.TIMESTAMP_OLDER_THAN_PARENT;

		// Check timestamp is not in the future (within configurable margin)
		// We don't need to check NTP.getTime() for null as we shouldn't reach here if that is already the case
		if (this.blockData.getTimestamp() - BlockChain.getInstance().getBlockTimestampMargin() > NTP.getTime())
			return ValidationResult.TIMESTAMP_IN_FUTURE;

		// Check timestamp is at least minimum based on parent block
		if (this.blockData.getTimestamp() < Block.calcMinimumTimestamp(parentBlockData))
			return ValidationResult.TIMESTAMP_TOO_SOON;

		// Qortal: minter is always a reward-share, so find actual minter and get their effective minting level
		int minterLevel = Account.getRewardShareEffectiveMintingLevel(repository, this.blockData.getMinterPublicKey());
		if (minterLevel == 0)
			return ValidationResult.MINTER_NOT_ACCEPTED;

		long expectedTimestamp = calcTimestamp(parentBlockData, this.blockData.getMinterPublicKey(), minterLevel);
		if (this.blockData.getTimestamp() != expectedTimestamp) {
			LOGGER.debug(String.format("timestamp mismatch! block had %s but we expected %s", this.blockData.getTimestamp(), expectedTimestamp));
			return ValidationResult.TIMESTAMP_INCORRECT;
		}

		return ValidationResult.OK;
	}

	public ValidationResult areOnlineAccountsValid() throws DataException {
		// Doesn't apply for Genesis block!
		if (this.blockData.getHeight() != null && this.blockData.getHeight() == 1)
			return ValidationResult.OK;

		// Don't bother revalidating if accounts have already been validated in this block
		if (this.onlineAccountsAlreadyValid)
			return ValidationResult.OK;

		// Expand block's online accounts indexes into actual accounts
		ConciseSet accountIndexes = BlockTransformer.decodeOnlineAccounts(this.blockData.getEncodedOnlineAccounts());
		// We use count of online accounts to validate decoded account indexes
		if (accountIndexes.size() != this.blockData.getOnlineAccountsCount())
			return ValidationResult.ONLINE_ACCOUNTS_INVALID;

		// Online accounts should only be included in designated blocks; all others must be empty
		if (!this.isOnlineAccountsBlock()) {
			if (this.blockData.getOnlineAccountsCount() != 0 || !accountIndexes.isEmpty()) {
				return ValidationResult.ONLINE_ACCOUNTS_INVALID;
			}
			// Not a designated online accounts block and account count is 0. Everything is correct so no need to validate further.
			return ValidationResult.OK;
		}

		// If this is a batch reward distribution block, ensure that online accounts have been copied from the correct previous block
		if (this.isBatchRewardDistributionBlock()) {
			int firstBlock = this.getBlockData().getHeight() - BlockChain.getInstance().getBlockRewardBatchAccountsBlockCount();
			int lastBlock = this.getBlockData().getHeight() - 1;
			BlockData highOnlineAccountsBlock = repository.getBlockRepository().getBlockInRangeWithHighestOnlineAccountsCount(firstBlock, lastBlock);

			if (this.blockData.getOnlineAccountsCount() != highOnlineAccountsBlock.getOnlineAccountsCount()) {
				return ValidationResult.ONLINE_ACCOUNTS_INVALID;
			}
			if (!Arrays.equals(this.blockData.getEncodedOnlineAccounts(), highOnlineAccountsBlock.getEncodedOnlineAccounts())) {
				return ValidationResult.ONLINE_ACCOUNTS_INVALID;
			}
			if (this.blockData.getOnlineAccountsSignatures() != null) {
				// Signatures are excluded to reduce block size
				return ValidationResult.ONLINE_ACCOUNTS_INVALID;
			}
			if (this.blockData.getOnlineAccountsTimestamp() != null) {
				// Online accounts timestamp must be null, because no signatures are included
				return ValidationResult.ONLINE_ACCOUNTS_INVALID;
			}

			// Online accounts have been correctly copied, and were already validated in earlier block, so consider them valid
			return ValidationResult.OK;
		}

		List<RewardShareData> onlineRewardShares = repository.getAccountRepository().getRewardSharesByIndexes(accountIndexes.toArray());
		if (onlineRewardShares == null)
			return ValidationResult.ONLINE_ACCOUNT_UNKNOWN;

		// After feature trigger, require all online account minters to be greater than level 0,
		// but only if it is before the feature trigger where we ignore level again
		if (this.blockData.getHeight() < BlockChain.getInstance().getIgnoreLevelForRewardShareHeight() &&
			this.getBlockData().getHeight() >= BlockChain.getInstance().getOnlineAccountMinterLevelValidationHeight()) {
			List<ExpandedAccount> expandedAccounts
					= this.getExpandedAccounts().stream()
					.filter(expandedAccount -> expandedAccount.isMinterMember)
					.collect(Collectors.toList());

			for (ExpandedAccount account : expandedAccounts) {
				if (account.getMintingAccount().getEffectiveMintingLevel() == 0)
					return ValidationResult.ONLINE_ACCOUNTS_INVALID;

				if (this.getBlockData().getHeight() >= BlockChain.getInstance().getFixBatchRewardHeight()) {
					if (!account.isMinterMember)
						return ValidationResult.ONLINE_ACCOUNTS_INVALID;
				}
			}
		}
		else if (this.blockData.getHeight() >= BlockChain.getInstance().getIgnoreLevelForRewardShareHeight()){
			Optional<ExpandedAccount> anyInvalidAccount
					= this.getExpandedAccounts().stream()
					.filter(account -> !account.isMinterMember)
					.findAny();
			if( anyInvalidAccount.isPresent() ) return ValidationResult.ONLINE_ACCOUNTS_INVALID;
		}

		// If block is past a certain age then we simply assume the signatures were correct
		long signatureRequirementThreshold = NTP.getTime() - BlockChain.getInstance().getOnlineAccountSignaturesMinLifetime();
		if (this.blockData.getTimestamp() < signatureRequirementThreshold)
			return ValidationResult.OK;

		if (this.blockData.getOnlineAccountsSignatures() == null || this.blockData.getOnlineAccountsSignatures().length == 0)
			return ValidationResult.ONLINE_ACCOUNT_SIGNATURES_MISSING;

		final int signaturesLength = Transformer.SIGNATURE_LENGTH;
		final int noncesLength = onlineRewardShares.size() * Transformer.INT_LENGTH;

		// We expect nonces to be appended to the online accounts signatures
		if (this.blockData.getOnlineAccountsSignatures().length != signaturesLength + noncesLength)
			return ValidationResult.ONLINE_ACCOUNT_SIGNATURES_MALFORMED;

		// Check signatures
		long onlineTimestamp = this.blockData.getOnlineAccountsTimestamp();
		byte[] onlineTimestampBytes = Longs.toByteArray(onlineTimestamp);

		byte[] encodedOnlineAccountSignatures = this.blockData.getOnlineAccountsSignatures();

		// Split online account signatures into signature(s) + nonces, then validate the nonces
		byte[] extractedSignatures = BlockTransformer.extract(encodedOnlineAccountSignatures, 0, signaturesLength);
		byte[] extractedNonces = BlockTransformer.extract(encodedOnlineAccountSignatures, signaturesLength, onlineRewardShares.size() * Transformer.INT_LENGTH);
		encodedOnlineAccountSignatures = extractedSignatures;

		List<Integer> nonces = BlockTransformer.decodeOnlineAccountNonces(extractedNonces);

		// Build block's view of online accounts (without signatures, as we don't need them here)
		Set<OnlineAccountData> onlineAccounts = new HashSet<>();
		for (int i = 0; i < onlineRewardShares.size(); ++i) {
			Integer nonce = nonces.get(i);
			byte[] publicKey = onlineRewardShares.get(i).getRewardSharePublicKey();

			OnlineAccountData onlineAccountData = new OnlineAccountData(onlineTimestamp, null, publicKey, nonce);
			onlineAccounts.add(onlineAccountData);
		}

		// Remove those already validated & cached by online accounts manager - no need to re-validate them
		OnlineAccountsManager.getInstance().removeKnown(onlineAccounts, onlineTimestamp);

		// Validate the rest
		for (OnlineAccountData onlineAccount : onlineAccounts)
			if (!OnlineAccountsManager.getInstance().verifyMemoryPoW(onlineAccount, null))
				return ValidationResult.ONLINE_ACCOUNT_NONCE_INCORRECT;

		// Cache the valid online accounts as they will likely be needed for the next block
		OnlineAccountsManager.getInstance().addBlocksOnlineAccounts(onlineAccounts, onlineTimestamp);

		// Extract online accounts' timestamp signatures from block data. Only one signature if aggregated.
		List<byte[]> onlineAccountsSignatures = BlockTransformer.decodeTimestampSignatures(encodedOnlineAccountSignatures);

		// Aggregate all public keys
		Collection<byte[]> publicKeys = onlineRewardShares.stream()
				.map(RewardShareData::getRewardSharePublicKey)
				.collect(Collectors.toList());

		byte[] aggregatePublicKey = Qortal25519Extras.aggregatePublicKeys(publicKeys);

		byte[] aggregateSignature = onlineAccountsSignatures.get(0);

		// One-step verification of aggregate signature using aggregate public key
		if (!Qortal25519Extras.verifyAggregated(aggregatePublicKey, aggregateSignature, onlineTimestampBytes))
			return ValidationResult.ONLINE_ACCOUNT_SIGNATURE_INCORRECT;

		// All online accounts valid, so save our list of online accounts for potential later use
		this.cachedOnlineRewardShares = onlineRewardShares;

		// Remember that the accounts are valid, to speed up subsequent checks
		this.onlineAccountsAlreadyValid = true;

		return ValidationResult.OK;
	}


	/**
	 * Returns whether Block is valid.
	 * <p>
	 * Performs various tests like checking for parent block, correct block timestamp, version, etc.
	 * <p>
	 * Checks block's transactions by testing their validity then processing them.<br>
	 * Hence uses a repository savepoint during execution.
	 *
	 * @return ValidationResult.OK if block is valid, or some other ValidationResult otherwise.
	 * @throws DataException
	 */
	public ValidationResult isValid() throws DataException {
		// Check parent block exists
		if (this.blockData.getReference() == null)
			return ValidationResult.REFERENCE_MISSING;

		BlockData parentBlockData = this.repository.getBlockRepository().fromSignature(this.blockData.getReference());
		if (parentBlockData == null)
			return ValidationResult.PARENT_DOES_NOT_EXIST;

		Block parentBlock = new Block(this.repository, parentBlockData);

		// Check parent doesn't already have a child block
		if (parentBlock.getChild() != null)
			return ValidationResult.PARENT_HAS_EXISTING_CHILD;

		// Check timestamp is newer than parent timestamp
		if (this.blockData.getTimestamp() <= parentBlockData.getTimestamp())
			return ValidationResult.TIMESTAMP_OLDER_THAN_PARENT;

		// These checks are disabled for testchains
		if (!BlockChain.getInstance().isTestChain()) {
			ValidationResult timestampResult = this.isTimestampValid();

			if (timestampResult != ValidationResult.OK)
				return timestampResult;
		}

		// Check block version
		if (this.blockData.getVersion() != parentBlock.getNextBlockVersion())
			return ValidationResult.VERSION_INCORRECT;
		if (this.blockData.getVersion() < 2 && this.blockData.getATCount() != 0)
			return ValidationResult.FEATURE_NOT_YET_RELEASED;

		// Check minter is allowed to mint this block
		if (!isMinterValid(parentBlock))
			return ValidationResult.MINTER_NOT_ACCEPTED;

		// Online Accounts
		ValidationResult onlineAccountsResult = this.areOnlineAccountsValid();
		LOGGER.trace("Accounts valid = {}", onlineAccountsResult);
		if (onlineAccountsResult != ValidationResult.OK)
			return onlineAccountsResult;

		// CIYAM ATs
		ValidationResult ciyamAtResult = this.areAtsValid();
		if (ciyamAtResult != ValidationResult.OK)
			return ciyamAtResult;

		// Check transactions
		ValidationResult transactionsResult = this.areTransactionsValid();
		if (transactionsResult != ValidationResult.OK)
			return transactionsResult;

		// Block is valid
		return ValidationResult.OK;
	}

	/** Returns whether block's transactions are valid. */
	private ValidationResult areTransactionsValid() throws DataException {
		// We're about to (test-)process a batch of transactions,
		// so create an account reference cache so get/set correct last-references.
		try (AccountRefCache accountRefCache = new AccountRefCache(repository)) {
			// Create repository savepoint here so we can rollback to it after testing transactions
			repository.setSavepoint();

			if (!isTestnet) {
				if (this.blockData.getHeight() == 212937) {
					// Apply fix for block 212937 but fix will be rolled back before we exit method
					Block212937.processFix(this);
				} else if (this.blockData.getHeight() == 1333492) {
					// Apply fix for block 1333492 but fix will be rolled back before we exit method
					Block1333492.processFix(this);
				} else if (InvalidNameRegistrationBlocks.isAffectedBlock(this.blockData.getHeight())) {
					// Apply fix for affected name registration blocks, but fix will be rolled back before we exit method
					InvalidNameRegistrationBlocks.processFix(this);
				} else if (InvalidBalanceBlocks.isAffectedBlock(this.blockData.getHeight())) {
					// Apply fix for affected balance blocks, but fix will be rolled back before we exit method
					InvalidBalanceBlocks.processFix(this);
				}
			}

			for (Transaction transaction : this.getTransactions()) {
				TransactionData transactionData = transaction.getTransactionData();

				// Skip AT transactions as they are covered by prior call to Block.areAtsValid()
				if (transactionData.getType() == TransactionType.AT)
					continue;

				// GenesisTransactions are not allowed (GenesisBlock overrides isValid() to allow them)
				if (transactionData.getType() == TransactionType.GENESIS || transactionData.getType() == TransactionType.ACCOUNT_FLAGS)
					return ValidationResult.GENESIS_TRANSACTIONS_INVALID;

				// Check timestamp and deadline
				if (transactionData.getTimestamp() > this.blockData.getTimestamp()
						|| transaction.getDeadline() <= this.blockData.getTimestamp())
					return ValidationResult.TRANSACTION_TIMESTAMP_INVALID;

				// After feature trigger, check that this transaction is confirmable
				if (transactionData.getTimestamp() >= BlockChain.getInstance().getMemPoWTransactionUpdatesTimestamp()) {
					if (!transaction.isConfirmable()) {
						return ValidationResult.TRANSACTION_NOT_CONFIRMABLE;
					}
					if (!transaction.isConfirmableAtHeight(this.blockData.getHeight())) {
						return ValidationResult.TRANSACTION_NOT_CONFIRMABLE;
					}
				}

				// Check transaction isn't already included in a block
				if (this.repository.getTransactionRepository().isConfirmed(transactionData.getSignature()))
					return ValidationResult.TRANSACTION_ALREADY_PROCESSED;

				// Check transaction has correct reference, etc.
				if (!transaction.hasValidReference()) {
					LOGGER.debug(String.format("Error during transaction validation, tx %s: INVALID_REFERENCE", Base58.encode(transactionData.getSignature())));
					return ValidationResult.TRANSACTION_INVALID;
				}

				// Check transaction is even valid
				// NOTE: in Gen1 there was an extra block height passed to DeployATTransaction.isValid
				Transaction.ValidationResult validationResult = transaction.isValid();
				if (validationResult != Transaction.ValidationResult.OK) {
					LOGGER.debug(String.format("Error during transaction validation, tx %s: %s", Base58.encode(transactionData.getSignature()), validationResult.name()));
					return ValidationResult.TRANSACTION_INVALID;
				}

				// Check transaction can even be processed
				validationResult = transaction.isProcessable();
				if (validationResult != Transaction.ValidationResult.OK) {
					LOGGER.debug(String.format("Error during transaction validation, tx %s: %s", Base58.encode(transactionData.getSignature()), validationResult.name()));
					return ValidationResult.TRANSACTION_INVALID;
				}

				// Process transaction to make sure other transactions validate properly
				try {
					// Only process transactions that don't require group-approval.
					// Group-approval transactions are dealt with later.
					if (transactionData.getApprovalStatus() == ApprovalStatus.NOT_REQUIRED)
						transaction.process();

					// Regardless of group-approval, update relevant info for creator (e.g. lastReference)
					transaction.processReferencesAndFees();
				} catch (Exception e) {
					LOGGER.error(String.format("Exception during transaction validation, tx %s", Base58.encode(transactionData.getSignature())), e);
					return ValidationResult.TRANSACTION_PROCESSING_FAILED;
				}
			}
		} catch (DataException e) {
			LOGGER.info("DataException during transaction validation", e);
			return ValidationResult.TRANSACTION_INVALID;
		} finally {
			// Rollback repository changes made by test-processing transactions above
			try {
				this.repository.rollbackToSavepoint();
			} catch (DataException e) {
				/*
				 * Rollback failure most likely due to prior DataException, so discard this DataException. Prior DataException propagates to caller.
				 */
			}
		}

		return ValidationResult.OK;
	}

	/**
	 * Returns whether blocks' ATs are valid.
	 * <p>
	 * NOTE: will execute ATs locally if not already done.<br>
	 * This is so we have locally-generated AT states for comparison.
	 *
	 * @return OK, or some AT-related validation result
	 * @throws DataException
	 */
	private ValidationResult areAtsValid() throws DataException {
		// Locally generated AT states should be valid so no need to re-execute them
		if (this.ourAtStates != null && this.ourAtStates == this.atStates) // Note object reference compare
			return ValidationResult.OK;

		// Generate local AT states for comparison
		this.executeATs();

		// Check locally generated AT states against ones received from elsewhere

		if (this.ourAtStates.size() != this.blockData.getATCount())
			return ValidationResult.AT_STATES_MISMATCH;

		if (this.ourAtFees != this.blockData.getATFees())
			return ValidationResult.AT_STATES_MISMATCH;

		// If we have a single AT states hash then compare that in preference
		if (this.atStatesHash != null) {
			int atBytesLength = blockData.getATCount() * BlockTransformer.AT_ENTRY_LENGTH;
			ByteArrayOutputStream atHashBytes = new ByteArrayOutputStream(atBytesLength);

			try {
				for (ATStateData atStateData : this.ourAtStates) {
					atHashBytes.write(atStateData.getATAddress().getBytes(StandardCharsets.UTF_8));
					atHashBytes.write(atStateData.getStateHash());
					atHashBytes.write(Longs.toByteArray(atStateData.getFees()));
				}
			} catch (IOException e) {
				throw new DataException("Couldn't validate AT states hash due to serialization issue?", e);
			}

			byte[] ourAtStatesHash = Crypto.digest(atHashBytes.toByteArray());
			if (!Arrays.equals(ourAtStatesHash, this.atStatesHash))
				return ValidationResult.AT_STATES_MISMATCH;

			// Use our AT state data from now on
			this.atStates = this.ourAtStates;
			return ValidationResult.OK;
		}

		// Note: this.atStates fully loaded thanks to this.getATStates() call:
		this.getATStates();
		for (int s = 0; s < this.ourAtStates.size(); ++s) {
			ATStateData ourAtState = this.ourAtStates.get(s);
			ATStateData theirAtState = this.atStates.get(s);

			if (!ourAtState.getATAddress().equals(theirAtState.getATAddress()))
				return ValidationResult.AT_STATES_MISMATCH;

			if (!Arrays.equals(ourAtState.getStateHash(), theirAtState.getStateHash()))
				return ValidationResult.AT_STATES_MISMATCH;

			if (!ourAtState.getFees().equals(theirAtState.getFees()))
				return ValidationResult.AT_STATES_MISMATCH;
		}

		return ValidationResult.OK;
	}

	/**
	 * Execute CIYAM ATs for this block.
	 * <p>
	 * This needs to be done locally for all blocks, regardless of origin.<br>
	 * Typically called by <tt>isValid()</tt> or new block constructor.
	 * <p>
	 * After calling, AT-generated transactions are prepended to the block's transactions and AT state data is generated.
	 * <p>
	 * Updates <tt>this.ourAtStates</tt> (local version) and <tt>this.ourAtFees</tt> (remote/imported/loaded version).
	 * <p>
	 * Note: this method does not store new AT state data into repository - that is handled by <tt>process()</tt>.
	 * <p>
	 * This method is not needed if fetching an existing block from the repository as AT state data will be loaded from repository as well.
	 *
	 * @see #isValid()
	 *
	 * @throws DataException
	 *
	 */
	private void executeATs() throws DataException {
		// We're expecting a lack of AT state data at this point.
		if (this.ourAtStates != null)
			throw new IllegalStateException("Attempted to execute ATs when block's local AT state data already exists");

		// AT-Transactions generated by running ATs, to be prepended to block's transactions
		List<AtTransaction> allAtTransactions = new ArrayList<>();

		this.ourAtStates = new ArrayList<>();
		this.ourAtFees = 0;

		// Find all executable ATs, ordered by earliest creation date first
		List<ATData> executableATs = this.repository.getATRepository().getAllExecutableATs();

		// Run each AT, appends AT-Transactions and corresponding AT states, to our lists
		for (ATData atData : executableATs) {
			AT at = new AT(this.repository, atData);
			List<AtTransaction> atTransactions = at.run(this.blockData.getHeight(), this.blockData.getTimestamp());
			ATStateData atStateData = at.getATStateData();
			// Didn't execute? (e.g. sleeping)
			if (atStateData == null)
				continue;

			allAtTransactions.addAll(atTransactions);
			this.ourAtStates.add(atStateData);
			this.ourAtFees += atStateData.getFees();
		}

		// AT Transactions never need approval
		allAtTransactions.forEach(transaction -> transaction.getTransactionData().setApprovalStatus(ApprovalStatus.NOT_REQUIRED));

		// Prepend our entire AT-Transactions/states to block's transactions
		this.transactions.addAll(0, allAtTransactions);

		// Re-sort
		this.transactions.sort(Transaction.getComparator());

		// AT Transactions do not affect block's transaction count

		// AT Transactions do not affect block's transaction signature
	}

	/** Returns whether block's minter is actually allowed to mint this block. */
	protected boolean isMinterValid(Block parentBlock) throws DataException {
		// Qortal: block's minter public key must be known reward-share public key
		RewardShareData rewardShareData = this.repository.getAccountRepository().getRewardShare(this.blockData.getMinterPublicKey());
		if (rewardShareData == null)
			return false;

		Account mintingAccount = new PublicKeyAccount(this.repository, rewardShareData.getMinterPublicKey());
		return mintingAccount.canMint(false);
	}

	/**
	 * Pre-process block, and its transactions.
	 * This allows for any database integrity checks prior to validation.
	 * This is called before isValid() and process()
	 *
	 * @throws DataException
	 */
	public void preProcess() throws DataException {
		List<Transaction> blocksTransactions = this.getTransactions();

		for (Transaction transaction : blocksTransactions) {
			transaction.preProcess();
		}
	}

	/**
	 * Process block, and its transactions, adding them to the blockchain.
	 *
	 * @throws DataException
	 */
	public void process() throws DataException {
		// Set our block's height
		int blockchainHeight = this.repository.getBlockRepository().getBlockchainHeight();
		this.blockData.setHeight(blockchainHeight + 1);

		LOGGER.trace(() -> String.format("Processing block %d", this.blockData.getHeight()));
		LOGGER.trace(() -> String.format("Online Reward Shares in process %s", this.cachedOnlineRewardShares));

		if (this.blockData.getHeight() > 1) {

			// Account levels and block rewards are only processed on block reward distribution blocks
			if (this.isRewardDistributionBlock()) {
				// Increase account levels
				increaseAccountLevels();

				// Distribute block rewards, including transaction fees, before transactions processed
				processBlockRewards();
			}

			if (!isTestnet) {
				if (this.blockData.getHeight() == 212937) {
					// Apply fix for block 212937
					Block212937.processFix(this);
				} else if (this.blockData.getHeight() == 1333492) {
					// Apply fix for block 1333492
					Block1333492.processFix(this);
				} else if (InvalidBalanceBlocks.isAffectedBlock(this.blockData.getHeight())) {
					// Apply fix for affected balance blocks
					InvalidBalanceBlocks.processFix(this);
				} else if (this.blockData.getHeight() == BlockChain.getInstance().getSelfSponsorshipAlgoV1Height()) {
					SelfSponsorshipAlgoV1Block.processAccountPenalties(this);
				} else if (this.blockData.getHeight() == BlockChain.getInstance().getSelfSponsorshipAlgoV2Height()) {
					SelfSponsorshipAlgoV2Block.processAccountPenalties(this);
				} else if (this.blockData.getHeight() == BlockChain.getInstance().getSelfSponsorshipAlgoV3Height()) {
					SelfSponsorshipAlgoV3Block.processAccountPenalties(this);
				} else if (this.blockData.getHeight() == BlockChain.getInstance().getMultipleNamesPerAccountHeight()) {
					PrimaryNamesBlock.processNames(this.repository);
				}
			}
		}

		// We're about to (test-)process a batch of transactions,
		// so create an account reference cache so get/set correct last-references.
		try (AccountRefCache accountRefCache = new AccountRefCache(this.repository)) {
			// Process transactions (we'll link them to this block after saving the block itself)
			processTransactions();

			// Group-approval transactions
			processGroupApprovalTransactions();

			// Process AT fees and save AT states into repository
			processAtFeesAndStates();

			// Commit new accounts' last-reference changes
			accountRefCache.commit();
		}

		// Link block into blockchain by fetching signature of highest block and setting that as our reference
		BlockData latestBlockData = this.repository.getBlockRepository().fromHeight(blockchainHeight);
		if (latestBlockData != null)
			this.blockData.setReference(latestBlockData.getSignature());

		// Save block
		this.repository.getBlockRepository().save(this.blockData);

		// Link transactions to this block, thus removing them from unconfirmed transactions list.
		// Also update "transaction participants" in repository for "transactions involving X" support in API
		linkTransactionsToBlock();

		postBlockTidy();

		// Log some debugging info relating to the block weight calculation
		this.logDebugInfo();
	}

	protected void increaseAccountLevels() throws DataException {
		// We are only interested in accounts that are NOT already lowest level
		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;

		final List<ExpandedAccount> expandedAccounts;

		if (this.getBlockData().getHeight() < BlockChain.getInstance().getFixBatchRewardHeight()) {
			expandedAccounts = this.getExpandedAccounts().stream().collect(Collectors.toList());
		}
		else {
			expandedAccounts
				= this.getExpandedAccounts().stream()
					.filter(expandedAccount -> expandedAccount.isMinterMember)
					.collect(Collectors.toList());
		}

		Set<AccountData> allUniqueExpandedAccounts = new HashSet<>();
		for (ExpandedAccount expandedAccount : expandedAccounts) {
			allUniqueExpandedAccounts.add(expandedAccount.mintingAccountData);

			if (!expandedAccount.isRecipientAlsoMinter)
				allUniqueExpandedAccounts.add(expandedAccount.recipientAccountData);
		}

		// Increase blocks minted count for all accounts
		int delta = 1;
		if (this.isBatchRewardDistributionActive()) {
			delta = BlockChain.getInstance().getBlockRewardBatchSize();
		}

		// Batch update in repository
		repository.getAccountRepository().modifyMintedBlockCounts(allUniqueExpandedAccounts.stream().map(AccountData::getAddress).collect(Collectors.toList()), +delta);

		// Keep track of level bumps in case we need to apply to other entries
		Map<String, Integer> bumpedAccounts = new HashMap<>();

		// Local changes and also checks for level bump
		for (AccountData accountData : allUniqueExpandedAccounts) {
			// Adjust count locally (in Java)
			accountData.setBlocksMinted(accountData.getBlocksMinted() + 1);
			LOGGER.trace(() -> String.format("Block minter %s up to %d minted block%s", accountData.getAddress(), accountData.getBlocksMinted(), (accountData.getBlocksMinted() != 1 ? "s" : "")));

			int blocksMintedAdjustment
				=
				(this.blockData.getHeight() > BlockChain.getInstance().getMintedBlocksAdjustmentRemovalHeight())
				?
				0
				:
				accountData.getBlocksMintedAdjustment();

			final int effectiveBlocksMinted = accountData.getBlocksMinted() + blocksMintedAdjustment + accountData.getBlocksMintedPenalty();

			for (int newLevel = maximumLevel; newLevel >= 0; --newLevel)
				if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
					if (newLevel != accountData.getLevel()) {
						// Account has increased in level!
						accountData.setLevel(newLevel);
						bumpedAccounts.put(accountData.getAddress(), newLevel);
						repository.getAccountRepository().setLevel(accountData);
						LOGGER.trace(() -> String.format("Block minter %s bumped to level %d", accountData.getAddress(), accountData.getLevel()));
					}

					break;
				}
		}

		// Also bump other entries if need be
		if (!bumpedAccounts.isEmpty()) {
			for (ExpandedAccount expandedAccount : expandedAccounts) {
				Integer newLevel = bumpedAccounts.get(expandedAccount.mintingAccountData.getAddress());
				if (newLevel != null && expandedAccount.mintingAccountData.getLevel() != newLevel) {
					expandedAccount.mintingAccountData.setLevel(newLevel);
					LOGGER.trace("Also bumped {} to level {}", expandedAccount.mintingAccountData.getAddress(), newLevel);
				}

				if (!expandedAccount.isRecipientAlsoMinter) {
					newLevel = bumpedAccounts.get(expandedAccount.recipientAccountData.getAddress());
					if (newLevel != null && expandedAccount.recipientAccountData.getLevel() != newLevel) {
						expandedAccount.recipientAccountData.setLevel(newLevel);
						LOGGER.trace("Also bumped {} to level {}", expandedAccount.recipientAccountData.getAddress(), newLevel);
					}
				}
			}
		}
	}

	protected void processBlockRewards() throws DataException {
		// General block reward
		long reward = BlockChain.getInstance().getRewardAtHeight(this.blockData.getHeight());

		if (this.isBatchRewardDistributionActive()) {
			// Batch distribution is active - so multiply the reward by the batch size
			reward *= BlockChain.getInstance().getBlockRewardBatchSize();

			if (!this.isRewardDistributionBlock()) {
				// Shouldn't ever happen, but checking here for safety
				throw new DataException("Attempted to distribute a batch reward in a non-reward-distribution block");
			}

			// Add transaction fees since last distribution block
			int firstBlock = this.getBlockData().getHeight() - BlockChain.getInstance().getBlockRewardBatchSize() + 1;
			int lastBlock = this.blockData.getHeight() - 1;
			Long totalFees = repository.getBlockRepository().getTotalFeesInBlockRange(firstBlock, lastBlock);
			if (totalFees == null) {
				throw new DataException("Unable to calculate total fees for block range");
			}
			reward += totalFees;
			LOGGER.debug("Total fees for range {} - {} when processing: {}", firstBlock, lastBlock, totalFees);
		}

		// Add transaction fees for this block (it was excluded from the range above as it's not in the repository yet)
		reward += this.blockData.getTotalFees();
		LOGGER.debug("Total fees when processing block {}: {}", this.blockData.getHeight(), this.blockData.getTotalFees());

		LOGGER.debug("Block reward when processing block {}: {}", this.blockData.getHeight(), reward);

		// Nothing to reward?
		if (reward <= 0)
			return;

		distributeBlockReward(reward);
	}

	protected void processTransactions() throws DataException {
		// Process transactions (we'll link them to this block after saving the block itself)
		// AT-generated transactions are already prepended to our transactions at this point.
		List<Transaction> blocksTransactions = this.getTransactions();

		for (Transaction transaction : blocksTransactions) {
			TransactionData transactionData = transaction.getTransactionData();

			// AT_TRANSACTIONs are created locally and need saving into repository before processing
			if (transactionData.getType() == TransactionType.AT)
				this.repository.getTransactionRepository().save(transactionData);

			// Only process transactions that don't require group-approval.
			// Group-approval transactions are dealt with later.
			if (transactionData.getApprovalStatus() == ApprovalStatus.NOT_REQUIRED)
				transaction.process();

			// Regardless of group-approval, update relevant info for creator (e.g. lastReference)
			transaction.processReferencesAndFees();
		}
	}

	protected void processGroupApprovalTransactions() throws DataException {
		TransactionRepository transactionRepository = this.repository.getTransactionRepository();

		// Search for pending transactions that have now expired
		List<TransactionData> approvalExpiringTransactions = transactionRepository.getApprovalExpiringTransactions(this.blockData.getHeight());

		for (TransactionData transactionData : approvalExpiringTransactions) {
			transactionData.setApprovalStatus(ApprovalStatus.EXPIRED);
			transactionRepository.save(transactionData);

			// Update group-approval decision height for transaction in repository
			transactionRepository.updateApprovalHeight(transactionData.getSignature(), this.blockData.getHeight());
		}

		// Search for pending transactions within min/max block delay range
		List<TransactionData> approvalPendingTransactions = transactionRepository.getApprovalPendingTransactions(this.blockData.getHeight());

		for (TransactionData transactionData : approvalPendingTransactions) {
			Transaction transaction = Transaction.fromData(this.repository, transactionData);

			// something like:
			Boolean isApproved = transaction.getApprovalDecision();

			if (isApproved == null)
				continue; // approve/reject threshold not yet met

			// Update group-approval decision height for transaction in repository
			transactionRepository.updateApprovalHeight(transactionData.getSignature(), this.blockData.getHeight());

			if (!isApproved) {
				// REJECT
				transactionData.setApprovalStatus(ApprovalStatus.REJECTED);
				transactionRepository.save(transactionData);
				continue;
			}

			// Approved, but check transaction can still be processed
			if (transaction.isProcessable() != Transaction.ValidationResult.OK) {
				transactionData.setApprovalStatus(ApprovalStatus.INVALID);
				transactionRepository.save(transactionData);
				continue;
			}

			// APPROVED, process transaction
			transactionData.setApprovalStatus(ApprovalStatus.APPROVED);
			transactionRepository.save(transactionData);

			transaction.process();
		}
	}

	protected void processAtFeesAndStates() throws DataException {
		ATRepository atRepository = this.repository.getATRepository();

		for (ATStateData atStateData : this.ourAtStates) {
			Account atAccount = new Account(this.repository, atStateData.getATAddress());

			// Subtract AT-generated fees from AT accounts
			atAccount.modifyAssetBalance(Asset.QORT, - atStateData.getFees());

			// Update AT info with latest state
			ATData atData = atRepository.fromATAddress(atStateData.getATAddress());

			AT at = new AT(repository, atData, atStateData);
			at.update(this.blockData.getHeight(), this.blockData.getTimestamp());
		}
	}

	protected void linkTransactionsToBlock() throws DataException {
		TransactionRepository transactionRepository = this.repository.getTransactionRepository();

		for (int sequence = 0; sequence < transactions.size(); ++sequence) {
			Transaction transaction = transactions.get(sequence);
			TransactionData transactionData = transaction.getTransactionData();

			// Link transaction to this block
			BlockTransactionData blockTransactionData = new BlockTransactionData(this.getSignature(), sequence,
					transactionData.getSignature());
			this.repository.getBlockRepository().save(blockTransactionData);

			// Update transaction's height in repository and local transactionData
			transactionRepository.updateBlockHeight(transactionData.getSignature(), this.blockData.getHeight());
			transaction.getTransactionData().setBlockHeight(this.blockData.getHeight());

			// Update transaction's sequence in repository and local transactionData
			transactionRepository.updateBlockSequence(transactionData.getSignature(), sequence);
			transaction.getTransactionData().setBlockSequence(sequence);

			// No longer unconfirmed
			transactionRepository.confirmTransaction(transactionData.getSignature());

			List<String> participantAddresses = transaction.getInvolvedAddresses();
			transactionRepository.saveParticipants(transactionData, participantAddresses);
		}
	}

	/**
	 * Removes block from blockchain undoing transactions and adding them to unconfirmed pile.
	 *
	 * @throws DataException
	 */
	public void orphan() throws DataException {
		LOGGER.trace(() -> String.format("Orphaning block %d", this.blockData.getHeight()));

		// Log some debugging info relating to the block weight calculation
		this.logDebugInfo();

		// Return AT fees and delete AT states from repository
		orphanAtFeesAndStates();

		// Orphan, and unlink, transactions from this block
		orphanTransactionsFromBlock();

		// Undo any group-approval decisions that happen at this block
		orphanGroupApprovalTransactions();

		if (this.blockData.getHeight() > 1) {
			// Invalidate expandedAccounts as they may have changed due to orphaning TRANSFER_PRIVS transactions, etc.
			this.cachedExpandedAccounts = null;

			if (!isTestnet) {
				if (this.blockData.getHeight() == 212937) {
					// Revert fix for block 212937
					Block212937.orphanFix(this);
				} else if (this.blockData.getHeight() == 1333492) {
					// Revert fix for block 1333492
					Block1333492.orphanFix(this);
				} else if (InvalidBalanceBlocks.isAffectedBlock(this.blockData.getHeight())) {
					// Revert fix for affected balance blocks
					InvalidBalanceBlocks.orphanFix(this);
				} else if (this.blockData.getHeight() == BlockChain.getInstance().getSelfSponsorshipAlgoV1Height()) {
					SelfSponsorshipAlgoV1Block.orphanAccountPenalties(this);
				} else if (this.blockData.getHeight() == BlockChain.getInstance().getSelfSponsorshipAlgoV2Height()) {
					SelfSponsorshipAlgoV2Block.orphanAccountPenalties(this);
				} else if (this.blockData.getHeight() == BlockChain.getInstance().getSelfSponsorshipAlgoV3Height()) {
					SelfSponsorshipAlgoV3Block.orphanAccountPenalties(this);
				} else if (this.blockData.getHeight() == BlockChain.getInstance().getMultipleNamesPerAccountHeight()) {
					PrimaryNamesBlock.orphanNames( this.repository );
				}
			}

			// Account levels and block rewards are only processed/orphaned on block reward distribution blocks
			if (this.isRewardDistributionBlock()) {
				// Block rewards, including transaction fees, removed after transactions undone
				orphanBlockRewards();

				// Decrease account levels
				decreaseAccountLevels();
			}
		}

		// Delete block from blockchain
		this.repository.getBlockRepository().delete(this.blockData);
		this.blockData.setHeight(null);

		postBlockTidy();
	}

	protected void orphanTransactionsFromBlock() throws DataException {
		TransactionRepository transactionRepository = this.repository.getTransactionRepository();

		// AT-generated transactions are already added to our transactions so no special handling is needed here.
		List<Transaction> blocksTransactions = this.getTransactions();

		for (int sequence = blocksTransactions.size() - 1; sequence >= 0; --sequence) {
			Transaction transaction = blocksTransactions.get(sequence);
			TransactionData transactionData = transaction.getTransactionData();

			// Orphan transaction
			// Only orphan transactions that didn't require group-approval.
			// Group-approval transactions are dealt with later.
			if (transactionData.getApprovalStatus() == ApprovalStatus.NOT_REQUIRED)
				transaction.orphan();

			// Regardless of group-approval, update relevant info for creator (e.g. lastReference)
			transaction.orphanReferencesAndFees();

			// Unlink transaction from this block
			BlockTransactionData blockTransactionData = new BlockTransactionData(this.getSignature(), sequence,
					transactionData.getSignature());
			this.repository.getBlockRepository().delete(blockTransactionData);

			// Add to unconfirmed pile and remove height, or delete if AT_TRANSACTION
			if (transaction.getTransactionData().getType() == TransactionType.AT) {
				transactionRepository.delete(transactionData);
			} else {
				// Add to unconfirmed pile
				transactionRepository.unconfirmTransaction(transactionData);

				// Unset height
				transactionRepository.updateBlockHeight(transactionData.getSignature(), null);

				// Unset sequence
				transactionRepository.updateBlockSequence(transactionData.getSignature(), null);
			}

			transactionRepository.deleteParticipants(transactionData);
		}
	}

	protected void orphanGroupApprovalTransactions() throws DataException {
		TransactionRepository transactionRepository = this.repository.getTransactionRepository();

		// Find all transactions where decision happened at this block height
		List<TransactionData> approvedTransactions = transactionRepository.getApprovalTransactionDecidedAtHeight(this.blockData.getHeight());

		for (TransactionData transactionData : approvedTransactions) {
			// Orphan/un-process transaction (if approved)
			Transaction transaction = Transaction.fromData(repository, transactionData);
			if (transactionData.getApprovalStatus() == ApprovalStatus.APPROVED)
				transaction.orphan();

			// Revert back to PENDING
			transactionData.setApprovalStatus(ApprovalStatus.PENDING);
			transactionRepository.save(transactionData);

			// Remove group-approval decision height
			transactionRepository.updateApprovalHeight(transactionData.getSignature(), null);
		}
	}

	protected void orphanBlockRewards() throws DataException {
		// General block reward
		long reward = BlockChain.getInstance().getRewardAtHeight(this.blockData.getHeight());

		if (this.isBatchRewardDistributionActive()) {
			// Batch distribution is active - so multiply the reward by the batch size
			reward *= BlockChain.getInstance().getBlockRewardBatchSize();

			if (!this.isRewardDistributionBlock()) {
				// Shouldn't ever happen, but checking here for safety
				throw new DataException("Attempted to orphan batched rewards in a non-reward-distribution block");
			}

			// Add transaction fees since last distribution block
			int firstBlock = this.getBlockData().getHeight() - BlockChain.getInstance().getBlockRewardBatchSize() + 1;
			int lastBlock = this.blockData.getHeight() - 1;
			Long totalFees = repository.getBlockRepository().getTotalFeesInBlockRange(firstBlock, lastBlock);
			if (totalFees == null) {
				throw new DataException("Unable to calculate total fees for block range");
			}
			reward += totalFees;
			LOGGER.debug("Total fees for range {} - {} when orphaning: {}", firstBlock, lastBlock, totalFees);
		}

		// Add transaction fees for this block (it was excluded from the range above as it's not in the repository yet)
		reward += this.blockData.getTotalFees();
		LOGGER.debug("Total fees when orphaning block {}: {}", this.blockData.getHeight(), this.blockData.getTotalFees());

		LOGGER.debug("Block reward when orphaning block {}: {}", this.blockData.getHeight(), reward);

		// Nothing to reward?
		if (reward <= 0)
			return;

		distributeBlockReward(- reward);
	}

	protected void orphanAtFeesAndStates() throws DataException {
		ATRepository atRepository = this.repository.getATRepository();
		for (ATStateData atStateData : this.getATStates()) {
			Account atAccount = new Account(this.repository, atStateData.getATAddress());

			// Return AT-generated fees to AT accounts
			atAccount.modifyAssetBalance(Asset.QORT, atStateData.getFees());

			// Revert AT info to prior values
			ATData atData = atRepository.fromATAddress(atStateData.getATAddress());

			AT at = new AT(repository, atData, atStateData);
			at.revert(this.blockData.getHeight(), this.blockData.getTimestamp());
		}
	}

	protected void decreaseAccountLevels() throws DataException {
		// We are only interested in accounts that are NOT already lowest level
		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;

		final List<ExpandedAccount> expandedAccounts;

		if (this.getBlockData().getHeight() < BlockChain.getInstance().getFixBatchRewardHeight()) {
			expandedAccounts = this.getExpandedAccounts().stream().collect(Collectors.toList());
		}
		else {
			expandedAccounts
				= this.getExpandedAccounts().stream()
					.filter(expandedAccount -> expandedAccount.isMinterMember)
					.collect(Collectors.toList());
		}

		Set<AccountData> allUniqueExpandedAccounts = new HashSet<>();
		for (ExpandedAccount expandedAccount : expandedAccounts) {
			allUniqueExpandedAccounts.add(expandedAccount.mintingAccountData);

			if (!expandedAccount.isRecipientAlsoMinter)
				allUniqueExpandedAccounts.add(expandedAccount.recipientAccountData);
		}

		// Decrease blocks minted count for all accounts
		int delta = 1;
		if (this.isBatchRewardDistributionActive()) {
			delta = BlockChain.getInstance().getBlockRewardBatchSize();
		}

		// Batch update in repository
		repository.getAccountRepository().modifyMintedBlockCounts(allUniqueExpandedAccounts.stream().map(AccountData::getAddress).collect(Collectors.toList()), -delta);

		for (AccountData accountData : allUniqueExpandedAccounts) {
			// Adjust count locally (in Java)
			accountData.setBlocksMinted(accountData.getBlocksMinted() - 1);
			LOGGER.trace(() -> String.format("Block minter %s down to %d minted block%s", accountData.getAddress(), accountData.getBlocksMinted(), (accountData.getBlocksMinted() != 1 ? "s" : "")));

			int blocksMintedAdjustment
				=
				(this.blockData.getHeight() -1 > BlockChain.getInstance().getMintedBlocksAdjustmentRemovalHeight())
				?
				0
				:
				accountData.getBlocksMintedAdjustment();

			final int effectiveBlocksMinted = accountData.getBlocksMinted() + blocksMintedAdjustment + accountData.getBlocksMintedPenalty();

			for (int newLevel = maximumLevel; newLevel >= 0; --newLevel)
				if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
					if (newLevel != accountData.getLevel()) {
						// Account has decreased in level!
						accountData.setLevel(newLevel);
						repository.getAccountRepository().setLevel(accountData);
						LOGGER.trace(() -> String.format("Block minter %s reduced to level %d", accountData.getAddress(), accountData.getLevel()));
					}

					break;
				}
		}
	}


	/**
	 * Specifies whether the batch reward feature trigger has activated yet.
	 * Note that the exact block of the feature trigger activation will return false,
	 * because this is actually the very last block with non-batched reward distributions.
	 *
	 * @return true if active, false if batch rewards feature trigger height not reached yet.
	 */
	public boolean isBatchRewardDistributionActive() {
		return Block.isBatchRewardDistributionActive(this.blockData.getHeight());
	}
	public static boolean isBatchRewardDistributionActive(int height) {
		// Once the getBlockRewardBatchStartHeight is reached, reward distributions per block must stop.
		// Note the > instead of >= below, as the first batch distribution isn't until 1000 blocks *after* the
		// start height. The block exactly matching the start height is not batched.
		return height > BlockChain.getInstance().getBlockRewardBatchStartHeight();
	}


	/**
	 * Specifies whether rewards are distributed in this block, via ANY method (batch or single).
	 *
	 * @return true if rewards are to be distributed in this block.
	 */
	public boolean isRewardDistributionBlock() {
		return Block.isRewardDistributionBlock(this.blockData.getHeight());
	}

	public static boolean isRewardDistributionBlock(int height) {
		// Up to and *including* the start height (feature trigger), the rewards are distributed in every block
		if (!Block.isBatchRewardDistributionActive(height)) {
			return true;
		}

		// After the start height (feature trigger) the rewards are distributed in blocks that are multiples of the batch size
		return height % BlockChain.getInstance().getBlockRewardBatchSize() == 0;
	}


	/**
	 * Specifies whether BATCH rewards are distributed in this block.
	 *
	 * @return true if a batch distribution will occur, false if a single no distribution will occur.
	 */
	public boolean isBatchRewardDistributionBlock() {
		return Block.isBatchRewardDistributionBlock(this.blockData.getHeight());
	}

	public static boolean isBatchRewardDistributionBlock(int height) {
		// Up to and *including* the start height (feature trigger), batch reward distribution isn't active yet
		if (!Block.isBatchRewardDistributionActive(height)) {
			return false;
		}

		// After the start height (feature trigger) the rewards are distributed in blocks that are multiples of the batch size
		return height % BlockChain.getInstance().getBlockRewardBatchSize() == 0;
	}


	/**
	 * Specifies whether online accounts are to be included in this block.
	 *
	 * @return true if online accounts should be included, false if they should be excluded.
	 */
	public boolean isOnlineAccountsBlock() {
		return Block.isOnlineAccountsBlock(this.getBlockData().getHeight());
	}

	public static boolean isOnlineAccountsBlock(int height) {
		// After feature trigger, only certain blocks contain online accounts
		if (height >= BlockChain.getInstance().getBlockRewardBatchStartHeight()) {
			final int leadingBlockCount = BlockChain.getInstance().getBlockRewardBatchAccountsBlockCount();
			return height >= (getNextBatchDistributionBlockHeight(height) - leadingBlockCount);
		}
		// Before feature trigger, all blocks contain online accounts
		return true;
	}


	/**
	 *
	 * @param currentHeight
	 *
	 * @return the next height of a batch reward distribution. Must only be called after the
	 * batch reward feature trigger has activated. It is not useful prior to this.
	 */
	private static int getNextBatchDistributionBlockHeight(int currentHeight) {
		final int batchSize = BlockChain.getInstance().getBlockRewardBatchSize();
		if (currentHeight % batchSize == 0) {
			// Already a reward distribution block
			return currentHeight;
		} else {
			// Calculate the difference needed to reach the next distribution block
			final int difference = batchSize - (currentHeight % batchSize);
			return currentHeight + difference;
		}
	}


	private static class BlockRewardCandidate {
		public final String description;
		public long share;
		public final BlockRewardDistributor distributionMethod;

		public BlockRewardCandidate(String description, long share, BlockRewardDistributor distributionMethod) {
			this.description = description;
			this.share = share;
			this.distributionMethod = distributionMethod;
		}

		public long distribute(long distibutionAmount, Map<String, Long> balanceChanges) throws DataException {
			return this.distributionMethod.distribute(distibutionAmount, balanceChanges);
		}
	}

	protected void distributeBlockReward(long totalAmount) throws DataException {
		final long totalAmountForLogging = totalAmount;
		LOGGER.trace(() -> String.format("Distributing: %s", Amounts.prettyAmount(totalAmountForLogging)));

		final boolean isProcessingNotOrphaning = totalAmount >= 0;

		// How to distribute reward among groups, with ratio, IN ORDER
		List<BlockRewardCandidate> rewardCandidates = determineBlockRewardCandidates(isProcessingNotOrphaning);

		// Now distribute to candidates

		// Collate all balance changes and then apply in one final step
		Map<String, Long> balanceChanges = new HashMap<>();

		long remainingAmount = totalAmount;
		for (int r = 0; r < rewardCandidates.size(); ++r) {
			BlockRewardCandidate rewardCandidate = rewardCandidates.get(r);

			// Distribute to these reward candidate accounts
			final long distributionAmount = Amounts.roundDownScaledMultiply(totalAmount, rewardCandidate.share);

			long sharedAmount = rewardCandidate.distribute(distributionAmount, balanceChanges);
			remainingAmount -= sharedAmount;

			// Reallocate any amount we didn't distribute, e.g. from maxxed legacy QORA holders
			if (sharedAmount != distributionAmount)
				totalAmount += Amounts.scaledDivide(distributionAmount - sharedAmount, 1_00000000 - rewardCandidate.share);

			final long remainingAmountForLogging = remainingAmount;
			LOGGER.trace(() -> String.format("%s share: %s. Actually shared: %s. Remaining: %s",
					rewardCandidate.description,
					Amounts.prettyAmount(distributionAmount),
					Amounts.prettyAmount(sharedAmount),
					Amounts.prettyAmount(remainingAmountForLogging)));
		}

		// Apply balance changes
		List<AccountBalanceData> accountBalanceDeltas = balanceChanges.entrySet().stream()
				.map(entry -> new AccountBalanceData(entry.getKey(), Asset.QORT, entry.getValue()))
				.collect(Collectors.toList());
		LOGGER.trace("Account Balance Deltas: {}", accountBalanceDeltas);
		this.repository.getAccountRepository().modifyAssetBalances(accountBalanceDeltas);
	}

	protected List<BlockRewardCandidate> determineBlockRewardCandidates(boolean isProcessingNotOrphaning) throws DataException {
		// How to distribute reward among groups, with ratio, IN ORDER
		List<BlockRewardCandidate> rewardCandidates = new ArrayList<>();

		// All online accounts
		final List<ExpandedAccount> expandedAccounts;

		if (this.getBlockData().getHeight() < BlockChain.getInstance().getFixBatchRewardHeight()) {
			expandedAccounts = this.getExpandedAccounts().stream().collect(Collectors.toList());
		}
		else {
			expandedAccounts
				= this.getExpandedAccounts().stream()
					.filter(expandedAccount -> expandedAccount.isMinterMember)
					.collect(Collectors.toList());
		}

		/*
		 * Distribution rules:
		 *
		 * Distribution is based on the minting account of 'online' reward-shares.
		 *
		 * If ANY founders are online, then they receive the leftover non-distributed reward.
		 * If NO founders are online, then account-level-based rewards are scaled up so 100% of reward is allocated.
		 *
		 * If ANY non-maxxed legacy QORA holders exist then they are always allocated their fixed share (e.g. 20%).
		 *
		 * There has to be either at least one 'online' account for blocks to be minted
		 * so there is always either one account-level-based or founder reward candidate.
		 *
		 * Examples:
		 *
		 * With at least one founder online:
		 * Level 1/2 accounts: 5%
		 * Legacy QORA holders: 20%
		 * Founders: ~75%
		 *
		 * No online founders:
		 * Level 1/2 accounts: 5%
		 * Level 5/6 accounts: 15%
		 * Legacy QORA holders: 20%
		 * Total: 40%
		 *
		 * After scaling account-level-based shares to fill 100%:
		 * Level 1/2 accounts: 20%
		 * Level 5/6 accounts: 60%
		 * Legacy QORA holders: 20%
		 * Total: 100%
		 */
		long totalShares = 0;

		// Determine whether we have any online founders
		final List<ExpandedAccount> onlineFounderAccounts = expandedAccounts.stream().filter(expandedAccount -> expandedAccount.isMinterFounder).collect(Collectors.toList());
		final boolean haveFounders = !onlineFounderAccounts.isEmpty();

		// Select the correct set of share bins based on block height
		List<AccountLevelShareBin> accountLevelShareBinsForBlock = (this.blockData.getHeight() >= BlockChain.getInstance().getSharesByLevelV2Height()) ?
				BlockChain.getInstance().getAccountLevelShareBinsV2() : BlockChain.getInstance().getAccountLevelShareBinsV1();
		// Determine reward candidates based on account level
		// This needs a deep copy, so the shares can be modified when tiers aren't activated yet
		List<AccountLevelShareBin> accountLevelShareBins = new ArrayList<>();
		for (AccountLevelShareBin accountLevelShareBin : accountLevelShareBinsForBlock) {
			accountLevelShareBins.add((AccountLevelShareBin) accountLevelShareBin.clone());
		}

		Map<Integer, List<ExpandedAccount>> accountsForShareBin = new HashMap<>();

		// We might need to combine some share bins if they haven't reached the minimum number of minters yet
		for (int binIndex = accountLevelShareBins.size()-1; binIndex >= 0; --binIndex) {
			AccountLevelShareBin accountLevelShareBin = accountLevelShareBins.get(binIndex);

			// Find all accounts in share bin. getShareBin() returns null for minter accounts that are also founders, so they are effectively filtered out.
			List<ExpandedAccount> binnedAccounts = expandedAccounts.stream().filter(accountInfo -> accountInfo.hasShareBin(accountLevelShareBin, this.blockData.getHeight())).collect(Collectors.toList());
			// Add any accounts that have been moved down from a higher tier
			List<ExpandedAccount> existingBinnedAccounts = accountsForShareBin.get(binIndex);
			if (existingBinnedAccounts != null)
				binnedAccounts.addAll(existingBinnedAccounts);

			// Logic below may only apply to higher levels, and only for share bins with a specific range of online accounts
			if (accountLevelShareBin.levels.get(0) < BlockChain.getInstance().getShareBinActivationMinLevel() ||
					binnedAccounts.isEmpty() || binnedAccounts.size() >= BlockChain.getInstance().getMinAccountsToActivateShareBin()) {
				// Add all accounts for this share bin to the accountsForShareBin list
				accountsForShareBin.put(binIndex, binnedAccounts);
				continue;
			}

			// Share bin contains more than one, but less than the minimum number of minters. We treat this share bin
			// as not activated yet. In these cases, the rewards and minters are combined and paid out to the previous
			// share bin, to prevent a single or handful of accounts receiving the entire rewards for a share bin.
			//
			// Example:
			//
			// - Share bin for levels 5 and 6 has 100 minters
			// - Share bin for levels 7 and 8 has 10 minters
			//
			// This is below the minimum of 30, so share bins are reconstructed as follows:
			//
			// - Share bin for levels 5 and 6 now contains 110 minters
			// - Share bin for levels 7 and 8 now contains 0 minters
			// - Share bin for levels 5 and 6 now pays out rewards for levels 5, 6, 7, and 8
			// - Share bin for levels 7 and 8 pays zero rewards
			//
			// This process is iterative, so will combine several tiers if needed.

			// Designate this share bin as empty
			accountsForShareBin.put(binIndex, new ArrayList<>());

			// Move the accounts originally intended for this share bin to the previous one
			accountsForShareBin.put(binIndex - 1, binnedAccounts);

			// Move the block reward from this share bin to the previous one
			AccountLevelShareBin previousShareBin = accountLevelShareBins.get(binIndex - 1);
			previousShareBin.share += accountLevelShareBin.share;
			accountLevelShareBin.share = 0L;
		}

		// Now loop through (potentially modified) share bins and determine the reward candidates
		for (int binIndex = 0; binIndex < accountLevelShareBins.size(); ++binIndex) {
			AccountLevelShareBin accountLevelShareBin = accountLevelShareBins.get(binIndex);
			List<ExpandedAccount> binnedAccounts = accountsForShareBin.get(binIndex);

			// No online accounts in this bin? Skip to next one
			if (binnedAccounts.isEmpty())
				continue;

			String description = String.format("Bin %d", binIndex);
			BlockRewardDistributor accountLevelBinDistributor = (distributionAmount, balanceChanges) -> distributeBlockRewardShare(distributionAmount, binnedAccounts, balanceChanges);

			BlockRewardCandidate rewardCandidate = new BlockRewardCandidate(description, accountLevelShareBin.share, accountLevelBinDistributor);
			rewardCandidates.add(rewardCandidate);

			totalShares += rewardCandidate.share;
		}

		// Fetch list of legacy QORA holders who haven't reached their cap of QORT reward.
		List<EligibleQoraHolderData> qoraHolders = this.repository.getAccountRepository().getEligibleLegacyQoraHolders(isProcessingNotOrphaning ? null : this.blockData.getHeight());
		final boolean haveQoraHolders = !qoraHolders.isEmpty();
		final long qoraHoldersShare = BlockChain.getInstance().getQoraHoldersShareAtHeight(this.blockData.getHeight());

		// Perform account-level-based reward scaling if appropriate
		if (!haveFounders && this.blockData.getHeight() < BlockChain.getInstance().getAdminsReplaceFoundersHeight() ) {
			// Recalculate distribution ratios based on candidates

			// Nothing shared? This shouldn't happen
			if (totalShares == 0)
				throw new DataException("Unexpected lack of block reward candidates?");

			// Re-scale individual reward candidate's share as if total shared was 100% - legacy QORA holders' share
			long scalingFactor;
			if (haveQoraHolders)
				scalingFactor = Amounts.scaledDivide(totalShares, 1_00000000 - qoraHoldersShare);
			else
				scalingFactor = totalShares;

			for (BlockRewardCandidate rewardCandidate : rewardCandidates)
				rewardCandidate.share = Amounts.scaledDivide(rewardCandidate.share, scalingFactor);
		}

		// Add legacy QORA holders as reward candidate with fixed share (if appropriate)
		if (haveQoraHolders) {
			// Yes: add to reward candidates list
			BlockRewardDistributor legacyQoraHoldersDistributor = (distributionAmount, balanceChanges) -> distributeBlockRewardToQoraHolders(distributionAmount, qoraHolders, balanceChanges, this);

			BlockRewardCandidate rewardCandidate = new BlockRewardCandidate("Legacy QORA holders", qoraHoldersShare, legacyQoraHoldersDistributor);

			if (haveFounders)
				// We have founders, so distribute legacy QORA holders just before founders so founders get any non-distributed
				rewardCandidates.add(rewardCandidate);
			else
				// No founder, so distribute legacy QORA holders first, so all account-level-based rewards get share of any non-distributed
				rewardCandidates.add(0, rewardCandidate);

			totalShares += rewardCandidate.share;
		}

		// Add founders as reward candidate if appropriate
		if (haveFounders && this.blockData.getHeight() < BlockChain.getInstance().getAdminsReplaceFoundersHeight()) {
			// Yes: add to reward candidates list
			BlockRewardDistributor founderDistributor = (distributionAmount, balanceChanges) -> distributeBlockRewardShare(distributionAmount, onlineFounderAccounts, balanceChanges);

			final long foundersShare = 1_00000000 - totalShares;
			BlockRewardCandidate rewardCandidate = new BlockRewardCandidate("Founders", foundersShare, founderDistributor);
			rewardCandidates.add(rewardCandidate);
			LOGGER.info("logging foundersShare prior to reward modifications {}",foundersShare);
		}
		else if (this.blockData.getHeight() >= BlockChain.getInstance().getAdminsReplaceFoundersHeight()) {
			try (final Repository repository = RepositoryManager.getRepository()) {
				GroupRepository groupRepository = repository.getGroupRepository();

				List<Integer> mintingGroupIds = Groups.getGroupIdsToMint(BlockChain.getInstance(), this.blockData.getHeight());

				// all minter admins
				List<String> minterAdmins = Groups.getAllAdmins(groupRepository, mintingGroupIds);

				// all minter admins that are online
				List<ExpandedAccount> onlineMinterAdminAccounts
					= expandedAccounts.stream()
						.filter(expandedAccount ->  minterAdmins.contains(expandedAccount.getMintingAccount().getAddress()))
						.collect(Collectors.toList());

				long minterAdminShare;

				if( onlineMinterAdminAccounts.isEmpty() ) {
					minterAdminShare = 0;
				}
				else {
					BlockRewardDistributor minterAdminDistributor
							= (distributionAmount, balanceChanges)
							->
							distributeBlockRewardShare(distributionAmount, onlineMinterAdminAccounts, balanceChanges);

					long adminShare = 1_00000000 - totalShares;
					LOGGER.info("initial total Shares: {}", totalShares);
					LOGGER.info("logging adminShare after hardfork, this is the primary reward that will be split {}", adminShare);

					minterAdminShare = adminShare / 2;
					BlockRewardCandidate minterAdminRewardCandidate
							= new BlockRewardCandidate("Minter Admins", minterAdminShare, minterAdminDistributor);
					rewardCandidates.add(minterAdminRewardCandidate);

					totalShares += minterAdminShare;
				}

				LOGGER.info("MINTER ADMIN SHARE: {}",minterAdminShare);

				// all dev admins
				List<String> devAdminAddresses
						= groupRepository.getGroupAdmins(1).stream()
						.map(GroupAdminData::getAdmin)
						.collect(Collectors.toList());

				LOGGER.debug("Removing NULL Account Address, Dev Admin Count = {}", devAdminAddresses.size());
				devAdminAddresses.removeIf( address -> Group.NULL_OWNER_ADDRESS.equals(address) );
				LOGGER.debug("Removed NULL Account Address, Dev Admin Count = {}", devAdminAddresses.size());

				BlockRewardDistributor devAdminDistributor
					= (distributionAmount, balanceChanges) -> distributeToAccounts(distributionAmount, devAdminAddresses, balanceChanges);

				long devAdminShare = 1_00000000 - totalShares;
				LOGGER.info("DEV ADMIN SHARE: {}",devAdminShare);
				BlockRewardCandidate devAdminRewardCandidate
					= new BlockRewardCandidate("Dev Admins", devAdminShare,devAdminDistributor);
				rewardCandidates.add(devAdminRewardCandidate);
			}
		}

		return rewardCandidates;
	}

	/**
	 * Distribute To Accounts
	 *
	 * Merges distribute shares to a map of distribution shares.
	 *
	 * @param distributionAmount the amount to distribute
	 * @param accountAddressess the addresses to distribute to
	 * @param balanceChanges the map of distribution shares, this gets appended to
	 *
	 * @return the total amount mapped to addresses for distribution
	 */
	public static long distributeToAccounts(long distributionAmount, List<String> accountAddressess, Map<String, Long> balanceChanges) {

		if( accountAddressess.isEmpty() ) return 0;

		long distibutionShare = distributionAmount / accountAddressess.size();

		for(String accountAddress : accountAddressess ) {
			balanceChanges.merge(accountAddress, distibutionShare, Long::sum);
		}

		return distibutionShare * accountAddressess.size();
	}

	private static long distributeBlockRewardShare(long distributionAmount, List<ExpandedAccount> accounts, Map<String, Long> balanceChanges) {
		// Collate all expanded accounts by minting account
		Map<String, List<ExpandedAccount>> accountsByMinter = new HashMap<>();

		for (ExpandedAccount expandedAccount : accounts)
			accountsByMinter.compute(expandedAccount.mintingAccount.getAddress(), (minterAddress, otherAccounts) -> {
				if (otherAccounts == null) {
					return new ArrayList<>(Arrays.asList(expandedAccount));
				} else {
					otherAccounts.add(expandedAccount);
					return otherAccounts;
				}
			});

		// Divide distribution amount by number of *minting* accounts
		long perMintingAccountAmount = distributionAmount / accountsByMinter.keySet().size();

		// Distribute, reducing totalAmount by how much was actually distributed
		long sharedAmount = 0;
		for (List<ExpandedAccount> recipientAccounts : accountsByMinter.values()) {
			long perRecipientAccountAmount = perMintingAccountAmount / recipientAccounts.size();

			for (ExpandedAccount expandedAccount : recipientAccounts)
				sharedAmount += expandedAccount.distribute(perRecipientAccountAmount, balanceChanges);
		}

		return sharedAmount;
	}

	private static long distributeBlockRewardToQoraHolders(long qoraHoldersAmount, List<EligibleQoraHolderData> qoraHolders, Map<String, Long> balanceChanges, Block block) throws DataException {
		final boolean isProcessingNotOrphaning = qoraHoldersAmount >= 0;

		long qoraPerQortReward = BlockChain.getInstance().getQoraPerQortReward();
		BigInteger qoraPerQortRewardBI = BigInteger.valueOf(qoraPerQortReward);

		long totalQoraHeld = 0;
		for (int i = 0; i < qoraHolders.size(); ++i)
			totalQoraHeld += qoraHolders.get(i).getQoraBalance();

		long finalTotalQoraHeld = totalQoraHeld;
		LOGGER.trace(() -> String.format("Total legacy QORA held: %s", Amounts.prettyAmount(finalTotalQoraHeld)));

		if (totalQoraHeld <= 0)
			return 0;

		// Could do with a faster 128bit integer library, but until then...
		BigInteger qoraHoldersAmountBI = BigInteger.valueOf(qoraHoldersAmount);
		BigInteger totalQoraHeldBI = BigInteger.valueOf(totalQoraHeld);

		long sharedAmount = 0;
		// For batched update of QORT_FROM_QORA balances
		List<AccountBalanceData> newQortFromQoraBalances = new ArrayList<>();

		for (int h = 0; h < qoraHolders.size(); ++h) {
			EligibleQoraHolderData qoraHolder = qoraHolders.get(h);
			BigInteger qoraHolderBalanceBI = BigInteger.valueOf(qoraHolder.getQoraBalance());
			String qoraHolderAddress = qoraHolder.getAddress();

			// This is where a 128bit integer library could help:
			// long holderReward = (qoraHoldersAmount * qoraHolder.getBalance()) / totalQoraHeld;
			long holderReward = qoraHoldersAmountBI.multiply(qoraHolderBalanceBI).divide(totalQoraHeldBI).longValue();

			final long holderRewardForLogging = holderReward;
			LOGGER.trace(() -> String.format("QORA holder %s has %s / %s QORA so share: %s",
					qoraHolderAddress, Amounts.prettyAmount(qoraHolder.getQoraBalance()), finalTotalQoraHeld, Amounts.prettyAmount(holderRewardForLogging)));

			// Too small to register this time?
			if (holderReward == 0)
				continue;

			long newQortFromQoraBalance = qoraHolder.getQortFromQoraBalance() + holderReward;

			// If processing, make sure we don't overpay
			if (isProcessingNotOrphaning) {
				long maxQortFromQora = Amounts.scaledDivide(qoraHolderBalanceBI, qoraPerQortRewardBI);

				if (newQortFromQoraBalance >= maxQortFromQora) {
					// Reduce final QORT-from-QORA payment to match max
					long adjustment = newQortFromQoraBalance - maxQortFromQora;

					holderReward -= adjustment;
					newQortFromQoraBalance -= adjustment;

					// This is also the QORA holder's final QORT-from-QORA block
					QortFromQoraData qortFromQoraData = new QortFromQoraData(qoraHolderAddress, holderReward, block.blockData.getHeight());
					block.repository.getAccountRepository().save(qortFromQoraData);

					long finalAdjustedHolderReward = holderReward;
					LOGGER.trace(() -> String.format("QORA holder %s final share %s at height %d",
							qoraHolderAddress, Amounts.prettyAmount(finalAdjustedHolderReward), block.blockData.getHeight()));
				}
			} else {
				// Orphaning
				if (qoraHolder.getFinalBlockHeight() != null) {
					// Final QORT-from-QORA amount from repository was stored during processing, and hence positive.
					// So we use + here as qortFromQora is negative during orphaning.
					// More efficient than "holderReward - (0 - final-qort-from-qora)"
					long adjustment = holderReward + qoraHolder.getFinalQortFromQora();

					holderReward -= adjustment;
					newQortFromQoraBalance -= adjustment;

					block.repository.getAccountRepository().deleteQortFromQoraInfo(qoraHolderAddress);

					long finalAdjustedHolderReward = holderReward;
					LOGGER.trace(() -> String.format("QORA holder %s final share %s was at height %d",
							qoraHolderAddress, Amounts.prettyAmount(finalAdjustedHolderReward), block.blockData.getHeight()));
				}
			}

			balanceChanges.merge(qoraHolderAddress, holderReward, Long::sum);

			// Add to batched QORT_FROM_QORA balance update list
			newQortFromQoraBalances.add(new AccountBalanceData(qoraHolderAddress, Asset.QORT_FROM_QORA, newQortFromQoraBalance));

			sharedAmount += holderReward;
		}

		// Perform batched update of QORT_FROM_QORA balances
		block.repository.getAccountRepository().setAssetBalances(newQortFromQoraBalances);

		return sharedAmount;
	}

	/** Opportunity to tidy repository, etc. after block process/orphan. */
	private void postBlockTidy() throws DataException {
		this.repository.getAccountRepository().tidy();
	}

	// Utils

	/**
	 * Find index of rewardSharePublicKey in list of rewardSharePublicKeys
	 *
	 * @param rewardSharePublicKey - the key to query
	 * @param rewardSharePublicKeys - a sorted list of keys
	 * @return - the index of the key, or null if not found
	 */
	private static Integer getRewardShareIndex(byte[] rewardSharePublicKey, List<byte[]> rewardSharePublicKeys) {
		int index = 0;
		for (byte[] publicKey : rewardSharePublicKeys) {
			if (Arrays.equals(rewardSharePublicKey, publicKey)) {
				return index;
			}
			index++;
		}
		return null;
	}

	private void logDebugInfo() {
		try {
			// Avoid calculations if possible. We have to check against INFO here, since Level.isMoreSpecificThan() confusingly uses <= rather than just <
			if (LOGGER.getLevel().isMoreSpecificThan(Level.INFO))
				return;

			if (this.repository == null || this.getMinter() == null || this.getBlockData() == null)
				return;

			int minterLevel = Account.getRewardShareEffectiveMintingLevel(this.repository, this.getMinter().getPublicKey());
			String minterAddress = Account.getRewardShareMintingAddress(this.repository, this.getMinter().getPublicKey());

			LOGGER.debug(String.format("======= BLOCK %d (%.8s) =======", this.getBlockData().getHeight(), Base58.encode(this.getSignature())));
			LOGGER.debug(String.format("Timestamp: %d", this.getBlockData().getTimestamp()));
			LOGGER.debug(String.format("Minter address: %s", minterAddress));
			LOGGER.debug(String.format("Minter level: %d", minterLevel));
			LOGGER.debug(String.format("Online accounts: %d", this.getBlockData().getOnlineAccountsCount()));
			LOGGER.debug(String.format("AT count: %d", this.getBlockData().getATCount()));

			BlockSummaryData blockSummaryData = new BlockSummaryData(this.getBlockData());
			if (this.getParent() == null || this.getParent().getSignature() == null || blockSummaryData == null || minterLevel == 0)
				return;

			blockSummaryData.setMinterLevel(minterLevel);
			BigInteger blockWeight = calcBlockWeight(this.getParent().getHeight(), this.getParent().getSignature(), blockSummaryData);
			BigInteger keyDistance = calcKeyDistance(this.getParent().getHeight(), this.getParent().getSignature(), blockSummaryData.getMinterPublicKey(), blockSummaryData.getMinterLevel());
			NumberFormat formatter = new DecimalFormat("0.###E0");

			LOGGER.debug(String.format("Key distance: %s", formatter.format(keyDistance)));
			LOGGER.debug(String.format("Weight: %s", formatter.format(blockWeight)));

		} catch (DataException e) {
			LOGGER.info(() -> String.format("Unable to log block debugging info: %s", e.getMessage()));
		}
	}
}
