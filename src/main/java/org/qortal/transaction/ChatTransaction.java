package org.qortal.transaction;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.GroupRepository;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ChatTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.ListUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.utils.NTP;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ChatTransaction extends Transaction {

	private static final Logger LOGGER = LogManager.getLogger(ChatTransaction.class);

	/**
	 * Per-creator cache of recent unconfirmed CHAT transaction timestamps.
	 * Key: creator public key (wrapped in ByteBuffer for use as map key).
	 * Value: deque of tx timestamps (ms), kept in insertion order.
	 * Entries older than recentChatMessagesMaxAge are pruned on each access.
	 * Populated lazily from the DB on first use, then maintained incrementally.
	 */
	private static final ConcurrentHashMap<ByteBuffer, ArrayDeque<Long>> recentChatTimestamps = new ConcurrentHashMap<>();

	/** Whether the cache has been warmed up from the DB at least once. */
	private static volatile boolean cacheWarmed = false;

	// Properties
	private ChatTransactionData chatTransactionData;

	// Other useful constants
	public static final int MAX_DATA_SIZE = 4000;
	public static final int POW_BUFFER_SIZE = 8 * 1024 * 1024; // bytes
	public static final int POW_DIFFICULTY_ABOVE_QORT_THRESHOLD = 8; // leading zero bits
	public static final int POW_DIFFICULTY_BELOW_QORT_THRESHOLD = 18; // leading zero bits
	public static final long POW_QORT_THRESHOLD = 400000000L;

	// Constructors

	public ChatTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.chatTransactionData = (ChatTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		String recipientAddress = this.chatTransactionData.getRecipient();
		if (recipientAddress == null)
			return Collections.emptyList();

		return Collections.singletonList(recipientAddress);
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	public Account getRecipient() {
		String recipientAddress = chatTransactionData.getRecipient();
		if (recipientAddress == null)
			return null;

		return new Account(this.repository, recipientAddress);
	}

	// Processing

	public void computeNonce() throws DataException {
		byte[] transactionBytes;

		try {
			transactionBytes = TransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		// Clear nonce from transactionBytes
		ChatTransactionTransformer.clearNonce(transactionBytes);

		int difficulty = this.getSender().getConfirmedBalance(Asset.QORT) >= POW_QORT_THRESHOLD ? POW_DIFFICULTY_ABOVE_QORT_THRESHOLD : POW_DIFFICULTY_BELOW_QORT_THRESHOLD;

		// Calculate nonce
		this.chatTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, POW_BUFFER_SIZE, difficulty));
	}

	/**
	 * Returns whether CHAT transaction has valid txGroupId.
	 * <p>
	 * For CHAT transactions, a non-NO_GROUP txGroupId represents
	 * sending to a group, rather than to everyone.
	 * <p>
	 * If txGroupId is not NO_GROUP, then the sender needs to be
	 * a member of that group. The recipient, if supplied, also
	 * needs to be a member of that group.
	 */
	@Override
	protected boolean isValidTxGroupId() throws DataException {
		int txGroupId = this.transactionData.getTxGroupId();

		// txGroupId represents recipient group, unless NO_GROUP

		// Anyone can use NO_GROUP
		if (txGroupId == Group.NO_GROUP)
			return true;

		// Group even exist?
		if (!this.repository.getGroupRepository().groupExists(txGroupId))
			return false;

		GroupRepository groupRepository = this.repository.getGroupRepository();

		// Is transaction's creator is group member?
		PublicKeyAccount creator = this.getCreator();
		if (!groupRepository.memberExists(txGroupId, creator.getAddress()))
			return false;

		// If recipient address present, check they belong to group too.
		String recipient = this.chatTransactionData.getRecipient();
		if (recipient != null && !groupRepository.memberExists(txGroupId, recipient))
			return false;

		return true;
	}

	@Override
	public ValidationResult isFeeValid() throws DataException {
		if (this.transactionData.getFee() < 0)
			return ValidationResult.NEGATIVE_FEE;

		return ValidationResult.OK;
	}

	@Override
	public boolean hasValidReference() throws DataException {
		return true;
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public boolean isConfirmable() {
		// CHAT transactions can't go into blocks
		return false;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Nonce checking is done via isSignatureValid() as that method is only called once per import

		// Disregard messages with timestamp too far in the future (we have stricter limits for CHAT transactions)
		if (this.chatTransactionData.getTimestamp() > NTP.getTime() + (5 * 60 * 1000L)) {
			return ValidationResult.TIMESTAMP_TOO_NEW;
		}

		// Check for blocked author by address
		if (ListUtils.isAddressBlocked(this.chatTransactionData.getSender())) {
			return ValidationResult.ADDRESS_BLOCKED;
		}

		// Check for blocked author by registered name
		List<NameData> names = this.repository.getNameRepository().getNamesByOwner(this.chatTransactionData.getSender());
		if (names != null && !names.isEmpty()) {
			for (NameData nameData : names) {
				if (nameData != null && nameData.getName() != null) {
					if (ListUtils.isNameBlocked(nameData.getName())) {
						return ValidationResult.NAME_BLOCKED;
					}
				}
			}
		}

		PublicKeyAccount creator = this.getCreator();
		if (creator == null)
			return ValidationResult.MISSING_CREATOR;

		// Reject if unconfirmed pile already has X recent CHAT transactions from same creator
		int recentCount = countRecentChatTransactionsByCreator(creator);
		if (recentCount >= Settings.getInstance().getMaxRecentChatMessagesPerAccount()) {
			LOGGER.info("Chat rate limit exceeded for {}: {} recent txs (limit {})",
					creator.getAddress(), recentCount, Settings.getInstance().getMaxRecentChatMessagesPerAccount());
			return ValidationResult.TOO_MANY_UNCONFIRMED;
		}

		// If we exist in the repository then we've been imported as unconfirmed,
		// but we don't want to make it into a block, so return fake non-OK result.
		if (this.repository.getTransactionRepository().exists(this.chatTransactionData.getSignature()))
			return ValidationResult.INVALID_BUT_OK;

		// If we have a recipient, check it is a valid address
		String recipientAddress = chatTransactionData.getRecipient();
		if (recipientAddress != null && !Crypto.isValidAddress(recipientAddress))
			return ValidationResult.INVALID_ADDRESS;

		// Check data length
		if (chatTransactionData.getData().length < 1 || chatTransactionData.getData().length > MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		return ValidationResult.OK;
	}

	@Override
	public boolean isSignatureValid() {
		byte[] signature = this.transactionData.getSignature();
		if (signature == null)
			return false;

		byte[] transactionBytes;

		try {
			transactionBytes = ChatTransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		if (!Crypto.verify(this.transactionData.getCreatorPublicKey(), signature, transactionBytes))
			return false;

		int nonce = this.chatTransactionData.getNonce();

		// Clear nonce from transactionBytes
		ChatTransactionTransformer.clearNonce(transactionBytes);

		int difficulty;
		try {
			difficulty = this.getSender().getConfirmedBalance(Asset.QORT) >= POW_QORT_THRESHOLD ? POW_DIFFICULTY_ABOVE_QORT_THRESHOLD : POW_DIFFICULTY_BELOW_QORT_THRESHOLD;
		} catch (DataException e) {
			return false;
		}

		// Check nonce
		return MemoryPoW.verify2(transactionBytes, POW_BUFFER_SIZE, difficulty, nonce);
	}

	private int countRecentChatTransactionsByCreator(PublicKeyAccount creator) throws DataException {
		long now = NTP.getTime() != null ? NTP.getTime() : System.currentTimeMillis();
		long maxAge = Settings.getInstance().getRecentChatMessagesMaxAge();
		long cutoff = now - maxAge;

		if (!cacheWarmed) {
			warmCache(cutoff);
		}

		ByteBuffer key = ByteBuffer.wrap(creator.getPublicKey());
		ArrayDeque<Long> timestamps = recentChatTimestamps.get(key);
		if (timestamps == null)
			return 0;

		synchronized (timestamps) {
			pruneOldEntries(timestamps, cutoff);
			int creatorCount = timestamps.size();
			int totalCount = recentChatTimestamps.values().stream().mapToInt(ArrayDeque::size).sum();
			int accountCount = recentChatTimestamps.size();
			LOGGER.info("Chat rate-limit cache: creator has {} recent txs | cache total: {} txs across {} accounts",
					creatorCount, totalCount, accountCount);
			return creatorCount;
		}
	}

	/**
	 * Warm the in-memory cache from the DB. Called once on first use.
	 * Populates recentChatTimestamps with all unconfirmed CHAT tx timestamps
	 * that are within the recent window.
	 */
	private void warmCache(long cutoff) throws DataException {
		synchronized (ChatTransaction.class) {
			if (cacheWarmed)
				return;

			long fetchStart = System.currentTimeMillis();
			List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();
			long fetchElapsed = System.currentTimeMillis() - fetchStart;
			LOGGER.info("Warming recent chat timestamps cache: getUnconfirmedTransactions() returned {} rows in {}ms",
					unconfirmedTransactions.size(), fetchElapsed);

			for (TransactionData txData : unconfirmedTransactions) {
				if (txData.getType() != TransactionType.CHAT)
					continue;
				if (txData.getTimestamp() < cutoff)
					continue;

				ByteBuffer key = ByteBuffer.wrap(txData.getCreatorPublicKey());
				recentChatTimestamps
						.computeIfAbsent(key, k -> new ArrayDeque<>())
						.add(txData.getTimestamp());
			}

			cacheWarmed = true;
			LOGGER.info("Recent chat timestamps cache warmed with {} creator entries", recentChatTimestamps.size());
		}
	}

	/** Record a newly imported CHAT tx timestamp in the in-memory cache. */
	public static void recordImportedChatTimestamp(byte[] creatorPublicKey, long timestamp) {
		ByteBuffer key = ByteBuffer.wrap(creatorPublicKey);
		ArrayDeque<Long> timestamps = recentChatTimestamps.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (timestamps) {
			timestamps.addLast(timestamp);
		}
	}

	/** Remove a CHAT tx timestamp from the in-memory cache (called on expiry). */
	public static void removeExpiredChatTimestamp(byte[] creatorPublicKey, long timestamp) {
		ByteBuffer key = ByteBuffer.wrap(creatorPublicKey);
		ArrayDeque<Long> timestamps = recentChatTimestamps.get(key);
		if (timestamps == null)
			return;

		synchronized (timestamps) {
			timestamps.remove(timestamp);
		}
	}

	/** Prune entries older than the cutoff from the front of the deque (timestamps are in insertion/chronological order). */
	private static void pruneOldEntries(Deque<Long> timestamps, long cutoff) {
		while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
			timestamps.pollFirst();
		}
	}


	/**
	 * Ensure there's at least a skeleton account so people
	 * can retrieve sender's public key using address, even if all their messages
	 * expire.
	 */
	@Override
	protected void onImportAsUnconfirmed() throws DataException {
		this.getCreator().ensureAccount();
		// Record this tx's timestamp in the in-memory rate-limit cache
		recordImportedChatTimestamp(this.chatTransactionData.getCreatorPublicKey(), this.chatTransactionData.getTimestamp());
	}

	@Override
	public void process() throws DataException {
		throw new DataException("CHAT transactions should never be processed");
	}

	@Override
	public void orphan() throws DataException {
		throw new DataException("CHAT transactions should never be orphaned");
	}

}
