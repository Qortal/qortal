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
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.qortal.controller.tradebot.TradeStates.State;

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
public class DogecoinACCTv3TradeBot implements AcctTradeBot {

	private static final Logger LOGGER = LogManager.getLogger(DogecoinACCTv3TradeBot.class);

	/** Maximum time Bob waits for his AT creation transaction to be confirmed into a block. (milliseconds) */
	private static final long MAX_AT_CONFIRMATION_PERIOD = 24 * 60 * 60 * 1000L; // ms

	private static DogecoinACCTv3TradeBot instance;

	private final List<String> endStates = Arrays.asList(State.BOB_DONE, State.BOB_REFUNDED, State.ALICE_DONE, State.ALICE_REFUNDING_A, State.ALICE_REFUNDED).stream()
			.map(State::name)
			.collect(Collectors.toUnmodifiableList());

	private DogecoinACCTv3TradeBot() {
	}

	public static synchronized DogecoinACCTv3TradeBot getInstance() {
		if (instance == null)
			instance = new DogecoinACCTv3TradeBot();

		return instance;
	}

	@Override
	public List<String> getEndStates() {
		return this.endStates;
	}

	/**
	 * Creates a new trade-bot entry from the "Bob" viewpoint, i.e. OFFERing QORT in exchange for DOGE.
	 * <p>
	 * Generates:
	 * <ul>
	 * 	<li>new 'trade' private key</li>
	 * </ul>
	 * Derives:
	 * <ul>
	 * 	<li>'native' (as in Qortal) public key, public key hash, address (starting with Q)</li>
	 * 	<li>'foreign' (as in Dogecoin) public key, public key hash</li>
	 * </ul>
	 * A Qortal AT is then constructed including the following as constants in the 'data segment':
	 * <ul>
	 * 	<li>'native'/Qortal 'trade' address - used as a MESSAGE contact</li>
	 * 	<li>'foreign'/Dogecoin public key hash - used by Alice's P2SH scripts to allow redeem</li>
	 * 	<li>QORT amount on offer by Bob</li>
	 * 	<li>DOGE amount expected in return by Bob (from Alice)</li>
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

		byte[] tradeNativePublicKey = TradeBot.deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		// Convert Dogecoin receiving address into public key hash (we only support P2PKH at this time)
		Address dogecoinReceivingAddress;
		try {
			dogecoinReceivingAddress = Address.fromString(Dogecoin.getInstance().getNetworkParameters(), tradeBotCreateRequest.receivingAddress);
		} catch (AddressFormatException e) {
			throw new DataException("Unsupported Dogecoin receiving address: " + tradeBotCreateRequest.receivingAddress);
		}
		if (dogecoinReceivingAddress.getOutputScriptType() != ScriptType.P2PKH)
			throw new DataException("Unsupported Dogecoin receiving address: " + tradeBotCreateRequest.receivingAddress);

		byte[] dogecoinReceivingAccountInfo = dogecoinReceivingAddress.getHash();

		PublicKeyAccount creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);

		// Deploy AT
		long timestamp = NTP.getTime();
		byte[] reference = creator.getLastReference();
		long fee = 0L;
		byte[] signature = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, creator.getPublicKey(), fee, signature);

		String name = "QORT/DOGE ACCT";
		String description = "QORT/DOGE cross-chain trade";
		String aTType = "ACCT";
		String tags = "ACCT QORT DOGE";
		byte[] creationBytes = DogecoinACCTv3.buildQortalAT(tradeNativeAddress, tradeForeignPublicKeyHash, tradeBotCreateRequest.qortAmount,
				tradeBotCreateRequest.foreignAmount, tradeBotCreateRequest.tradeTimeout);
		long amount = tradeBotCreateRequest.fundingQortAmount;

		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, aTType, tags, creationBytes, amount, Asset.QORT);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		fee = deployAtTransaction.calcRecommendedFee();
		deployAtTransactionData.setFee(fee);

		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, DogecoinACCTv3.NAME,
				State.BOB_WAITING_FOR_AT_CONFIRM.name(), State.BOB_WAITING_FOR_AT_CONFIRM.value,
				creator.getAddress(), atAddress, timestamp, tradeBotCreateRequest.qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				null, null,
				SupportedBlockchain.DOGECOIN.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.foreignAmount, null, null, null, dogecoinReceivingAccountInfo);

		TradeBot.updateTradeBotState(repository, tradeBotData, () -> String.format("Built AT %s. Waiting for deployment", atAddress));

		// Attempt to backup the trade bot data
		TradeBot.backupTradeBotData(repository, null);

		// Return to user for signing and broadcast as we don't have their Qortal private key
		try {
			return DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform DEPLOY_AT transaction?", e);
		}
	}

	/**
	 * Creates a trade-bot entry from the 'Alice' viewpoint, i.e. matching DOGE to an existing offer.
	 * <p>
	 * Requires a chosen trade offer from Bob, passed by <tt>crossChainTradeData</tt>
	 * and access to a Dogecoin wallet via <tt>xprv58</tt>.
	 * <p>
	 * The <tt>crossChainTradeData</tt> contains the current trade offer state
	 * as extracted from the AT's data segment.
	 * <p>
	 * Access to a funded wallet is via a Dogecoin BIP32 hierarchical deterministic key,
	 * passed via <tt>xprv58</tt>.
	 * <b>This key will be stored in your node's database</b>
	 * to allow trade-bot to create/fund the necessary P2SH transactions!
	 * However, due to the nature of BIP32 keys, it is possible to give the trade-bot
	 * only a subset of wallet access (see BIP32 for more details).
	 * <p>
	 * As an example, the xprv58 can be extract from a <i>legacy, password-less</i>
	 * Electrum wallet by going to the console tab and entering:<br>
	 * <tt>wallet.keystore.xprv</tt><br>
	 * which should result in a base58 string starting with either 'xprv' (for Dogecoin main-net)
	 * or 'tprv' for (Dogecoin test-net).
	 * <p>
	 * It is envisaged that the value in <tt>xprv58</tt> will actually come from a Qortal-UI-managed wallet.
	 * <p>
	 * If sufficient funds are available, <b>this method will actually fund the P2SH-A</b>
	 * with the Dogecoin amount expected by 'Bob'.
	 * <p>
	 * If the Dogecoin transaction is successfully broadcast to the network then
	 * we also send a MESSAGE to Bob's trade-bot to let them know.
	 * <p>
	 * The trade-bot entry is saved to the repository and the cross-chain trading process commences.
	 * <p>
	 * @param repository
	 * @param crossChainTradeData chosen trade OFFER that Alice wants to match
	 * @param xprv58 funded wallet xprv in base58
	 * @return true if P2SH-A funding transaction successfully broadcast to Dogecoin network, false otherwise
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

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, DogecoinACCTv3.NAME,
				State.ALICE_WAITING_FOR_AT_LOCK.name(), State.ALICE_WAITING_FOR_AT_LOCK.value,
				receivingAddress, crossChainTradeData.qortalAtAddress, now, crossChainTradeData.qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretA, hashOfSecretA,
				SupportedBlockchain.DOGECOIN.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				crossChainTradeData.expectedForeignAmount, xprv58, null, lockTimeA, receivingPublicKeyHash);

		// Attempt to backup the trade bot data
		// Include tradeBotData as an additional parameter, since it's not in the repository yet
		TradeBot.backupTradeBotData(repository, Arrays.asList(tradeBotData));

		// Check we have enough funds via xprv58 to fund P2SH to cover expectedForeignAmount
		long p2shFee;
		try {
			p2shFee = Dogecoin.getInstance().getP2shFee(now);
		} catch (ForeignBlockchainException e) {
			LOGGER.debug("Couldn't estimate Dogecoin fees?");
			return ResponseResult.NETWORK_ISSUE;
		}

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		// Do not include fee for funding transaction as this is covered by buildSpend()
		long amountA = crossChainTradeData.expectedForeignAmount + p2shFee /*redeeming/refunding P2SH-A*/;

		// P2SH-A to be funded
		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(tradeForeignPublicKeyHash, lockTimeA, crossChainTradeData.creatorForeignPKH, hashOfSecretA);
		String p2shAddress = Dogecoin.getInstance().deriveP2shAddress(redeemScriptBytes);

		// Build transaction for funding P2SH-A
		Transaction p2shFundingTransaction = Dogecoin.getInstance().buildSpend(tradeBotData.getForeignKey(), p2shAddress, amountA);
		if (p2shFundingTransaction == null) {
			LOGGER.debug("Unable to build P2SH-A funding transaction - lack of funds?");
			return ResponseResult.BALANCE_ISSUE;
		}

		try {
			Dogecoin.getInstance().broadcastTransaction(p2shFundingTransaction);
		} catch (ForeignBlockchainException e) {
			// We couldn't fund P2SH-A at this time
			LOGGER.debug("Couldn't broadcast P2SH-A funding transaction?");
			return ResponseResult.NETWORK_ISSUE;
		}

		// Attempt to send MESSAGE to Bob's Qortal trade address
		byte[] messageData = CrossChainUtils.buildOfferMessage(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret(), tradeBotData.getLockTimeA());
		String messageRecipient = crossChainTradeData.qortalCreatorTradeAddress;

		boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, messageData);
		if (!isMessageAlreadySent) {
			// Do this in a new thread so caller doesn't have to wait for computeNonce()
			// In the unlikely event that the transaction doesn't validate then the buy won't happen and eventually Alice's AT will be refunded
			new Thread(() -> {
				try (final Repository threadsRepository = RepositoryManager.getRepository()) {
					PrivateKeyAccount sender = new PrivateKeyAccount(threadsRepository, tradeBotData.getTradePrivateKey());
					MessageTransaction messageTransaction = MessageTransaction.build(threadsRepository, sender, Group.NO_GROUP, messageRecipient, messageData, false, false);

					LOGGER.info("Computing nonce at difficulty {} for AT {} and recipient {}", messageTransaction.getPoWDifficulty(), tradeBotData.getAtAddress(), messageRecipient);
					messageTransaction.computeNonce();
					MessageTransactionData newMessageTransactionData = (MessageTransactionData) messageTransaction.getTransactionData();
					LOGGER.info("Computed nonce {} at difficulty {}", newMessageTransactionData.getNonce(), messageTransaction.getPoWDifficulty());
					messageTransaction.sign(sender);

					// reset repository state to prevent deadlock
					threadsRepository.discardChanges();

					if (messageTransaction.isSignatureValid()) {
						ValidationResult result = messageTransaction.importAsUnconfirmed();

						if (result != ValidationResult.OK) {
							LOGGER.warn(() -> String.format("Unable to send MESSAGE to Bob's trade-bot %s: %s", messageRecipient, result.name()));
						}
					}
					else {
						LOGGER.warn(() -> String.format("Unable to send MESSAGE to Bob's trade-bot %s: signature invalid", messageRecipient));
					}
				} catch (DataException e) {
					LOGGER.warn(() -> String.format("Unable to send MESSAGE to Bob's trade-bot %s: %s", messageRecipient, e.getMessage()));
				}
			}, "TradeBot response").start();
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, () -> String.format("Funding P2SH-A %s. Messaged Bob. Waiting for AT-lock", p2shAddress));

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
				tradeData = DogecoinACCTv3.getInstance().populateTradeData(repository, atData);
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

			case BOB_WAITING_FOR_MESSAGE:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleBobWaitingForMessage(repository, tradeBotData, atData, tradeData);
				break;

            case ALICE_WAITING_FOR_FUNDING:
                LocalTradeFunding.progress(repository, tradeBotData, tradeData, Dogecoin.getInstance());
                break;

			case ALICE_WAITING_FOR_AT_LOCK:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleAliceWaitingForAtLock(repository, tradeBotData, atData, tradeData);
				break;

			case BOB_WAITING_FOR_AT_REDEEM:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleBobWaitingForAtRedeem(repository, tradeBotData, atData, tradeData);
				break;

			case ALICE_DONE:
			case BOB_DONE:
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
	 * Trade-bot is waiting for MESSAGE from Alice's trade-bot, containing Alice's trade info.
	 * <p>
	 * It's possible Bob has cancelling his trade offer, receiving an automatic QORT refund,
	 * in which case trade-bot is done with this specific trade and finalizes on refunded state.
	 * <p>
	 * Assuming trade is still on offer, trade-bot checks the contents of MESSAGE from Alice's trade-bot.
	 * <p>
	 * Details from Alice are used to derive P2SH-A address and this is checked for funding balance.
	 * <p>
	 * Assuming P2SH-A has at least expected Dogecoin balance,
	 * Bob's trade-bot constructs a zero-fee, PoW MESSAGE to send to Bob's AT with more trade details.
	 * <p>
	 * On processing this MESSAGE, Bob's AT should switch into 'TRADE' mode and only trade with Alice.
	 * <p>
	 * Trade-bot's next step is to wait for Alice to redeem the AT, which will allow Bob to
	 * extract secret-A needed to redeem Alice's P2SH.
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

		Dogecoin dogecoin = Dogecoin.getInstance();

		String address = tradeBotData.getTradeNativeAddress();
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null, address, null, null, null);

		for (MessageTransactionData messageTransactionData : messageTransactionsData) {
			if (messageTransactionData.isText())
				continue;

			// We're expecting: HASH160(secret-A), Alice's Dogecoin pubkeyhash and lockTime-A
			byte[] messageData = messageTransactionData.getData();
			DogecoinACCTv3.OfferMessageData offerMessageData = DogecoinACCTv3.extractOfferMessageData(messageData);
			if (offerMessageData == null)
				continue;

			byte[] aliceForeignPublicKeyHash = offerMessageData.partnerDogecoinPKH;
			byte[] hashOfSecretA = offerMessageData.hashOfSecretA;
			int lockTimeA = (int) offerMessageData.lockTimeA;
			long messageTimestamp = messageTransactionData.getTimestamp();
			int refundTimeout = DogecoinACCTv3.calcRefundTimeout(messageTimestamp, lockTimeA);

			// Determine P2SH-A address and confirm funded
			byte[] redeemScriptA = BitcoinyHTLC.buildScript(aliceForeignPublicKeyHash, lockTimeA, tradeBotData.getTradeForeignPublicKeyHash(), hashOfSecretA);
			String p2shAddressA = dogecoin.deriveP2shAddress(redeemScriptA);

			long feeTimestamp = calcFeeTimestamp(lockTimeA, crossChainTradeData.tradeTimeout);
			long p2shFee = Dogecoin.getInstance().getP2shFee(feeTimestamp);
			final long minimumAmountA = tradeBotData.getForeignAmount() + p2shFee;

			BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(dogecoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

			switch (htlcStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
					// There might be another MESSAGE from someone else with an actually funded P2SH-A...
					continue;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// We've already redeemed this?
					TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_DONE,
							() -> String.format("P2SH-A %s already spent? Assuming trade complete", p2shAddressA));
					return;

				case REFUND_IN_PROGRESS:
				case REFUNDED:
					// This P2SH-A is burnt, but there might be another MESSAGE from someone else with an actually funded P2SH-A...
					continue;

				case FUNDED:
					// Fall-through out of switch...
					break;
			}

			// Good to go - send MESSAGE to AT

			String aliceNativeAddress = Crypto.toAddress(messageTransactionData.getCreatorPublicKey());

			// Build outgoing message, padding each part to 32 bytes to make it easier for AT to consume
			byte[] outgoingMessageData = DogecoinACCTv3.buildTradeMessage(aliceNativeAddress, aliceForeignPublicKeyHash, hashOfSecretA, lockTimeA, refundTimeout);
			String messageRecipient = tradeBotData.getAtAddress();

			boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, outgoingMessageData);
			if (!isMessageAlreadySent) {
				PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
				MessageTransaction outgoingMessageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, messageRecipient, outgoingMessageData, false, false);

				LOGGER.info("Computing nonce at difficulty {} for AT {} and recipient {}", outgoingMessageTransaction.getPoWDifficulty(), tradeBotData.getAtAddress(), messageRecipient);
				outgoingMessageTransaction.computeNonce();
				MessageTransactionData newMessageTransactionData = (MessageTransactionData) outgoingMessageTransaction.getTransactionData();
				LOGGER.info("Computed nonce {} at difficulty {}", newMessageTransactionData.getNonce(), outgoingMessageTransaction.getPoWDifficulty());
				outgoingMessageTransaction.sign(sender);

				// reset repository state to prevent deadlock
				repository.discardChanges();

				if (outgoingMessageTransaction.isSignatureValid()) {
					ValidationResult result = outgoingMessageTransaction.importAsUnconfirmed();

					if (result != ValidationResult.OK) {
						LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", messageRecipient, result.name()));
						return;
					}
				}
				else {
					LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: signature invalid", messageRecipient));
					return;
				}
			}

			TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_WAITING_FOR_AT_REDEEM,
					() -> String.format("Locked AT %s to %s. Waiting for AT redeem", tradeBotData.getAtAddress(), aliceNativeAddress));

			return;
		}
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
	 * If all is well, trade-bot then redeems AT using Alice's secret-A, releasing Bob's QORT to Alice.
	 * <p>
	 * In revealing a valid secret-A, Bob can then redeem the DOGE funds from P2SH-A.
	 * <p>
	 * @throws ForeignBlockchainException
	 */
	private void handleAliceWaitingForAtLock(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		if (aliceUnexpectedState(repository, tradeBotData, atData, crossChainTradeData))
			return;

		Dogecoin dogecoin = Dogecoin.getInstance();
		int lockTimeA = tradeBotData.getLockTimeA();

		// Refund P2SH-A if we've passed lockTime-A
		if (NTP.getTime() >= lockTimeA * 1000L) {
			byte[] redeemScriptA = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeA, crossChainTradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
			String p2shAddressA = dogecoin.deriveP2shAddress(redeemScriptA);

			long feeTimestamp = calcFeeTimestamp(lockTimeA, crossChainTradeData.tradeTimeout);
			long p2shFee = Dogecoin.getInstance().getP2shFee(feeTimestamp);
			long minimumAmountA = crossChainTradeData.expectedForeignAmount + p2shFee;

			BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(dogecoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

			switch (htlcStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
				case FUNDED:
					break;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// Already redeemed?
					TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE,
							() -> String.format("P2SH-A %s already spent? Assuming trade completed", p2shAddressA));
					return;

				case REFUND_IN_PROGRESS:
				case REFUNDED:
					TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDED,
							() -> String.format("P2SH-A %s already refunded. Trade aborted", p2shAddressA));
					return;

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

		// Find our MESSAGE to AT from previous state
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(tradeBotData.getTradeNativePublicKey(),
				crossChainTradeData.qortalCreatorTradeAddress, null, null, null);
		if (messageTransactionsData == null || messageTransactionsData.isEmpty()) {
			LOGGER.warn(() -> String.format("Unable to find our message to trade creator %s?", crossChainTradeData.qortalCreatorTradeAddress));
			return;
		}

		long recipientMessageTimestamp = messageTransactionsData.get(0).getTimestamp();
		int refundTimeout = DogecoinACCTv3.calcRefundTimeout(recipientMessageTimestamp, lockTimeA);

		// Our calculated refundTimeout should match AT's refundTimeout
		if (refundTimeout != crossChainTradeData.refundTimeout) {
			LOGGER.debug(() -> String.format("Trade AT refundTimeout '%d' doesn't match our refundTimeout '%d'", crossChainTradeData.refundTimeout, refundTimeout));
			// We'll eventually refund
			return;
		}

		// We're good to redeem AT

		// Send 'redeem' MESSAGE to AT using both secret
		byte[] secretA = tradeBotData.getSecret();
		String qortalReceivingAddress = Base58.encode(tradeBotData.getReceivingAccountInfo()); // Actually contains whole address, not just PKH
		byte[] messageData = DogecoinACCTv3.buildRedeemMessage(secretA, qortalReceivingAddress);
		String messageRecipient = tradeBotData.getAtAddress();

		boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, messageData);
		if (!isMessageAlreadySent) {
			PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
			MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, messageRecipient, messageData, false, false);

			LOGGER.info("Computing nonce at difficulty {} for AT {} and recipient {}", messageTransaction.getPoWDifficulty(), tradeBotData.getAtAddress(), messageRecipient);
			messageTransaction.computeNonce();
			MessageTransactionData newMessageTransactionData = (MessageTransactionData) messageTransaction.getTransactionData();
			LOGGER.info("Computed nonce {} at difficulty {}", newMessageTransactionData.getNonce(), messageTransaction.getPoWDifficulty());
			messageTransaction.sign(sender);

			// Reset repository state to prevent deadlock
			repository.discardChanges();

			if (messageTransaction.isSignatureValid()) {
				ValidationResult result = messageTransaction.importAsUnconfirmed();

				if (result != ValidationResult.OK) {
					LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: %s", messageRecipient, result.name()));
					return;
				}
			}
			else {
				LOGGER.warn(() -> String.format("Unable to send MESSAGE to AT %s: signature invalid", messageRecipient));
				return;
			}
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE,
				() -> String.format("Redeeming AT %s. Funds should arrive at %s",
						tradeBotData.getAtAddress(), qortalReceivingAddress));
	}

	/**
	 * Trade-bot is waiting for Alice to redeem Bob's AT, thus revealing secret-A which is required to spend the DOGE funds from P2SH-A.
	 * <p>
	 * It's possible that Bob's AT has reached its trading timeout and automatically refunded QORT back to Bob. In which case,
	 * trade-bot is done with this specific trade and finalizes in refunded state.
	 * <p>
	 * Assuming trade-bot can extract a valid secret-A from Alice's MESSAGE then trade-bot uses that to redeem the DOGE funds from P2SH-A
	 * to Bob's 'foreign'/Dogecoin trade legacy-format address, as derived from trade private key.
	 * <p>
	 * (This could potentially be 'improved' to send DOGE to any address of Bob's choosing by changing the transaction output).
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

		// If AT is REFUNDED or CANCELLED then something has gone wrong
		if (crossChainTradeData.mode == AcctMode.REFUNDED || crossChainTradeData.mode == AcctMode.CANCELLED) {
			// Alice hasn't redeemed the QORT, so there is no point in trying to redeem the DOGE
			TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_REFUNDED,
					() -> String.format("AT %s has auto-refunded - trade aborted", tradeBotData.getAtAddress()));

			return;
		}

		byte[] secretA = DogecoinACCTv3.getInstance().findSecretA(repository, crossChainTradeData);
		if (secretA == null) {
			LOGGER.debug(() -> String.format("Unable to find secret-A from redeem message to AT %s?", tradeBotData.getAtAddress()));
			return;
		}

		// Use secret-A to redeem P2SH-A

		Dogecoin dogecoin = Dogecoin.getInstance();

		byte[] receivingAccountInfo = tradeBotData.getReceivingAccountInfo();
		int lockTimeA = crossChainTradeData.lockTimeA;
		byte[] redeemScriptA = BitcoinyHTLC.buildScript(crossChainTradeData.partnerForeignPKH, lockTimeA, crossChainTradeData.creatorForeignPKH, crossChainTradeData.hashOfSecretA);
		String p2shAddressA = dogecoin.deriveP2shAddress(redeemScriptA);

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long feeTimestamp = calcFeeTimestamp(lockTimeA, crossChainTradeData.tradeTimeout);
		long p2shFee = Dogecoin.getInstance().getP2shFee(feeTimestamp);
		long minimumAmountA = crossChainTradeData.expectedForeignAmount + p2shFee;
		BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(dogecoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

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
				Coin redeemAmount = Coin.valueOf(crossChainTradeData.expectedForeignAmount);
				ECKey redeemKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
				List<TransactionOutput> fundingOutputs = dogecoin.getUnspentOutputs(p2shAddressA, false);

				Transaction p2shRedeemTransaction = BitcoinyHTLC.buildRedeemTransaction(dogecoin.getNetworkParameters(), redeemAmount, redeemKey,
						fundingOutputs, redeemScriptA, secretA, receivingAccountInfo);

				dogecoin.broadcastTransaction(p2shRedeemTransaction);
				break;
			}
		}

		String receivingAddress = dogecoin.pkhToAddress(receivingAccountInfo);

		TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_DONE,
				() -> String.format("P2SH-A %s redeemed. Funds should arrive at %s", tradeBotData.getAtAddress(), receivingAddress));
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

		Dogecoin dogecoin = Dogecoin.getInstance();

		// We can't refund P2SH-A until median block time has passed lockTime-A (see BIP113)
		int medianBlockTime = dogecoin.getMedianBlockTime();
		if (medianBlockTime <= lockTimeA)
			return;

		byte[] redeemScriptA = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeA, crossChainTradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
		String p2shAddressA = dogecoin.deriveP2shAddress(redeemScriptA);

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		long feeTimestamp = calcFeeTimestamp(lockTimeA, crossChainTradeData.tradeTimeout);
		long p2shFee = Dogecoin.getInstance().getP2shFee(feeTimestamp);
		long minimumAmountA = crossChainTradeData.expectedForeignAmount + p2shFee;
		BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(dogecoin.getBlockchainProvider(), p2shAddressA, minimumAmountA);

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
				Coin refundAmount = Coin.valueOf(crossChainTradeData.expectedForeignAmount);
				ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
				List<TransactionOutput> fundingOutputs = dogecoin.getUnspentOutputs(p2shAddressA, false);

				// Determine receive address for refund
				String receiveAddress = dogecoin.getUnusedReceiveAddress(tradeBotData.getForeignKey());
				Address receiving = Address.fromString(dogecoin.getNetworkParameters(), receiveAddress);

				Transaction p2shRefundTransaction = BitcoinyHTLC.buildRefundTransaction(dogecoin.getNetworkParameters(), refundAmount, refundKey,
						fundingOutputs, redeemScriptA, lockTimeA, receiving.getHash());

				dogecoin.broadcastTransaction(p2shRefundTransaction);
				break;
			}
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDED,
				() -> String.format("LockTime-A reached. Refunded P2SH-A %s. Trade aborted", p2shAddressA));
	}

	/**
	 * Returns true if Alice finds AT unexpectedly cancelled, refunded, redeemed or locked to someone else.
	 * <p>
	 * Will automatically update trade-bot state to <tt>ALICE_REFUNDING_A</tt> or <tt>ALICE_DONE</tt> as necessary.
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

		if (!atData.getIsFinished() && crossChainTradeData.mode == AcctMode.TRADING)
			if (isAtLockedToUs) {
				// AT is trading with us - OK
				return false;
			} else {
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
						() -> String.format("AT %s trading with someone else: %s. Refunding & aborting trade", tradeBotData.getAtAddress(), crossChainTradeData.qortalPartnerAddress));

				return true;
			}

		if (atData.getIsFinished() && crossChainTradeData.mode == AcctMode.REDEEMED && isAtLockedToUs) {
			// We've redeemed already?
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE,
					() -> String.format("AT %s already redeemed by us. Trade completed", tradeBotData.getAtAddress()));
		} else {
			// Any other state is not good, so start defensive refund
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
					() -> String.format("AT %s cancelled/refunded/redeemed by someone else/invalid state. Refunding & aborting trade", tradeBotData.getAtAddress()));
		}

		return true;
	}

	private long calcFeeTimestamp(int lockTimeA, int tradeTimeout) {
		return (lockTimeA - tradeTimeout * 60) * 1000L;
	}

}
