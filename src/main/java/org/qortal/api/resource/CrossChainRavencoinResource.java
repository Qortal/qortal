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
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.crosschain.AddressRequest;
import org.qortal.api.model.crosschain.RavencoinSendRequest;
import org.qortal.crosschain.AddressInfo;
import org.qortal.crosschain.ChainableServer;
import org.qortal.crosschain.ElectrumX;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.Ravencoin;
import org.qortal.crosschain.RepairWalletPreview;
import org.qortal.crosschain.ServerConnectionInfo;
import org.qortal.crosschain.ServerInfo;
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

@Path("/crosschain/rvn")
@Tag(name = "Cross-Chain (Ravencoin)")
public class CrossChainRavencoinResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/height")
	@Operation(
		summary = "Returns current Ravencoin block height",
		description = "Returns the height of the most recent block in the Ravencoin chain.",
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
	public String getRavencoinHeight() {
		Ravencoin ravencoin = Ravencoin.getInstance();

		try {
			Integer height = ravencoin.getBlockchainHeight();
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
		summary = "Returns RVN balance for hierarchical, deterministic BIP32 wallet",
		description = "Supply BIP32 'm' private/public key in base58, starting with 'xprv'/'xpub' for mainnet, 'tprv'/'tpub' for testnet",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					description = "BIP32 'm' private/public key in base58",
					example = "tpubD6NzVbkrYhZ4XTPc4btCZ6SMgn8CxmWkj6VBVZ1tfcJfMq4UwAjZbG8U74gGSypL9XBYk2R2BLbDBe8pcEyBKM1edsGQEPKXNbEskZozeZc"
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
	public String getRavencoinWalletBalance(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String key58) {
		Security.checkApiCallAllowed(request);

		Ravencoin ravencoin = Ravencoin.getInstance();

		if (!ravencoin.isValidDeterministicKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			Long balance = ravencoin.getWalletBalance(key58);
			if (balance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

			return balance.toString();

		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/wallettransactions")
	@Operation(
		summary = "Returns transactions for hierarchical, deterministic BIP32 wallet",
		description = "Supply BIP32 'm' private/public key in base58, starting with 'xprv'/'xpub' for mainnet, 'tprv'/'tpub' for testnet",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					description = "BIP32 'm' private/public key in base58",
					example = "tpubD6NzVbkrYhZ4XTPc4btCZ6SMgn8CxmWkj6VBVZ1tfcJfMq4UwAjZbG8U74gGSypL9XBYk2R2BLbDBe8pcEyBKM1edsGQEPKXNbEskZozeZc"
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
	public List<SimpleTransaction> getRavencoinWalletTransactions(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String key58) {
		Security.checkApiCallAllowed(request);

		Ravencoin ravencoin = Ravencoin.getInstance();

		if (!ravencoin.isValidDeterministicKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			return ravencoin.getWalletTransactions(key58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/addressinfos")
	@Operation(
			summary = "Returns information for each address for a hierarchical, deterministic BIP32 wallet",
			description = "Supply BIP32 'm' private/public key in base58, starting with 'xprv'/'xpub' for mainnet, 'tprv'/'tpub' for testnet",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = AddressRequest.class
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(array = @ArraySchema( schema = @Schema( implementation = AddressInfo.class ) ) )
					)
			}

	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<AddressInfo> getRavencoinAddressInfos(@HeaderParam(Security.API_KEY_HEADER) String apiKey, AddressRequest addressRequest) {
		Security.checkApiCallAllowed(request);

		Ravencoin ravencoin = Ravencoin.getInstance();

		if (!ravencoin.isValidDeterministicKey(addressRequest.xpub58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			return ravencoin.getWalletAddressInfos(addressRequest.xpub58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/send")
	@Operation(
		summary = "Sends RVN from hierarchical, deterministic BIP32 wallet to specific address",
		description = "Currently only supports 'legacy' P2PKH Ravencoin addresses. Supply BIP32 'm' private key in base58, starting with 'xprv' for mainnet, 'tprv' for testnet",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RavencoinSendRequest.class
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
	public String sendBitcoin(@HeaderParam(Security.API_KEY_HEADER) String apiKey, RavencoinSendRequest ravencoinSendRequest) {
		Security.checkApiCallAllowed(request);

		if (ravencoinSendRequest.ravencoinAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (ravencoinSendRequest.feePerByte != null && ravencoinSendRequest.feePerByte <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		Ravencoin ravencoin = Ravencoin.getInstance();

		if (!ravencoin.isValidAddress(ravencoinSendRequest.receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (!ravencoin.isValidDeterministicKey(ravencoinSendRequest.xprv58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		Transaction spendTransaction = ravencoin.buildSpend(ravencoinSendRequest.xprv58,
				ravencoinSendRequest.receivingAddress,
				ravencoinSendRequest.ravencoinAmount,
				ravencoinSendRequest.feePerByte);

		if (spendTransaction == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE);

		try {
			ravencoin.broadcastTransaction(spendTransaction);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}

		return spendTransaction.getTxId().toString();
	}

	@GET
	@Path("/serverinfos")
	@Operation(
			summary = "Returns current Ravencoin server configuration",
			description = "Returns current Ravencoin server locations and use status",
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
	public ServerConfigurationInfo getServerConfiguration() {

		return CrossChainUtils.buildServerConfigurationInfo(Ravencoin.getInstance());
	}

	@GET
	@Path("/serverconnectionhistory")
	@Operation(
			summary = "Returns Ravencoin server connection history",
			description = "Returns Ravencoin server connection history",
			responses = {
					@ApiResponse(
							content = @Content(array = @ArraySchema( schema = @Schema( implementation = ServerConnectionInfo.class ) ) )
					)
			}
	)
	public List<ServerConnectionInfo> getServerConnectionHistory() {

		return CrossChainUtils.buildServerConnectionHistory(Ravencoin.getInstance());
	}

	@POST
	@Path("/addserver")
	@Operation(
			summary = "Add server to list of Ravencoin servers",
			description = "Add server to list of Ravencoin servers",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ServerInfo.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "true if added, false if not added",
							content = @Content(
									schema = @Schema(
											type = "string"
									)
							)
					)
			}

	)
	@ApiErrors({ApiError.INVALID_DATA})
	@SecurityRequirement(name = "apiKey")
	public String addServer(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		try {
			ElectrumX.Server server = new ElectrumX.Server(
					serverInfo.getHostName(),
					ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
					serverInfo.getPort()
			);

			if( CrossChainUtils.addServer( Ravencoin.getInstance(), server )) {
				return "true";
			}
			else {
				return "false";
			}
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
		catch (Exception e) {
			return "false";
		}
	}

	@POST
	@Path("/removeserver")
	@Operation(
			summary = "Remove server from list of Ravencoin servers",
			description = "Remove server from list of Ravencoin servers",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ServerInfo.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "true if removed, otherwise",
							content = @Content(
									schema = @Schema(
											type = "string"
									)
							)
					)
			}

	)
	@ApiErrors({ApiError.INVALID_DATA})
	@SecurityRequirement(name = "apiKey")
	public String removeServer(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		try {
			ElectrumX.Server server = new ElectrumX.Server(
					serverInfo.getHostName(),
					ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
					serverInfo.getPort()
			);

			if( CrossChainUtils.removeServer( Ravencoin.getInstance(), server ) ) {

				return "true";
			}
			else {
				return "false";
			}
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
		catch (Exception e) {
			return "false";
		}
	}

	@POST
	@Path("/setcurrentserver")
	@Operation(
			summary = "Set current Ravencoin server",
			description = "Set current Ravencoin server",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ServerInfo.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "connection info",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(
											implementation = ServerConnectionInfo.class
									)
							)
					)
			}

	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.UNAUTHORIZED})
	@SecurityRequirement(name = "apiKey")
	public ServerConnectionInfo setCurrentServer(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		if( serverInfo.getConnectionType() == null ||
				serverInfo.getHostName() == null) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		try {
			ServerConnectionInfo serverConnectionInfo = CrossChainUtils.setCurrentServer(Ravencoin.getInstance(), serverInfo);

			if( serverConnectionInfo != null ) {
				return serverConnectionInfo;
			}
			else {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.UNAUTHORIZED);
			}
		}
		catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
		catch (Exception e) {
			return new ServerConnectionInfo(
					serverInfo,
					CrossChainUtils.CORE_API_CALL,
					true,
					false,
					System.currentTimeMillis(),
					CrossChainUtils.getNotes(e));
		}
	}

	@POST
	@Path("/repair/preview")
	@Operation(
			summary = "Previews wallet repair sweep",
			description = "Supply BIP32 'm' private/public key in base58, starting with 'xprv'/'xpub' for mainnet, 'tprv'/'tpub' for testnet",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "BIP32 'm' private/public key in base58",
									example = "tpubD6NzVbkrYhZ4XTPc4btCZ6SMgn8CxmWkj6VBVZ1tfcJfMq4UwAjZbG8U74gGSypL9XBYk2R2BLbDBe8pcEyBKM1edsGQEPKXNbEskZozeZc"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = RepairWalletPreview.class))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public RepairWalletPreview previewRepairOldWallet(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String key58) {
		Security.checkApiCallAllowed(request);

		Ravencoin ravencoin = Ravencoin.getInstance();

		if (!ravencoin.isValidDeterministicKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			return ravencoin.previewRepairOldWallet(key58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/repair")
	@Operation(
			summary = "Sends all coins in wallet to primary receive address",
			description = "Supply BIP32 'm' private key in base58, starting with 'xprv' for mainnet, 'tprv' for testnet. If repair is not recommended, set force=true to override.",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "BIP32 'm' private key in base58",
									example = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "transaction hash"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String repairOldWallet(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(description = "Force repair even if preview does not recommend it", example = "true")
			@QueryParam("force") Boolean force,
			String key58) {
		Security.checkApiCallAllowed(request);

		Ravencoin ravencoin = Ravencoin.getInstance();

		if (!ravencoin.isValidDeterministicPrivateKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			if (force == null || !force) {
				RepairWalletPreview preview = ravencoin.previewRepairOldWallet(key58);
				if (!preview.isRepairRecommended())
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Repair not recommended; use force=true to override");
			}

			return ravencoin.repairOldWallet(key58);
		} catch (InsufficientMoneyException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}


	@GET
	@Path("/feekb")
	@Operation(
			summary = "Returns Ravencoin fee per Kb.",
			description = "Returns Ravencoin fee per Kb.",
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
	public String getRavencoinFeePerKb() {
		Ravencoin ravencoin = Ravencoin.getInstance();

		return String.valueOf(ravencoin.getFeePerKb().value);
	}

	@POST
	@Path("/updatefeekb")
	@Operation(
			summary = "Sets Ravencoin fee per Kb.",
			description = "Sets Ravencoin fee per Kb.",
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
	public String setRavencoinFeePerKb(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);

		Ravencoin ravencoin = Ravencoin.getInstance();

		try {
			return CrossChainUtils.setFeePerKb(ravencoin, fee);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

	@GET
	@Path("/feerequired")
	@Operation(
			summary = "The total fee required for unlocking RVN to the trade offer creator.",
			description = "This is in sats for a transaction that is approximately 300 kB in size.",
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
	public String getRavencoinFeeRequired() {
		Ravencoin ravencoin = Ravencoin.getInstance();

		return String.valueOf(ravencoin.getFeeRequired());
	}

	@POST
	@Path("/updatefeerequired")
	@Operation(
			summary = "The total fee required for unlocking RVN to the trade offer creator.",
			description = "This is in sats for a transaction that is approximately 300 kB in size.",
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
	public String setRavencoinFeeRequired(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);

		Ravencoin ravencoin = Ravencoin.getInstance();

		try {
			return CrossChainUtils.setFeeRequired(ravencoin, fee);
		}
		catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}
}
