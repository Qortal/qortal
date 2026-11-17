package org.qortal.controller.tradebot;

import com.rust.litewalletjni.LiteWalletJni;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
public class PirateChainACCTv3TradeBot implements AcctTradeBot {

	private static final Logger LOGGER = LogManager.getLogger(PirateChainACCTv3TradeBot.class);

	/** Maximum time Bob waits for his AT creation transaction to be confirmed into a block. (milliseconds) */
	private static final long MAX_AT_CONFIRMATION_PERIOD = 24 * 60 * 60 * 1000L; // ms

	private static PirateChainACCTv3TradeBot instance;

	// Audit old ACK-only terminal records gradually, without scanning every wallet on every block.
	private final java.util.Map<String, Long> auditedLegacyRefunds = new java.util.HashMap<>();
	private long nextLegacyRefundAudit;

	// Keep ordinary wait diagnostics visible at INFO without logging every chain tip.
	private final java.util.Map<String, RecoveryLogState> recoveryLogStates = new java.util.LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<String, RecoveryLogState> eldest) {
			return size() > 1024;
		}
	};
	private static final class RecoveryLogState {
		final String reason;
		final long loggedAt;
		RecoveryLogState(String reason, long loggedAt) { this.reason = reason; this.loggedAt = loggedAt; }
	}

	/** Public trade identifiers and explicit numeric observations only; never pass wallet or signing data. */
	private void logRecoveryWait(TradeBotData trade, String phase, String reason, String details) {
		String key = trade.getAtAddress() + ":" + trade.getTradeNativeAddress() + ":" + phase;
		String stateReason = trade.getState() + ":" + reason;
		long now = System.nanoTime();
		synchronized (recoveryLogStates) {
			RecoveryLogState previous = recoveryLogStates.get(key);
			if (previous != null && previous.reason.equals(stateReason)
					&& now - previous.loggedAt < 60_000_000_000L) return;
			recoveryLogStates.put(key, new RecoveryLogState(stateReason, now));
		}
		LOGGER.info("ARRR recovery for AT {} (trade {}): phase={} state={} reason={} {}",
				trade.getAtAddress(), trade.getTradeNativeAddress(), phase, trade.getState(), reason, details);
	}

	private final List<String> endStates = Arrays.asList(State.BOB_DONE, State.BOB_REFUNDED, State.ALICE_DONE, State.ALICE_REFUNDING_A, State.ALICE_REFUNDED).stream()
			.map(State::name)
			.collect(Collectors.toUnmodifiableList());

	private PirateChainACCTv3TradeBot() {
	}

	public static synchronized PirateChainACCTv3TradeBot getInstance() {
		if (instance == null)
			instance = new PirateChainACCTv3TradeBot();

		return instance;
	}

	@Override
	public List<String> getEndStates() {
		return this.endStates;
	}

	/**
	 * Creates a new trade-bot entry from the "Bob" viewpoint, i.e. OFFERing QORT in exchange for ARRR.
	 * <p>
	 * Generates:
	 * <ul>
	 * 	<li>new 'trade' private key</li>
	 * </ul>
	 * Derives:
	 * <ul>
	 * 	<li>'native' (as in Qortal) public key, public key hash, address (starting with Q)</li>
	 * 	<li>'foreign' (as in PirateChain) public key, public key hash</li>
	 * </ul>
	 * A Qortal AT is then constructed including the following as constants in the 'data segment':
	 * <ul>
	 * 	<li>'native'/Qortal 'trade' address - used as a MESSAGE contact</li>
	 * 	<li>'foreign'/PirateChain public key hash - used by Alice's P2SH scripts to allow redeem</li>
	 * 	<li>QORT amount on offer by Bob</li>
	 * 	<li>ARRR amount expected in return by Bob (from Alice)</li>
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

		// ARRR wallet must be loaded before a trade can be created
		// This is to stop trades from nodes on unsupported architectures (e.g. 32bit)
		if (!LiteWalletJni.isLoaded()) {
			throw new DataException("Pirate wallet not found. Check wallets screen for details.");
		}

		if (!PirateChainAddress.isValid(tradeBotCreateRequest.receivingAddress)) {
			throw new DataException("Unsupported Pirate Chain receiving address: " + tradeBotCreateRequest.receivingAddress);
		}

		byte[] pirateChainReceivingAccountInfo = PirateChainAddress.toReceivingAccountInfo(tradeBotCreateRequest.receivingAddress);

		PublicKeyAccount creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);

		// Deploy AT
		long timestamp = NTP.getTime();
		byte[] reference = creator.getLastReference();
		long fee = 0L;
		byte[] signature = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, creator.getPublicKey(), fee, signature);

		String name = "QORT/ARRR ACCT";
		String description = "QORT/ARRR cross-chain trade";
		String aTType = "ACCT";
		String tags = "ACCT QORT ARRR";
		byte[] creationBytes = PirateChainACCTv3.buildQortalAT(tradeNativeAddress, tradeForeignPublicKey, tradeBotCreateRequest.qortAmount,
				tradeBotCreateRequest.foreignAmount, tradeBotCreateRequest.tradeTimeout);
		long amount = tradeBotCreateRequest.fundingQortAmount;

		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, aTType, tags, creationBytes, amount, Asset.QORT);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		fee = deployAtTransaction.calcRecommendedFee();
		deployAtTransactionData.setFee(fee);

		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, PirateChainACCTv3.NAME,
				State.BOB_WAITING_FOR_AT_CONFIRM.name(), State.BOB_WAITING_FOR_AT_CONFIRM.value,
				creator.getAddress(), atAddress, timestamp, tradeBotCreateRequest.qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				null, null,
				SupportedBlockchain.PIRATECHAIN.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.foreignAmount, null, null, null, pirateChainReceivingAccountInfo);

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
	 * Creates a trade-bot entry from the 'Alice' viewpoint, i.e. matching ARRR to an existing offer.
	 * <p>
	 * Requires a chosen trade offer from Bob, passed by <tt>crossChainTradeData</tt>
	 * and access to a PirateChain wallet via <tt>entropy58</tt>.
	 * <p>
	 * The <tt>crossChainTradeData</tt> contains the current trade offer state
	 * as extracted from the AT's data segment.
	 * <p>
	 * Access to a funded wallet is via 32 bytes of wallet entropy, Base58 encoded.
	 * Core derives the deterministic mnemonic from this entropy and the native
	 * wallet derives the shielded spending keys. The entropy is a wallet secret:
	 * it is stored as foreignKey in the trade-bot database and recovery backups
	 * so the wallet can be reopened for a refund.
	 * <p>
	 * If sufficient funds are available, <b>this method will actually fund the P2SH-A</b>
	 * with the PirateChain amount expected by 'Bob'.
	 * <p>
	 * If the PirateChain transaction is successfully broadcast to the network then
	 * we also send a MESSAGE to Bob's trade-bot to let them know.
	 * <p>
	 * The trade-bot entry is saved to the repository and the cross-chain trading process commences.
	 * <p>
	 * @param repository
	 * @param crossChainTradeData chosen trade OFFER that Alice wants to match
	 * @param entropy58 funded wallet's 32-byte entropy, Base58 encoded
	 * @return true if P2SH-A funding transaction successfully broadcast to PirateChain network, false otherwise
	 * @throws DataException
	 */
	public synchronized ResponseResult startResponse(Repository repository, ATData atData, ACCT acct, CrossChainTradeData crossChainTradeData, String entropy58, String receivingAddress) throws DataException {
		// Repeat the outer check under this lock: concurrent API requests may both have
		// observed no attempt before the first one committed its durable funding intent.
		if (repository.getCrossChainRepository().existsTradeWithAtExcludingStates(crossChainTradeData.qortalAtAddress, endStates))
			return ResponseResult.TRADE_ALREADY_EXISTS;
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

		TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, PirateChainACCTv3.NAME,
				State.ALICE_WAITING_FOR_FUNDING.name(), State.ALICE_WAITING_FOR_FUNDING.value,
				receivingAddress, crossChainTradeData.qortalAtAddress, now, crossChainTradeData.qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				secretA, hashOfSecretA,
				SupportedBlockchain.PIRATECHAIN.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				crossChainTradeData.expectedForeignAmount, entropy58, null, lockTimeA, receivingPublicKeyHash);

		// Check the entropy-backed wallet can fund P2SH to cover expectedForeignAmount.
		long p2shFee;
		try {
			p2shFee = PirateChain.getInstance().getP2shFee(now);
		} catch (ForeignBlockchainException e) {
			LOGGER.debug("Couldn't estimate PirateChain fees?");
			return ResponseResult.NETWORK_ISSUE;
		}

		tradeBotData.setFundingFee(p2shFee);
		// Persist the recovery keys BEFORE JNI. An uncertain funding result must survive a crash
		// even when the best-effort JSON backup fails. Funding is never automatically repeated.
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();
		LOGGER.info("Persisted ARRR funding recovery intent for AT {} (trade {})",
				tradeBotData.getAtAddress(), tradeBotData.getTradeNativeAddress());
		TradeBot.backupTradeBotData(repository, null);

		// Fee for redeem/refund is subtracted from P2SH-A balance.
		// Do not include fee for funding transaction as this is covered by buildSpend()
		long amountA = crossChainTradeData.expectedForeignAmount + p2shFee /*redeeming/refunding P2SH-A*/;

		// P2SH-A to be funded
		byte[] redeemScriptBytes = PirateChainHTLC.buildScript(tradeForeignPublicKey, lockTimeA, crossChainTradeData.creatorForeignPKH, hashOfSecretA);
		String p2shAddressT3 = PirateChain.getInstance().deriveP2shAddress(redeemScriptBytes); // Use t3 prefix when funding
		byte[] redeemScriptWithPrefixBytes = PirateChainHTLC.buildScriptWithPrefix(tradeForeignPublicKey, lockTimeA, crossChainTradeData.creatorForeignPKH, hashOfSecretA);
		String redeemScriptWithPrefix58 = Base58.encode(redeemScriptWithPrefixBytes);

		// Send to P2SH address
		try {
			String txid = PirateChain.getInstance().fundP2SH(entropy58, p2shAddressT3, amountA, redeemScriptWithPrefix58);
			LOGGER.info("fundingTxidHex: {} (redeem/refund reserve {})", txid, p2shFee);
			try {
				UnspentOutput funding = PirateChainHTLC.getFundingOutput(PirateChain.getInstance().getBlockchainProvider(),
						txid, p2shAddressT3, amountA);
				tradeBotData.setFundingOutputIndex(funding.index);
			} catch (ForeignBlockchainException | io.grpc.StatusRuntimeException e) {
				// A successful broadcast can precede lightwalletd visibility. Recovery re-reads the UTXO.
				LOGGER.warn("ARRR funding {} not yet available to capture output index; will recover from UTXO", txid, e);
			}
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WAITING_FOR_AT_LOCK,
					() -> "ARRR funding acknowledged; waiting for AT lock");

		} catch (ForeignBlockchainException.InsufficientFundsException | ForeignBlockchainException.TransactionRejectedException e) {
			// These typed native validation failures establish that no transaction was sent.
			repository.getCrossChainRepository().delete(tradePrivateKey);
			repository.saveChanges();
			LOGGER.warn("ARRR funding rejected before broadcast: {}", e.getMessage());
			return e instanceof ForeignBlockchainException.InsufficientFundsException
					? ResponseResult.BALANCE_ISSUE : ResponseResult.NETWORK_ISSUE;
		} catch (ForeignBlockchainException e) {
			LOGGER.warn("Unable to fund PirateChain P2SH-A: {}", e.getMessage());
			TradeBot.updateTradeBotState(repository, tradeBotData,
					() -> "Uncertain ARRR funding result; retaining recovery intent without resending");
			return ResponseResult.NETWORK_ISSUE;
		}

		announceFunding(repository, tradeBotData, crossChainTradeData);
		return ResponseResult.OK;
	}

	private void announceFunding(Repository repository, TradeBotData tradeBotData,
			CrossChainTradeData crossChainTradeData) throws DataException {
		// Attempt to send MESSAGE to Bob's Qortal trade address
		byte[] messageData = CrossChainUtils.buildOfferMessage(tradeBotData.getTradeForeignPublicKey(), tradeBotData.getHashOfSecret(), tradeBotData.getLockTimeA());
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

	}

	private void recoverFunding(Repository repository, TradeBotData trade, ATData at,
			CrossChainTradeData data) throws DataException, ForeignBlockchainException {
		PirateChain pirate = PirateChain.getInstance();
		byte[] script = PirateChainHTLC.buildScript(trade.getTradeForeignPublicKey(), trade.getLockTimeA(),
				data.creatorForeignPKH, trade.getHashOfSecret());
		String address = pirate.deriveP2shAddressBPrefix(script);
		UnspentOutput funding = PirateChainHTLC.getUnspentFundingOutput(pirate.getBlockchainProvider(), address,
				data.expectedForeignAmount + fundingFeeOrDefault(trade));
		BitcoinyHTLC.Status status = PirateChainHTLC.determineHtlcStatus(pirate.getBlockchainProvider(), address,
				data.expectedForeignAmount + fundingFeeOrDefault(trade), 3);
		if (funding == null && (status == BitcoinyHTLC.Status.UNFUNDED
				|| status == BitcoinyHTLC.Status.FUNDING_IN_PROGRESS)) {
			logRecoveryWait(trade, "funding-recovery", "HTLC_" + status,
					"htlc=" + address + " expectedAmount=" + data.expectedForeignAmount
					+ " fundingFee=" + trade.getFundingFee() + " confirmedFundingOutput=not-found; funding is not resubmitted");
			return;
		}
		if (funding != null) recordFundingDetails(trade, funding, data.expectedForeignAmount);
		TradeBot.updateTradeBotState(repository, trade, State.ALICE_WAITING_FOR_AT_LOCK,
				() -> "Recovered ARRR funding intent from the chain");
		if (!at.getIsFinished() && data.mode == AcctMode.OFFERING
				&& NTP.getTime() != null && NTP.getTime() < trade.getLockTimeA() * 1000L)
			announceFunding(repository, trade, data);
		handleAliceWaitingForAtLock(repository, trade, at, data);
	}

	public static String hex(byte[] bytes) {
		StringBuilder result = new StringBuilder();
		for (byte aByte : bytes) {
			result.append(String.format("%02x", aByte));
			// upper case
			// result.append(String.format("%02X", aByte));
		}
		return result.toString();
	}

	@Override
	public boolean canDelete(Repository repository, TradeBotData tradeBotData) throws DataException {
		State tradeBotState = State.valueOf(tradeBotData.getStateValue());
		if (tradeBotState == null)
			return true;

		if (tradeBotState == State.ALICE_WAITING_FOR_FUNDING || tradeBotState == State.ALICE_WAITING_FOR_AT_LOCK
				|| tradeBotState == State.ALICE_REFUNDING_A || (tradeBotState == State.ALICE_REFUNDED
				&& !PirateChainRefundState.isConfirmed(tradeBotData.getLastTransactionSignature()))) return false;

		if (tradeBotState == State.BOB_DONE)
			return PirateChainRefundState.isConfirmed(tradeBotData.getLastTransactionSignature());
		if (tradeBotState == State.ALICE_DONE) {
			ATData at = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
			if (at == null) return false;
			CrossChainTradeData data = PirateChainACCTv3.getInstance().populateTradeData(repository, at);
			return data != null && buyerReceivedQort(tradeBotData, at, data);
		}

		// If the AT doesn't exist then we might as well let the user tidy up
		if (!repository.getATRepository().exists(tradeBotData.getAtAddress()))
			return true;

		switch (tradeBotState) {
			case BOB_WAITING_FOR_AT_CONFIRM:
			case ALICE_DONE:
			case BOB_DONE:
			case ALICE_REFUNDED:
			case BOB_REFUNDED:
				return true;

			default:
				return false;
		}
	}

	@Override
	public void progress(Repository repository, TradeBotData tradeBotData) throws DataException, ForeignBlockchainException {
		State state = State.valueOf(tradeBotData.getStateValue());
		if (state == null) return;
		boolean audit = state == State.ALICE_DONE || ((state == State.ALICE_REFUNDED || state == State.BOB_DONE)
				&& !PirateChainRefundState.isConfirmed(tradeBotData.getLastTransactionSignature()));
		if (audit) {
			Long now = NTP.getTime();
			synchronized (this) {
				if (now == null || now < nextLegacyRefundAudit
						|| now < auditedLegacyRefunds.getOrDefault(tradeBotData.getTradeNativeAddress(), 0L)) return;
				auditedLegacyRefunds.put(tradeBotData.getTradeNativeAddress(), now + 5 * 60_000L);
				nextLegacyRefundAudit = now + 60_000L;
			}
		}
		ATData at = null;
		CrossChainTradeData data = null;
		if (state.requiresAtData || audit) {
			at = repository.getATRepository().fromATAddress(tradeBotData.getAtAddress());
			if (at == null) return;
			data = PirateChainACCTv3.getInstance().populateTradeData(repository, at);
			if (data == null) return;
		}
		switch (state) {
			case BOB_WAITING_FOR_AT_CONFIRM:
				handleBobWaitingForAtConfirm(repository, tradeBotData); break;
			case BOB_WAITING_FOR_MESSAGE:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, data);
				handleBobWaitingForMessage(repository, tradeBotData, at, data); break;
			case ALICE_WAITING_FOR_FUNDING:
				recoverFunding(repository, tradeBotData, at, data); break;
			case ALICE_WAITING_FOR_AT_LOCK:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, data);
				handleAliceWaitingForAtLock(repository, tradeBotData, at, data); break;
			case BOB_WAITING_FOR_AT_REDEEM:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, data);
				handleBobWaitingForAtRedeem(repository, tradeBotData, at, data); break;
			case ALICE_DONE:
				if (!buyerReceivedQort(tradeBotData, at, data)) {
					TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WAITING_FOR_AT_LOCK,
							() -> "Rechecking legacy buyer before QORT settlement");
					handleAliceWaitingForAtLock(repository, tradeBotData, at, data);
				}
				break;
			case BOB_DONE:
				if (audit) redeem(repository, tradeBotData, at, data); break;
			case ALICE_REFUNDING_A:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, data);
				refund(repository, tradeBotData, at, data); break;
			case ALICE_REFUNDED:
				if (audit) refund(repository, tradeBotData, at, data); break;
			default: break;
		}
		if (audit) synchronized (this) {
			if (!state.name().equals(tradeBotData.getState())
					|| PirateChainRefundState.isConfirmed(tradeBotData.getLastTransactionSignature()))
				auditedLegacyRefunds.remove(tradeBotData.getTradeNativeAddress());
			else {
				Long now = NTP.getTime();
				auditedLegacyRefunds.put(tradeBotData.getTradeNativeAddress(),
						(now == null ? System.currentTimeMillis() : now) + 6 * 60 * 60_000L);
			}
		}
	}

	private static boolean buyerReceivedQort(TradeBotData trade, ATData at, CrossChainTradeData data) {
		return at.getIsFinished() && data.mode == AcctMode.REDEEMED
				&& trade.getTradeNativeAddress().equals(data.qortalPartnerAddress);
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
	 * Assuming P2SH-A has at least expected PirateChain balance,
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

		PirateChain pirateChain = PirateChain.getInstance();

		String address = tradeBotData.getTradeNativeAddress();
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null, address, null, null, null);

		for (MessageTransactionData messageTransactionData : messageTransactionsData) {
			if (messageTransactionData.isText())
				continue;

			// We're expecting: HASH160(secret-A), Alice's PirateChain pubkeyhash and lockTime-A
			byte[] messageData = messageTransactionData.getData();
			PirateChainACCTv3.OfferMessageData offerMessageData = PirateChainACCTv3.extractOfferMessageData(messageData);
			if (offerMessageData == null)
				continue;

			byte[] aliceForeignPublicKey = offerMessageData.partnerPirateChainPublicKey;
			byte[] hashOfSecretA = offerMessageData.hashOfSecretA;
			int lockTimeA = (int) offerMessageData.lockTimeA;
			long messageTimestamp = messageTransactionData.getTimestamp();
			int refundTimeout = PirateChainACCTv3.calcRefundTimeout(messageTimestamp, lockTimeA);

			// Determine P2SH-A address and confirm funded
			byte[] redeemScriptA = PirateChainHTLC.buildScript(aliceForeignPublicKey, lockTimeA, tradeBotData.getTradeForeignPublicKey(), hashOfSecretA);
			String p2shAddress = pirateChain.deriveP2shAddressBPrefix(redeemScriptA); // Use 'b' prefix when checking status

			// The buyer's reserve belongs to this output, not this seller's current fee setting.
			final long minimumAmountA = tradeBotData.getForeignAmount();

			BitcoinyHTLC.Status htlcStatusA = PirateChainHTLC.determineHtlcStatus(pirateChain.getBlockchainProvider(), p2shAddress, minimumAmountA);

			switch (htlcStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
					// There might be another MESSAGE from someone else with an actually funded P2SH-A...
					continue;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// We've already redeemed this?
					TradeBot.updateTradeBotState(repository, tradeBotData, State.BOB_DONE,
							() -> String.format("P2SH-A %s already spent? Assuming trade complete", p2shAddress));
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
			byte[] outgoingMessageData = PirateChainACCTv3.buildTradeMessage(aliceNativeAddress, aliceForeignPublicKey, hashOfSecretA, lockTimeA, refundTimeout);
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
	 * In revealing a valid secret-A, Bob can then redeem the ARRR funds from P2SH-A.
	 * <p>
	 * @throws ForeignBlockchainException
	 */
	private void handleAliceWaitingForAtLock(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData crossChainTradeData) throws DataException, ForeignBlockchainException {
		if (aliceUnexpectedState(repository, tradeBotData, atData, crossChainTradeData))
			return;

		PirateChain pirateChain = PirateChain.getInstance();
		int lockTimeA = tradeBotData.getLockTimeA();

		// Refund P2SH-A if we've passed lockTime-A
		if (NTP.getTime() >= lockTimeA * 1000L) {
			byte[] redeemScriptA = PirateChainHTLC.buildScript(tradeBotData.getTradeForeignPublicKey(), lockTimeA, crossChainTradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
			String p2shAddress = pirateChain.deriveP2shAddressBPrefix(redeemScriptA); // Use 'b' prefix when checking status

			long p2shFee = fundingFeeOrDefault(tradeBotData);
			long minimumAmountA = crossChainTradeData.expectedForeignAmount + p2shFee;

			BitcoinyHTLC.Status htlcStatusA = PirateChainHTLC.determineHtlcStatus(pirateChain.getBlockchainProvider(), p2shAddress, minimumAmountA);
			logRecoveryWait(tradeBotData, "refund-entry", "HTLC_" + htlcStatusA,
					"htlc=" + p2shAddress + " minimumAmount=" + minimumAmountA
					+ " fundingFee=" + tradeBotData.getFundingFee() + " lockTime=" + lockTimeA);

			switch (htlcStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
				case FUNDED:
					break;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// Already redeemed?
					TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE,
							() -> String.format("P2SH-A %s already spent? Assuming trade completed", p2shAddress));
					return;

				case REFUND_IN_PROGRESS:
				case REFUNDED:
					TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
							() -> String.format("Checking confirmation of P2SH-A %s refund", p2shAddress));
					return;

			}

			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A,
					() -> atData.getIsFinished()
					? String.format("AT %s cancelled. Refunding P2SH-A %s - aborting trade", tradeBotData.getAtAddress(), p2shAddress)
					: String.format("LockTime-A reached, refunding P2SH-A %s - aborting trade", p2shAddress));

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
		int refundTimeout = PirateChainACCTv3.calcRefundTimeout(recipientMessageTimestamp, lockTimeA);

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
		byte[] messageData = PirateChainACCTv3.buildRedeemMessage(secretA, qortalReceivingAddress);
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

		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_WAITING_FOR_AT_LOCK,
				() -> String.format("Redeem message submitted to AT %s; awaiting settlement at %s",
						tradeBotData.getAtAddress(), qortalReceivingAddress));
	}

	/**
	 * Trade-bot is waiting for Alice to redeem Bob's AT, thus revealing secret-A which is required to spend the ARRR funds from P2SH-A.
	 * <p>
	 * It's possible that Bob's AT has reached its trading timeout and automatically refunded QORT back to Bob. In which case,
	 * trade-bot is done with this specific trade and finalizes in refunded state.
	 * <p>
	 * Assuming trade-bot can extract a valid secret-A from Alice's MESSAGE then trade-bot uses that to redeem the ARRR funds from P2SH-A
	 * to Bob's 'foreign'/PirateChain trade legacy-format address, as derived from trade private key.
	 * <p>
	 * (This could potentially be 'improved' to send ARRR to any address of Bob's choosing by changing the transaction output).
	 * <p>
	 * A broadcast remains pending until the ARRR spend has three confirmations.
	 * @throws ForeignBlockchainException
	 */
	private void handleBobWaitingForAtRedeem(Repository repository, TradeBotData trade,
			ATData at, CrossChainTradeData data) throws DataException, ForeignBlockchainException {
		if (at.getIsFinished() && (data.mode == AcctMode.REFUNDED || data.mode == AcctMode.CANCELLED)) {
			TradeBot.updateTradeBotState(repository, trade, State.BOB_REFUNDED, () -> "QORT offer refunded; no ARRR redemption");
			return;
		}
		redeem(repository, trade, at, data);
	}

	/** Seller recovery for scheduler, targeted API and redeemAll. ACKs remain pending. */
	public synchronized boolean redeem(Repository repository, TradeBotData trade, ATData at,
			CrossChainTradeData data) throws DataException, ForeignBlockchainException {
		refreshCheckpoint(repository, trade);
		if (trade.getState() == null || !trade.getState().startsWith("BOB_")
				|| trade.getTradePrivateKey() == null || trade.getTradePrivateKey().length != 32
				|| trade.getTradeForeignPublicKey() == null || trade.getTradeForeignPublicKey().length != 33
				|| data.creatorForeignPKH == null || data.creatorForeignPKH.length != 33
				|| trade.getReceivingAccountInfo() == null
				|| !Arrays.equals(trade.getTradeForeignPublicKey(), data.creatorForeignPKH)
				|| !at.getIsFinished() || data.mode != AcctMode.REDEEMED
				|| data.partnerForeignPKH == null || data.partnerForeignPKH.length != 33
				|| data.hashOfSecretA == null || data.hashOfSecretA.length != 20 || data.lockTimeA == null) return false;
		byte[] secret = PirateChainACCTv3.getInstance().findSecretA(repository, data);
		if (secret == null || secret.length != 32 || !Arrays.equals(Crypto.hash160(secret), data.hashOfSecretA)) return false;
		Long now = NTP.getTime();
		if (now == null) return false;
		PirateChain pirate = PirateChain.getInstance();
		String destination;
		try { destination = PirateChainAddress.fromReceivingAccountInfo(trade.getReceivingAccountInfo()); }
		catch (IllegalArgumentException e) { throw new DataException("Invalid stored ARRR receiving address", e); }
		byte[] script = PirateChainHTLC.buildScript(data.partnerForeignPKH, data.lockTimeA,
				data.creatorForeignPKH, data.hashOfSecretA);
		String address = pirate.deriveP2shAddressBPrefix(script);
		BitcoinyHTLC.Status status = PirateChainHTLC.determineHtlcStatus(pirate.getBlockchainProvider(), address,
				data.expectedForeignAmount, 3);
		if (status == BitcoinyHTLC.Status.REDEEMED) {
			PirateChainRefundState previous = PirateChainRefundState.decode(trade.getLastTransactionSignature());
			trade.setLastTransactionSignature(new PirateChainRefundState(0, now, previous == null ? null : previous.txid, true).encode());
			TradeBot.updateTradeBotState(repository, trade, State.BOB_DONE, () -> "ARRR redemption confirmed");
			return false;
		}
		if (status != BitcoinyHTLC.Status.FUNDED) {
			if (State.BOB_DONE.name().equals(trade.getState()) && !PirateChainRefundState.isConfirmed(trade.getLastTransactionSignature())
					&& status == BitcoinyHTLC.Status.REDEEM_IN_PROGRESS)
				TradeBot.updateTradeBotState(repository, trade, State.BOB_WAITING_FOR_AT_REDEEM, () -> "Checking legacy ARRR redemption confirmations");
			return false;
		}
		int height = pirate.getBlockchainProvider().getCurrentHeight();
		PirateChainRefundState checkpoint = PirateChainRefundState.decode(trade.getLastTransactionSignature());
		if (State.BOB_DONE.name().equals(trade.getState()) && (checkpoint == null || !checkpoint.confirmed)) {
			trade.setLastTransactionSignature(new PirateChainRefundState(height + 30, now, checkpoint == null ? null : checkpoint.txid).encode());
			TradeBot.updateTradeBotState(repository, trade, State.BOB_WAITING_FOR_AT_REDEEM, () -> "Legacy ARRR redemption still funded; quarantining before retry");
			return false;
		}
		if (!mayReplaceSpend(pirate, checkpoint, height, now, trade, "redeem", address)) return false;
		UnspentOutput funding = PirateChainHTLC.getUnspentFundingOutput(pirate.getBlockchainProvider(), address, data.expectedForeignAmount);
		if (funding == null) return false;
		recordFundingDetails(trade, funding, data.expectedForeignAmount);
		trade.setLastTransactionSignature(new PirateChainRefundState(height + 30, now, null).encode());
		TradeBot.updateTradeBotState(repository, trade, State.BOB_WAITING_FOR_AT_REDEEM, () -> "Broadcasting ARRR redemption; awaiting confirmations");
		try {
			String txid = pirate.redeemP2sh(pirate.deriveP2shAddress(script), destination, data.expectedForeignAmount,
					Base58.encode(script), Base58.encode(funding.hash), Base58.encode(secret),
					Base58.encode(trade.getTradePrivateKey()), fundingFeeOrDefault(trade), trade.getFundingOutputIndex());
			trade.setLastTransactionSignature(new PirateChainRefundState(height + 30, now, txid).encode());
			repository.getCrossChainRepository().save(trade); repository.saveChanges();
			LOGGER.info("ARRR redemption broadcast: {} (awaiting confirmations)", txid);
			return true;
		} catch (ForeignBlockchainException.TransactionRejectedException e) {
			trade.setLastTransactionSignature(null);
			repository.getCrossChainRepository().save(trade); repository.saveChanges();
			throw e;
		} catch (IllegalArgumentException e) {
			throw new ForeignBlockchainException("Invalid ARRR redemption transaction ID");
		}
	}

	private static void refreshCheckpoint(Repository repository, TradeBotData trade) throws DataException {
		TradeBotData latest = repository.getCrossChainRepository().getTradeBotData(trade.getTradePrivateKey());
		if (latest != null) {
			trade.setLastTransactionSignature(latest.getLastTransactionSignature());
			trade.setFundingFee(latest.getFundingFee());
			trade.setFundingOutputIndex(latest.getFundingOutputIndex());
			trade.setState(latest.getState()); trade.setStateValue(latest.getStateValue());
		}
	}

	private boolean mayReplaceSpend(PirateChain pirate, PirateChainRefundState checkpoint,
			int height, long now, TradeBotData trade, String phase, String address) throws ForeignBlockchainException {
		if (checkpoint == null) return true;
		if (!checkpoint.mayRetry(height, now)) {
			logRecoveryWait(trade, phase, "RETRY_QUARANTINE", "htlc=" + address + " height=" + height
					+ " retryAfterHeight=" + checkpoint.retryHeight + " nowMillis=" + now
					+ " retryAtMillis=" + (checkpoint.attemptedAt + 10 * 60_000L) + " txid=" + checkpoint.txid);
			return false;
		}
		if (checkpoint.txid != null) {
			try {
				int expiry = PirateChainTransaction.read(pirate.getBlockchainProvider().getRawTransaction(checkpoint.transactionHash())).expiryHeight;
				if (expiry == 0 || height <= expiry + 2) {
					logRecoveryWait(trade, phase, "SPEND_NOT_EXPIRED", "htlc=" + address + " txid=" + checkpoint.txid
							+ " height=" + height + " expiryHeight=" + expiry + " expirySafetyBlocks=2");
					return false;
				}
			} catch (org.qortal.transform.TransformationException e) {
				throw new ForeignBlockchainException("Unable to verify ARRR spend expiry");
			} catch (ForeignBlockchainException.NotFoundException e) {
				// The block/time quarantine passed; still require a fresh unspent output below.
			}
		}
		return true;
	}

	/**
	 * Trade-bot is attempting to refund P2SH-A.
	 * @throws ForeignBlockchainException
	 */
	private void handleAliceRefundingP2shA(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		refund(repository, tradeBotData, atData, tradeData);
	}

	/** Shared by the scheduler and manual recovery. An ACK is pending, never settlement. */
	public synchronized boolean refund(Repository repository, TradeBotData tradeBotData,
			ATData atData, CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		refreshCheckpoint(repository, tradeBotData);
		if (atData.getIsFinished() && tradeData.mode == AcctMode.REDEEMED
				&& tradeData.qortalPartnerAddress != null && tradeData.qortalPartnerAddress.equals(tradeBotData.getTradeNativeAddress())
				&& tradeBotData.getState() != null && tradeBotData.getState().startsWith("ALICE_")) {
			logRecoveryWait(tradeBotData, "refund", "QORT_ALREADY_RECEIVED", "atMode=" + tradeData.mode);
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE, () -> "QORT already redeemed by this buyer");
			return false;
		}
		if (!isRefundEligible(tradeBotData, atData, tradeData)) {
			logRecoveryWait(tradeBotData, "refund", "INELIGIBLE_RECORD",
					"atFinished=" + atData.getIsFinished() + " atMode=" + tradeData.mode
					+ "; requires a buyer record with its recovery keys and a refundable AT state");
			return false;
		}
		Long now = NTP.getTime();
		int lockTime = tradeBotData.getLockTimeA();
		if (now == null || now <= lockTime * 1000L) {
			logRecoveryWait(tradeBotData, "refund", now == null ? "CLOCK_UNAVAILABLE" : "LOCKTIME_NOT_REACHED",
					"nowMillis=" + now + " lockTime=" + lockTime);
			return false;
		}
		PirateChain pirate = PirateChain.getInstance();
		int medianTime = pirate.getMedianBlockTime();
		if (medianTime <= lockTime) {
			logRecoveryWait(tradeBotData, "refund", "MEDIAN_TIME_NOT_MATURE",
					"medianBlockTime=" + medianTime + " lockTime=" + lockTime + " nowMillis=" + now);
			return false;
		}
		byte[] script = PirateChainHTLC.buildScript(tradeBotData.getTradeForeignPublicKey(), lockTime,
				tradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
		String address = pirate.deriveP2shAddressBPrefix(script);
		if (tradeBotData.getFundingFee() == null) {
			// Recover legacy reserves before applying a minimum, including reserves below 10000.
			UnspentOutput legacyFunding = PirateChainHTLC.getUnspentFundingOutput(pirate.getBlockchainProvider(), address, tradeData.expectedForeignAmount);
			if (legacyFunding != null) recordFundingDetails(tradeBotData, legacyFunding, tradeData.expectedForeignAmount);
		}
		long minimum = tradeData.expectedForeignAmount + fundingFeeOrDefault(tradeBotData);
		BitcoinyHTLC.Status status = PirateChainHTLC.determineHtlcStatus(pirate.getBlockchainProvider(), address, minimum, 3);
		if (status == BitcoinyHTLC.Status.REFUNDED) {
			logRecoveryWait(tradeBotData, "refund", "HTLC_REFUNDED", "htlc=" + address + " requiredConfirmations=3");
			PirateChainRefundState previous = PirateChainRefundState.decode(tradeBotData.getLastTransactionSignature());
			tradeBotData.setLastTransactionSignature(new PirateChainRefundState(0, now, previous == null ? null : previous.txid, true).encode());
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDED, () -> "ARRR refund confirmed");
			return false;
		}
		if (status == BitcoinyHTLC.Status.REDEEMED) {
			logRecoveryWait(tradeBotData, "refund", "HTLC_REDEEMED", "htlc=" + address + " requiredConfirmations=3");
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_DONE, () -> "ARRR HTLC redeemed by seller");
			return false;
		}
		if (status == BitcoinyHTLC.Status.REFUND_IN_PROGRESS || status == BitcoinyHTLC.Status.REDEEM_IN_PROGRESS) {
			logRecoveryWait(tradeBotData, "refund", "HTLC_" + status,
					"htlc=" + address + " requiredConfirmations=3 minimumAmount=" + minimum);
			if (State.ALICE_REFUNDED.name().equals(tradeBotData.getState()))
				TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A, () -> "Rechecking an unconfirmed legacy ARRR spend");
			return false;
		}
		if (status != BitcoinyHTLC.Status.FUNDED) {
			logRecoveryWait(tradeBotData, "refund", "HTLC_" + status,
					"htlc=" + address + " expectedAmount=" + tradeData.expectedForeignAmount
					+ " fundingFee=" + tradeBotData.getFundingFee() + " minimumAmount=" + minimum);
			return false;
		}

		int height = pirate.getBlockchainProvider().getCurrentHeight();
		PirateChainRefundState checkpoint = PirateChainRefundState.decode(tradeBotData.getLastTransactionSignature());
		if ((checkpoint == null || !checkpoint.confirmed) && State.ALICE_REFUNDED.name().equals(tradeBotData.getState())) {
			logRecoveryWait(tradeBotData, "refund", "LEGACY_REFUND_STILL_FUNDED",
					"htlc=" + address + " height=" + height + "; saving a retry quarantine before replacement");
			tradeBotData.setLastTransactionSignature(new PirateChainRefundState(height + 30, now, checkpoint == null ? null : checkpoint.txid).encode());
			TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A, () -> "Legacy ARRR refund still funded; checking before retry");
			return false;
		}
		if (!mayReplaceSpend(pirate, checkpoint, height, now, tradeBotData, "refund", address)) return false;
		UnspentOutput funding = PirateChainHTLC.getUnspentFundingOutput(pirate.getBlockchainProvider(), address, minimum);
		if (funding == null) {
			logRecoveryWait(tradeBotData, "refund", "NO_CONFIRMED_FUNDING_OUTPUT",
					"htlc=" + address + " historyStatus=" + status + " minimumAmount=" + minimum
					+ "; waiting for a matching confirmed unspent output");
			return false; // Missing/unconfirmed spend: wait for a definitive chain result.
		}
		recordFundingDetails(tradeBotData, funding, tradeData.expectedForeignAmount);
		logRecoveryWait(tradeBotData, "refund", "PREPARING_NATIVE_REFUND",
				"htlc=" + address + " fundingOutputIndex=" + funding.index + " fundingValue=" + funding.value
				+ " amount=" + tradeData.expectedForeignAmount + " fee=" + tradeBotData.getFundingFee());
		String destination = pirate.getWalletAddress(tradeBotData.getForeignKey());
		// Commit before JNI so a crash or lost response cannot cause an immediate duplicate attempt.
		checkpoint = new PirateChainRefundState(height + 30, now, null);
		tradeBotData.setLastTransactionSignature(checkpoint.encode());
		TradeBot.updateTradeBotState(repository, tradeBotData, State.ALICE_REFUNDING_A, () -> "Broadcasting ARRR refund; awaiting confirmation");
		String txid;
		try {
			logRecoveryWait(tradeBotData, "refund", "SUBMITTING_NATIVE_REFUND", "htlc=" + address
					+ " fundingOutputIndex=" + funding.index + " checkpoint=committed");
			txid = pirate.refundP2sh(pirate.deriveP2shAddress(script), destination, tradeData.expectedForeignAmount,
					Base58.encode(script), Base58.encode(funding.hash), lockTime,
					Base58.encode(tradeBotData.getTradePrivateKey()), fundingFeeOrDefault(tradeBotData), tradeBotData.getFundingOutputIndex());
		} catch (ForeignBlockchainException.TransactionRejectedException e) {
			// The native validator did not broadcast; an uncertain-send quarantine does not apply.
			tradeBotData.setLastTransactionSignature(null);
			repository.getCrossChainRepository().save(tradeBotData);
			repository.saveChanges();
			LOGGER.error("Cleared ARRR refund checkpoint after deterministic rejection for AT {} (trade {})",
					tradeBotData.getAtAddress(), tradeBotData.getTradeNativeAddress(), e);
			throw e;
		}
		try {
			tradeBotData.setLastTransactionSignature(new PirateChainRefundState(height + 30, now, txid).encode());
		} catch (IllegalArgumentException e) {
			throw new ForeignBlockchainException("Invalid ARRR refund transaction ID");
		}
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();
		LOGGER.info("ARRR refund broadcast: {} for AT {} (trade {}), awaiting confirmations",
				txid, tradeBotData.getAtAddress(), tradeBotData.getTradeNativeAddress());
		return true;
	}

	/** Finished offers can contain other buyers' independent refundable P2SH outputs. */
	public static boolean isRefundEligible(TradeBotData trade, ATData at, CrossChainTradeData data) {
		if (trade.getState() == null || !trade.getState().startsWith("ALICE_")
				|| trade.getForeignKey() == null || trade.getForeignKey().isEmpty() || trade.getLockTimeA() == null
				|| trade.getLockTimeA() <= 0 || trade.getTradePrivateKey() == null || trade.getTradePrivateKey().length != 32
				|| trade.getHashOfSecret() == null || trade.getHashOfSecret().length != 20
				|| trade.getTradeForeignPublicKey() == null || trade.getTradeForeignPublicKey().length != 33
				|| (SupportedBlockchain.PIRATECHAIN.name().equals(trade.getForeignBlockchain())
						&& (data.creatorForeignPKH == null || data.creatorForeignPKH.length != 33))) return false;
		if (!at.getIsFinished()) return true;
		if (data.mode == AcctMode.REFUNDED || data.mode == AcctMode.CANCELLED) return true;
		return data.mode == AcctMode.REDEEMED && data.qortalPartnerAddress != null
				&& !data.qortalPartnerAddress.equals(trade.getTradeNativeAddress());
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

	private static long fundingFeeOrDefault(TradeBotData trade) {
		if (trade.getFundingFee() != null) return trade.getFundingFee();
		LOGGER.warn("ARRR funding fee unavailable for AT {} (trade {}); falling back to 10000",
				trade.getAtAddress(), trade.getTradeNativeAddress());
		return 10000L;
	}

	private static void recordFundingDetails(TradeBotData trade, UnspentOutput funding, long expectedAmount) {
		if (trade.getFundingFee() == null) {
			trade.setFundingFee(funding.value - expectedAmount);
			LOGGER.info("Recovered ARRR funding fee {} from output {} for AT {} (trade {})",
					trade.getFundingFee(), funding.index, trade.getAtAddress(), trade.getTradeNativeAddress());
		}
		trade.setFundingOutputIndex(funding.index);
	}

}
