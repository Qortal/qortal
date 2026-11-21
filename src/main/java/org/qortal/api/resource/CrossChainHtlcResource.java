package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.qortal.api.*;
import org.qortal.api.model.CrossChainBitcoinyHTLCStatus;
import org.qortal.api.model.crosschain.HtlcRecoveryApproval;
import org.qortal.api.model.crosschain.HtlcRecoveryQuote;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.crosschain.*;
import org.qortal.controller.tradebot.PirateChainACCTv3TradeBot;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

@Path("/crosschain/htlc")
@Tag(name = "Cross-Chain (Hash time-locked contracts)")
public class CrossChainHtlcResource {

	private static final Logger LOGGER = LogManager.getLogger(CrossChainHtlcResource.class);

	@Context
	HttpServletRequest request;

	@GET
	@Path("/address/{blockchain}/{refundPKH}/{locktime}/{redeemPKH}/{hashOfSecret}")
	@Operation(
		summary = "Returns HTLC address based on trade info",
		description = "Public key hashes (PKH) and hash of secret should be 20 bytes (base58 encoded). Locktime is seconds since epoch.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_CRITERIA})
	public String deriveHtlcAddress(@PathParam("blockchain") String blockchainName,
			@PathParam("refundPKH") String refundPKH,
			@PathParam("locktime") int lockTime,
			@PathParam("redeemPKH") String redeemPKH,
			@PathParam("hashOfSecret") String hashOfSecret) {
		SupportedBlockchain blockchain = SupportedBlockchain.valueOf(blockchainName);
		if (blockchain == null || blockchain == SupportedBlockchain.PIRATECHAIN)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] refunderPubKeyHash;
		byte[] redeemerPubKeyHash;
		byte[] decodedHashOfSecret;

		try {
			refunderPubKeyHash = Base58.decode(refundPKH);
			redeemerPubKeyHash = Base58.decode(redeemPKH);

			if (refunderPubKeyHash.length != 20 || redeemerPubKeyHash.length != 20)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);
		}

		try {
			decodedHashOfSecret = Base58.decode(hashOfSecret);
			if (decodedHashOfSecret.length != 20)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		byte[] redeemScript = BitcoinyHTLC.buildScript(refunderPubKeyHash, lockTime, redeemerPubKeyHash, decodedHashOfSecret);

		Bitcoiny bitcoiny = (Bitcoiny) blockchain.getInstance();

		return bitcoiny.deriveP2shAddress(redeemScript);
	}

	@GET
	@Path("/status/{blockchain}/{refundPKH}/{locktime}/{redeemPKH}/{hashOfSecret}")
	@Operation(
		summary = "Checks HTLC status",
		description = "Public key hashes (PKH) and hash of secret should be 20 bytes (base58 encoded). Locktime is seconds since epoch.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = CrossChainBitcoinyHTLCStatus.class))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN})
	@SecurityRequirement(name = "apiKey")
	public CrossChainBitcoinyHTLCStatus checkHtlcStatus(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchainName,
			@PathParam("refundPKH") String refundPKH,
			@PathParam("locktime") int lockTime,
			@PathParam("redeemPKH") String redeemPKH,
			@PathParam("hashOfSecret") String hashOfSecret) {
		Security.checkApiCallAllowed(request);

		SupportedBlockchain blockchain = SupportedBlockchain.valueOf(blockchainName);
		if (blockchain == null || blockchain == SupportedBlockchain.PIRATECHAIN)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] refunderPubKeyHash;
		byte[] redeemerPubKeyHash;
		byte[] decodedHashOfSecret;

		try {
			refunderPubKeyHash = Base58.decode(refundPKH);
			redeemerPubKeyHash = Base58.decode(redeemPKH);

			if (refunderPubKeyHash.length != 20 || redeemerPubKeyHash.length != 20)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);
		}

		try {
			decodedHashOfSecret = Base58.decode(hashOfSecret);
			if (decodedHashOfSecret.length != 20)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		byte[] redeemScript = BitcoinyHTLC.buildScript(refunderPubKeyHash, lockTime, redeemerPubKeyHash, decodedHashOfSecret);

		Bitcoiny bitcoiny = (Bitcoiny) blockchain.getInstance();

		String p2shAddress = bitcoiny.deriveP2shAddress(redeemScript);

		long now = NTP.getTime();

		try {
			int medianBlockTime = bitcoiny.getMedianBlockTime();

			// Check P2SH is funded
			long p2shBalance = bitcoiny.getConfirmedBalance(p2shAddress.toString());

			CrossChainBitcoinyHTLCStatus htlcStatus = new CrossChainBitcoinyHTLCStatus();
			htlcStatus.bitcoinP2shAddress = p2shAddress;
			htlcStatus.bitcoinP2shBalance = BigDecimal.valueOf(p2shBalance, 8);

			List<TransactionOutput> fundingOutputs = bitcoiny.getUnspentOutputs(p2shAddress.toString(), false);

			if (p2shBalance > 0L && !fundingOutputs.isEmpty()) {
				htlcStatus.canRedeem = now >= medianBlockTime * 1000L;
				htlcStatus.canRefund = now >= lockTime * 1000L;
			}

			if (now >= medianBlockTime * 1000L) {
				// See if we can extract secret
				htlcStatus.secret = BitcoinyHTLC.findHtlcSecret(bitcoiny, htlcStatus.bitcoinP2shAddress);
			}

			return htlcStatus;
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/redeem/{ataddress}")
	@Operation(
			summary = "Redeems HTLC associated with supplied AT",
			description = "To be used by a QORT seller (Bob) who needs to redeem ARRR/LTC/DOGE/etc proceeds that are stuck in a P2SH.<br>" +
					"This requires Bob's trade bot data to be present in the database for this AT.<br>" +
					"It will fail if the buyer has yet to redeem the QORT held in the AT.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN})
	@SecurityRequirement(name = "apiKey")
	public boolean redeemHtlc(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("ataddress") String atAddress,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Optional approval from the matching recovery preview. Review a fresh quote before submitting its tradeAddress, quoteId and expiresAt. Omit the body for normal recovery without an extra fee deduction.",
					required = false,
					content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = HtlcRecoveryApproval.class))) HtlcRecoveryApproval approval) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			boolean found = false, broadcast = false;
			ForeignBlockchainException failure = null;
			for (TradeBotData trade : repository.getCrossChainRepository().getAllTradeBotData()) {
				if (!Objects.equals(atAddress, trade.getAtAddress())) continue;
				if (approval != null && !Objects.equals(approval.tradeAddress, trade.getTradeNativeAddress())) continue;
				found = true;
				try { broadcast |= approval == null ? doRedeemHtlc(repository, trade) : submitApprovedRecovery(repository, trade, false, approval); }
				catch (ForeignBlockchainException e) { failure = e; }
			}
			if (!found) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			if (!broadcast && failure != null) throw failure;
			return broadcast;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/redeemAll")
	@Operation(
			summary = "Redeems HTLC for all applicable ATs in tradebot data",
			description = "To be used by a QORT seller (Bob) who needs to redeem ARRR/LTC/DOGE/etc proceeds that are stuck in P2SH transactions.<br>" +
					"This requires Bob's trade bot data to be present in the database for any ATs that need redeeming.<br>" +
					"Returns true if a new spend was broadcast for at least one attempt. ARRR remains pending until three confirmations. More detail is available in the logs.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN})
	@SecurityRequirement(name = "apiKey")
	public boolean redeemAllHtlc(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);
		boolean broadcast = false;
		try (final Repository repository = RepositoryManager.getRepository()) {
			for (TradeBotData trade : repository.getCrossChainRepository().getAllTradeBotData()) {
				try { broadcast |= doRedeemHtlc(repository, trade); }
				catch (ForeignBlockchainException | ApiException | IllegalArgumentException | DataException e) {
					repository.discardChanges();
					if (e instanceof BitcoinyHtlcFees.ApprovalRequiredException) {
						BitcoinyHtlcRecovery.logApprovalRequired(LOGGER, trade, (BitcoinyHtlcFees.ApprovalRequiredException)e);
						continue;
					}
					LOGGER.error("Unable to redeem HTLC attempt for AT {} (trade {}); continuing other attempts",
							trade.getAtAddress(), trade.getTradeNativeAddress(), e);
				}
			}
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
		return broadcast;
	}

	private boolean doRedeemHtlc(Repository repository, TradeBotData trade) throws DataException, ForeignBlockchainException {
		synchronized (TradeBot.getInstance()) {
			repository.discardChanges();
			trade = repository.getCrossChainRepository().getTradeBotData(trade.getTradePrivateKey());
			if (trade == null || BitcoinyHtlcRecovery.holdPending(repository, trade)) return false;
			if (trade.getAtAddress() == null || trade.getState() == null || !trade.getState().startsWith("BOB_")) return false;
			ATData at = repository.getATRepository().fromATAddress(trade.getAtAddress());
			if (at == null || !at.getIsFinished()) return false;
			ACCT acct = SupportedBlockchain.getAcctByCodeHash(at.getCodeHash());
			if (acct == null || !(acct.getBlockchain() instanceof Bitcoiny)) return false;
			CrossChainTradeData data = acct.populateTradeData(repository, at);
			if (data == null || data.mode != AcctMode.REDEEMED) return false;
			Bitcoiny coin = (Bitcoiny) acct.getBlockchain();
			if (Objects.equals(coin.getCurrencyCode(), "ARRR"))
				return PirateChainACCTv3TradeBot.getInstance().redeem(repository, trade, at, data);
			byte[] secret = acct.findSecretA(repository, data);
			if (secret == null || secret.length != 32 || !java.util.Arrays.equals(Crypto.hash160(secret), data.hashOfSecretA)
					|| trade.getTradePrivateKey() == null || trade.getTradePrivateKey().length != 32
					|| data.lockTimeA == null || trade.getReceivingAccountInfo() == null
					|| trade.getReceivingAccountInfo().length != 20) return false;
			byte[] script = BitcoinyHTLC.buildScript(data.partnerForeignPKH, data.lockTimeA, data.creatorForeignPKH, data.hashOfSecretA);
			String address = coin.deriveP2shAddress(script);
			if (BitcoinyHTLC.determineHtlcStatus(coin.getBlockchainProvider(), address, BitcoinyHtlcRecovery.paymentAmount(trade, data)) != BitcoinyHTLC.Status.FUNDED)
				return false;
			Transaction spend = BitcoinyHtlcFees.buildRedeemTransaction(coin, Coin.valueOf(BitcoinyHtlcRecovery.paymentAmount(trade, data)),
					ECKey.fromPrivate(trade.getTradePrivateKey()), coin.getUnspentOutputs(address, false), script, secret, trade.getReceivingAccountInfo());
			coin.broadcastTransaction(spend);
			return true;
		}
	}

	@POST
	@Path("/refund/{ataddress}")
	@Operation(
			summary = "Refunds HTLC associated with supplied AT",
			description = "To be used by a QORT buyer (Alice) who needs to refund their ARRR/LTC/DOGE/etc that is stuck in a P2SH.<br>" +
					"This requires Alice's trade bot data to be present in the database for this AT.<br>" +
					"No new broadcast occurs while immature, pending or already spent. ARRR requires its recorded locktime and chain median time to pass; a true result is a broadcast acknowledgement, not settlement.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN})
	@SecurityRequirement(name = "apiKey")
	public boolean refundHtlc(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("ataddress") String atAddress,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Optional approval from the matching recovery preview. Review a fresh quote before submitting its tradeAddress, quoteId and expiresAt. Omit the body for normal recovery without an extra fee deduction.",
					required = false,
					content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = HtlcRecoveryApproval.class))) HtlcRecoveryApproval approval) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			boolean found = false;
			boolean broadcast = false;
			ForeignBlockchainException failure = null;
			for (TradeBotData trade : repository.getCrossChainRepository().getAllTradeBotData()) {
				if (!Objects.equals(atAddress, trade.getAtAddress())) continue;
				if (approval != null && !Objects.equals(approval.tradeAddress, trade.getTradeNativeAddress())) continue;
				found = true;
				try {
					broadcast |= approval == null ? doRefundHtlc(repository, trade) : submitApprovedRecovery(repository, trade, true, approval);
				} catch (ForeignBlockchainException e) {
					failure = e; // A bad attempt must not prevent recovery of another wallet's attempt.
				}
			}
			if (!found) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			if (!broadcast && failure != null) throw failure;
			return broadcast;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}


	@POST
	@Path("/refundAll")
	@Operation(
			summary = "Refunds HTLC for all applicable ATs in tradebot data",
			description = "To be used by a QORT buyer (Alice) who needs to refund their ARRR/LTC/DOGE/etc proceeds that are stuck in P2SH transactions.<br>" +
					"This requires Alice's trade bot data to be present in the database for this AT.<br>" +
					"No new broadcast occurs while immature, pending or already spent. ARRR requires its recorded locktime and chain median time to pass; a true result is a broadcast acknowledgement, not settlement.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN})
	@SecurityRequirement(name = "apiKey")
	public boolean refundAllHtlc(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);
		boolean broadcast = false;
		try (final Repository repository = RepositoryManager.getRepository()) {
			for (TradeBotData trade : repository.getCrossChainRepository().getAllTradeBotData()) {
				try {
					broadcast |= doRefundHtlc(repository, trade);
				} catch (ForeignBlockchainException | ApiException | IllegalArgumentException | DataException e) {
					repository.discardChanges();
					if (e instanceof BitcoinyHtlcFees.ApprovalRequiredException) {
						BitcoinyHtlcRecovery.logApprovalRequired(LOGGER, trade, (BitcoinyHtlcFees.ApprovalRequiredException)e);
						continue;
					}
					LOGGER.error("Unable to refund HTLC attempt for AT {} (trade {}); continuing other attempts",
							trade.getAtAddress(), trade.getTradeNativeAddress(), e);
				}
			}
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
		return broadcast;
	}

	private boolean doRefundHtlc(Repository repository, TradeBotData trade) throws DataException, ForeignBlockchainException {
		synchronized (TradeBot.getInstance()) {
			repository.discardChanges();
			trade = repository.getCrossChainRepository().getTradeBotData(trade.getTradePrivateKey());
			if (trade == null || BitcoinyHtlcRecovery.holdPending(repository, trade)) return false;

			if (trade.getAtAddress() == null || trade.getForeignKey() == null || trade.getLockTimeA() == null
					|| trade.getState() == null || !trade.getState().startsWith("ALICE_")) return false;
			ATData at = repository.getATRepository().fromATAddress(trade.getAtAddress());
			if (at == null) return false;
			ACCT acct = SupportedBlockchain.getAcctByCodeHash(at.getCodeHash());
			if (acct == null || !(acct.getBlockchain() instanceof Bitcoiny)) return false;
			CrossChainTradeData data = acct.populateTradeData(repository, at);
			if (data == null || !PirateChainACCTv3TradeBot.isRefundEligible(trade, at, data)) return false;
			Bitcoiny bitcoiny = (Bitcoiny) acct.getBlockchain();
			if (Objects.equals(bitcoiny.getCurrencyCode(), "ARRR"))
				return PirateChainACCTv3TradeBot.getInstance().refund(repository, trade, at, data);
			int lockTime = trade.getLockTimeA();
			Long now = NTP.getTime();
			if (now == null || now <= lockTime * 1000L || bitcoiny.getMedianBlockTime() <= lockTime) return false;
			long minimum = BitcoinyHtlcRecovery.paymentAmount(trade, data);
			byte[] script = BitcoinyHTLC.buildScript(trade.getTradeForeignPublicKeyHash(), lockTime,
					data.creatorForeignPKH, trade.getHashOfSecret());
			String address = bitcoiny.deriveP2shAddress(script);
			if (BitcoinyHTLC.determineHtlcStatus(bitcoiny.getBlockchainProvider(), address, minimum) != BitcoinyHTLC.Status.FUNDED)
				return false;
			// Destination and signing key must belong to the very same buyer record.
			String destination = bitcoiny.getUnusedReceiveAddress(trade.getForeignKey());
			Address receiving = Address.fromString(bitcoiny.getNetworkParameters(), destination);
			if (receiving.getOutputScriptType() != Script.ScriptType.P2PKH)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			Transaction refund = BitcoinyHtlcFees.buildRefundTransaction(bitcoiny, Coin.valueOf(BitcoinyHtlcRecovery.paymentAmount(trade, data)),
					ECKey.fromPrivate(trade.getTradePrivateKey()), bitcoiny.getUnspentOutputs(address, false), script, lockTime, receiving.getHash());
			bitcoiny.broadcastTransaction(refund);
			return true;
		}
	}


	// Empty-body calls retain their existing behavior; extra fees always require a quote.
	public boolean redeemHtlc(String apiKey, String atAddress) { return redeemHtlc(apiKey, atAddress, null); }
	public boolean refundHtlc(String apiKey, String atAddress) { return refundHtlc(apiKey, atAddress, null); }

	@GET
	@Path("/redeem/{ataddress}/quote")
	@Produces(MediaType.APPLICATION_JSON)
	@SecurityRequirement(name = "apiKey")
	@Operation(summary = "Preview exact HTLC redemption fees and net payout; does not sign or broadcast")
	public List<HtlcRecoveryQuote> previewRedeem(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("ataddress") String atAddress, @QueryParam("tradeAddress") String tradeAddress) {
		return previewRecovery(atAddress, tradeAddress, false);
	}

	@GET
	@Path("/refund/{ataddress}/quote")
	@Produces(MediaType.APPLICATION_JSON)
	@SecurityRequirement(name = "apiKey")
	@Operation(summary = "Preview exact HTLC refund fees and net payout; does not sign or broadcast")
	public List<HtlcRecoveryQuote> previewRefund(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("ataddress") String atAddress, @QueryParam("tradeAddress") String tradeAddress) {
		return previewRecovery(atAddress, tradeAddress, true);
	}

	private List<HtlcRecoveryQuote> previewRecovery(String atAddress, String tradeAddress, boolean refund) {
		Security.checkApiCallAllowed(request);
		try (Repository repository = RepositoryManager.getRepository()) {
			List<HtlcRecoveryQuote> quotes = new ArrayList<>();
			for (TradeBotData trade : repository.getCrossChainRepository().getAllTradeBotData()) {
				if (!Objects.equals(atAddress, trade.getAtAddress()) || trade.getState() == null
						|| !trade.getState().startsWith(refund ? "ALICE_" : "BOB_")
						|| (tradeAddress != null && !Objects.equals(tradeAddress, trade.getTradeNativeAddress()))) continue;
				quotes.add(BitcoinyHtlcRecovery.preview(repository, trade, refund));
			}
			if (quotes.isEmpty()) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			return quotes;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (ForeignBlockchainException | IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	private boolean submitApprovedRecovery(Repository repository, TradeBotData trade, boolean refund, HtlcRecoveryApproval approval)
			throws DataException, ForeignBlockchainException {
		synchronized (TradeBot.getInstance()) {
			repository.discardChanges();
			boolean broadcast = BitcoinyHtlcRecovery.submit(repository, trade, refund, approval);
			LOGGER.info("Explicitly approved HTLC {} submitted for AT {} (trade {}); awaiting confirmations",
					refund ? "refund" : "redemption", trade.getAtAddress(), trade.getTradeNativeAddress());
			return broadcast;
		}
	}

}
