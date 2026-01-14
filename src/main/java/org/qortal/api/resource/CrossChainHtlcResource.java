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
			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			TradeBotData tradeBotData = allTradeBotData.stream().filter(tradeBotDataItem -> tradeBotDataItem.getAtAddress().equals(atAddress)).findFirst().orElse(null);
			if (tradeBotData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			if (tradeBotData.getForeignKey() == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			if (atData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			ACCT acct = SupportedBlockchain.getAcctByCodeHash(atData.getCodeHash());
			if (acct == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Determine foreign blockchain receive address for refund
			Bitcoiny bitcoiny = (Bitcoiny) acct.getBlockchain();
			String receiveAddress = bitcoiny.getUnusedReceiveAddress(tradeBotData.getForeignKey());

			return this.doRefundHtlc(atAddress, receiveAddress);

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

				if (tradeState.startsWith("BOB")) {
					LOGGER.info("AT {} isn't refundable because it is a sell order", atAddress);
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

				CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
				if (crossChainTradeData == null) {
					LOGGER.info("Couldn't find crosschain trade data for AT {}", atAddress);
					continue;
				}

				if (tradeBotData.getForeignKey() == null) {
					LOGGER.info("Couldn't find foreign key for AT {}", atAddress);
					continue;
				}

				try {
					// Determine foreign blockchain receive address for refund
					Bitcoiny bitcoiny = (Bitcoiny) acct.getBlockchain();
					String receivingAddress = bitcoiny.getUnusedReceiveAddress(tradeBotData.getForeignKey());

					LOGGER.info("Attempting to refund P2SH balance associated with AT {}...", atAddress);
					boolean refunded = this.doRefundHtlc(atAddress, receivingAddress);
					if (refunded) {
						LOGGER.info("Refunded P2SH balance associated with AT {}", atAddress);
						success = true;
					}
					else {
						LOGGER.info("Couldn't refund P2SH balance associated with AT {}. Already redeemed?", atAddress);
					}
				} catch (ApiException | ForeignBlockchainException e) {
					LOGGER.info("Couldn't refund P2SH balance associated with AT {}. Missing data?", atAddress);
				}
			}

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		return success;
	}


	private boolean doRefundHtlc(String atAddress, String receiveAddress) {
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

			// If the AT is "finished" then it will have a zero balance
			// In these cases we should avoid HTLC refunds if the QORT haven't been returned to the seller
			if (atData.getIsFinished() && crossChainTradeData.mode != AcctMode.REFUNDED && crossChainTradeData.mode != AcctMode.CANCELLED) {
				LOGGER.info(String.format("Skipping AT %s because the QORT has already been redeemed by the buyer", atAddress));
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}

			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			List<TradeBotData> tradeBotDataList = allTradeBotData.stream().filter(tradeBotDataItem -> tradeBotDataItem.getAtAddress().equals(atAddress)).collect(Collectors.toList());
			if (tradeBotDataList == null || tradeBotDataList.isEmpty())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Loop through all matching entries for this AT address, as there might be more than one
			for (TradeBotData tradeBotData : tradeBotDataList) {

				if (tradeBotData == null)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				Bitcoiny bitcoiny = (Bitcoiny) acct.getBlockchain();
				int lockTime = tradeBotData.getLockTimeA();

				// We can't refund P2SH-A until lockTime-A has passed
				if (NTP.getTime() <= lockTime * 1000L)
					continue;

				// We can't refund P2SH-A until median block time has passed lockTime-A (see BIP113)
				int medianBlockTime = bitcoiny.getMedianBlockTime();
				if (medianBlockTime <= lockTime)
					continue;

				// Fee for redeem/refund is subtracted from P2SH-A balance.
				long feeTimestamp = calcFeeTimestamp(lockTime, crossChainTradeData.tradeTimeout);
				long p2shFee = bitcoiny.getP2shFee(feeTimestamp);
				long minimumAmountA = crossChainTradeData.expectedForeignAmount + p2shFee;

				// Create redeem script based on destination chain
				byte[] redeemScriptA;
				String p2shAddressA;
				BitcoinyHTLC.Status htlcStatusA;
				if (Objects.equals(bitcoiny.getCurrencyCode(), "ARRR")) {
					redeemScriptA = PirateChainHTLC.buildScript(tradeBotData.getTradeForeignPublicKey(), lockTime, crossChainTradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
					p2shAddressA = PirateChain.getInstance().deriveP2shAddressBPrefix(redeemScriptA);
					htlcStatusA = PirateChainHTLC.determineHtlcStatus(bitcoiny.getBlockchainProvider(), p2shAddressA, minimumAmountA);
				} else {
					redeemScriptA = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTime, crossChainTradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
					p2shAddressA = bitcoiny.deriveP2shAddress(redeemScriptA);
					htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoiny.getBlockchainProvider(), p2shAddressA, minimumAmountA);
				}
				LOGGER.info(String.format("Refunding P2SH address: %s", p2shAddressA));

				switch (htlcStatusA) {
					case UNFUNDED:
					case FUNDING_IN_PROGRESS:
						// Still waiting for P2SH-A to be funded...
						continue;

					case REDEEM_IN_PROGRESS:
					case REDEEMED:
					case REFUND_IN_PROGRESS:
					case REFUNDED:
						// Too late!
						continue;

					case FUNDED: {
						Coin refundAmount = Coin.valueOf(crossChainTradeData.expectedForeignAmount);

						if (Objects.equals(bitcoiny.getCurrencyCode(), "ARRR")) {
							// Pirate Chain custom integration

							PirateChain pirateChain = PirateChain.getInstance();
							String p2shAddressT3 = pirateChain.deriveP2shAddress(redeemScriptA);

							// Get funding txid
							String fundingTxidHex = PirateChainHTLC.getUnspentFundingTxid(pirateChain.getBlockchainProvider(), p2shAddressA, minimumAmountA);
							if (fundingTxidHex == null) {
								throw new ForeignBlockchainException("Missing funding txid when refunding P2SH");
							}
							String fundingTxid58 = Base58.encode(HashCode.fromString(fundingTxidHex).asBytes());

							byte[] privateKey = tradeBotData.getTradePrivateKey();
							String privateKey58 = Base58.encode(privateKey);
							String redeemScript58 = Base58.encode(redeemScriptA);

							String txid = PirateChain.getInstance().refundP2sh(p2shAddressT3,
									receiveAddress, refundAmount.value, redeemScript58, fundingTxid58, lockTime, privateKey58);
							LOGGER.info("Refund txid: {}", txid);
						} else {
							// ElectrumX coins

							ECKey refundKey = ECKey.fromPrivate(tradeBotData.getTradePrivateKey());
							List<TransactionOutput> fundingOutputs = bitcoiny.getUnspentOutputs(p2shAddressA, false);

							// Validate the destination foreign blockchain address
							Address receiving = Address.fromString(bitcoiny.getNetworkParameters(), receiveAddress);
							if (receiving.getOutputScriptType() != Script.ScriptType.P2PKH)
								throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

							Transaction p2shRefundTransaction = BitcoinyHTLC.buildRefundTransaction(bitcoiny.getNetworkParameters(), refundAmount, refundKey,
									fundingOutputs, redeemScriptA, lockTime, receiving.getHash());

							bitcoiny.broadcastTransaction(p2shRefundTransaction);
						}

						return true;
					}
				}
			}

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, e);
		}

		return false;
	}

	private long calcFeeTimestamp(int lockTimeA, int tradeTimeout) {
		return (lockTimeA - tradeTimeout * 60) * 1000L;
	}

}
