package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.libdohj.params.DogecoinMainNetParams;
import org.libdohj.params.DogecoinTestNet3Params;
import org.qortal.crosschain.ElectrumX.Server;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class Dogecoin extends Bitcoiny {

	public static final String CURRENCY_CODE = "DOGE";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(1000000); // 0.01 DOGE per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 100000000L; // 1 DOGE minimum order. See recommendations:
	// https://github.com/dogecoin/dogecoin/blob/master/doc/fee-recommendation.md

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 100000L;
	private static final long NON_MAINNET_FEE = 10000L; // TODO: calibrate this

	private static final Map<ConnectionType, Integer> DEFAULT_ELECTRUMX_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.TCP, 50001);
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.SSL, 50002);
	}

	public enum DogecoinNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return DogecoinMainNetParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				List<ElectrumX.Server> defaultServers = Arrays.asList(
					// Servers chosen on NO BASIS WHATSOEVER from various sources!
					// Status verified at https://1209k.com/bitcoin-eye/ele.php?chain=doge
					new Server("dogecoin.stackwallet.com", Server.ConnectionType.SSL, 50022),
					new Server("electrum.qortal.link", Server.ConnectionType.SSL, 54002),
					new Server("electrum1.cipig.net", Server.ConnectionType.SSL, 20060),
					new Server("electrum2.cipig.net", Server.ConnectionType.SSL, 20060),
					new Server("electrum3.cipig.net", Server.ConnectionType.SSL, 20060)
				);

				List<ElectrumX.Server> availableServers = new ArrayList<>();
				Boolean useDefault = Settings.getInstance().getUseDogecoinDefaults();
				if (useDefault == true) {
					availableServers.addAll(defaultServers);
				}

				String[] settingsList = Settings.getInstance().getDogecoinServers();
				if (settingsList != null) {
					List<ElectrumX.Server> customServers = new ArrayList<>();
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
				return "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return this.getFeeCeiling();
			}
		},
		TEST3 {
			@Override
			public NetworkParameters getParams() {
				return DogecoinTestNet3Params.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(); // TODO: find testnet servers
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
				return null; // TODO: DogecoinRegTestParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
					new Server("localhost", Server.ConnectionType.TCP, 50001),
					new Server("localhost", Server.ConnectionType.SSL, 50002)
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

	private static Dogecoin instance;

	private final DogecoinNet dogecoinNet;

	// Constructors and instance

	private Dogecoin(DogecoinNet dogecoinNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode, DEFAULT_FEE_PER_KB);
		this.dogecoinNet = dogecoinNet;

		LOGGER.info(() -> String.format("Starting Dogecoin support using %s", this.dogecoinNet.name()));
	}

	public static synchronized Dogecoin getInstance() {
		if (instance == null) {
			DogecoinNet dogecoinNet = Settings.getInstance().getDogecoinNet();

			BitcoinyBlockchainProvider electrumX = new ElectrumX("Dogecoin-" + dogecoinNet.name(), dogecoinNet.getGenesisHash(), dogecoinNet.getServers(), DEFAULT_ELECTRUMX_PORTS);
			Context bitcoinjContext = new Context(dogecoinNet.getParams());

			instance = new Dogecoin(dogecoinNet, electrumX, bitcoinjContext, CURRENCY_CODE);

			electrumX.setBlockchain(instance);
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
	 * Returns estimated DOGE fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.dogecoinNet.getP2shFee(timestamp);
	}

	@Override
	public long getFeeCeiling() {
		return this.dogecoinNet.getFeeCeiling();
	}

	@Override
	public void setFeeCeiling(long fee) {

		this.dogecoinNet.setFeeCeiling( fee );
	}
}
