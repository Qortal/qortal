package org.qortal.api.resource;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.integration.api.OpenApiReader; // Added for Swagger 2.1.13
import io.swagger.v3.jaxrs2.ReaderListener;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrorMessage;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiService;
import org.qortal.globalization.Translator;

import javax.ws.rs.Path;
import java.lang.reflect.Method;
import java.util.Locale;

public class AnnotationPostProcessor implements ReaderListener {

	private static final Logger LOGGER = LogManager.getLogger(AnnotationPostProcessor.class);

	@Override  // Updated for Swagger v2.1.13 
	public void beforeScan(OpenApiReader reader, OpenAPI openAPI) {
	}

	@Override // Updated for Swagger v2.1.13
	public void afterScan(OpenApiReader reader, OpenAPI openAPI) {
		// Populate Components section with reusable parameters, like "limit" and "offset"
		// We take the reusable parameters from AdminResource.globalParameters path "/admin/unused"
		Components components = openAPI.getComponents();

		PathItem globalParametersPathItem = openAPI.getPaths().get("/admin/unused");

		if (globalParametersPathItem != null) {
			for (Parameter parameter : globalParametersPathItem.getGet().getParameters())
				components.addParameters(parameter.getName(), parameter);

			openAPI.getPaths().remove("/admin/unused");
		}

		// Search all ApiService resources (classes) for @ApiErrors annotations
		// to generate corresponding openAPI operation responses.
		for (Class<?> clazz : ApiService.getInstance().getResources()) {
			Path classPath = clazz.getAnnotation(Path.class);
			if (classPath == null)
				continue;

			String classPathString = classPath.value();
			if (classPathString.charAt(0) != '/')
				classPathString = "/" + classPathString;

			for (Method method : clazz.getDeclaredMethods()) {
				ApiErrors apiErrors = method.getAnnotation(ApiErrors.class);
				if (apiErrors == null)
					continue;

				LOGGER.trace(() -> String.format("Found @ApiErrors annotation on %s.%s", clazz.getSimpleName(), method.getName()));
				PathItem pathItem = getPathItemFromMethod(openAPI, classPathString, method);

				if (pathItem == null) {
					LOGGER.error(String.format("Couldn't get PathItem for %s", clazz.getSimpleName() + "." + method.getName()));
					LOGGER.error(String.format("Known paths: %s", String.join(", ", openAPI.getPaths().keySet())));
					continue;
				}

				for (Operation operation : pathItem.readOperations())
					for (ApiError apiError : apiErrors.value())
						addApiErrorResponse(operation, apiError);
			}
		}
	}

	private PathItem getPathItemFromMethod(OpenAPI openAPI, String classPathString, Method method) {
		Path path = method.getAnnotation(Path.class);
		if (path == null)
			return openAPI.getPaths().get(classPathString);

		String pathString = path.value();

		if (pathString.equals("/"))
			pathString = "";

		return openAPI.getPaths().get(classPathString + pathString);
	}

	private void addApiErrorResponse(Operation operation, ApiError apiError) {
		String statusCode = Integer.toString(apiError.getStatus()) + " " + apiError.name();

		// Create response for this HTTP response code if it doesn't already exist
		ApiResponse apiResponse = operation.getResponses().get(statusCode);
		if (apiResponse == null) {
			Schema<?> errorMessageSchema = ModelConverters.getInstance().readAllAsResolvedSchema(ApiErrorMessage.class).schema;
			MediaType mediaType = new MediaType().schema(errorMessageSchema);
			Content content = new Content().addMediaType(javax.ws.rs.core.MediaType.APPLICATION_JSON, mediaType);
			apiResponse = new ApiResponse().content(content);
			operation.getResponses().addApiResponse(statusCode, apiResponse);
		}

		// Add this specific ApiError code as an example
		int apiErrorCode = apiError.getCode();
		String lang = Locale.getDefault().getLanguage();
		ApiErrorMessage apiErrorMessage = new ApiErrorMessage(apiErrorCode, Translator.INSTANCE.translate("ApiError", lang, apiError.name()));
		Example example = new Example().value(apiErrorMessage);

		// XXX: addExamples(..) is not working in Swagger 2.0.4. This bug is referenced in https://github.com/swagger-api/swagger-ui/issues/2651
		// Replace the call to .setExample(..) by .addExamples(..) when the bug is fixed.
		apiResponse.getContent().get(javax.ws.rs.core.MediaType.APPLICATION_JSON).setExample(example);
		// apiResponse.getContent().get(javax.ws.rs.core.MediaType.APPLICATION_JSON).addExamples(Integer.toString(apiErrorCode), example);
	}

}
