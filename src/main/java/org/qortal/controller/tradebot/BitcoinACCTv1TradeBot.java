package org.qortal.controller.tradebot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script.ScriptType;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.asset.Asset;
import org.qortal.crosschain.*;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

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
public class BitcoinACCTv1TradeBot implements AcctTradeBot {

	private static final Logger LOGGER = LogManager.getLogger(BitcoinACCTv1TradeBot.class);

	public enum State implements TradeBot.StateNameAndValueSupplier {
		BOB_WAITING_FOR_AT_CONFIRM(10, false, false),
		BOB_WAITING_FOR_MESSAGE(15, true, true),
		BOB_WAITING_FOR_P2SH_B(20, true, true),
		BOB_WAITING_FOR_AT_REDEEM(25, true, true),
		BOB_DONE(30, false, false),
		BOB_REFUNDED(35, false, false),

		ALICE_WAITING_FOR_P2SH_A(80, true, true),
		ALICE_WAITING_FOR_AT_LOCK(85, true, true),
		ALICE_WATCH_P2SH_B(90, true, true),
		ALICE_DONE(95, false, false),
		ALICE_REFUNDING_B(100, true, true),
		ALICE_REFUNDING_A(105, true, true),
		ALICE_REFUNDED(110, false, false);

		private static final Map<Integer, State> map = stream(State.values()).collect(toMap(state -> state.value, state -> state));

		public final int value;
		public final boolean requiresAtData;
		public final boolean requiresTradeData;

		State(int value, boolean requiresAtData, boolean requiresTradeData) {
			this.value = value;
			this.requiresAtData = requiresAtData;
			this.requiresTradeData = requiresTradeData;
		}

		public static State valueOf(int value) {
			return map.get(value);
		}

		@Override
		public String getState() {
			return this.name();
		}

		@Override
		public int getStateValue() {
			return this.value;
		}
	}

	/** Maximum time Bob waits for his AT creation transaction to be confirmed into a block. (milliseconds) */
	private static final long MAX_AT_CONFIRMATION_PERIOD = 24 * 60 * 60 * 1000L; // ms

	/** P2SH-B output amount to avoid dust threshold (3000 sats/kB). */
	private static final long P2SH_B_OUTPUT_AMOUNT = 1000L;

	private static BitcoinACCTv1TradeBot instance;

	private final List<String> endStates = Arrays.asList(State.BOB_DONE, State.BOB_REFUNDED, State.ALICE_DONE, State.ALICE_REFUNDING_A, State.ALICE_REFUNDING_B, State.ALICE_REFUNDED).stream()
			.map(State::name)
			.collect(Collectors.toUnmodifiableList());

	private BitcoinACCTv1TradeBot() {
	}

	public static synchronized BitcoinACCTv1TradeBot getInstance() {
		if (instance == null)
			instance = new BitcoinACCTv1TradeBot();

		return instance;
	}

	@Override
	public List<String> getEndStates() {
		return this.endStates;
	}

	/**
	 * Creates a new trade-bot entry from the "Bob" viewpoint, i.e. OFFERing QORT in exchange for BTC.
	 * <p>
	 * Generates:
	 * <ul>
	 * 	<li>new 'trade' private key</li>
	 * 	<li>secret-B</li>
	 * </ul>
	 * Derives:
	 * <ul>
	 * 	<li>'native' (as in Qortal) public key, public key hash, address (starting with Q)</li>
	 * 	<li>'foreign' (as in Bitcoin) public key, public key hash</li>
	 *	<li>HASH160 of secret-B</li>
	 * </ul>
	 * A Qortal AT is then constructed including the following as constants in the 'data segment':
	 * <ul>
	 * 	<li>'native'/Qortal 'trade' address - used as a MESSAGE contact</li>
	 * 	<li>'foreign'/Bitcoin public key hash - used by Alice's P2SH scripts to allow redeem</li>
	 * 	<li>HASH160 of secret-B - used by AT and P2SH to validate a potential secret-B</li>
	 * 	<li>QORT amount on offer by Bob</li>
	 * 	<li>BTC amount expected in return by Bob (from Alice)</li>
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
		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] secretB = TradeBot.generateSecret();
		byte[] hashOfSecretB = Crypto.hash160(secretB);

		byte[] tradeNativePublicKey = TradeBot.deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		// Convert Bitcoin receiving address into public key hash (we only support P2PKH at this time)
		Address bitcoinReceivingAddress;
		try {
			bitcoinReceivingAddress = Address.fromString(Bitcoin.getInstance().getNetworkParameters(), tradeBotCreateRequest.receivingAddress);
		} catch (AddressFormatException e) {
			throw new DataException("Unsupported Bitcoin receiving address: " + tradeBotCreateRequest.receivingAddress);
		}
		if (bitcoinReceivingAddress.getOutputScriptType() != ScriptType.P2PKH)
			throw new DataException("Unsupported Bitcoin receiving address: " + tradeBotCreateRequest.receivingAddress);

		byte[] bitcoinReceivingAccountInfo = bitcoinReceivingAddress.getHash();

		PublicKeyAccount creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);

		// Deploy AT
		long timestamp = NTP.getTime();
		byte[] reference = creator.getLastReference();
		long fee = 0L;
		byte[] signature = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, creator.getPublicKey(), fee, signature);

		String name = "QORT/BTC ACCT";
		String description = "QORT/BTC cross-chain trade";
		String aTType = "ACCT";
		String tags = "ACCT QORT BTC";
		byte[] creationBytes = BitcoinACCTv1.buildQortalAT(tradeNativeAddress, tradeForeignPublicKeyHash, hashOfSecretB, tradeBotCreateRequest.qortAmount,
				tradeBotCreateRequest.foreignAmount, tradeBotCreateRequest.tradeTimeout);
		long amount = tradeBotCreateRequest.fundingQortAmount;

		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, aTType, tags, creationBytes, amount, Asset.QORT);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		fee = deployAtTransaction.calcRecommendedFee();
		deployAtTransactionData.setFee(fee);

		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, BitcoinACCTv1.NAME,
				State.BOB_WAITING_FOR_AT_CONFIRM.name(), State.BOB_WAITING_FOR_AT_CONFIRM.value,
				creator.getAddress(), atAddress, timestamp, tradeBotCreateRequest.qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretB, hashOfSecretB,
				SupportedBlockchain.BITCOIN.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.foreignAmount, null, null, null, bitcoinReceivingAccountInfo);

		TradeBot.updateTradeBotState(repository, tradeBotData, () -> String.format("Built AT %s. Waiting for deployment", atAddress));

		// Return to user for signing and broadcast as we don't have their Qortal private key
		try {
			return DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform DEPLOY_AT transaction?", e);
		}
	}

	/**
	 * Creates a trade-bot entry from the 'Alice' viewpoint, i.e. matching BTC to an existing offer.
	 * <p>
	 * Requires a chosen trade offer from Bob, passed by <tt>crossChainTradeData</tt>
	 * and access to a Bitcoin wallet via <tt>xprv58</tt>.
	 * <p>
	 * The <tt>crossChainTradeData</tt> contains the current trade offer state
	 * as extracted from the AT's data segment.
	 * <p>
	 * Access to a funded wallet is via a Bitcoin BIP32 hierarchical deterministic key,
	 * passed via <tt>xprv58</tt>.
	 * <b>This key will be stored in your node's database</b>
	 * to allow trade-bot to create/fund the necessary P2SH transactions!
	 * However, due to the nature of BIP32 keys, it is possible to give the trade-bot
	 * only a subset of wallet access (see BIP32 for more details).
	 * <p>
	 * As an example, the xprv58 can be extract from a <i>legacy, password-less</i>
	 * Electrum wallet by going to the console tab and entering:<br>
	 * <tt>wallet.keystore.xprv</tt><br>
	 * which should result in a base58 string starting with either 'xprv' (for Bitcoin main-net)
	 * or 'tprv' for (Bitcoin test-net).
	 * <p>
	 * It is envisaged that the value in <tt>xprv58</tt> will actually come from a Qortal-UI-managed wallet.
	 * <p>
	 * If sufficient funds are available, <b>this method will actually fund the P2SH-A</b>
	 * with the Bitcoin amount expected by 'Bob'.
	 * <p>
	 * If the Bitcoin transaction is successfully broadcast to the network then the trade-bot entry
	 * is saved to the repository and the cross-chain trading process commences.
	 * <p>
	 * Trade-bot will wait for P2SH-A to confirm before taking next step.
	 * <p>
	 * @param repository
	 * @param crossChainTradeData chosen trade OFFER that Alice wants to match
	 * @param xprv58 funded wallet xprv in base58
	 * @return true if P2SH-A funding transaction successfully broadcast to Bitcoin network, false otherwise
	 * @throws DataException
	 */
	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct, CrossChainTradeData crossChainTradeData, String xprv58, String receivingAddress) throws DataException {
		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] secretA = TradeBot.generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);

		byte[] tradeNativePublicKey = TradeBot.deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
		byte[] receivingPublicKeyHash = Base58.decode(receivingAddress); // Actually the whole address, not just PKH

		// We need to generate lockTime-A: add tradeTimeout to now
		long now = NTP.getTime();
		int lockTimeA = crossChainTradeData.tradeTimeout * 60 + (int) (now / 1000L);

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, BitcoinACCTv1.NAME,
				State.ALICE_WAITING_FOR_P2SH_A.name(), State.ALICE_WAITING_FOR_P2SH_A.value,
				receivingAddress, crossChainTradeData.qortalAtAddress, now, crossChainTradeData.qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretA, hashOfSecretA,
				SupportedBlockchain.BITCOIN.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				crossChainTradeData.expectedForeignAmount, xprv58, null, lockTimeA, receivingPublicKeyHash);

		// Check we have enough funds via xprv58 to fund both P2SHs to cover expectedBitcoin
		String tradeForeignAddress = Bitcoin.getInstance().pkhToAddress(tradeForeignPublicKeyHash);

		long p2shFee;
		try {
			p2shFee = BitcoinyHtlcFees.fundingReserve(Bitcoin.getInstance(), now);
		} catch (ForeignBlockchainException e) {
			LOGGER.debug("Couldn't estimate Bitcoin fees?");
			return ResponseResult.NETWORK_ISSUE;
		}

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long fundsRequiredForP2shA = p2shFee /*funding P2SH-A*/ + crossChainTradeData.expectedForeignAmount - P2SH_B_OUTPUT_AMOUNT + p2shFee /*redeeming/refunding P2SH-A*/;
		long fundsRequiredForP2shB = p2shFee /*funding P2SH-B*/ + P2SH_B_OUTPUT_AMOUNT + p2shFee /*redeeming/refunding P2SH-B*/;
		long totalFundsRequired = fundsRequiredForP2shA + fundsRequiredForP2shB;

		// As buildSpend also adds a fee, this is more pessimistic than required
		Transaction fundingCheckTransaction;
		try { fundingCheckTransaction = Bitcoin.getInstance().buildSpend(xprv58, tradeForeignAddress, totalFundsRequired); }
		catch (ForeignBlockchainException e) { return ResponseResult.NETWORK_ISSUE; }
		if (fundingCheckTransaction == null)
			return ResponseResult.BALANCE_ISSUE;

		// P2SH-A to be funded
		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(tradeForeignPublicKeyHash, lockTimeA, crossChainTradeData.creatorForeignPKH, hashOfSecretA);
		String p2shAddress = Bitcoin.getInstance().deriveP2shAddress(redeemScriptBytes);

		// Fund P2SH-A

		// Do not include fee for funding transaction as this is covered by buildSpend()
		long amountA = crossChainTradeData.expectedForeignAmount - P2SH_B_OUTPUT_AMOUNT + p2shFee /*redeeming/refunding P2SH-A*/;

		Transaction p2shFundingTransaction;
		try { p2shFundingTransaction = Bitcoin.getInstance().buildSpend(tradeBotData.getForeignKey(), p2shAddress, amountA); }
		catch (ForeignBlockchainException e) { return ResponseResult.NETWORK_ISSUE; }
		if (p2shFundingTransaction == null) {
			LOGGER.debug("Unable to build P2SH-A funding transaction - lack of funds?");
			return ResponseResult.BALANCE_ISSUE;
		}

		try {
			Bitcoin.getInstance().broadcastTransaction(p2shFundingTransaction);
		} catch (ForeignBlockchainException e) {
			// We couldn't fund P2SH-A at this time
			LOGGER.debug("Couldn't broadcast P2SH-A funding transaction?");
			return ResponseResult.NETWORK_ISSUE;
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, () -> String.format("Funding P2SH-A %s. Waiting for confirmation", p2shAddress));

		return ResponseResult.OK;
	}

	@Override
	public boolean canDelete(Repository repository, TradeBotData tradeBotData) throws DataException {
		State tradeBotState = State.valueOf(tradeBotData.getStateValue());
		if (tradeBotState == null)
			return true;

		// If the AT doesn't exist then we might as well let the user tidy up
		if (!repository.getATRepository().exists(tradeBotData.getAtAddress()))
			return true;

		switch (tradeBotState) {
			case BOB_WAITING_FOR_AT_CONFIRM:
			case ALICE_DONE:
			case BOB_DONE:
			case ALICE_REFUNDED:
			case BOB_REFUNDED:
			case ALICE_REFUNDING_A:
				return true;

			default:
				return false;
		}
	}

	@Override
	public void progress(Repository repository, TradeBotData tradeBotData) throws DataException, ForeignBlockchainException {
		State tradeBotState = State.valueOf(tradeBotData.getStateValue());
		if (tradeBotState == null) {
			LOGGER.info(() -> String.format("Trade-bot entry for AT %s has invalid state?", tradeBotData.getAtAddress()));
			return;
		}

		ATData atData = null;
		CrossChainTradeData tradeData = null;

		if (tradeBotState.requiresAtData) {
			// Attempt to fetch AT data
			atData = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
			if (atData == null) {
				LOGGER.debug(() -> String.format("Unable to fetch trade AT %s from repository", tradeBotData.getAtAddress()));
				return;
			}

			if (tradeBotState.requiresTradeData) {
				tradeData = BitcoinACCTv1.getInstance().populateTradeData(repository, atData);
				if (tradeData == null) {
					LOGGER.warn(() -> String.format("Unable to fetch ACCT trade data for AT %s from repository", tradeBotData.getAtAddress()));
					return;
				}
			}
		}

		switch (tradeBotState) {
			case BOB_WAITING_FOR_AT_CONFIRM:
				handleBobWaitingForAtConfirm(repository, tradeBotData);
				break;

			case ALICE_WAITING_FOR_P2SH_A:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleAliceWaitingForP2shA(repository, tradeBotData, atData, tradeData);
				break;

			case BOB_WAITING_FOR_MESSAGE:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleBobWaitingForMessage(repository, tradeBotData, atData, tradeData);
				break;

			case ALICE_WAITING_FOR_AT_LOCK:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleAliceWaitingForAtLock(repository, tradeBotData, atData, tradeData);
				break;

			case BOB_WAITING_FOR_P2SH_B:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleBobWaitingForP2shB(repository, tradeBotData, atData, tradeData);
				break;

			case ALICE_WATCH_P2SH_B:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleAliceWatchingP2shB(repository, tradeBotData, atData, tradeData);
				break;

			case BOB_WAITING_FOR_AT_REDEEM:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleBobWaitingForAtRedeem(repository, tradeBotData, atData, tradeData);
				break;

			case ALICE_DONE:
			case BOB_DONE:
				break;

			case ALICE_REFUNDING_B:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleAliceRefundingP2shB(repository, tradeBotData, atData, tradeData);
				break;

			case ALICE_REFUNDING_A:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleAliceRefundingP2shA(repository, tradeBotData, atData, tradeData);
				break;

			case ALICE_REFUNDED:
			case BOB_REFUNDED:
				break;
		}
	}

	/**
	 * Trade-bot is waiting for Bob's AT to deploy.
	 * <p>
	 * If AT is deployed, then trade-bot's next step is to wait for MESSAGE from Alice.
	 */
	private void handleBobWaitingForAtConfirm(Repository repository, TradeBotData tradeBotData) throws DataException {
		if (!repository.getATRepository().exists(tradeBotData.getAtAddress())) {
			if (NTP.getTime() - tradeBotData.getTimestamp() <= MAX_AT_CONFIRMATION_PERIOD)
				return;

			// We've waited ages for AT to be confirmed into a block but something has gone awry.
			// After this long we assume transaction loss so give up with trade-bot entry too.
			tradeBotData.setState(State.BOB_REFUNDED.name());
			tradeBotData.setStateValue(State.BOB_REFUNDED.value);
			tradeBotData.setTimestamp(NTP.getTime());
			// We delete trade-bot entry here instead of saving, hence not using updateTradeBotState()
			repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
			repository.saveChanges();

			LOGGER.info(() -> String.format("AT %s never confirmed. Giving up on trade", tradeBotData.getAtAddress()));
			TradeBot.notifyStateChange(tradeBotData);
			return;
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_WAITING_FOR_MESSAGE,
				() -> String.format("AT %s confirmed ready. Waiting for trade message", tradeBotData.getAtAddress()));
	}

	/**
	 * Trade-bot is waiting for Alice's P2SH-A to confirm.
	 * <p>
	 * If P2SH-A is confirmed, then trade-bot's next step is to MESSAGE Bob's trade address with Alice's trade info.
	 * <p>
	 * It is possible between broadcast and confirmation of P2SH-A funding transaction, that Bob has cancelled his trade offer.
	 * If this is detected then trade-bot's next step is to wait until P2SH-A can refund back to Alice.
	 * <p>
	 * In normal operation, trade-bot send a zero-fee, PoW MESSAGE on Alice's behalf containing:
	 * <ul>
	 * 	<li>Alice's 'foreign'/Bitcoin public key hash - so Bob's trade-bot can derive P2SH-A address and check balance</li>
	 * 	<li>HASH160 of Alice's secret-A - also used to derive P2SH-A address</li>
	 * 	<li>lockTime of P2SH-A - also used to derive P2SH-A address, but also for other use later in the trading process</li>
	 * </ul>
	 * If MESSAGE transaction is successfully broadcast, trade-bot's next step is to wait until Bob's AT has locked trade to Alice only.
	 * @throws ForeignBlockchainException
	 */
	private void handleAliceWaitingForP2shA(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		if (aliceUnexpectedState(repository, tradeBotData, atData, crossChainTradeData))
			return;

		Bitcoin bitcoin = Bitcoin.getInstance();

		byte[] redeemScriptA = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getLockTimeA(), crossChainTradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
		String p2shAddressA = bitcoin.deriveP2shAddress(redeemScriptA);

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long minimumAmountA = crossChainTradeData.expectedForeignAmount - P2SH_B_OUTPUT_AMOUNT;
		BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

		switch (htlcStatusA) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// This shouldn't occur, but defensively check P2SH-B in case we haven't redeemed the AT
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WATCH_P2SH_B,
						() -> String.format("P2SH-A %s already spent? Defensively checking P2SH-B next", p2shAddressA));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDED,
						() -> String.format("P2SH-A %s already refunded. Trade aborted", p2shAddressA));
				return;

			case FUNDED:
				// Fall-through out of switch...
				break;
		}

		// P2SH-A funding confirmed

		// Attempt to send MESSAGE to Bob's Qortal trade address
		byte[] messageData = CrossChainUtils.buildOfferMessage(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret(), tradeBotData.getLockTimeA());
		String messageRecipient = crossChainTradeData.qortalCreatorTradeAddress;

		boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, messageData);
		if (!isMessageAlreadySent) {
			PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
			MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, messageRecipient, messageData, false, false);

			messageTransaction.computeNonce();
			messageTransaction.sign(sender);

			// reset repository state to prevent deadlock
			repository.discardChanges();
			ValidationResult result = messageTransaction.importAsUnconfirmed();

			if (result != ValidationResult.OK) {
				LOGGER.warn(() -> String.format("Unable to send MESSAGE to Bob's trade-bot %s: %s", messageRecipient, result.name()));
				return;
			}
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WAITING_FOR_AT_LOCK,
				() -> String.format("P2SH-A %s funding confirmed. Messaged %s. Waiting for AT %s to lock to us",
				p2shAddressA, messageRecipient, tradeBotData.getAtAddress()));
	}

	/**
	 * Trade-bot is waiting for MESSAGE from Alice's trade-bot, containing Alice's trade info.
	 * <p>
	 * It's possible Bob has cancelling his trade offer, receiving an automatic QORT refund,
	 * in which case trade-bot is done with this specific trade and finalizes on refunded state.
	 * <p>
	 * Assuming trade is still on offer, trade-bot checks the contents of MESSAGE from Alice's trade-bot.
	 * <p>
	 * Details from Alice are used to derive P2SH-A address and this is checked for funding balance.
	 * <p>
	 * Assuming P2SH-A has at least expected Bitcoin balance,
	 * Bob's trade-bot constructs a zero-fee, PoW MESSAGE to send to Bob's AT with more trade details.
	 * <p>
	 * On processing this MESSAGE, Bob's AT should switch into 'TRADE' mode and only trade with Alice.
	 * <p>
	 * Trade-bot's next step is to wait for P2SH-B, which will allow Bob to reveal his secret-B,
	 * needed by Alice to progress her side of the trade.
	 * @throws ForeignBlockchainException
	 */
	private void handleBobWaitingForMessage(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		// If AT has finished then Bob likely cancelled his trade offer
		if (atData.getIsFinished()) {
			TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_REFUNDED,
					() -> String.format("AT %s cancelled - trading aborted", tradeBotData.getAtAddress()));
			return;
		}

		Bitcoin bitcoin = Bitcoin.getInstance();

		String address = tradeBotData.getTradeNativeAddress();
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null, address, null, null, null);

		final byte[] originalLastTransactionSignature = tradeBotData.getLastTransactionSignature();

		// Skip past previously processed messages
		if (originalLastTransactionSignature != null)
			for (int i = 0; i < messageTransactionsData.size(); ++i)
				if (Arrays.equals(messageTransactionsData.get(i).getSignature(), originalLastTransactionSignature)) {
					messageTransactionsData.subList(0, i + 1).clear();
					break;
				}

		while (!messageTransactionsData.isEmpty()) {
			MessageTransactionData messageTransactionData = messageTransactionsData.remove(0);
			tradeBotData.setLastTransactionSignature(messageTransactionData.getSignature());

			if (messageTransactionData.isText())
				continue;

			// We're expecting: HASH160(secret-A), Alice's Bitcoin pubkeyhash and lockTime-A
			byte[] messageData = messageTransactionData.getData();
			BitcoinACCTv1.OfferMessageData offerMessageData = BitcoinACCTv1.extractOfferMessageData(messageData);
			if (offerMessageData == null)
				continue;

			byte[] aliceForeignPublicKeyHash = offerMessageData.partnerBitcoinPKH;
			byte[] hashOfSecretA = offerMessageData.hashOfSecretA;
			int lockTimeA = (int) offerMessageData.lockTimeA;

			// Determine P2SH-A address and confirm funded
			byte[] redeemScriptA = BitcoinyHTLC.buildScript(aliceForeignPublicKeyHash, lockTimeA, tradeBotData.getTradeForeignPublicKeyHash(), hashOfSecretA);
			String p2shAddressA = bitcoin.deriveP2shAddress(redeemScriptA);

			long feeTimestampA = calcP2shAFeeTimestamp(lockTimeA, crossChainTradeData.tradeTimeout);
			// Require a sufficient reserve before committing QORT to a new trade.
			final long minimumAmountA = Math.addExact(tradeBotData.getForeignAmount() - P2SH_B_OUTPUT_AMOUNT, BitcoinyHtlcFees.fundingReserve(bitcoin, feeTimestampA));

			BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

			switch (htlcStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
					// There might be another MESSAGE from someone else with an actually funded P2SH-A...
					continue;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// This shouldn't occur, but defensively bump to next state
					TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_WAITING_FOR_P2SH_B,
							() -> String.format("P2SH-A %s already spent? Defensively checking P2SH-B next", p2shAddressA));
					return;

				case REFUND_IN_PROGRESS:
				case REFUNDED:
					// This P2SH-A is burnt, but there might be another MESSAGE from someone else with an actually funded P2SH-A...
					continue;

				case FUNDED:
					// Fall-through out of switch...
					break;
			}

			BitcoinyHtlcFees.uniqueFunding(bitcoin.getUnspentOutputs(p2shAddressA, false), redeemScriptA, minimumAmountA);

			// Good to go - send MESSAGE to AT

			String aliceNativeAddress = Crypto.toAddress(messageTransactionData.getCreatorPublicKey());
			int lockTimeB = BitcoinACCTv1.calcLockTimeB(messageTransactionData.getTimestamp(), lockTimeA);

			// Build outgoing message, padding each part to 32 bytes to make it easier for AT to consume
			byte[] outgoingMessageData = BitcoinACCTv1.buildTradeMessage(aliceNativeAddress, aliceForeignPublicKeyHash, hashOfSecretA, lockTimeA, lockTimeB);
			String messageRecipient = tradeBotData.getAtAddress();

			boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, outgoingMessageData);
			if (!isMessageAlreadySent) {
				PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
				MessageTransaction outgoingMessageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, messageRecipient, outgoingMessageData, false, false);

				outgoingMessageTransaction.computeNonce();
				outgoingMessageTransaction.sign(sender);

				// reset repository state to prevent deadlock
				repository.discardChanges();
				ValidationResult result = outgoingMessageTransaction.importAsUnconfirmed();

				if (result != ValidationResult.OK) {
					LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", messageRecipient, result.name()));
					return;
				}
			}

			byte[] redeemScriptB = BitcoinyHTLC.buildScript(aliceForeignPublicKeyHash, lockTimeB, tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret());
			String p2shAddressB = bitcoin.deriveP2shAddress(redeemScriptB);

			TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_WAITING_FOR_P2SH_B,
					() -> String.format("Locked AT %s to %s. Waiting for P2SH-B %s", tradeBotData.getAtAddress(), aliceNativeAddress, p2shAddressB));

			return;
		}

		// Don't resave/notify if we don't need to
		if (tradeBotData.getLastTransactionSignature() != originalLastTransactionSignature)
			TradeBot.updateTradeBotState(repository, tradeBotData, null);
	}

	/**
	 * Trade-bot is waiting for Bob's AT to switch to TRADE mode and lock trade to Alice only.
	 * <p>
	 * It's possible that Bob has cancelled his trade offer in the mean time, or that somehow
	 * this process has taken so long that we've reached P2SH-A's locktime, or that someone else
	 * has managed to trade with Bob. In any of these cases, trade-bot switches to begin the refunding process.
	 * <p>
	 * Assuming Bob's AT is locked to Alice, trade-bot checks AT's state data to make sure it is correct.
	 * <p>
	 * If all is well, trade-bot then uses Bitcoin wallet to (token) fund P2SH-B.
	 * <p>
	 * If P2SH-B funding transaction is successfully broadcast to the Bitcoin network, trade-bot's next
	 * step is to watch for Bob revealing secret-B by redeeming P2SH-B.
	 * @throws ForeignBlockchainException
	 */
	private void handleAliceWaitingForAtLock(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		if (aliceUnexpectedState(repository, tradeBotData, atData, crossChainTradeData))
			return;

		Bitcoin bitcoin = Bitcoin.getInstance();
		int lockTimeA = tradeBotData.getLockTimeA();

		// Refund P2SH-A if we've passed lockTime-A
		if (NTP.getTime() >= tradeBotData.getLockTimeA() * 1000L) {
			byte[] redeemScriptA = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeA, crossChainTradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
			String p2shAddressA = bitcoin.deriveP2shAddress(redeemScriptA);

			long minimumAmountA = crossChainTradeData.expectedForeignAmount - P2SH_B_OUTPUT_AMOUNT;
			BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

			switch (htlcStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
					// This shouldn't occur, but defensively revert back to waiting for P2SH-A
					TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WAITING_FOR_P2SH_A,
							() -> String.format("P2SH-A %s no longer funded? Defensively checking P2SH-A next", p2shAddressA));
					return;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// This shouldn't occur, but defensively bump to next state
					TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WATCH_P2SH_B,
							() -> String.format("P2SH-A %s already spent? Defensively checking P2SH-B next", p2shAddressA));
					return;

				case REFUND_IN_PROGRESS:
				case REFUNDED:
					TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDED,
							() -> String.format("P2SH-A %s already refunded. Trade aborted", p2shAddressA));
					return;

				case FUNDED:
					// Fall-through out of switch...
					break;
			}

			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
					() -> atData.getIsFinished()
					? String.format("AT %s cancelled. Refunding P2SH-A %s - aborting trade", tradeBotData.getAtAddress(), p2shAddressA)
					: String.format("LockTime-A reached, refunding P2SH-A %s - aborting trade", p2shAddressA));

			return;
		}

		// We're waiting for AT to be in TRADE mode
		if (crossChainTradeData.mode != AcctMode.TRADING)
			return;

		// AT is in TRADE mode and locked to us as checked by aliceUnexpectedState() above

		// Alice needs to fund P2SH-B here

		// Find our MESSAGE to AT from previous state
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(tradeBotData.getTradeNativePublicKey(),
				crossChainTradeData.qortalCreatorTradeAddress, null, null, null);
		if (messageTransactionsData == null || messageTransactionsData.isEmpty()) {
			LOGGER.warn(() -> String.format("Unable to find our message to trade creator %s?", crossChainTradeData.qortalCreatorTradeAddress));
			return;
		}

		long recipientMessageTimestamp = messageTransactionsData.get(0).getTimestamp();
		int lockTimeB = BitcoinACCTv1.calcLockTimeB(recipientMessageTimestamp, lockTimeA);

		// Our calculated lockTime-B should match AT's calculated lockTime-B
		if (lockTimeB != crossChainTradeData.lockTimeB) {
			LOGGER.debug(() -> String.format("Trade AT lockTime-B '%d' doesn't match our lockTime-B '%d'", crossChainTradeData.lockTimeB, lockTimeB));
			// We'll eventually refund
			return;
		}

		byte[] redeemScriptB = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeB, crossChainTradeData.creatorForeignPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddressB = bitcoin.deriveP2shAddress(redeemScriptB);

		long feeTimestampB = calcP2shBFeeTimestamp(lockTimeA, lockTimeB);
		long p2shFeeB = BitcoinyHtlcFees.fundingReserve(bitcoin, feeTimestampB);

		// Have we funded P2SH-B already?
		final long minimumAmountB = P2SH_B_OUTPUT_AMOUNT;

		BitcoinyHTLC.Status htlcStatusB = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressB, minimumAmountB);

		switch (htlcStatusB) {
			case UNFUNDED: {
				// Do not include fee for funding transaction as this is covered by buildSpend()
				long amountB = P2SH_B_OUTPUT_AMOUNT + p2shFeeB /*redeeming/refunding P2SH-B*/;

				Transaction p2shFundingTransaction = bitcoin.buildSpend(tradeBotData.getForeignKey(), p2shAddressB, amountB);
				if (p2shFundingTransaction == null) {
					LOGGER.debug("Unable to build P2SH-B funding transaction - lack of funds?");
					return;
				}

				bitcoin.broadcastTransaction(p2shFundingTransaction);
				break;
			}

			case FUNDING_IN_PROGRESS:
			case FUNDED:
				break;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// This shouldn't occur, but defensively bump to next state
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WATCH_P2SH_B,
						() -> String.format("P2SH-B %s already spent? Defensively checking P2SH-B next", p2shAddressB));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
						() -> String.format("P2SH-B %s already refunded. Refunding P2SH-A next", p2shAddressB));
				return;
		}

		// P2SH-B funded, now we wait for Bob to redeem it
		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WATCH_P2SH_B,
				() -> String.format("AT %s locked to us (%s). P2SH-B %s funded. Watching P2SH-B for secret-B",
						tradeBotData.getAtAddress(), tradeBotData.getTradeNativeAddress(), p2shAddressB));
	}

	/**
	 * Trade-bot is waiting for P2SH-B to funded.
	 * <p>
	 * It's possible than Bob's AT has reached it's trading timeout and automatically refunded QORT back to Bob.
	 * In which case, trade-bot is done with this specific trade and finalizes on refunded state.
	 * <p>
	 * Assuming P2SH-B is funded, trade-bot 'redeems' this P2SH using secret-B, thus revealing it to Alice.
	 * <p>
	 * Trade-bot's next step is to wait for Alice to use secret-B, and her secret-A, to redeem Bob's AT.
	 * @throws ForeignBlockchainException
	 */
	private void handleBobWaitingForP2shB(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		// If we've passed AT refund timestamp then AT will have finished after auto-refunding
		if (atData.getIsFinished()) {
			TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_REFUNDED,
					() -> String.format("AT %s has auto-refunded - trade aborted", tradeBotData.getAtAddress()));

			return;
		}

		// It's possible AT hasn't processed our previous MESSAGE yet and so lockTimeB won't be set
		if (crossChainTradeData.lockTimeB == null)
			// AT yet to process MESSAGE
			return;

		Bitcoin bitcoin = Bitcoin.getInstance();

		byte[] redeemScriptB = BitcoinyHTLC.buildScript(crossChainTradeData.partnerForeignPKH, crossChainTradeData.lockTimeB, crossChainTradeData.creatorForeignPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddressB = bitcoin.deriveP2shAddress(redeemScriptB);


		final long minimumAmountB = P2SH_B_OUTPUT_AMOUNT;

		BitcoinyHTLC.Status htlcStatusB = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressB, minimumAmountB);

		switch (htlcStatusB) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				// Still waiting for P2SH-B to be funded...
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// This shouldn't occur, but defensively bump to next state
				TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_WAITING_FOR_AT_REDEEM,
						() -> String.format("P2SH-B %s already spent (exposing secret-B)? Checking AT %s for secret-A", p2shAddressB, tradeBotData.getAtAddress()));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				// AT should auto-refund - we don't need to do anything here
				return;

			case FUNDED:
				break;
		}

		// Redeem P2SH-B using secret-B
		Coin redeemAmount = Coin.valueOf(P2SH_B_OUTPUT_AMOUNT); // An actual amount to avoid dust filter, remaining used as fees. The real funds are in P2SH-A.
		ECKey redeemKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
		List<TransactionOutput> fundingOutputs = bitcoin.getUnspentOutputs(p2shAddressB, false);
		byte[] receivingAccountInfo = tradeBotData.getReceivingAccountInfo();

		Transaction p2shRedeemTransaction = BitcoinyHtlcFees.buildRedeemTransaction(bitcoin, redeemAmount, redeemKey,
				fundingOutputs, redeemScriptB, tradeBotData.getSecret(), receivingAccountInfo);

		bitcoin.broadcastTransaction(p2shRedeemTransaction);

		// P2SH-B redeemed, now we wait for Alice to use secret-A to redeem AT
		TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_WAITING_FOR_AT_REDEEM,
				() -> String.format("P2SH-B %s redeemed (exposing secret-B). Watching AT %s for secret-A", p2shAddressB, tradeBotData.getAtAddress()));
	}

	/**
	 * Trade-bot is waiting for Bob to redeem P2SH-B thus revealing secret-B to Alice.
	 * <p>
	 * It's possible that this process has taken so long that we've reached P2SH-B's locktime.
	 * In which case, trade-bot switches to begin the refund process.
	 * <p>
	 * If trade-bot can extract a valid secret-B from the spend of P2SH-B, then it creates a
	 * zero-fee, PoW MESSAGE to send to Bob's AT, including both secret-B and also Alice's secret-A.
	 * <p>
	 * Both secrets are needed to release the QORT funds from Bob's AT to Alice's 'native'/Qortal
	 * trade address.
	 * <p>
	 * In revealing a valid secret-A, Bob can then redeem the BTC funds from P2SH-A.
	 * <p>
	 * If trade-bot successfully broadcasts the MESSAGE transaction, then this specific trade is done.
	 * @throws ForeignBlockchainException
	 */
	private void handleAliceWatchingP2shB(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		if (aliceUnexpectedState(repository, tradeBotData, atData, crossChainTradeData))
			return;

		Bitcoin bitcoin = Bitcoin.getInstance();

		byte[] redeemScriptB = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), crossChainTradeData.lockTimeB, crossChainTradeData.creatorForeignPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddressB = bitcoin.deriveP2shAddress(redeemScriptB);

		final long minimumAmountB = P2SH_B_OUTPUT_AMOUNT;

		BitcoinyHTLC.Status htlcStatusB = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressB, minimumAmountB);

		switch (htlcStatusB) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
			case FUNDED:
			case REDEEM_IN_PROGRESS:
				// Still waiting for P2SH-B to be funded/redeemed...
				return;

			case REDEEMED:
				// Bob has redeemed P2SH-B, so double-check that we have redeemed AT...
				break;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				// We've refunded P2SH-B? Bump to refunding P2SH-A then
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
						() -> String.format("P2SH-B %s already refunded. Refunding P2SH-A next", p2shAddressB));
				return;
		}

		byte[] secretB = BitcoinyHTLC.findHtlcSecret(bitcoin, p2shAddressB);
		if (secretB == null)
			// Secret not revealed at this time
			return;

		// Send 'redeem' MESSAGE to AT using both secrets
		byte[] secretA = tradeBotData.getSecret();
		String qortalReceivingAddress = Base58.encode(tradeBotData.getReceivingAccountInfo()); // Actually contains whole address, not just PKH
		byte[] messageData = BitcoinACCTv1.buildRedeemMessage(secretA, secretB, qortalReceivingAddress);
		String messageRecipient = tradeBotData.getAtAddress();

		boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, messageData);
		if (!isMessageAlreadySent) {
			PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
			MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, messageRecipient, messageData, false, false);

			messageTransaction.computeNonce();
			messageTransaction.sign(sender);

			// Reset repository state to prevent deadlock
			repository.discardChanges();
			ValidationResult result = messageTransaction.importAsUnconfirmed();

			if (result != ValidationResult.OK) {
				LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", messageRecipient, result.name()));
				return;
			}
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE,
				() -> String.format("P2SH-B %s redeemed, using secrets to redeem AT %s. Funds should arrive at %s",
						p2shAddressB, tradeBotData.getAtAddress(), qortalReceivingAddress));
	}

	/**
	 * Trade-bot is waiting for Alice to redeem Bob's AT, thus revealing secret-A which is required to spend the BTC funds from P2SH-A.
	 * <p>
	 * It's possible that Bob's AT has reached its trading timeout and automatically refunded QORT back to Bob. In which case,
	 * trade-bot is done with this specific trade and finalizes in refunded state.
	 * <p>
	 * Assuming trade-bot can extract a valid secret-A from Alice's MESSAGE then trade-bot uses that to redeem the BTC funds from P2SH-A
	 * to Bob's 'foreign'/Bitcoin trade legacy-format address, as derived from trade private key.
	 * <p>
	 * (This could potentially be 'improved' to send BTC to any address of Bob's choosing by changing the transaction output).
	 * <p>
	 * If trade-bot successfully broadcasts the transaction, then this specific trade is done.
	 * @throws ForeignBlockchainException
	 */
	private void handleBobWaitingForAtRedeem(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		// AT should be 'finished' once Alice has redeemed QORT funds
		if (!atData.getIsFinished())
			// Not finished yet
			return;

		// If AT is not REDEEMED then something has gone wrong
		if (crossChainTradeData.mode != AcctMode.REDEEMED) {
			// Not redeemed so must be refunded/cancelled
			TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_REFUNDED,
					() -> String.format("AT %s has auto-refunded - trade aborted", tradeBotData.getAtAddress()));

			return;
		}

		byte[] secretA = BitcoinACCTv1.getInstance().findSecretA(repository, crossChainTradeData);
		if (secretA == null) {
			LOGGER.debug(() -> String.format("Unable to find secret-A from redeem message to AT %s?", tradeBotData.getAtAddress()));
			return;
		}

		// Use secret-A to redeem P2SH-A

		Bitcoin bitcoin = Bitcoin.getInstance();
		int lockTimeA = crossChainTradeData.lockTimeA;

		byte[] receivingAccountInfo = tradeBotData.getReceivingAccountInfo();
		byte[] redeemScriptA = BitcoinyHTLC.buildScript(crossChainTradeData.partnerForeignPKH, lockTimeA, crossChainTradeData.creatorForeignPKH, crossChainTradeData.hashOfSecretA);
		String p2shAddressA = bitcoin.deriveP2shAddress(redeemScriptA);

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long minimumAmountA = crossChainTradeData.expectedForeignAmount - P2SH_B_OUTPUT_AMOUNT;
		BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

		switch (htlcStatusA) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				// P2SH-A suddenly not funded? Our best bet at this point is to hope for AT auto-refund
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// Double-check that we have redeemed P2SH-A...
				break;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				// Wait for AT to auto-refund
				return;

			case FUNDED: {
				Coin redeemAmount = Coin.valueOf(crossChainTradeData.expectedForeignAmount - P2SH_B_OUTPUT_AMOUNT);
				ECKey redeemKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
				List<TransactionOutput> fundingOutputs = bitcoin.getUnspentOutputs(p2shAddressA, false);

				Transaction p2shRedeemTransaction = BitcoinyHtlcFees.buildRedeemTransaction(bitcoin, redeemAmount, redeemKey,
						fundingOutputs, redeemScriptA, secretA, receivingAccountInfo);

				bitcoin.broadcastTransaction(p2shRedeemTransaction);
				break;
			}
		}

		String receivingAddress = bitcoin.pkhToAddress(receivingAccountInfo);

		TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_DONE,
				() -> String.format("P2SH-A %s redeemed. Funds should arrive at %s", tradeBotData.getAtAddress(), receivingAddress));
	}

	/**
	 * Trade-bot is attempting to refund P2SH-B.
	 * <p>
	 * We could potentially skip this step as P2SH-B is only funded with a token amount to cover the mining fee should Bob redeem P2SH-B.
	 * <p>
	 * Upon successful broadcast of P2SH-B refunding transaction, trade-bot's next step is to begin refunding of P2SH-A.
	 * @throws ForeignBlockchainException
	 */
	private void handleAliceRefundingP2shB(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		int lockTimeB = crossChainTradeData.lockTimeB;

		// We can't refund P2SH-B until lockTime-B has passed
		if (NTP.getTime() <= lockTimeB * 1000L)
			return;

		Bitcoin bitcoin = Bitcoin.getInstance();

		// We can't refund P2SH-B until median block time has passed lockTime-B (see BIP113)
		int medianBlockTime = bitcoin.getMedianBlockTime();
		if (medianBlockTime <= lockTimeB)
			return;

		byte[] redeemScriptB = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeB, crossChainTradeData.creatorForeignPKH, crossChainTradeData.hashOfSecretB);
		String p2shAddressB = bitcoin.deriveP2shAddress(redeemScriptB);

		final long minimumAmountB = P2SH_B_OUTPUT_AMOUNT;

		BitcoinyHTLC.Status htlcStatusB = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressB, minimumAmountB);

		switch (htlcStatusB) {
			case UNFUNDED:
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
						() -> String.format("P2SH-B %s never funded?. Refunding P2SH-A next", p2shAddressB));
				return;

			case FUNDING_IN_PROGRESS:
				// Still waiting for P2SH-B to be funded...
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// We must be very close to trade timeout. Defensively try to refund P2SH-A
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
						() -> String.format("P2SH-B %s already spent?. Refunding P2SH-A next", p2shAddressB));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				break;

			case FUNDED:{
				Coin refundAmount = Coin.valueOf(P2SH_B_OUTPUT_AMOUNT); // An actual amount to avoid dust filter, remaining used as fees.
				ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
				List<TransactionOutput> fundingOutputs = bitcoin.getUnspentOutputs(p2shAddressB, false);

				// Determine receive address for refund
				String receiveAddress = bitcoin.getUnusedReceiveAddress(tradeBotData.getForeignKey());
				Address receiving = Address.fromString(bitcoin.getNetworkParameters(), receiveAddress);

				Transaction p2shRefundTransaction = BitcoinyHtlcFees.buildRefundTransaction(bitcoin, refundAmount, refundKey,
						fundingOutputs, redeemScriptB, lockTimeB, receiving.getHash());

				bitcoin.broadcastTransaction(p2shRefundTransaction);
				break;
			}
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
				() -> String.format("Refunded P2SH-B %s. Waiting for LockTime-A", p2shAddressB));
	}

	/**
	 * Trade-bot is attempting to refund P2SH-A.
	 * @throws ForeignBlockchainException
	 */
	private void handleAliceRefundingP2shA(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		int lockTimeA = tradeBotData.getLockTimeA();

		// We can't refund P2SH-A until lockTime-A has passed
		if (NTP.getTime() <= lockTimeA * 1000L)
			return;

		Bitcoin bitcoin = Bitcoin.getInstance();

		// We can't refund P2SH-A until median block time has passed lockTime-A (see BIP113)
		int medianBlockTime = bitcoin.getMedianBlockTime();
		if (medianBlockTime <= lockTimeA)
			return;

		byte[] redeemScriptA = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeA, crossChainTradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
		String p2shAddressA = bitcoin.deriveP2shAddress(redeemScriptA);

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long minimumAmountA = crossChainTradeData.expectedForeignAmount - P2SH_B_OUTPUT_AMOUNT;
		BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

		switch (htlcStatusA) {
			case UNFUNDED:
			case FUNDING_IN_PROGRESS:
				// Still waiting for P2SH-A to be funded...
				return;

			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				// Too late!
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE,
						() -> String.format("P2SH-A %s already spent!", p2shAddressA));
				return;

			case REFUND_IN_PROGRESS:
			case REFUNDED:
				break;

			case FUNDED:{
				Coin refundAmount = Coin.valueOf(crossChainTradeData.expectedForeignAmount - P2SH_B_OUTPUT_AMOUNT);
				ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
				List<TransactionOutput> fundingOutputs = bitcoin.getUnspentOutputs(p2shAddressA, false);

				// Determine receive address for refund
				String receiveAddress = bitcoin.getUnusedReceiveAddress(tradeBotData.getForeignKey());
				Address receiving = Address.fromString(bitcoin.getNetworkParameters(), receiveAddress);

				Transaction p2shRefundTransaction = BitcoinyHtlcFees.buildRefundTransaction(bitcoin, refundAmount, refundKey,
						fundingOutputs, redeemScriptA, lockTimeA, receiving.getHash());

				bitcoin.broadcastTransaction(p2shRefundTransaction);
				break;
			}
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDED,
				() -> String.format("LockTime-A reached. Refunded P2SH-A %s. Trade aborted", p2shAddressA));
	}

	/**
	 * Returns true if Alice finds AT unexpectedly cancelled, refunded, redeemed or locked to someone else.
	 * <p>
	 * Will automatically update trade-bot state to <tt>ALICE_REFUNDING_B</tt> or <tt>ALICE_DONE</tt> as necessary.
	 * 
	 * @throws DataException
	 * @throws ForeignBlockchainException
	 */
	private boolean aliceUnexpectedState(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		// This is OK
		if (!atData.getIsFinished() && crossChainTradeData.mode == AcctMode.OFFERING)
			return false;

		boolean isAtLockedToUs = tradeBotData.getTradeNativeAddress().equals(crossChainTradeData.qortalPartnerAddress);

		if (!atData.getIsFinished() && crossChainTradeData.mode == AcctMode.TRADING && isAtLockedToUs)
			return false;

		if (atData.getIsFinished() && crossChainTradeData.mode == AcctMode.REDEEMED && isAtLockedToUs) {
			// We've redeemed already?
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE,
					() -> String.format("AT %s already redeemed by us. Trade completed", tradeBotData.getAtAddress()));
		} else {
			// Any other state is not good, so start defensive refund
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_B,
					() -> String.format("AT %s cancelled/refunded/redeemed by someone else/invalid state. Refunding & aborting trade", tradeBotData.getAtAddress()));
		}

		return true;
	}

	private long calcP2shAFeeTimestamp(int lockTimeA, int tradeTimeout) {
		return (lockTimeA - tradeTimeout * 60) * 1000L;
	}

	private long calcP2shBFeeTimestamp(int lockTimeA, int lockTimeB) {
		// lockTimeB is halfway between offerMessageTimestamp and lockTimeA
		return (lockTimeA - (lockTimeA - lockTimeB) * 2) * 1000L;
	}

}
