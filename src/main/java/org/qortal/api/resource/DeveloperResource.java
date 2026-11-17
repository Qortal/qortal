package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
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
import org.qortal.controller.DevProxyManager;
import org.qortal.repository.DataException;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;


@Path("/developer")
@Tag(name = "Developer Tools")
public class DeveloperResource {

	@Context HttpServletRequest request;
	@Context HttpServletResponse response;
	@Context ServletContext context;


	@POST
	@Path("/proxy/start")
	@Operation(
			summary = "Start proxy server, for real time QDN app/website development",
			requestBody = @RequestBody(
					description = "Host and port of source webserver to be proxied",
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									example = "127.0.0.1:5173"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "Port number of running server",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "number"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA})
	@SecurityRequirement(name = "apiKey")
	public Integer startProxy(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String sourceHostAndPort) {
		Security.checkApiCallAllowed(request);
		DevProxyManager devProxyManager = DevProxyManager.getInstance();
		try {
			devProxyManager.setSourceHostAndPort(sourceHostAndPort);
			devProxyManager.start();
			return devProxyManager.getPort();

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
		}
	}

	@POST
	@Path("/proxy/stop")
	@Operation(
			summary = "Stop proxy server",
			responses = {
					@ApiResponse(
							description = "true if stopped",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "boolean"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public boolean stopProxy(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);
		DevProxyManager devProxyManager = DevProxyManager.getInstance();
		devProxyManager.stop();
		return !devProxyManager.isRunning();
	}

}