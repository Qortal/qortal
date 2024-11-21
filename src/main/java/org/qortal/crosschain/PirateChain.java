package org.qortal.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats;
import com.google.common.hash.HashCode;
import com.rust.litewalletjni.LiteWalletJni;
import org.bitcoinj.core.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.libdohj.params.LitecoinRegTestParams;
import org.libdohj.params.LitecoinTestNet3Params;
import org.libdohj.params.PirateChainMainNetParams;
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

public class PirateChain extends Bitcoiny {

	public static final String CURRENCY_CODE = "ARRR";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(10000); // 0.0001 ARRR per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 10000; // 0.0001 ARRR minimum order, to avoid dust errors // TODO: increase this

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 10000L; // 0.0001 ARRR
	private static final long NON_MAINNET_FEE = 10000L; // 0.0001 ARRR

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
				List<Server> defaultServers = Arrays.asList(
					// Servers chosen on NO BASIS WHATSOEVER from various sources!
					new Server("lightd.pirate.black", Server.ConnectionType.SSL, 443),
					new Server("wallet-arrr1.qortal.online", Server.ConnectionType.SSL, 443),
					new Server("wallet-arrr2.qortal.online", Server.ConnectionType.SSL, 443),
					new Server("wallet-arrr3.qortal.online", Server.ConnectionType.SSL, 443),
					new Server("wallet-arrr4.qortal.online", Server.ConnectionType.SSL, 443),
					new Server("wallet-arrr5.qortal.online", Server.ConnectionType.SSL, 443)
				);

				List<Server> availableServers = new ArrayList<>();
				Boolean useDefault = Settings.getInstance().getUsePirateChainDefaults();
				if (useDefault == true) {
					availableServers.addAll(defaultServers);
				}

				String[] settingsList = Settings.getInstance().getPirateChainServers();
				if (settingsList != null) {
					List<Server> customServers = new ArrayList<>();
					for (String setting : settingsList) {
						String[] colonParts = setting.split(":");
						if (colonParts.length == 2) {
							String[] commaParts = colonParts[1].split(",");
							if (commaParts.length == 2) {
								String hostname = colonParts[0];
								int port = Integer.parseInt(commaParts[0].trim());
								String typeString = commaParts[1].trim().toUpperCase();
								Server.ConnectionType type = Server.ConnectionType.SSL;
								if (typeString.equals("TCP")) {
									type = Server.ConnectionType.TCP;
								}
								customServers.add(new Server(hostname, type, port));
							}
						}
					}
					availableServers.addAll(customServers);
				}
				return availableServers;
			}

			@Override
			public String getGenesisHash() {
				return "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return this.getFeeCeiling();
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
					new Server("localhost", Server.ConnectionType.SSL, 443)
				);
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

		private long feeCeiling = MAINNET_FEE;

		public long getFeeCeiling() {
			return feeCeiling;
		}

		public void setFeeCeiling(long feeCeiling) {
			this.feeCeiling = feeCeiling;
		}

		public abstract NetworkParameters getParams();
		public abstract Collection<Server> getServers();
		public abstract String getGenesisHash();
		public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;
	}

	private static PirateChain instance;

	private final PirateChainNet pirateChainNet;

	// Constructors and instance

	private PirateChain(PirateChainNet pirateChainNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode, DEFAULT_FEE_PER_KB);
		this.pirateChainNet = pirateChainNet;

		LOGGER.info(() -> String.format("Starting Pirate Chain support using %s", this.pirateChainNet.name()));
	}

	public static synchronized PirateChain getInstance() {
		if (instance == null) {
			PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();

			BitcoinyBlockchainProvider pirateLightClient = new PirateLightClient("PirateChain-" + pirateChainNet.name(), pirateChainNet.getGenesisHash(), pirateChainNet.getServers(), DEFAULT_LITEWALLET_PORTS);
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
	 * Returns estimated LTC fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.pirateChainNet.getP2shFee(timestamp);
	}

	@Override
	public long getFeeCeiling() {
		return this.pirateChainNet.getFeeCeiling();
	}

	@Override
	public void setFeeCeiling(long fee) {

		this.pirateChainNet.setFeeCeiling( fee );
	}
	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if balance unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws ForeignBlockchainException {
		return this.blockchainProvider.getConfirmedAddressBalance(base58Address);
	}

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
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
	 * @throws ForeignBlockchainException if error occurs
	 */
	public List<CompactFormats.CompactBlock> getCompactBlocks(int startHeight, int count) throws ForeignBlockchainException {
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
		}
		catch (AddressFormatException e) {
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
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);
			walletController.ensureInitialized();
			walletController.ensureSynchronized();
			walletController.ensureNotNullSeed();

			// Get balance
			String response = LiteWalletJni.execute("balance", "");
			JSONObject json = new JSONObject(response);
			if (json.has("zbalance")) {
				return json.getLong("zbalance");
			}

			throw new ForeignBlockchainException("Unable to determine balance");
		}
	}

	public List<SimpleTransaction> getWalletTransactions(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);
			walletController.ensureInitialized();
			walletController.ensureSynchronized();
			walletController.ensureNotNullSeed();

			List<SimpleTransaction> transactions = new ArrayList<>();

			// Get transactions list
			String response = LiteWalletJni.execute("list", "");
			JSONArray transactionsJson = new JSONArray(response);
			if (transactionsJson != null) {
				for (int i = 0; i < transactionsJson.length(); i++) {
					JSONObject transactionJson = transactionsJson.getJSONObject(i);

					if (transactionJson.has("txid")) {
						String txId = transactionJson.getString("txid");
						Long timestamp = transactionJson.getLong("datetime");
						Long amount = transactionJson.getLong("amount");
						Long fee = transactionJson.getLong("fee");
						String memo = null;

						if (transactionJson.has("incoming_metadata")) {
							JSONArray incomingMetadatas = transactionJson.getJSONArray("incoming_metadata");
							if (incomingMetadatas != null) {
								for (int j = 0; j < incomingMetadatas.length(); j++) {
									JSONObject incomingMetadata = incomingMetadatas.getJSONObject(j);
									if (incomingMetadata.has("value")) {
										//String address = incomingMetadata.getString("address");
										Long value = incomingMetadata.getLong("value");
										amount = value; // TODO: figure out how to parse transactions with multiple incomingMetadata entries
									}

									if (incomingMetadata.has("memo") && !incomingMetadata.isNull("memo")) {
										memo = incomingMetadata.getString("memo");
									}
								}
							}
						}

						if (transactionJson.has("outgoing_metadata")) {
							JSONArray outgoingMetadatas = transactionJson.getJSONArray("outgoing_metadata");
							for (int j = 0; j < outgoingMetadatas.length(); j++) {
								JSONObject outgoingMetadata = outgoingMetadatas.getJSONObject(j);

								if (outgoingMetadata.has("memo") && !outgoingMetadata.isNull("memo")) {
									memo = outgoingMetadata.getString("memo");
								}
							}
						}

						long timestampMillis = Math.toIntExact(timestamp) * 1000L;
						SimpleTransaction transaction = new SimpleTransaction(txId, timestampMillis, amount, fee, null, null, memo);
						transactions.add(transaction);
					}
				}
			}

			return transactions;
		}
	}

	public String getWalletAddress(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);
			walletController.ensureInitialized();
			walletController.ensureNotNullSeed();

			return walletController.getCurrentWallet().getWalletAddress();
		}
	}

	public String getPrivateKey(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);
			walletController.ensureInitialized();
			walletController.ensureNotNullSeed();
                        walletController.getCurrentWallet().unlock();

			return walletController.getCurrentWallet().getPrivateKey();
		}
	}

	public String getWalletSeed(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);
			walletController.ensureInitialized();
			walletController.ensureNotNullSeed();
                        walletController.getCurrentWallet().unlock();

			return walletController.getCurrentWallet().getWalletSeed(entropy58);
		}
	}

	public String getUnusedReceiveAddress(String key58) throws ForeignBlockchainException {
		// For now, return the main wallet address
		// FUTURE: generate an unused one
		return this.getWalletAddress(key58);
	}

	public String sendCoins(PirateChainSendRequest pirateChainSendRequest) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.initWithEntropy58(pirateChainSendRequest.entropy58);
		walletController.ensureInitialized();
		walletController.ensureSynchronized();
		walletController.ensureNotNullSeed();

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
		String response = LiteWalletJni.execute("send", txnString);
		JSONObject json = new JSONObject(response);
		try {
			if (json.has("txid")) { // Success
				return json.getString("txid");
			}
			else if (json.has("error")) {
				String error = json.getString("error");
				throw new ForeignBlockchainException(error);
			}

		} catch (JSONException e) {
			throw new ForeignBlockchainException(e.getMessage());
		}

		throw new ForeignBlockchainException("Something went wrong");
	}

	public String fundP2SH(String entropy58, String receivingAddress, long amount,
						   String redeemScript58) throws ForeignBlockchainException {

		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.initWithEntropy58(entropy58);
		walletController.ensureInitialized();
		walletController.ensureSynchronized();
		walletController.ensureNotNullSeed();

		// Unlock wallet
		walletController.getCurrentWallet().unlock();

		// Build spend
		JSONObject txn = new JSONObject();
		txn.put("input", walletController.getCurrentWallet().getWalletAddress());
		txn.put("fee", MAINNET_FEE);

		JSONObject output = new JSONObject();
		output.put("address", receivingAddress);
		output.put("amount", amount);
		//output.put("memo", memo);

		JSONArray outputs = new JSONArray();
		outputs.put(output);
		txn.put("output", outputs);
		txn.put("script", redeemScript58);

		String txnString = txn.toString();

		// Send the coins
		String response = LiteWalletJni.execute("sendp2sh", txnString);
		JSONObject json = new JSONObject(response);
		try {
			if (json.has("txid")) { // Success
				return json.getString("txid");
			}
			else if (json.has("error")) {
				String error = json.getString("error");
				throw new ForeignBlockchainException(error);
			}

		} catch (JSONException e) {
			throw new ForeignBlockchainException(e.getMessage());
		}

		throw new ForeignBlockchainException("Something went wrong");
	}

	public String redeemP2sh(String p2shAddress, String receivingAddress, long amount, String redeemScript58,
							 String fundingTxid58, String secret58, String privateKey58) throws ForeignBlockchainException {

		// Use null seed wallet since we may not have the entropy bytes for a real wallet's seed
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.initNullSeedWallet();
		walletController.ensureInitialized();

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
		String response = LiteWalletJni.execute("redeemp2sh", txnString);
		JSONObject json = new JSONObject(response);
		try {
			if (json.has("txid")) { // Success
				return json.getString("txid");
			}
			else if (json.has("error")) {
				String error = json.getString("error");
				throw new ForeignBlockchainException(error);
			}

		} catch (JSONException e) {
			throw new ForeignBlockchainException(e.getMessage());
		}

		throw new ForeignBlockchainException("Something went wrong");
	}

	public String refundP2sh(String p2shAddress, String receivingAddress, long amount, String redeemScript58,
							 String fundingTxid58, int lockTime, String privateKey58) throws ForeignBlockchainException {

		// Use null seed wallet since we may not have the entropy bytes for a real wallet's seed
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		walletController.initNullSeedWallet();
		walletController.ensureInitialized();

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
		String response = LiteWalletJni.execute("redeemp2sh", txnString);
		JSONObject json = new JSONObject(response);
		try {
			if (json.has("txid")) { // Success
				return json.getString("txid");
			}
			else if (json.has("error")) {
				String error = json.getString("error");
				throw new ForeignBlockchainException(error);
			}

		} catch (JSONException e) {
			throw new ForeignBlockchainException(e.getMessage());
		}

		throw new ForeignBlockchainException("Something went wrong");
	}

	public String getSyncStatus(String entropy58) throws ForeignBlockchainException {
		synchronized (this) {
			PirateChainWalletController walletController = PirateChainWalletController.getInstance();
			walletController.initWithEntropy58(entropy58);

			return walletController.getSyncStatus();
		}
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
		for (int i=0; i<vinCount; i++) {
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
		for (int i=0; i<voutCount; i++) {
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

		// Note: this is incomplete, as sapling spend info is not yet parsed. We don't need it for our
		// current trade bot implementation, but it could be added in the future, for completeness.
		// See link below for reference:
		// https://github.com/PirateNetwork/librustzcash/blob/2981c4d2860f7cd73282fed885daac0323ff0280/zcash_primitives/src/transaction/mod.rs#L197

		return new BitcoinyTransaction(txHash, size, locktime, timestamp, inputs, outputs);
	}

}
