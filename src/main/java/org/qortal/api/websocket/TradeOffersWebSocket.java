package org.qortal.api.websocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.qortal.api.model.CrossChainOfferSummary;
import org.qortal.controller.Synchronizer;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.AcctMode;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.ByteArray;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@WebSocket
@SuppressWarnings("serial")
public class TradeOffersWebSocket extends ApiWebSocket implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(TradeOffersWebSocket.class);

	private static class CachedOfferInfo {
		public final Map<String, AcctMode> previousAtModes = new HashMap<>();
		// OFFERING
		public final Map<String, CrossChainOfferSummary> currentSummaries = new HashMap<>();
		// REDEEMED/REFUNDED/CANCELLED
		public final Map<String, CrossChainOfferSummary> historicSummaries = new HashMap<>();
	}
	// Manual synchronization
	private static final Map<String, CachedOfferInfo> cachedInfoByBlockchain = new HashMap<>();

	private static final Predicate<CrossChainOfferSummary> isHistoric = offerSummary
			-> offerSummary.getMode() == AcctMode.REDEEMED
			|| offerSummary.getMode() == AcctMode.REFUNDED
			|| offerSummary.getMode() == AcctMode.CANCELLED;

	private static final Map<Session, String> sessionBlockchain = Collections.synchronizedMap(new HashMap<>());

	/**
	 * Updated for Jetty 10.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		// Register this instance to handle websocket upgrades
		factory.addMapping("/", (req, res) -> this);

		try (final Repository repository = RepositoryManager.getRepository()) {
			populateCurrentSummaries(repository);
			populateHistoricSummaries(repository);
		} catch (DataException e) {
			// How to fail properly?
			return;
		}

		EventBus.INSTANCE.addListener(this);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof Synchronizer.NewChainTipEvent))
			return;

		BlockData blockData = ((Synchronizer.NewChainTipEvent) event).getNewChainTip();

		// Process any new info

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Find any new/changed trade ATs since this block
			final Boolean isFinished = null;
			final Integer dataByteOffset = null;
			final Long expectedValue = null;
			final Integer minimumFinalHeight = blockData.getHeight();

			for (SupportedBlockchain blockchain : SupportedBlockchain.values()) {
				Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = SupportedBlockchain.getFilteredAcctMap(blockchain);
				List<CrossChainOfferSummary> crossChainOfferSummaries = new ArrayList<>();

				synchronized (cachedInfoByBlockchain) {
					CachedOfferInfo cachedInfo = cachedInfoByBlockchain.computeIfAbsent(blockchain.name(), k -> new CachedOfferInfo());

					for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
						byte[] codeHash = acctInfo.getKey().value;
						ACCT acct = acctInfo.getValue().get();

						List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(codeHash, null, null,
								isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
								null, null, null);

						crossChainOfferSummaries.addAll(produceSummaries(repository, acct, atStates, blockData.getTimestamp()));
					}

					// Remove any entries unchanged from last time
					crossChainOfferSummaries.removeIf(offerSummary -> cachedInfo.previousAtModes.get(offerSummary.getQortalAtAddress()) == offerSummary.getMode());

					// Skip to next blockchain if nothing has changed (for this blockchain)
					if (crossChainOfferSummaries.isEmpty())
						continue;

					// Update
					for (CrossChainOfferSummary offerSummary : crossChainOfferSummaries) {
						String offerAtAddress = offerSummary.getQortalAtAddress();
						cachedInfo.previousAtModes.put(offerAtAddress, offerSummary.getMode());
						LOGGER.trace("Block height: {}, AT: {}, mode: {}", blockData.getHeight(), offerAtAddress, offerSummary.getMode().name());

						switch (offerSummary.getMode()) {
							case OFFERING:
								cachedInfo.currentSummaries.put(offerAtAddress, offerSummary);
								cachedInfo.historicSummaries.remove(offerAtAddress);
								break;
							case REDEEMED:
							case REFUNDED:
							case CANCELLED:
								cachedInfo.currentSummaries.remove(offerAtAddress);
								cachedInfo.historicSummaries.put(offerAtAddress, offerSummary);
								break;
							case TRADING:
								cachedInfo.currentSummaries.remove(offerAtAddress);
								cachedInfo.historicSummaries.remove(offerAtAddress);
								break;
						}
					}

					// Remove any historic offers that are over 24 hours old
					final long tooOldTimestamp = NTP.getTime() - 24 * 60 * 60 * 1000L;
					cachedInfo.historicSummaries.values().removeIf(historicSummary -> historicSummary.getTimestamp() < tooOldTimestamp);
				}

				// Notify sessions
				for (Session session : getSessions()) {
					// Only send if this session has this/no preferred blockchain
					String preferredBlockchain = sessionBlockchain.get(session);
					if (preferredBlockchain == null || preferredBlockchain.equals(blockchain.name()))
						sendOfferSummaries(session, crossChainOfferSummaries);
				}
			}
		} catch (DataException e) {
			// No output this time
		}
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		final boolean includeHistoric = queryParams.containsKey("includeHistoric");
		final boolean excludeInitialData = queryParams.containsKey("excludeInitialData");

		List<String> foreignBlockchains = queryParams.get("foreignBlockchain");
		final String foreignBlockchain = (foreignBlockchains == null || foreignBlockchains.isEmpty()) ? null : foreignBlockchains.get(0);

		// Make sure blockchain (if any) is valid
		if (foreignBlockchain != null && SupportedBlockchain.fromString(foreignBlockchain) == null) {
			session.close(4003, "unknown blockchain: " + foreignBlockchain);
			return;
		}

		// Save session's preferred blockchain, if given
		if (foreignBlockchain != null)
			sessionBlockchain.put(session, foreignBlockchain);

		List<CrossChainOfferSummary> crossChainOfferSummaries = new ArrayList<>();

		// We might need to exclude the initial data from the response
		if (!excludeInitialData) {
			synchronized (cachedInfoByBlockchain) {
				Collection<CachedOfferInfo> cachedInfos;
				if (foreignBlockchain == null)
					// No preferred blockchain, so iterate through all of them
					cachedInfos = cachedInfoByBlockchain.values();
				else
					cachedInfos = Collections.singleton(cachedInfoByBlockchain.computeIfAbsent(foreignBlockchain, k -> new CachedOfferInfo()));

				for (CachedOfferInfo cachedInfo : cachedInfos) {
					crossChainOfferSummaries.addAll(cachedInfo.currentSummaries.values());
					if (includeHistoric)
						crossChainOfferSummaries.addAll(cachedInfo.historicSummaries.values());
				}
			}
		}

		if (!sendOfferSummaries(session, crossChainOfferSummaries)) {
			session.close(4002, "websocket issue");
			return;
		}

		super.onWebSocketConnect(session);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		// clean up
		sessionBlockchain.remove(session);
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* ignored */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		if (Objects.equals(message, "ping") && session.isOpen()) {
			session.getRemote().sendString("pong", WriteCallback.NOOP);
		}
	}

	private boolean sendOfferSummaries(Session session, List<CrossChainOfferSummary> crossChainOfferSummaries) {
		if (session.isOpen()) {
			try {
				StringWriter stringWriter = new StringWriter();
				marshall(stringWriter, crossChainOfferSummaries);
				String output = stringWriter.toString();

				// Updated for Jetty 10 async send pattern
				session.getRemote().sendString(output, WriteCallback.NOOP);
				return true;
			} catch (IOException e) {
				return false;
			}
		}
		return false;
	}

	private static void populateCurrentSummaries(Repository repository) throws DataException {
		Boolean isFinished = Boolean.FALSE;
		Long expectedValue = (long) AcctMode.OFFERING.value;

		for (SupportedBlockchain blockchain : SupportedBlockchain.values()) {
			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = SupportedBlockchain.getFilteredAcctMap(blockchain);
			CachedOfferInfo cachedInfo = cachedInfoByBlockchain.computeIfAbsent(blockchain.name(), k -> new CachedOfferInfo());

			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();
				Integer dataByteOffset = acct.getModeByteOffset();

				List<ATStateData> initialAtStates = repository.getATRepository().getMatchingFinalATStates(codeHash, null, null,
						isFinished, dataByteOffset, expectedValue, null, null, null, null);

				if (initialAtStates == null)
					throw new DataException("Couldn't fetch current trades from repository");

				// Save initial AT modes
				cachedInfo.previousAtModes.putAll(initialAtStates.stream().collect(Collectors.toMap(ATStateData::getATAddress, atState -> AcctMode.OFFERING)));

				// Convert to offer summaries
				cachedInfo.currentSummaries.putAll(produceSummaries(repository, acct, initialAtStates, null).stream()
						.collect(Collectors.toMap(CrossChainOfferSummary::getQortalAtAddress, offerSummary -> offerSummary)));
			}
		}
	}

	private static void populateHistoricSummaries(Repository repository) throws DataException {
		// We want REDEEMED/REFUNDED/CANCELLED trades over the last 24 hours
		long timestamp = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
		int minimumFinalHeight = repository.getBlockRepository().getHeightFromTimestamp(timestamp);

		if (minimumFinalHeight == 0)
			throw new DataException("Couldn't fetch block timestamp from repository");

		Boolean isFinished = Boolean.TRUE;
		++minimumFinalHeight; // because height is just *before* timestamp

		for (SupportedBlockchain blockchain : SupportedBlockchain.values()) {
			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = SupportedBlockchain.getFilteredAcctMap(blockchain);
			CachedOfferInfo cachedInfo = cachedInfoByBlockchain.computeIfAbsent(blockchain.name(), k -> new CachedOfferInfo());

			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();

				List<ATStateData> historicAtStates = repository.getATRepository().getMatchingFinalATStates(codeHash, null, null,
						isFinished, null, null, minimumFinalHeight, null, null, null);

				if (historicAtStates == null)
					throw new DataException("Couldn't fetch historic trades from repository");

				for (ATStateData historicAtState : historicAtStates) {
					CrossChainOfferSummary historicOfferSummary = produceSummary(repository, acct, historicAtState, null, null);
					if (!isHistoric.test(historicOfferSummary))
						continue;

					// Add summary to initial burst
					cachedInfo.historicSummaries.put(historicOfferSummary.getQortalAtAddress(), historicOfferSummary);

					// Save initial AT mode
					cachedInfo.previousAtModes.put(historicOfferSummary.getQortalAtAddress(), historicOfferSummary.getMode());
				}
			}
		}
	}

	private static CrossChainOfferSummary produceSummary(Repository repository, ACCT acct, ATStateData atState, CrossChainTradeData crossChainTradeData, Long timestamp) throws DataException {
		if (crossChainTradeData == null) {
			crossChainTradeData = acct.populateTradeData(repository, atState);
		}

		long atStateTimestamp;
		if (crossChainTradeData.mode == AcctMode.OFFERING)
			// We want when trade was created, not when it was last updated
			atStateTimestamp = crossChainTradeData.creationTimestamp;
		else
			atStateTimestamp = timestamp != null ? timestamp : repository.getBlockRepository().getTimestampFromHeight(atState.getHeight());

		return new CrossChainOfferSummary(crossChainTradeData, atStateTimestamp);
	}

	private static List<CrossChainOfferSummary> produceSummaries(Repository repository, ACCT acct, List<ATStateData> atStates, Long timestamp) throws DataException {
		List<CrossChainOfferSummary> offerSummaries = new ArrayList<>();
		for (ATStateData atState : atStates) {
			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atState);

			// Ignore trade if it has failed
			if (TradeBot.getInstance().isFailedTrade(repository, crossChainTradeData)) {
				continue;
			}
			offerSummaries.add(produceSummary(repository, acct, atState, crossChainTradeData, timestamp));
		}
		return offerSummaries;
	}
}