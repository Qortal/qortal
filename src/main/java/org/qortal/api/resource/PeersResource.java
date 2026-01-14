package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.qortal.api.*;
import org.qortal.api.model.ConnectedPeer;
import org.qortal.api.model.PeersSummary;
import org.qortal.controller.Controller;
import org.qortal.controller.Synchronizer;
import org.qortal.controller.Synchronizer.SynchronizationResult;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.network.PeerData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.ExecuteProduceConsume;
import org.qortal.utils.NTP;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Path("/peers")
@Tag(name = "Peers")
public class PeersResource {

	@Context
	HttpServletRequest request;

	@GET
	@Operation(
		summary = "Fetch list of connected peers",
		responses = {
			@ApiResponse(
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(
						schema = @Schema(
							implementation = ConnectedPeer.class
						)
					)
				)
			)
		}
	)
	public List<ConnectedPeer> getPeers() {
		return Network.getInstance().getImmutableConnectedPeers().stream().map(ConnectedPeer::new).collect(Collectors.toList());
	}

	@GET
	@Path("/known")
	@Operation(
		summary = "Fetch list of all known peers",
		responses = {
			@ApiResponse(
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(
						schema = @Schema(
							implementation = PeerData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.REPOSITORY_ISSUE
	})
	public List<PeerData> getKnownPeers() {
		return Network.getInstance().getAllKnownPeers();
	}

	@GET
	@Path("/self")
	@Operation(
		summary = "Fetch list of peers that connect to self",
		responses = {
			@ApiResponse(
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(
						schema = @Schema(
							implementation = PeerAddress.class
						)
					)
				)
			)
		}
	)
	public List<PeerAddress> getSelfPeers() {
		return Network.getInstance().getSelfPeers();
	}

	@GET
	@Path("/enginestats")
	@Operation(
		summary = "Fetch statistics snapshot for networking engine",
		responses = {
			@ApiResponse(
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(
						schema = @Schema(
							implementation = ExecuteProduceConsume.StatsSnapshot.class
						)
					)
				)
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public ExecuteProduceConsume.StatsSnapshot getEngineStats(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @QueryParam("newLoggingLevel") Level newLoggingLevel) {
		Security.checkApiCallAllowed(request);

		if (newLoggingLevel != null) {
			final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			final Configuration config = ctx.getConfiguration();

			String epcClassName = "org.qortal.network.Network.NetworkProcessor";
			LoggerConfig loggerConfig = config.getLoggerConfig(epcClassName);
			LoggerConfig specificConfig = loggerConfig;

			// We need a specific configuration for this logger,
			// otherwise we would change the level of all other loggers
			// having the original configuration as parent as well
			if (!loggerConfig.getName().equals(epcClassName)) {
				specificConfig = new LoggerConfig(epcClassName, newLoggingLevel, true);
				specificConfig.setParent(loggerConfig);
				config.addLogger(epcClassName, specificConfig);
			}
			specificConfig.setLevel(newLoggingLevel);
			ctx.updateLoggers();
		}

		return Network.getInstance().getStatsSnapshot();
	}

	@POST
	@Operation(
		summary = "Add new peer address",
		description = "Specify a new peer using hostname, IPv4 address, IPv6 address and optional port number preceded with colon (e.g. :9084)<br>"
				+ "Note that IPv6 literal addresses must be surrounded with brackets.<br>" + "Examples:<br><ul>" + "<li>some-peer.example.com</li>"
				+ "<li>some-peer.example.com:9084</li>" + "<li>10.1.2.3</li>" + "<li>10.1.2.3:9084</li>" + "<li>[2001:d8b::1]</li>"
				+ "<li>[2001:d8b::1]:9084</li>" + "</ul>",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					example = "some-peer.example.com"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "true if accepted",
				content = @Content(
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_NETWORK_ADDRESS, ApiError.REPOSITORY_ISSUE
	})
	@SecurityRequirement(name = "apiKey")
	public String addPeer(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String address) {
		Security.checkApiCallAllowed(request);

		final Long addedWhen = NTP.getTime();
		if (addedWhen == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NO_TIME_SYNC);

		try {
			PeerAddress peerAddress = PeerAddress.fromString(address);

			List<PeerAddress> newPeerAddresses = new ArrayList<>(1);
			newPeerAddresses.add(peerAddress);

			boolean addResult = Network.getInstance().mergePeers("API", addedWhen, newPeerAddresses);

			return addResult ? "true" : "false";
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_NETWORK_ADDRESS);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@DELETE
	@Operation(
		summary = "Remove peer address from database",
		description = "Specify peer to be removed using hostname, IPv4 address, IPv6 address and optional port number preceded with colon (e.g. :9084)<br>"
				+ "Note that IPv6 literal addresses must be surrounded with brackets.<br>" + "Examples:<br><ul>" + "<li>some-peer.example.com</li>"
				+ "<li>some-peer.example.com:9084</li>" + "<li>10.1.2.3</li>" + "<li>10.1.2.3:9084</li>" + "<li>[2001:d8b::1]</li>"
				+ "<li>[2001:d8b::1]:9084</li>" + "</ul>",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					example = "some-peer.example.com"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "true if removed, false if not found",
				content = @Content(
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_NETWORK_ADDRESS, ApiError.REPOSITORY_ISSUE
	})
	@SecurityRequirement(name = "apiKey")
	public String removePeer(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String address) {
		Security.checkApiCallAllowed(request);

		try {
			PeerAddress peerAddress = PeerAddress.fromString(address);

			boolean wasKnown = Network.getInstance().forgetPeer(peerAddress);
			return wasKnown ? "true" : "false";
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_NETWORK_ADDRESS);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@DELETE
	@Path("/known")
	@Operation(
		summary = "Remove all known peers from database",
		responses = {
			@ApiResponse(
				description = "true if any peers were removed, false if there were no peers to delete",
				content = @Content(
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.REPOSITORY_ISSUE
	})
	@SecurityRequirement(name = "apiKey")
	public String removeKnownPeers(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String address) {
		Security.checkApiCallAllowed(request);

		try {
			int numDeleted = Network.getInstance().forgetAllPeers();

			return numDeleted != 0 ? "true" : "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/commonblock")
	@Operation(
		summary = "Report common block with given peer.",
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
				description = "the block",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = BlockSummaryData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<BlockSummaryData> commonBlock(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String targetPeerAddress) {
		Security.checkApiCallAllowed(request);

		try {
			// Try to resolve passed address to make things easier
			PeerAddress peerAddress = PeerAddress.fromString(targetPeerAddress);
			InetSocketAddress resolvedAddress = peerAddress.toSocketAddress();

			List<Peer> peers = Network.getInstance().getImmutableHandshakedPeers();
			Peer targetPeer = peers.stream().filter(peer -> peer.getResolvedAddress().equals(resolvedAddress)).findFirst().orElse(null);

			if (targetPeer == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			try (final Repository repository = RepositoryManager.getRepository()) {
				int ourInitialHeight = Controller.getInstance().getChainHeight();
				boolean force = true;
				List<BlockSummaryData> peerBlockSummaries = new ArrayList<>();

				SynchronizationResult findCommonBlockResult = Synchronizer.getInstance().fetchSummariesFromCommonBlock(repository, targetPeer, ourInitialHeight, force, peerBlockSummaries, true);
				if (findCommonBlockResult != SynchronizationResult.OK)
					return null;

				return peerBlockSummaries;
			}
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (UnknownHostException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (InterruptedException e) {
			return null;
		}
	}

	@GET
	@Path("/summary")
	@Operation(
			summary = "Returns total inbound and outbound connections for connected peers",
			responses = {
					@ApiResponse(
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(
											schema = @Schema(
													implementation = PeersSummary.class
											)
									)
							)
					)
			}
	)
	public PeersSummary peersSummary() {
		PeersSummary peersSummary = new PeersSummary();

		List<Peer> connectedPeers = Network.getInstance().getImmutableConnectedPeers().stream().collect(Collectors.toList());
		for (Peer peer : connectedPeers) {
			if (!peer.isOutbound()) {
				peersSummary.inboundConnections++;
			}
			else {
				peersSummary.outboundConnections++;
			}
		}
		return peersSummary;
	}

}
