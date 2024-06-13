package org.qortal.api.resource;

import com.google.common.primitives.Bytes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.*;
import org.qortal.api.model.AccountPenaltyStats;
import org.qortal.api.model.ApiOnlineAccount;
import org.qortal.api.model.RewardShareKeyRequest;
import org.qortal.asset.Asset;
import org.qortal.controller.LiteNode;
import org.qortal.controller.OnlineAccountsManager;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountPenaltyData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.data.network.OnlineAccountLevel;
import org.qortal.data.transaction.PublicizeTransactionData;
import org.qortal.data.transaction.RewardShareTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.PublicizeTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.PublicizeTransactionTransformer;
import org.qortal.transform.transaction.RewardShareTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Path("/addresses")
@Tag(name = "Addresses")
public class AddressesResource {

	@Context
	HttpServletRequest request;
	
	@GET
	@Path("/{address}")
	@Operation(
		summary = "Return general account information for the given address",
		responses = {
			@ApiResponse(
				description = "general account information",
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AccountData.class))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN, ApiError.REPOSITORY_ISSUE})
	public AccountData getAccountInfo(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);
			// Not found?
			if (accountData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			return accountData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/lastreference/{address}")
	@Operation(
		summary = "Fetch reference for next transaction to be created by address",
		description = "Returns the base58-encoded signature of the last confirmed transaction created by address, failing that: the first incoming transaction. Returns \"false\" if there is no last-reference.",
		responses = {
			@ApiResponse(
				description = "the base58-encoded last-reference",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN, ApiError.REPOSITORY_ISSUE})
	public String getLastReference(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		AccountData accountData;

		if (Settings.getInstance().isLite()) {
			// Lite nodes request data from peers instead of the local db
			accountData = LiteNode.getInstance().fetchAccountData(address);
		}
		else {
			// All other node types request data from local db
			try (final Repository repository = RepositoryManager.getRepository()) {
				accountData = repository.getAccountRepository().getAccount(address);
			} catch (DataException e) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
			}
		}

		// Not found?
		if (accountData == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

		byte[] lastReference = accountData.getReference();

		if (lastReference == null || lastReference.length == 0)
			return "false";

		return Base58.encode(lastReference);
	}

	@GET
	@Path("/validate/{address}")
	@Operation(
		summary = "Validates the given address",
		description = "Returns true/false.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
			)
		}
	)
	public boolean validate(@PathParam("address") String address) {
		return Crypto.isValidAddress(address);
	}

	@GET
	@Path("/online")
	@Operation(
		summary = "Return currently 'online' accounts",
		responses = {
			@ApiResponse(
				description = "online accounts",
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = ApiOnlineAccount.class)))
			)
		}
	)
	@ApiErrors({ApiError.PUBLIC_KEY_NOT_FOUND, ApiError.REPOSITORY_ISSUE})
	public List<ApiOnlineAccount> getOnlineAccounts() {
		List<OnlineAccountData> onlineAccounts = OnlineAccountsManager.getInstance().getOnlineAccounts();

		// Map OnlineAccountData entries to OnlineAccount via reward-share data
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<ApiOnlineAccount> apiOnlineAccounts = new ArrayList<>();

			for (OnlineAccountData onlineAccountData : onlineAccounts) {
				RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(onlineAccountData.getPublicKey());
				if (rewardShareData == null)
					// This shouldn't happen?
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.PUBLIC_KEY_NOT_FOUND);
				final int minterLevel = Account.getRewardShareEffectiveMintingLevelIncludingLevelZero(repository, onlineAccountData.getPublicKey());

				apiOnlineAccounts.add(new ApiOnlineAccount(onlineAccountData.getTimestamp(), onlineAccountData.getSignature(), onlineAccountData.getPublicKey(),
						rewardShareData.getMintingAccount(), rewardShareData.getRecipient(), minterLevel));
			}

			return apiOnlineAccounts;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/online/levels")
	@Operation(
			summary = "Return currently 'online' accounts counts, grouped by level",
			responses = {
					@ApiResponse(
							description = "online accounts",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = ApiOnlineAccount.class)))
					)
			}
	)
	@ApiErrors({ApiError.PUBLIC_KEY_NOT_FOUND, ApiError.REPOSITORY_ISSUE})
	public List<OnlineAccountLevel> getOnlineAccountsByLevel() {
		List<OnlineAccountData> onlineAccounts = OnlineAccountsManager.getInstance().getOnlineAccounts();

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<OnlineAccountLevel> onlineAccountLevels = new ArrayList<>();

			// Prepopulate all levels
			for (int i=0; i<=11; i++)
				onlineAccountLevels.add(new OnlineAccountLevel(i, 0));

			for (OnlineAccountData onlineAccountData : onlineAccounts) {
				try {
					final int minterLevel = Account.getRewardShareEffectiveMintingLevelIncludingLevelZero(repository, onlineAccountData.getPublicKey());

					OnlineAccountLevel onlineAccountLevel = onlineAccountLevels.stream()
							.filter(a -> a.getLevel() == minterLevel)
							.findFirst().orElse(null);

					// Note: I don't think we can use the level as the List index here because there will be gaps.
					// So we are forced to manually look up the existing item each time.
					// There's probably a nice shorthand java way of doing this, but this approach gets the same result.

					if (onlineAccountLevel == null) {
						// No entry exists for this level yet, so create one
						onlineAccountLevel = new OnlineAccountLevel(minterLevel, 1);
						onlineAccountLevels.add(onlineAccountLevel);
					}
					else {
						// Already exists - so increment the count
						int existingCount = onlineAccountLevel.getCount();
						onlineAccountLevel.setCount(++existingCount);

						// Then replace the existing item
						int index = onlineAccountLevels.indexOf(onlineAccountLevel);
						onlineAccountLevels.set(index, onlineAccountLevel);
					}

				} catch (DataException e) {
					continue;
				}
			}

			// Sort by level
			onlineAccountLevels.sort(Comparator.comparingInt(OnlineAccountLevel::getLevel));

			return onlineAccountLevels;

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/balance/{address}")
	@Operation(
		summary = "Returns account balance",
		description = "Returns account's QORT balance, or of other specified asset",
		responses = {
			@ApiResponse(
				description = "the balance",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", format = "number"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.INVALID_ASSET_ID, ApiError.INVALID_HEIGHT,  ApiError.REPOSITORY_ISSUE})
	public BigDecimal getBalance(@PathParam("address") String address,
			@QueryParam("assetId") Long assetId) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Account account = new Account(repository, address);

			if (assetId == null)
				assetId = Asset.QORT;
			else if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			return Amounts.toBigDecimal(account.getConfirmedBalance(assetId));
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/publickey/{address}")
	@Operation(
		summary = "Get public key of address",
		description = "Returns the base58-encoded account public key of the given address, or \"false\" if address not known or has no public key.",
		responses = {
			@ApiResponse(
				description = "the public key",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String getPublicKey(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);

			if (accountData == null)
				return "false";

			byte[] publicKey = accountData.getPublicKey();
			if (publicKey == null)
				return "false";

			return Base58.encode(publicKey);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/convert/{publickey}")
	@Operation(
		summary = "Convert public key into address",
		description = "Returns account address based on supplied public key. Expects base58-encoded, 32-byte public key.",
		responses = {
			@ApiResponse(
				description = "the address",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.NON_PRODUCTION, ApiError.REPOSITORY_ISSUE})
	public String fromPublicKey(@PathParam("publickey") String publicKey58) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		// Decode public key
		byte[] publicKey;
		try {
			publicKey = Base58.decode(publicKey58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY, e);
		}

		// Correct size for public key?
		if (publicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return Crypto.toAddress(publicKey);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/rewardshares")
	@Operation(
		summary = "List reward-share relationships",
		description = "Returns list of accounts, with reward-share percentage and reward-share public key.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = RewardShareData.class)))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<RewardShareData> getRewardShares(@QueryParam("minters") List<String> mintingAccounts,
			@QueryParam("recipients") List<String> recipientAccounts,
			@QueryParam("involving") List<String> addresses,
			@Parameter(
			ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getAccountRepository().findRewardShares(mintingAccounts, recipientAccounts, addresses, limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/rewardsharekey")
	@Operation(
		summary = "Calculate reward-share private key",
		description = "Calculates reward-share private key using passed minting account's private key and recipient account's public key",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RewardShareKeyRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_PUBLIC_KEY, ApiError.REPOSITORY_ISSUE})
	public String calculateRewardShareKey(RewardShareKeyRequest rewardShareKeyRequest) {
		byte[] mintingPrivateKey = rewardShareKeyRequest.mintingAccountPrivateKey;
		byte[] recipientPublicKey = rewardShareKeyRequest.recipientAccountPublicKey;

		if (mintingPrivateKey == null || mintingPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		if (recipientPublicKey == null || recipientPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		PrivateKeyAccount mintingAccount = new PrivateKeyAccount(null, mintingPrivateKey);

		byte[] rewardSharePrivateKey = mintingAccount.getRewardSharePrivateKey(recipientPublicKey);

		return Base58.encode(rewardSharePrivateKey);
	}

	@POST
	@Path("/rewardshare")
	@Operation(
		summary = "Build raw, unsigned, REWARD_SHARE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RewardShareTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, REWARD_SHARE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String rewardShare(RewardShareTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = RewardShareTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/penalties")
	@Operation(
			summary = "Get addresses with penalties",
			description = "Returns a list of accounts with a blocksMintedPenalty",
			responses = {
					@ApiResponse(
							description = "accounts with penalties",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = AccountPenaltyData.class)))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<AccountPenaltyData> getAccountsWithPenalties() {
		try (final Repository repository = RepositoryManager.getRepository()) {

			List<AccountData> accounts = repository.getAccountRepository().getPenaltyAccounts();
			List<AccountPenaltyData> penalties = accounts.stream().map(a -> new AccountPenaltyData(a.getAddress(), a.getBlocksMintedPenalty())).collect(Collectors.toList());

			return penalties;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/penalties/stats")
	@Operation(
			summary = "Get stats about current penalties",
			responses = {
					@ApiResponse(
							description = "aggregated stats about accounts with penalties",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = AccountPenaltyStats.class)))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public AccountPenaltyStats getPenaltyStats() {
		try (final Repository repository = RepositoryManager.getRepository()) {

			List<AccountData> accounts = repository.getAccountRepository().getPenaltyAccounts();
			return AccountPenaltyStats.fromAccounts(accounts);

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/publicize")
	@Operation(
		summary = "Build raw, unsigned, PUBLICIZE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PublicizeTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, PUBLICIZE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String publicize(PublicizeTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK && result != ValidationResult.INCORRECT_NONCE)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = PublicizeTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/publicize/compute")
	@Operation(
		summary = "Compute nonce for raw, unsigned PUBLICIZE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					description = "raw, unsigned PUBLICIZE transaction in base58 encoding",
					example = "raw transaction base58"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, PUBLICIZE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.TRANSACTION_INVALID, ApiError.INVALID_DATA, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String computePublicize(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String rawBytes58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rawBytes = Base58.decode(rawBytes58);
			// We're expecting unsigned transaction, so append empty signature prior to decoding
			rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			if (transactionData.getType() != TransactionType.PUBLICIZE)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			PublicizeTransaction publicizeTransaction = (PublicizeTransaction) Transaction.fromData(repository, transactionData);

			// Quicker validity check first before we compute nonce
			ValidationResult result = publicizeTransaction.isValid();
			if (result != ValidationResult.OK && result != ValidationResult.INCORRECT_NONCE)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			publicizeTransaction.computeNonce();

			// Re-check, but ignores signature
			result = publicizeTransaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			// Strip zeroed signature
			transactionData.setSignature(null);

			byte[] bytes = PublicizeTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}
