package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.libdohj.params.LitecoinMainNetParams;
import org.libdohj.params.LitecoinRegTestParams;
import org.libdohj.params.LitecoinTestNet3Params;
import org.qortal.crosschain.ElectrumX.Server;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.settings.Settings;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

public class Litecoin extends Bitcoiny {

	public static final String CURRENCY_CODE = "LTC";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(10000); // 0.0001 LTC per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 1000000; // 0.01 LTC minimum order, to avoid dust errors

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 1000L;
	private static final long NON_MAINNET_FEE = 1000L; // enough for TESTNET3 and should be OK for REGTEST

	private static final Map<ElectrumX.Server.ConnectionType, Integer> DEFAULT_ELECTRUMX_PORTS = new EnumMap<>(ElectrumX.Server.ConnectionType.class);
	static {
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.TCP, 50001);
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.SSL, 50002);
	}

	public enum LitecoinNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return LitecoinMainNetParams.get();
			}

			@Override
			public Collection<ElectrumX.Server> getServers() {
				return Arrays.asList(
					// Servers chosen on NO BASIS WHATSOEVER from various sources!
					// Status verified at https://1209k.com/bitcoin-eye/ele.php?chain=ltc
					new Server("backup.electrum-ltc.org", Server.ConnectionType.SSL, 443),
					new Server("5.161.216.180", Server.ConnectionType.SSL, 50002),
					new Server("5.78.97.174", Server.ConnectionType.SSL, 50002),
					new Server("46.101.3.154", Server.ConnectionType.SSL, 50002),
					new Server("188.166.208.106", Server.ConnectionType.SSL, 50002),
					new Server("electrum.ltc.xurious.com", Server.ConnectionType.SSL, 50002),
					new Server("electrum.qortal.link", Server.ConnectionType.SSL, 50002),
					new Server("electrum-ltc.petrkr.net", Server.ConnectionType.SSL, 60002),
					new Server("electrum-ltc.qortal.online", Server.ConnectionType.SSL, 50002),
					new Server("electrum1.cipig.net", Server.ConnectionType.SSL, 20063),
					new Server("electrum2.cipig.net", Server.ConnectionType.SSL, 20063),
					new Server("electrum3.cipig.net", Server.ConnectionType.SSL, 20063),
					new Server("fallacy.fiatfaucet.com", Server.ConnectionType.SSL, 50002),
					new Server("fury.fiatfaucet.com", Server.ConnectionType.SSL, 50002),
					new Server("lightweight.fiatfaucet.com", Server.ConnectionType.SSL, 50002),
					new Server("litecoin.stackwallet.com", Server.ConnectionType.SSL, 20063),
					new Server("ltc.aftrek.org", Server.ConnectionType.SSL, 50002),
					new Server("ltc.rentonrisk.com", Server.ConnectionType.SSL, 50002)
				);
			}

			@Override
			public String getGenesisHash() {
				return "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2";
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
			public Collection<ElectrumX.Server> getServers() {
				return Arrays.asList(
					new Server("electrum-ltc.bysh.me", Server.ConnectionType.SSL, 51002),
					new Server("electrum.ltc.xurious.com", Server.ConnectionType.SSL, 51002)
				);
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
			public Collection<ElectrumX.Server> getServers() {
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
		public abstract Collection<ElectrumX.Server> getServers();
		public abstract String getGenesisHash();
		public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;
	}

	private static Litecoin instance;

	private final LitecoinNet litecoinNet;

	// Constructors and instance

	private Litecoin(LitecoinNet litecoinNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode, DEFAULT_FEE_PER_KB);
		this.litecoinNet = litecoinNet;

		LOGGER.info(() -> String.format("Starting Litecoin support using %s", this.litecoinNet.name()));
	}

	public static synchronized Litecoin getInstance() {
		if (instance == null) {
			LitecoinNet litecoinNet = Settings.getInstance().getLitecoinNet();

			BitcoinyBlockchainProvider electrumX = new ElectrumX("Litecoin-" + litecoinNet.name(), litecoinNet.getGenesisHash(), litecoinNet.getServers(), DEFAULT_ELECTRUMX_PORTS);
			Context bitcoinjContext = new Context(litecoinNet.getParams());

			instance = new Litecoin(litecoinNet, electrumX, bitcoinjContext, CURRENCY_CODE);

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
	 * Returns estimated LTC fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.litecoinNet.getP2shFee(timestamp);
	}

	@Override
	public long getFeeCeiling() {
		return this.litecoinNet.getFeeCeiling();
	}

	@Override
	public void setFeeCeiling(long fee) {

		this.litecoinNet.setFeeCeiling( fee );
	}
}
