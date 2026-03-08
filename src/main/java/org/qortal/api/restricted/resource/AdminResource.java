package org.qortal.api.restricted.resource;

import com.google.common.collect.Lists;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.json.JSONArray;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.*;
import org.qortal.api.model.ActivitySummary;
import org.qortal.api.model.CertificateSanInfo;
import org.qortal.api.model.NodeInfo;
import org.qortal.api.model.NodeStatus;
import org.qortal.block.BlockChain;
import org.qortal.controller.BootstrapNode;
import org.qortal.controller.Controller;
import org.qortal.controller.RestartNode;
import org.qortal.controller.Synchronizer;
import org.qortal.controller.Synchronizer.SynchronizationResult;
import org.qortal.controller.repository.BlockArchiveRebuilder;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.network.PeerAddressFactory;
import org.qortal.repository.ReindexManager;
import org.qortal.repository.DataException;
import org.qortal.repository.ReindexManager;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.data.system.SystemInfo;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.SslUtils;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Path("/admin")
@Tag(name = "Admin")
public class AdminResource {

	private static final Logger LOGGER = LogManager.getLogger(AdminResource.class);

	private static final int MAX_LOG_LINES = 500;

	@Context
	HttpServletRequest request;

	/** Alias of the server key entry in the SSL keystore (must match SslUtils). */
	private static final String SSL_KEYSTORE_ALIAS = "server";

	@POST
	@Path("/http/createca")
	@Operation(
			summary = "Create a new local root CA",
			description = "Generates and saves a new root CA certificate and key, then restarts the API service to apply the new certificate.",
			responses = {
					@ApiResponse(
							description = "CA created successfully",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String createCA(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);
		try {
			// Generate new SSL certificate
			SslUtils.generateSsl();
			
			// Restart API service in a background thread to apply the new certificate
			// This allows the current request to complete before the restart happens
			new Thread(() -> {
				try {
					// Give the response time to be sent back to the client
					Thread.sleep(500);
					LOGGER.info("Restarting API service to apply new SSL certificate...");
					ApiService apiService = ApiService.getInstance();
					try {
						apiService.restart();
					} catch (Exception e) {
						LOGGER.warn("First restart attempt failed ({}), retrying after 2s: {}", e.getMessage(), e);
						Thread.sleep(2000);
						apiService.start();
					}
					LOGGER.info("API service restarted successfully with new SSL certificate");
				} catch (Exception e) {
					LOGGER.error("Failed to restart API service after certificate generation. API may be down. Restart the node to recover: {}", e.getMessage(), e);
				}
			}, "SSL-Cert-Restart").start();
			
			return "CA and server certificate created successfully. API service will restart in a moment to apply the new certificate.";
		} catch (Exception e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA, e);
		}
	}

	@GET
	@Path("/http/getca")
	@Operation(
			summary = "Get the local root CA certificate",
			description = "Returns the root CA certificate in PEM format.",
			responses = {
					@ApiResponse(
							description = "The root CA certificate",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
					)
			}
	)
	public String getCA() {
		String keystorePathname = Settings.getInstance().getSslKeystorePathname();
		String keystorePassword = Settings.getInstance().getSslKeystorePassword();
		if (keystorePathname == null || keystorePassword == null) {
			return "CA certificate not found.";
		}
		java.nio.file.Path keystorePath = Paths.get(keystorePathname);
		if (!Files.isReadable(keystorePath)) {
			return "CA certificate not found.";
		}
		try {
			KeyStore keyStore = KeyStore.getInstance("PKCS12", "BC");
			try (FileInputStream fis = new FileInputStream(keystorePath.toFile())) {
				keyStore.load(fis, keystorePassword.toCharArray());
			}
			if (!keyStore.containsAlias(SSL_KEYSTORE_ALIAS)) {
				return "CA certificate not found.";
			}
			Certificate[] chain = keyStore.getCertificateChain(SSL_KEYSTORE_ALIAS);
			if (chain == null || chain.length < 2) {
				return "CA certificate not found.";
			}
			// Root CA is the last certificate in the chain (same order as TLS server sends).
			X509Certificate caCert = (X509Certificate) chain[chain.length - 1];
			StringWriter sw = new StringWriter();
			try (PemWriter pw = new PemWriter(sw)) {
				pw.writeObject(new PemObject("CERTIFICATE", caCert.getEncoded()));
			}
			return sw.toString();
		} catch (Exception e) {
			LOGGER.debug("Could not read CA from keystore: {}", e.getMessage());
			return "CA certificate not found.";
		}
	}

	@GET
	@Path("/http/san")
	@Operation(
			summary = "Get SSL certificate Subject Alternative Names",
			description = "Returns the DNS names and IP addresses the current server certificate is valid for (SAN list).",
			responses = {
					@ApiResponse(
							description = "SAN list (dns and ip arrays)",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = CertificateSanInfo.class))
					)
			}
	)
	public CertificateSanInfo getCertificateSan() {
		List<String> dns = new ArrayList<>();
		List<String> ip = new ArrayList<>();
		String keystorePathname = Settings.getInstance().getSslKeystorePathname();
		String keystorePassword = Settings.getInstance().getSslKeystorePassword();
		if (keystorePathname == null || keystorePassword == null) {
			return new CertificateSanInfo(dns, ip);
		}
		java.nio.file.Path keystorePath = Paths.get(keystorePathname);
		if (!Files.isReadable(keystorePath)) {
			return new CertificateSanInfo(dns, ip);
		}
		try {
			KeyStore keyStore = KeyStore.getInstance("PKCS12", "BC");
			try (FileInputStream fis = new FileInputStream(keystorePath.toFile())) {
				keyStore.load(fis, keystorePassword.toCharArray());
			}
			if (!keyStore.containsAlias(SSL_KEYSTORE_ALIAS)) {
				return new CertificateSanInfo(dns, ip);
			}
			Certificate[] chain = keyStore.getCertificateChain(SSL_KEYSTORE_ALIAS);
			if (chain == null || chain.length < 1) {
				return new CertificateSanInfo(dns, ip);
			}
			X509Certificate serverCert = (X509Certificate) chain[0];
			Collection<?> sanCollection = serverCert.getSubjectAlternativeNames();
			if (sanCollection != null) {
				for (Object item : sanCollection) {
					if (!(item instanceof List)) continue;
					List<?> pair = (List<?>) item;
					if (pair.size() < 2) continue;
					Integer type = (Integer) pair.get(0);
					Object value = pair.get(1);
				if (type == null || value == null) continue;
				if (type == 2) {
					dns.add(value.toString());
				} else if (type == 7) {
					// Handle both byte[] (standard ASN.1 OCTET STRING) and String formats
					try {
						if (value instanceof byte[]) {
							// Standard format: byte array representation of IP address
							ip.add(InetAddress.getByAddress((byte[]) value).getHostAddress());
						} else if (value instanceof String) {
							// Alternative format: IP address already as string
							String ipStr = (String) value;
							if (!ipStr.isEmpty()) {
								ip.add(ipStr);
							}
						}
					} catch (Exception e) {
						LOGGER.trace("Could not parse SAN IP: {}", e.getMessage());
					}
				}
				}
			}
			return new CertificateSanInfo(dns, ip);
		} catch (Exception e) {
			LOGGER.debug("Could not read SAN from keystore: {}", e.getMessage());
			return new CertificateSanInfo(dns, ip);
		}
	}

	@GET
	@Path("/unused")
	@Parameter(in = ParameterIn.PATH, name = "assetid", description = "Asset ID, 0 is native coin", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.PATH, name = "otherassetid", description = "Asset ID, 0 is native coin", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.PATH, name = "address", description = "An account address", example = "QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v")
	@Parameter(in = ParameterIn.PATH, name = "path", description = "Local path to folder containing the files", schema = @Schema(type = "String", defaultValue = "/Users/user/Documents/MyStaticWebsite"))
	@Parameter(in = ParameterIn.QUERY, name = "count", description = "Maximum number of entries to return, 0 means none", schema = @Schema(type = "integer", defaultValue = "20"))
	@Parameter(in = ParameterIn.QUERY, name = "limit", description = "Maximum number of entries to return, 0 means unlimited", schema = @Schema(type = "integer", defaultValue = "20"))
	@Parameter(in = ParameterIn.QUERY, name = "offset", description = "Starting entry in results, 0 is first entry", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.QUERY, name = "reverse", description = "Reverse results", schema = @Schema(type = "boolean"))
	public String globalParameters() {
		return "";
	}

	@GET
	@Path("/uptime")
	@Operation(
		summary = "Fetch running time of server",
		description = "Returns uptime in milliseconds",
		responses = {
			@ApiResponse(
				description = "uptime in milliseconds",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number"))
			)
		}
	)
	public long uptime() {
		return System.currentTimeMillis() - Controller.startTime;
	}

	@GET
	@Path("/info")
	@Operation(
		summary = "Fetch generic node info",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NodeInfo.class))
			)
		}
	)
	public NodeInfo info() {
		NodeInfo nodeInfo = new NodeInfo();

		nodeInfo.currentTimestamp = NTP.getTime();
		nodeInfo.uptime = System.currentTimeMillis() - Controller.startTime;
		nodeInfo.buildVersion = Controller.getInstance().getVersionString();
		nodeInfo.buildTimestamp = Controller.getInstance().getBuildTimestamp();
		nodeInfo.nodeId = Network.getInstance().getOurNodeId();
		nodeInfo.isTestNet = Settings.getInstance().isTestNet();
		nodeInfo.type = getNodeType();

		return nodeInfo;
	}

	private String getNodeType() {
		if (Settings.getInstance().isLite()) {
			return "lite";
		}
		else if (Settings.getInstance().isTopOnly()) {
			return "topOnly";
		}
		else {
			return "full";
		}
	}

	@GET
	@Path("/status")
	@Operation(
		summary = "Fetch node status",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NodeStatus.class))
			)
		}
	)
	public NodeStatus status() {
		NodeStatus nodeStatus = new NodeStatus();

		return nodeStatus;
	}

	@GET
	@Path("/settings")
	@Operation(
		summary = "Fetch node settings",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Settings.class))
			)
		}
	)
	public Settings settings() {
		Settings nodeSettings = Settings.getInstance();

		return nodeSettings;
	}

	@GET
	@Path("/settings/{setting}")
	@Operation(
			summary = "Fetch a single node setting",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
					)
			}
	)
	public String setting(@PathParam("setting") String setting) {
		try {
			Object settingValue = FieldUtils.readField(Settings.getInstance(), setting, true);
			if (settingValue == null) {
				return "null";
			}
			else if (settingValue instanceof String[]) {
				JSONArray array = new JSONArray(settingValue);
				return array.toString(4);
			}
			else if (settingValue instanceof List) {
				JSONArray array = new JSONArray((List<Object>) settingValue);
				return array.toString(4);
			}

			return settingValue.toString();
		} catch (IllegalAccessException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA, e);
		}
	}

	@GET
	@Path("/stop")
	@Operation(
		summary = "Shutdown",
		description = "Shutdown",
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public String shutdown(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		new Thread(() -> {
			// Short sleep to allow HTTP response body to be emitted
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// Not important
			}

			Controller.getInstance().shutdownAndExit();
		}).start();

		return "true";
	}

	@GET
	@Path("/restart")
	@Operation(
		summary = "Restart",
		description = "Restart",
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public String restart(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		new Thread(() -> {
			// Short sleep to allow HTTP response body to be emitted
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// Not important
			}

			RestartNode.attemptToRestart();

		}).start();

		return "true";
	}

	@GET
	@Path("/bootstrap")
	@Operation(
		summary = "Bootstrap",
		description = "Delete and download new database archive",
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public String bootstrap(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		new Thread(() -> {
			// Short sleep to allow HTTP response body to be emitted
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// Not important
			}

			BootstrapNode.attemptToBootstrap();

		}).start();

		return "true";
	}

	@GET
	@Path("/summary")
	@Operation(
		summary = "Summary of activity past 24 hours",
		responses = {
			@ApiResponse(
				content = @Content(schema = @Schema(implementation = ActivitySummary.class))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public ActivitySummary summary() {
		ActivitySummary summary = new ActivitySummary();
		
		long now = NTP.getTime();
		long oneday = now - 24 * 60 * 60 * 1000L;

		try (final Repository repository = RepositoryManager.getRepository()) {
			int startHeight = repository.getBlockRepository().getHeightFromTimestamp(oneday);
			int endHeight = repository.getBlockRepository().getBlockchainHeight();

			summary.setBlockCount(endHeight - startHeight);

			summary.setTransactionCountByType(repository.getTransactionRepository().getTransactionSummary(startHeight + 1, endHeight));

			summary.setAssetsIssued(repository.getAssetRepository().getRecentAssetIds(oneday).size());

			summary.setNamesRegistered (repository.getNameRepository().getRecentNames(oneday).size());

			return summary;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/summary/alltime")
	@Operation(
			summary = "Summary of activity since genesis",
			responses = {
					@ApiResponse(
							content = @Content(schema = @Schema(implementation = ActivitySummary.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public ActivitySummary allTimeSummary(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		ActivitySummary summary = new ActivitySummary();

		try (final Repository repository = RepositoryManager.getRepository()) {
			int startHeight = 1;
			long start = repository.getBlockRepository().fromHeight(startHeight).getTimestamp();
			int endHeight = repository.getBlockRepository().getBlockchainHeight();

			summary.setBlockCount(endHeight - startHeight);

			summary.setTransactionCountByType(repository.getTransactionRepository().getTransactionSummary(startHeight + 1, endHeight));

			summary.setAssetsIssued(repository.getAssetRepository().getRecentAssetIds(start).size());

			summary.setNamesRegistered (repository.getNameRepository().getRecentNames(start).size());

			return summary;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/enginestats")
	@Operation(
		summary = "Fetch statistics snapshot for core engine",
		responses = {
			@ApiResponse(
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(
						schema = @Schema(
							implementation = Controller.StatsSnapshot.class
						)
					)
				)
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public Controller.StatsSnapshot getEngineStats(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		return Controller.getInstance().getStatsSnapshot();
	}

	@GET
	@Path("/mintingaccounts")
	@Operation(
		summary = "List public keys of accounts used to mint blocks by BlockMinter",
		description = "Returns PUBLIC keys of accounts for safety.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = MintingAccountData.class)))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<MintingAccountData> getMintingAccounts() {

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<MintingAccountData> mintingAccounts = repository.getAccountRepository().getMintingAccounts();

			// Expand with reward-share data where appropriate
			mintingAccounts = mintingAccounts.stream().map(mintingAccountData -> {
				byte[] publicKey = mintingAccountData.getPublicKey();

				RewardShareData rewardShareData = null;
				try {
					rewardShareData = repository.getAccountRepository().getRewardShare(publicKey);
				} catch (DataException e) {
					// ignore
				}

				return new MintingAccountData(mintingAccountData, rewardShareData);
			}).collect(Collectors.toList());

			return mintingAccounts;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/mintingaccounts")
	@Operation(
		summary = "Add private key of account/reward-share for use by BlockMinter to mint blocks",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "private key"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.REPOSITORY_ISSUE, ApiError.CANNOT_MINT})
	@SecurityRequirement(name = "apiKey")
	public String addMintingAccount(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String seed58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] seed = Base58.decode(seed58.trim());

			// Check seed is valid
			PrivateKeyAccount mintingAccount = new PrivateKeyAccount(repository, seed);

			// Qortal: account must derive to known reward-share public key
			RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingAccount.getPublicKey());
			if (rewardShareData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			// Qortal: check reward-share's minting account is still allowed to mint
			Account rewardShareMintingAccount = new Account(repository, rewardShareData.getMinter());
			if (!rewardShareMintingAccount.canMint(false))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.CANNOT_MINT);

			MintingAccountData mintingAccountData = new MintingAccountData(mintingAccount.getPrivateKey(), mintingAccount.getPublicKey());

			repository.getAccountRepository().save(mintingAccountData);
			repository.saveChanges();
			repository.exportNodeLocalData();//after adding new minting account let's persist it to the backup  MintingAccounts.json 
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		return "true";
	}

	@DELETE
	@Path("/mintingaccounts")
	@Operation(
		summary = "Remove account/reward-share from use by BlockMinter, using public or private key",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "public or private key"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String deleteMintingAccount(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String key58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] key = Base58.decode(key58.trim());

			if (repository.getAccountRepository().delete(key) == 0)
				return "false";

			repository.saveChanges();
			repository.exportNodeLocalData();//after removing new minting account let's persist it to the backup  MintingAccounts.json 
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		return "true";
	}

	@GET
	@Path("/logs")
	@Operation(
		summary = "Return logs entries",
		description = "Limit pegged to 500 max",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	public String fetchLogs(@Parameter(
			ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				name = "tail",
				description = "Fetch most recent log lines",
				schema = @Schema(type = "boolean")
			) @QueryParam("tail") Boolean tail, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		LoggerContext loggerContext = (LoggerContext) LogManager.getContext();
		RollingFileAppender fileAppender = (RollingFileAppender) loggerContext.getConfiguration().getAppenders().values().stream().filter(appender -> appender instanceof RollingFileAppender).findFirst().get();

		String filename = fileAppender.getManager().getFileName();
		java.nio.file.Path logPath = Paths.get(filename);

		try {
			List<String> logLines = Files.readAllLines(logPath);

			// Slicing
			if (reverse != null && reverse)
				logLines = Lists.reverse(logLines);

			// Tail mode - return the last X lines (where X = limit)
			if (tail != null && tail) {
				if (limit != null && limit > 0) {
					offset = logLines.size() - limit;
				}
			}

			// offset out of bounds?
			if (offset != null && (offset < 0 || offset >= logLines.size()))
				return "";

			if (offset != null) {
				offset = Math.min(offset, logLines.size() - 1);
				logLines.subList(0, offset).clear();
			}

			// invalid limit
			if (limit != null && limit <= 0)
				return "";

			if (limit != null)
				limit = Math.min(limit, MAX_LOG_LINES);
			else
				limit = MAX_LOG_LINES;

			limit = Math.min(limit, logLines.size());

			logLines.subList(limit, logLines.size()).clear();

			return String.join("\n", logLines);
		} catch (IOException e) {
			return "";
		}
	}

	@POST
	@Path("/orphan")
	@Operation(
		summary = "Discard blocks back to given height.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "0"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_HEIGHT, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String orphan(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String targetHeightString) {
		Security.checkApiCallAllowed(request);

		try {
			int targetHeight = Integer.parseUnsignedInt(targetHeightString);

			if (targetHeight <= 0 || targetHeight > Controller.getInstance().getChainHeight())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_HEIGHT);

			// Make sure we're not orphaning as far back as the archived blocks
			// FUTURE: we could support this by first importing earlier blocks from the archive
			if (Settings.getInstance().isTopOnly() ||
				Settings.getInstance().isArchiveEnabled()) {

				try (final Repository repository = RepositoryManager.getRepository()) {
					// Find the first unarchived block
					int oldestBlock = repository.getBlockArchiveRepository().getBlockArchiveHeight();
					// Add some extra blocks just in case we're currently archiving/pruning
					oldestBlock += 100;
					if (targetHeight <= oldestBlock) {
						LOGGER.info("Unable to orphan beyond block {} because it is archived", oldestBlock);
						throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_HEIGHT);
					}
				}
			}

			if (BlockChain.orphan(targetHeight))
				return "true";
			else
				return "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_HEIGHT);
		}
	}

	@POST
	@Path("/forcesync")
	@Operation(
		summary = "Forcibly synchronize to given peer.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "node2.qortal.org"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String forceSync(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String targetPeerAddress) {
		Security.checkApiCallAllowed(request);

		try {
			// Try to resolve passed address to make things easier
			//PeerAddress peerAddress = PeerAddress.fromString(targetPeerAddress);
            PeerAddress peerAddress = PeerAddressFactory.create("address", targetPeerAddress);
			InetSocketAddress resolvedAddress = peerAddress.toSocketAddress();

			List<Peer> peers = Network.getInstance().getImmutableHandshakedPeers();
			Peer targetPeer = peers.stream().filter(peer -> peer.getResolvedAddress().equals(resolvedAddress)).findFirst().orElse(null);

			if (targetPeer == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			// Try to grab blockchain lock
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
			if (!blockchainLock.tryLock(30000, TimeUnit.MILLISECONDS))
				return SynchronizationResult.NO_BLOCKCHAIN_LOCK.name();

			SynchronizationResult syncResult;
			try {
				do {
					syncResult = Synchronizer.getInstance().actuallySynchronize(targetPeer, true);
				} while (syncResult == SynchronizationResult.OK);
			} finally {
				blockchainLock.unlock();
			}

			return syncResult.name();
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (UnknownHostException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (InterruptedException e) {
			return SynchronizationResult.NO_BLOCKCHAIN_LOCK.name();
		} catch (ReflectiveOperationException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS, e);
        }
	}

	@GET
	@Path("/repository/data")
	@Operation(
		summary = "Export sensitive/node-local data from repository.",
		description = "Exports data to .json files on local machine"
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String exportRepository(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			repository.exportNodeLocalData();
			return "true";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/repository/data")
	@Operation(
		summary = "Import data into repository.",
		description = "Imports data from file on local machine. Filename is forced to 'qortal-backup/TradeBotStates.json' if apiKey is not set.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "qortal-backup/TradeBotStates.json"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String importRepository(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String filename) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				repository.importDataFromFile(filename);
				repository.saveChanges();

				return "true";

			} catch (IOException e) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA, e);

			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException e) {
			// We couldn't lock blockchain to perform import
			return "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/repository/checkpoint")
	@Operation(
		summary = "Checkpoint data in repository.",
		description = "Forces repository to checkpoint uncommitted writes.",
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String checkpointRepository(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		RepositoryManager.setRequestedCheckpoint(Boolean.TRUE);

		return "true";
	}

	@POST
	@Path("/repository/backup")
	@Operation(
		summary = "Perform online backup of repository.",
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String backupRepository(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				// Timeout if the database isn't ready for backing up after 60 seconds
				long timeout = 60 * 1000L;
				repository.backup(true, "backup", timeout);
				repository.saveChanges();

				return "true";
			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException | TimeoutException e) {
			// We couldn't lock blockchain to perform backup
			return "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/repository/archive/rebuild")
	@Operation(
			summary = "Rebuild archive",
			description = "Rebuilds archive files, using the specified serialization version",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "number", example = "2"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "\"true\"",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String rebuildArchive(@HeaderParam(Security.API_KEY_HEADER) String apiKey, Integer serializationVersion) {
		Security.checkApiCallAllowed(request);

		// Default serialization version to value specified in settings
		if (serializationVersion == null) {
			serializationVersion = Settings.getInstance().getDefaultArchiveVersion();
		}

		try {
			// We don't actually need to lock the blockchain here, but we'll do it anyway so that
			// the node can focus on rebuilding rather than synchronizing / minting.
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				BlockArchiveRebuilder blockArchiveRebuilder = new BlockArchiveRebuilder(serializationVersion);
				blockArchiveRebuilder.start();

				return "true";

			} catch (IOException e) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA, e);

			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException e) {
			// We couldn't lock blockchain to perform rebuild
			return "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/repository/reindex")
	@Operation(
			summary = "Reindex repository",
			description = "Rebuilds all transactions and balances from archived blocks. Warning: takes around 1 week, and the core will not function normally during this time. If 'false' is returned, the database may be left in an inconsistent state, requiring another reindex or a bootstrap to correct it.",
			responses = {
					@ApiResponse(
							description = "\"true\"",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE, ApiError.BLOCKCHAIN_NEEDS_SYNC})
	@SecurityRequirement(name = "apiKey")
	public String reindex(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		if (Synchronizer.getInstance().isSynchronizing())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

		try {
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				ReindexManager reindexManager = new ReindexManager();
				reindexManager.reindex();
				return "true";

			} catch (DataException e) {
				LOGGER.info("DataException when reindexing: {}", e.getMessage());

			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException e) {
			// We couldn't lock blockchain to perform reindex
			return "false";
		}

		return "false";
	}

	@DELETE
	@Path("/repository")
	@Operation(
		summary = "Perform maintenance on repository.",
		description = "Requires enough free space to rebuild repository. This will pause your node for a while."
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public void performRepositoryMaintenance(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				// Timeout if the database isn't ready to start after 60 seconds
				long timeout = 60 * 1000L;
				repository.performPeriodicMaintenance(timeout);
			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException e) {
			// No big deal
		} catch (DataException | TimeoutException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/repository/importarchivedtrades")
	@Operation(
			summary = "Imports archived trades from TradeBotStatesArchive.json",
			description = "This can be used to recover trades that exist in the archive only, which may be needed if a<br />" +
					"problem occurred during the proof-of-work computation stage of a buy request.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public boolean importArchivedTrades(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();

			blockchainLock.lockInterruptibly();

			try {
				repository.importDataFromFile("qortal-backup/TradeBotStatesArchive.json");
				repository.saveChanges();

				return true;

			} catch (IOException e) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA, e);

			} finally {
				blockchainLock.unlock();
			}
		} catch (InterruptedException e) {
			// We couldn't lock blockchain to perform import
			return false;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/apikey/generate")
	@Operation(
			summary = "Generate an API key",
			description = "This request is unauthenticated if no API key has been generated yet. " +
					"If an API key already exists, it needs to be passed as a header and this endpoint " +
					"will then generate a new key which replaces the existing one.",
			responses = {
					@ApiResponse(
							description = "API key string",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String generateApiKey(@HeaderParam(Security.API_KEY_HEADER) String apiKeyHeader) {
		ApiKey apiKey = Security.getApiKey(request);

		// If the API key is already generated, we need to authenticate this request
		if (apiKey.generated() && apiKey.exists()) {
			Security.checkApiCallAllowed(request);
		}

		// Not generated yet - so we are safe to generate one
		// FUTURE: we may want to restrict this to local/loopback only?

		try {
			apiKey.generate();
		} catch (IOException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "Unable to generate API key");
		}

		return apiKey.toString();
	}

	@GET
	@Path("/apikey/test")
	@Operation(
			summary = "Test an API key",
			responses = {
					@ApiResponse(
							description = "true if authenticated",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String testApiKey(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		return "true";
	}

	@GET
	@Path("/systeminfo")
	@Operation(
			summary = "System Information",
			description = "System memory usage and available processors.",
			responses = {
					@ApiResponse(
							description = "memory usage and available processors",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SystemInfo.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public SystemInfo getSystemInformation() {

		SystemInfo info
			= new SystemInfo(
				Runtime.getRuntime().freeMemory(),
				Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
				Runtime.getRuntime().totalMemory(),
				Runtime.getRuntime().maxMemory(),
				Runtime.getRuntime().availableProcessors());

		return info;
	}
}
