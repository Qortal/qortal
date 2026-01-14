package org.qortal.transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.controller.TransactionImporter;
import org.qortal.crypto.Crypto;
import org.qortal.data.block.BlockData;
import org.qortal.data.group.GroupApprovalData;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.GroupRepository;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.NTP;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public abstract class Transaction {

	// Transaction types
	public enum TransactionType {
		// NOTE: must be contiguous or reflection fails
		GENESIS(1, false),
		PAYMENT(2, false),
		REGISTER_NAME(3, true),
		UPDATE_NAME(4, true),
		SELL_NAME(5, false),
		CANCEL_SELL_NAME(6, false),
		BUY_NAME(7, false),
		CREATE_POLL(8, true),
		VOTE_ON_POLL(9, false),
		ARBITRARY(10, true),
		ISSUE_ASSET(11, true),
		TRANSFER_ASSET(12, false),
		CREATE_ASSET_ORDER(13, false),
		CANCEL_ASSET_ORDER(14, false),
		MULTI_PAYMENT(15, false),
		DEPLOY_AT(16, true),
		MESSAGE(17, true),
		CHAT(18, false),
		PUBLICIZE(19, false),
		AIRDROP(20, false),
		AT(21, false),
		CREATE_GROUP(22, true),
		UPDATE_GROUP(23, true),
		ADD_GROUP_ADMIN(24, true),
		REMOVE_GROUP_ADMIN(25, true),
		GROUP_BAN(26, true),
		CANCEL_GROUP_BAN(27, true),
		GROUP_KICK(28, true),
		GROUP_INVITE(29, true),
		CANCEL_GROUP_INVITE(30, true),
		JOIN_GROUP(31, false),
		LEAVE_GROUP(32, false),
		GROUP_APPROVAL(33, false),
		SET_GROUP(34, false),
		UPDATE_ASSET(35, true),
		ACCOUNT_FLAGS(36, false),
		ENABLE_FORGING(37, false),
		REWARD_SHARE(38, false),
		ACCOUNT_LEVEL(39, false),
		TRANSFER_PRIVS(40, false),
		PRESENCE(41, false);

		public final int value;
		public final boolean needsApproval;
		public final String valueString;
		public final String className;
		public final Class<?> clazz;
		public final Constructor<?> constructor;

		private static final Map<Integer, TransactionType> map = stream(TransactionType.values()).collect(toMap(type -> type.value, type -> type));

		TransactionType(int value, boolean needsApproval) {
			this.value = value;
			this.needsApproval = needsApproval;
			this.valueString = String.valueOf(value);

			String[] classNameParts = this.name().toLowerCase().split("_");

			for (int i = 0; i < classNameParts.length; ++i)
				classNameParts[i] = classNameParts[i].substring(0, 1).toUpperCase().concat(classNameParts[i].substring(1));

			this.className = String.join("", classNameParts);

			Class<?> subClazz = null;
			Constructor<?> subConstructor = null;

			try {
				subClazz = Class.forName(String.join("", Transaction.class.getPackage().getName(), ".", this.className, "Transaction"));

				try {
					subConstructor = subClazz.getConstructor(Repository.class, TransactionData.class);
				} catch (NoSuchMethodException | SecurityException e) {
					LOGGER.debug(String.format("Transaction subclass constructor not found for transaction type \"%s\"", this.name()));
				}
			} catch (ClassNotFoundException e) {
				LOGGER.debug(String.format("Transaction subclass not found for transaction type \"%s\"", this.name()));
			}

			this.clazz = subClazz;
			this.constructor = subConstructor;
		}

		public static TransactionType valueOf(int value) {
			return map.get(value);
		}
	}

	// Group-approval status
	public enum ApprovalStatus {
		NOT_REQUIRED(0),
		PENDING(1),
		APPROVED(2),
		REJECTED(3),
		EXPIRED(4),
		INVALID(5);

		public final int value;

		private static final Map<Integer, ApprovalStatus> map = stream(ApprovalStatus.values()).collect(toMap(result -> result.value, result -> result));

		ApprovalStatus(int value) {
			this.value = value;
		}

		public static ApprovalStatus valueOf(int value) {
			return map.get(value);
		}
	}

	// Validation results
	public enum ValidationResult {
		OK(1),
		INVALID_ADDRESS(2),
		NEGATIVE_AMOUNT(3),
		NEGATIVE_FEE(4),
		NO_BALANCE(5),
		INVALID_REFERENCE(6),
		INVALID_NAME_LENGTH(7),
		INVALID_VALUE_LENGTH(8),
		NAME_ALREADY_REGISTERED(9),
		NAME_DOES_NOT_EXIST(10),
		INVALID_NAME_OWNER(11),
		NAME_ALREADY_FOR_SALE(12),
		NAME_NOT_FOR_SALE(13),
		BUYER_ALREADY_OWNER(14),
		INVALID_AMOUNT(15),
		INVALID_SELLER(16),
		NAME_NOT_NORMALIZED(17),
		INVALID_DESCRIPTION_LENGTH(18),
		INVALID_OPTIONS_COUNT(19),
		INVALID_OPTION_LENGTH(20),
		DUPLICATE_OPTION(21),
		POLL_ALREADY_EXISTS(22),
		POLL_ALREADY_HAS_VOTES(23),
		POLL_DOES_NOT_EXIST(24),
		POLL_OPTION_DOES_NOT_EXIST(25),
		ALREADY_VOTED_FOR_THAT_OPTION(26),
		INVALID_DATA_LENGTH(27),
		INVALID_QUANTITY(28),
		ASSET_DOES_NOT_EXIST(29),
		INVALID_RETURN(30),
		HAVE_EQUALS_WANT(31),
		ORDER_DOES_NOT_EXIST(32),
		INVALID_ORDER_CREATOR(33),
		INVALID_PAYMENTS_COUNT(34),
		NEGATIVE_PRICE(35),
		INVALID_CREATION_BYTES(36),
		INVALID_TAGS_LENGTH(37),
		INVALID_AT_TYPE_LENGTH(38),
		INVALID_AT_TRANSACTION(39),
		INSUFFICIENT_FEE(40),
		ASSET_DOES_NOT_MATCH_AT(41),
		ASSET_ALREADY_EXISTS(43),
		MISSING_CREATOR(44),
		TIMESTAMP_TOO_OLD(45),
		TIMESTAMP_TOO_NEW(46),
		TOO_MANY_UNCONFIRMED(47),
		GROUP_ALREADY_EXISTS(48),
		GROUP_DOES_NOT_EXIST(49),
		INVALID_GROUP_OWNER(50),
		ALREADY_GROUP_MEMBER(51),
		GROUP_OWNER_CANNOT_LEAVE(52),
		NOT_GROUP_MEMBER(53),
		ALREADY_GROUP_ADMIN(54),
		NOT_GROUP_ADMIN(55),
		INVALID_LIFETIME(56),
		INVITE_UNKNOWN(57),
		BAN_EXISTS(58),
		BAN_UNKNOWN(59),
		BANNED_FROM_GROUP(60),
		JOIN_REQUEST_EXISTS(61),
		INVALID_GROUP_APPROVAL_THRESHOLD(62),
		GROUP_ID_MISMATCH(63),
		INVALID_GROUP_ID(64),
		TRANSACTION_UNKNOWN(65),
		TRANSACTION_ALREADY_CONFIRMED(66),
		INVALID_TX_GROUP_ID(67),
		TX_GROUP_ID_MISMATCH(68),
		MULTIPLE_NAMES_FORBIDDEN(69),
		INVALID_ASSET_OWNER(70),
		AT_IS_FINISHED(71),
		NO_FLAG_PERMISSION(72),
		NOT_MINTING_ACCOUNT(73),
		REWARD_SHARE_UNKNOWN(76),
		INVALID_REWARD_SHARE_PERCENT(77),
		PUBLIC_KEY_UNKNOWN(78),
		INVALID_PUBLIC_KEY(79),
		AT_UNKNOWN(80),
		AT_ALREADY_EXISTS(81),
		GROUP_APPROVAL_NOT_REQUIRED(82),
		GROUP_APPROVAL_DECIDED(83),
		MAXIMUM_REWARD_SHARES(84),
		TRANSACTION_ALREADY_EXISTS(85),
		NO_BLOCKCHAIN_LOCK(86),
		ORDER_ALREADY_CLOSED(87),
		CLOCK_NOT_SYNCED(88),
		ASSET_NOT_SPENDABLE(89),
		ACCOUNT_CANNOT_REWARD_SHARE(90),
		SELF_SHARE_EXISTS(91),
		ACCOUNT_ALREADY_EXISTS(92),
		INVALID_GROUP_BLOCK_DELAY(93),
		INCORRECT_NONCE(94),
		INVALID_TIMESTAMP_SIGNATURE(95),
		ADDRESS_BLOCKED(96),
		NAME_BLOCKED(97),
		GROUP_APPROVAL_REQUIRED(98),
		ACCOUNT_NOT_TRANSFERABLE(99),
		TRANSFER_PRIVS_DISABLED(100),
		TEMPORARY_DISABLED(101),
		GENERAL_TEMPORARY_DISABLED(102),
		INVALID_BUT_OK(999),
		NOT_YET_RELEASED(1000),
		NOT_SUPPORTED(1001);

		public final int value;

		private static final Map<Integer, ValidationResult> map = stream(ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

		ValidationResult(int value) {
			this.value = value;
		}

		public static ValidationResult valueOf(int value) {
			return map.get(value);
		}
	}

	private static final Logger LOGGER = LogManager.getLogger(Transaction.class);

	// Properties

	protected Repository repository;
	protected TransactionData transactionData;
	/** Cached creator account. Use <tt>getCreator()</tt> to access. */
	private PublicKeyAccount creator = null;

	// Constructors

	/**
	 * Basic constructor for use by subclasses.
	 * 
	 * @param repository
	 * @param transactionData
	 */
	protected Transaction(Repository repository, TransactionData transactionData) {
		this.repository = repository;
		this.transactionData = transactionData;
	}

	/**
	 * Returns subclass of Transaction constructed using passed transaction data.
	 * <p>
	 * Uses transaction-type in transaction data to call relevant subclass constructor.
	 * 
	 * @param repository
	 * @param transactionData
	 * @return a Transaction subclass, or null if a transaction couldn't be determined/built from passed data
	 */
	public static Transaction fromData(Repository repository, TransactionData transactionData) {
		TransactionType type = transactionData.getType();

		try {
			Constructor<?> constructor = type.constructor;

			if (constructor == null)
				throw new IllegalStateException("Unsupported transaction type [" + type.value + "] during fetch from repository");

			return (Transaction) constructor.newInstance(repository, transactionData);
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | InstantiationException e) {
			throw new IllegalStateException("Internal error with transaction type [" + type.value + "] during fetch from repository");
		}
	}

	// Getters / Setters

	public TransactionData getTransactionData() {
		return this.transactionData;
	}

	public void setRepository(Repository repository) {
		this.repository = repository;
	}

	// More information

	public static long getDeadline(TransactionData transactionData) {
		// Calculate deadline to include transaction in a block
		return transactionData.getTimestamp() + BlockChain.getInstance().getTransactionExpiryPeriod();
	}

	public long getDeadline() {
		return Transaction.getDeadline(transactionData);
	}

	/** Returns whether transaction's fee is at least minimum unit fee as specified in blockchain config. */
	public boolean hasMinimumFee() {
		return this.transactionData.getFee() >= this.getUnitFee(this.transactionData.getTimestamp());
	}

	public long feePerByte() {
		try {
			return this.transactionData.getFee() / TransactionTransformer.getDataLength(this.transactionData);
		} catch (TransformationException e) {
			throw new IllegalStateException("Unable to get transaction byte length?");
		}
	}

	/** Returns whether transaction's fee is at least amount needed to cover byte-length of transaction. */
	public boolean hasMinimumFeePerByte() {
		long unitFee = this.getUnitFee(this.transactionData.getTimestamp());
		int maxBytePerUnitFee = BlockChain.getInstance().getMaxBytesPerUnitFee();

		// If the unit fee is zero, any fee is enough to cover the byte-length of the transaction
		if (unitFee == 0) {
			return true;
		}
		return this.feePerByte() >= maxBytePerUnitFee / unitFee;
	}

	public long calcRecommendedFee() {
		int dataLength;
		try {
			dataLength = TransactionTransformer.getDataLength(this.transactionData);
		} catch (TransformationException e) {
			throw new IllegalStateException("Unable to get transaction byte length?");
		}

		int maxBytePerUnitFee = BlockChain.getInstance().getMaxBytesPerUnitFee();

		int unitFeeCount = ((dataLength - 1) / maxBytePerUnitFee) + 1;

		return this.getUnitFee(this.transactionData.getTimestamp()) * unitFeeCount;
	}

	/**
	 * Calculate unit fee for a given transaction type
	 *
	 * FUTURE: add "accountLevel" parameter if needed - the level of the transaction creator
	 * @param timestamp - the transaction's timestamp (currently not used)
	 * @return
	 */
	public long getUnitFee(Long timestamp) {
		return BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp);
	}

	/**
	 * Return the transaction version number that should be used, based on passed timestamp.
	 * <p>
	 * We're starting with version 4 as a nod to being newer than successor Qora,
	 * whose latest transaction version was 3.
	 * 
	 * @param timestamp
	 * @return transaction version number
	 */
	public static int getVersionByTimestamp(long timestamp) {
		if (timestamp >= BlockChain.getInstance().getTransactionV6Timestamp()) {
			return 6;
		}
		else if (timestamp >= BlockChain.getInstance().getTransactionV5Timestamp()) {
			return 5;
		}
		return 4;
	}

	/**
	 * Get block height for this transaction in the blockchain.
	 * 
	 * @return height, or 0 if not in blockchain (i.e. unconfirmed)
	 * @throws DataException
	 */
	public int getHeight() throws DataException {
		return this.repository.getTransactionRepository().getHeightFromSignature(this.transactionData.getSignature());
	}

	/**
	 * Get number of confirmations for this transaction.
	 * 
	 * @return confirmation count, or 0 if not in blockchain (i.e. unconfirmed)
	 * @throws DataException
	 */
	public int getConfirmations() throws DataException {
		int ourHeight = getHeight();
		if (ourHeight == 0)
			return 0;

		int blockChainHeight = this.repository.getBlockRepository().getBlockchainHeight();
		if (blockChainHeight == 0)
			return 0;

		return blockChainHeight - ourHeight + 1;
	}

	/**
	 * Returns a list of recipient addresses for this transaction.
	 * 
	 * @return list of recipients addresses, or empty list if none
	 * @throws DataException
	 */
	public abstract List<String> getRecipientAddresses() throws DataException;

	/**
	 * Returns a list of involved addresses for this transaction.
	 * <p>
	 * "Involved" means sender or recipient.
	 * 
	 * @return list of involved addresses, or empty list if none
	 * @throws DataException
	 */
	public List<String> getInvolvedAddresses() throws DataException {
		// Typically this is all the recipients plus the transaction creator/sender
		List<String> participants = new ArrayList<>(getRecipientAddresses());
		participants.add(0, this.getCreator().getAddress());
		return participants;
	}

	// Navigation

	/**
	 * Return transaction's "creator" account.
	 * 
	 * @return creator
	 * @throws DataException
	 */
	protected PublicKeyAccount getCreator() {
		if (this.creator == null)
			this.creator = new PublicKeyAccount(this.repository, this.transactionData.getCreatorPublicKey());

		return this.creator;
	}

	/**
	 * Load parent's transaction data from repository via this transaction's reference.
	 * 
	 * @return Parent's TransactionData, or null if no parent found (which should not happen)
	 * @throws DataException
	 */
	protected TransactionData getParent() throws DataException {
		byte[] reference = this.transactionData.getReference();
		if (reference == null)
			return null;

		return this.repository.getTransactionRepository().fromSignature(reference);
	}

	/**
	 * Load child's transaction data from repository, if any.
	 * 
	 * @return Child's TransactionData, or null if no child found
	 * @throws DataException
	 */
	protected TransactionData getChild() throws DataException {
		byte[] signature = this.transactionData.getSignature();
		if (signature == null)
			return null;

		return this.repository.getTransactionRepository().fromReference(signature);
	}

	// Processing

	public void sign(PrivateKeyAccount signer) {
		try {
			this.transactionData.setSignature(signer.sign(TransactionTransformer.toBytesForSigning(transactionData)));
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for signing", e);
		}
	}

	public boolean isSignatureValid() {
		byte[] signature = this.transactionData.getSignature();
		if (signature == null)
			return false;

		try {
			return Crypto.verify(this.transactionData.getCreatorPublicKey(), signature, TransactionTransformer.toBytesForSigning(transactionData));
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}
	}

	/**
	 * Returns whether transaction can be added to unconfirmed transactions.
	 * 
	 * @return transaction validation result, e.g. OK
	 * @throws DataException
	 */
	public ValidationResult isValidUnconfirmed() throws DataException {
		final Long now = NTP.getTime();
		if (now == null)
			return ValidationResult.CLOCK_NOT_SYNCED;

		// Expired already?
		if (now >= this.getDeadline())
			return ValidationResult.TIMESTAMP_TOO_OLD;

		// Transactions with a timestamp too far into future are too new
		long maxTimestamp = now + Settings.getInstance().getMaxTransactionTimestampFuture();
		if (this.transactionData.getTimestamp() > maxTimestamp)
			return ValidationResult.TIMESTAMP_TOO_NEW;

		// Check fee is sufficient
		ValidationResult feeValidationResult = isFeeValid();
		if (feeValidationResult != ValidationResult.OK)
			return feeValidationResult;

		if (Settings.getInstance().isLite()) {
			// Everything from this point is difficult to validate for a lite node, since it has no blocks.
			// For now, we will assume it is valid, to allow it to move around the network easily.
			// If it turns out to be invalid, other full/top-only nodes will reject it on receipt.
			// Lite nodes would never mint a block, so there's not much risk of holding invalid transactions.
			// TODO: implement lite-only validation for each transaction type
			return ValidationResult.OK;
		}

		PublicKeyAccount creator = this.getCreator();
		if (creator == null)
			return ValidationResult.MISSING_CREATOR;

		// Reject if unconfirmed pile already has X transactions from same creator
		if (countUnconfirmedByCreator(creator) >= Settings.getInstance().getMaxUnconfirmedPerAccount())
			return ValidationResult.TOO_MANY_UNCONFIRMED;

		// Transactions with a expiry prior to latest block's timestamp are too old
		// Not relevant for lite nodes, as they don't have any blocks
		BlockData latestBlock = repository.getBlockRepository().getLastBlock();
		if (this.getDeadline() <= latestBlock.getTimestamp())
			return ValidationResult.TIMESTAMP_TOO_OLD;

		// Check transaction's txGroupId
		if (!this.isValidTxGroupId())
			return ValidationResult.INVALID_TX_GROUP_ID;

		// Check transaction references
		if (!this.hasValidReference())
			return ValidationResult.INVALID_REFERENCE;

		// Check transaction is valid
		ValidationResult result = this.isValid();
		if (result != ValidationResult.OK)
			return result;

		// Check transaction is processable
		result = this.isProcessable();

		return result;
	}

	/** Returns whether transaction's fee is valid. Might be overridden in transaction subclasses. */
	protected ValidationResult isFeeValid() throws DataException {
		if (!hasMinimumFee() || !hasMinimumFeePerByte())
			return ValidationResult.INSUFFICIENT_FEE;

		return ValidationResult.OK;
	}

	protected boolean isValidTxGroupId() throws DataException {
		int txGroupId = this.transactionData.getTxGroupId();

		// If transaction type doesn't need approval then we insist on NO_GROUP
		if (!this.transactionData.getType().needsApproval)
			return txGroupId == Group.NO_GROUP;

		// Handling NO_GROUP
		if (txGroupId == Group.NO_GROUP)
			// true if NO_GROUP txGroupId is allowed for approval-needing tx types
			return !BlockChain.getInstance().getRequireGroupForApproval();

		// Group even exist?
		if (!this.repository.getGroupRepository().groupExists(txGroupId))
			return false;

		GroupRepository groupRepository = this.repository.getGroupRepository();

		// Is transaction's creator is group member?
		PublicKeyAccount creator = this.getCreator();
		if (groupRepository.memberExists(txGroupId, creator.getAddress()))
			return true;

		return false;
	}

	private int countUnconfirmedByCreator(PublicKeyAccount creator) throws DataException {
		List<TransactionData> unconfirmedTransactions = TransactionImporter.getInstance().unconfirmedTransactionsCache;
		if (unconfirmedTransactions == null) {
			unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();
		}

		// We exclude CHAT transactions as they never get included into blocks and
		// have spam/DoS prevention by requiring proof of work
		Predicate<TransactionData> hasSameCreatorButNotChat = transactionData -> {
			if (transactionData.getType() == TransactionType.CHAT)
				return false;

			return Arrays.equals(creator.getPublicKey(), transactionData.getCreatorPublicKey());
		};

		return (int) unconfirmedTransactions.stream().filter(hasSameCreatorButNotChat).count();
	}

	/**
	 * Returns sorted, unconfirmed transactions, excluding invalid and unconfirmable.
	 * 
	 * @return sorted, unconfirmed transactions
	 * @throws DataException
	 */
	public static List<TransactionData> getUnconfirmedTransactions(Repository repository) throws DataException {
		BlockData latestBlockData = repository.getBlockRepository().getLastBlock();

		EnumSet<TransactionType> excludedTxTypes = EnumSet.of(TransactionType.CHAT, TransactionType.PRESENCE);
		List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions(excludedTxTypes, null);

		unconfirmedTransactions.sort(getDataComparator());

		Iterator<TransactionData> unconfirmedTransactionsIterator = unconfirmedTransactions.iterator();
		while (unconfirmedTransactionsIterator.hasNext()) {
			TransactionData transactionData = unconfirmedTransactionsIterator.next();
			Transaction transaction = Transaction.fromData(repository, transactionData);

			// Must be confirmable and valid
			if (!transaction.isConfirmable() || transaction.isStillValidUnconfirmed(latestBlockData.getTimestamp()) != ValidationResult.OK)
				unconfirmedTransactionsIterator.remove();
		}

		return unconfirmedTransactions;
	}

	/**
	 * Returns invalid, unconfirmed transactions.
	 * 
	 * @return sorted, invalid, unconfirmed transactions
	 * @throws DataException
	 */
	public static List<TransactionData> getInvalidTransactions(Repository repository) throws DataException {
		BlockData latestBlockData = repository.getBlockRepository().getLastBlock();

		List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();
		List<TransactionData> invalidTransactions = new ArrayList<>();

		unconfirmedTransactions.sort(getDataComparator());

		Iterator<TransactionData> unconfirmedTransactionsIterator = unconfirmedTransactions.iterator();
		while (unconfirmedTransactionsIterator.hasNext()) {
			TransactionData transactionData = unconfirmedTransactionsIterator.next();
			Transaction transaction = Transaction.fromData(repository, transactionData);

			if (transaction.isStillValidUnconfirmed(latestBlockData.getTimestamp()) != ValidationResult.OK)
				invalidTransactions.add(transactionData);
		}

		return invalidTransactions;
	}

	/**
	 * Returns whether transaction is still a valid unconfirmed transaction.
	 * <p>
	 * This is like {@link #isValidUnconfirmed()} but only needs to perform
	 * a subset of those checks.
	 * 
	 * @return transaction validation result, e.g. OK
	 * @throws DataException
	 */
	private ValidationResult isStillValidUnconfirmed(long blockTimestamp) throws DataException {
		final Long now = NTP.getTime();
		if (now == null)
			return ValidationResult.CLOCK_NOT_SYNCED;

		// Expired already?
		if (now >= this.getDeadline())
			return ValidationResult.TIMESTAMP_TOO_OLD;

		// Transactions with a expiry prior to latest block's timestamp are too old
		if (this.getDeadline() <= blockTimestamp)
			return ValidationResult.TIMESTAMP_TOO_OLD;

		// Transactions with a timestamp too far into future are too new
		// Skipped because this test only applies at instant of submission

		// Check fee is sufficient
		// Skipped because this is checked upon submission and the result would be the same now

		// Reject if unconfirmed pile already has X transactions from same creator
		// Skipped because this test only applies at instant of submission

		// Check transaction's txGroupId
		// Skipped because this is checked upon submission and the result would be the same now

		// Check transaction references
		if (!this.hasValidReference())
			return ValidationResult.INVALID_REFERENCE;

		// Check transaction is valid
		ValidationResult result = this.isValid();
		if (result != ValidationResult.OK)
			return result;

		// Check transaction is processable
		result = this.isProcessable();

		return result;
	}

	/**
	 * Returns whether transaction needs to go through group-admin approval.
	 * <p>
	 * This test is more than simply "does this transaction type need approval?"
	 * because group admins bypass approval for transactions attached to their group.
	 * 
	 * @throws DataException
	 */
	public boolean needsGroupApproval() throws DataException {
		// Does this transaction type bypass approval?
		if (!this.transactionData.getType().needsApproval)
			return false;

		int txGroupId = this.transactionData.getTxGroupId();

		if (txGroupId == Group.NO_GROUP)
			return false;

		GroupRepository groupRepository = this.repository.getGroupRepository();

		if (!groupRepository.groupExists(txGroupId))
			// Group no longer exists? Possibly due to blockchain orphaning undoing group creation?
			return true; // stops tx being included in block but it will eventually expire

		String groupOwner = this.repository.getGroupRepository().getOwner(txGroupId);
		boolean groupOwnedByNullAccount = Objects.equals(groupOwner, Group.NULL_OWNER_ADDRESS);

		// If transaction's creator is group admin (of group with ID txGroupId) then auto-approve
		// This is disabled for null-owned groups, since these require approval from other admins
		PublicKeyAccount creator = this.getCreator();
		if (!groupOwnedByNullAccount && groupRepository.adminExists(txGroupId, creator.getAddress()))
			return false;

		return true;
	}

	public void setInitialApprovalStatus() throws DataException {
		if (this.needsGroupApproval()) {
			transactionData.setApprovalStatus(ApprovalStatus.PENDING);
		} else {
			transactionData.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
		}
	}

	public Boolean getApprovalDecision() throws DataException {
		// Grab latest decisions from repository
		GroupApprovalData groupApprovalData = this.repository.getTransactionRepository().getApprovalData(this.transactionData.getSignature());
		if (groupApprovalData == null)
			return null;

		// We need group info
		int txGroupId = this.transactionData.getTxGroupId();
		GroupData groupData = repository.getGroupRepository().fromGroupId(txGroupId);
		ApprovalThreshold approvalThreshold = groupData.getApprovalThreshold();

		// Fetch total number of admins in group
		int totalAdmins = repository.getGroupRepository().countGroupAdmins(txGroupId);

		// Are there enough approvals?
		if (approvalThreshold.meetsTheshold(groupApprovalData.approvingAdmins.size(), totalAdmins))
			return true;

		// Are there enough rejections?
		if (approvalThreshold.meetsTheshold(groupApprovalData.rejectingAdmins.size(), totalAdmins))
			return false;

		// No definitive decision yet
		return null;
	}

	/**
	 * Import into our repository as a new, unconfirmed transaction.
	 * <p>
	 * @implSpec <i>blocks</i> to obtain blockchain lock
	 * <p>
	 * If transaction is valid, then:
	 * <ul>
	 * <li>calls {@link Repository#discardChanges()}</li>
	 * <li>calls {@link Controller#onNewTransaction(TransactionData, Peer)}</li>
	 * </ul>
	 * 
	 * @throws DataException
	 */
	public ValidationResult importAsUnconfirmed() throws DataException {
		// Attempt to acquire blockchain lock
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		blockchainLock.lock();

		try {
			// Check transaction doesn't already exist
			if (repository.getTransactionRepository().exists(transactionData.getSignature()))
				return ValidationResult.TRANSACTION_ALREADY_EXISTS;

			// Fix up approval status
			this.setInitialApprovalStatus();

			this.preProcess();

			ValidationResult validationResult = this.isValidUnconfirmed();
			if (validationResult != ValidationResult.OK)
				return validationResult;

			/*
			 * We call discardChanges() to restart repository 'transaction', discarding any
			 * transactional table locks, hence reducing possibility of deadlock or
			 * "serialization failure" with HSQLDB due to reads.
			 * 
			 * We should be OK to proceed after validation check as we're protected by
			 * BLOCKCHAIN_LOCK so no other thread will be writing the same transaction.
			 */
			repository.discardChanges();

			repository.getTransactionRepository().save(transactionData);
			repository.getTransactionRepository().unconfirmTransaction(transactionData);

			this.onImportAsUnconfirmed();

			repository.saveChanges();

			// Notify controller of new transaction
			Controller.getInstance().onNewTransaction(transactionData);

			return ValidationResult.OK;
		} finally {
			/*
			 * We call discardChanges() to restart repository 'transaction', discarding any
			 * transactional table locks, hence reducing possibility of deadlock or
			 * "serialization failure" with HSQLDB due to reads.
			 * 
			 * "Serialization failure" most likely caused by existing transaction check above,
			 * where multiple threads are importing transactions
			 * and one thread finds existing an transaction, returns (unlocking blockchain lock),
			 * then another thread immediately obtains lock, tries to delete above existing transaction
			 * (e.g. older PRESENCE transaction) but can't because first thread's repository
			 * session still has row-lock on existing transaction and hasn't yet closed
			 * repository session. Deadlock caused by race condition.
			 * 
			 * Hence we clear any repository-based locks before releasing blockchain lock.
			 */
			repository.discardChanges();

			blockchainLock.unlock();
		}
	}

	/**
	 * Callback for when a transaction is imported as unconfirmed.
	 * <p>
	 * Called after transaction is added to repository, but before commit.
	 * <p>
	 * Blockchain lock is being held during this time.
	 */
	protected void onImportAsUnconfirmed() throws DataException {
		/* To be optionally overridden */
	}

	/**
	 * Returns whether transaction is 'confirmable' - i.e. is of a type that
	 * can be included in a block. Some transactions are 'unconfirmable'
	 * and therefore must remain in the mempool until they expire.
	 * @return
	 */
	public boolean isConfirmable() {
		/* To be optionally overridden */
		return true;
	}

	/**
	 * Returns whether transaction is confirmable in a block at a given height.
	 * @return
	 */
	public boolean isConfirmableAtHeight(int height) {
		/* To be optionally overridden */
		return true;
	}

	/**
	 * Returns whether transaction can be added to the blockchain.
	 * <p>
	 * Checks if transaction can have {@link TransactionHandler#process()} called.
	 * <p>
	 * Transactions that have already been processed will return false.
	 * 
	 * @return true if transaction can be processed, false otherwise
	 * @throws DataException
	 */
	public abstract ValidationResult isValid() throws DataException;

	/**
	 * Returns whether transaction's reference is valid.
	 * 
	 * @throws DataException
	 */
	public boolean hasValidReference() throws DataException {
		// Disable reference checking after feature trigger timestamp
		if (this.transactionData.getTimestamp() >= BlockChain.getInstance().getDisableReferenceTimestamp()) {
			// Allow any value as long as it is the correct length
			return this.transactionData.getReference() != null &&
					this.transactionData.getReference().length == Transformer.SIGNATURE_LENGTH;
		}

		Account creator = getCreator();

		return Arrays.equals(transactionData.getReference(), creator.getLastReference());
	}

	/**
	 * Returns whether transaction can be processed.
	 * <p>
	 * With group-approval, even if a transaction had valid values
	 * when submitted, by the time it is approved these values
	 * might become invalid, e.g. because dependencies might
	 * have changed.
	 * <p>
	 * For example, with UPDATE_ASSET, the asset owner might have
	 * changed between submission and approval and so the transaction
	 * is invalid because the previous owner (as specified in the
	 * transaction) no longer has permission to update the asset.
	 * 
	 * @throws DataException
	 */
	public ValidationResult isProcessable() throws DataException {
		return ValidationResult.OK;
	}

	/**
	 * * Pre-process a transaction before validating or processing the block
	 * This allows for any database integrity checks prior to validation.
	 *
	 * @throws DataException
	 */
	public abstract void preProcess() throws DataException;

	/**
	 * Actually process a transaction, updating the blockchain.
	 * <p>
	 * Processes transaction, updating balances, references, assets, etc. as appropriate.
	 * 
	 * @throws DataException
	 */
	public abstract void process() throws DataException;

	/**
	 * Update last references, subtract transaction fees, etc.
	 * 
	 * @throws DataException
	 */
	public void processReferencesAndFees() throws DataException {
		Account creator = getCreator();

		// Update transaction creator's balance
		creator.modifyAssetBalance(Asset.QORT, - transactionData.getFee());

		// Update transaction creator's reference (and possibly public key)
		creator.setLastReference(transactionData.getSignature());
	}

	/**
	 * Undo transaction, updating the blockchain.
	 * <p>
	 * Undoes transaction, updating balances, references, assets, etc. as appropriate.
	 * 
	 * @throws DataException
	 */
	public abstract void orphan() throws DataException;

	/**
	 * Update last references, subtract transaction fees, etc.
	 * 
	 * @throws DataException
	 */
	public void orphanReferencesAndFees() throws DataException {
		Account creator = getCreator();

		// Update transaction creator's balance
		creator.modifyAssetBalance(Asset.QORT, transactionData.getFee());

		// Update transaction creator's reference (and possibly public key)
		creator.setLastReference(transactionData.getReference());
	}


	// Comparison

	/** Returns comparator that sorts ATTransactions first, then by timestamp, then by signature */
	public static Comparator<Transaction> getComparator() {
		class TransactionComparator implements Comparator<Transaction> {

			private Comparator<TransactionData> transactionDataComparator;

			public TransactionComparator(Comparator<TransactionData> transactionDataComparator) {
				this.transactionDataComparator = transactionDataComparator;
			}

			// Compare by type, timestamp, then signature
			@Override
			public int compare(Transaction t1, Transaction t2) {
				TransactionData td1 = t1.getTransactionData();
				TransactionData td2 = t2.getTransactionData();

				return transactionDataComparator.compare(td1, td2);
			}

		}

		return new TransactionComparator(getDataComparator());
	}

	public static Comparator<TransactionData> getDataComparator() {
		class TransactionDataComparator implements Comparator<TransactionData> {

			// Compare by type, timestamp, then signature
			@Override
			public int compare(TransactionData td1, TransactionData td2) {
				// AT transactions come before non-AT transactions
				if (td1.getType() == TransactionType.AT && td2.getType() != TransactionType.AT)
					return -1;

				// Non-AT transactions come after AT transactions
				if (td1.getType() != TransactionType.AT && td2.getType() == TransactionType.AT)
					return 1;

				// If both transactions are AT type, then preserve existing ordering.
				if (td1.getType() == TransactionType.AT)
					return 0;

				// Both transactions are non-AT so compare timestamps
				int result = Long.compare(td1.getTimestamp(), td2.getTimestamp());

				if (result == 0)
					// Same timestamp so compare signatures
					result = new BigInteger(td1.getSignature()).compareTo(new BigInteger(td2.getSignature()));

				return result;
			}

		}

		return new TransactionDataComparator();
	}

	@Override
	public int hashCode() {
		return this.transactionData.hashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof TransactionData))
			return false;

		return this.transactionData.equals(other);
	}

}
