package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.qortal.api.model.crosschain.BitcoinyTBDRequest;
import org.qortal.crosschain.ChainableServer.ConnectionType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BitcoinyTBD extends Bitcoiny {

	private static HashMap<String, BitcoinyTBDRequest> requestsById = new HashMap<>();

	private long minimumOrderAmount;

	private static Map<String, BitcoinyTBD> instanceByCode = new HashMap<>();

	private final NetTBD netTBD;

	/**
	 * Default ElectrumX Ports
	 *
	 * These are the defaults for all Bitcoin forks.
	 */
	private static final Map<ConnectionType, Integer> DEFAULT_ELECTRUMX_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.TCP, 50001);
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.SSL, 50002);
	}

	/**
	 * Constructor
	 *
	 * @param netTBD network access to the blockchain provider
	 * @param blockchain blockchain provider
	 * @param bitcoinjContext
	 * @param currencyCode the trading symbol, ie LTC
	 * @param minimumOrderAmount web search, LTC minimumOrderAmount = 1000000, 0.01 LTC minimum order to avoid dust errors
	 * @param feePerKb web search, LTC feePerKb = 10000, 0.0001 LTC per 1000 bytes
	 */
	private BitcoinyTBD(
			NetTBD netTBD,
			BitcoinyBlockchainProvider blockchain,
			Context bitcoinjContext,
			String currencyCode,
			long minimumOrderAmount,
			long feePerKb) {

		super(blockchain, bitcoinjContext, currencyCode, Coin.valueOf( feePerKb));

		this.netTBD = netTBD;
		this.minimumOrderAmount = minimumOrderAmount;

		LOGGER.info(() -> String.format("Starting BitcoinyTBD support using %s", this.netTBD.getName()));
	}

	/**
	 * Get Instance
	 *
	 * @param currencyCode the trading symbol, ie LTC
	 *
	 * @return the instance
	 */
	public static synchronized Optional<BitcoinyTBD> getInstance(String currencyCode) {

		return Optional.ofNullable(instanceByCode.get(currencyCode));
	}

	/**
	 * Build Instance
	 *
	 * @param bitcoinyTBDRequest
	 * @param networkParams
	 * @return the instance
	 */
	public static synchronized  BitcoinyTBD buildInstance(
			BitcoinyTBDRequest bitcoinyTBDRequest,
			NetworkParameters networkParams
			) {

		NetTBD netTBD
				= new NetTBD(
				bitcoinyTBDRequest.getNetworkName(),
				bitcoinyTBDRequest.getFeeRequired(),
				networkParams,
				Collections.emptyList(),
				bitcoinyTBDRequest.getExpectedGenesisHash()
		);

		BitcoinyBlockchainProvider electrumX = new ElectrumX(netTBD.getName(), netTBD.getGenesisHash(), netTBD.getServers(), DEFAULT_ELECTRUMX_PORTS);
		Context bitcoinjContext = new Context(netTBD.getParams());

		BitcoinyTBD instance
				= new BitcoinyTBD(
						netTBD,
						electrumX,
						bitcoinjContext,
						bitcoinyTBDRequest.getCurrencyCode(),
						bitcoinyTBDRequest.getMinimumOrderAmount(),
						bitcoinyTBDRequest.getFeePerKb());
		electrumX.setBlockchain(instance);

		instanceByCode.put(bitcoinyTBDRequest.getCurrencyCode(), instance);
		requestsById.put(bitcoinyTBDRequest.getId(), bitcoinyTBDRequest);

		return instance;
	}

	public static List<BitcoinyTBDRequest> getRequests() {

		Collection<BitcoinyTBDRequest> requests = requestsById.values();

		List<BitcoinyTBDRequest> list = new ArrayList<>( requests.size() );

		list.addAll( requests );

		return list;
	}

	@Override
	public long getMinimumOrderAmount() {

		return minimumOrderAmount;
	}

	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {

		return this.netTBD.getFeeRequired();
	}

	@Override
	public long getFeeRequired() {

		return this.netTBD.getFeeRequired();
	}

	@Override
	public void setFeeRequired(long fee) {

		this.netTBD.setFeeRequired( fee );
	}
}