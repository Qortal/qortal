package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.controller.arbitrary.ArbitraryDataFileManager;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

@Path("/data")
@Tag(name = "Data")
public class DataResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/cache/size")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(
		summary = "Get relay cache size",
		description = "Returns the total size of the relay cache in bytes",
		responses = {
			@ApiResponse(
				description = "Cache size in bytes",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "number"
					)
				)
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED})
	public long getCacheSize(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);
		
		ArbitraryDataFileManager manager = ArbitraryDataFileManager.getInstance();
		return manager.getRelayCacheSize();
	}

	@POST
	@Path("/cache/erase")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(
		summary = "Erase relay cache",
		description = "Deletes all files from the relay cache",
		responses = {
			@ApiResponse(
				description = "true if successful, false otherwise",
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
	@ApiErrors({ApiError.UNAUTHORIZED})
	public boolean eraseCache(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);
		
		ArbitraryDataFileManager manager = ArbitraryDataFileManager.getInstance();
		return manager.eraseRelayCache();
	}

}

