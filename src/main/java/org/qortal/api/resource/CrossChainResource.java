package org.qortal.api.resource;

import com.google.common.primitives.Longs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.multipart.ContentDisposition;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.CrossChainCancelRequest;
import org.qortal.api.model.CrossChainTradeLedgerEntry;
import org.qortal.api.model.CrossChainTradeSummary;
import org.qortal.controller.ForeignFeesManager;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.AcctMode;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TransactionSummary;
import org.qortal.data.crosschain.ForeignFeeDecodedData;
import org.qortal.data.crosschain.ForeignFeeEncodedData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.MessageTransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;
import org.qortal.utils.ByteArray;
import org.qortal.utils.NTP;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;



@Path("/crosschain")
@Tag(name = "Cross-Chain")
public class CrossChainResource {

	private static final Logger LOGGER = LogManager.getLogger(CrossChainResource.class);

	@Context
	HttpServletRequest request;

	@Context
	HttpServletResponse response;

	@Context
	ServletContext context;


	@GET
	@Path("/tradeoffers")
	@Operation(
		summary = "Find cross-chain trade offers",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = CrossChainTradeData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<CrossChainTradeData> getTradeOffers(
			@Parameter(
				description = "Limit to specific blockchain",
				example = "LITECOIN",
				schema = @Schema(implementation = SupportedBlockchain.class)
			) @QueryParam("foreignBlockchain") SupportedBlockchain foreignBlockchain,
			@Parameter( ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter( ref = "offset" ) @QueryParam("offset") Integer offset,
			@Parameter( ref = "reverse" ) @QueryParam("reverse") Boolean reverse) {
		// Impose a limit on 'limit'
		if (limit != null && limit > 100)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		final boolean isExecutable = true;
		List<CrossChainTradeData> crossChainTrades = new ArrayList<>();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = SupportedBlockchain.getFilteredAcctMap(foreignBlockchain);

			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();

				List<ATData> atsData = repository.getATRepository().getATsByFunctionality(codeHash, isExecutable, limit, offset, reverse);

				for (ATData atData : atsData) {
					CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
					if (crossChainTradeData.mode == AcctMode.OFFERING) {
						crossChainTrades.add(crossChainTradeData);
					}
				}
			}

			// Sort the trades by timestamp
			if (reverse != null && reverse) {
				crossChainTrades.sort((a, b) -> Longs.compare(b.creationTimestamp, a.creationTimestamp));
			}
			else {
				crossChainTrades.sort((a, b) -> Longs.compare(a.creationTimestamp, b.creationTimestamp));
			}

			// Remove any trades that have had too many failures
			crossChainTrades = TradeBot.getInstance().removeFailedTrades(repository, crossChainTrades);

			if (limit != null && limit > 0) {
				// Make sure to not return more than the limit
				int upperLimit = Math.min(limit, crossChainTrades.size());
				crossChainTrades = crossChainTrades.subList(0, upperLimit);
			}

			crossChainTrades.stream().forEach(CrossChainResource::decorateTradeDataWithPresence);

			return crossChainTrades;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/tradeoffers/hidden")
	@Operation(
			summary = "Find cross-chain trade offers that have been hidden due to too many failures",
			responses = {
					@ApiResponse(
							content = @Content(
									array = @ArraySchema(
											schema = @Schema(
													implementation = CrossChainTradeData.class
											)
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<CrossChainTradeData> getHiddenTradeOffers(
			@Parameter(
					description = "Limit to specific blockchain",
					example = "LITECOIN",
					schema = @Schema(implementation = SupportedBlockchain.class)
			) @QueryParam("foreignBlockchain") SupportedBlockchain foreignBlockchain) {

		final boolean isExecutable = true;
		List<CrossChainTradeData> crossChainTrades = new ArrayList<>();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = SupportedBlockchain.getFilteredAcctMap(foreignBlockchain);

			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();

				List<ATData> atsData = repository.getATRepository().getATsByFunctionality(codeHash, isExecutable, null, null, null);

				for (ATData atData : atsData) {
					CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
					if (crossChainTradeData.mode == AcctMode.OFFERING) {
						crossChainTrades.add(crossChainTradeData);
					}
				}
			}

			// Sort the trades by timestamp
			crossChainTrades.sort((a, b) -> Longs.compare(a.creationTimestamp, b.creationTimestamp));

			// Keep only failed trades by batching the failed-trade check once.
			Set<String> nonFailedTradeAddresses = TradeBot.getInstance().removeFailedTrades(repository, crossChainTrades).stream()
					.map(crossChainTradeData -> crossChainTradeData.qortalAtAddress)
					.collect(Collectors.toSet());
			crossChainTrades.removeIf(crossChainTradeData -> nonFailedTradeAddresses.contains(crossChainTradeData.qortalAtAddress));

			crossChainTrades.stream().forEach(CrossChainResource::decorateTradeDataWithPresence);

			return crossChainTrades;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trade/{ataddress}")
	@Operation(
		summary = "Show detailed trade info",
		responses = {
			@ApiResponse(
				content = @Content(
					schema = @Schema(
						implementation = CrossChainTradeData.class
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.ADDRESS_UNKNOWN, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public CrossChainTradeData getTrade(@PathParam("ataddress") String atAddress) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			if (atData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			ACCT acct = SupportedBlockchain.getAcctByCodeHash(atData.getCodeHash());
			if (acct == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);

			decorateTradeDataWithPresence(crossChainTradeData);

			return crossChainTradeData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trades")
	@Operation(
		summary = "Find completed cross-chain trades",
		description = "Returns summary info about successfully completed cross-chain trades",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = CrossChainTradeSummary.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<CrossChainTradeSummary> getCompletedTrades(
			@Parameter(
					description = "Limit to specific blockchain",
					example = "LITECOIN",
					schema = @Schema(implementation = SupportedBlockchain.class)
				) @QueryParam("foreignBlockchain") SupportedBlockchain foreignBlockchain,
			@Parameter(
				description = "Only return trades that completed on/after this timestamp (milliseconds since epoch)",
				example = "1597310000000"
			) @QueryParam("minimumTimestamp") Long minimumTimestamp,
			@Parameter(
				description = "Optionally filter by buyer Qortal public key"
			) @QueryParam("buyerPublicKey") String buyerPublicKey58,
			@Parameter(
				description = "Optionally filter by seller Qortal public key"
			) @QueryParam("sellerPublicKey") String sellerPublicKey58,
			@Parameter( ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter( ref = "offset" ) @QueryParam("offset") Integer offset,
			@Parameter( ref = "reverse" ) @QueryParam("reverse") Boolean reverse) {
		// Impose a limit on 'limit'
		if (limit != null && limit > 100)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// minimumTimestamp (if given) needs to be positive
		if (minimumTimestamp != null && minimumTimestamp <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// Decode public keys
		byte[] buyerPublicKey = decodePublicKey(buyerPublicKey58);
		byte[] sellerPublicKey = decodePublicKey(sellerPublicKey58);

		final Boolean isFinished = Boolean.TRUE;

		try (final Repository repository = RepositoryManager.getRepository()) {
			Integer minimumFinalHeight = null;

			if (minimumTimestamp != null) {
				minimumFinalHeight = repository.getBlockRepository().getHeightFromTimestamp(minimumTimestamp);
				// If not found in the block repository it will return either 0 or 1
				if (minimumFinalHeight == 0 || minimumFinalHeight == 1) {
					// Try the archive
					minimumFinalHeight = repository.getBlockArchiveRepository().getHeightFromTimestamp(minimumTimestamp);
				}

				if (minimumFinalHeight == 0)
					// We don't have any blocks since minimumTimestamp, let alone trades, so nothing to return
					return Collections.emptyList();

				// height returned from repository is for block BEFORE timestamp
				// but we want trades AFTER timestamp so bump height accordingly
				minimumFinalHeight++;
			}

			List<CrossChainTradeSummary> crossChainTrades = new ArrayList<>();

			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = SupportedBlockchain.getFilteredAcctMap(foreignBlockchain);

			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();

				List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(codeHash, buyerPublicKey, sellerPublicKey,
						isFinished, acct.getModeByteOffset(), (long) AcctMode.REDEEMED.value, minimumFinalHeight,
						limit, offset, reverse);

				for (ATStateData atState : atStates) {
					CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atState);

					// We also need block timestamp for use as trade timestamp
					long timestamp = repository.getBlockRepository().getTimestampFromHeight(atState.getHeight());
					if (timestamp == 0) {
						// Try the archive
						timestamp = repository.getBlockArchiveRepository().getTimestampFromHeight(atState.getHeight());
					}

					CrossChainTradeSummary crossChainTradeSummary = new CrossChainTradeSummary(crossChainTradeData, timestamp);
					crossChainTrades.add(crossChainTradeSummary);
				}
			}

			// Sort the trades by timestamp
			if (reverse != null && reverse) {
				crossChainTrades.sort((a, b) -> Longs.compare(b.getTradeTimestamp(), a.getTradeTimestamp()));
			}
			else {
				crossChainTrades.sort((a, b) -> Longs.compare(a.getTradeTimestamp(), b.getTradeTimestamp()));
			}

			if (limit != null && limit > 0) {
				// Make sure to not return more than the limit
				int upperLimit = Math.min(limit, crossChainTrades.size());
				crossChainTrades = crossChainTrades.subList(0, upperLimit);
			}

			return crossChainTrades;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/signedfees")
	@Operation(
		summary = "",
		description = "",
		requestBody = @RequestBody(
			required = true,
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(
						schema = @Schema(
								implementation = ForeignFeeEncodedData.class
						)
					)
				)
		),
		responses = {
			@ApiResponse(
				description = "true on success",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
							type = "boolean"
					)
				)
			)
		}
	)
	public String postSignedForeignFees(List<ForeignFeeEncodedData> signedFees) {

		LOGGER.info("signedFees = " + signedFees);

		try {
			ForeignFeesManager.getInstance().addSignedFees(signedFees);

			return "true";
		}
		catch( Exception e ) {

			LOGGER.error(e.getMessage(), e);

			return "false";
		}
	}

	@GET
	@Path("/unsignedfees/{address}")
	@Operation(
		summary = "",
		description = "",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = ForeignFeeEncodedData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<ForeignFeeEncodedData> getUnsignedFees(@PathParam("address") String address) {

		List<ForeignFeeEncodedData> unsignedFeesForAddress = ForeignFeesManager.getInstance().getUnsignedFeesForAddress(address);

		LOGGER.info("address = " + address);
		LOGGER.info("returning unsigned = " + unsignedFeesForAddress);
		return unsignedFeesForAddress;
	}

	@GET
	@Path("/signedfees")
	@Operation(
		summary = "",
		description = "",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = ForeignFeeDecodedData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<ForeignFeeDecodedData> getSignedFees() {

		return ForeignFeesManager.getInstance().getSignedFees();
	}

	/**
	 * Decode Public Key
	 *
	 * @param publicKey58 the public key in a string
	 *
	 * @return the public key in bytes
	 */
	private byte[] decodePublicKey(String publicKey58) {

		if( publicKey58 == null ) return null;
		if( publicKey58.isEmpty() ) return new byte[0];

		byte[] publicKey;
		try {
			publicKey = Base58.decode(publicKey58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY, e);
		}

		// Correct size for public key?
		if (publicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		return publicKey;
	}

	@GET
	@Path("/ledger/{publicKey}")
	@Operation(
			summary = "Accounting entries for all trades.",
			description = "Returns accounting entries for all completed cross-chain trades",
			responses = {
					@ApiResponse(
							content = @Content(
									schema = @Schema(
											type = "string",
											format = "byte"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public HttpServletResponse getLedgerEntries(
			@PathParam("publicKey") String publicKey58,
			@Parameter(
					description = "Only return trades that completed on/after this timestamp (milliseconds since epoch)",
					example = "1597310000000"
			) @QueryParam("minimumTimestamp") Long minimumTimestamp) {

		byte[] publicKey = decodePublicKey(publicKey58);

		// minimumTimestamp (if given) needs to be positive
		if (minimumTimestamp != null && minimumTimestamp <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Integer minimumFinalHeight = null;

			if (minimumTimestamp != null) {
				minimumFinalHeight = repository.getBlockRepository().getHeightFromTimestamp(minimumTimestamp);
				// If not found in the block repository it will return either 0 or 1
				if (minimumFinalHeight == 0 || minimumFinalHeight == 1) {
					// Try the archive
					minimumFinalHeight = repository.getBlockArchiveRepository().getHeightFromTimestamp(minimumTimestamp);
				}

				if (minimumFinalHeight == 0)
					// We don't have any blocks since minimumTimestamp, let alone trades, so nothing to return
					return response;

				// height returned from repository is for block BEFORE timestamp
				// but we want trades AFTER timestamp so bump height accordingly
				minimumFinalHeight++;
			}

			List<CrossChainTradeLedgerEntry> crossChainTradeLedgerEntries = new ArrayList<>();

			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = SupportedBlockchain.getAcctMap();

			// collect ledger entries for each ACCT
			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();

				// collect buys and sells
				CrossChainUtils.collectLedgerEntries(publicKey, repository, minimumFinalHeight, crossChainTradeLedgerEntries, codeHash, acct, true);
				CrossChainUtils.collectLedgerEntries(publicKey, repository, minimumFinalHeight, crossChainTradeLedgerEntries, codeHash, acct, false);
			}

			crossChainTradeLedgerEntries.sort((a, b) -> Longs.compare(a.getTradeTimestamp(), b.getTradeTimestamp()));

			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("text/csv");
			response.setHeader(
				HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition
					.type("attachment")
					.fileName(CrossChainUtils.createLedgerFileName(Crypto.toAddress(publicKey)))
					.build()
					.toString()
			);

			CrossChainUtils.writeToLedger( response.getWriter(), crossChainTradeLedgerEntries);

			return response;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (IOException e) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			return response;
		}
	}

	@GET
	@Path("/price/{blockchain}")
	@Operation(
		summary = "Request current estimated trading price",
		description = "Returns price based on most recent completed trades. Price is expressed in terms of QORT per unit foreign currency.",
		responses = {
			@ApiResponse(
				content = @Content(
					schema = @Schema(
						type = "number"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public long getTradePriceEstimate(
			@Parameter(
					description = "foreign blockchain",
					example = "LITECOIN",
					schema = @Schema(implementation = SupportedBlockchain.class)
				) @PathParam("blockchain") SupportedBlockchain foreignBlockchain,
			@Parameter(
					description = "Maximum number of trades to include in price calculation",
					example = "10",
					schema = @Schema(type = "integer", defaultValue = "10")
			) @QueryParam("maxtrades") Integer maxtrades,
			@Parameter(
					description = "Display price in terms of foreign currency per unit QORT",
					example = "false",
					schema = @Schema(type = "boolean", defaultValue = "false")
			) @QueryParam("inverse") Boolean inverse) {
		// foreignBlockchain is required
		if (foreignBlockchain == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// We want both a minimum of 5 trades and enough trades to span at least 4 hours
		int minimumCount = 5;
		int maximumCount = maxtrades != null ? maxtrades : 10;
		long minimumPeriod = 4 * 60 * 60 * 1000L; // ms
		Boolean isFinished = Boolean.TRUE;
		boolean useInversePrice = (inverse != null && inverse);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = SupportedBlockchain.getFilteredAcctMap(foreignBlockchain);

			long totalForeign = 0;
			long totalQort = 0;

			Map<Long, CrossChainTradeData> reverseSortedTradeData = new TreeMap<>(Collections.reverseOrder());

			// Collect recent AT states for each ACCT version
			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();

				List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStatesQuorum(codeHash,
						isFinished, acct.getModeByteOffset(), (long) AcctMode.REDEEMED.value, minimumCount, maximumCount, minimumPeriod);

				for (ATStateData atState : atStates) {
					// We also need block timestamp for use as trade timestamp
					long timestamp = repository.getBlockRepository().getTimestampFromHeight(atState.getHeight());
					if (timestamp == 0) {
						// Try the archive
						timestamp = repository.getBlockArchiveRepository().getTimestampFromHeight(atState.getHeight());
					}

					CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atState);
					reverseSortedTradeData.put(timestamp, crossChainTradeData);
				}
			}

			// Loop through the sorted map and calculate the average price
			// Also remove elements beyond the maxtrades limit
			Set set = reverseSortedTradeData.entrySet();
			Iterator i = set.iterator();
			int index = 0;
			while (i.hasNext()) {
				Map.Entry tradeDataMap = (Map.Entry)i.next();
				CrossChainTradeData crossChainTradeData = (CrossChainTradeData) tradeDataMap.getValue();

				if (maxtrades != null && index >= maxtrades) {
					// We've reached the limit
					break;
				}

				totalForeign += crossChainTradeData.expectedForeignAmount;
				totalQort += crossChainTradeData.qortAmount;
				index++;
			}

			return useInversePrice ? Amounts.scaledDivide(totalForeign, totalQort) : Amounts.scaledDivide(totalQort, totalForeign);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@DELETE
	@Path("/tradeoffer")
	@Operation(
		summary = "Builds raw, unsigned 'cancel' MESSAGE transaction that cancels cross-chain trade offer",
		description = "Specify address of cross-chain AT that needs to be cancelled.<br>"
			+ "AT needs to be in 'offer' mode. Messages sent to an AT in 'trade' mode will be ignored.<br>"
			+ "Performs MESSAGE proof-of-work.<br>"
			+ "You need to sign output with AT creator's private key otherwise the MESSAGE transaction will be invalid.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CrossChainCancelRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String cancelTrade(@HeaderParam(Security.API_KEY_HEADER) String apiKey, CrossChainCancelRequest cancelRequest) {
		Security.checkApiCallAllowed(request);

		byte[] creatorPublicKey = cancelRequest.creatorPublicKey;

		if (creatorPublicKey == null || creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		if (cancelRequest.atAddress == null || !Crypto.isValidAtAddress(cancelRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, cancelRequest.atAddress);

			ACCT acct = SupportedBlockchain.getAcctByCodeHash(atData.getCodeHash());
			if (acct == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);

			if (crossChainTradeData.mode != AcctMode.OFFERING)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Does supplied public key match AT creator's public key?
			if (!Arrays.equals(creatorPublicKey, atData.getCreatorPublicKey()))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

			// Good to make MESSAGE

			String atCreatorAddress = Crypto.toAddress(creatorPublicKey);
			byte[] messageData = acct.buildCancelMessage(atCreatorAddress);

			byte[] messageTransactionBytes = buildAtMessage(repository, creatorPublicKey, cancelRequest.atAddress, messageData);

			return Base58.encode(messageTransactionBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/p2sh")
	@Operation(
			summary = "Returns P2SH Address",
			description = "Get the P2SH address to lock foreign coin in a cross chain trade for QORT",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "the AT address",
									example = "AKFnu9yBp7tUAc5HAphhfCxRZTYoeKXgUy"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "address"))
					)
			}
	)
	@ApiErrors({ApiError.ADDRESS_UNKNOWN, ApiError.INVALID_CRITERIA})
	@SecurityRequirement(name = "apiKey")
	public String getForeignP2SH(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String atAddress) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			if (atData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			ACCT acct = SupportedBlockchain.getAcctByCodeHash(atData.getCodeHash());

			if( acct == null || !(acct.getBlockchain() instanceof Bitcoiny) )
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			Bitcoiny bitcoiny = (Bitcoiny) acct.getBlockchain();

			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);

			Optional<String> p2sh
					= CrossChainUtils.getP2ShAddressForAT(atAddress, repository, bitcoiny, crossChainTradeData);

			if(p2sh.isPresent()){
				return p2sh.get();
			}
			else{
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);
			}
		}
		catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/txactivity")
	@Operation(
			summary = "Returns Foreign Transaction Activity",
			description = "Get the activity related to foreign coin trading",
			responses = {
					@ApiResponse(
							content = @Content(
									array = @ArraySchema(
											schema = @Schema(
													implementation = TransactionSummary.class
											)
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<TransactionSummary> getForeignTransactionActivity(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @Parameter(
			description = "Limit to specific blockchain",
			example = "LITECOIN",
			schema = @Schema(implementation = SupportedBlockchain.class)
	) @QueryParam("foreignBlockchain") SupportedBlockchain foreignBlockchain) {
		Security.checkApiCallAllowed(request);

		if (!(foreignBlockchain.getInstance() instanceof Bitcoiny))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		Bitcoiny bitcoiny = (Bitcoiny) foreignBlockchain.getInstance() ;

		org.bitcoinj.core.Context.propagate( bitcoiny.getBitcoinjContext() );

		try (final Repository repository = RepositoryManager.getRepository()) {

			// sort from last lock to first lock
			return CrossChainUtils
					.getForeignTradeSummaries(foreignBlockchain, repository, bitcoiny).stream()
					.sorted(Comparator.comparing(TransactionSummary::getLockingTimestamp).reversed())
					.collect(Collectors.toList());
		}
		catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
		catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	private ATData fetchAtDataWithChecking(Repository repository, String atAddress) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		if (atData == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

		// No point sending message to AT that's finished
		if (atData.getIsFinished())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return atData;
	}

	private byte[] buildAtMessage(Repository repository, byte[] senderPublicKey, String atAddress, byte[] messageData) throws DataException {
		long txTimestamp = NTP.getTime();

		// senderPublicKey could be ephemeral trade public key where there is no corresponding account and hence no reference
		String senderAddress = Crypto.toAddress(senderPublicKey);
		byte[] lastReference = repository.getAccountRepository().getLastReference(senderAddress);
		final boolean requiresPoW = lastReference == null;

		if (requiresPoW) {
			Random random = new Random();
			lastReference = new byte[Transformer.SIGNATURE_LENGTH];
			random.nextBytes(lastReference);
		}

		int version = 4;
		int nonce = 0;
		long amount = 0L;
		Long assetId = null; // no assetId as amount is zero
		Long fee = 0L;

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, senderPublicKey, fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, version, nonce, atAddress, amount, assetId, messageData, false, false);

		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		if (requiresPoW) {
			messageTransaction.computeNonce();
		} else {
			fee = messageTransaction.calcRecommendedFee();
			messageTransactionData.setFee(fee);
		}

		ValidationResult result = messageTransaction.isValidUnconfirmed();
		if (result != ValidationResult.OK)
			throw TransactionsResource.createTransactionInvalidException(request, result);

		try {
			return MessageTransactionTransformer.toBytes(messageTransactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		}
	}

	private static void decorateTradeDataWithPresence(CrossChainTradeData crossChainTradeData) {
		TradeBot.getInstance().decorateTradeDataWithPresence(crossChainTradeData);
	}
}
