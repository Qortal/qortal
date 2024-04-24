package org.qortal.crosschain;

import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.qortal.crosschain.ElectrumX.Server;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.settings.Settings;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

public class Bitcoin extends Bitcoiny {

	public static final String CURRENCY_CODE = "BTC";

	private static final long MINIMUM_ORDER_AMOUNT = 100000; // 0.001 BTC minimum order, due to high fees

	// Temporary values until a dynamic fee system is written.
	private static final long NEW_FEE_AMOUNT = 6_000L;

	private static final long NON_MAINNET_FEE = 1000L; // enough for TESTNET3 and should be OK for REGTEST

	private static final Map<ElectrumX.Server.ConnectionType, Integer> DEFAULT_ELECTRUMX_PORTS = new EnumMap<>(ElectrumX.Server.ConnectionType.class);
	static {
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.TCP, 50001);
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.SSL, 50002);
	}

	public enum BitcoinNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return MainNetParams.get();
			}

			@Override
			public Collection<ElectrumX.Server> getServers() {
				return Arrays.asList(
					// Servers chosen on NO BASIS WHATSOEVER from various sources!
					// Status verified at https://1209k.com/bitcoin-eye/ele.php?chain=btc
					new Server("104.198.149.61", Server.ConnectionType.SSL, 50002),
					new Server("128.0.190.26", Server.ConnectionType.SSL, 50002),
					new Server("157.245.172.236", Server.ConnectionType.SSL, 50002),
					new Server("260.whyza.net", Server.ConnectionType.SSL, 50002),
					new Server("34.136.93.37", Server.ConnectionType.SSL, 50002),
					new Server("34.67.22.216", Server.ConnectionType.SSL, 50002),
					new Server("34.68.133.78", Server.ConnectionType.SSL, 50002),
					new Server("alviss.coinjoined.com", Server.ConnectionType.SSL, 50002),
					new Server("api.ordimint.com", Server.ConnectionType.SSL, 50002),
					new Server("ax101.blockeng.ch", Server.ConnectionType.SSL, 50002),
					new Server("ax102.blockeng.ch", Server.ConnectionType.SSL, 50002),
					new Server("b.1209k.com", Server.ConnectionType.SSL, 50002),
					new Server("b6.1209k.com", Server.ConnectionType.SSL, 50002),
					new Server("bitcoin.aranguren.org", Server.ConnectionType.SSL, 50002),
					new Server("bitcoin.dermichi.com", Server.ConnectionType.SSL, 50002),
					new Server("bitcoin.lu.ke", Server.ConnectionType.SSL, 50002),
					new Server("bitcoin.lukechilds.co", Server.ConnectionType.SSL, 50002),
					new Server("bitcoin.threshold.p2p.org", Server.ConnectionType.SSL, 50002),
					new Server("blkhub.net", Server.ConnectionType.SSL, 50002),
					new Server("bolt.schulzemic.net", Server.ConnectionType.SSL, 50002),
					new Server("btc.aftrek.org", Server.ConnectionType.SSL, 50002),
					new Server("btc.electrum.bitbitnet.net", Server.ConnectionType.SSL, 50002),
					new Server("btc.hodler.ninja", Server.ConnectionType.SSL, 50002),
					new Server("btc.lastingcoin.net", Server.ConnectionType.SSL, 50002),
					new Server("btc.ocf.sh", Server.ConnectionType.SSL, 50002),
					new Server("btc.prompt.cash", Server.ConnectionType.SSL, 63871),
					new Server("btce.iiiiiii.biz", Server.ConnectionType.SSL, 50002),
					new Server("caleb.vegas", Server.ConnectionType.SSL, 50002),
					new Server("d762li0k0g.d.firewalla.org", Server.ConnectionType.SSL, 50002),
					new Server("de.poiuty.com", Server.ConnectionType.SSL, 50002),
					new Server("dijon.anties.org", Server.ConnectionType.SSL, 50002),
					new Server("e.keff.org", Server.ConnectionType.SSL, 50002),
					new Server("eai.coincited.net", Server.ConnectionType.SSL, 50002),
					new Server("electrum.bitaroo.net", Server.ConnectionType.SSL, 50002),
					new Server("electrum.bitrefill.com", Server.ConnectionType.SSL, 50002),
					new Server("electrum.blockstream.info", Server.ConnectionType.SSL, 50002),
					new Server("electrum.brainshome.de", Server.ConnectionType.SSL, 50002),
					new Server("electrum.coineuskal.com", Server.ConnectionType.SSL, 50002),
					new Server("electrum.coinext.com.br", Server.ConnectionType.SSL, 50002),
					new Server("electrum.coinucopia.io", Server.ConnectionType.SSL, 50002),
					new Server("electrum.emzy.de", Server.ConnectionType.SSL, 50002),
					new Server("electrum.jochen-hoenicke.de", Server.ConnectionType.SSL, 50006),
					new Server("electrum.kcicom.net", Server.ConnectionType.SSL, 50002),
					new Server("electrum.kendigisland.xyz", Server.ConnectionType.SSL, 50002),
					new Server("electrum.mmitech.info", Server.ConnectionType.SSL, 50002),
					new Server("electrum.thomasfischbach.de", Server.ConnectionType.SSL, 50002),
					new Server("electrum-btc.leblancnet.us", Server.ConnectionType.SSL, 50002),
					new Server("electrum0.snel.it", Server.ConnectionType.SSL, 50002),
					new Server("electrum1.cipig.net", Server.ConnectionType.SSL, 20000),
					new Server("electrum2.cipig.net", Server.ConnectionType.SSL, 20000),
					new Server("electrum3.cipig.net", Server.ConnectionType.SSL, 20000),
					new Server("electrumx.blockfinance-eco.li", Server.ConnectionType.SSL, 50002),
					new Server("electrumx.indoor.app", Server.ConnectionType.SSL, 50002),
					new Server("electrumx.iodata.org", Server.ConnectionType.SSL, 50002),
					new Server("electrumx-btc.cryptonermal.net", Server.ConnectionType.SSL, 50002),
					new Server("electrumx-core.1209k.com", Server.ConnectionType.SSL, 50002),
					new Server("elx.bitske.com", Server.ConnectionType.SSL, 50002),
					new Server("ex08.axalgo.com", Server.ConnectionType.SSL, 50002),
					new Server("exs.dyshek.org", Server.ConnectionType.SSL, 50002),
					new Server("fortress.qtornado.com", Server.ConnectionType.SSL, 50002),
					new Server("fulcrum.grey.pw", Server.ConnectionType.SSL, 50002),
					new Server("fulcrum.not.fyi", Server.ConnectionType.SSL, 51002),
					new Server("fulcrum.sethforprivacy.com", Server.ConnectionType.SSL, 50002),
					new Server("fulcrum.slicksparks.ky", Server.ConnectionType.SSL, 50002),
					new Server("gods-of-rock.screaminglemur.net", Server.ConnectionType.SSL, 50002),
					new Server("guichet.centure.cc", Server.ConnectionType.SSL, 50002),
					new Server("hodl.artyomk13.me", Server.ConnectionType.SSL, 50002),
					new Server("hodlers.beer", Server.ConnectionType.SSL, 50002),
					new Server("horsey.cryptocowboys.net", Server.ConnectionType.SSL, 50002),
					new Server("kareoke.qoppa.org", Server.ConnectionType.SSL, 50002),
					new Server("kirsche.emzy.de", Server.ConnectionType.SSL, 50002),
					new Server("kittyserver.ddnsfree.com", Server.ConnectionType.SSL, 50002),
					new Server("lille.anties.org", Server.ConnectionType.SSL, 50002),
					new Server("marseille.anties.org", Server.ConnectionType.SSL, 50002),
					new Server("node1.btccuracao.com", Server.ConnectionType.SSL, 50002),
					new Server("osr1ex1.compumundohipermegared.one", Server.ConnectionType.SSL, 50002),
					new Server("paris.anties.org", Server.ConnectionType.SSL, 50002),
					new Server("ragtor.duckdns.org", Server.ConnectionType.SSL, 50002),
					new Server("smmalis37.ddns.net", Server.ConnectionType.SSL, 50002),
					new Server("stavver.dyshek.org", Server.ConnectionType.SSL, 50002),
					new Server("ulrichard.ch", Server.ConnectionType.SSL, 50002),
					new Server("vmd63185.contaboserver.net", Server.ConnectionType.SSL, 50002),
					new Server("xtrum.com", Server.ConnectionType.SSL, 50002)
				);
			}

			@Override
			public String getGenesisHash() {
				return "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f";
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
			public Collection<ElectrumX.Server> getServers() {
				return Arrays.asList(
					new Server("bitcoin.devmole.eu", Server.ConnectionType.TCP, 5000),
					new Server("bitcoin.stagemole.eu", Server.ConnectionType.TCP, 5000),
					new Server("blockstream.info", Server.ConnectionType.SSL, 993),
					new Server("electrum.blockstream.info", Server.ConnectionType.SSL, 60002),
					new Server("electrum1.cipig.net", Server.ConnectionType.TCP, 10068),
					new Server("electrum2.cipig.net", Server.ConnectionType.TCP, 10068),
					new Server("electrum3.cipig.net", Server.ConnectionType.TCP, 10068),
					new Server("testnet.aranguren.org", Server.ConnectionType.SSL, 51002),
					new Server("testnet.hsmiths.com", Server.ConnectionType.SSL, 53012),
					new Server("testnet.qtornado.com", Server.ConnectionType.SSL, 51002),
					new Server("v22019051929289916.bestsrv.de", Server.ConnectionType.SSL, 50002)
				);
			}

			@Override
			public String getGenesisHash() {
				return "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943";
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

		private long feeCeiling = NEW_FEE_AMOUNT;

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

	private static Bitcoin instance;

	private final BitcoinNet bitcoinNet;

	// Constructors and instance

	private Bitcoin(BitcoinNet bitcoinNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, currencyCode, bitcoinjContext.getFeePerKb());
		this.bitcoinNet = bitcoinNet;

		LOGGER.info(() -> String.format("Starting Bitcoin support using %s", this.bitcoinNet.name()));
	}

	public static synchronized Bitcoin getInstance() {
		if (instance == null) {
			BitcoinNet bitcoinNet = Settings.getInstance().getBitcoinNet();

			BitcoinyBlockchainProvider electrumX = new ElectrumX("Bitcoin-" + bitcoinNet.name(), bitcoinNet.getGenesisHash(), bitcoinNet.getServers(), DEFAULT_ELECTRUMX_PORTS);
			Context bitcoinjContext = new Context(bitcoinNet.getParams());

			instance = new Bitcoin(bitcoinNet, electrumX, bitcoinjContext, CURRENCY_CODE);

			electrumX.setBlockchain(instance);
		}

		return instance;
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		instance = null;
	}

	@Override
	public long getMinimumOrderAmount() {
		return MINIMUM_ORDER_AMOUNT;
	}

	// Actual useful methods for use by other classes

	/**
	 * Returns estimated BTC fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.bitcoinNet.getP2shFee(timestamp);
	}

	@Override
	public long getFeeCeiling() {
		return this.bitcoinNet.getFeeCeiling();
	}

	@Override
	public void setFeeCeiling(long fee) {

		this.bitcoinNet.setFeeCeiling( fee );
	}
	/**
 	* Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt> using 20 sat/byte fee.
 	*
 	* @param xprv58 BIP32 private key
 	* @param recipient P2PKH address
 	* @param amount unscaled amount
 	* @return transaction, or null if insufficient funds
 	*/
	@Override
	public Transaction buildSpend(String xprv58, String recipient, long amount) {
		return buildSpend(xprv58, recipient, amount, 20L);
	}

}
