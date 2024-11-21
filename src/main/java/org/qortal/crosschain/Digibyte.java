package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.libdohj.params.DigibyteMainNetParams;
import org.qortal.crosschain.ElectrumX.Server;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.settings.Settings;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

public class Digibyte extends Bitcoiny {

	public static final String CURRENCY_CODE = "DGB";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(100000); // 0.001 DGB per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 1000000; // 0.01 DGB minimum order, to avoid dust errors

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 10000L;
	private static final long NON_MAINNET_FEE = 10000L; // enough for TESTNET3 and should be OK for REGTEST

	private static final Map<ConnectionType, Integer> DEFAULT_ELECTRUMX_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.TCP, 50001);
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.SSL, 50002);
	}

	public enum DigibyteNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return DigibyteMainNetParams.get();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
					// Servers chosen on NO BASIS WHATSOEVER from various sources!
					// Status verified at https://1209k.com/bitcoin-eye/ele.php?chain=dgb
					// offline Server("electrum.qortal.link", Server.ConnectionType.SSL, 55002),
					new Server("electrum1.cipig.net", Server.ConnectionType.SSL, 20059),
					new Server("electrum2.cipig.net", Server.ConnectionType.SSL, 20059),
					new Server("electrum3.cipig.net", Server.ConnectionType.SSL, 20059)
				);
			}

			@Override
			public String getGenesisHash() {
				return "7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496";
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
				return "308ea0711d5763be2995670dd9ca9872753561285a84da1d58be58acaa822252";
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

	private static Digibyte instance;

	private final DigibyteNet digibyteNet;

	// Constructors and instance

	private Digibyte(DigibyteNet digibyteNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode, DEFAULT_FEE_PER_KB);
		this.digibyteNet = digibyteNet;

		LOGGER.info(() -> String.format("Starting Digibyte support using %s", this.digibyteNet.name()));
	}

	public static synchronized Digibyte getInstance() {
		if (instance == null) {
			DigibyteNet digibyteNet = Settings.getInstance().getDigibyteNet();

			BitcoinyBlockchainProvider electrumX = new ElectrumX("Digibyte-" + digibyteNet.name(), digibyteNet.getGenesisHash(), digibyteNet.getServers(), DEFAULT_ELECTRUMX_PORTS);
			Context bitcoinjContext = new Context(digibyteNet.getParams());

			instance = new Digibyte(digibyteNet, electrumX, bitcoinjContext, CURRENCY_CODE);

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
	 * Returns estimated DGB fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.digibyteNet.getP2shFee(timestamp);
	}

	@Override
	public long getFeeCeiling() {
		return this.digibyteNet.getFeeCeiling();
	}

	@Override
	public void setFeeCeiling(long fee) {

		this.digibyteNet.setFeeCeiling( fee );
	}
}
