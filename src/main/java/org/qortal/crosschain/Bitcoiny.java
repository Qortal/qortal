package org.qortal.crosschain;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.params.AbstractBitcoinNetParams;
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
import java.util.stream.Collectors;

/** Bitcoin-like (Bitcoin, Litecoin, etc.) support */
public abstract class Bitcoiny extends AbstractBitcoinNetParams implements ForeignBlockchain {

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

	// Constructors and instance

	protected Bitcoiny(BitcoinyBlockchainProvider blockchainProvider, Context bitcoinjContext, String currencyCode, Coin feePerKb) {
		this.genesisBlock = this.getGenesisBlock();
		this.blockchainProvider = blockchainProvider;
		this.bitcoinjContext = bitcoinjContext;
		this.currencyCode = currencyCode;
		this.feePerKb = feePerKb;

		this.params = this.bitcoinjContext.getParams();
	}

	// Getters & setters
	@Override
	public String getPaymentProtocolId() {
		return this.id;
	}

	@Override
	public Block getGenesisBlock() {
		return this.genesisBlock;
	}

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

		List<TransactionOutput> unspentTransactionOutputs = new ArrayList<>();
		for (UnspentOutput unspentOutput : unspentOutputs) {
			List<TransactionOutput> transactionOutputs = this.getOutputs(unspentOutput.hash);

			unspentTransactionOutputs.add(transactionOutputs.get(unspentOutput.index));
		}

		return unspentTransactionOutputs;
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

		List<TransactionOutput> allUnspentOutputs = new ArrayList<>();
		Set<String> walletAddresses = this.getWalletAddresses(key58);
		for (String address : walletAddresses) {
			allUnspentOutputs.addAll(this.getUnspentOutputs(address, true));
		}
		for (TransactionOutput output : allUnspentOutputs) {
			if (!output.isAvailableForSpending()) {
				continue;
			}
			balance += output.getValue().value;
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
		synchronized (this) {
			// Serve from the cache if it's recent, and matches this xpub
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

			Set<BitcoinyTransaction> walletTransactions = new HashSet<>();
			Set<String> keySet = new HashSet<>();

			int unusedCounter = 0;
			int ki = 0;
			do {
				boolean areAllKeysUnused = true;

				for (; ki < keys.size(); ++ki) {
					DeterministicKey dKey = keys.get(ki);

					// Check for transactions
					Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
					keySet.add(address.toString());
					byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

					// Ask for transaction history - if it's empty then key has never been used
					List<TransactionHash> historicTransactionHashes = this.getAddressTransactions(script, true);

					if (!historicTransactionHashes.isEmpty()) {
						areAllKeysUnused = false;

						for (TransactionHash transactionHash : historicTransactionHashes) {

							Optional<BitcoinyTransaction> walletTransaction
									= this.blockchainCache.getTransactionByHash( transactionHash.txHash );

							// if the wallet transaction is already cached
							if(walletTransaction.isPresent() ) {
								walletTransactions.add( walletTransaction.get() );
							}
							// otherwise get the transaction from the blockchain server
							else {
								BitcoinyTransaction transaction = getTransaction(transactionHash.txHash);
								walletTransactions.add( transaction );
								this.blockchainCache.addTransactionByHash(transactionHash.txHash, transaction);
							}
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

			Comparator<SimpleTransaction> newestTimestampFirstComparator = Comparator.comparingLong(SimpleTransaction::getTimestamp).reversed();

			// Update cache and return
			transactionsCacheTimestamp = NTP.getTime();
			transactionsCacheXpub = key58;
			transactionsCache = walletTransactions.stream()
					.map(t -> convertToSimpleTransaction(t, keySet))
					.sorted(newestTimestampFirstComparator).collect(Collectors.toList());

			return transactionsCache;
		}
	}

	public List<AddressInfo> getWalletAddressInfos(String key58) throws ForeignBlockchainException {
		List<AddressInfo> infos = new ArrayList<>();

		List<String> candidates = this.getSpendingCandidateAddresses(key58);

		for(DeterministicKey key : getOldWalletKeys(key58)) {
			infos.add(buildAddressInfo(key, candidates));
		}

		return infos.stream()
				.sorted(new PathComparator(1))
				.collect(Collectors.toList());
	}

	public AddressInfo buildAddressInfo(DeterministicKey key, List<String> candidates) throws ForeignBlockchainException  {

		Address address = Address.fromKey(this.params, key, ScriptType.P2PKH);

		int transactionCount = getAddressTransactions(ScriptBuilder.createOutputScript(address).getProgram(), true).size();

		return new AddressInfo(
				address.toString(),
				toIntegerList( key.getPath() ),
				summingUnspentOutputs(address.toString()),
				key.getPathAsString(),
				transactionCount,
				candidates.contains(address.toString()));
	}

	/**
	 * <p>Convert BitcoinJ native type to List of Integers, BitcoinJ v16 compatible
	 * </p>
	 *
	 * @param path path to deterministic key
	 * @return Array of Ints representing the keys position in the tree
	 * @since v4.7.2
	 */
	private static  List<Integer> toIntegerList(HDPath path) {
		return path.stream().map(ChildNumber::num).collect(Collectors.toList());
	}

	// BitcoinJ v15 compatible
	private static  List<Integer> toIntegerList(ImmutableList<ChildNumber> path) {
		return path.stream().map(ChildNumber::num).collect(Collectors.toList());
	}

	public Set<String> getWalletAddresses(String key58) throws ForeignBlockchainException {
		synchronized (this) {
			Context.propagate(bitcoinjContext);

			Wallet wallet = walletFromDeterministicKey58(key58);
			DeterministicKeyChain keyChain = wallet.getActiveKeyChain();

			keyChain.setLookaheadSize(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);
			keyChain.maybeLookAhead();

			List<DeterministicKey> keys = new ArrayList<>(keyChain.getLeafKeys());

			Set<String> keySet = new HashSet<>();

			int unusedCounter = 0;
			int ki = 0;
			do {
				boolean areAllKeysUnused = true;

				for (; ki < keys.size(); ++ki) {
					DeterministicKey dKey = keys.get(ki);

					Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
					keySet.add(address.toString());

					// if the key already has a verified transaction history
					if( this.blockchainCache.keyHasHistory( dKey ) ){
						areAllKeysUnused = false;
					}
					else {
						// Check for transactions
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

			return keySet;
		}
	}

	/**
	 * Get Old Wallet Keys
	 *
	 * Get wallet keys using the old key generation algorithm. This is used for diagnosing and repairing wallets
	 * created before 2024.
	 *
	 * @param masterPrivateKey
	 *
	 * @return the keys
	 *
	 * @throws ForeignBlockchainException
	 */
	private List<DeterministicKey> getOldWalletKeys(String masterPrivateKey) throws ForeignBlockchainException {
		synchronized (this) {
			Context.propagate(bitcoinjContext);

			Wallet wallet = walletFromDeterministicKey58(masterPrivateKey);
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
				LOGGER.trace("Failed to retrieve transaction information {}", input.outputTxHash);
			}
		}
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
		long timestampMillis = t.timestamp * 1000L;
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

	public abstract long getFeeCeiling();

	public abstract void setFeeCeiling(long fee);

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
			this.keyChain.setLookaheadSize(Settings.getInstance().getBitcoinjLookaheadSize());
			this.keyChain.maybeLookAhead();
		}

		@Override
		public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
			List<UTXO> allUnspentOutputs = new ArrayList<>();
			final boolean coinbase = false;

			int ki = 0;
			do {
				boolean areAllKeysUnspent = true;

				for (; ki < keys.size(); ++ki) {
					ECKey key = keys.get(ki);

					Address address = Address.fromKey(this.bitcoiny.params, key, ScriptType.P2PKH);
					byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

					List<UnspentOutput> unspentOutputs;
					try {
						unspentOutputs = this.bitcoiny.blockchainProvider.getUnspentOutputs(script, true);
					} catch (ForeignBlockchainException e) {
						throw new UTXOProviderException(String.format("Unable to fetch unspent outputs for %s", address));
					}

					/*
					 * If there are no unspent outputs then either:
					 * a) all the outputs have been spent
					 * b) address has never been used
					 *
					 * For case (a) we want to remember not to check this address (key) again.
					 */

					if (unspentOutputs.isEmpty()) {
						// If this is a known key that has been spent before, then we can skip asking for transaction history
						if (this.bitcoiny.spentKeys.contains(key)) {
							this.wallet.getActiveKeyChain().markKeyAsUsed((DeterministicKey) key);
							areAllKeysUnspent = false;
							continue;
						}

						// Ask for transaction history - if it's empty then key has never been used
						List<TransactionHash> historicTransactionHashes;
						try {
							historicTransactionHashes = this.bitcoiny.blockchainProvider.getAddressTransactions(script, false);
						} catch (ForeignBlockchainException e) {
							throw new UTXOProviderException(String.format("Unable to fetch transaction history for %s", address));
						}

						if (!historicTransactionHashes.isEmpty()) {
							// Fully spent key - case (a)
							this.bitcoiny.spentKeys.add(key);
							this.wallet.getActiveKeyChain().markKeyAsUsed((DeterministicKey) key);
							areAllKeysUnspent = false;
						} else {
							// Key never been used - case (b)
						}

						continue;
					}

					// If we reach here, then there's definitely at least one unspent key
					this.bitcoiny.spentKeys.remove(key);

					for (UnspentOutput unspentOutput : unspentOutputs) {
						List<TransactionOutput> transactionOutputs;
						try {
							transactionOutputs = this.bitcoiny.getOutputs(unspentOutput.hash);
						} catch (ForeignBlockchainException e) {
							throw new UTXOProviderException(String.format("Unable to fetch outputs for TX %s",
									HashCode.fromBytes(unspentOutput.hash)));
						}

						TransactionOutput transactionOutput = transactionOutputs.get(unspentOutput.index);

						UTXO utxo = new UTXO(Sha256Hash.wrap(unspentOutput.hash), unspentOutput.index,
								Coin.valueOf(unspentOutput.value), unspentOutput.height, coinbase,
								transactionOutput.getScriptPubKey());

						allUnspentOutputs.add(utxo);
					}
				}

				if (areAllKeysUnspent)
					// No transactions for this batch of keys so assume we're done searching.
					return allUnspentOutputs;

				// Generate some more keys
				keys.addAll(Bitcoiny.generateMoreKeys(this.keyChain));

				// Process new keys
			} while (true);
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

	/**
	 * Repair Wallet
	 *
	 * Repair wallets generated before 2024 by moving all the address balances to the first address.
	 *
	 * @param privateMasterKey
	 *
	 * @return the transaction Id of the spend operation that moves the balances or the exception name if an exception
	 * is thrown
	 *
	 * @throws ForeignBlockchainException
	 */
	public String repairOldWallet(String privateMasterKey) throws ForeignBlockchainException {

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
		catch( Exception e ) {
			// log error and return exception name
			LOGGER.error(e.getMessage(), e);
			return e.getClass().getSimpleName();
		}
	}
}
