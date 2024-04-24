package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.libdohj.params.RavencoinMainNetParams;
import org.qortal.crosschain.ElectrumX.Server;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.settings.Settings;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

public class Ravencoin extends Bitcoiny {

	public static final String CURRENCY_CODE = "RVN";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(1125000); // 0.01125 RVN per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 1000000; // 0.01 RVN minimum order, to avoid dust errors

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 1000000L;
	private static final long NON_MAINNET_FEE = 1000000L; // enough for TESTNET3 and should be OK for REGTEST

	private static final Map<ConnectionType, Integer> DEFAULT_ELECTRUMX_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.TCP, 50001);
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.SSL, 50002);
	}

	public enum RavencoinNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return RavencoinMainNetParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
					// Servers chosen on NO BASIS WHATSOEVER from various sources!
					// Status verified at https://1209k.com/bitcoin-eye/ele.php?chain=rvn
					// offline Server("electrum.qortal.link", Server.ConnectionType.SSL, 56002),
					new Server("electrum1.cipig.net", Server.ConnectionType.SSL, 20051),
					new Server("electrum2.cipig.net", Server.ConnectionType.SSL, 20051),
					new Server("electrum3.cipig.net", Server.ConnectionType.SSL, 20051),
					new Server("rvn-dashboard.com", Server.ConnectionType.SSL, 50002),
					new Server("rvn4lyfe.com", Server.ConnectionType.SSL, 50002)
				);
			}

			@Override
			public String getGenesisHash() {
				return "0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return this.getFeeCeiling();
			}
		},
		TEST3 {
			@Override
			public NetworkParameters getParams() {
				return TestNet3Params.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(); // TODO: find testnet servers
			}

			@Override
			public String getGenesisHash() {
				return "000000ecfc5e6324a079542221d00e10362bdc894d56500c414060eea8a3ad5a";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		},
		REGTEST {
			@Override
			public NetworkParameters getParams() {
				return RegTestParams.get();
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

	private static Ravencoin instance;

	private final RavencoinNet ravencoinNet;

	// Constructors and instance

	private Ravencoin(RavencoinNet ravencoinNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode, DEFAULT_FEE_PER_KB);
		this.ravencoinNet = ravencoinNet;

		LOGGER.info(() -> String.format("Starting Ravencoin support using %s", this.ravencoinNet.name()));
	}

	public static synchronized Ravencoin getInstance() {
		if (instance == null) {
			RavencoinNet ravencoinNet = Settings.getInstance().getRavencoinNet();

			BitcoinyBlockchainProvider electrumX = new ElectrumX("Ravencoin-" + ravencoinNet.name(), ravencoinNet.getGenesisHash(), ravencoinNet.getServers(), DEFAULT_ELECTRUMX_PORTS);
			Context bitcoinjContext = new Context(ravencoinNet.getParams());

			instance = new Ravencoin(ravencoinNet, electrumX, bitcoinjContext, CURRENCY_CODE);

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
	 * Returns estimated RVN fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.ravencoinNet.getP2shFee(timestamp);
	}

	@Override
	public long getFeeCeiling() {
		return this.ravencoinNet.getFeeCeiling();
	}

	@Override
	public void setFeeCeiling(long fee) {

		this.ravencoinNet.setFeeCeiling( fee );
	}
}
