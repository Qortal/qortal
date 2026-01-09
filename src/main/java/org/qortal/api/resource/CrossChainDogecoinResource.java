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
import org.qortal.api.model.crosschain.DogecoinSendRequest;
import org.qortal.crosschain.AddressInfo;
import org.qortal.crosschain.ChainableServer;
import org.qortal.crosschain.ElectrumX;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.Dogecoin;
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

@Path("/crosschain/doge")
@Tag(name = "Cross-Chain (Dogecoin)")
public class CrossChainDogecoinResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/height")
	@Operation(
		summary = "Returns current Dogecoin block height",
		description = "Returns the height of the most recent block in the Dogecoin chain.",
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
	public String getDogecoinHeight() {
		Dogecoin dogecoin = Dogecoin.getInstance();

		try {
			Integer height = dogecoin.getBlockchainHeight();
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
		summary = "Returns DOGE balance for hierarchical, deterministic BIP32 wallet",
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
	public String getDogecoinWalletBalance(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String key58) {
		Security.checkApiCallAllowed(request);

		Dogecoin dogecoin = Dogecoin.getInstance();

		if (!dogecoin.isValidDeterministicKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			Long balance = dogecoin.getWalletBalance(key58);
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
	public List<SimpleTransaction> getDogecoinWalletTransactions(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String key58) {
		Security.checkApiCallAllowed(request);

		Dogecoin dogecoin = Dogecoin.getInstance();

		if (!dogecoin.isValidDeterministicKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			return dogecoin.getWalletTransactions(key58);
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
	public List<AddressInfo> getDogecoinAddressInfos(@HeaderParam(Security.API_KEY_HEADER) String apiKey, AddressRequest addressRequest) {
		Security.checkApiCallAllowed(request);

		Dogecoin dogecoin = Dogecoin.getInstance();

		if (!dogecoin.isValidDeterministicKey(addressRequest.xpub58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			return dogecoin.getWalletAddressInfos(addressRequest.xpub58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/send")
	@Operation(
		summary = "Sends DOGE from hierarchical, deterministic BIP32 wallet to specific address",
		description = "Currently only supports 'legacy' P2PKH Dogecoin addresses. Supply BIP32 'm' private key in base58, starting with 'xprv' for mainnet, 'tprv' for testnet",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = DogecoinSendRequest.class
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
	public String sendBitcoin(@HeaderParam(Security.API_KEY_HEADER) String apiKey, DogecoinSendRequest dogecoinSendRequest) {
		Security.checkApiCallAllowed(request);

		if (dogecoinSendRequest.dogecoinAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (dogecoinSendRequest.feePerByte != null && dogecoinSendRequest.feePerByte <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		Dogecoin dogecoin = Dogecoin.getInstance();

		if (!dogecoin.isValidAddress(dogecoinSendRequest.receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (!dogecoin.isValidDeterministicKey(dogecoinSendRequest.xprv58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		Transaction spendTransaction = dogecoin.buildSpend(dogecoinSendRequest.xprv58,
				dogecoinSendRequest.receivingAddress,
				dogecoinSendRequest.dogecoinAmount,
				dogecoinSendRequest.feePerByte);

		if (spendTransaction == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE);

		try {
			dogecoin.broadcastTransaction(spendTransaction);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}

		return spendTransaction.getTxId().toString();
	}

	@GET
	@Path("/serverinfos")
	@Operation(
			summary = "Returns current Dogecoin server configuration",
			description = "Returns current Dogecoin server locations and use status",
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

		return CrossChainUtils.buildServerConfigurationInfo(Dogecoin.getInstance());
	}

	@GET
	@Path("/serverconnectionhistory")
	@Operation(
			summary = "Returns Dogecoin server connection history",
			description = "Returns Dogecoin server connection history",
			responses = {
					@ApiResponse(
							content = @Content(array = @ArraySchema( schema = @Schema( implementation = ServerConnectionInfo.class ) ) )
					)
			}
	)
	public List<ServerConnectionInfo> getServerConnectionHistory() {

		return CrossChainUtils.buildServerConnectionHistory(Dogecoin.getInstance());
	}

	@POST
	@Path("/addserver")
	@Operation(
			summary = "Add server to list of Dogecoin servers",
			description = "Add server to list of Dogecoin servers",
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

			if( CrossChainUtils.addServer( Dogecoin.getInstance(), server )) {
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
			summary = "Remove server from list of Dogecoin servers",
			description = "Remove server from list of Dogecoin servers",
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

			if( CrossChainUtils.removeServer( Dogecoin.getInstance(), server ) ) {

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
			summary = "Set current Dogecoin server",
			description = "Set current Dogecoin server",
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
			ServerConnectionInfo serverConnectionInfo = CrossChainUtils.setCurrentServer(Dogecoin.getInstance(), serverInfo);

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

		Dogecoin dogecoin = Dogecoin.getInstance();

		if (!dogecoin.isValidDeterministicKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			return dogecoin.previewRepairOldWallet(key58);
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

		Dogecoin dogecoin = Dogecoin.getInstance();

		if (!dogecoin.isValidDeterministicPrivateKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			if (force == null || !force) {
				RepairWalletPreview preview = dogecoin.previewRepairOldWallet(key58);
				if (!preview.isRepairRecommended())
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Repair not recommended; use force=true to override");
			}

			return dogecoin.repairOldWallet(key58);
		} catch (InsufficientMoneyException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}


	@GET
	@Path("/feekb")
	@Operation(
			summary = "Returns Dogecoin fee per Kb.",
			description = "Returns Dogecoin fee per Kb.",
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
	public String getDogecoinFeePerKb() {
		Dogecoin dogecoin = Dogecoin.getInstance();

		return String.valueOf(dogecoin.getFeePerKb().value);
	}

	@POST
	@Path("/updatefeekb")
	@Operation(
			summary = "Sets Dogecoin fee per Kb.",
			description = "Sets Dogecoin fee per Kb.",
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
	public String setDogecoinFeePerKb(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);

		Dogecoin dogecoin = Dogecoin.getInstance();

		try {
			return CrossChainUtils.setFeePerKb(dogecoin, fee);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

	@GET
	@Path("/feerequired")
	@Operation(
			summary = "The total fee required for unlocking DOGE to the trade offer creator.",
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
	public String getDogecoinFeeRequired() {
		Dogecoin dogecoin = Dogecoin.getInstance();

		return String.valueOf(dogecoin.getFeeRequired());
	}

	@POST
	@Path("/updatefeerequired")
	@Operation(
			summary = "The total fee required for unlocking DOGE to the trade offer creator.",
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
	public String setDogecoinFeeRequired(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);

		Dogecoin dogecoin = Dogecoin.getInstance();

		try {
			return CrossChainUtils.setFeeRequired(dogecoin, fee);
		}
		catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}
}
