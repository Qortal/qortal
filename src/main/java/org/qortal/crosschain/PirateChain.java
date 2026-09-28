package org.qortal.crosschain;

import pirate.wallet.sdk.rpc.CompactFormats;
import com.google.common.hash.HashCode;
import com.rust.litewalletjni.LiteWalletJni;
import org.bitcoinj.core.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.libdohj.params.LitecoinRegTestParams;
import org.libdohj.params.LitecoinTestNet3Params;
import org.libdohj.params.PirateChainMainNetParams;
import org.qortal.api.model.crosschain.PirateChainBalance;
import org.qortal.api.model.crosschain.PirateChainSendRequest;
import org.qortal.controller.PirateChainWalletController;
import org.qortal.crosschain.PirateLightClient.Server;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;
import org.qortal.transform.TransformationException;
import org.qortal.utils.BitTwiddling;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class PirateChain extends Bitcoiny {

	public static final String CURRENCY_CODE = "ARRR";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(10000); // 0.0001 ARRR per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 10000; // 0.0001 ARRR minimum order, to avoid dust errors // TODO:
																													// increase this

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 10000L; // 0.0001 ARRR
	private static final long NON_MAINNET_FEE = 10000L; // 0.0001 ARRR
	private static final long RECIPIENT_ENRICHMENT_RETRY_MS = 15_000L;
	private static final int RECIPIENT_ENRICHMENT_BATCH_SIZE = 4;
	private static final int MAX_RECIPIENT_CACHE_WALLETS = 64;
	private static final int MAX_RECIPIENT_CACHE_TRANSACTIONS = 2_048;

	private static final Map<ConnectionType, Integer> DEFAULT_LITEWALLET_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_LITEWALLET_PORTS.put(ConnectionType.TCP, 9067);
		DEFAULT_LITEWALLET_PORTS.put(ConnectionType.SSL, 443);
	}

	public enum PirateChainNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return PirateChainMainNetParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
						// Servers chosen on NO BASIS WHATSOEVER from various sources!
						new Server("arrr3.qortal.link", Server.ConnectionType.SSL, 443),
						new Server("arrr.qortal.link", Server.ConnectionType.SSL, 443),
						new Server("arrr2.qortal.link", Server.ConnectionType.SSL, 443),
						new Server("lightd.pirate.black", Server.ConnectionType.SSL, 443),
						new Server("lightd1.pirate.black", Server.ConnectionType.SSL, 443));
			}

			@Override
			public String getGenesisHash() {
				return "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return this.getFeeRequired();
			}
		},
		TEST3 {
			@Override
			public NetworkParameters getParams() {
				return LitecoinTestNet3Params.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList();
			}

			@Override
			public String getGenesisHash() {
				return "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		},
		REGTEST {
			@Override
			public NetworkParameters getParams() {
				return LitecoinRegTestParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
						new Server("localhost", Server.ConnectionType.TCP, 9067),
						new Server("localhost", Server.ConnectionType.SSL, 443));
			}

			@Override
			public String getGenesisHash() {
				// This is unique to each regtest instance
				return null;
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		};

		private AtomicLong feeRequired = new AtomicLong(MAINNET_FEE);

		public long getFeeRequired() {
			return feeRequired.get();
		}

		public void setFeeRequired(long feeRequired) {
			this.feeRequired.set(feeRequired);
		}

		public abstract NetworkParameters getParams();

		public abstract Collection<Server> getServers();

		public abstract String getGenesisHash();

		public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;
	}

	private static PirateChain instance;

	private final PirateChainNet pirateChainNet;

	// Scheduled executor service to check connection to Pirate Chain server
	private final ScheduledExecutorService pirateChainCheckScheduler = Executors.newScheduledThreadPool(1);
	private final ScheduledExecutorService recipientEnrichmentScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "PirateRecipientEnrichment");
		thread.setDaemon(true);
		return thread;
	});
	private final Map<String, RecipientEnrichmentCache> recipientCaches = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, RecipientEnrichmentCache> eldest) {
			return size() > MAX_RECIPIENT_CACHE_WALLETS;
		}
	};

	// Constructors and instance

	private PirateChain(PirateChainNet pirateChainNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext,
			String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode, DEFAULT_FEE_PER_KB);
		this.pirateChainNet = pirateChainNet;

		pirateChainCheckScheduler.scheduleWithFixedDelay(this::establishConnection, 30, 300, TimeUnit.SECONDS);

		LOGGER.info(() -> String.format("Starting Pirate Chain support using %s", this.pirateChainNet.name()));
	}

	public static synchronized PirateChain getInstance() {
		if (instance == null && Settings.getInstance().isWalletEnabled("ARRR")) {
			PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();

			BitcoinyBlockchainProvider pirateLightClient = new PirateLightClient("PirateChain-" + pirateChainNet.name(),
					pirateChainNet.getGenesisHash(), pirateChainNet.getServers(), DEFAULT_LITEWALLET_PORTS);
			Context bitcoinjContext = new Context(pirateChainNet.getParams());

			instance = new PirateChain(pirateChainNet, pirateLightClient, bitcoinjContext, CURRENCY_CODE);

			pirateLightClient.setBlockchain(instance);
		}

		return instance;
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		instance = null;
	}

	// Actual useful methods for use by other classes

	@Override
	public long getMinimumOrderAmount() {
		return MINIMUM_ORDER_AMOUNT;
	}

	/**
	 * Returns estimated Cross-Chain fee, in sats per 1000bytes, optionally for historic
	 * timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something
	 *         went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.pirateChainNet.getP2shFee(timestamp);
	}

	@Override
	public long getFeeRequired() {
		return this.pirateChainNet.getFeeRequired();
	}

	@Override
	public void setFeeRequired(long fee) {

		this.pirateChainNet.setFeeRequired(fee);
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * 
	 * @return confirmed balance, or zero if balance unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws ForeignBlockchainException {
		return this.blockchainProvider.getConfirmedAddressBalance(base58Address);
	}

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
	 * 
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public int getMedianBlockTime() throws ForeignBlockchainException {
		int height = this.blockchainProvider.getCurrentHeight();

		// Grab latest 11 blocks
		List<Long> blockTimestamps = this.blockchainProvider.getBlockTimestamps(height - 11, 11);
		if (blockTimestamps.size() < 11)
			throw new ForeignBlockchainException("Not enough blocks to determine median block time");

		// Descending order
		blockTimestamps.sort((a, b) -> Long.compare(b, a));

		// Pick median
		return Math.toIntExact(blockTimestamps.get(5));
	}

	/**
	 * Returns list of compact blocks
	 * <p>
	 * 
	 * @throws ForeignBlockchainException if error occurs
	 */
	public List<CompactFormats.CompactBlock> getCompactBlocks(int startHeight, int count)
			throws ForeignBlockchainException {
		return this.blockchainProvider.getCompactBlocks(startHeight, count);
	}

	@Override
	public boolean isValidAddress(String address) {
		// Start with some simple checks
		if (address == null || !address.toLowerCase().startsWith("zs") || address.length() != 78) {
			return false;
		}

		// Now try Bech32 decoding the address (which includes checksum verification)
		try {
			Bech32.Bech32Data decoded = Bech32.decode(address);
			return (decoded != null && Objects.equals("zs", decoded.hrp));
		} catch (AddressFormatException e) {
			// Invalid address, checksum failed, etc
			return false;
		}
	}

	@Override
	public boolean isValidWalletKey(String walletKey) {
		// For Pirate Chain, we only care that the key is a random string
		// 32 characters in length, as it is used as entropy for the seed.
		return walletKey != null && Base58.decode(walletKey).length == 32;
	}

	/** Returns 't3' prefixed P2SH address using passed redeem script. */
	public String deriveP2shAddress(byte[] redeemScriptBytes) {
		Context.propagate(bitcoinjContext);
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
		return LegacyZcashAddress.fromScriptHash(this.params, redeemScriptHash).toString();
	}

	/** Returns 'b' prefixed P2SH address using passed redeem script. */
	public String deriveP2shAddressBPrefix(byte[] redeemScriptBytes) {
		Context.propagate(bitcoinjContext);
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
		return LegacyAddress.fromScriptHash(this.params, redeemScriptHash).toString();
	}

	public Long getWalletBalance(String entropy58) throws ForeignBlockchainException {
		PirateChainBalance balance = getWalletBalances(entropy58);
		return balance == null ? null : balance.zbalance;
	}

	public PirateChainBalance getWalletBalances(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.beginWalletUse(entropy58, false, true, true);
			try {
				// Get balance
				String response = LiteWalletJni.execute("balance", "");
				JSONObject json = parseLitewalletResponse(response, "balance");
				if (!json.has("zbalance")) {
					throw new ForeignBlockchainException("Unable to determine balance");
				}

				PirateChainBalance balance = new PirateChainBalance();
				balance.zbalance = json.getLong("zbalance");
				if (json.has("verified_zbalance")) {
					balance.verified_zbalance = json.getLong("verified_zbalance");
				} else {
					balance.verified_zbalance = balance.zbalance;
				}
				return balance;
			} finally {
				walletController.endWalletUse();
			}
		}
	}

	/**
	 * Establish Connection
	 *
	 * Some methods in this class need to establish a connection before proceeding
	 * and this is the best way
	 * to do it as far as I know.
	 */
	private void establishConnection() {
		try {

			LOGGER.info("Checking Pirate Chain Connection ... ");

			int height;
			synchronized (this) {
				height = this.blockchainProvider.getCurrentHeight();
			}

			LOGGER.info("Checked Pirate Chain Connection: height = " + height);
		} catch (ForeignBlockchainException e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	public List<SimpleTransaction> getWalletTransactions(String entropy58) throws ForeignBlockchainException {

		synchronized (this) {
			long requestStarted = System.nanoTime();
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			// Transaction history is a read of the wallet's persisted view. Q-Wallets
			// already waits for the sync-status endpoint to report "Synchronized", and
			// repeating the native height/info/syncStatus gate here can push this request
			// beyond the Q-App host's 30-second response timeout. Direct API callers can
			// safely receive the currently scanned history while a sync is in progress.
			walletController.beginWalletUse(entropy58, false, false, true);
			try {
				long walletReady = System.nanoTime();
				PirateWallet wallet = walletController.getCurrentWallet();
				String myAddress = wallet.getWalletAddress();
				RecipientEnrichmentCache recipientCache = this.recipientCacheFor(myAddress);
				List<SimpleTransaction> cachedTransactions = wallet.getCachedTransactionHistory();
				if (cachedTransactions != null) {
					cachedTransactions = recipientCache.overlay(cachedTransactions);
					wallet.cacheTransactionHistory(cachedTransactions);
					this.scheduleRecipientEnrichment(entropy58, myAddress, recipientCache, cachedTransactions);
					long completed = System.nanoTime();
					LOGGER.info(
							"Pirate wallet transaction history completed (count={}, source=cache, walletReadyMs={}, totalMs={})",
							cachedTransactions.size(),
							elapsedMillis(requestStarted, walletReady),
							elapsedMillis(requestStarted, completed));
					return cachedTransactions;
				}

				long addressReady = System.nanoTime();
				List<SimpleTransaction> transactions;
				String source;
				if (wallet.usesPersistentUnifiedStorage()) {
					transactions = this.getUnifiedWalletTransactions(myAddress, recipientCache);
					source = "unified-local";
				} else {
					transactions = this.getCompatibilityWalletTransactions(myAddress);
					source = "legacy";
				}
				long historyReady = System.nanoTime();
				transactions = recipientCache.overlay(transactions);
				wallet.cacheTransactionHistory(transactions);
				if (wallet.usesPersistentUnifiedStorage()) {
					this.scheduleRecipientEnrichment(entropy58, myAddress, recipientCache, transactions);
				}

				long completed = System.nanoTime();
				LOGGER.info(
						"Pirate wallet transaction history completed (count={}, source={}, walletReadyMs={}, addressMs={}, historyMs={}, cacheMs={}, totalMs={})",
						transactions.size(),
						source,
						elapsedMillis(requestStarted, walletReady),
						elapsedMillis(walletReady, addressReady),
						elapsedMillis(addressReady, historyReady),
						elapsedMillis(historyReady, completed),
						elapsedMillis(requestStarted, completed));

				return transactions;
			} finally {
				walletController.endWalletUse();
			}
		}
	}

	private List<SimpleTransaction> getUnifiedWalletTransactions(String myAddress,
			RecipientEnrichmentCache recipientCache)
			throws ForeignBlockchainException {
		JSONObject activeWallet = parseLitewalletResponse(
				LiteWalletJni.invokeJson("{\"method\":\"get_active_wallet\"}", false),
				"get_active_wallet");
		String walletId = getUnifiedEnvelopeString(activeWallet, "get_active_wallet");

		JSONObject transactionsRequest = new JSONObject()
				.put("method", "list_transactions")
				.put("wallet_id", walletId)
				.put("limit", JSONObject.NULL);
		JSONArray transactions = getUnifiedEnvelopeArray(parseLitewalletResponse(
				LiteWalletJni.invokeJson(transactionsRequest.toString(), false),
				"list_transactions"), "list_transactions");

		JSONObject depositsRequest = new JSONObject()
				.put("method", "list_incoming_deposits")
				.put("wallet_id", walletId)
				.put("limit", JSONObject.NULL);
		JSONArray deposits = getUnifiedEnvelopeArray(parseLitewalletResponse(
				LiteWalletJni.invokeJson(depositsRequest.toString(), false),
				"list_incoming_deposits"), "list_incoming_deposits");
		recipientCache.rememberInternalAddresses(PirateTransactionHistory.internalAddresses(deposits));

		return PirateTransactionHistory.parseUnified(transactions, deposits, myAddress);
	}

	private RecipientEnrichmentCache recipientCacheFor(String walletAddress) {
		synchronized (this.recipientCaches) {
			return this.recipientCaches.computeIfAbsent(walletAddress,
					ignored -> new RecipientEnrichmentCache(MAX_RECIPIENT_CACHE_TRANSACTIONS));
		}
	}

	private void scheduleRecipientEnrichment(String entropy58, String walletAddress,
			RecipientEnrichmentCache recipientCache, List<SimpleTransaction> transactions) {
		// The stock Unified JNI serializes all calls process-wide. Keep recovery off
		// the response path and release the controller lock between a small number of
		// detail calls so other wallets get a chance to run.
		this.scheduleRecipientEnrichment(entropy58, walletAddress, recipientCache, transactions,
				RECIPIENT_ENRICHMENT_BATCH_SIZE, 1L, TimeUnit.SECONDS);
	}

	private void scheduleRecipientEnrichment(String entropy58, String walletAddress,
			RecipientEnrichmentCache recipientCache, List<SimpleTransaction> transactions,
			int remaining, long delay, TimeUnit delayUnit) {
		if (remaining <= 0) {
			return;
		}
		String txId = recipientCache.claimNext(transactions, System.currentTimeMillis());
		if (txId == null) {
			return;
		}

		try {
			this.recipientEnrichmentScheduler.schedule(
					() -> {
						boolean completed = this.enrichTransactionRecipient(
								entropy58, walletAddress, txId, recipientCache);
						if (completed) {
							this.scheduleRecipientEnrichment(entropy58, walletAddress, recipientCache,
									transactions, remaining - 1, 250L, TimeUnit.MILLISECONDS);
						}
					},
					delay, delayUnit);
		} catch (RuntimeException e) {
			recipientCache.failed(txId, 0L);
			LOGGER.info("Unable to schedule Pirate recipient enrichment ({})", e.getClass().getSimpleName());
		}
	}

	private boolean enrichTransactionRecipient(String entropy58, String expectedWalletAddress, String txId,
			RecipientEnrichmentCache recipientCache) {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		boolean walletUseStarted = false;
		try {
			walletController.beginWalletUse(entropy58, false, false, true);
			walletUseStarted = true;
			PirateWallet wallet = walletController.getCurrentWallet();
			String walletAddress = wallet.getWalletAddress();
			if (!Objects.equals(expectedWalletAddress, walletAddress)) {
				throw new ForeignBlockchainException("Pirate wallet changed before recipient enrichment");
			}
			if (!wallet.isSynchronized()) {
				throw new ForeignBlockchainException("Pirate wallet is not synchronized for recipient enrichment");
			}

			JSONObject activeWallet = parseLitewalletResponse(
					LiteWalletJni.invokeJson("{\"method\":\"get_active_wallet\"}", false),
					"get_active_wallet");
			String walletId = getUnifiedEnvelopeString(activeWallet, "get_active_wallet");
			if (!wallet.isNativeEndpointPoolConfigured()) {
				this.configureNativeEndpointPool(walletId, wallet);
				wallet.setNativeEndpointPoolConfigured();
			}

			JSONObject request = new JSONObject()
					.put("method", "get_transaction_details")
					.put("wallet_id", walletId)
					.put("txid", txId);
			JSONObject response = parseLitewalletResponse(
					LiteWalletJni.invokeJson(request.toString(), false), "get_transaction_details");
			JSONObject details = getUnifiedEnvelopeObject(response, "get_transaction_details");
			PirateTransactionHistory.RecoveredRecipients recovered =
					PirateTransactionHistory.parseTransactionDetails(
							details, recipientCache.getInternalAddresses(), walletAddress);
			if (!txId.equals(recovered.getTxId()) || recovered.getOutputs().isEmpty()) {
				throw new ForeignBlockchainException("Pirate recipient recovery returned no external recipients");
			}

			recipientCache.completed(txId, recovered);
			List<SimpleTransaction> cachedTransactions = wallet.getCachedTransactionHistory();
			if (cachedTransactions != null) {
				wallet.cacheTransactionHistory(recipientCache.overlay(cachedTransactions));
			}
			LOGGER.info("Pirate transaction recipient enrichment completed for one transaction");
			return true;
		} catch (ForeignBlockchainException | RuntimeException e) {
			recipientCache.failed(txId, System.currentTimeMillis() + RECIPIENT_ENRICHMENT_RETRY_MS);
			LOGGER.info("Pirate transaction recipient enrichment deferred ({})", e.getClass().getSimpleName());
			return false;
		} finally {
			if (walletUseStarted) {
				walletController.endWalletUse();
			}
		}
	}

	private void configureNativeEndpointPool(String walletId, PirateWallet wallet)
			throws ForeignBlockchainException {
		if (this.pirateChainNet != PirateChainNet.MAIN
				|| !(this.blockchainProvider instanceof PirateLightClient)) {
			return;
		}

		String primary = wallet.getServerUri();
		if (primary == null || primary.isBlank()) {
			return;
		}
		PirateLightClient lightClient = (PirateLightClient) this.blockchainProvider;
		JSONArray failovers = new JSONArray();
		for (ChainableServer server : lightClient.getServers()) {
			if (server.getConnectionType() != ConnectionType.SSL || lightClient.getUselessServers().contains(server)) {
				continue;
			}
			String candidate = PirateWallet.serverUri(server);
			if (!primary.equalsIgnoreCase(candidate)) {
				failovers.put(candidate);
			}
		}

		JSONObject request = new JSONObject()
				.put("method", "set_lightd_endpoint_pool")
				.put("wallet_id", walletId)
				.put("url", primary)
				.put("tls_pin_opt", JSONObject.NULL)
				.put("failover_endpoints", failovers);
		getUnifiedEnvelopeResult(parseLitewalletResponse(
				LiteWalletJni.invokeJson(request.toString(), false), "set_lightd_endpoint_pool"),
				"set_lightd_endpoint_pool");
	}

	private void rememberSentRecipient(PirateWallet wallet, String txId, String address, long amount, String memo) {
		if (wallet == null || txId == null || address == null) {
			return;
		}
		String walletAddress = wallet.getWalletAddress();
		if (walletAddress == null) {
			return;
		}
		PirateTransactionHistory.RecoveredRecipients recovered =
				new PirateTransactionHistory.RecoveredRecipients(txId,
						List.of(new SimpleTransaction.Output(address, amount, address.equals(walletAddress))), memo);
		this.recipientCacheFor(walletAddress).completed(txId, recovered);
	}

	private List<SimpleTransaction> getCompatibilityWalletTransactions(String myAddress)
			throws ForeignBlockchainException {
		String response = LiteWalletJni.execute("list", "");
		return parseCompatibilityTransactionHistory(parseLitewalletArray(response, "list"), myAddress);
	}

	private static List<SimpleTransaction> parseCompatibilityTransactionHistory(JSONArray transactionsJson,
			String myAddress) throws ForeignBlockchainException {
		return PirateTransactionHistory.parseQortal(transactionsJson, myAddress, MAINNET_FEE);
	}

	private static String getUnifiedEnvelopeString(JSONObject envelope, String command)
			throws ForeignBlockchainException {
		Object result = getUnifiedEnvelopeResult(envelope, command);
		if (!(result instanceof String) || ((String) result).isBlank()) {
			throw new ForeignBlockchainException("Pirate Unified " + command + " returned an invalid result");
		}
		return (String) result;
	}

	private static JSONArray getUnifiedEnvelopeArray(JSONObject envelope, String command)
			throws ForeignBlockchainException {
		Object result = getUnifiedEnvelopeResult(envelope, command);
		if (!(result instanceof JSONArray)) {
			throw new ForeignBlockchainException("Pirate Unified " + command + " returned an invalid result");
		}
		return (JSONArray) result;
	}

	private static JSONObject getUnifiedEnvelopeObject(JSONObject envelope, String command)
			throws ForeignBlockchainException {
		Object result = getUnifiedEnvelopeResult(envelope, command);
		if (!(result instanceof JSONObject)) {
			throw new ForeignBlockchainException("Pirate Unified " + command + " returned an invalid result");
		}
		return (JSONObject) result;
	}

	private static Object getUnifiedEnvelopeResult(JSONObject envelope, String command)
			throws ForeignBlockchainException {
		if (!envelope.optBoolean("ok", false) || !envelope.has("result") || envelope.isNull("result")) {
			throw new ForeignBlockchainException("Pirate Unified " + command + " failed");
		}
		return envelope.get("result");
	}

	private static long elapsedMillis(long started, long completed) {
		return TimeUnit.NANOSECONDS.toMillis(completed - started);
	}

	static final class RecipientEnrichmentCache {
		private final int maximumTransactions;
		private final LinkedHashMap<String, PirateTransactionHistory.RecoveredRecipients> recovered =
				new LinkedHashMap<>(16, 0.75f, true);
		private final Map<String, Long> retryAfter = new HashMap<>();
		private final Set<String> internalAddresses = new HashSet<>();
		private String inFlightTxId;

		RecipientEnrichmentCache(int maximumTransactions) {
			this.maximumTransactions = maximumTransactions;
		}

		synchronized void rememberInternalAddresses(Set<String> addresses) {
			if (addresses != null) {
				this.internalAddresses.addAll(addresses);
			}
		}

		synchronized Set<String> getInternalAddresses() {
			return new HashSet<>(this.internalAddresses);
		}

		synchronized List<SimpleTransaction> overlay(List<SimpleTransaction> transactions) {
			return PirateTransactionHistory.overlayRecoveredRecipients(transactions,
					new HashMap<>(this.recovered));
		}

		synchronized String claimNext(List<SimpleTransaction> transactions, long now) {
			if (this.inFlightTxId != null || transactions == null) {
				return null;
			}
			for (SimpleTransaction transaction : transactions) {
				String txId = transaction == null ? null : transaction.getTxHash();
				if (txId == null || this.recovered.containsKey(txId)
						|| this.retryAfter.getOrDefault(txId, 0L) > now
						|| !PirateTransactionHistory.hasUnresolvedRecipients(Collections.singletonList(transaction))) {
					continue;
				}
				this.inFlightTxId = txId;
				return txId;
			}
			return null;
		}

		synchronized void completed(String txId,
				PirateTransactionHistory.RecoveredRecipients recoveredRecipients) {
			if (txId != null && recoveredRecipients != null) {
				this.recovered.put(txId, recoveredRecipients);
				this.retryAfter.remove(txId);
				while (this.recovered.size() > this.maximumTransactions) {
					Iterator<String> iterator = this.recovered.keySet().iterator();
					iterator.next();
					iterator.remove();
				}
			}
			if (Objects.equals(this.inFlightTxId, txId)) {
				this.inFlightTxId = null;
			}
		}

		synchronized void failed(String txId, long retryAt) {
			if (txId != null && retryAt > 0L) {
				this.retryAfter.put(txId, retryAt);
			}
			if (Objects.equals(this.inFlightTxId, txId)) {
				this.inFlightTxId = null;
			}
		}
	}

	public String getWalletAddress(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.beginWalletUse(entropy58, false, false, true);
			try {
				return walletController.getCurrentWallet().getWalletAddress();
			} finally {
				walletController.endWalletUse();
			}
		}
	}

	public String getPrivateKey(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.beginWalletUse(entropy58, false, false, true);
			try {
				walletController.getCurrentWallet().unlock();
				return walletController.getCurrentWallet().getPrivateKey();
			} finally {
				walletController.endWalletUse();
			}
		}
	}

	public String getWalletSeed(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.beginWalletUse(entropy58, false, false, true);
			try {
				walletController.getCurrentWallet().unlock();
				return walletController.getCurrentWallet().getWalletSeed(entropy58);
			} finally {
				walletController.endWalletUse();
			}
		}
	}

	public String getUnusedReceiveAddress(String key58) throws ForeignBlockchainException {
		// For now, return the main wallet address
		// FUTURE: generate an unused one
		return this.getWalletAddress(key58);
	}

	public String sendCoins(PirateChainSendRequest pirateChainSendRequest) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.beginWalletUse(pirateChainSendRequest.entropy58, false, true, true);
		try {
			// Unlock wallet
			walletController.getCurrentWallet().unlock();

			// Build spend
			JSONObject txn = new JSONObject();
			txn.put("input", walletController.getCurrentWallet().getWalletAddress());
			txn.put("fee", MAINNET_FEE);

			JSONObject output = new JSONObject();
			output.put("address", pirateChainSendRequest.receivingAddress);
			output.put("amount", pirateChainSendRequest.arrrAmount);
			output.put("memo", pirateChainSendRequest.memo);

			JSONArray outputs = new JSONArray();
			outputs.put(output);
			txn.put("output", outputs);

			String txnString = txn.toString();

			// Send the coins
			walletController.getCurrentWallet().clearTransactionHistoryCache();
			String response = LiteWalletJni.execute("send", txnString);
			JSONObject json = parseLitewalletResponse(response, "send");
			try {
				if (json.has("txid")) { // Success
					String txId = json.getString("txid");
					this.rememberSentRecipient(walletController.getCurrentWallet(), txId,
							pirateChainSendRequest.receivingAddress, pirateChainSendRequest.arrrAmount,
							pirateChainSendRequest.memo);
					return txId;
				} else if (json.has("error")) {
					String error = json.getString("error");
					throw new ForeignBlockchainException(error);
				}

			} catch (JSONException e) {
				throw new ForeignBlockchainException(e.getMessage());
			}

			throw new ForeignBlockchainException("Something went wrong");
		} finally {
			walletController.endWalletUse();
		}
	}

	public String fundP2SH(String entropy58, String receivingAddress, long amount,
			String redeemScript58) throws ForeignBlockchainException {

		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.beginWalletUse(entropy58, false, true, true);
		try {
			// Unlock wallet
			walletController.getCurrentWallet().unlock();

			// Build spend
			JSONObject txn = new JSONObject();
			txn.put("input", walletController.getCurrentWallet().getWalletAddress());
			txn.put("fee", MAINNET_FEE);

			JSONObject output = new JSONObject();
			output.put("address", receivingAddress);
			output.put("amount", amount);
			// output.put("memo", memo);

			JSONArray outputs = new JSONArray();
			outputs.put(output);
			txn.put("output", outputs);
			txn.put("script", redeemScript58);

			String txnString = txn.toString();

			// Send the coins
			walletController.getCurrentWallet().clearTransactionHistoryCache();
			String response = LiteWalletJni.execute("sendp2sh", txnString);
			JSONObject json = parseLitewalletResponse(response, "sendp2sh");
			try {
				if (json.has("txid")) { // Success
					String txId = json.getString("txid");
					this.rememberSentRecipient(walletController.getCurrentWallet(), txId,
							receivingAddress, amount, null);
					return txId;
				} else if (json.has("error")) {
					String error = json.getString("error");
					throw new ForeignBlockchainException(error);
				}

			} catch (JSONException e) {
				throw new ForeignBlockchainException(e.getMessage());
			}

			throw new ForeignBlockchainException("Something went wrong");
		} finally {
			walletController.endWalletUse();
		}
	}

	public String redeemP2sh(String p2shAddress, String receivingAddress, long amount, String redeemScript58,
			String fundingTxid58, String secret58, String privateKey58) throws ForeignBlockchainException {

		// Use null seed wallet since we may not have the entropy bytes for a real
		// wallet's seed
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.beginNullSeedWalletUse(false);
		try {
			walletController.getCurrentWallet().unlock();

			// Build spend
			JSONObject txn = new JSONObject();
			txn.put("input", p2shAddress);
			txn.put("fee", MAINNET_FEE);

			JSONObject output = new JSONObject();
			output.put("address", receivingAddress);
			output.put("amount", amount);
			// output.put("memo", ""); // Maybe useful in future to include trade details?

			JSONArray outputs = new JSONArray();
			outputs.put(output);
			txn.put("output", outputs);

			txn.put("script", redeemScript58);
			txn.put("txid", fundingTxid58);
			txn.put("locktime", 0); // Must be 0 when redeeming
			txn.put("secret", secret58);
			txn.put("privkey", privateKey58);

			String txnString = txn.toString();

			// Redeem the P2SH
			walletController.getCurrentWallet().clearTransactionHistoryCache();
			String response = LiteWalletJni.execute("redeemp2sh", txnString);
			JSONObject json = parseLitewalletResponse(response, "redeemp2sh");
			try {
				if (json.has("txid")) { // Success
					return json.getString("txid");
				} else if (json.has("error")) {
					String error = json.getString("error");
					throw new ForeignBlockchainException(error);
				}

			} catch (JSONException e) {
				throw new ForeignBlockchainException(e.getMessage());
			}

			throw new ForeignBlockchainException("Something went wrong");
		} finally {
			walletController.endWalletUse();
		}
	}

	public String refundP2sh(String p2shAddress, String receivingAddress, long amount, String redeemScript58,
			String fundingTxid58, int lockTime, String privateKey58) throws ForeignBlockchainException {

		// Use null seed wallet since we may not have the entropy bytes for a real
		// wallet's seed
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.beginNullSeedWalletUse(false);
		try {
			walletController.getCurrentWallet().unlock();

			// Build spend
			JSONObject txn = new JSONObject();
			txn.put("input", p2shAddress);
			txn.put("fee", MAINNET_FEE);

			JSONObject output = new JSONObject();
			output.put("address", receivingAddress);
			output.put("amount", amount);
			// output.put("memo", ""); // Maybe useful in future to include trade details?

			JSONArray outputs = new JSONArray();
			outputs.put(output);
			txn.put("output", outputs);

			txn.put("script", redeemScript58);
			txn.put("txid", fundingTxid58);
			txn.put("locktime", lockTime);
			txn.put("secret", ""); // Must be blank when refunding
			txn.put("privkey", privateKey58);

			String txnString = txn.toString();

			// Redeem the P2SH
			walletController.getCurrentWallet().clearTransactionHistoryCache();
			String response = LiteWalletJni.execute("redeemp2sh", txnString);
			JSONObject json = parseLitewalletResponse(response, "redeemp2sh");
			try {
				if (json.has("txid")) { // Success
					return json.getString("txid");
				} else if (json.has("error")) {
					String error = json.getString("error");
					throw new ForeignBlockchainException(error);
				}

			} catch (JSONException e) {
				throw new ForeignBlockchainException(e.getMessage());
			}

			throw new ForeignBlockchainException("Something went wrong");
		} finally {
			walletController.endWalletUse();
		}
	}

	private static JSONObject parseLitewalletResponse(String response, String command) throws ForeignBlockchainException {
		if (response == null || response.trim().isEmpty()) {
			throw new ForeignBlockchainException(
					String.format("LiteWalletJni %s returned empty response", command));
		}
			try {
				return new JSONObject(response);
			} catch (JSONException e) {
				String message = String.format("LiteWalletJni %s returned non-JSON (length %d)",
						command, response.trim().length());
				throw new ForeignBlockchainException(message);
		}
	}

	private static JSONArray parseLitewalletArray(String response, String command) throws ForeignBlockchainException {
		if (response == null || response.trim().isEmpty()) {
			throw new ForeignBlockchainException(
					String.format("LiteWalletJni %s returned empty response", command));
		}
			try {
				return new JSONArray(response);
			} catch (JSONException e) {
				String message = String.format("LiteWalletJni %s returned non-JSON (length %d)",
						command, response.trim().length());
				throw new ForeignBlockchainException(message);
		}
	}

	public String getSyncStatus(String entropy58) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.getSyncStatusWithInitTimeout(entropy58);
	}

	public String getSyncStatusJson(String entropy58) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.getSyncStatusJsonWithInitTimeout(entropy58);
	}

	public static BitcoinyTransaction deserializeRawTransaction(String rawTransactionHex) throws TransformationException {
		byte[] rawTransactionData = HashCode.fromString(rawTransactionHex).asBytes();
		ByteBuffer byteBuffer = ByteBuffer.wrap(rawTransactionData);

		// Header
		int header = BitTwiddling.readU32(byteBuffer);
		boolean overwintered = ((header >> 31 & 0xff) == 255);
		int version = header & 0x7FFFFFFF;

		// Version group ID
		int versionGroupId = 0;
		if (overwintered) {
			versionGroupId = BitTwiddling.readU32(byteBuffer);
		}

		boolean isOverwinterV3 = overwintered && versionGroupId == 0x03C48270 && version == 3;
		boolean isSaplingV4 = overwintered && versionGroupId == 0x892F2085 && version == 4;
		if (overwintered && !(isOverwinterV3 || isSaplingV4)) {
			throw new TransformationException("Unknown transaction format");
		}

		// Inputs
		List<BitcoinyTransaction.Input> inputs = new ArrayList<>();
		int vinCount = BitTwiddling.readU8(byteBuffer);
		for (int i = 0; i < vinCount; i++) {
			// Outpoint hash
			byte[] outpointHashBytes = new byte[32];
			byteBuffer.get(outpointHashBytes);
			String outpointHash = HashCode.fromBytes(outpointHashBytes).toString();

			// vout
			int vout = BitTwiddling.readU32(byteBuffer);

			// scriptSig
			int scriptSigLength = BitTwiddling.readU8(byteBuffer);
			byte[] scriptSigBytes = new byte[scriptSigLength];
			byteBuffer.get(scriptSigBytes);
			String scriptSig = HashCode.fromBytes(scriptSigBytes).toString();

			int sequence = BitTwiddling.readU32(byteBuffer);

			BitcoinyTransaction.Input input = new BitcoinyTransaction.Input(scriptSig, sequence, outpointHash, vout);
			inputs.add(input);
		}

		// Outputs
		List<BitcoinyTransaction.Output> outputs = new ArrayList<>();
		int voutCount = BitTwiddling.readU8(byteBuffer);
		for (int i = 0; i < voutCount; i++) {
			// Amount
			byte[] amountBytes = new byte[8];
			byteBuffer.get(amountBytes);
			long amount = BitTwiddling.longFromLEBytes(amountBytes, 0);

			// Script pubkey
			int scriptPubkeySize = BitTwiddling.readU8(byteBuffer);
			byte[] scriptPubkeyBytes = new byte[scriptPubkeySize];
			byteBuffer.get(scriptPubkeyBytes);
			String scriptPubKey = HashCode.fromBytes(scriptPubkeyBytes).toString();

			outputs.add(new BitcoinyTransaction.Output(scriptPubKey, amount, null));
		}

		// Locktime
		byte[] locktimeBytes = new byte[4];
		byteBuffer.get(locktimeBytes);
		int locktime = BitTwiddling.intFromLEBytes(locktimeBytes, 0);

		// Expiry height
		int expiryHeight = 0;
		if (isOverwinterV3 || isSaplingV4) {
			byte[] expiryHeightBytes = new byte[4];
			byteBuffer.get(expiryHeightBytes);
			expiryHeight = BitTwiddling.intFromLEBytes(expiryHeightBytes, 0);
		}

		String txHash = null; // Not present in raw transaction data
		int size = 0; // Not present in raw transaction data
		Integer timestamp = null; // Not present in raw transaction data

		// Note: this is incomplete, as sapling spend info is not yet parsed. We don't
		// need it for our
		// current trade bot implementation, but it could be added in the future, for
		// completeness.
		// See link below for reference:
		// https://github.com/PirateNetwork/librustzcash/blob/2981c4d2860f7cd73282fed885daac0323ff0280/zcash_primitives/src/transaction/mod.rs#L197

		return new BitcoinyTransaction(txHash, size, locktime, timestamp, inputs, outputs);
	}

}
