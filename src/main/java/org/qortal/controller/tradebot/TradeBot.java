package org.qortal.controller.tradebot;

import com.google.common.primitives.Longs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.ECKey;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.controller.Controller;
import org.qortal.controller.Synchronizer;
import org.qortal.controller.arbitrary.PeerMessage;
import org.qortal.controller.tradebot.AcctTradeBot.ResponseResult;
import org.qortal.crosschain.*;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.network.TradePresenceData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.gui.SysTray;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.GetTradePresencesMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.TradePresencesMessage;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBImportExport;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.utils.ByteArray;
import org.qortal.utils.NTP;

import java.awt.TrayIcon.MessageType;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Performing cross-chain trading steps on behalf of user.
 * <p>
 * We deal with three different independent state-spaces here:
 * <ul>
 * 	<li>Qortal blockchain</li>
 * 	<li>Foreign blockchain</li>
 * 	<li>Trade-bot entries</li>
 * </ul>
 */
public class TradeBot implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(TradeBot.class);
	private static final Random RANDOM = new SecureRandom();

	/** Maximum lifetime of trade presence timestamp. 30 mins in ms. */
	private static final long PRESENCE_LIFETIME = 30 * 60 * 1000L;
	/** How soon before expiry of our own trade presence timestamp that we want to trigger renewal. 5 mins in ms. */
	private static final long EARLY_RENEWAL_PERIOD = 5 * 60 * 1000L;
	/** Trade presence timestamps are rounded up to this nearest interval. Bigger values improve grouping of entries in [GET_]TRADE_PRESENCES network messages. 15 mins in ms. */
	private static final long EXPIRY_ROUNDING = 15 * 60 * 1000L;
	/** How often we want to broadcast our list of all known trade presences to peers. 5 mins in ms. */
	private static final long PRESENCE_BROADCAST_INTERVAL = 5 * 60 * 1000L;

	public interface StateNameAndValueSupplier {
		public String getState();
		public int getStateValue();
	}

	public static class StateChangeEvent implements Event {
		private final TradeBotData tradeBotData;

		public StateChangeEvent(TradeBotData tradeBotData) {
			this.tradeBotData = tradeBotData;
		}

		public TradeBotData getTradeBotData() {
			return this.tradeBotData;
		}
	}

	public static class TradePresenceEvent implements Event {
		private final TradePresenceData tradePresenceData;

		public TradePresenceEvent(TradePresenceData tradePresenceData) {
			this.tradePresenceData = tradePresenceData;
		}

		public TradePresenceData getTradePresenceData() {
			return this.tradePresenceData;
		}
	}

	private static final Map<Class<? extends ACCT>, Supplier<AcctTradeBot>> acctTradeBotSuppliers = new HashMap<>();
	static {
		acctTradeBotSuppliers.put(BitcoinACCTv1.class, BitcoinACCTv1TradeBot::getInstance);
		acctTradeBotSuppliers.put(BitcoinACCTv3.class, BitcoinACCTv3TradeBot::getInstance);
		acctTradeBotSuppliers.put(LitecoinACCTv1.class, LitecoinACCTv1TradeBot::getInstance);
		acctTradeBotSuppliers.put(LitecoinACCTv3.class, LitecoinACCTv3TradeBot::getInstance);
		acctTradeBotSuppliers.put(DogecoinACCTv1.class, DogecoinACCTv1TradeBot::getInstance);
		acctTradeBotSuppliers.put(DogecoinACCTv3.class, DogecoinACCTv3TradeBot::getInstance);
		acctTradeBotSuppliers.put(DigibyteACCTv3.class, DigibyteACCTv3TradeBot::getInstance);
		acctTradeBotSuppliers.put(RavencoinACCTv3.class, RavencoinACCTv3TradeBot::getInstance);
		acctTradeBotSuppliers.put(PirateChainACCTv3.class, PirateChainACCTv3TradeBot::getInstance);
	}

	private static TradeBot instance;

	private final Map<ByteArray, Long> ourTradePresenceTimestampsByPubkey = Collections.synchronizedMap(new HashMap<>());
	private final List<TradePresenceData> pendingTradePresences = Collections.synchronizedList(new ArrayList<>());

	private final Map<ByteArray, TradePresenceData> allTradePresencesByPubkey = Collections.synchronizedMap(new HashMap<>());
	private Map<ByteArray, TradePresenceData> safeAllTradePresencesByPubkey = Collections.emptyMap();
	private long nextTradePresenceBroadcastTimestamp = 0L;

	private Map<String, Long> failedTrades = new HashMap<>();
	private Map<String, Long> validTrades = new HashMap<>();

	private TradeBot() {

		tradePresenceMessageScheduler.scheduleAtFixedRate( this::processTradePresencesMessages, 60, 1, TimeUnit.SECONDS);

		EventBus.INSTANCE.addListener(event -> TradeBot.getInstance().listen(event));
	}

	public static synchronized TradeBot getInstance() {
		if (instance == null)
			instance = new TradeBot();

		return instance;
	}

	public ACCT getAcctUsingAtData(ATData atData) {
		byte[] codeHash = atData.getCodeHash();
		if (codeHash == null)
			return null;

		return SupportedBlockchain.getAcctByCodeHash(codeHash);
	}

	public CrossChainTradeData populateTradeData(Repository repository, ATData atData) throws DataException {
		ACCT acct = this.getAcctUsingAtData(atData);
		if (acct == null)
			return null;

		return acct.populateTradeData(repository, atData);
	}

	/**
	 * Creates a new trade-bot entry from the "Bob" viewpoint,
	 * i.e. OFFERing QORT in exchange for foreign blockchain currency.
	 * <p>
	 * Generates:
	 * <ul>
	 * 	<li>new 'trade' private key</li>
	 * 	<li>secret(s)</li>
	 * </ul>
	 * Derives:
	 * <ul>
	 * 	<li>'native' (as in Qortal) public key, public key hash, address (starting with Q)</li>
	 * 	<li>'foreign' public key, public key hash</li>
	 *	<li>hash(es) of secret(s)</li>
	 * </ul>
	 * A Qortal AT is then constructed including the following as constants in the 'data segment':
	 * <ul>
	 * 	<li>'native' (Qortal) 'trade' address - used to MESSAGE AT</li>
	 * 	<li>'foreign' public key hash - used by Alice's to allow redeem of currency on foreign blockchain</li>
	 * 	<li>hash(es) of secret(s) - used by AT (optional) and foreign blockchain as needed</li>
	 * 	<li>QORT amount on offer by Bob</li>
	 * 	<li>foreign currency amount expected in return by Bob (from Alice)</li>
	 * 	<li>trading timeout, in case things go wrong and everyone needs to refund</li>
	 * </ul>
	 * Returns a DEPLOY_AT transaction that needs to be signed and broadcast to the Qortal network.
	 * <p>
	 * Trade-bot will wait for Bob's AT to be deployed before taking next step.
	 * <p>
	 * @param repository
	 * @param tradeBotCreateRequest
	 * @return raw, unsigned DEPLOY_AT transaction
	 * @throws DataException
	 */
	public byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException {
		// Fetch latest ACCT version for requested foreign blockchain
		ACCT acct = tradeBotCreateRequest.foreignBlockchain.getLatestAcct();

		AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
		if (acctTradeBot == null)
			return null;

		return acctTradeBot.createTrade(repository, tradeBotCreateRequest);
	}

	/**
	 * Creates a trade-bot entry from the 'Alice' viewpoint,
	 * i.e. matching foreign blockchain currency to an existing QORT offer.
	 * <p>
	 * Requires a chosen trade offer from Bob, passed by <tt>crossChainTradeData</tt>
	 * and access to a foreign blockchain wallet via <tt>foreignKey</tt>.
	 * <p>
	 * @param repository
	 * @param crossChainTradeData chosen trade OFFER that Alice wants to match
	 * @param foreignKey foreign blockchain wallet key
	 * @throws DataException
	 */
	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct,
			CrossChainTradeData crossChainTradeData, String foreignKey, String receivingAddress) throws DataException {
		AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
		if (acctTradeBot == null) {
			LOGGER.debug(() -> String.format("Couldn't find ACCT trade-bot for AT %s", atData.getATAddress()));
			return ResponseResult.NETWORK_ISSUE;
		}

		// Check Alice doesn't already have an existing, on-going trade-bot entry for this AT.
		if (repository.getCrossChainRepository().existsAliceTradeWithAtExcludingStates(atData.getATAddress(), acctTradeBot.getEndStates()))
			return ResponseResult.TRADE_ALREADY_EXISTS;

		return acctTradeBot.startResponse(repository, atData, acct, crossChainTradeData, foreignKey, receivingAddress);
	}

	/**
	 * Creates a trade-bot entries from the 'Alice' viewpoint,
	 * i.e. matching foreign blockchain currency to existing QORT offers.
	 * <p>
	 * Requires chosen trade offers from Bob, passed by <tt>crossChainTradeData</tt>
	 * and access to a foreign blockchain wallet via <tt>foreignKey</tt>.
	 * <p>
	 * @param repository
	 * @param crossChainTradeDataList chosen trade OFFERs that Alice wants to match
	 * @param receiveAddress Alice's Qortal address to receive her QORT
	 * @param foreignKey foreign blockchain wallet key
	 * @param bitcoiny
	 * @throws DataException
	 */
	public ResponseResult startResponseMultiple(
			Repository repository,
			ACCT acct,
			List<CrossChainTradeData> crossChainTradeDataList,
			String receiveAddress,
			String foreignKey,
			Bitcoiny bitcoiny) throws DataException {
		AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
		if (acctTradeBot == null) {
			LOGGER.debug(() -> String.format("Couldn't find ACCT trade-bot for %s", acct.getBlockchain()));
			return ResponseResult.NETWORK_ISSUE;
		}

		for( CrossChainTradeData tradeData : crossChainTradeDataList) {
			// Check Alice doesn't already have an existing, on-going trade-bot entry for this AT.
			if (repository.getCrossChainRepository().existsAliceTradeWithAtExcludingStates(tradeData.qortalAtAddress, acctTradeBot.getEndStates()))
				return ResponseResult.TRADE_ALREADY_EXISTS;
		}
		return TradeBotUtils.startResponseMultiple(repository, acct, crossChainTradeDataList, receiveAddress, foreignKey, bitcoiny);
	}

	public boolean deleteEntry(Repository repository, byte[] tradePrivateKey) throws DataException {
		TradeBotData tradeBotData = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
		if (tradeBotData == null)
			// Can't delete what we don't have!
			return false;

		boolean canDelete = false;

		ACCT acct = SupportedBlockchain.getAcctByName(tradeBotData.getAcctName());
		if (acct == null)
			// We can't/no longer support this ACCT
			canDelete = true;
		else {
			AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
			canDelete = acctTradeBot == null || acctTradeBot.canDelete(repository, tradeBotData);
		}

		if (canDelete) {
			repository.getCrossChainRepository().delete(tradePrivateKey);
			repository.saveChanges();
		}

		return canDelete;
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof Synchronizer.NewChainTipEvent))
			return;

		// Don't process trade bots or broadcast presence timestamps if our chain is more than 60 minutes old
		final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
		if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
			return;

		synchronized (this) {
			expireOldPresenceTimestamps();

			List<TradeBotData> allTradeBotData;

			try (final Repository repository = RepositoryManager.getRepository()) {
				allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			} catch (DataException e) {
				LOGGER.error("Couldn't run trade bot due to repository issue", e);
				return;
			}

			for (TradeBotData tradeBotData : allTradeBotData)
				try (final Repository repository = RepositoryManager.getRepository()) {
					// Find ACCT-specific trade-bot for this entry
					ACCT acct = SupportedBlockchain.getAcctByName(tradeBotData.getAcctName());
					if (acct == null) {
						LOGGER.debug(() -> String.format("Couldn't find ACCT matching name %s", tradeBotData.getAcctName()));
						continue;
					}

					AcctTradeBot acctTradeBot = findTradeBotForAcct(acct);
					if (acctTradeBot == null) {
						LOGGER.debug(() -> String.format("Couldn't find ACCT trade-bot matching name %s", tradeBotData.getAcctName()));
						continue;
					}

					acctTradeBot.progress(repository, tradeBotData);
				} catch (DataException e) {
					LOGGER.error("Couldn't run trade bot due to repository issue", e);
				} catch (ForeignBlockchainException e) {
					LOGGER.warn(() -> String.format("Foreign blockchain issue processing trade-bot entry for AT %s: %s", tradeBotData.getAtAddress(), e.getMessage()));
				}

			broadcastPresenceTimestamps();
		}
	}

	public static byte[] generateTradePrivateKey() {
		// The private key is used for both Curve25519 and secp256k1 so needs to be valid for both.
		// Curve25519 accepts any seed, so generate a valid secp256k1 key and use that.
		return new ECKey().getPrivKeyBytes();
	}

	public static byte[] deriveTradeNativePublicKey(byte[] privateKey) {
		return Crypto.toPublicKey(privateKey);
	}

	public static byte[] deriveTradeForeignPublicKey(byte[] privateKey) {
		return ECKey.fromPrivate(privateKey).getPubKey();
	}

	/*package*/ public static byte[] generateSecret() {
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		return secret;
	}

	/*package*/ static void backupTradeBotData(Repository repository, List<TradeBotData> additional) {
		// Attempt to backup the trade bot data. This an optional step and doesn't impact trading, so don't throw an exception on failure
		try {
			LOGGER.info("About to backup trade bot data...");
			HSQLDBImportExport.backupTradeBotStates(repository, additional);
		} catch (DataException e) {
			LOGGER.info(String.format("Repository issue when exporting trade bot data: %s", e.getMessage()));
		}
	}

	/** Updates trade-bot entry to new state, with current timestamp, logs message and notifies state-change listeners. */
	/*package*/ static void updateTradeBotState(Repository repository, TradeBotData tradeBotData,
			String newState, int newStateValue, Supplier<String> logMessageSupplier) throws DataException {
		tradeBotData.setState(newState);
		tradeBotData.setStateValue(newStateValue);
		tradeBotData.setTimestamp(NTP.getTime());
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();

		if (Settings.getInstance().isTradebotSystrayEnabled())
			SysTray.getInstance().showMessage("Trade-Bot", String.format("%s: %s", tradeBotData.getAtAddress(), newState), MessageType.INFO);

		if (logMessageSupplier != null)
			LOGGER.info(logMessageSupplier.get());

		LOGGER.debug(() -> String.format("new state for trade-bot entry based on AT %s: %s", tradeBotData.getAtAddress(), newState));

		notifyStateChange(tradeBotData);
	}

	/** Updates trade-bot entry to new state, with current timestamp, logs message and notifies state-change listeners. */
	/*package*/ static void updateTradeBotState(Repository repository, TradeBotData tradeBotData, StateNameAndValueSupplier newStateSupplier, Supplier<String> logMessageSupplier) throws DataException {
		updateTradeBotState(repository, tradeBotData, newStateSupplier.getState(), newStateSupplier.getStateValue(), logMessageSupplier);
	}

	/** Updates trade-bot entry to new state, with current timestamp, logs message and notifies state-change listeners. */
	/*package*/ static void updateTradeBotState(Repository repository, TradeBotData tradeBotData, Supplier<String> logMessageSupplier) throws DataException {
		updateTradeBotState(repository, tradeBotData, tradeBotData.getState(), tradeBotData.getStateValue(), logMessageSupplier);
	}

	/*package*/ static void notifyStateChange(TradeBotData tradeBotData) {
		StateChangeEvent stateChangeEvent = new StateChangeEvent(tradeBotData);
		EventBus.INSTANCE.notify(stateChangeEvent);
	}

	/*package*/ static AcctTradeBot findTradeBotForAcct(ACCT acct) {
		Supplier<AcctTradeBot> acctTradeBotSupplier = acctTradeBotSuppliers.get(acct.getClass());
		if (acctTradeBotSupplier == null)
			return null;

		return acctTradeBotSupplier.get();
	}

	// PRESENCE-related

	public Collection<TradePresenceData> getAllTradePresences() {
		return this.safeAllTradePresencesByPubkey.values();
	}

	/** Trade presence timestamps expire in the 'future' so any that reach 'now' have expired and are removed. */
	private void expireOldPresenceTimestamps() {
		long now = NTP.getTime();

		int allRemovedCount = 0;
		synchronized (this.allTradePresencesByPubkey) {
			int preRemoveCount = this.allTradePresencesByPubkey.size();
			this.allTradePresencesByPubkey.values().removeIf(tradePresenceData -> tradePresenceData.getTimestamp() <= now);
			allRemovedCount = this.allTradePresencesByPubkey.size() - preRemoveCount;
		}

		int ourRemovedCount = 0;
		synchronized (this.ourTradePresenceTimestampsByPubkey) {
			int preRemoveCount = this.ourTradePresenceTimestampsByPubkey.size();
			this.ourTradePresenceTimestampsByPubkey.values().removeIf(timestamp -> timestamp < now);
			ourRemovedCount = this.ourTradePresenceTimestampsByPubkey.size() - preRemoveCount;
		}

		if (allRemovedCount > 0)
			LOGGER.debug("Removed {} expired trade presences, of which {} ours", allRemovedCount, ourRemovedCount);
	}

	/*package*/ void updatePresence(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException {
		String atAddress = tradeBotData.getAtAddress();

		PrivateKeyAccount tradeNativeAccount = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
		String signerAddress = tradeNativeAccount.getAddress();

		/*
		* There's no point in Alice trying to broadcast presence for an AT that isn't locked to her,
		* as other peers won't be able to verify as signing public key isn't yet in the AT's data segment.
		*/
		if (!signerAddress.equals(tradeData.qortalCreatorTradeAddress) && !signerAddress.equals(tradeData.qortalPartnerAddress)) {
			// Signer is neither Bob, nor trade locked to Alice
			LOGGER.trace("Can't provide trade presence for our AT {} as it's not yet locked to Alice", atAddress);
			return;
		}

		long now = NTP.getTime();
		long newExpiry = generateExpiry(now);
		ByteArray pubkeyByteArray = ByteArray.wrap(tradeNativeAccount.getPublicKey());

		// If map entry's timestamp is missing, or within early renewal period, use the new expiry - otherwise use existing timestamp.
		synchronized (this.ourTradePresenceTimestampsByPubkey) {
			Long currentTimestamp = this.ourTradePresenceTimestampsByPubkey.get(pubkeyByteArray);

			if (currentTimestamp != null && currentTimestamp - now > EARLY_RENEWAL_PERIOD) {
				// timestamp still good
				LOGGER.trace("Current trade presence timestamp {} still good for our trade {}", currentTimestamp, atAddress);
				return;
			}

			this.ourTradePresenceTimestampsByPubkey.put(pubkeyByteArray, newExpiry);
		}

		// Create signature
		byte[] signature = tradeNativeAccount.sign(Longs.toByteArray(newExpiry));

		// Add new trade presence to queue to be broadcast around network
		TradePresenceData tradePresenceData = new TradePresenceData(newExpiry, tradeNativeAccount.getPublicKey(), signature, atAddress);
		this.pendingTradePresences.add(tradePresenceData);

		this.allTradePresencesByPubkey.put(pubkeyByteArray, tradePresenceData);
		rebuildSafeAllTradePresences();

		LOGGER.trace("New trade presence timestamp {} for our trade {}", newExpiry, atAddress);

		EventBus.INSTANCE.notify(new TradePresenceEvent(tradePresenceData));
	}

	private void rebuildSafeAllTradePresences() {
		synchronized (this.allTradePresencesByPubkey) {
			// Collect into a *new* unmodifiable map.
			this.safeAllTradePresencesByPubkey = Map.copyOf(this.allTradePresencesByPubkey);
		}
	}

	private void broadcastPresenceTimestamps() {
		// If we have new trade presences that are pending broadcast, send those as a priority
		if (!this.pendingTradePresences.isEmpty()) {
			// Create a copy for Network to safely use in another thread
			List<TradePresenceData> safeTradePresences;
			synchronized (this.pendingTradePresences) {
				safeTradePresences = List.copyOf(this.pendingTradePresences);
				this.pendingTradePresences.clear();
			}

			LOGGER.debug("Broadcasting {} new trade presences", safeTradePresences.size());

			TradePresencesMessage tradePresencesMessage = new TradePresencesMessage(safeTradePresences);
			Network.getInstance().broadcast(peer -> tradePresencesMessage);

			return;
		}

		// As we have no new trade presences, check whether it's time to do a general broadcast
		Long now = NTP.getTime();
		if (now == null || now < nextTradePresenceBroadcastTimestamp)
			return;

		nextTradePresenceBroadcastTimestamp = now + PRESENCE_BROADCAST_INTERVAL;

		List<TradePresenceData> safeTradePresences = List.copyOf(this.safeAllTradePresencesByPubkey.values());

		LOGGER.debug("Broadcasting all {} known trade presences. Next broadcast timestamp: {}",
				safeTradePresences.size(), nextTradePresenceBroadcastTimestamp
		);

		GetTradePresencesMessage getTradePresencesMessage = new GetTradePresencesMessage(safeTradePresences);
		Network.getInstance().broadcast(peer -> getTradePresencesMessage);
	}

	// Network message processing

	public void onGetTradePresencesMessage(Peer peer, Message message) {
		GetTradePresencesMessage getTradePresencesMessage = (GetTradePresencesMessage) message;

		List<TradePresenceData> peersTradePresences = getTradePresencesMessage.getTradePresences();

		// Create mutable copy from safe snapshot
		Map<ByteArray, TradePresenceData> entriesUnknownToPeer = new HashMap<>(this.safeAllTradePresencesByPubkey);
		int knownCount = entriesUnknownToPeer.size();

		for (TradePresenceData peersTradePresence : peersTradePresences) {
			ByteArray pubkeyByteArray = ByteArray.wrap(peersTradePresence.getPublicKey());

			TradePresenceData ourEntry = entriesUnknownToPeer.get(pubkeyByteArray);

			if (ourEntry != null && ourEntry.getTimestamp() == peersTradePresence.getTimestamp())
				entriesUnknownToPeer.remove(pubkeyByteArray);
		}

		if (entriesUnknownToPeer.isEmpty())
			return;

		LOGGER.debug("Sending {} trade presences to peer {} after excluding their {} from known {}",
				entriesUnknownToPeer.size(), peer, peersTradePresences.size(), knownCount
		);

		// Send complement to peer
		List<TradePresenceData> safeTradePresences = List.copyOf(entriesUnknownToPeer.values());
		Message responseMessage = new TradePresencesMessage(safeTradePresences);
		if (!peer.sendMessage(responseMessage)) {
			peer.disconnect("failed to send TRADE_PRESENCES response");
			return;
		}
	}

	// List to collect messages
	private final List<PeerMessage> tradePresenceMessageList = new ArrayList<>();
	// Lock to synchronize access to the list
	private final Object tradePresenceMessageLock = new Object();

	// Scheduled executor service to process messages every second
	private final ScheduledExecutorService tradePresenceMessageScheduler = Executors.newScheduledThreadPool(1);

	public void onTradePresencesMessage(Peer peer, Message message) {

		synchronized (tradePresenceMessageLock) {
			tradePresenceMessageList.add(new PeerMessage(peer, message));
		}
	}

	public void processTradePresencesMessages() {

		try {
			List<PeerMessage> messagesToProcess;
			synchronized (tradePresenceMessageLock) {
				messagesToProcess = new ArrayList<>(tradePresenceMessageList);
				tradePresenceMessageList.clear();
			}

			if( messagesToProcess.isEmpty() ) return;

			Map<Peer, List<TradePresenceData>> tradePresencesByPeer = new HashMap<>(messagesToProcess.size());

			// map all trade presences from the messages to their peer
			for( PeerMessage peerMessage : messagesToProcess ) {
				TradePresencesMessage tradePresencesMessage = (TradePresencesMessage) peerMessage.getMessage();

				List<TradePresenceData> peersTradePresences = tradePresencesMessage.getTradePresences();

				tradePresencesByPeer.put(peerMessage.getPeer(), peersTradePresences);
			}

			long now = NTP.getTime();
			// Timestamps before this are too far into the past
			long pastThreshold = now;
			// Timestamps after this are too far into the future
			long futureThreshold = now + PRESENCE_LIFETIME;

			Map<ByteArray, Supplier<ACCT>> acctSuppliersByCodeHash = SupportedBlockchain.getAcctMap();

			int newCount = 0;

			Map<String, List<Peer>> peersByAtAddress = new HashMap<>(tradePresencesByPeer.size());
			Map<String, TradePresenceData> tradePresenceByAtAddress = new HashMap<>(tradePresencesByPeer.size());

			// for each batch of trade presence data from a peer, validate and populate the maps declared above
			for ( Map.Entry<Peer, List<TradePresenceData>> entry: tradePresencesByPeer.entrySet()) {

				Peer peer = entry.getKey();

				for( TradePresenceData peersTradePresence : entry.getValue() ) {
					// TradePresenceData peersTradePresence
					long timestamp = peersTradePresence.getTimestamp();

					// Ignore if timestamp is out of bounds
					if (timestamp < pastThreshold || timestamp > futureThreshold) {
						if (timestamp < pastThreshold)
							LOGGER.trace("Ignoring trade presence {} from peer {} as timestamp {} is too old vs {}",
									peersTradePresence.getAtAddress(), peer, timestamp, pastThreshold
							);
						else
							LOGGER.trace("Ignoring trade presence {} from peer {} as timestamp {} is too new vs {}",
									peersTradePresence.getAtAddress(), peer, timestamp, pastThreshold
							);

						continue;
					}

					ByteArray pubkeyByteArray = ByteArray.wrap(peersTradePresence.getPublicKey());

					// Ignore if we've previously verified this timestamp+publickey combo or sent timestamp is older
					TradePresenceData existingTradeData = this.safeAllTradePresencesByPubkey.get(pubkeyByteArray);
					if (existingTradeData != null && timestamp <= existingTradeData.getTimestamp()) {
						if (timestamp == existingTradeData.getTimestamp())
							LOGGER.trace("Ignoring trade presence {} from peer {} as we have verified timestamp {} before",
									peersTradePresence.getAtAddress(), peer, timestamp
							);
						else
							LOGGER.trace("Ignoring trade presence {} from peer {} as timestamp {} is older than latest {}",
									peersTradePresence.getAtAddress(), peer, timestamp, existingTradeData.getTimestamp()
							);

						continue;
					}

					// Check timestamp signature
					byte[] timestampSignature = peersTradePresence.getSignature();
					byte[] timestampBytes = Longs.toByteArray(timestamp);
					byte[] publicKey = peersTradePresence.getPublicKey();
					if (!Crypto.verify(publicKey, timestampSignature, timestampBytes)) {
						LOGGER.trace("Ignoring trade presence {} from peer {} as signature failed to verify",
								peersTradePresence.getAtAddress(), peer
						);

						continue;
					}

					peersByAtAddress.computeIfAbsent(peersTradePresence.getAtAddress(), address -> new ArrayList<>()).add(peer);
					tradePresenceByAtAddress.put(peersTradePresence.getAtAddress(), peersTradePresence);
				}
			}

			if( tradePresenceByAtAddress.isEmpty() ) return;

			List<ATData> atDataList;
			try (final Repository repository = RepositoryManager.getRepository()) {
				atDataList = repository.getATRepository().fromATAddresses( new ArrayList<>(tradePresenceByAtAddress.keySet()) );
			} catch (DataException e) {
				LOGGER.error("Couldn't process TRADE_PRESENCES message due to repository issue", e);
				return;
			}

			Map<String, Supplier<ACCT>> supplierByAtAddress = new HashMap<>(atDataList.size());

			List<ATData> validatedAtDataList = new ArrayList<>(atDataList.size());

			// for each trade
			for( ATData atData : atDataList ) {

				TradePresenceData peersTradePresence = tradePresenceByAtAddress.get(atData.getATAddress());
				if (atData == null || atData.getIsFrozen() || atData.getIsFinished()) {
					if (atData == null)
						LOGGER.trace("Ignoring trade presence {} from peer as AT doesn't exist",
								peersTradePresence.getAtAddress()
						);
					else
						LOGGER.trace("Ignoring trade presence {} from peer as AT is frozen or finished",
								peersTradePresence.getAtAddress()
						);

					continue;
				}

				ByteArray atCodeHash = ByteArray.wrap(atData.getCodeHash());
				Supplier<ACCT> acctSupplier = acctSuppliersByCodeHash.get(atCodeHash);
				if (acctSupplier == null) {
					LOGGER.trace("Ignoring trade presence {} from peer as AT isn't a known ACCT?",
							peersTradePresence.getAtAddress()
					);

					continue;
				}
				validatedAtDataList.add(atData);
			}

			// populated data for each trade
			List<CrossChainTradeData> crossChainTradeDataList;

			// validated trade data grouped by code (cross chain coin)
			Map<ByteArray, List<ATData>> atDataByCodeHash
				= validatedAtDataList.stream().collect(
					Collectors.groupingBy(data -> ByteArray.wrap(data.getCodeHash())));

			try (final Repository repository = RepositoryManager.getRepository()) {

				crossChainTradeDataList = new ArrayList<>();

				// for each code (cross chain coin), get each trade, then populate trade data
				for( Map.Entry<ByteArray, List<ATData>> entry : atDataByCodeHash.entrySet() ) {

					Supplier<ACCT> acctSupplier = acctSuppliersByCodeHash.get(entry.getKey());

					crossChainTradeDataList.addAll(
						acctSupplier.get().populateTradeDataList(
								repository,
								entry.getValue()
						)
						.stream().filter( data -> data != null )
						.collect(Collectors.toList())
					);
				}
			} catch (DataException e) {
				LOGGER.error("Couldn't process TRADE_PRESENCES message due to repository issue", e);
				return;
			}

			// for each populated trade data, validate and fire event
			for( CrossChainTradeData tradeData : crossChainTradeDataList ) {

				List<Peer> peers = peersByAtAddress.get(tradeData.qortalAtAddress);

				for( Peer peer : peers ) {

					TradePresenceData peersTradePresence = tradePresenceByAtAddress.get(tradeData.qortalAtAddress);

					// Convert signer's public key to address form
					String signerAddress = peersTradePresence.getTradeAddress();

					// Signer's public key (in address form) must match Bob's / Alice's trade public key (in address form)
					if (!signerAddress.equals(tradeData.qortalCreatorTradeAddress) && !signerAddress.equals(tradeData.qortalPartnerAddress)) {
						LOGGER.trace("Ignoring trade presence {} from peer {} as signer isn't Alice or Bob?",
								peersTradePresence.getAtAddress(), peer
						);

						continue;
					}

					ByteArray pubkeyByteArray = ByteArray.wrap(peersTradePresence.getPublicKey());

					// This is new to us
					this.allTradePresencesByPubkey.put(pubkeyByteArray, peersTradePresence);
					++newCount;

					LOGGER.trace("Added trade presence {} from peer {} with timestamp {}",
							peersTradePresence.getAtAddress(), peer, tradeData.creationTimestamp
					);

					EventBus.INSTANCE.notify(new TradePresenceEvent(peersTradePresence));
				}
			}

			if (newCount > 0) {
				LOGGER.info("New trade presences: {}, all trade presences: {}", newCount, allTradePresencesByPubkey.size());
				rebuildSafeAllTradePresences();
			}
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	public void bridgePresence(long timestamp, byte[] publicKey, byte[] signature, String atAddress) {
		long expiry = generateExpiry(timestamp);
		ByteArray pubkeyByteArray = ByteArray.wrap(publicKey);

		TradePresenceData fakeTradePresenceData = new TradePresenceData(expiry, publicKey, signature, atAddress);

		// Only bridge if trade presence expiry timestamp is newer
		TradePresenceData computedTradePresenceData = this.allTradePresencesByPubkey.compute(pubkeyByteArray, (k, v) ->
				v == null || v.getTimestamp() < expiry ? fakeTradePresenceData : v
		);

		if (computedTradePresenceData == fakeTradePresenceData) {
			LOGGER.trace("Bridged PRESENCE transaction for trade {} with timestamp {}", atAddress, expiry);
			rebuildSafeAllTradePresences();

			EventBus.INSTANCE.notify(new TradePresenceEvent(fakeTradePresenceData));
		}
	}

	/** Decorates a CrossChainTradeData object with Alice / Bob trade-bot presence timestamp, if available. */
	public void decorateTradeDataWithPresence(CrossChainTradeData crossChainTradeData) {
		// Match by AT address, then check for Bob vs Alice
		this.safeAllTradePresencesByPubkey.values().stream()
				.filter(tradePresenceData -> tradePresenceData.getAtAddress().equals(crossChainTradeData.qortalAtAddress))
				.forEach(tradePresenceData -> {
					String signerAddress = tradePresenceData.getTradeAddress();

					// Signer's public key (in address form) must match Bob's / Alice's trade public key (in address form)
					if (signerAddress.equals(crossChainTradeData.qortalCreatorTradeAddress))
						crossChainTradeData.creatorPresenceExpiry = tradePresenceData.getTimestamp();
					else if (signerAddress.equals(crossChainTradeData.qortalPartnerAddress))
						crossChainTradeData.partnerPresenceExpiry = tradePresenceData.getTimestamp();
				});
	}

	/** Removes any trades that have had multiple failures */
	public List<CrossChainTradeData> removeFailedTrades(Repository repository, List<CrossChainTradeData> crossChainTrades) {
		Long now = NTP.getTime();
		if (now == null) {
			return crossChainTrades;
		}

		List<CrossChainTradeData> updatedCrossChainTrades = new ArrayList<>(crossChainTrades);
		int getMaxTradeOfferAttempts = Settings.getInstance().getMaxTradeOfferAttempts();

		for (CrossChainTradeData crossChainTradeData : crossChainTrades) {
			// We only care about trades in the OFFERING state
			if (crossChainTradeData.mode != AcctMode.OFFERING) {
				failedTrades.remove(crossChainTradeData.qortalAtAddress);
				validTrades.remove(crossChainTradeData.qortalAtAddress);
				continue;
			}

			// Return recently cached values if they exist
			Long failedTimestamp = failedTrades.get(crossChainTradeData.qortalAtAddress);
			if (failedTimestamp != null && now - failedTimestamp < 60 * 60 * 1000L) {
				updatedCrossChainTrades.remove(crossChainTradeData);
				//LOGGER.info("Removing cached failed trade AT {}", crossChainTradeData.qortalAtAddress);
				continue;
			}
			Long validTimestamp = validTrades.get(crossChainTradeData.qortalAtAddress);
			if (validTimestamp != null && now - validTimestamp < 60 * 60 * 1000L) {
				//LOGGER.info("NOT removing cached valid trade AT {}", crossChainTradeData.qortalAtAddress);
				continue;
			}

			try {
				List<TransactionData> transactions = repository.getTransactionRepository().getUnconfirmedTransactions(Arrays.asList(Transaction.TransactionType.MESSAGE), null, null, null, null);

				for (TransactionData transactionData : transactions) {
					// Treat as failed if buy attempt was more than 60 mins ago (as it's still in the OFFERING state)
					if (transactionData.getRecipient().equals(crossChainTradeData.qortalCreatorTradeAddress) && now - transactionData.getTimestamp() > 60*60*1000L) {
						failedTrades.put(crossChainTradeData.qortalAtAddress, now);
						updatedCrossChainTrades.remove(crossChainTradeData);
					} else {
						validTrades.put(crossChainTradeData.qortalAtAddress, now);
					}
				}

			} catch (DataException e) {
				LOGGER.info("Unable to determine failed state of AT {}", crossChainTradeData.qortalAtAddress);
            }
		}

		return updatedCrossChainTrades;
	}

	public boolean isFailedTrade(Repository repository, CrossChainTradeData crossChainTradeData) {
		List<CrossChainTradeData> results = removeFailedTrades(repository, Arrays.asList(crossChainTradeData));
		return results.isEmpty();
	}

	private long generateExpiry(long timestamp) {
		return ((timestamp - 1) / EXPIRY_ROUNDING) * EXPIRY_ROUNDING + PRESENCE_LIFETIME;
	}

}
