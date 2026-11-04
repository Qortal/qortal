package org.qortal.api.resource;

import com.google.common.hash.HashCode;
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
import java.util.Objects;
import java.util.stream.Collectors;

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
		if (blockchain == null)
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
		if (blockchain == null)
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
			description = "To be used by a QORT seller (Bob) who needs to redeem LTC/DOGE/etc proceeds that are stuck in a P2SH.<br>" +
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
	public boolean redeemHtlc(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("ataddress") String atAddress) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			if (atData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			ACCT acct = SupportedBlockchain.getAcctByCodeHash(atData.getCodeHash());
			if (acct == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
			if (crossChainTradeData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Attempt to find secret from the buyer's message to AT
			byte[] decodedSecret = acct.findSecretA(repository, crossChainTradeData);
			if (decodedSecret == null) {
				LOGGER.info(() -> String.format("Unable to find secret-A from redeem message to AT %s", atAddress));
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}

			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			TradeBotData tradeBotData = allTradeBotData.stream().filter(tradeBotDataItem -> tradeBotDataItem.getAtAddress().equals(atAddress)).findFirst().orElse(null);

			// Search for the tradePrivateKey in the tradebot data
			byte[] decodedPrivateKey = null;
			if (tradeBotData != null)
				decodedPrivateKey = tradeBotData.getTradePrivateKey();

			// Search for the foreign blockchain receiving address in the tradebot data
			byte[] foreignBlockchainReceivingAccountInfo = null;
			if (tradeBotData != null)
				// Use receiving address PKH from tradebot data
				foreignBlockchainReceivingAccountInfo = tradeBotData.getReceivingAccountInfo();

			return this.doRedeemHtlc(atAddress, decodedPrivateKey, decodedSecret, foreignBlockchainReceivingAccountInfo);

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/redeemAll")
	@Operation(
			summary = "Redeems HTLC for all applicable ATs in tradebot data",
			description = "To be used by a QORT seller (Bob) who needs to redeem LTC/DOGE/etc proceeds that are stuck in P2SH transactions.<br>" +
					"This requires Bob's trade bot data to be present in the database for any ATs that need redeeming.<br>" +
					"Returns true if at least one trade is redeemed. More detail is available in the log.txt.* file.",
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
		boolean success = false;

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();

			for (TradeBotData tradeBotData : allTradeBotData) {
				String atAddress = tradeBotData.getAtAddress();
				if (atAddress == null) {
					LOGGER.info("Missing AT address in tradebot data", atAddress);
					continue;
				}

				String tradeState = tradeBotData.getState();
				if (tradeState == null) {
					LOGGER.info("Missing trade state for AT {}", atAddress);
					continue;
				}

				if (tradeState.startsWith("ALICE")) {
					LOGGER.info("AT {} isn't redeemable because it is a buy order", atAddress);
					continue;
				}

				ATData atData = repository.getATRepository().fromATAddress(atAddress);
				if (atData == null) {
					LOGGER.info("Couldn't find AT with address {}", atAddress);
					continue;
				}

				ACCT acct = SupportedBlockchain.getAcctByCodeHash(atData.getCodeHash());
				if (acct == null) {
					continue;
				}

				Bitcoiny bitcoiny = (Bitcoiny) acct.getBlockchain();
				if (Objects.equals(bitcoiny.getCurrencyCode(), "ARRR")) {
					LOGGER.info("Skipping AT {} because ARRR is currently unsupported", atAddress);
					continue;
				}

				CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
				if (crossChainTradeData == null) {
					LOGGER.info("Couldn't find crosschain trade data for AT {}", atAddress);
					continue;
				}

				// Attempt to find secret from the buyer's message to AT
				byte[] decodedSecret = acct.findSecretA(repository, crossChainTradeData);
				if (decodedSecret == null) {
					LOGGER.info("Unable to find secret-A from redeem message to AT {}", atAddress);
					continue;
				}

				// Search for the tradePrivateKey in the tradebot data
				byte[] decodedPrivateKey = tradeBotData.getTradePrivateKey();

				// Search for the foreign blockchain receiving address PKH in the tradebot data
				byte[] foreignBlockchainReceivingAccountInfo = tradeBotData.getReceivingAccountInfo();

				try {
					LOGGER.info("Attempting to redeem P2SH balance associated with AT {}...", atAddress);
					boolean redeemed = this.doRedeemHtlc(atAddress, decodedPrivateKey, decodedSecret, foreignBlockchainReceivingAccountInfo);
					if (redeemed) {
						LOGGER.info("Redeemed P2SH balance associated with AT {}", atAddress);
						success = true;
					}
					else {
						LOGGER.info("Couldn't redeem P2SH balance associated with AT {}. Already redeemed?", atAddress);
					}
				} catch (ApiException e) {
					LOGGER.info("Couldn't redeem P2SH balance associated with AT {}. Missing data?", atAddress);
				}
			}

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		return success;
	}

	private boolean doRedeemHtlc(String atAddress, byte[] decodedTradePrivateKey, byte[] decodedSecret,
								 byte[] foreignBlockchainReceivingAccountInfo) {
		try (final Repository repository = RepositoryManager.getRepository()) {

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			if (atData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			ACCT acct = SupportedBlockchain.getAcctByCodeHash(atData.getCodeHash());
			if (acct == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
			if (crossChainTradeData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Validate trade private key
			if (decodedTradePrivateKey == null || decodedTradePrivateKey.length != 32)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Validate secret
			if (decodedSecret == null || decodedSecret.length != 32)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Validate receiving address
			if (foreignBlockchainReceivingAccountInfo == null || foreignBlockchainReceivingAccountInfo.length != 20)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Make sure the receiving address isn't a QORT address, given that we can share the same field for both QORT and foreign blockchains
			if (Crypto.isValidAddress(foreignBlockchainReceivingAccountInfo))
				if (Base58.encode(foreignBlockchainReceivingAccountInfo).startsWith("Q"))
					// This is likely a QORT address, not a foreign blockchain
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);


			// Use secret-A to redeem P2SH-A

			Bitcoiny bitcoiny = (Bitcoiny) acct.getBlockchain();

			int lockTime = crossChainTradeData.lockTimeA;
			byte[] redeemScriptA = BitcoinyHTLC.buildScript(crossChainTradeData.partnerForeignPKH, lockTime, crossChainTradeData.creatorForeignPKH, crossChainTradeData.hashOfSecretA);
			String p2shAddressA = bitcoiny.deriveP2shAddress(redeemScriptA);
			LOGGER.info(String.format("Redeeming P2SH address: %s", p2shAddressA));

			// Fee for redeem/refund is subtracted from P2SH-A balance.
			long feeTimestamp = calcFeeTimestamp(lockTime, crossChainTradeData.tradeTimeout);
			long p2shFee = bitcoiny.getP2shFee(feeTimestamp);
			long minimumAmountA = crossChainTradeData.expectedForeignAmount + p2shFee;
			BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoiny.getBlockchainProvider(), p2shAddressA, minimumAmountA);

			switch (htlcStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
					// P2SH-A suddenly not funded? Our best bet at this point is to hope for AT auto-refund
					return false;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					// Double-check that we have redeemed P2SH-A...
					return false;

				case REFUND_IN_PROGRESS:
				case REFUNDED:
					// Wait for AT to auto-refund
					return false;

				case FUNDED: {
					Coin redeemAmount = Coin.valueOf(crossChainTradeData.expectedForeignAmount);
					ECKey redeemKey = ECKey.fromPrivate(decodedTradePrivateKey);
					List<TransactionOutput> fundingOutputs = bitcoiny.getUnspentOutputs(p2shAddressA, false);

					Transaction p2shRedeemTransaction = BitcoinyHTLC.buildRedeemTransaction(bitcoiny.getNetworkParameters(), redeemAmount, redeemKey,
							fundingOutputs, redeemScriptA, decodedSecret, foreignBlockchainReceivingAccountInfo);

					bitcoiny.broadcastTransaction(p2shRedeemTransaction);
					LOGGER.info(String.format("P2SH address %s redeemed!", p2shAddressA));
					return true;
				}
			}

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, e);
		}

		return false;
	}

	@POST
	@Path("/refund/{ataddress}")
	@Operation(
			summary = "Refunds HTLC associated with supplied AT",
			description = "To be used by a QORT buyer (Alice) who needs to refund their LTC/DOGE/etc that is stuck in a P2SH.<br>" +
					"This requires Alice's trade bot data to be present in the database for this AT.<br>" +
					"It will fail if it's already redeemed by the seller, or if the lockTime (60 minutes) hasn't passed yet.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN})
	@SecurityRequirement(name = "apiKey")
	public boolean refundHtlc(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("ataddress") String atAddress) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			boolean found = false;
			boolean broadcast = false;
			ForeignBlockchainException failure = null;
			for (TradeBotData trade : repository.getCrossChainRepository().getAllTradeBotData()) {
				if (!Objects.equals(atAddress, trade.getAtAddress())) continue;
				found = true;
				try {
					broadcast |= doRefundHtlc(repository, trade);
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
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, e);
		}
	}


	@POST
	@Path("/refundAll")
	@Operation(
			summary = "Refunds HTLC for all applicable ATs in tradebot data",
			description = "To be used by a QORT buyer (Alice) who needs to refund their LTC/DOGE/etc proceeds that are stuck in P2SH transactions.<br>" +
					"This requires Alice's trade bot data to be present in the database for this AT.<br>" +
					"It will fail if it's already redeemed by the seller, or if the lockTime (60 minutes) hasn't passed yet.",
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
				} catch (ForeignBlockchainException | ApiException e) {
					LOGGER.info("Unable to refund an HTLC attempt; continuing other attempts");
				}
			}
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
		return broadcast;
	}

	private boolean doRefundHtlc(Repository repository, TradeBotData trade) throws DataException, ForeignBlockchainException {
		if (trade.getAtAddress() == null || trade.getForeignKey() == null || trade.getLockTimeA() == null
				|| trade.getState() == null || !trade.getState().startsWith("ALICE_")) return false;
		ATData at = repository.getATRepository().fromATAddress(trade.getAtAddress());
		if (at == null) return false;
		ACCT acct = SupportedBlockchain.getAcctByCodeHash(at.getCodeHash());
		if (acct == null) return false;
		CrossChainTradeData data = acct.populateTradeData(repository, at);
		if (data == null || !PirateChainACCTv3TradeBot.isRefundEligible(trade, at, data)) return false;
		Bitcoiny bitcoiny = (Bitcoiny) acct.getBlockchain();
		if (Objects.equals(bitcoiny.getCurrencyCode(), "ARRR"))
			return PirateChainACCTv3TradeBot.getInstance().refund(repository, trade, at, data);
		int lockTime = trade.getLockTimeA();
		Long now = NTP.getTime();
		if (now == null || now <= lockTime * 1000L || bitcoiny.getMedianBlockTime() <= lockTime) return false;
		long minimum = data.expectedForeignAmount + bitcoiny.getP2shFee(calcFeeTimestamp(lockTime, data.tradeTimeout));
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
		Transaction refund = BitcoinyHTLC.buildRefundTransaction(bitcoiny.getNetworkParameters(), Coin.valueOf(data.expectedForeignAmount),
				ECKey.fromPrivate(trade.getTradePrivateKey()), bitcoiny.getUnspentOutputs(address, false), script, lockTime, receiving.getHash());
		bitcoiny.broadcastTransaction(refund);
		return true;
	}

	private long calcFeeTimestamp(int lockTimeA, int tradeTimeout) {
		return (lockTimeA - tradeTimeout * 60) * 1000L;
	}

}
