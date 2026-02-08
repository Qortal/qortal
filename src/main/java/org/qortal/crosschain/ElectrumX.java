package org.qortal.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
import org.qortal.utils.BitTwiddling;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** ElectrumX network support for querying Bitcoiny-related info like block headers, transaction outputs, etc. */
public class ElectrumX extends BitcoinyBlockchainProvider {

	public static final String NULL_RESPONSE_FROM_ELECTRUM_X_SERVER = "Null response from ElectrumX server";
	private static final Logger LOGGER = LogManager.getLogger(ElectrumX.class);
	private static final Random RANDOM = new Random();

	// See: https://electrumx.readthedocs.io/en/latest/protocol-changes.html
	private static final double MIN_PROTOCOL_VERSION = 1.2;
	private static final double MAX_PROTOCOL_VERSION = 2.0; // Higher than current latest, for hopeful future-proofing
	private static final int MIN_TARGET_CONNECTIONS = 2;
	private static final int DEFAULT_TARGET_CONNECTIONS = 3;
	private static final double TARGET_CONNECTIONS_FRACTION = 0.75d;
	private static final int PROBE_TIMEOUT_MS = 2000;
	private static final long PROBE_RETRY_MS = 5 * 60 * 1000L;
	private static final long FAILURE_PENALTY_MS = 5000L;

	private static final int BLOCK_HEADER_LENGTH = 80;

	// "message": "daemon error: DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})"
	private static final Pattern DAEMON_ERROR_REGEX = Pattern.compile("DaemonError\\(\\{.*'code': ?(-?[0-9]+).*\\}\\)\\z"); // Capture 'code' inside curly-brace content

	/** Error message sent by some ElectrumX servers when they don't support returning verbose transactions. */
	private static final String VERBOSE_TRANSACTIONS_UNSUPPORTED_MESSAGE = "verbose transactions are currently unsupported";

	private static final int RESPONSE_TIME_READINGS = 5;
	private static final long MAX_AVG_RESPONSE_TIME = 2000L; // ms
	private static final long UNKNOWN_RESPONSE_PENALTY_MS = MAX_AVG_RESPONSE_TIME * 5;
	public static final String MISSING_FEATURES_ERROR = "MISSING FEATURES ERROR";
	public static final String EXPECTED_GENESIS_ERROR = "EXPECTED GENESIS ERROR";
	private static final long IDLE_DISCONNECT_MS = 2 * 60 * 1000L;

	private ChainableServerConnectionRecorder recorder = new ChainableServerConnectionRecorder(100);

	// the minimum number of connections targeted for this foreign blockchain
	private int minimumConnections;
	private int maximumConnections;

	public static class Server implements ChainableServer {
		String hostname;

		ConnectionType connectionType;

		int port;
		private List<Long> responseTimes = new ArrayList<>();

		public Server(String hostname, ConnectionType connectionType, int port) {
			this.hostname = hostname;
			this.connectionType = connectionType;
			this.port = port;
		}

		@Override
		public void addResponseTime(long responseTime) {
			while (this.responseTimes.size() > RESPONSE_TIME_READINGS) {
				this.responseTimes.remove(0);
			}
			this.responseTimes.add(responseTime);
		}

		@Override
		public long averageResponseTime() {
			List<Long> snapshot;
			synchronized (this.responseTimes) {
				snapshot = new ArrayList<>(this.responseTimes);
			}
			if (snapshot.size() < RESPONSE_TIME_READINGS) {
				// Not enough readings yet
				return 0L;
			}
			OptionalDouble average = snapshot.stream().filter(Objects::nonNull).mapToDouble(a -> a).average();
			if (average.isPresent()) {
				return Double.valueOf(average.getAsDouble()).longValue();
			}
			return 0L;
		}

		@Override
		public String getHostName() {
			return this.hostname;
		}

		@Override
		public int getPort() {
			return this.port;
		}

		@Override
		public ConnectionType getConnectionType() {
			return this.connectionType;
		}

		@Override
		public boolean equals(Object other) {
			if (other == this)
				return true;

			if (!(other instanceof Server))
				return false;

			Server otherServer = (Server) other;

			return this.connectionType == otherServer.connectionType
					&& this.port == otherServer.port
					&& this.hostname.equals(otherServer.hostname);
		}

		@Override
		public int hashCode() {
			return this.hostname.hashCode() ^ this.port;
		}

		@Override
		public String toString() {
			return String.format("%s:%s:%d", this.connectionType.name(), this.hostname, this.port);
		}
	}
	private Set<ChainableServer> servers = Collections.synchronizedSet(new HashSet<>());
	private List<ChainableServer> remainingServers = new ArrayList<>(); // this is only accessed in the scheduling thread, so it is not thread safe
	private Set<ChainableServer> uselessServers = Collections.synchronizedSet(new HashSet<>());

	private Set<ElectrumServer> connections = Collections.synchronizedSet(new HashSet<>());
	private BlockingQueue<ElectrumServer> availableConnections = new LinkedBlockingQueue<>();

	private final String netId;
	private final String expectedGenesisHash;
	private final Map<Server.ConnectionType, Integer> defaultPorts = new EnumMap<>(Server.ConnectionType.class);
	private Bitcoiny blockchain;

	private static final int TX_CACHE_SIZE = 1000;

	private final Map<String, BitcoinyTransaction> transactionCache = Collections.synchronizedMap(new LinkedHashMap<>(TX_CACHE_SIZE + 1, 0.75F, true) {
		// This method is called just after a new entry has been added
		@Override
		public boolean removeEldestEntry(Map.Entry<String, BitcoinyTransaction> eldest) {
			return size() > TX_CACHE_SIZE;
		}
	});

	// Scheduled executor service to make connections
	private final ScheduledExecutorService scheduleMakeConnections = Executors.newScheduledThreadPool(1);

	// Scheduled executor service to recover connections
	private final ScheduledExecutorService scheduleRecoverConnections = Executors.newScheduledThreadPool(1);

	// Scheduled executor service to monitor connections
	private final ScheduledExecutorService scheduleMonitorConnections = Executors.newScheduledThreadPool(1);

	private final Object connectionManagementLock = new Object();
	private final Object connectionListLock = new Object();
	private volatile boolean connectionManagementStarted = false;
	private volatile long lastRpcTimeMs = 0L;
	private final AtomicInteger inFlightRpcCount = new AtomicInteger(0);
	private final Map<ChainableServer, Integer> serverFailureCounts = new ConcurrentHashMap<>();
	private final Map<ChainableServer, Long> serverLastProbeTime = new ConcurrentHashMap<>();
	private volatile boolean initialProbeCompleted = false;
	private volatile String lastScoreExtremesDigest = "";

	// Constructors

	public ElectrumX(String netId, String genesisHash, Collection<Server> initialServerList, Map<Server.ConnectionType, Integer> defaultPorts) {
		this.netId = netId;
		this.expectedGenesisHash = genesisHash;
		this.servers.addAll(initialServerList);
		this.defaultPorts.putAll(defaultPorts);

		updateConnectionTargets(initialServerList.size());
	}

	// Methods for use by other classes

	@Override
	public void setBlockchain(Bitcoiny blockchain) {
		this.blockchain = blockchain;
	}

	@Override
	public String getNetId() {
		return this.netId;
	}

	/**
	 * Returns current blockchain height.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public int getCurrentHeight() throws ForeignBlockchainException {
		Object blockObj = this.rpc("blockchain.headers.subscribe").getResponse();
		if (!(blockObj instanceof JSONObject))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from ElectrumX blockchain.headers.subscribe RPC");

		JSONObject blockJson = (JSONObject) blockObj;

		Object heightObj = blockJson.get("height");

		if (!(heightObj instanceof Long))
			throw new ForeignBlockchainException.NetworkException("Missing/invalid 'height' in JSON from ElectrumX blockchain.headers.subscribe RPC");

		return ((Long) heightObj).intValue();
	}

	/**
	 * Returns list of raw blocks, starting from <tt>startHeight</tt> inclusive.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public List<CompactBlock> getCompactBlocks(int startHeight, int count) throws ForeignBlockchainException {
		throw new ForeignBlockchainException("getCompactBlocks not implemented for ElectrumX due to being specific to zcash");
	}

	/**
	 * Returns list of raw block headers, starting from <tt>startHeight</tt> inclusive.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public List<byte[]> getRawBlockHeaders(int startHeight, int count) throws ForeignBlockchainException {
		Object blockObj = this.rpc("blockchain.block.headers", startHeight, count).getResponse();
		if (!(blockObj instanceof JSONObject))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from ElectrumX blockchain.block.headers RPC");

		JSONObject blockJson = (JSONObject) blockObj;

		Object countObj = blockJson.get("count");
		Object hexObj = blockJson.get("hex");

		if (!(countObj instanceof Long) || !(hexObj instanceof String))
			throw new ForeignBlockchainException.NetworkException("Missing/invalid 'count' or 'hex' entries in JSON from ElectrumX blockchain.block.headers RPC");

		long returnedCount = (Long) countObj;
		String hex = (String) hexObj;

		List<byte[]> rawBlockHeaders = new ArrayList<>((int) returnedCount);

		byte[] raw = HashCode.fromString(hex).asBytes();

		// Most chains use a fixed length 80 byte header, so block headers can be split up by dividing the hex into
		// 80-byte segments. However, some chains such as DOGE use variable length headers due to AuxPoW or other
		// reasons. In these cases we can identify the start of each block header by the location of the block version
		// numbers. Each block starts with a version number, and for DOGE this is easily identifiable (6422788) at the
		// time of writing (Jul 2021). If we encounter a chain that is using more generic version numbers (e.g. 1)
		// and can't be used to accurately identify block indexes, then there are sufficient checks to ensure an
		// exception is thrown.

		if (raw.length == returnedCount * BLOCK_HEADER_LENGTH) {
			// Fixed-length header (BTC, LTC, etc)
			for (int i = 0; i < returnedCount; ++i) {
				rawBlockHeaders.add(Arrays.copyOfRange(raw, i * BLOCK_HEADER_LENGTH, (i + 1) * BLOCK_HEADER_LENGTH));
			}
		}
		else if (raw.length > returnedCount * BLOCK_HEADER_LENGTH) {
			// Assume AuxPoW variable length header (DOGE)
			int referenceVersion = BitTwiddling.intFromLEBytes(raw, 0); // DOGE uses 6422788 at time of commit (Jul 2021)
			for (int i = 0; i < raw.length - 4; ++i) {
				// Locate the start of each block by its version number
				if (BitTwiddling.intFromLEBytes(raw, i) == referenceVersion) {
					rawBlockHeaders.add(Arrays.copyOfRange(raw, i, i + BLOCK_HEADER_LENGTH));
				}
			}
			// Ensure that we found the correct number of block headers
			if (rawBlockHeaders.size() != count) {
				throw new ForeignBlockchainException.NetworkException("Unexpected raw header contents in JSON from ElectrumX blockchain.block.headers RPC.");
			}
		}
		else if (raw.length != returnedCount * BLOCK_HEADER_LENGTH) {
			throw new ForeignBlockchainException.NetworkException("Unexpected raw header length in JSON from ElectrumX blockchain.block.headers RPC");
		}

		return rawBlockHeaders;
	}

	/**
	 * Returns list of raw block timestamps, starting from <tt>startHeight</tt> inclusive.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public List<Long> getBlockTimestamps(int startHeight, int count) throws ForeignBlockchainException {
		// FUTURE: implement this if needed. For now we use getRawBlockHeaders directly
		throw new ForeignBlockchainException("getBlockTimestamps not yet implemented for ElectrumX");
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if script unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	@Override
	public long getConfirmedBalance(byte[] script) throws ForeignBlockchainException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object balanceObj = this.rpc("blockchain.scripthash.get_balance", HashCode.fromBytes(scriptHash).toString()).getResponse();
		if (!(balanceObj instanceof JSONObject))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from ElectrumX blockchain.scripthash.get_balance RPC");

		JSONObject balanceJson = (JSONObject) balanceObj;

		Object confirmedBalanceObj = balanceJson.get("confirmed");

		if (!(confirmedBalanceObj instanceof Long))
			throw new ForeignBlockchainException.NetworkException("Missing confirmed balance from ElectrumX blockchain.scripthash.get_balance RPC");

		return (Long) balanceJson.get("confirmed");
	}

	/**
	 * Returns confirmed balance, based on passed base58 encoded address.
	 * <p>
	 * @return confirmed balance, or zero if address unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	@Override
	public long getConfirmedAddressBalance(String base58Address) throws ForeignBlockchainException {
		throw new ForeignBlockchainException("getConfirmedAddressBalance not yet implemented for ElectrumX");
	}

	/**
	 * Returns list of unspent outputs pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if address unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	@Override
	public List<UnspentOutput> getUnspentOutputs(String address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		byte[] script = this.blockchain.addressToScriptPubKey(address);
		return this.getUnspentOutputs(script, includeUnconfirmed);
	}

	/**
	 * Returns list of unspent outputs pertaining to passed payment script.
	 * <p>
	 * @return list of unspent outputs, or empty list if script unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	@Override
	public List<UnspentOutput> getUnspentOutputs(byte[] script, boolean includeUnconfirmed) throws ForeignBlockchainException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		Object unspentJson = this.rpc("blockchain.scripthash.listunspent", HashCode.fromBytes(scriptHash).toString()).getResponse();
		if (!(unspentJson instanceof JSONArray))
			throw new ForeignBlockchainException("Expected array output from ElectrumX blockchain.scripthash.listunspent RPC");

		List<UnspentOutput> unspentOutputs = new ArrayList<>();
		for (Object rawUnspent : (JSONArray) unspentJson) {
			JSONObject unspent = (JSONObject) rawUnspent;

			int height = ((Long) unspent.get("height")).intValue();
			// We only want unspent outputs from confirmed transactions (and definitely not mempool duplicates with height 0)
			if (!includeUnconfirmed && height <= 0)
				continue;

			byte[] txHash = HashCode.fromString((String) unspent.get("tx_hash")).asBytes();
			int outputIndex = ((Long) unspent.get("tx_pos")).intValue();
			long value = (Long) unspent.get("value");

			unspentOutputs.add(new UnspentOutput(txHash, outputIndex, height, value));
		}

		return unspentOutputs;
	}

	/**
	 * Returns raw transaction for passed transaction hash.
	 * <p>
	 * NOTE: Do not mutate returned byte[]!
	 * 
	 * @throws ForeignBlockchainException.NotFoundException if transaction not found
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public byte[] getRawTransaction(String txHash) throws ForeignBlockchainException {
		Object rawTransactionHex;
		try {
			rawTransactionHex = this.rpc("blockchain.transaction.get", txHash, false).getResponse();
		} catch (ForeignBlockchainException.NetworkException e) {
			// DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})
			if (Integer.valueOf(-5).equals(e.getDaemonErrorCode()))
				throw new ForeignBlockchainException.NotFoundException(e.getMessage());

			throw e;
		}

		if (!(rawTransactionHex instanceof String))
			throw new ForeignBlockchainException.NetworkException("Expected hex string as raw transaction from ElectrumX blockchain.transaction.get RPC");

		return HashCode.fromString((String) rawTransactionHex).asBytes();
	}

	/**
	 * Returns raw transaction for passed transaction hash.
	 * <p>
	 * NOTE: Do not mutate returned byte[]!
	 * 
	 * @throws ForeignBlockchainException.NotFoundException if transaction not found
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public byte[] getRawTransaction(byte[] txHash) throws ForeignBlockchainException {
		return getRawTransaction(HashCode.fromBytes(txHash).toString());
	}

	/**
	 * Returns transaction info for passed transaction hash.
	 * <p>
	 * @throws ForeignBlockchainException.NotFoundException if transaction not found
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
		// Check cache first
		BitcoinyTransaction transaction = transactionCache.get(txHash);
		if (transaction != null)
			return transaction;

		Object transactionObj = null;
		ElectrumServerResponse serverResponse = null;

		do {
			try {
				serverResponse = this.rpc("blockchain.transaction.get", txHash, true);
				transactionObj = serverResponse.getResponse();
			} catch (ForeignBlockchainException.NetworkException e) {
				// DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})
				if (Integer.valueOf(-5).equals(e.getDaemonErrorCode()))
					throw new ForeignBlockchainException.NotFoundException(e.getMessage());

				throw e;
			}
		} while (transactionObj == null);

		if (!(transactionObj instanceof JSONObject))
			throw new ForeignBlockchainException.NetworkException("Expected JSONObject as response from ElectrumX blockchain.transaction.get RPC");

		JSONObject transactionJson = (JSONObject) transactionObj;

		Object inputsObj = transactionJson.get("vin");
		if (!(inputsObj instanceof JSONArray))
			throw new ForeignBlockchainException.NetworkException("Expected JSONArray for 'vin' from ElectrumX blockchain.transaction.get RPC");

		Object outputsObj = transactionJson.get("vout");
		if (!(outputsObj instanceof JSONArray))
			throw new ForeignBlockchainException.NetworkException("Expected JSONArray for 'vout' from ElectrumX blockchain.transaction.get RPC");

		try {
			int size = ((Long) transactionJson.get("size")).intValue();
			int locktime = ((Long) transactionJson.get("locktime")).intValue();

			// Timestamp might not be present, e.g. for unconfirmed transaction
			Object timeObj = transactionJson.get("time");
			Integer timestamp = timeObj != null
					? ((Long) timeObj).intValue()
					: null;

			List<BitcoinyTransaction.Input> inputs = new ArrayList<>();
			for (Object inputObj : (JSONArray) inputsObj) {
				JSONObject inputJson = (JSONObject) inputObj;

				String scriptSig = (String) ((JSONObject) inputJson.get("scriptSig")).get("hex");
				int sequence = ((Long) inputJson.get("sequence")).intValue();
				String outputTxHash = (String) inputJson.get("txid");
				int outputVout = ((Long) inputJson.get("vout")).intValue();

				inputs.add(new BitcoinyTransaction.Input(scriptSig, sequence, outputTxHash, outputVout));
			}

			List<BitcoinyTransaction.Output> outputs = new ArrayList<>();
			for (Object outputObj : (JSONArray) outputsObj) {
				JSONObject outputJson = (JSONObject) outputObj;

				String scriptPubKey = (String) ((JSONObject) outputJson.get("scriptPubKey")).get("hex");
				long value = BigDecimal.valueOf((Double) outputJson.get("value")).setScale(8).unscaledValue().longValue();

				// address too, if present in the "addresses" array
				List<String> addresses = null;
				Object addressesObj = ((JSONObject) outputJson.get("scriptPubKey")).get("addresses");
				if (addressesObj instanceof JSONArray) {
					addresses = new ArrayList<>();
					for (Object addressObj : (JSONArray) addressesObj) {
						addresses.add((String) addressObj);
					}
				}

				// some peers return a single "address" string
				Object addressObj = ((JSONObject) outputJson.get("scriptPubKey")).get("address");
				if (addressObj instanceof String) {
					if (addresses == null) {
						addresses = new ArrayList<>();
					}
					addresses.add((String) addressObj);
				}

				// For the purposes of Qortal we require all outputs to contain addresses
				// Some servers omit this info, causing problems down the line with balance calculations
				// Update: it turns out that they were just using a different key - "address" instead of "addresses"
				// The code below can remain in place, just in case a peer returns a missing address in the future
				if (addresses == null || addresses.isEmpty()) {
					final String message = String.format("No output addresses returned for transaction %s", txHash);
					LOGGER.warn("{}: No output addresses returned for transaction {}", this.blockchain.getCurrencyCode(), txHash);

					uselessServers.add(serverResponse.getElectrumServer().getServer());
					throw new ForeignBlockchainException(message);
				}

				outputs.add(new BitcoinyTransaction.Output(scriptPubKey, value, addresses));
			}

			transaction = new BitcoinyTransaction(txHash, size, locktime, timestamp, inputs, outputs);

			// Save into cache, if and only if it has been confirmed
			if( transaction.timestamp != null ) {
				transactionCache.put(txHash, transaction);
			}

			return transaction;
		} catch (NullPointerException | ClassCastException e) {
			// Unexpected / invalid response from ElectrumX server
		}

		this.connections.remove(serverResponse.getElectrumServer());
		serverResponse.getElectrumServer().closeServer(this.getClass().getSimpleName(), "Unexpected JSON format from ElectrumX blockchain.transaction.get RPC");
		return getTransaction(txHash);
	}

	/**
	 * Returns list of transactions, relating to passed payment script.
	 * <p>
	 * @return list of related transactions, or empty list if script unknown
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public List<TransactionHash> getAddressTransactions(byte[] script, boolean includeUnconfirmed) throws ForeignBlockchainException {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);

		ElectrumServerResponse serverResponse = this.rpc("blockchain.scripthash.get_history", HashCode.fromBytes(scriptHash).toString());
		Object transactionsJson = serverResponse.getResponse();
		if (!(transactionsJson instanceof JSONArray))
			throw new ForeignBlockchainException.NetworkException("Expected array output from ElectrumX blockchain.scripthash.get_history RPC");

		List<TransactionHash> transactionHashes = new ArrayList<>();

		for (Object rawTransactionInfo : (JSONArray) transactionsJson) {
			JSONObject transactionInfo = (JSONObject) rawTransactionInfo;

			Long height = (Long) transactionInfo.get("height");
			if (!includeUnconfirmed && (height == null || height == 0))
				// We only want confirmed transactions
				continue;

			String txHash = (String) transactionInfo.get("tx_hash");

			transactionHashes.add(new TransactionHash(height.intValue(), txHash));
		}

		return transactionHashes;
	}

	@Override
	public List<BitcoinyTransaction> getAddressBitcoinyTransactions(String address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		// FUTURE: implement this if needed. For now we use getAddressTransactions() + getTransaction()
		throw new ForeignBlockchainException("getAddressBitcoinyTransactions not yet implemented for ElectrumX");
	}

	/**
	 * Broadcasts raw transaction to network.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public void broadcastTransaction(byte[] transactionBytes) throws ForeignBlockchainException {
		Object rawBroadcastResult = this.rpc("blockchain.transaction.broadcast", HashCode.fromBytes(transactionBytes).toString()).getResponse();

		// We're expecting a simple string that is the transaction hash
		if (!(rawBroadcastResult instanceof String))
			throw new ForeignBlockchainException.NetworkException("Unexpected response from ElectrumX blockchain.transaction.broadcast RPC");
	}

	// Class-private utility methods

	/**
	 * Query current server for its list of peer servers, and return those we can parse.
	 * <p>
	 * @throws ForeignBlockchainException
	 * @throws ClassCastException to be handled by caller
	 */
	private Set<Server> serverPeersSubscribe() {
		Set<Server> newServers = new HashSet<>();

		List<ElectrumServer> electrumServers = acquireServers();

		try {
			for( ElectrumServer electrumServer : electrumServers ) {
				Object peers = this.connectedRpc(electrumServer, "server.peers.subscribe");

				if( peers == null ) continue;

				Object peersObject = Objects.requireNonNull(peers);

				if( !(peersObject instanceof JSONArray) ) continue;

				for (Object rawPeer : (JSONArray) peersObject) {

					JSONArray peer = (JSONArray) rawPeer;
					if (peer.size() < 3)
						// We're expecting at least 3 fields for each peer entry: IP, hostname, features
						continue;

					String hostname = (String) peer.get(1);
					JSONArray features = (JSONArray) peer.get(2);

					for (Object rawFeature : features) {
						String feature = (String) rawFeature;
						Server.ConnectionType connectionType = null;
						Integer port = null;

						switch (feature.charAt(0)) {
							case 's':
								connectionType = Server.ConnectionType.SSL;
								port = this.defaultPorts.get(connectionType);
								break;

							case 't':
								connectionType = Server.ConnectionType.TCP;
								port = this.defaultPorts.get(connectionType);
								break;

							default:
								// e.g. could be 'v' for protocol version, or 'p' for pruning limit
								break;
						}

						if (connectionType == null || port == null)
							// We couldn't extract any peer aainfo?
							continue;

						// Possible non-default port?
						if (feature.length() > 1)
							try {
								port = Integer.parseInt(feature.substring(1));
							} catch (NumberFormatException e) {
								// no good
								continue; // for-loop above
							}

						Server newServer = new Server(hostname, connectionType, port);
						newServers.add(newServer);
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		} finally {
			for( ElectrumServer server : electrumServers ) {
				releaseServer(server);
			}
		}

		return newServers;
	}

	private ElectrumServer acquireServer() throws ForeignBlockchainException {

		try {
			return this.availableConnections.take();
		}
		catch( InterruptedException e ) {
			throw new ForeignBlockchainException(e.getMessage());
		}
	}

	private List<ElectrumServer> acquireServers() {

		List<ElectrumServer> servers = drainRandomly(this.availableConnections, this.availableConnections.size() / 2);

		LOGGER.info("{} draining count {}", this.blockchain.currencyCode, servers.size());
		return servers;
	}

	public static <T> List<T> drainRandomly(BlockingQueue<T> queue, int numToDrain) {
		List<T> drainedList = new ArrayList<>();
		Random random = new Random();
		int queueSize = queue.size();

		// Ensure we don't try to drain more objects than are in the queue
		numToDrain = Math.min(numToDrain, queueSize);

		// Create a list to hold the indices of objects to drain
		List<Integer> indicesToDrain = new ArrayList<>();
		while (indicesToDrain.size() < numToDrain) {
			int index = random.nextInt(queueSize);
			if (!indicesToDrain.contains(index)) {
				indicesToDrain.add(index);
			}
		}

		// Drain the selected objects from the queue
		for (int index : indicesToDrain) {
			for (int i = 0; i <= index; i++) {
				T obj;
				try {
					obj = queue.take();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("Interrupted while taking from queue", e);
				}
				if (i == index) {
					drainedList.add(obj);
				} else {
					queue.add(obj);
				}
			}
		}

		return drainedList;
	}

	private void releaseServer( ElectrumServer server ) {

		// if the connection is still open
		if( this.connections.contains(server))
			this.availableConnections.add(server);
	}

	private boolean isIdle() {
		if (this.inFlightRpcCount.get() > 0) {
			return false;
		}
		if (this.lastRpcTimeMs <= 0L) {
			return true;
		}
		return System.currentTimeMillis() - this.lastRpcTimeMs > IDLE_DISCONNECT_MS;
	}

	private void closeAllConnections(String reason) {
		synchronized (this.connectionListLock) {
			for (ElectrumServer server : new HashSet<>(this.connections)) {
				this.connections.remove(server);
				server.closeServer(this.getClass().getSimpleName(), reason);
			}
			this.availableConnections.clear();
			this.remainingServers.clear();
		}
	}

	private long averageConnectedResponseTime() {
		long total = 0L;
		int count = 0;
		synchronized (this.connections) {
			for (ElectrumServer server : this.connections) {
				long responseTime = server.averageResponseTime();
				if (responseTime > 0) {
					total += responseTime;
					count++;
				}
			}
		}
		return count == 0 ? 0L : total / count;
	}

	private void updateConnectionTargets(int listSize) {
		if (listSize <= 0) {
			this.maximumConnections = 0;
			this.minimumConnections = 0;
			LOGGER.info("{} has no ElectrumX servers configured", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode());
			return;
		}

		int targetConnections = (int) Math.ceil(listSize * TARGET_CONNECTIONS_FRACTION);
		if (listSize > 30) {
			targetConnections = 30;
		}
		targetConnections = Math.max(targetConnections, DEFAULT_TARGET_CONNECTIONS);
		int minTarget = Math.min(MIN_TARGET_CONNECTIONS, listSize);
		targetConnections = clamp(targetConnections, minTarget, listSize);
		this.maximumConnections = targetConnections;
		this.minimumConnections = Math.max(1, Math.min(listSize, Math.max(1, targetConnections / 2)));

		LOGGER.info("{} targets {} connections (min {}), listSize {}, avgResponse {}ms", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode(), this.maximumConnections, this.minimumConnections, listSize, averageConnectedResponseTime());
	}

	private long scoreServer(ChainableServer server) {
		long averageResponse = server.averageResponseTime();
		long latencyScore = averageResponse > 0 ? averageResponse : UNKNOWN_RESPONSE_PENALTY_MS;
		int failures = this.serverFailureCounts.getOrDefault(server, 0);
		return latencyScore + (failures * FAILURE_PENALTY_MS);
	}

	private List<ChainableServer> selectPreferredServers(int maxServers) {
		List<ChainableServer> snapshot;
		synchronized (this.connectionListLock) {
			snapshot = new ArrayList<>(this.servers);
		}
		if (snapshot.isEmpty() || maxServers <= 0) {
			return Collections.emptyList();
		}
		snapshot.sort(Comparator.comparingLong(this::scoreServer));
		logScoreExtremes(snapshot);
		int limit = Math.min(maxServers, snapshot.size());
		return new ArrayList<>(snapshot.subList(0, limit));
	}

	private void logScoreExtremes(List<ChainableServer> sortedServers) {
		int limit = Math.min(3, sortedServers.size());
		if (limit == 0) {
			return;
		}

		StringBuilder best = new StringBuilder();
		StringBuilder worst = new StringBuilder();
		for (int i = 0; i < limit; i++) {
			if (i > 0) {
				best.append(", ");
			}
			ChainableServer server = sortedServers.get(i);
			best.append(server).append(":").append(scoreServer(server)).append("ms");
		}
		for (int i = sortedServers.size() - limit; i < sortedServers.size(); i++) {
			if (i > sortedServers.size() - limit) {
				worst.append(", ");
			}
			ChainableServer server = sortedServers.get(i);
			worst.append(server).append(":").append(scoreServer(server)).append("ms");
		}

		String digest = best.toString() + "|" + worst.toString() + "|" + this.connections.size();
		if (digest.equals(this.lastScoreExtremesDigest)) {
			return;
		}
		this.lastScoreExtremesDigest = digest;

		LOGGER.info("{} top {} ElectrumX servers: {}", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode(), limit, best);
		LOGGER.info("{} bottom {} ElectrumX servers: {}", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode(), limit, worst);
	}

	private void recordFailure(ChainableServer server) {
		this.serverFailureCounts.merge(server, 1, Integer::sum);
	}

	private void recordSuccess(ChainableServer server) {
		this.serverFailureCounts.put(server, 0);
	}

	private void probeServers(Collection<ChainableServer> servers) {
		long now = System.currentTimeMillis();
		for (ChainableServer server : servers) {
			Long lastProbe = this.serverLastProbeTime.get(server);
			if (lastProbe != null && now - lastProbe < PROBE_RETRY_MS) {
				continue;
			}
			this.serverLastProbeTime.put(server, now);
			probeServer(server);
		}
	}

	private void probeServer(ChainableServer server) {
		ElectrumServer electrumServer = null;
		try {
			SocketAddress endpoint = new InetSocketAddress(server.getHostName(), server.getPort());
			electrumServer = ElectrumServer.createInstance(server, endpoint, PROBE_TIMEOUT_MS, this.recorder);
			electrumServer.setClientName(randomClientName());

			Object response = connectedRpc(electrumServer, "server.version");
			if (response != null) {
				recordSuccess(server);
			} else {
				recordFailure(server);
			}
		} catch (IOException | ForeignBlockchainException | ClassCastException | NullPointerException e) {
			recordFailure(server);
		} finally {
			if (electrumServer != null) {
				electrumServer.closeServer(this.getClass().getSimpleName(), "probe");
			}
		}
	}

	/**
	 * Ensure the connection maintenance threads are running and initial connections exist.
	 */
	private void ensureConnectionManagementStarted() {
		if (this.connectionManagementStarted) {
			return;
		}

		boolean shouldInit = false;
		synchronized (this.connectionManagementLock) {
			if (!this.connectionManagementStarted) {
				this.connectionManagementStarted = true;
				shouldInit = true;
			}
		}

		if (!shouldInit) {
			return;
		}

		if (!this.initialProbeCompleted) {
			List<ChainableServer> serversSnapshot;
			synchronized (this.connectionListLock) {
				serversSnapshot = new ArrayList<>(this.servers);
			}
			if (!serversSnapshot.isEmpty()) {
				LOGGER.info("{} probing {} ElectrumX servers for initial scoring", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode(), serversSnapshot.size());
				probeServers(serversSnapshot);
			}
			this.initialProbeCompleted = true;
		}

		startMakingConnections();

		scheduleMakeConnections.scheduleWithFixedDelay(this::makeConnections, 1, 3600, TimeUnit.SECONDS);
		scheduleRecoverConnections.scheduleWithFixedDelay(this::recoverConnections, 120, 10, TimeUnit.SECONDS);
		scheduleMonitorConnections.scheduleWithFixedDelay(this::monitorConnections, 1, 10, TimeUnit.MINUTES);
	}

	/**
	 * <p>Performs RPC call, with automatic reconnection to different server if needed.
	 * </p>
	 * @param method String representation of the RPC call value
	 * @param params a list of Objects passed to the method of the Remote Server
	 * @return "result" object from within JSON output
	 * @throws ForeignBlockchainException if server returns error or something goes wrong
	 */
	private ElectrumServerResponse rpc(String method, Object...params) throws ForeignBlockchainException {
		this.inFlightRpcCount.incrementAndGet();
		this.lastRpcTimeMs = System.currentTimeMillis();
		try {
			ensureConnectionManagementStarted();
			if (this.availableConnections.isEmpty()) {
				LOGGER.debug("{} no available ElectrumX connections; starting connections on demand", this.blockchain.getCurrencyCode());
				startMakingConnections();
			}

			ElectrumServer electrumServer = acquireServer();

			Object response = null;

			while(response == null) {

				response = connectedRpc(electrumServer, method, params);

				// If we have more servers and this one replied slowly, try another
				if (!this.availableConnections.isEmpty()) {
					long averageResponseTime = electrumServer.averageResponseTime();
					if (averageResponseTime > MAX_AVG_RESPONSE_TIME) {
						String message = String.format("Slow average response time %dms from %s - trying another server...", averageResponseTime, electrumServer.getServer());
						LOGGER.info(message);
						electrumServer.closeServer(this.getClass().getSimpleName(), message);
						break;
					}
				}

				if (response != null) {
					releaseServer(electrumServer);
					this.lastRpcTimeMs = System.currentTimeMillis();
					return new ElectrumServerResponse(electrumServer, response);
				}

				LOGGER.debug(NULL_RESPONSE_FROM_ELECTRUM_X_SERVER);

				// Didn't work, try another server...
				this.connections.remove(electrumServer);
				electrumServer.closeServer(this.getClass().getSimpleName(), NULL_RESPONSE_FROM_ELECTRUM_X_SERVER);
				electrumServer = acquireServer();
			}

			// Failed to perform RPC - maybe lack of servers?
			LOGGER.info("Error: No connected Electrum servers when trying to make RPC call");
			throw new ForeignBlockchainException.NetworkException(String.format("Failed to perform ElectrumX RPC %s", method));
		} finally {
			this.inFlightRpcCount.decrementAndGet();
		}
	}

	/**
	 * Monitor Connections
	 *
	 * Log the server connection status for this foreign blockchain.
	 */
	private void monitorConnections() {

		if (this.isIdle() && !this.connections.isEmpty()) {
			LOGGER.info("{} idle; closing {} ElectrumX connections", this.blockchain.getCurrencyCode(), this.connections.size());
			this.closeAllConnections("idle timeout");
		}

		LOGGER.info(
			"{} {} available connections, {} total servers, {} total connections (target {}), {} useless servers",
			this.blockchain.getCurrencyCode(),
			this.availableConnections.size(),
			this.servers.size(),
			this.connections.size(),
			this.maximumConnections,
			this.uselessServers.size()
		);
	}

	/**
	 * Make connections
	 *
	 * Connect to many servers on this foreign blockchain.
	 */
	private void makeConnections() {

		try {
			if (this.isIdle()) {
				return;
			}
			if( this.connections.isEmpty() ) {
				startMakingConnections();
			}

			makeMoreConnections();
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	/**
	 * Recover Connections
	 *
	 * If connection count is below the minimum, then recover connections from the initial list.
	 */
	private void recoverConnections() {

		try {
			if (this.isIdle()) {
				return;
			}
			if( this.connections.size() < this.minimumConnections ) {
				LOGGER.debug("{} recovering connections", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode());
				List<ChainableServer> serversSnapshot;
				synchronized (this.connectionListLock) {
					serversSnapshot = new ArrayList<>(this.servers);
				}
				probeServers(serversSnapshot);
				startMakingConnections();
				LOGGER.debug("{} recovered {} connections", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode(), this.connections.size());
			}
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	/**
	 * Start Making Connections
	 */
	private void startMakingConnections() {

		// assume there are no server to get peers from, so we must start from the base list
		synchronized (this.connectionListLock) {
			this.remainingServers.clear();
			updateConnectionTargets(this.servers.size());
			List<ChainableServer> preferredServers = selectPreferredServers(this.maximumConnections);
			LOGGER.info("{} selecting {} of {} ElectrumX servers by score", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode(), preferredServers.size(), this.servers.size());
			this.remainingServers.addAll(preferredServers);
		}

		connectRemainingServers();
	}

	/**
	 * Make More Connections
	 *
	 * Assuming initial connections have been made, continue to make more.
	 */
	private void makeMoreConnections() {

		// if we need more connections
		if(this.connections.size() < this.maximumConnections) {

			// Ask for more servers
			Set<Server> moreServers = serverPeersSubscribe();
			List<ChainableServer> newlyAdded = new ArrayList<>();

			synchronized (this.connectionListLock) {
				for (Server server : moreServers) {
					if (!this.servers.contains(server)) {
						newlyAdded.add(server);
					}
				}
				this.servers.addAll(moreServers);
			}

			if (!newlyAdded.isEmpty()) {
				LOGGER.info("{} probing {} newly discovered ElectrumX servers", this.blockchain == null ? "ElectrumX" : this.blockchain.getCurrencyCode(), newlyAdded.size());
				probeServers(newlyAdded);
			}

			synchronized (this.connectionListLock) {
				updateConnectionTargets(this.servers.size());
				List<ChainableServer> preferredServers = selectPreferredServers(this.maximumConnections);
				this.remainingServers.clear();
				this.remainingServers.addAll(preferredServers);
				this.remainingServers.removeAll(this.connections.stream().map(ElectrumServer::getServer).collect(Collectors.toList()));
			}

			// try connecting the remaining servers
			connectRemainingServers();
		}
	}

	private void connectRemainingServers() {
		// while there are remaining servers and less than the maximum connections
		while (true) {
			ChainableServer server;
			synchronized (this.connectionListLock) {
				if (this.remainingServers.isEmpty() || this.connections.size() >= this.maximumConnections) {
					return;
				}
				server = this.remainingServers.remove(RANDOM.nextInt(this.remainingServers.size()));
			}

			makeConnection(server, this.getClass().getSimpleName());
		}
	}

	private static int clamp(int value, int min, int max) {
		if (value < min) {
			return min;
		}
		if (value > max) {
			return max;
		}
		return value;
	}

	private static String randomClientName() {
		final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
		StringBuilder name = new StringBuilder(12);
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < 12; i++) {
			name.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return name.toString();
	}

	private Optional<ChainableServerConnection> makeConnection(ChainableServer server, String requestedBy) {
		LOGGER.debug(() -> String.format("Connecting to %s %s", server, this.blockchain.currencyCode));

		try {
			SocketAddress endpoint = new InetSocketAddress(server.getHostName(), server.getPort());
			int timeout = 5000; // ms

			ElectrumServer electrumServer = ElectrumServer.createInstance(server, endpoint, timeout, this.recorder);
			electrumServer.setClientName(randomClientName());

			// All connections need to start with a version negotiation
			this.connectedRpc(electrumServer, "server.version");

			// Check connection is suitable by asking for server features, including genesis block hash
			JSONObject featuresJson = (JSONObject) this.connectedRpc(electrumServer, "server.features");

			if (featuresJson == null ) {
				recordFailure(server);
				return Optional.of( recorder.recordConnection(server, requestedBy, true,  false, MISSING_FEATURES_ERROR) );
			}

			try {
				double protocol_min = CrossChainUtils.getVersionDecimal(featuresJson, "protocol_min");

				if (protocol_min < MIN_PROTOCOL_VERSION) {
					recordFailure(server);
					return Optional.of( recorder.recordConnection(server, requestedBy, true,  false, "old version: protocol_min = " + protocol_min + " < MIN_PROTOCOL_VERSION = " + MIN_PROTOCOL_VERSION) );
				}
			} catch (NumberFormatException e) {
				recordFailure(server);
				return Optional.of( recorder.recordConnection(server, requestedBy,true, false,featuresJson.get("protocol_min").toString() + " is not a valid version"));
			} catch (NullPointerException e) {
				recordFailure(server);
				return Optional.of( recorder.recordConnection(server, requestedBy,true, false,"server version not available: protocol_min"));
			}

			if (this.expectedGenesisHash != null && !((String) featuresJson.get("genesis_hash")).equals(this.expectedGenesisHash)) {
				recordFailure(server);
				return Optional.of( recorder.recordConnection(server, requestedBy, true, false, EXPECTED_GENESIS_ERROR) );
			}

			recordSuccess(server);
			LOGGER.debug(() -> String.format("Connected to %s %s", server, this.blockchain.currencyCode));
			this.connections.add(electrumServer);
			this.availableConnections.add(electrumServer);
			return Optional.of( this.recorder.recordConnection( server, requestedBy, true, true, EMPTY) );
		} catch (IOException | ForeignBlockchainException | ClassCastException | NullPointerException e) {
			// Didn't work, try another server...
			recordFailure(server);
			return Optional.of( this.recorder.recordConnection( server, requestedBy, true, false, CrossChainUtils.getNotes(e)));
		} catch( Exception e ) {
			LOGGER.error(e.getMessage(), e);
			return Optional.empty();
		}
	}

	/**
	 * Perform RPC using currently connected server.
	 * <p>
	 * @param method
	 * @param params
	 * @return response Object, or null if server fails to respond
	 * @throws ForeignBlockchainException if server returns error
	 */
	@SuppressWarnings("unchecked")
	private Object connectedRpc(ElectrumServer server, String method, Object...params) throws ForeignBlockchainException {
		JSONObject requestJson = new JSONObject();
		String id = UUID.randomUUID().toString();
		requestJson.put("id", id);
		requestJson.put("method", method);
		requestJson.put("jsonrpc", "2.0");

		JSONArray requestParams = new JSONArray();
		requestParams.addAll(Arrays.asList(params));

		// server.version needs additional params to negotiate a version
		if (method.equals("server.version")) {
			String clientName = server.getClientName();
			if (clientName == null) {
				clientName = randomClientName();
				server.setClientName(clientName);
			}
			requestParams.add(clientName);
			List<String> versions = new ArrayList<>();
			DecimalFormat df = new DecimalFormat("#.#");
			versions.add(df.format(MIN_PROTOCOL_VERSION));
			versions.add(df.format(MAX_PROTOCOL_VERSION));
			requestParams.add(versions);
		}

		requestJson.put("params", requestParams);

		String request = requestJson.toJSONString() + "\n";
		LOGGER.trace(() -> String.format("Request: %s", request));

		long startTime = System.currentTimeMillis();
		final String response;

		try {
			response = server.write(request.getBytes(), id);
		} catch (IOException | NoSuchElementException e) {
			// Unable to send, or receive -- try another server?
			return null;
		} catch (NoSuchMethodError e) {
			// Likely an SSL dependency issue - retries are unlikely to succeed
			LOGGER.error("ElectrumX output stream error", e);
			return null;
		}

		long endTime = System.currentTimeMillis();
		long responseTime = endTime-startTime;

		LOGGER.trace(() -> String.format("Request: %s Response: %s", request, response));
		LOGGER.trace(() -> String.format("Time taken: %dms", endTime-startTime));

		if (response.isEmpty())
			// Empty response - try another server?
			return null;

		Object responseObj = JSONValue.parse(response);
		if (!(responseObj instanceof JSONObject))
			// Unexpected response - try another server?
			return null;

		server.addResponseTime(responseTime);

		JSONObject responseJson = (JSONObject) responseObj;

		Object errorObj = responseJson.get("error");
		if (errorObj != null) {
			if (errorObj instanceof String) {
				LOGGER.debug(String.format("Unexpected error message from ElectrumX server %s for RPC method %s: %s", server.getServer(), method, (String) errorObj));
				// Try another server
				return null;
			}

			if (!(errorObj instanceof JSONObject)) {
				LOGGER.debug(String.format("Unexpected error response from ElectrumX server %s for RPC method %s", server.getServer(), method));
				// Try another server
				return null;
			}

			JSONObject errorJson = (JSONObject) errorObj;

			Object messageObj = errorJson.get("message");

			if (!(messageObj instanceof String)) {
				LOGGER.debug(String.format("Missing/invalid message in error response from ElectrumX server %s for RPC method %s", server.getServer(), method));
				// Try another server
				return null;
			}

			String message = (String) messageObj;

			// Some error 'messages' are actually wrapped upstream bitcoind errors:
			// "message": "daemon error: DaemonError({'code': -5, 'message': 'No such mempool or blockchain transaction. Use gettransaction for wallet transactions.'})"
			// We want to detect these and extract the upstream error code for caller's use
			Matcher messageMatcher = DAEMON_ERROR_REGEX.matcher(message);
			if (messageMatcher.find())
				try {
					int daemonErrorCode = Integer.parseInt(messageMatcher.group(1));
					throw new ForeignBlockchainException.NetworkException(daemonErrorCode, message, server.getServer());
				} catch (NumberFormatException e) {
					// We couldn't parse the error code integer? Fall-through to generic exception...
				}

			throw new ForeignBlockchainException.NetworkException(message, server.getServer());
		}

		return responseJson.get("result");
	}

	@Override
	public Set<ChainableServer> getServers() {
		return new HashSet<>(this.servers );
	}

	@Override
	public Set<ChainableServer> getUselessServers() {
		return new HashSet<>(this.uselessServers);
	}

	@Override
	public ChainableServer getCurrentServer() {
		return null;
	}

	@Override
	public boolean addServer(ChainableServer server) {
		return this.servers.add(server);
	}

	@Override
	public boolean removeServer(ChainableServer server) {
		boolean removedRemaining = this.servers.remove(server);

		return removedRemaining;
	}

	@Override
	public Optional<ChainableServerConnection> setCurrentServer(ChainableServer server, String requestedBy) {
		// this class makes its own connections
		return Optional.empty();
	}

	@Override
	public List<ChainableServerConnection> getServerConnections() {
		return this.recorder.getConnections();
	}

	@Override
	public ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port) {
		return new ElectrumX.Server(hostName, type, port);
	}
}
