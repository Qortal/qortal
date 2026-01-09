package org.qortal.crosschain;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.script.Script.ScriptType;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.KeyChain;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.qortal.api.model.SimpleForeignTransaction;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.utils.Amounts;
import org.qortal.utils.BitTwiddling;
import org.qortal.utils.NTP;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Bitcoin-like (Bitcoin, Litecoin, etc.) support */
public abstract class Bitcoiny implements ForeignBlockchain {

	protected static final Logger LOGGER = LogManager.getLogger(Bitcoiny.class);

	public static final int HASH160_LENGTH = 20;

	protected final BitcoinyBlockchainProvider blockchainProvider;
	protected final Context bitcoinjContext;
	protected final String currencyCode;

	protected final NetworkParameters params;

	/** Cache recent transactions to speed up subsequent lookups */
	protected List<SimpleTransaction> transactionsCache;
	protected Long transactionsCacheTimestamp;
	protected String transactionsCacheXpub;
	protected static long TRANSACTIONS_CACHE_TIMEOUT = 2 * 60 * 1000L; // 2 minutes

	/** Keys that have been previously marked as fully spent,<br>
	 * i.e. keys with transactions but with no unspent outputs. */
	protected final Set<ECKey> spentKeys = Collections.synchronizedSet(new HashSet<>());

	/** How many wallet keys to generate in each batch. */
	private static final int WALLET_KEY_LOOKAHEAD_INCREMENT = 3;

	/** Byte offset into raw block headers to block timestamp. */
	private static final int TIMESTAMP_OFFSET = 4 + 32 + 32;

	protected Coin feePerKb;

	/**
	 * Blockchain Cache
	 *
	 * To store blockchain data and reduce redundant RPCs to the ElectrumX servers
	 */
	private final BlockchainCache blockchainCache = new BlockchainCache();

	/**
	 * Executor
	 *
	 * Executor service to manage all Electrum server access.
	 */
	private static ExecutorService EXECUTOR = Executors.newFixedThreadPool(Settings.getInstance().getElectrumThreadCount());

	// Constructors and instance

	protected Bitcoiny(BitcoinyBlockchainProvider blockchainProvider, Context bitcoinjContext, String currencyCode, Coin feePerKb) {
		this.blockchainProvider = blockchainProvider;
		this.bitcoinjContext = bitcoinjContext;
		this.currencyCode = currencyCode;
		this.feePerKb = feePerKb;

		this.params = this.bitcoinjContext.getParams();
	}

	// Getters & setters

	public BitcoinyBlockchainProvider getBlockchainProvider() {
		return this.blockchainProvider;
	}

	public Context getBitcoinjContext() {
		return this.bitcoinjContext;
	}

	@Override
	public String getCurrencyCode() {
		return this.currencyCode;
	}

	public NetworkParameters getNetworkParameters() {
		return this.params;
	}

	// Interface obligations

	@Override
	public boolean isValidAddress(String address) {
		try {
			ScriptType addressType = Address.fromString(this.params, address).getOutputScriptType();

			return addressType == ScriptType.P2PKH || addressType == ScriptType.P2SH || addressType == ScriptType.P2WPKH;
		} catch (AddressFormatException e) {
			LOGGER.error(String.format("Unrecognised address format: %s", address));
			return false;
		}
	}

	@Override
	public boolean isValidWalletKey(String walletKey) {
		return this.isValidDeterministicKey(walletKey);
	}

	// Actual useful methods for use by other classes

	public String format(Coin amount) {
		return this.format(amount.value);
	}

	public String format(long amount) {
		return Amounts.prettyAmount(amount) + " " + this.currencyCode;
	}

	public boolean isValidDeterministicKey(String key58) {
		try {
			Context.propagate(this.bitcoinjContext);
			DeterministicKey.deserializeB58(null, key58, this.params);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public boolean isValidDeterministicPrivateKey(String key58) {
		try {
			Context.propagate(this.bitcoinjContext);
			DeterministicKey key = DeterministicKey.deserializeB58(null, key58, this.params);
			return key.hasPrivKey();
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** Returns P2PKH address using passed public key hash. */
	public String pkhToAddress(byte[] publicKeyHash) {
		Context.propagate(this.bitcoinjContext);
		return LegacyAddress.fromPubKeyHash(this.params, publicKeyHash).toString();
	}

	/** Returns P2SH address using passed redeem script. */
	public String deriveP2shAddress(byte[] redeemScriptBytes) {
		Context.propagate(bitcoinjContext);
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
		return LegacyAddress.fromScriptHash(this.params, redeemScriptHash).toString();
	}

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public int getMedianBlockTime() throws ForeignBlockchainException {
		int height = this.blockchainProvider.getCurrentHeight();

		// Grab latest 11 blocks
		List<byte[]> blockHeaders = this.blockchainProvider.getRawBlockHeaders(height - 11, 11);
		if (blockHeaders.size() < 11)
			throw new ForeignBlockchainException("Not enough blocks to determine median block time");

		List<Integer> blockTimestamps = blockHeaders.stream().map(blockHeader -> BitTwiddling.intFromLEBytes(blockHeader, TIMESTAMP_OFFSET)).collect(Collectors.toList());

		// Descending order
		blockTimestamps.sort((a, b) -> Integer.compare(b, a));

		// Pick median
		return blockTimestamps.get(5);
	}

	/**
	 * Returns height from latest block.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public int getBlockchainHeight() throws ForeignBlockchainException {
		int height = this.blockchainProvider.getCurrentHeight();
		return height;
	}

	/** Returns fee per transaction KB. To be overridden for testnet/regtest. */
	public Coin getFeePerKb() {
		return this.feePerKb;
	}

	public void setFeePerKb(Coin feePerKb) {
		this.feePerKb = feePerKb;
	}

	/** Returns minimum order size in sats. To be overridden for coins that need to restrict order size. */
	public long getMinimumOrderAmount() {
		return 0L;
	}

	/**
	 * Returns fixed P2SH spending fee, in sats per 1000bytes, optionally for historic timestamp.
	 *
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes
	 * @throws ForeignBlockchainException if something went wrong
	 */
	public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if script unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws ForeignBlockchainException {
		return this.blockchainProvider.getConfirmedBalance(addressToScriptPubKey(base58Address));
	}

	/**
	 * Returns list of unspent outputs pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if address unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	// TODO: don't return bitcoinj-based objects like TransactionOutput, use BitcoinyTransaction.Output instead
	public List<TransactionOutput> getUnspentOutputs(String base58Address, boolean includeUnconfirmed) throws ForeignBlockchainException {

		List<UnspentOutput> unspentOutputs = this.blockchainProvider.getUnspentOutputs(addressToScriptPubKey(base58Address), includeUnconfirmed);

		List<Optional<TransactionOutput>> unspentTransactionOutputs = new ArrayList<>();
		for (UnspentOutput unspentOutput : unspentOutputs) {
			unspentTransactionOutputs.add( getTransactionOutput(unspentOutput));
		}

		return unspentTransactionOutputs.stream().filter(Optional::isPresent)
				.map(Optional::get)
				.collect(Collectors.toList());
	}

	/**
	 * GEt UTXOs Asynchronously
	 *
	 * @param address the foreign coin address
	 * @param includeUnconfirmed true to include unconfirmed outputs, otherwise false
	 * @param executor the executor to run the multi-threaded fetching
	 *
	 * @return the UTXOs
	 * @throws ForeignBlockchainException
	 */
	private List<Future<UTXO>> getUTXOsAsync(String address, boolean includeUnconfirmed, ExecutorService executor) throws ForeignBlockchainException {
		List<UnspentOutput> unspentOutputs = this.blockchainProvider.getUnspentOutputs(address, true);

		List<Future<UTXO>> utxos = new ArrayList<>();

		final boolean coinbase = false;

		for (UnspentOutput unspentOutput : unspentOutputs) {
			utxos.add(executor.submit( () -> buildUTXO(coinbase, unspentOutput) ));
		}

		return utxos;
	}

	/**
	 * Build UTXO
	 *
	 * Build UTXo from a an unspent output
	 *
	 * @param coinbase true if coinbase transaction, otherwise false
	 * @param unspentOutput the unpent output to build from
	 *
	 * @return the UTXO
	 *
	 * @throws ForeignBlockchainException
	 */
	private UTXO buildUTXO(boolean coinbase, UnspentOutput unspentOutput) throws ForeignBlockchainException {
		List<TransactionOutput> transactionOutputs = getOutputs(unspentOutput.hash);

		TransactionOutput transactionOutput = transactionOutputs.get(unspentOutput.index);

		UTXO utxo = new UTXO(Sha256Hash.wrap(unspentOutput.hash), unspentOutput.index,
				Coin.valueOf(unspentOutput.value), unspentOutput.height, coinbase,
				transactionOutput.getScriptPubKey());

		return utxo;
	}

	/**
	 * Get Transaction Output
	 *
	 * Get transaction output from unspent output.
	 *
	 * @param unspentOutput the unspent output
	 *
	 * @return the transaction output
	 */
	private Optional<TransactionOutput> getTransactionOutput(UnspentOutput unspentOutput)  {
		try {
			List<TransactionOutput> transactionOutputs = this.getOutputs(unspentOutput.hash);

			TransactionOutput transactionOutput = transactionOutputs.get(unspentOutput.index);
			return Optional.of(transactionOutput);
		} catch (ForeignBlockchainException e) {
			LOGGER.error(e.getMessage(), e);
			return Optional.empty();
		}
	}

	/**
	 * Returns list of outputs pertaining to passed transaction hash.
	 * <p>
	 * @return list of outputs, or empty list if transaction unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	// TODO: don't return bitcoinj-based objects like TransactionOutput, use BitcoinyTransaction.Output instead
	public List<TransactionOutput> getOutputs(byte[] txHash) throws ForeignBlockchainException {
		byte[] rawTransactionBytes = this.blockchainProvider.getRawTransaction(txHash);

		Context.propagate(bitcoinjContext);
		Transaction transaction = new Transaction(this.params, rawTransactionBytes);
		return transaction.getOutputs();
	}

	/**
	 * Returns transactions for passed script
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) throws ForeignBlockchainException {
		int retries = 0;
		ForeignBlockchainException e2 = null;
		while (retries <= 3) {
			try {
				return this.blockchainProvider.getAddressTransactions(scriptPubKey, includeUnconfirmed);
			} catch (ForeignBlockchainException e) {
				e2 = e;
				retries++;
			}
		}
		throw(e2);
	}

	/**
	 * Returns list of transaction hashes pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if script unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	public List<TransactionHash> getAddressTransactions(String base58Address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		return this.blockchainProvider.getAddressTransactions(addressToScriptPubKey(base58Address), includeUnconfirmed);
	}

	/**
	 * Returns list of raw, confirmed transactions involving given address.
	 * <p>
	 * @throws ForeignBlockchainException if there was an error
	 */
	public List<byte[]> getAddressTransactions(String base58Address) throws ForeignBlockchainException {
		List<TransactionHash> transactionHashes = this.blockchainProvider.getAddressTransactions(addressToScriptPubKey(base58Address), false);

		List<byte[]> rawTransactions = new ArrayList<>();
		for (TransactionHash transactionInfo : transactionHashes) {
			byte[] rawTransaction = this.blockchainProvider.getRawTransaction(HashCode.fromString(transactionInfo.txHash).asBytes());
			rawTransactions.add(rawTransaction);
		}

		return rawTransactions;
	}

	/**
	 * Returns transaction info for passed transaction hash.
	 * <p>
	 * @throws ForeignBlockchainException.NotFoundException if transaction unknown
	 * @throws ForeignBlockchainException if error occurs
	 */
	public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
		int retries = 0;
		ForeignBlockchainException e2 = null;
		while (retries <= 3) {
			try {
				return this.blockchainProvider.getTransaction(txHash);
			} catch (ForeignBlockchainException e) {
				e2 = e;
				retries++;
			}
		}
		throw(e2);
	}

	/**
	 * Broadcasts raw transaction to network.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public void broadcastTransaction(Transaction transaction) throws ForeignBlockchainException {
		this.blockchainProvider.broadcastTransaction(transaction.bitcoinSerialize());
	}

	/**
	 * Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt>.
	 *
	 * @param xprv58 BIP32 private key
	 * @param recipient P2PKH address
	 * @param amount unscaled amount
	 * @param feePerByte unscaled fee per byte, or null to use default fees
	 * @return transaction, or null if insufficient funds
	 */
	public Transaction buildSpend(String xprv58, String recipient, long amount, Long feePerByte) {
		Context.propagate(bitcoinjContext);

		Wallet wallet = Wallet.fromSpendingKeyB58(this.params, xprv58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet));

		Address destination = Address.fromString(this.params, recipient);
		SendRequest sendRequest = SendRequest.to(destination, Coin.valueOf(amount));

		if (feePerByte != null)
			sendRequest.feePerKb = Coin.valueOf(feePerByte * 1000L); // Note: 1000 not 1024
		else
			// Allow override of default for TestNet3, etc.
			sendRequest.feePerKb = this.getFeePerKb();

		try {
			wallet.completeTx(sendRequest);
			return sendRequest.tx;
		} catch (InsufficientMoneyException e) {
			return null;
		}
	}

	/**
	 * Returns bitcoinj transaction sending the recipient's amount to each recipient given.
	 *
	 *
	 * @param xprv58 the private master key
	 * @param amountByRecipient each amount to send indexed by the recipient to send to
	 * @param feePerByte the satoshis per byte
	 *
	 * @return the completed transaction, ready to broadcast
	 */
	public Transaction buildSpendMultiple(String xprv58, Map<String, Long> amountByRecipient, Long feePerByte) {
		Context.propagate(bitcoinjContext);

		Wallet wallet = Wallet.fromSpendingKeyB58(this.params, xprv58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet));

		Transaction transaction = new Transaction(this.params);

		for(Map.Entry<String, Long> amountForRecipient : amountByRecipient.entrySet()) {
			Address destination = Address.fromString(this.params, amountForRecipient.getKey());
			transaction.addOutput(Coin.valueOf(amountForRecipient.getValue()), destination);
		}

		SendRequest sendRequest = SendRequest.forTx(transaction);

		if (feePerByte != null)
			sendRequest.feePerKb = Coin.valueOf(feePerByte * 1000L); // Note: 1000 not 1024
		else
			// Allow override of default for TestNet3, etc.
			sendRequest.feePerKb = this.getFeePerKb();

		try {
			wallet.completeTx(sendRequest);
			return sendRequest.tx;
		} catch (InsufficientMoneyException e) {
			return null;
		}
	}

	/**
	 * Get Spending Candidate Addresses
	 *
	 * @param key58 public master key
	 * @return the addresses this instance will look at when building a spend
	 * @throws ForeignBlockchainException
	 */
	public List<String> getSpendingCandidateAddresses(String key58) throws ForeignBlockchainException {

		Wallet wallet = Wallet.fromWatchingKeyB58(this.params, key58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet));

		// from Wallet.getStoredOutputsFromUTXOProvider()
		List<ECKey> spendingKeys = wallet.getImportedKeys();
		spendingKeys.addAll(wallet.getActiveKeyChain().getLeafKeys());

		List<String> spendingCandidateAddresses
				= spendingKeys.stream()
					.map(spendingKey -> Address.fromKey(this.params, spendingKey, ScriptType.P2PKH ).toString())
					.collect(Collectors.toList());

		return spendingCandidateAddresses;
	}

	/**
	 * Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt> using default fees.
	 *
	 * @param xprv58 BIP32 private key
	 * @param recipient P2PKH address
	 * @param amount unscaled amount
	 * @return transaction, or null if insufficient funds
	 */
	public Transaction buildSpend(String xprv58, String recipient, long amount) {
		return buildSpend(xprv58, recipient, amount, null);
	}

	/**
	 * Returns unspent Bitcoin balance given 'm' BIP32 key.
	 *
	 * @param key58 BIP32/HD extended Bitcoin private/public key
	 * @return unspent BTC balance, or null if unable to determine balance
	 */
public Long getWalletBalance(String key58) throws ForeignBlockchainException {
	Long balance = 0L;

	// Get all wallet addresses (via recursive gap-limit logic)
	Set<String> walletAddresses = this.getWalletAddressesWithExecutor(key58, EXECUTOR);

	try {
		List<Future<List<TransactionOutput>>> futures = new ArrayList<>();

		// Parallel fetch of unspent outputs per address
		for (String address : walletAddresses) {
			futures.add(EXECUTOR.submit(() -> {
				try {
					return this.getUnspentOutputs(address, true);
				} catch (Exception e) {
					LOGGER.warn("⚠️ Failed to fetch outputs for address {}", address, e);
					return Collections.emptyList();
				}
			}));
		}

		List<TransactionOutput> unspentOutputs = new ArrayList<>();

		// Wait for all futures to complete
		for (Future<List<TransactionOutput>> future : futures) {
			
				unspentOutputs.addAll(future.get(10, TimeUnit.SECONDS)); // No timeout needed
		
		}

		// Sum up the available unspent outputs
		for (TransactionOutput unspentOutput : unspentOutputs) {
			if (unspentOutput.isAvailableForSpending()) {
				balance += unspentOutput.getValue().value;
			}
		}
	} catch (Exception e) {
		LOGGER.error("Unexpected error in getWalletBalance: {}", e.getMessage(), e);
	}

	return balance;
}

	public Long getWalletBalanceFromBitcoinj(String key58) {
		Context.propagate(bitcoinjContext);

		Wallet wallet = walletFromDeterministicKey58(key58);
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet));

		Coin balance = wallet.getBalance();
		if (balance == null)
			return null;

		return balance.value;
	}

	public Long getWalletBalanceFromTransactions(String key58) throws ForeignBlockchainException {
		long balance = 0;
		Comparator<SimpleTransaction> oldestTimestampFirstComparator = Comparator.comparingLong(SimpleTransaction::getTimestamp);
		List<SimpleTransaction> transactions = getWalletTransactions(key58).stream().sorted(oldestTimestampFirstComparator).collect(Collectors.toList());
		for (SimpleTransaction transaction : transactions) {
			balance += transaction.getTotalAmount();
		}
		return balance;
	}

public List<SimpleTransaction> getWalletTransactions(String key58) throws ForeignBlockchainException {
	try {
		// Serve from cache if valid
		if (Objects.equals(transactionsCacheXpub, key58)) {
			if (transactionsCache != null && transactionsCacheTimestamp != null) {
				Long now = NTP.getTime();
				boolean isCacheStale = (now != null && now - transactionsCacheTimestamp >= TRANSACTIONS_CACHE_TIMEOUT);
				if (!isCacheStale) {
					return transactionsCache;
				}
			}
		}

		Context.propagate(bitcoinjContext);

		Wallet wallet = walletFromDeterministicKey58(key58);
		DeterministicKeyChain keyChain = wallet.getActiveKeyChain();

		keyChain.setLookaheadSize(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);
		keyChain.maybeLookAhead();

		List<DeterministicKey> keys = new ArrayList<>(keyChain.getLeafKeys());

		// Use thread-safe list for futures
		List<Future<Optional<BitcoinyTransaction>>> futures = Collections.synchronizedList(new ArrayList<>());


		// Fetch keys with transaction checks
		Set<String> keySet = processKeysWithTransactionFuturesIterative(EXECUTOR, keys, keyChain, futures);

		// Collect transactions from futures
		Set<BitcoinyTransaction> walletTransactions = Collections.synchronizedSet(new HashSet<>());
		for (Future<Optional<BitcoinyTransaction>> future : futures) {
			Optional<BitcoinyTransaction> transactionOptional = future.get(10, TimeUnit.SECONDS);

			if (transactionOptional.isPresent()) {
				BitcoinyTransaction transaction = transactionOptional.get();
				walletTransactions.add(transaction);

				// Cache confirmed transactions
				if (transaction.timestamp != null) {
					this.blockchainCache.addTransactionByHash(transaction.txHash, transaction);
				}
			}
		}

		Comparator<SimpleTransaction> newestTimestampFirstComparator =
			Comparator.comparingLong(SimpleTransaction::getTimestamp).reversed();

		// Convert to simplified form
		List<SimpleTransaction> simpleTransactions = walletTransactions.parallelStream()
			.map(t -> convertToSimpleTransaction(t, keySet))
			.collect(Collectors.toList());

		// Unconfirmed transactions (null timestamp)
		transactionsCache = simpleTransactions.stream()
			.filter(t -> t.getTimestamp() == null)
			.collect(Collectors.toList());

		// Add confirmed transactions sorted by timestamp
		transactionsCache.addAll(
			simpleTransactions.stream()
				.filter(t -> t.getTimestamp() != null)
				.sorted(newestTimestampFirstComparator)
				.collect(Collectors.toList())
		);

		// Update cache metadata
		transactionsCacheTimestamp = NTP.getTime();
		transactionsCacheXpub = key58;

		return transactionsCache;
	} catch (ForeignBlockchainException e) {
		LOGGER.error(e.getMessage(), e);
		throw e;
	} catch (ExecutionException | InterruptedException e) {
		LOGGER.error(e.getMessage(), e);
		throw new ForeignBlockchainException("Execution or interruption exception when calling foreign chain");
	} catch (Exception e) {
		LOGGER.error(e.getMessage(), e);
		return new ArrayList<>(0);
	}
}

	private Set<String> processKeysWithTransactionFuturesIterative(
	ExecutorService executor,
	List<DeterministicKey> initialKeys,
	DeterministicKeyChain keyChain,
	List<Future<Optional<BitcoinyTransaction>>> futures
) throws ForeignBlockchainException {

	Set<String> keySet = new HashSet<>();
	int unusedCounter = 0;

	List<DeterministicKey> keysToProcess = new ArrayList<>(initialKeys);

	while (!keysToProcess.isEmpty()) {
		List<Future<Boolean>> transactionChecks = new ArrayList<>(keysToProcess.size());
		boolean foundTransaction = false;

		for (DeterministicKey dKey : keysToProcess) {
			Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
			keySet.add(address.toString());

			// Schedule transaction check
			transactionChecks.add(executor.submit(() -> getTransactions(address, futures, executor)));
		}

		// Wait for transaction check results
		for (Future<Boolean> check : transactionChecks) {
			try {
				if (check.get()) {
					foundTransaction = true;
				}
			} catch (Exception e) {
				LOGGER.warn("Failed to check transaction for key", e);
			}
		}

		if (foundTransaction) {
			unusedCounter = 0;
		} else {
			unusedCounter += WALLET_KEY_LOOKAHEAD_INCREMENT;
		}

		if (unusedCounter >= Settings.getInstance().getGapLimit()) {
			LOGGER.debug("Reached gap limit of " + unusedCounter + ", stopping key discovery.");
			break;
		}

		// Generate next batch of keys
		keysToProcess = generateMoreKeys(keyChain);
	}

	return keySet;
}

	/**
	 * Process Keys With Transaction Futures
	 *
	 * @param executor the executor to process asynchronously
	 * @param keys the keys to process
	 * @param keyChain the key chain to generate more keys from if necessary
	 * @param futures the transactions already fetched for
	 * @param unusedCounter starts at zero and increments for recursion
	 *
	 * @return the addresses generated from the keys
	 *
	 * @throws ForeignBlockchainException
	 */
	private Set<String> processKeysWithTransactionFutures(
			ExecutorService executor,
			List<DeterministicKey> keys,
			DeterministicKeyChain keyChain,
			List<Future<Optional<BitcoinyTransaction>>> futures,
			int unusedCounter) throws ForeignBlockchainException {

		Set<String> keySet = new HashSet<>();

		List<Future<Boolean>> transactionChecks = new ArrayList<>(keys.size());

		for( DeterministicKey dKey : keys) {

			// Check for transactions
			Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
			keySet.add(address.toString());

			transactionChecks.add( executor.submit(() -> getTransactions(address, futures, executor) ));
		}

		if(anyTrue(transactionChecks)) {
			keySet.addAll(processKeysWithTransactionFutures(executor, generateMoreKeys(keyChain), keyChain, futures, 0));
		}
		// if no additional keys were already processed and the if the gap limit held, then process additional keys
		else if ( unusedCounter < Settings.getInstance().getGapLimit()) {
			keySet.addAll(processKeysWithTransactionFutures(executor, generateMoreKeys(keyChain), keyChain, futures, unusedCounter + WALLET_KEY_LOOKAHEAD_INCREMENT));
		}

		return keySet;
	}

	/**
	 * Get Bitcoiny Transaction
	 *
	 * Get the transaction object stored in memory if available
	 *
	 * @param transactionHash the hash identifying the transaction
	 *
	 * @return the transaction is available, otherwise empty
	 */
	private Optional<BitcoinyTransaction> getBitcoinyTransaction(TransactionHash transactionHash) {
		try {
			BitcoinyTransaction transaction = getTransaction(transactionHash.txHash);
			return Optional.of(transaction);
		} catch (ForeignBlockchainException e) {
			LOGGER.error(e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Get Wallet Infos
	 *
	 * Get information for each address in the wallet.
	 *
	 * @param key58 the master key to determine key generation for the addresses
	 *
	 * @return the info for each address
	 *
	 * @throws ForeignBlockchainException
	 */
	public List<AddressInfo> getWalletAddressInfos(String key58) throws ForeignBlockchainException {

		// return list
		List<AddressInfo> infos = new ArrayList<>();

		// generate keys asynchronously
		Set<DeterministicKey> walletKeys = getWalletKeysWithExecutor(key58, EXECUTOR);

		// collect all address info build tasks
		List<Future<AddressInfo>> futures = new ArrayList<>(walletKeys.size());

		// build info for each key, one address per key
		for(DeterministicKey key : walletKeys) {
			futures.add(EXECUTOR.submit(() -> buildAddressInfo(key)));
		}

		try {
			// once the tasks are done, get the info objects
			for( Future<AddressInfo> future : futures ) {
				infos.add( future.get() );
			}
		} catch (InterruptedException | ExecutionException e) {
			LOGGER.error(e.getMessage(), e);
		}

		return infos.stream()
				.sorted(new PathComparator(1))
				.collect(Collectors.toList());
	}

	public AddressInfo buildAddressInfo(DeterministicKey key) throws ForeignBlockchainException  {

		Address address = Address.fromKey(this.params, key, ScriptType.P2PKH);

		int transactionCount = getAddressTransactions(ScriptBuilder.createOutputScript(address).getProgram(), true).size();

		return new AddressInfo(
				address.toString(),
				toIntegerList( key.getPath()),
				summingUnspentOutputs(address.toString()),
				key.getPathAsString(),
				transactionCount,
				true);
	}

	private static  List<Integer> toIntegerList(ImmutableList<ChildNumber> path) {

		return path.stream().map(ChildNumber::num).collect(Collectors.toList());
	}

	public Set<String> getWalletAddresses(String key58) throws ForeignBlockchainException {
		Context.propagate(bitcoinjContext);

		// generate keys asynchronously and get the addresses, return value
		Set<String> addresses = getWalletAddressesWithExecutor(key58, EXECUTOR);

		return addresses;
	}

	/**
	 * Get Wallet Addresses With Executor
	 *
	 * Get wallet addresses asynchronously.
	 *
	 * @param key58 the master key
	 * @param executor the executor for asynchronous processing
	 *
	 * @return the addresses
	 *
	 * @throws ForeignBlockchainException
	 */
	public Set<String> getWalletAddressesWithExecutor(String key58, ExecutorService executor) throws ForeignBlockchainException {
		Wallet wallet = walletFromDeterministicKey58(key58);

		return getAddressesWithExecutor( wallet, executor);
	}

	/**
	 * Get Addresses With Executor
	 *
	 * Get wallet addresses asynchronously
	 *
	 * @param wallet the wallet
	 * @param executor the executor for asynchronous processing
	 *
	 * @return the addresses
	 */
	private Set<String> getAddressesWithExecutor( Wallet wallet, ExecutorService executor) {
		DeterministicKeyChain keyChain = wallet.getActiveKeyChain();

		keyChain.setLookaheadSize(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);
		keyChain.maybeLookAhead();

		Set<String> keySet = processKeysIterative(executor, keyChain);


		return keySet;
	}

	/**
	 * Get Wallet Keys With Executor
	 *
	 * Get wallet keys asynchronously
	 *
	 * @param key58 the master key to determine kday generation
	 * @param executor the executor for asychronous processing
	 *
	 * @return the keys
	 *
	 * @throws ForeignBlockchainException
	 */
	public Set<DeterministicKey> getWalletKeysWithExecutor(String key58, ExecutorService executor) throws ForeignBlockchainException {
		Wallet wallet = walletFromDeterministicKey58(key58);
		DeterministicKeyChain keyChain = wallet.getActiveKeyChain();

		keyChain.setLookaheadSize(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);
		keyChain.maybeLookAhead();

		// the return value
		Set<DeterministicKey> keySet = processKeysOnly(executor, new ArrayList<>(keyChain.getLeafKeys()), keyChain, 0);

		return keySet;
	}


private Set<String> processKeysIterative(ExecutorService executor, DeterministicKeyChain keyChain) {
    Set<String> allAddresses = new HashSet<>();
    int unusedCounter = 0;
    int batchCount = 0;
    int gapLimit = Settings.getInstance().getGapLimit(); // e.g. 20
    boolean continueProcessing = true;

    while (continueProcessing) {
        // Generate a batch of new keys
        List<DeterministicKey> batch = keyChain.getLeafKeys().subList(
            allAddresses.size(), 
            Math.min(allAddresses.size() + WALLET_KEY_LOOKAHEAD_INCREMENT, keyChain.getLeafKeys().size())
        );

        if (batch.isEmpty()) {
            // Ask keyChain to generate more if needed
            keyChain.setLookaheadSize(allAddresses.size() + WALLET_KEY_LOOKAHEAD_INCREMENT);
            keyChain.maybeLookAhead();

            // Try to get new keys again
            batch = keyChain.getLeafKeys().subList(
                allAddresses.size(), 
                Math.min(allAddresses.size() + WALLET_KEY_LOOKAHEAD_INCREMENT, keyChain.getLeafKeys().size())
            );

            if (batch.isEmpty()) {
                LOGGER.warn("⚠️ No more keys to process.");
                break;
            }
        }

        batchCount++;
        boolean foundTxInBatch = false;

        List<Future<Boolean>> futures = new ArrayList<>();

        for (DeterministicKey dKey : batch) {
            Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
            allAddresses.add(address.toString());

            // First check cache
            if (this.blockchainCache.keyHasHistory(dKey)) {
                foundTxInBatch = true;
                LOGGER.debug("Cached transaction found for {}", address);
            } else {
                // Submit async transaction check
                futures.add(executor.submit(() -> {
                    boolean hasTx = checkForTransactions(dKey, address);
                    if (hasTx) LOGGER.debug("Live transaction found for {}", address);
                    return hasTx;
                }));
            }
        }

        // Wait for async results
        for (Future<Boolean> future : futures) {
            try {
                if (future.get(10, TimeUnit.SECONDS)) {
                    foundTxInBatch = true;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed transaction check", e);
            }
        }

        if (foundTxInBatch) {
            unusedCounter = 0; // Reset on successful discovery
        } else {
            unusedCounter += WALLET_KEY_LOOKAHEAD_INCREMENT;
        }

        LOGGER.debug("Batch {}: Processed {} keys, unusedCounter = {}", batchCount, batch.size(), unusedCounter);

        if (unusedCounter >= gapLimit) {
            LOGGER.debug("Reached gap limit of {} unused keys, stopping.", gapLimit);
            continueProcessing = false;
        }
    }

    LOGGER.debug("Discovered {} total addresses in {} batches", allAddresses.size(), batchCount);
    return allAddresses;
}


	/**
	 * Process Keys
	 *
	 * Generate keys asynchronously
	 *
	 * @param executor for asynchronous processing
	 * @param keys the keys generated
	 * @param keyChain the key chain to generate the keys from
	 * @param unusedCounter starts at zero, increases during recursion
	 *
	 * @return the addresses derived from the keys
	 */
	private Set<String> processKeys(ExecutorService executor, List<DeterministicKey> keys, DeterministicKeyChain keyChain, int unusedCounter) {

		// the return value
		Set<String> keySet = new HashSet<>();

		boolean needToProcessAdditionalKeys = false;

		List<Future<Boolean>> transactionChecks = new ArrayList<>(keys.size());

		// for each key, collect address, determine additional key generation
		for (DeterministicKey dKey : keys) {

			Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
			keySet.add(address.toString());

			// if the key already has a verified transaction history
			if( this.blockchainCache.keyHasHistory( dKey ) ){
				needToProcessAdditionalKeys = true;
			}
			// if the key does not have a verified transaction history
			else {
				transactionChecks.add( executor.submit(() -> checkForTransactions(dKey, address)));
			}
		}

		// process more keys
		if( needToProcessAdditionalKeys || anyTrue( transactionChecks )) {
			keySet.addAll(processKeys(executor, generateMoreKeys(keyChain), keyChain, 0));
		}
		// if no additional keys were already processed and the if the gap limit held, then process additional keys
		else if ( unusedCounter < Settings.getInstance().getGapLimit()) {

			keySet.addAll(processKeys(executor, generateMoreKeys(keyChain), keyChain, unusedCounter + WALLET_KEY_LOOKAHEAD_INCREMENT));
		}

		return keySet;
	}

	/**
	 * Process Keys Only
	 *
	 * Generate keys asynchronously, no addresses are generated
	 *
	 * @param executor for asynchronou processing
	 * @param keys the generated keys
	 * @param keyChain for determining keys to generate
	 * @param unusedCounter start at zero, increases from recursion
	 *
	 * @return the generated keys
	 *
	 * @throws ForeignBlockchainException
	 */
	private Set<DeterministicKey> processKeysOnly(ExecutorService executor, List<DeterministicKey> keys, DeterministicKeyChain keyChain, int unusedCounter) throws ForeignBlockchainException {

		Set<DeterministicKey> keySet = new HashSet<>();

		boolean needToProcessAdditionalKeys = false;

		List<Future<Boolean>> transactionChecks = new ArrayList<>(keys.size());

		for (DeterministicKey dKey : keys) {

			Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
			keySet.add(dKey);

			// if the key already has a verified transaction history
			if( this.blockchainCache.keyHasHistory( dKey ) ){
				needToProcessAdditionalKeys = true;
			}
			// if the key does not have a verified transaction history
			else {
				transactionChecks.add( executor.submit(() -> checkForTransactions(dKey, address)));
			}
		}

		if( needToProcessAdditionalKeys || anyTrue( transactionChecks )) {
			keySet.addAll(processKeysOnly(executor, generateMoreKeys(keyChain), keyChain, 0));
		}
		// if no additional keys were already processed and the if the gap limit held, then process additional keys
		else if ( unusedCounter < Settings.getInstance().getGapLimit()) {

			keySet.addAll(processKeysOnly(executor, generateMoreKeys(keyChain), keyChain, unusedCounter + WALLET_KEY_LOOKAHEAD_INCREMENT));
		}

		return keySet;
	}

	/**
	 * Any True?
	 *
	 * Are any of the future tasks returning true?
	 *
	 * @param futures the future tasks
	 *
	 * @return true if any task returns true, false if all tasks return false
	 */
	public static boolean anyTrue(List<Future<Boolean>> futures) {

		try {
			while( true ) {

				int falseCount = 0;

				for (Future<Boolean> future : futures) {

					if (future.isDone()) {

						// if one is true, then return true
						if( future.get() ) {
							return true;
						}
						else {
							falseCount++;
						}
					}
				}

				// if all are done and all are false, then none are true
				if( falseCount == futures.size() ) {
					return false;
				}
			}
		} catch (InterruptedException | ExecutionException e) {
			LOGGER.error(e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Any Transactions?
	 *
	 * Any transactions for this address?
	 *
	 * @param dKey the key that generated this address
	 * @param address the address
	 *
	 * @return true if there are any transactions for this address, false if there are no transactions
	 *
	 * @throws ForeignBlockchainException
	 */
	private boolean checkForTransactions(DeterministicKey dKey, Address address) throws ForeignBlockchainException {
		// Check for transactions
		byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

		// Ask for transaction history - if it's empty then key has never been used
		List<TransactionHash> historicTransactionHashes = this.getAddressTransactions(script, true);

		// if the key has history, then it should be processing additional keys
		if (!historicTransactionHashes.isEmpty()) {
			this.blockchainCache.addKeyWithHistory(dKey);
			return true;
		}

		return false;
	}

	/**
	 * Get Transactions
	 *
	 * Get all the transactions for an address, asynchronously.
	 *
	 * @param address the address
	 * @param futures where the transaction fetch tasks get collected
	 * @param executor for asychronous processing
	 *
	 * @return true if the adddress has any transactions, false for no transactions
	 *
	 * @throws ForeignBlockchainException
	 */
	private boolean getTransactions(Address address, List<Future<Optional<BitcoinyTransaction>>> futures, ExecutorService executor) throws ForeignBlockchainException {

		// return value
		boolean processAdditionalKeys = false;

		// Check for transactions
		byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

		// Ask for transaction history - if it's empty then key has never been used
		List<TransactionHash> historicTransactionHashes = this.getAddressTransactions(script, true);

		// if the key has history, then it should be processing additional keys
		if (!historicTransactionHashes.isEmpty()) {

			processAdditionalKeys = true;

			// get the transactions from the hashes
			for (TransactionHash transactionHash : historicTransactionHashes) {

				Optional<BitcoinyTransaction> walletTransaction
						= this.blockchainCache.getTransactionByHash( transactionHash.txHash );

				// if the wallet transaction is already cached
				if(walletTransaction.isPresent() ) {
					futures.add( executor.submit(() -> walletTransaction ) );
				}
				// otherwise get the transaction from the blockchain server
				else {
					futures.add( executor.submit(() -> getBitcoinyTransaction(transactionHash)) );
				}
			}
		}

		return processAdditionalKeys;
	}

	/**
	 * Get Old Wallet Keys
	 *
	 * Get wallet keys using the old key generation algorithm. This is used for diagnosing and repairing wallets
	 * created before 2024.
	 *
	 * @param masterKey BIP32/HD master key (private or public)
	 *
	 * @return the keys
	 *
	 * @throws ForeignBlockchainException
	 */
	private List<DeterministicKey> getOldWalletKeys(String masterKey) throws ForeignBlockchainException {
		Context.propagate(bitcoinjContext);

		Wallet wallet = walletFromDeterministicKey58(masterKey);
		DeterministicKeyChain keyChain = wallet.getActiveKeyChain();

		keyChain.setLookaheadSize(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);
		keyChain.maybeLookAhead();

		List<DeterministicKey> keys = new ArrayList<>(keyChain.getLeafKeys());

		int unusedCounter = 0;
		int ki = 0;
		do {
			boolean areAllKeysUnused = true;

			for (; areAllKeysUnused && ki < keys.size(); ++ki) {
				DeterministicKey dKey = keys.get(ki);

				// if the key already has a verified transaction history
				if( this.blockchainCache.keyHasHistory(dKey)) {
					areAllKeysUnused = false;
				}
				else {
					// Check for transactions
					Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
					byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

					// Ask for transaction history - if it's empty then key has never been used
					List<TransactionHash> historicTransactionHashes = this.getAddressTransactions(script, true);

					if (!historicTransactionHashes.isEmpty()) {
						areAllKeysUnused = false;
						this.blockchainCache.addKeyWithHistory(dKey);
					}
				}
			}

			if (areAllKeysUnused) {
				// No transactions
				if (unusedCounter >= Settings.getInstance().getGapLimit()) {
					// ... and we've hit our search limit
					break;
				}
				// We haven't hit our search limit yet so increment the counter and keep looking
				unusedCounter += WALLET_KEY_LOOKAHEAD_INCREMENT;
			} else {
				// Some keys in this batch were used, so reset the counter
				unusedCounter = 0;
			}

			// Generate some more keys
			keys.addAll(generateMoreKeys(keyChain));

			// Process new keys
		} while (true);

		return keys;
	}

	protected SimpleTransaction convertToSimpleTransaction(BitcoinyTransaction t, Set<String> keySet) {
		long amount = 0;
		long total = 0L;
		long totalInputAmount = 0L;
		long totalOutputAmount = 0L;
		List<SimpleTransaction.Input> inputs = new ArrayList<>();
		List<SimpleTransaction.Output> outputs = new ArrayList<>();

		boolean anyOutputAddressInWallet = false;
		boolean transactionInvolvesExternalWallet = false;

		for (BitcoinyTransaction.Input input : t.inputs) {
			try {
				BitcoinyTransaction t2 = getTransaction(input.outputTxHash);
				List<String> senders = t2.outputs.get(input.outputVout).addresses;
				long inputAmount = t2.outputs.get(input.outputVout).value;
				totalInputAmount += inputAmount;
				if (senders != null) {
					for (String sender : senders) {
						boolean addressInWallet = false;
						if (keySet.contains(sender)) {
							total += inputAmount;
							addressInWallet = true;
						}
						else {
							transactionInvolvesExternalWallet = true;
						}
						inputs.add(new SimpleTransaction.Input(sender, inputAmount, addressInWallet));
					}
				}
			} catch (ForeignBlockchainException e) {
				LOGGER.warn("Failed to retrieve transaction information {}", input.outputTxHash);
			}
		}

		// Group by sender and sum values
		Map<String, Long> totalSumBySender
			= inputs.stream()
				.collect(Collectors.groupingBy(
						SimpleTransaction.Input::getAddress,
						Collectors.reducing(
								0L,
								SimpleTransaction.Input::getAmount,
								Long::sum
						)
				));

		// Create new objects with summed values
		List<SimpleTransaction.Input> groupedInputs
			= totalSumBySender.entrySet().stream()
				.map(entry -> new SimpleTransaction.Input(entry.getKey(), entry.getValue(), keySet.contains(entry.getKey())))
				.collect(Collectors.toList());

		inputs.clear();
		inputs.addAll(groupedInputs);

		if (t.outputs != null && !t.outputs.isEmpty()) {
			for (BitcoinyTransaction.Output output : t.outputs) {
				if (output.addresses != null) {
					for (String address : output.addresses) {
						boolean addressInWallet = false;
						if (keySet.contains(address)) {
							if (total > 0L) { // Change returned from sent amount
								amount -= (total - output.value);
							} else { // Amount received
								amount += output.value;
							}
							addressInWallet = true;
							anyOutputAddressInWallet = true;
						}
						else {
							transactionInvolvesExternalWallet = true;
						}
						outputs.add(new SimpleTransaction.Output(address, output.value, addressInWallet));
					}
				}
				totalOutputAmount += output.value;
			}
		}

		// Group by address and sum values
		Map<String, Long> totalSumByAddress
				= outputs.stream()
				.collect(Collectors.groupingBy(
						SimpleTransaction.Output::getAddress,
						Collectors.reducing(
								0L,
								SimpleTransaction.Output::getAmount,
								Long::sum
						)
				));

		// Create new objects with summed values
		List<SimpleTransaction.Output> groupedOutputs
				= totalSumByAddress.entrySet().stream()
				.map(entry -> new SimpleTransaction.Output(entry.getKey(), entry.getValue(), keySet.contains(entry.getKey())))
				.collect(Collectors.toList());

		outputs.clear();
		outputs.addAll(groupedOutputs);

		long fee = totalInputAmount - totalOutputAmount;

		if (!anyOutputAddressInWallet) {
			// No outputs relate to this wallet - check if any inputs did (which is signified by a positive total)
			if (total > 0) {
				amount = total * -1;
			}
		}
		else if (!transactionInvolvesExternalWallet) {
			// All inputs and outputs relate to this wallet, so the balance should be unaffected
			amount = 0;
		}
		Long timestampMillis;

		if( t.timestamp != null )
			timestampMillis = t.timestamp * 1000L;
		else
			timestampMillis = null;

		return new SimpleTransaction(t.txHash, timestampMillis, amount, fee, inputs, outputs, null);
	}

	/**
	 * Returns first unused receive address given a BIP32 key.
	 *
	 * @param key58 BIP32/HD extended Bitcoin private/public key
	 * @return P2PKH address
	 * @throws ForeignBlockchainException if something went wrong
	 */
	public String getUnusedReceiveAddress(String key58) throws ForeignBlockchainException {
		Context.propagate(bitcoinjContext);

		Wallet wallet = walletFromDeterministicKey58(key58);
		DeterministicKeyChain keyChain = wallet.getActiveKeyChain();

		do {
			// the next receive funds address
			Address address = Address.fromKey(this.params, keyChain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS), ScriptType.P2PKH);

			// if zero transactions, return address
			if(getAddressTransactions(ScriptBuilder.createOutputScript(address).getProgram(), true).isEmpty())
				return address.toString();

			// else try the next receive funds address
		} while (true);
	}

	public abstract long getFeeRequired();

	public abstract void setFeeRequired(long fee);

	// UTXOProvider support

	static class WalletAwareUTXOProvider implements UTXOProvider {
		private final Bitcoiny bitcoiny;
		private final Wallet wallet;

		private final DeterministicKeyChain keyChain;

		public WalletAwareUTXOProvider(Bitcoiny bitcoiny, Wallet wallet) {
			this.bitcoiny = bitcoiny;
			this.wallet = wallet;
			this.keyChain = this.wallet.getActiveKeyChain();

			// Set up wallet's key chain
			this.keyChain.setLookaheadSize(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);
			this.keyChain.maybeLookAhead();
		}

		@Override
		public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
			Set<String> addresses = bitcoiny.processKeys(EXECUTOR, this.keyChain.getLeafKeys(), this.keyChain, 0);

			List<Future<UTXO>> futures = new ArrayList<>();

			try {
				for( String address : addresses ) {
					futures.addAll( bitcoiny.getUTXOsAsync( address, true, EXECUTOR) );
				}

				List<UTXO> utxos = new ArrayList<>(futures.size());

				for( Future<UTXO> future : futures ) {

					utxos.add(future.get());
				}

				return utxos;
			} catch (Exception e) {
				throw new UTXOProviderException(e.getMessage());
			}
		}

		@Override
		public int getChainHeadHeight() throws UTXOProviderException {
			try {
				return this.bitcoiny.blockchainProvider.getCurrentHeight();
			} catch (ForeignBlockchainException e) {
				throw new UTXOProviderException("Unable to determine Bitcoiny chain height");
			}
		}

		@Override
		public NetworkParameters getParams() {
			return this.bitcoiny.params;
		}
	}

	private Long summingUnspentOutputs(String walletAddress) throws ForeignBlockchainException {
		return this.getUnspentOutputs(walletAddress, true).stream()
				.map(TransactionOutput::getValue)
				.mapToLong(Coin::longValue)
				.sum();
	}

	// Utility methods for others

	public static List<SimpleForeignTransaction> simplifyWalletTransactions(List<BitcoinyTransaction> transactions) {
		// Sort by oldest timestamp first
		transactions.sort(Comparator.comparingInt(t -> t.timestamp));

		// Manual 2nd-level sort same-timestamp transactions so that a transaction's input comes first
		int fromIndex = 0;
		do {
			int timestamp = transactions.get(fromIndex).timestamp;

			int toIndex;
			for (toIndex = fromIndex + 1; toIndex < transactions.size(); ++toIndex)
				if (transactions.get(toIndex).timestamp != timestamp)
					break;

			// Process same-timestamp sub-list
			List<BitcoinyTransaction> subList = transactions.subList(fromIndex, toIndex);

			// Only if necessary
			if (subList.size() > 1) {
				// Quick index lookup
				Map<String, Integer> indexByTxHash = subList.stream().collect(Collectors.toMap(t -> t.txHash, t -> t.timestamp));

				int restartIndex = 0;
				boolean isSorted;
				do {
					isSorted = true;

					for (int ourIndex = restartIndex; ourIndex < subList.size(); ++ourIndex) {
						BitcoinyTransaction ourTx = subList.get(ourIndex);

						for (BitcoinyTransaction.Input input : ourTx.inputs) {
							Integer inputIndex = indexByTxHash.get(input.outputTxHash);

							if (inputIndex != null && inputIndex > ourIndex) {
								// Input tx is currently after current tx, so swap
								BitcoinyTransaction tmpTx = subList.get(inputIndex);
								subList.set(inputIndex, ourTx);
								subList.set(ourIndex, tmpTx);

								// Update index lookup too
								indexByTxHash.put(ourTx.txHash, inputIndex);
								indexByTxHash.put(tmpTx.txHash, ourIndex);

								if (isSorted)
									restartIndex = Math.max(restartIndex, ourIndex);

								isSorted = false;
								break;
							}
						}
					}
				} while (!isSorted);
			}

			fromIndex = toIndex;
		} while (fromIndex < transactions.size());

		// Simplify
		List<SimpleForeignTransaction> simpleTransactions = new ArrayList<>();

		// Quick lookup of txs in our wallet
		Set<String> walletTxHashes = transactions.stream().map(t -> t.txHash).collect(Collectors.toSet());

		for (BitcoinyTransaction transaction : transactions) {
			SimpleForeignTransaction.Builder builder = new SimpleForeignTransaction.Builder();
			builder.txHash(transaction.txHash);
			builder.timestamp(transaction.timestamp);

			builder.isSentNotReceived(false);

			for (BitcoinyTransaction.Input input : transaction.inputs) {
				// TODO: add input via builder

				if (walletTxHashes.contains(input.outputTxHash))
					builder.isSentNotReceived(true);
			}

			for (BitcoinyTransaction.Output output : transaction.outputs)
				builder.output(output.addresses, output.value);

			simpleTransactions.add(builder.build());
		}

		return simpleTransactions;
	}

	// Utility methods for us

	protected static List<DeterministicKey> generateMoreKeys(DeterministicKeyChain keyChain) {
		int existingLeafKeyCount = keyChain.getLeafKeys().size();

		// Increase lookahead size...
		keyChain.setLookaheadSize(keyChain.getLookaheadSize() + Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);
		// ...and lookahead threshold (minimum number of keys to generate)...
		keyChain.setLookaheadThreshold(0);
		// ...so that this call will generate more keys
		keyChain.maybeLookAhead();

		// This returns *all* keys
		List<DeterministicKey> allLeafKeys = keyChain.getLeafKeys();

		// Only return newly generated keys
		return allLeafKeys.subList(existingLeafKeyCount, allLeafKeys.size());
	}

	protected byte[] addressToScriptPubKey(String base58Address) {
		Context.propagate(this.bitcoinjContext);
		Address address = Address.fromString(this.params, base58Address);
		return ScriptBuilder.createOutputScript(address).getProgram();
	}

	protected Wallet walletFromDeterministicKey58(String key58) {
		DeterministicKey dKey = DeterministicKey.deserializeB58(null, key58, this.params);

		if (dKey.hasPrivKey())
			return Wallet.fromSpendingKeyB58(this.params, key58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		else
			return Wallet.fromWatchingKeyB58(this.params, key58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
	}

	private static class UtxoSummary {
		private final long balance;
		private final int utxoCount;

		private UtxoSummary(long balance, int utxoCount) {
			this.balance = balance;
			this.utxoCount = utxoCount;
		}
	}

	private Map<String, UtxoSummary> getUtxoSummaries(Set<String> addresses) throws ForeignBlockchainException {
		Map<String, Future<UtxoSummary>> futures = new HashMap<>(addresses.size());

		for (String address : addresses) {
			futures.put(address, EXECUTOR.submit(() -> {
				List<TransactionOutput> outputs = getUnspentOutputs(address, true);
				long balance = 0L;
				int utxoCount = 0;

				for (TransactionOutput output : outputs) {
					balance += output.getValue().value;
					utxoCount++;
				}

				return new UtxoSummary(balance, utxoCount);
			}));
		}

		Map<String, UtxoSummary> summaries = new HashMap<>(addresses.size());
		for (Map.Entry<String, Future<UtxoSummary>> entry : futures.entrySet()) {
			try {
				summaries.put(entry.getKey(), entry.getValue().get(10, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ForeignBlockchainException("Interrupted while fetching unspent outputs", e);
			} catch (ExecutionException | TimeoutException e) {
				throw new ForeignBlockchainException("Unable to fetch unspent outputs", e);
			}
		}

		return summaries;
	}

	private long estimateSweepFee(int inputCount, Coin feePerKb) {
		if (inputCount <= 0 || feePerKb == null)
			return 0L;

		int outputCount = 1;
		int txSize = 10 + (inputCount * 148) + (outputCount * 34);
		return (feePerKb.value * txSize) / 1000L;
	}

	public RepairWalletPreview previewRepairOldWallet(String key58) throws ForeignBlockchainException {
		Context.propagate(this.bitcoinjContext);

		List<DeterministicKey> oldKeys = getOldWalletKeys(key58);
		if (oldKeys.isEmpty())
			throw new ForeignBlockchainException("Unable to derive old wallet keys");

		Set<String> oldAddresses = oldKeys.stream()
				.map(key -> Address.fromKey(this.params, key, ScriptType.P2PKH).toString())
				.collect(Collectors.toSet());

		Set<String> currentAddresses = getWalletAddressesWithExecutor(key58, EXECUTOR);

		Set<String> allAddresses = new HashSet<>(oldAddresses);
		allAddresses.addAll(currentAddresses);

		Map<String, UtxoSummary> summaries = getUtxoSummaries(allAddresses);

		long oldBalance = 0L;
		int oldUtxoCount = 0;
		for (String address : oldAddresses) {
			UtxoSummary summary = summaries.get(address);
			if (summary != null) {
				oldBalance += summary.balance;
				oldUtxoCount += summary.utxoCount;
			}
		}

		long currentBalance = 0L;
		for (String address : currentAddresses) {
			UtxoSummary summary = summaries.get(address);
			if (summary != null) {
				currentBalance += summary.balance;
			}
		}

		Set<String> missingAddresses = new HashSet<>(oldAddresses);
		missingAddresses.removeAll(currentAddresses);

		long missingBalance = 0L;
		int missingUtxoCount = 0;
		for (String address : missingAddresses) {
			UtxoSummary summary = summaries.get(address);
			if (summary != null) {
				missingBalance += summary.balance;
				missingUtxoCount += summary.utxoCount;
			}
		}

		Coin feePerKb = this.getFeePerKb();
		long estimatedFee = estimateSweepFee(oldUtxoCount, feePerKb);

		Address primaryAddress = Address.fromKey(this.params, oldKeys.get(0), ScriptType.P2PKH);
		TransactionOutput output = new TransactionOutput(this.params, null, Coin.ZERO, primaryAddress);
		Coin dustThresholdCoin = feePerKb != null ? output.getMinNonDustValue(feePerKb) : output.getMinNonDustValue();
		long dustThreshold = dustThresholdCoin.value;

		boolean repairRecommended = missingBalance > 0 && missingBalance > (estimatedFee + dustThreshold);

		return new RepairWalletPreview(
				oldBalance,
				currentBalance,
				missingBalance,
				estimatedFee,
				dustThreshold,
				repairRecommended,
				oldAddresses.size(),
				currentAddresses.size(),
				missingUtxoCount);
	}

	/**
	 * Repair Wallet
	 *
	 * Repair wallets generated before 2024 by moving all the address balances to the first address.
	 *
	 * @param privateMasterKey
	 *
	 * @return the transaction Id of the spend operation that moves the balances
	 *
	 * @throws ForeignBlockchainException
	 * @throws InsufficientMoneyException
	 */
	public String repairOldWallet(String privateMasterKey) throws ForeignBlockchainException, InsufficientMoneyException {

		// create a deterministic wallet to satisfy the bitcoinj API
		Wallet wallet = Wallet.createDeterministic(this.bitcoinjContext, ScriptType.P2PKH);

		// use the blockchain resources of this instance for UTXO provision
		wallet.setUTXOProvider(new BitcoinyUTXOProvider( this ));

		// import in each that is generated using the old key generation algorithm
		List<DeterministicKey> walletKeys = getOldWalletKeys(privateMasterKey);

		for( DeterministicKey key : walletKeys) {
			wallet.importKey(ECKey.fromPrivate(key.getPrivKey()));
		}

		// get the primary receive address
		Address firstAddress = Address.fromKey(this.params, walletKeys.get(0), ScriptType.P2PKH);

		// send all the imported coins to the primary receive address
		SendRequest sendRequest = SendRequest.emptyWallet(firstAddress);
		sendRequest.feePerKb = this.getFeePerKb();

		try {
			// allow the wallet to build the send request transaction and broadcast
			wallet.completeTx(sendRequest);
			broadcastTransaction(sendRequest.tx);

			// return the transaction Id
			return sendRequest.tx.getTxId().toString();
		}
		catch (InsufficientMoneyException e) {
			LOGGER.warn("Unable to repair wallet due to insufficient funds", e);
			throw e;
		}
		catch (Exception e) {
			LOGGER.error("Failed to repair wallet", e);
			throw new ForeignBlockchainException("Unable to repair wallet", e);
		}
	}
}
