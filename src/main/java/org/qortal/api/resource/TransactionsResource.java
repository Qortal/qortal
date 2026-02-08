package org.qortal.api.resource;

import com.google.common.primitives.Bytes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.*;
import org.qortal.api.model.SimpleTransactionSignRequest;
import org.qortal.controller.Controller;
import org.qortal.controller.LiteNode;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.TransactionData;
import org.qortal.globalization.Translator;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Path("/transactions")
@Tag(name = "Transactions")
public class TransactionsResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/signature/{signature}")
	@Operation(
		summary = "Fetch transaction using transaction signature",
		description = "Returns transaction",
		responses = {
			@ApiResponse(
				description = "a transaction",
				content = @Content(
					schema = @Schema(
						implementation = TransactionData.class
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_SIGNATURE, ApiError.TRANSACTION_UNKNOWN, ApiError.REPOSITORY_ISSUE
	})
	public TransactionData getTransactionBySignature(@PathParam("signature") String signature58) {
		byte[] signature;
		try {
			signature = Base58.decode(signature58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_SIGNATURE, e);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_UNKNOWN);

			return transactionData;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/signature/{signature}/raw")
	@Operation(
		summary = "Fetch raw, base58-encoded, transaction using transaction signature",
		description = "Returns transaction",
		responses = {
			@ApiResponse(
				description = "raw transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_SIGNATURE, ApiError.TRANSACTION_UNKNOWN, ApiError.REPOSITORY_ISSUE, ApiError.TRANSFORMATION_ERROR
	})
	public String getRawTransactionBySignature(@PathParam("signature") String signature58) {
		byte[] signature;
		try {
			signature = Base58.decode(signature58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_SIGNATURE, e);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_UNKNOWN);

			byte[] transactionBytes = TransactionTransformer.toBytes(transactionData);

			return Base58.encode(transactionBytes);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		}
	}

	@GET
	@Path("/reference/{reference}")
	@Operation(
		summary = "Fetch transaction using transaction reference",
		description = "Returns transaction",
		responses = {
			@ApiResponse(
				description = "a transaction",
				content = @Content(
					schema = @Schema(
						implementation = TransactionData.class
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_REFERENCE, ApiError.TRANSACTION_UNKNOWN, ApiError.REPOSITORY_ISSUE
	})
	public TransactionData getTransactionByReference(@PathParam("reference") String reference58) {
		byte[] reference;
		try {
			reference = Base58.decode(reference58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_REFERENCE, e);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromReference(reference);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_UNKNOWN);

			return transactionData;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/block/{signature}")
	@Operation(
		summary = "Fetch transactions using block signature",
		description = "Returns list of transactions",
		responses = {
			@ApiResponse(
				description = "the block",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_SIGNATURE, ApiError.BLOCK_UNKNOWN, ApiError.REPOSITORY_ISSUE
	})
	public List<TransactionData> getBlockTransactions(@PathParam("signature") String signature58, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		// Decode signature
		byte[] signature;
		try {
			signature = Base58.decode(signature58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_SIGNATURE, e);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Check if the block exists in either the database or archive
			int height = repository.getBlockRepository().getHeightFromSignature(signature);
			if (height == 0) {
				height = repository.getBlockArchiveRepository().getHeightFromSignature(signature);
				if (height == 0) {
					// Not found in either the database or archive
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCK_UNKNOWN);
				}
			}

			List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, height, height);

			// Expand signatures to transactions
			List<TransactionData> transactions = new ArrayList<>(signatures.size());
			for (byte[] s : signatures) {
				transactions.add(repository.getTransactionRepository().fromSignature(s));
			}

			return transactions;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/unconfirmed")
	@Operation(
		summary = "List unconfirmed transactions",
		description = "Returns transactions",
		responses = {
			@ApiResponse(
				description = "transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.REPOSITORY_ISSUE
	})
	public List<TransactionData> getUnconfirmedTransactions(@Parameter(
			description = "A list of transaction types"
	) @QueryParam("txType") List<TransactionType> txTypes, @Parameter(
			description = "Transaction creator's base58 encoded public key"
	) @QueryParam("creator") String creatorPublicKey58, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {

		// Decode public key if supplied
		byte[] creatorPublicKey = null;
		if (creatorPublicKey58 != null) {
			try {
				creatorPublicKey = Base58.decode(creatorPublicKey58);
			} catch (NumberFormatException e) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY, e);
			}
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getTransactionRepository().getUnconfirmedTransactions(txTypes, creatorPublicKey, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/pending")
	@Operation(
		summary = "List transactions pending group approval",
		description = "Returns transactions that are pending group-admin approval",
		responses = {
			@ApiResponse(
				description = "transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.REPOSITORY_ISSUE
	})
	public List<TransactionData> getPendingTransactions(@QueryParam("txGroupId") Integer txGroupId, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getTransactionRepository().getApprovalPendingTransactions(txGroupId, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	public enum ConfirmationStatus {
		CONFIRMED,
		UNCONFIRMED,
		BOTH
    }

	@GET
	@Path("/search")
	@Operation(
		summary = "Find matching transactions",
		description = "Returns transactions that match criteria. At least either txType or address or limit <= 20 must be provided. Block height ranges allowed when searching CONFIRMED transactions ONLY.",
		responses = {
			@ApiResponse(
				description = "transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE
	})
	public List<TransactionData> searchTransactions(@QueryParam("startBlock") Integer startBlock, @QueryParam("blockLimit") Integer blockLimit,
			@QueryParam("txGroupId") Integer txGroupId,
			@QueryParam("txType") List<TransactionType> txTypes, @QueryParam("address") String address, @Parameter(
				description = "whether to include confirmed, unconfirmed or both",
				required = true
			) @QueryParam("confirmationStatus") ConfirmationStatus confirmationStatus, @Parameter(
				ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		// Must have at least one of txType / address / limit <= 20
		if ((txTypes == null || txTypes.isEmpty()) && (address == null || address.isEmpty()) && (limit == null || limit > 20))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// You can't ask for unconfirmed and impose a block height range
		if (confirmationStatus != ConfirmationStatus.CONFIRMED && (startBlock != null || blockLimit != null))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(startBlock, blockLimit, txGroupId,
					txTypes, null, null, address, confirmationStatus, limit, offset, reverse);

			// Expand signatures to transactions
			List<TransactionData> transactions = new ArrayList<>(signatures.size());
			for (byte[] signature : signatures)
				transactions.add(repository.getTransactionRepository().fromSignature(signature));

			return transactions;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/address/{address}")
	@Operation(
			summary = "Returns transactions for given address",
			responses = {
					@ApiResponse(
							description = "transactions",
							content = @Content(
									array = @ArraySchema(
											schema = @Schema(
													implementation = TransactionData.class
											)
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS,  ApiError.REPOSITORY_ISSUE})
	public List<TransactionData> getAddressTransactions(@PathParam("address") String address,
												 		@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
												 		@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
														@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		if (!Crypto.isValidAddress(address)) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		if (limit == null) {
			limit = 0;
		}
		if (offset == null) {
			offset = 0;
		}

		List<TransactionData> transactions;

		if (Settings.getInstance().isLite()) {
			// Fetch from network
			transactions = LiteNode.getInstance().fetchAccountTransactions(address, limit, offset);

			// Sort the data, since we can't guarantee the order that a peer sent it in
			if (reverse) {
				transactions.sort(Comparator.comparingLong(TransactionData::getTimestamp).reversed());
			} else {
				transactions.sort(Comparator.comparingLong(TransactionData::getTimestamp));
			}
		}
		else {
			// Fetch from local db
			try (final Repository repository = RepositoryManager.getRepository()) {
				List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null,
						null, null, null, address, TransactionsResource.ConfirmationStatus.CONFIRMED, limit, offset, reverse);

				// Expand signatures to transactions
				transactions = new ArrayList<>(signatures.size());
				for (byte[] signature : signatures) {
					transactions.add(repository.getTransactionRepository().fromSignature(signature));
				}
			} catch (ApiException e) {
				throw e;
			} catch (DataException e) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
			}
		}

		return transactions;
	}

	@GET
	@Path("/payments/between")
	@Operation(
			summary = "Get payment transactions between recipient and sender addresses",
			description = "Returns PAYMENT type transactions filtered by recipient address and/or sender address. " +
					"At least one of recipientAddress or senderAddress must be provided. " +
					"Block height ranges allowed when searching CONFIRMED transactions ONLY.",
			responses = {
					@ApiResponse(
							description = "payment transactions",
							content = @Content(
									array = @ArraySchema(
											schema = @Schema(
													implementation = TransactionData.class
											)
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<TransactionData> getPaymentsBetweenAddresses(
			@Parameter(description = "Recipient's address") @QueryParam("recipientAddress") String recipientAddress,
			@Parameter(description = "Sender's address") @QueryParam("senderAddress") String senderAddress,
			@Parameter(description = "Payment amount (e.g., 1 or 1.5 for QORT)") @QueryParam("amount") String amountString,
			@Parameter(description = "Start block height") @QueryParam("startBlock") Integer startBlock,
			@Parameter(description = "Block limit (number of blocks to search from startBlock)") @QueryParam("blockLimit") Integer blockLimit,
			@Parameter(
					description = "whether to include confirmed, unconfirmed or both",
					required = true
			) @QueryParam("confirmationStatus") ConfirmationStatus confirmationStatus,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {

		// Validate that at least one address is provided
		boolean hasRecipient = recipientAddress != null && !recipientAddress.isEmpty();
		boolean hasSender = senderAddress != null && !senderAddress.isEmpty();
		
		if (!hasRecipient && !hasSender) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		// Validate recipient address if provided
		if (hasRecipient && !Crypto.isValidAddress(recipientAddress)) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		// Validate sender address if provided
		if (hasSender && !Crypto.isValidAddress(senderAddress)) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		}

		// Convert amount from decimal string to database format (e.g., "1" -> 100000000)
		Long amount = null;
		if (amountString != null && !amountString.isEmpty()) {
			try {
				amount = new BigDecimal(amountString).setScale(8).unscaledValue().longValue();
			} catch (NumberFormatException | ArithmeticException e) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}
		}

		// You can't ask for unconfirmed and impose a block height range
		if (confirmationStatus != ConfirmationStatus.CONFIRMED && (startBlock != null || blockLimit != null)) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TransactionData> transactions = repository.getTransactionRepository()
					.getPaymentsBetweenAddresses(recipientAddress, senderAddress, amount, startBlock, blockLimit, 
							confirmationStatus, limit, offset, reverse);

			return transactions;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/unitfee")
	@Operation(
			summary = "Get transaction unit fee",
			responses = {
					@ApiResponse(
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "number"
									)
							)
					)
			}
	)
	@ApiErrors({
			ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE
	})
	public long getTransactionUnitFee(@QueryParam("txType") TransactionType txType,
                                      @QueryParam("timestamp") Long timestamp,
									  @QueryParam("level") Integer accountLevel) {
		try {
			if (timestamp == null) {
				timestamp = NTP.getTime();
			}

			Constructor<?> constructor = txType.constructor;
			Transaction transaction = (Transaction) constructor.newInstance(null, null);
			// FUTURE: add accountLevel parameter to transaction.getUnitFee() if needed
			return transaction.getUnitFee(timestamp);

		} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA, e);
		}
	}

	@POST
	@Path("/fee")
	@Operation(
			summary = "Get recommended fee for supplied transaction data",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string"
							)
					)
			)
	)
	@ApiErrors({
			ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE
	})
	public long getRecommendedTransactionFee(String rawInputBytes58) {
		byte[] rawInputBytes = Base58.decode(rawInputBytes58);
		if (rawInputBytes.length == 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.JSON);

		try (final Repository repository = RepositoryManager.getRepository()) {

			// Append null signature on the end before transformation
			byte[] rawBytes = Bytes.concat(rawInputBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			Transaction transaction = Transaction.fromData(repository, transactionData);
			return transaction.calcRecommendedFee();

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}  catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		}
	}

	@GET
	@Path("/creator/{publickey}")
	@Operation(
		summary = "Find matching transactions created by account with given public key",
		responses = {
			@ApiResponse(
				description = "transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE
	})
	public List<TransactionData> findCreatorsTransactions(@PathParam("publickey") String publicKey58,
			@Parameter(
				description = "whether to include confirmed, unconfirmed or both",
				required = true
			) @QueryParam("confirmationStatus") ConfirmationStatus confirmationStatus, @Parameter(
				ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		// Decode public key
		byte[] publicKey;
		try {
			publicKey = Base58.decode(publicKey58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY, e);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null,
					publicKey, confirmationStatus, limit, offset, reverse);

			// Expand signatures to transactions
			List<TransactionData> transactions = new ArrayList<>(signatures.size());
			for (byte[] signature : signatures)
				transactions.add(repository.getTransactionRepository().fromSignature(signature));

			return transactions;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/convert")
	@Operation(
			summary = "Convert transaction bytes into bytes for signing",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "raw, unsigned transaction in base58 encoding",
									example = "raw transaction base58"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned transaction encoded in Base58, ready for signing",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@ApiErrors({
			ApiError.NON_PRODUCTION, ApiError.TRANSFORMATION_ERROR
	})
	public String convertTransactionForSigning(String rawInputBytes58) {
		byte[] rawInputBytes = Base58.decode(rawInputBytes58);
		if (rawInputBytes.length == 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.JSON);

		try {
			// Append null signature on the end before transformation
			byte[] rawBytes = Bytes.concat(rawInputBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			byte[] convertedBytes = TransactionTransformer.toBytesForSigning(transactionData);

			return Base58.encode(convertedBytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		}
	}

	@POST
	@Path("/sign")
	@Operation(
			summary = "Sign a raw, unsigned transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = SimpleTransactionSignRequest.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, signed transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@ApiErrors({
			ApiError.NON_PRODUCTION, ApiError.INVALID_PRIVATE_KEY, ApiError.TRANSFORMATION_ERROR
	})
	public String signTransaction(SimpleTransactionSignRequest signRequest) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		if (signRequest.transactionBytes.length == 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.JSON);

		try {
			// Append null signature on the end before transformation
			byte[] rawBytes = Bytes.concat(signRequest.transactionBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			PrivateKeyAccount signer = new PrivateKeyAccount(null, signRequest.privateKey);

			Transaction transaction = Transaction.fromData(null, transactionData);
			transaction.sign(signer);

			byte[] signedBytes = TransactionTransformer.toBytes(transactionData);

			return Base58.encode(signedBytes);
		} catch (IllegalArgumentException e) {
			// Invalid private key
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		}
	}

	@POST
	@Path("/process")
	@Operation(
		summary = "Submit raw, signed transaction for processing and adding to blockchain",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					description = "raw, signed transaction in base58 encoding",
					example = "raw transaction base58"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "For API version 1, this returns true if accepted.\nFor API version 2, the transactionData is returned as a JSON string if accepted.",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.BLOCKCHAIN_NEEDS_SYNC, ApiError.INVALID_SIGNATURE, ApiError.INVALID_DATA, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE
	})
	public String processTransaction(String rawBytes58, @HeaderParam(ApiService.API_VERSION_HEADER) String apiVersionHeader) {
		int apiVersion = ApiService.getApiVersion(request);

		// Only allow a transaction to be processed if our latest block is less than 60 minutes old
		// If older than this, we should first wait until the blockchain is synced
		final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
		if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

		byte[] rawBytes = Base58.decode(rawBytes58);

		TransactionData transactionData;
		try {
			transactionData = TransactionTransformer.fromBytes(rawBytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		}

		if (transactionData == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		// general chat transactions are invalid
		if( transactionData.getType() == TransactionType.CHAT && transactionData.getTxGroupId() == 0 && transactionData.getRecipient() == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			if (!transaction.isSignatureValid())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_SIGNATURE);

			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
			if (!blockchainLock.tryLock(60, TimeUnit.SECONDS))
				throw createTransactionInvalidException(request, ValidationResult.NO_BLOCKCHAIN_LOCK);

			try {
				ValidationResult result = transaction.importAsUnconfirmed();
				if (result != ValidationResult.OK)
					throw createTransactionInvalidException(request, result);
			} finally {
				blockchainLock.unlock();
			}

			switch (apiVersion) {
				case 1:
					return "true";

				case 2:
				default:
					// Marshall transactionData to string
					StringWriter stringWriter = new StringWriter();
					ApiRequest.marshall(stringWriter, transactionData);
					return stringWriter.toString();
			}


		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (InterruptedException e) {
			throw createTransactionInvalidException(request, ValidationResult.NO_BLOCKCHAIN_LOCK);
		} catch (IOException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		}
	}

	@POST
	@Path("/decode")
	@Operation(
		summary = "Decode a raw, signed/unsigned transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					description = "raw, unsigned/signed transaction in base58 encoding",
					example = "raw transaction base58"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "a transaction",
				content = @Content(
					schema = @Schema(
						implementation = TransactionData.class
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_SIGNATURE, ApiError.INVALID_DATA, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE
	})
	public TransactionData decodeTransaction(String rawBytes58, @QueryParam("ignoreValidityChecks") boolean ignoreValidityChecks) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rawBytes = Base58.decode(rawBytes58);
			boolean hasSignature = true;

			TransactionData transactionData;
			try {
				transactionData = TransactionTransformer.fromBytes(rawBytes);
			} catch (TransformationException e) {
				// Maybe we're missing a signature, so append one and try one more time
				rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);
				hasSignature = false;
				transactionData = TransactionTransformer.fromBytes(rawBytes);
			}

			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			Transaction transaction = Transaction.fromData(repository, transactionData);

			if (!ignoreValidityChecks) {
				if (hasSignature && !transaction.isSignatureValid())
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_SIGNATURE);

				ValidationResult result = transaction.isValid();
				if (result != ValidationResult.OK)
					throw createTransactionInvalidException(request, result);
			}

			if (!hasSignature)
				transactionData.setSignature(null);

			return transactionData;
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA, e);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	public static ApiException createTransactionInvalidException(HttpServletRequest request, ValidationResult result) {
		String translatedResult = Translator.INSTANCE.translate("TransactionValidity", request.getLocale().getLanguage(), result.name());
		return ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSACTION_INVALID, null, translatedResult, result.name());
	}

}
