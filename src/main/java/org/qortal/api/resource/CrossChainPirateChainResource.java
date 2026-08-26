package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.crosschain.PirateChainBalance;
import org.qortal.api.model.crosschain.PirateChainSendRequest;
import org.qortal.api.model.crosschain.ForeignCoinStatus;
import org.qortal.controller.PirateChainWalletController;
import org.qortal.crosschain.ChainableServer;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.PirateChain;
import org.qortal.crosschain.PirateLightClient;
import org.qortal.crosschain.ServerConnectionInfo;
import org.qortal.crosschain.ServerInfo;
import org.qortal.crosschain.SimpleTransaction;
import org.qortal.crosschain.ServerConfigurationInfo;
import org.qortal.settings.Settings;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.Produces;

import java.util.List;

@Path("/crosschain/arrr")
@Tag(name = "Cross-Chain (Pirate Chain)")
public class CrossChainPirateChainResource {

	private static final Logger LOGGER = LogManager.getLogger(CrossChainPirateChainResource.class);

	@Context
	HttpServletRequest request;
	@Context
	HttpHeaders headers;

	@GET
	@Path("/status")
	@Operation(summary = "Returns wallet and lightwallet-server status", responses = {
			@ApiResponse(content = @Content(schema = @Schema(implementation = ForeignCoinStatus.class)))
	})
	public ForeignCoinStatus getPirateStatus() {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		PirateChain pirateChain = PirateChain.getInstance();
		int connections = 0;
		int known = 0;
		if (pirateChain.getBlockchainProvider() instanceof PirateLightClient) {
			PirateLightClient lightClient = (PirateLightClient) pirateChain.getBlockchainProvider();
			connections = lightClient.getConnectedServerCount();
			known = lightClient.getKnownServerCount();
		}
		return new ForeignCoinStatus(walletController != null, connections, known);
	}

	@POST
	@Path("/start")
	@Operation(summary = "Enables and starts the Pirate Chain wallet controller")
	@SecurityRequirement(name = "apiKey")
	public String startPirateChainWallet(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);
		Settings.getInstance().enableWallet("ARRR");
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		if (walletController != null && walletController.getState() == Thread.State.NEW)
			walletController.start();
		return Boolean.toString(walletController != null);
	}

	@GET
	@Path("/height")
	@Operation(summary = "Returns current PirateChain block height", description = "Returns the height of the most recent block in the PirateChain chain.", responses = {
			@ApiResponse(content = @Content(schema = @Schema(type = "number")))
	})
	@ApiErrors({ ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE })
	public String getPirateChainHeight() {
		this.logRequest("height", null);
		PirateChain pirateChain = PirateChain.getInstance();

		try {
			Integer height = pirateChain.getBlockchainHeight();
			if (height == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

			String response = height.toString();
			this.logResponse("height", response, false);
			return response;

		} catch (ForeignBlockchainException e) {
			this.logError("height", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/walletbalance")
	@Operation(summary = "Returns ARRR balance", description = "Supply 32 bytes of entropy, Base58 encoded", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "32 bytes of entropy, Base58 encoded", example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"))), responses = {
			@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "balance (zatoshis)")))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE })
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletBalance(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(description = "If true, return verified balance instead of total balance") @QueryParam("verified") Boolean verified,
			String entropy58) {
		Security.checkApiCallAllowed(request);
		this.logRequest("walletbalance", this.entropyDetail(entropy58) + ", verified=" + verified);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			PirateChainBalance balances = pirateChain.getWalletBalances(entropy58);
			if (balances == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

			long balance = Boolean.TRUE.equals(verified) ? balances.verified_zbalance : balances.zbalance;
			String response = Long.toString(balance);
			this.logResponse("walletbalance", response, false);
			return response;

		} catch (ForeignBlockchainException e) {
			this.logError("walletbalance", e);
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
					e.getMessage());
		}
	}

	@POST
	@Path("/wallettransactions")
	@Operation(summary = "Returns transactions", description = "Supply 32 bytes of entropy, Base58 encoded", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "32 bytes of entropy, Base58 encoded", example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"))), responses = {
			@ApiResponse(content = @Content(array = @ArraySchema(schema = @Schema(implementation = SimpleTransaction.class))))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE })
	@SecurityRequirement(name = "apiKey")
	public List<SimpleTransaction> getPirateChainWalletTransactions(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			String entropy58) {
		Security.checkApiCallAllowed(request);
		this.logRequest("wallettransactions", this.entropyDetail(entropy58));

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			List<SimpleTransaction> response = pirateChain.getWalletTransactions(entropy58);
			this.logResponse("wallettransactions", response, false);
			return response;
		} catch (ForeignBlockchainException e) {
			this.logError("wallettransactions", e);
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
					e.getMessage());
		}
	}

	@POST
	@Path("/send")
	@Operation(summary = "Sends ARRR from wallet", description = "Currently supports 'legacy' P2PKH PirateChain addresses and Native SegWit (P2WPKH) addresses. Supply BIP32 'm' private key in base58, starting with 'xprv' for mainnet, 'tprv' for testnet", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "32 bytes of entropy, Base58 encoded", example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"))), responses = {
			@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "transaction hash")))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS,
			ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE })
	@SecurityRequirement(name = "apiKey")
	public String sendBitcoin(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PirateChainSendRequest pirateChainSendRequest) {
		Security.checkApiCallAllowed(request);
		this.logRequest("send", this.sendRequestDetail(pirateChainSendRequest));

		if (pirateChainSendRequest.arrrAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (pirateChainSendRequest.feePerByte != null && pirateChainSendRequest.feePerByte <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			String response = pirateChain.sendCoins(pirateChainSendRequest);
			this.logResponse("send", response, true);
			return response;

		} catch (ForeignBlockchainException e) {
			// TODO
			this.logError("send", e);
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
					e.getMessage());
		}
	}

	@POST
	@Path("/walletaddress")
	@Operation(summary = "Returns main wallet address", description = "Supply 32 bytes of entropy, Base58 encoded", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "32 bytes of entropy, Base58 encoded", example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"))), responses = {
			@ApiResponse(content = @Content(array = @ArraySchema(schema = @Schema(implementation = SimpleTransaction.class))))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE })
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletAddress(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);
		this.logRequest("walletaddress", this.entropyDetail(entropy58));

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			String response = pirateChain.getWalletAddress(entropy58);
			this.logResponse("walletaddress", response, true);
			return response;
		} catch (ForeignBlockchainException e) {
			this.logError("walletaddress", e);
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
					e.getMessage());
		}
	}

	@POST
	@Path("/walletprivatekey")
	@Operation(summary = "Returns main wallet private key", description = "Supply 32 bytes of entropy, Base58 encoded", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "32 bytes of entropy, Base58 encoded", example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"))), responses = {
			@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "Private Key String")))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE })
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainPrivateKey(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);
		this.logRequest("walletprivatekey", this.entropyDetail(entropy58));

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			String response = pirateChain.getPrivateKey(entropy58);
			this.logResponse("walletprivatekey", response, true);
			return response;
		} catch (ForeignBlockchainException e) {
			this.logError("walletprivatekey", e);
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
					e.getMessage());
		}
	}

	@POST
	@Path("/walletseedphrase")
	@Operation(summary = "Returns main wallet seedphrase", description = "Supply 32 bytes of entropy, Base58 encoded", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "32 bytes of entropy, Base58 encoded", example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"))), responses = {
			@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "Wallet Seedphrase String")))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE })
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletSeed(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);
		this.logRequest("walletseedphrase", this.entropyDetail(entropy58));

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			String response = pirateChain.getWalletSeed(entropy58);
			this.logResponse("walletseedphrase", response, true);
			return response;
		} catch (ForeignBlockchainException e) {
			this.logError("walletseedphrase", e);
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
					e.getMessage());
		}
	}

	@POST
	@Path("/syncstatus")
	@Produces({ MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON })
	@Operation(summary = "Returns synchronization status", description = "Supply 32 bytes of entropy, Base58 encoded", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "32 bytes of entropy, Base58 encoded", example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"))), responses = {
			@ApiResponse(content = @Content(schema = @Schema(type = "string", description = "sync status or status JSON")))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE })
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainSyncStatus(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(description = "If true, return JSON status payload") @QueryParam("json") Boolean json,
			String entropy58) {
		Security.checkApiCallAllowed(request);
		this.logRequest("syncstatus", this.entropyDetail(entropy58) + ", json=" + json);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			String response = Boolean.TRUE.equals(json)
					? pirateChain.getSyncStatusJson(entropy58)
					: pirateChain.getSyncStatus(entropy58);
			this.logResponse("syncstatus", response, false);
			return response;
		} catch (ForeignBlockchainException e) {
			this.logError("syncstatus", e);
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
					e.getMessage());
		}
	}

	@GET
	@Path("/serverinfos")
	@Operation(summary = "Returns current PirateChain server configuration", description = "Returns current PirateChain server locations and use status", responses = {
			@ApiResponse(content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerConfigurationInfo.class)))
	})
	public ServerConfigurationInfo getServerConfiguration() {
		this.logRequest("serverinfos", null);
		ServerConfigurationInfo response = CrossChainUtils.buildServerConfigurationInfo(PirateChain.getInstance());
		this.logResponse("serverinfos", response, false);
		return response;
	}

	@GET
	@Path("/serverconnectionhistory")
	@Operation(summary = "Returns Pirate Chain server connection history", description = "Returns Pirate Chain server connection history", responses = {
			@ApiResponse(content = @Content(array = @ArraySchema(schema = @Schema(implementation = ServerConnectionInfo.class))))
	})
	public List<ServerConnectionInfo> getServerConnectionHistory() {
		this.logRequest("serverconnectionhistory", null);
		List<ServerConnectionInfo> response = CrossChainUtils.buildServerConnectionHistory(PirateChain.getInstance());
		this.logResponse("serverconnectionhistory", response, false);
		return response;
	}

	@POST
	@Path("/addserver")
	@Operation(summary = "Add server to list of Pirate Chain servers", description = "Add server to list of Pirate Chain servers", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerInfo.class))), responses = {
			@ApiResponse(description = "true if added, false if not added", content = @Content(schema = @Schema(type = "string")))
	}

	)
	@ApiErrors({ ApiError.INVALID_DATA })
	@SecurityRequirement(name = "apiKey")
	public String addServerInfo(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);
		this.logRequest("addserver", this.serverInfoDetail(serverInfo));

		try {
			PirateLightClient.Server server = new PirateLightClient.Server(
					serverInfo.getHostName(),
					ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
					serverInfo.getPort());

			String response = CrossChainUtils.addServer(PirateChain.getInstance(), server) ? "true" : "false";
			this.logResponse("addserver", response, false);
			return response;
		} catch (IllegalArgumentException | NullPointerException e) {
			this.logError("addserver", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (Exception e) {
			this.logError("addserver", e);
			return "false";
		}
	}

	@POST
	@Path("/removeserver")
	@Operation(summary = "Remove server from list of Pirate Chain servers", description = "Remove server from list of Pirate Chain servers", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerInfo.class))), responses = {
			@ApiResponse(description = "true if removed, otherwise", content = @Content(schema = @Schema(type = "string")))
	}

	)
	@ApiErrors({ ApiError.INVALID_DATA })
	@SecurityRequirement(name = "apiKey")
	public String removeServerInfo(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);
		this.logRequest("removeserver", this.serverInfoDetail(serverInfo));

		try {
			PirateLightClient.Server server = new PirateLightClient.Server(
					serverInfo.getHostName(),
					ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
					serverInfo.getPort());

			String response = CrossChainUtils.removeServer(PirateChain.getInstance(), server) ? "true" : "false";
			this.logResponse("removeserver", response, false);
			return response;
		} catch (IllegalArgumentException | NullPointerException e) {
			this.logError("removeserver", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (Exception e) {
			this.logError("removeserver", e);
			return "false";
		}
	}

	@POST
	@Path("/setcurrentserver")
	@Operation(summary = "Set current Pirate Chain server", description = "Set current Pirate Chain server", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerInfo.class))), responses = {
			@ApiResponse(description = "connection info", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerConnectionInfo.class)))
	}

	)
	@ApiErrors({ ApiError.INVALID_DATA })
	@SecurityRequirement(name = "apiKey")
	public ServerConnectionInfo setCurrentServerInfo(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);
		this.logRequest("setcurrentserver", this.serverInfoDetail(serverInfo));

		if (serverInfo.getConnectionType() == null ||
				serverInfo.getHostName() == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		try {
			ServerConnectionInfo response = CrossChainUtils.setCurrentServer(PirateChain.getInstance(), serverInfo);
			this.logResponse("setcurrentserver", response, false);
			return response;
		} catch (IllegalArgumentException e) {
			this.logError("setcurrentserver", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (Exception e) {
			this.logError("setcurrentserver", e);
			ServerConnectionInfo response = new ServerConnectionInfo(
					serverInfo,
					CrossChainUtils.CORE_API_CALL,
					true,
					false,
					System.currentTimeMillis(),
					CrossChainUtils.getNotes(e));
			this.logResponse("setcurrentserver", response, false);
			return response;
		}
	}

	@GET
	@Path("/feekb")
	@Operation(summary = "Returns PirateChain fee per Kb.", description = "Returns PirateChain fee per Kb.", responses = {
			@ApiResponse(content = @Content(schema = @Schema(type = "number")))
	})
	public String getPirateChainFeePerKb() {
		this.logRequest("feekb", null);
		PirateChain pirateChain = PirateChain.getInstance();

		String response = String.valueOf(pirateChain.getFeePerKb().value);
		this.logResponse("feekb", response, false);
		return response;
	}

	@POST
	@Path("/updatefeekb")
	@Operation(summary = "Sets PirateChain fee per Kb.", description = "Sets PirateChain fee per Kb.", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number", description = "the fee per Kb", example = "100"))), responses = {
			@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number", description = "fee")))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA })
	public String setPirateChainFeePerKb(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);
		this.logRequest("updatefeekb", "fee=" + fee);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			String response = CrossChainUtils.setFeePerKb(pirateChain, fee);
			this.logResponse("updatefeekb", response, false);
			return response;
		} catch (IllegalArgumentException e) {
			this.logError("updatefeekb", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

	@GET
	@Path("/feerequired")
	@Operation(summary = "The total fee required for unlocking ARRR to the trade offer creator.", description = "The total fee required for unlocking ARRR to the trade offer creator.", responses = {
			@ApiResponse(content = @Content(schema = @Schema(type = "number")))
	})
	public String getPirateChainFeeRequired() {
		this.logRequest("feerequired", null);
		PirateChain pirateChain = PirateChain.getInstance();

		String response = String.valueOf(pirateChain.getFeeRequired());
		this.logResponse("feerequired", response, false);
		return response;
	}

	@POST
	@Path("/updatefeerequired")
	@Operation(summary = "The total fee required for unlocking ARRR to the trade offer creator.", description = "This is in sats for a transaction that is approximately 300 kB in size.", requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number", description = "the fee", example = "100"))), responses = {
			@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number", description = "fee")))
	})
	@ApiErrors({ ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA })
	public String setPirateChainFeeRequired(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);
		this.logRequest("updatefeerequired", "fee=" + fee);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			String response = CrossChainUtils.setFeeRequired(pirateChain, fee);
			this.logResponse("updatefeerequired", response, false);
			return response;
		} catch (IllegalArgumentException e) {
			this.logError("updatefeerequired", e);
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

	private void logRequest(String action, String detail) {
		String info = detail == null ? "" : " (" + detail + ")";
		if (this.request == null) {
			LOGGER.info("Pirate API {} request{}", action, info);
			return;
		}
		String uri = this.request.getRequestURI();
		String query = this.request.getQueryString();
		String path = query == null ? uri : uri + "?" + CrossChainPirateChainResource.redactQueryString(query);
		LOGGER.info("Pirate API {} request: {}{}", action, path, info);
	}

	/** Keep API keys out of request logs when clients use the query parameter form. */
	static String redactQueryString(String query) {
		String[] parameters = query.split("&", -1);
		StringBuilder redacted = new StringBuilder(query.length());
		for (int index = 0; index < parameters.length; ++index) {
			if (index > 0) {
				redacted.append('&');
			}
			String parameter = parameters[index];
			int separator = parameter.indexOf('=');
			String name = separator >= 0 ? parameter.substring(0, separator) : parameter;
			if ("apiKey".equalsIgnoreCase(name)) {
				redacted.append(name).append("=<redacted>");
			} else {
				redacted.append(parameter);
			}
		}
		return redacted.toString();
	}

	private void logResponse(String action, Object response, boolean sensitive) {
		LOGGER.info("Pirate API {} response: {}", action, this.summarizeResponse(response, sensitive));
	}

	private void logError(String action, Exception e) {
		LOGGER.info("Pirate API {} error: {}", action, e.getMessage());
	}

	private String summarizeResponse(Object response, boolean sensitive) {
		if (response == null) {
			return "null";
		}
		if (response instanceof String) {
			String value = (String) response;
			if (sensitive) {
				return "string len=" + value.length() + " (redacted)";
			}
			return "string len=" + value.length();
		}
		if (response instanceof List) {
			return "list size=" + ((List<?>) response).size();
		}
		if (response instanceof ServerConfigurationInfo) {
			ServerConfigurationInfo info = (ServerConfigurationInfo) response;
			int servers = info.getServers() != null ? info.getServers().size() : 0;
			int remaining = info.getRemainingServers() != null ? info.getRemainingServers().size() : 0;
			int useless = info.getUselessServers() != null ? info.getUselessServers().size() : 0;
			return "servers=" + servers + ", remaining=" + remaining + ", useless=" + useless;
		}
		return response.getClass().getSimpleName();
	}

	private String entropyDetail(String entropy58) {
		int length = entropy58 == null ? 0 : entropy58.length();
		return "entropyLen=" + length;
	}

	private String serverInfoDetail(ServerInfo serverInfo) {
		if (serverInfo == null) {
			return "server=null";
		}
		return "server=" + serverInfo.getHostName() + ":" + serverInfo.getPort()
				+ " (" + serverInfo.getConnectionType() + ")";
	}

	private String sendRequestDetail(PirateChainSendRequest requestBody) {
		if (requestBody == null) {
			return "request=null";
		}
		String address = this.maskAddress(requestBody.receivingAddress);
		return "entropyLen=" + (requestBody.entropy58 == null ? 0 : requestBody.entropy58.length())
				+ ", address=" + address
				+ ", amount=" + requestBody.arrrAmount
				+ ", feePerByte=" + requestBody.feePerByte;
	}

	private String maskAddress(String address) {
		if (address == null || address.isEmpty()) {
			return "null";
		}
		if (address.length() <= 8) {
			return "****";
		}
		return address.substring(0, 4) + "..." + address.substring(address.length() - 4);
	}
}
