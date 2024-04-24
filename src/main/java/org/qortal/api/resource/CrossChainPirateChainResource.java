package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.crosschain.PirateChainSendRequest;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.PirateChain;
import org.qortal.crosschain.SimpleTransaction;
import org.qortal.crosschain.ServerConfigurationInfo;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Path("/crosschain/arrr")
@Tag(name = "Cross-Chain (Pirate Chain)")
public class CrossChainPirateChainResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/height")
	@Operation(
		summary = "Returns current PirateChain block height",
		description = "Returns the height of the most recent block in the PirateChain chain.",
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
	@ApiErrors({ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	public String getPirateChainHeight() {
		PirateChain pirateChain = PirateChain.getInstance();

		try {
			Integer height = pirateChain.getBlockchainHeight();
			if (height == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

			return height.toString();

		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/walletbalance")
	@Operation(
		summary = "Returns ARRR balance",
		description = "Supply 32 bytes of entropy, Base58 encoded",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
						type = "string",
						description = "32 bytes of entropy, Base58 encoded",
						example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "balance (satoshis)"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletBalance(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			Long balance = pirateChain.getWalletBalance(entropy58);
			if (balance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

			return balance.toString();

		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/wallettransactions")
	@Operation(
		summary = "Returns transactions",
		description = "Supply 32 bytes of entropy, Base58 encoded",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
						type = "string",
						description = "32 bytes of entropy, Base58 encoded",
						example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(array = @ArraySchema( schema = @Schema( implementation = SimpleTransaction.class ) ) )
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<SimpleTransaction> getPirateChainWalletTransactions(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getWalletTransactions(entropy58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/send")
	@Operation(
		summary = "Sends ARRR from wallet",
		description = "Currently supports 'legacy' P2PKH PirateChain addresses and Native SegWit (P2WPKH) addresses. Supply BIP32 'm' private key in base58, starting with 'xprv' for mainnet, 'tprv' for testnet",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
						type = "string",
						description = "32 bytes of entropy, Base58 encoded",
						example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "transaction hash"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String sendBitcoin(@HeaderParam(Security.API_KEY_HEADER) String apiKey, PirateChainSendRequest pirateChainSendRequest) {
		Security.checkApiCallAllowed(request);

		if (pirateChainSendRequest.arrrAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (pirateChainSendRequest.feePerByte != null && pirateChainSendRequest.feePerByte <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.sendCoins(pirateChainSendRequest);

		} catch (ForeignBlockchainException e) {
			// TODO
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}


	@POST
	@Path("/walletaddress")
	@Operation(
			summary = "Returns main wallet address",
			description = "Supply 32 bytes of entropy, Base58 encoded",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "32 bytes of entropy, Base58 encoded",
									example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(array = @ArraySchema( schema = @Schema( implementation = SimpleTransaction.class ) ) )
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletAddress(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getWalletAddress(entropy58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/walletprivatekey")
	@Operation(
			summary = "Returns main wallet private key",
			description = "Supply 32 bytes of entropy, Base58 encoded",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "32 bytes of entropy, Base58 encoded",
									example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "Private Key String"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainPrivateKey(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getPrivateKey(entropy58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/walletseedphrase")
	@Operation(
			summary = "Returns main wallet seedphrase",
			description = "Supply 32 bytes of entropy, Base58 encoded",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "32 bytes of entropy, Base58 encoded",
									example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "Wallet Seedphrase String"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletSeed(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getWalletSeed(entropy58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/syncstatus")
	@Operation(
			summary = "Returns synchronization status",
			description = "Supply 32 bytes of entropy, Base58 encoded",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "32 bytes of entropy, Base58 encoded",
									example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(array = @ArraySchema( schema = @Schema( implementation = SimpleTransaction.class ) ) )
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainSyncStatus(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getSyncStatus(entropy58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@GET
	@Path("/serverinfos")
	@Operation(
			summary = "Returns current PirateChain server configuration",
			description = "Returns current PirateChain server locations and use status",
			responses = {
					@ApiResponse(
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(
											implementation = ServerConfigurationInfo.class
									)
							)
					)
			}
	)
	public ServerConfigurationInfo getServerConfiguration(@Parameter(
		description = "Returns info for the currently connected server only"
	) @QueryParam("current") Boolean current) {

		return CrossChainUtils.buildServerConfigurationInfo(PirateChain.getInstance(), current);
	}

	@GET
	@Path("/feekb")
	@Operation(
			summary = "Returns PirateChain fee per Kb.",
			description = "Returns PirateChain fee per Kb.",
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
	public String getPirateChainFeePerKb() {
		PirateChain pirateChain = PirateChain.getInstance();

		return String.valueOf(pirateChain.getFeePerKb().value);
	}

	@POST
	@Path("/updatefeekb")
	@Operation(
			summary = "Sets PirateChain fee per Kb.",
			description = "Sets PirateChain fee per Kb.",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "number",
									description = "the fee per Kb",
									example = "100"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number", description = "fee"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA})
	public String setPirateChainFeePerKb(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return CrossChainUtils.setFeePerKb(pirateChain, fee);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

	@GET
	@Path("/feeceiling")
	@Operation(
			summary = "Returns PirateChain fee per Kb.",
			description = "Returns PirateChain fee per Kb.",
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
	public String getPirateChainFeeCeiling() {
		PirateChain pirateChain = PirateChain.getInstance();

		return String.valueOf(pirateChain.getFeeCeiling());
	}

	@POST
	@Path("/updatefeeceiling")
	@Operation(
			summary = "Sets PirateChain fee ceiling.",
			description = "Sets PirateChain fee ceiling.",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "number",
									description = "the fee",
									example = "100"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number", description = "fee"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA})
	public String setPirateChainFeeCeiling(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return CrossChainUtils.setFeeCeiling(pirateChain, fee);
		}
		catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}
}
