package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.data.system.DbConnectionInfo;
import org.qortal.repository.RepositoryFactory;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBRepositoryFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.List;

@Path("/admin")
@Tag(name = "Admin")
public class AdminResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/dbpool")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "List database connection pool states",
		description = "Returns per-connection state (thread owner, allocated/available/empty, timestamp). Requires connectionPoolMonitorEnabled=true in settings.",
		responses = {
			@ApiResponse(
				description = "Connection pool states",
				content = @Content(array = @ArraySchema(schema = @Schema(implementation = DbConnectionInfo.class)))
			)
		}
	)
	public List<DbConnectionInfo> dbPool() {
		RepositoryFactory factory = RepositoryManager.getRepositoryFactory();
		if (factory instanceof HSQLDBRepositoryFactory)
			return ((HSQLDBRepositoryFactory) factory).getDbConnectionsStates();
		return Collections.emptyList();
	}

}
