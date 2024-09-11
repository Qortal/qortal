package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
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
import org.qortal.api.model.ListRequest;
import org.qortal.list.ResourceListManager;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.List;
import org.json.JSONArray;


@Path("/lists")
@Tag(name = "Lists")
public class ListsResource {

	@Context
	HttpServletRequest request;

	@GET
	@Operation(
		summary = "Fetch all lists",
		responses = {
            @ApiResponse(
                description = "A JSON array of list names",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = String.class)))
            )
        }
	)
	@SecurityRequirement(name = "apiKey")
	public String getAllLists(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
    	Security.checkApiCallAllowed(request);
    	List<String> listNames = ResourceListManager.getInstance().getAllListNames();
    	return new JSONArray(listNames).toString();
	}

	@POST
	@Path("/{listName}")
	@Operation(
			summary = "Add items to a new or existing list",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ListRequest.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "Returns true if all items were processed, false if any couldn't be " +
									"processed, or an exception on failure. If false or an exception is returned, " +
									"the list will not be updated, and the request will need to be re-issued.",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String addItemstoList(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
								 @PathParam("listName") String listName,
								 ListRequest listRequest) {
		Security.checkApiCallAllowed(request);

		if (listName == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		if (listRequest == null || listRequest.items == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		int successCount = 0;
		int errorCount = 0;

		for (String item : listRequest.items) {

			boolean success = ResourceListManager.getInstance().addToList(listName, item, false);
			if (success) {
				successCount++;
			}
			else {
				errorCount++;
			}
		}

		if (successCount > 0 && errorCount == 0) {
			// All were successful, so save the list
			ResourceListManager.getInstance().saveList(listName);
			return "true";
		}
		else {
			// Something went wrong, so revert
			ResourceListManager.getInstance().revertList(listName);
			return "false";
		}
	}

	@DELETE
	@Path("/{listName}")
	@Operation(
			summary = "Remove one or more items from a list",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ListRequest.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "Returns true if all items were processed, false if any couldn't be " +
									"processed, or an exception on failure. If false or an exception is returned, " +
									"the list will not be updated, and the request will need to be re-issued.",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String removeItemsFromList(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
									  @PathParam("listName") String listName,
									  ListRequest listRequest) {
		Security.checkApiCallAllowed(request);

		if (listRequest == null || listRequest.items == null) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		int successCount = 0;
		int errorCount = 0;

		for (String address : listRequest.items) {

			// Attempt to remove the item
			// Don't save as we will do this at the end of the process
			boolean success = ResourceListManager.getInstance().removeFromList(listName, address, false);
			if (success) {
				successCount++;
			}
			else {
				errorCount++;
			}
		}

		if (successCount > 0 && errorCount == 0) {
			// All were successful, so save the list
			ResourceListManager.getInstance().saveList(listName);
			return "true";
		}
		else {
			// Something went wrong, so revert
			ResourceListManager.getInstance().revertList(listName);
			return "false";
		}
	}

	@GET
	@Path("/{listName}")
	@Operation(
			summary = "Fetch all items in a list",
			responses = {
					@ApiResponse(
							description = "A JSON array of items",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = String.class)))
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String getItemsInList(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("listName") String listName) {
		Security.checkApiCallAllowed(request);
		return ResourceListManager.getInstance().getJSONStringForList(listName);
	}

}
