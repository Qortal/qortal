package org.qortal.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import cash.z.wallet.sdk.rpc.CompactTxStreamerGrpc;
import cash.z.wallet.sdk.rpc.Service;
import cash.z.wallet.sdk.rpc.Service.*;
import com.google.common.hash.HashCode;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.settings.Settings;
import org.qortal.transform.TransformationException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Pirate Chain network support for querying Bitcoiny-related info like block headers, transaction outputs, etc. */
public class PirateLightClient extends BitcoinyBlockchainProvider {

	private static final Logger LOGGER = LogManager.getLogger(PirateLightClient.class);
	private static final Random RANDOM = new Random();

	private static final int RESPONSE_TIME_READINGS = 5;
	private static final long MAX_AVG_RESPONSE_TIME = 500L; // ms

	public static class Server implements ChainableServer{
		String hostname;

		ConnectionType connectionType;

		int port;
		private List<Long> responseTimes = new ArrayList<>();

		public Server(String hostname, ConnectionType connectionType, int port) {
			this.hostname = hostname;
			this.connectionType = connectionType;
			this.port = port;
		}

		public void addResponseTime(long responseTime) {
			while (this.responseTimes.size() > RESPONSE_TIME_READINGS) {
				this.responseTimes.remove(0);
			}
			this.responseTimes.add(responseTime);
		}

		public long averageResponseTime() {
			if (this.responseTimes.size() < RESPONSE_TIME_READINGS) {
				// Not enough readings yet
				return 0L;
			}
			OptionalDouble average = this.responseTimes.stream().mapToDouble(a -> a).average();
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
		public ChainableServer.ConnectionType getConnectionType() {
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
	private Set<ChainableServer> servers = new HashSet<>();
	private List<ChainableServer> remainingServers = new ArrayList<>();
	private Set<ChainableServer> uselessServers = Collections.synchronizedSet(new HashSet<>());

	private final String netId;
	private final String expectedGenesisHash;
	private final Map<Server.ConnectionType, Integer> defaultPorts = new EnumMap<>(Server.ConnectionType.class);
	private Bitcoiny blockchain;

	private final Object serverLock = new Object();
	private ChainableServer currentServer;
	private ManagedChannel channel;
	private int nextId = 1;

	private static final int TX_CACHE_SIZE = 1000;
	@SuppressWarnings("serial")
	private final Map<String, BitcoinyTransaction> transactionCache = Collections.synchronizedMap(new LinkedHashMap<>(TX_CACHE_SIZE + 1, 0.75F, true) {
		// This method is called just after a new entry has been added
		@Override
		public boolean removeEldestEntry(Map.Entry<String, BitcoinyTransaction> eldest) {
			return size() > TX_CACHE_SIZE;
		}
	});

	private ChainableServerConnectionRecorder recorder = new ChainableServerConnectionRecorder(100);

	// Constructors

	public PirateLightClient(String netId, String genesisHash, Collection<Server> initialServerList, Map<Server.ConnectionType, Integer> defaultPorts) {
		this.netId = netId;
		this.expectedGenesisHash = genesisHash;
		this.servers.addAll(initialServerList);
		this.defaultPorts.putAll(defaultPorts);
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
		BlockID latestBlock = this.getCompactTxStreamerStub().getLatestBlock(null);

		if (!(latestBlock instanceof BlockID))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain getLatestBlock gRPC");

		return (int)latestBlock.getHeight();
	}

	/**
	 * Returns list of compact blocks, starting from <tt>startHeight</tt> inclusive.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 * @return
	 */
	@Override
	public List<CompactBlock> getCompactBlocks(int startHeight, int count) throws ForeignBlockchainException {
		BlockID startBlock = BlockID.newBuilder().setHeight(startHeight).build();
		BlockID endBlock = BlockID.newBuilder().setHeight(startHeight + count - 1).build();
		BlockRange range = BlockRange.newBuilder().setStart(startBlock).setEnd(endBlock).build();

		Iterator<CompactBlock> blocksIterator = this.getCompactTxStreamerStub().getBlockRange(range);

		// Map from Iterator to List
		List<CompactBlock> blocks = new ArrayList<>();
		blocksIterator.forEachRemaining(blocks::add);

		return blocks;
	}

	/**
	 * Returns list of raw block headers, starting from <tt>startHeight</tt> inclusive.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public List<byte[]> getRawBlockHeaders(int startHeight, int count) throws ForeignBlockchainException {
		BlockID startBlock = BlockID.newBuilder().setHeight(startHeight).build();
		BlockID endBlock = BlockID.newBuilder().setHeight(startHeight + count - 1).build();
		BlockRange range = BlockRange.newBuilder().setStart(startBlock).setEnd(endBlock).build();

		Iterator<CompactBlock> blocks = this.getCompactTxStreamerStub().getBlockRange(range);

		List<byte[]> rawBlockHeaders = new ArrayList<>();

		while (blocks.hasNext()) {
			CompactBlock block = blocks.next();

			if (block.getHeader() == null) {
				throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain getBlockRange gRPC");
			}

			rawBlockHeaders.add(block.getHeader().toByteArray());
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
		BlockID startBlock = BlockID.newBuilder().setHeight(startHeight).build();
		BlockID endBlock = BlockID.newBuilder().setHeight(startHeight + count - 1).build();
		BlockRange range = BlockRange.newBuilder().setStart(startBlock).setEnd(endBlock).build();

		Iterator<CompactBlock> blocks = this.getCompactTxStreamerStub().getBlockRange(range);

		List<Long> rawBlockTimestamps = new ArrayList<>();

		while (blocks.hasNext()) {
			CompactBlock block = blocks.next();

			if (block.getTime() <= 0) {
				throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain getBlockRange gRPC");
			}

			rawBlockTimestamps.add(Long.valueOf(block.getTime()));
		}

		return rawBlockTimestamps;
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if script unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	@Override
	public long getConfirmedBalance(byte[] script) throws ForeignBlockchainException {
		throw new ForeignBlockchainException("getConfirmedBalance not yet implemented for Pirate Chain");
	}

	/**
	 * Returns confirmed balance, based on passed base58 encoded address.
	 * <p>
	 * @return confirmed balance, or zero if address unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	@Override
	public long getConfirmedAddressBalance(String base58Address) throws ForeignBlockchainException {
		AddressList addressList = AddressList.newBuilder().addAddresses(base58Address).build();
		Balance balance = this.getCompactTxStreamerStub().getTaddressBalance(addressList);

		if (!(balance instanceof Balance))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain getConfirmedAddressBalance gRPC");

		return balance.getValueZat();
	}

	/**
	 * Returns list of unspent outputs pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if address unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	@Override
	public List<UnspentOutput> getUnspentOutputs(String address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		GetAddressUtxosArg getAddressUtxosArg = GetAddressUtxosArg.newBuilder().addAddresses(address).build();
		GetAddressUtxosReplyList replyList = this.getCompactTxStreamerStub().getAddressUtxos(getAddressUtxosArg);

		if (!(replyList instanceof GetAddressUtxosReplyList))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain getUnspentOutputs gRPC");

		List<GetAddressUtxosReply> unspentList = replyList.getAddressUtxosList();
		if (unspentList == null)
			throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain getUnspentOutputs gRPC");

		List<UnspentOutput> unspentOutputs = new ArrayList<>();
		for (GetAddressUtxosReply unspent : unspentList) {

			int height = (int)unspent.getHeight();
			// We only want unspent outputs from confirmed transactions (and definitely not mempool duplicates with height 0)
			if (!includeUnconfirmed && height <= 0)
				continue;

			byte[] txHash = unspent.getTxid().toByteArray();
			int outputIndex = unspent.getIndex();
			long value = unspent.getValueZat();
			byte[] script = unspent.getScript().toByteArray();
			String addressRes = unspent.getAddress();

			unspentOutputs.add(new UnspentOutput(txHash, outputIndex, height, value, script, addressRes));
		}

		return unspentOutputs;
	}

	/**
	 * Returns list of unspent outputs pertaining to passed payment script.
	 * <p>
	 * @return list of unspent outputs, or empty list if script unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	@Override
	public List<UnspentOutput> getUnspentOutputs(byte[] script, boolean includeUnconfirmed) throws ForeignBlockchainException {
		String address = this.blockchain.deriveP2shAddress(script);
		return this.getUnspentOutputs(address, includeUnconfirmed);
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
		return getRawTransaction(HashCode.fromString(txHash).asBytes());
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
		ByteString byteString = ByteString.copyFrom(txHash);
		TxFilter txFilter = TxFilter.newBuilder().setHash(byteString).build();
		RawTransaction rawTransaction = this.getCompactTxStreamerStub().getTransaction(txFilter);

		if (!(rawTransaction instanceof RawTransaction))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain getTransaction gRPC");

		return rawTransaction.getData().toByteArray();
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

		ByteString byteString = ByteString.copyFrom(HashCode.fromString(txHash).asBytes());
		TxFilter txFilter = TxFilter.newBuilder().setHash(byteString).build();
		RawTransaction rawTransaction = this.getCompactTxStreamerStub().getTransaction(txFilter);

		if (!(rawTransaction instanceof RawTransaction))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain getTransaction gRPC");

		byte[] transactionData = rawTransaction.getData().toByteArray();
		String transactionDataString = HashCode.fromBytes(transactionData).toString();

		JSONParser parser = new JSONParser();
		JSONObject transactionJson;
		try {
			transactionJson = (JSONObject) parser.parse(transactionDataString);
		} catch (ParseException e) {
			throw new ForeignBlockchainException.NetworkException("Expected JSON string from Pirate Chain getTransaction gRPC");
		}

		Object inputsObj = transactionJson.get("vin");
		if (!(inputsObj instanceof JSONArray))
			throw new ForeignBlockchainException.NetworkException("Expected JSONArray for 'vin' from Pirate Chain getTransaction gRPC");

		Object outputsObj = transactionJson.get("vout");
		if (!(outputsObj instanceof JSONArray))
			throw new ForeignBlockchainException.NetworkException("Expected JSONArray for 'vout' from Pirate Chain getTransaction gRPC");

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
					if (this.currentServer != null) {
						this.uselessServers.add(this.currentServer);
						this.closeServer(this.currentServer, message, this.getClass().getSimpleName());
					}
					LOGGER.info(message);
					throw new ForeignBlockchainException(message);
				}

				outputs.add(new BitcoinyTransaction.Output(scriptPubKey, value, addresses));
			}

			transaction = new BitcoinyTransaction(txHash, size, locktime, timestamp, inputs, outputs);

			// Save into cache
			transactionCache.put(txHash, transaction);

			return transaction;
		} catch (NullPointerException | ClassCastException e) {
			// Unexpected / invalid response from ElectrumX server
		}

		throw new ForeignBlockchainException.NetworkException("Unexpected JSON format from Pirate Chain getTransaction gRPC");
	}

	/**
	 * Returns list of transactions, relating to passed payment script.
	 * <p>
	 * @return list of related transactions, or empty list if script unknown
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public List<TransactionHash> getAddressTransactions(byte[] script, boolean includeUnconfirmed) throws ForeignBlockchainException {
		// FUTURE: implement this if needed. Probably not very useful for private blockchains.
		throw new ForeignBlockchainException("getAddressTransactions not yet implemented for Pirate Chain");
	}

	@Override
	public List<BitcoinyTransaction> getAddressBitcoinyTransactions(String address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		try {
			// Firstly we need to get the latest block
			int defaultBirthday = Settings.getInstance().getArrrDefaultBirthday();
			BlockID endBlock = this.getCompactTxStreamerStub().getLatestBlock(null);
			BlockID startBlock = BlockID.newBuilder().setHeight(defaultBirthday).build();
			BlockRange blockRange = BlockRange.newBuilder().setStart(startBlock).setEnd(endBlock).build();

			TransparentAddressBlockFilter blockFilter = TransparentAddressBlockFilter.newBuilder()
					.setAddress(address)
					.setRange(blockRange)
					.build();
			Iterator<Service.RawTransaction> transactionIterator = this.getCompactTxStreamerStub().getTaddressTxids(blockFilter);

			// Map from Iterator to List
			List<RawTransaction> rawTransactions = new ArrayList<>();
			transactionIterator.forEachRemaining(rawTransactions::add);

			List<BitcoinyTransaction> transactions = new ArrayList<>();

			for (RawTransaction rawTransaction : rawTransactions) {

				Long height = rawTransaction.getHeight();
				if (!includeUnconfirmed && (height == null || height == 0))
					// We only want confirmed transactions
					continue;

				byte[] transactionData = rawTransaction.getData().toByteArray();
				String transactionDataHex = HashCode.fromBytes(transactionData).toString();
				BitcoinyTransaction bitcoinyTransaction = PirateChain.deserializeRawTransaction(transactionDataHex);
				bitcoinyTransaction.height = height.intValue();
				transactions.add(bitcoinyTransaction);
			}

			return transactions;
		}
		catch (RuntimeException | TransformationException e) {
			throw new ForeignBlockchainException(String.format("Unable to get transactions for address %s: %s", address, e.getMessage()));
		}
	}

	/**
	 * Broadcasts raw transaction to network.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public void broadcastTransaction(byte[] transactionBytes) throws ForeignBlockchainException {
		ByteString byteString = ByteString.copyFrom(transactionBytes);
		RawTransaction rawTransaction = RawTransaction.newBuilder().setData(byteString).build();
		SendResponse sendResponse = this.getCompactTxStreamerStub().sendTransaction(rawTransaction);

		if (!(sendResponse instanceof SendResponse))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from Pirate Chain broadcastTransaction gRPC");

		if (sendResponse.getErrorCode() != 0)
			throw new ForeignBlockchainException.NetworkException(String.format("Unexpected error code from Pirate Chain broadcastTransaction gRPC: %d", sendResponse.getErrorCode()));
	}

	@Override
	public Set<ChainableServer> getServers() {
		return this.servers;
	}

	@Override
	public Set<ChainableServer> getUselessServers() {
		return this.uselessServers;
	}

	@Override
	public ChainableServer getCurrentServer() { return this.currentServer; }

	@Override
	public boolean addServer(ChainableServer server) {
		return this.servers.add(server);
	}

	@Override
	public boolean removeServer(ChainableServer server) {
		boolean removedServer = this.servers.remove(server);
		boolean removedRemaining = this.remainingServers.remove(server);

		return removedServer || removedRemaining;
	}

	@Override
	public Optional<ChainableServerConnection> setCurrentServer(ChainableServer server, String requestedBy) throws ForeignBlockchainException {

		closeServer( requestedBy, "Connecting to different server by request." );
		Optional<ChainableServerConnection> connection = makeConnection(server, requestedBy);

		if( !connection.isPresent() || !connection.get().isSuccess() ) {
			haveConnection();
		}

		return connection;
	}

	@Override
	public List<ChainableServerConnection> getServerConnections() {
		return this.recorder.getConnections();
	}

	@Override
	public ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port) {
		return new PirateLightClient.Server(hostName, type, port);
	}

	// Class-private utility methods


	/**
	 * Performs RPC call, with automatic reconnection to different server if needed.
	 * <p>
	 * @return "result" object from within JSON output
	 * @throws ForeignBlockchainException if server returns error or something goes wrong
	 */
	private CompactTxStreamerGrpc.CompactTxStreamerBlockingStub getCompactTxStreamerStub() throws ForeignBlockchainException {
		synchronized (this.serverLock) {
			if (this.remainingServers.isEmpty())
				this.remainingServers.addAll(this.servers);

			while (haveConnection()) {
				// If we have more servers and the last one replied slowly, try another
				if (!this.remainingServers.isEmpty()) {
					long averageResponseTime = this.currentServer.averageResponseTime();
					if (averageResponseTime > MAX_AVG_RESPONSE_TIME) {
						String message = String.format("Slow average response time %dms from %s - trying another server...", averageResponseTime, this.currentServer.getHostName());
						LOGGER.info(message);
						this.closeServer(this.getClass().getSimpleName(), message);
						continue;
					}
				}

				return CompactTxStreamerGrpc.newBlockingStub(this.channel);

//				// Didn't work, try another server...
//				this.closeServer();
			}

			// Failed to perform RPC - maybe lack of servers?
			LOGGER.info("Error: No connected Pirate Light servers when trying to make RPC call");
			throw new ForeignBlockchainException.NetworkException("No connected Pirate Light servers when trying to make RPC call");
		}
	}

	/** Returns true if we have, or create, a connection to an ElectrumX server. */
	private boolean haveConnection() throws ForeignBlockchainException {
		if (this.currentServer != null && this.channel != null && !this.channel.isShutdown())
			return true;

		while (!this.remainingServers.isEmpty()) {
			ChainableServer server = this.remainingServers.remove(RANDOM.nextInt(this.remainingServers.size()));

			Optional<ChainableServerConnection> chainableServerConnection = makeConnection(server, this.getClass().getSimpleName());
			if( chainableServerConnection.isPresent() && chainableServerConnection.get().isSuccess() ) return true;
		}

		return false;
	}

	private Optional<ChainableServerConnection> makeConnection(ChainableServer server, String requestedBy) {
		LOGGER.info(() -> String.format("Connecting to %s", server));

		try {
			this.channel = ManagedChannelBuilder.forAddress(server.getHostName(), server.getPort()).build();

			CompactTxStreamerGrpc.CompactTxStreamerBlockingStub stub = CompactTxStreamerGrpc.newBlockingStub(this.channel);
			LightdInfo lightdInfo = stub.getLightdInfo(Empty.newBuilder().build());

			if (lightdInfo == null || lightdInfo.getBlockHeight() <= 0)
				return Optional.of( this.recorder.recordConnection(server, requestedBy,true, false, "lightd info issues") );

			// TODO: find a way to verify that the server is using the expected chain

//				if (featuresJson == null || Double.valueOf((String) featuresJson.get("protocol_min")) < MIN_PROTOCOL_VERSION)
//					continue;

//				if (this.expectedGenesisHash != null && !((String) featuresJson.get("genesis_hash")).equals(this.expectedGenesisHash))
//					continue;

			LOGGER.info(() -> String.format("Connected to %s", server));
			this.currentServer = server;
			return Optional.of( this.recorder.recordConnection(server, requestedBy,true, true, EMPTY) );
		} catch (Exception e) {
			// Didn't work, try another server...
			return Optional.of( this.recorder.recordConnection( server, requestedBy, true, false, CrossChainUtils.getNotes(e)));
		}
	}
	/**
	 * Closes connection to <tt>server</tt> if it is currently connected server.
	 *
	 * @param server
	 * @param requestedBy
	 */
	private Optional<ChainableServerConnection> closeServer(ChainableServer server, String notes, String requestedBy) {

		final ChainableServerConnection connection;

		synchronized (this.serverLock) {
			if (this.currentServer == null || !this.currentServer.equals(server) || this.channel == null) {
				return Optional.empty();
			}

			connection = this.recorder.recordConnection(server, requestedBy, false, true, notes);

			// Close the gRPC managed-channel if not shut down already.
			if (!this.channel.isShutdown()) {
				try {
					this.channel.shutdown();
					if (!this.channel.awaitTermination(10, TimeUnit.SECONDS)) {
						LOGGER.warn("Timed out gracefully shutting down connection: {}. ", this.channel);
					}
				} catch (Exception e) {
					LOGGER.error("Unexpected exception while waiting for channel termination", e);
				}
			}

			// Forceful shut down if still not terminated.
			if (!this.channel.isTerminated()) {
				try {
					this.channel.shutdownNow();
					if (!this.channel.awaitTermination(15, TimeUnit.SECONDS)) {
						LOGGER.warn("Timed out forcefully shutting down connection: {}. ", this.channel);
					}
				} catch (Exception e) {
					LOGGER.error("Unexpected exception while waiting for channel termination", e);
				}
			}

			this.channel = null;
			this.currentServer = null;
		}

		return Optional.of( connection );
	}

	/** Closes connection to currently connected server (if any). */
	private Optional<ChainableServerConnection> closeServer(String requestedBy, String notes) {
		synchronized (this.serverLock) {
			return this.closeServer(this.currentServer, notes, requestedBy);
		}
	}

}
