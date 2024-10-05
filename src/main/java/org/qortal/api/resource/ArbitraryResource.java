package org.qortal.api.resource;

import com.google.common.primitives.Bytes;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;
import org.qortal.api.*;
import org.qortal.api.model.FileProperties;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.arbitrary.*;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.Controller;
import org.qortal.controller.arbitrary.ArbitraryDataCacheManager;
import org.qortal.controller.arbitrary.ArbitraryDataRenderManager;
import org.qortal.controller.arbitrary.ArbitraryDataStorageManager;
import org.qortal.controller.arbitrary.ArbitraryMetadataManager;
import org.qortal.data.account.AccountData;
import org.qortal.data.arbitrary.ArbitraryCategoryInfo;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.list.ResourceListManager;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ArbitraryTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.*;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Path("/arbitrary")
@Tag(name = "Arbitrary")
public class ArbitraryResource {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryResource.class);

	@Context HttpServletRequest request;
	@Context HttpServletResponse response;
	@Context ServletContext context;

	@GET
	@Path("/resources")
	@Operation(
			summary = "List arbitrary resources available on chain, optionally filtered by service and identifier",
			description = "- If the identifier parameter is missing or empty, it will return an unfiltered list of all possible identifiers.\n" +
					"- If an identifier is specified, only resources with a matching identifier will be returned.\n" +
					"- If default is set to true, only resources without identifiers will be returned.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryResourceData.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<ArbitraryResourceData> getResources(
			@QueryParam("service") Service service,
			@QueryParam("name") String name,
			@QueryParam("identifier") String identifier,
			@Parameter(description = "Default resources (without identifiers) only") @QueryParam("default") Boolean defaultResource,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse,
			@Parameter(description = "Include followed names only") @QueryParam("followedonly") Boolean followedOnly,
			@Parameter(description = "Exclude blocked content") @QueryParam("excludeblocked") Boolean excludeBlocked,
			@Parameter(description = "Filter names by list") @QueryParam("namefilter") String nameListFilter,
			@Parameter(description = "Include status") @QueryParam("includestatus") Boolean includeStatus,
			@Parameter(description = "Include metadata") @QueryParam("includemetadata") Boolean includeMetadata) {

		try (final Repository repository = RepositoryManager.getRepository()) {

			// Treat empty identifier as null
			if (identifier != null && identifier.isEmpty()) {
				identifier = null;
			}

			// Ensure that "default" and "identifier" parameters cannot coexist
			boolean defaultRes = Boolean.TRUE.equals(defaultResource);
			if (defaultRes && identifier != null) {
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "identifier cannot be specified when requesting a default resource");
			}

			// Set up name filters if supplied
			List<String> names = null;
			if (name != null) {
				// Filter using single name
				names = Arrays.asList(name);
			}
			else if (nameListFilter != null) {
				// Filter using supplied list of names
				names = ResourceListManager.getInstance().getStringsInList(nameListFilter);
				if (names.isEmpty()) {
					// If list is empty (or doesn't exist) we can shortcut with empty response
					return new ArrayList<>();
				}
			}

			List<ArbitraryResourceData> resources = repository.getArbitraryRepository()
					.getArbitraryResources(service, identifier, names, defaultRes, followedOnly, excludeBlocked,
							includeMetadata, includeStatus, limit, offset, reverse);

			if (resources == null) {
				return new ArrayList<>();
			}

			return resources;

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/resources/search")
	@Operation(
			summary = "Search arbitrary resources available on chain, optionally filtered by service.\n" +
					"If default is set to true, only resources without identifiers will be returned.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryResourceData.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<ArbitraryResourceData> searchResources(
			@QueryParam("service") Service service,
			@Parameter(description = "Query (searches name, identifier, title and description fields)") @QueryParam("query") String query,
			@Parameter(description = "Identifier (searches identifier field only)") @QueryParam("identifier") String identifier,
			@Parameter(description = "Name (searches name field only)") @QueryParam("name") List<String> names,
			@Parameter(description = "Title (searches title metadata field only)") @QueryParam("title") String title,
			@Parameter(description = "Description (searches description metadata field only)") @QueryParam("description") String description,
			@Parameter(description = "Prefix only (if true, only the beginning of fields are matched)") @QueryParam("prefix") Boolean prefixOnly,
			@Parameter(description = "Exact match names only (if true, partial name matches are excluded)") @QueryParam("exactmatchnames") Boolean exactMatchNamesOnly,
			@Parameter(description = "Default resources (without identifiers) only") @QueryParam("default") Boolean defaultResource,
			@Parameter(description = "Search mode") @QueryParam("mode") SearchMode mode,
			@Parameter(description = "Min level") @QueryParam("minlevel") Integer minLevel,
			@Parameter(description = "Filter names by list (exact matches only)") @QueryParam("namefilter") String nameListFilter,
			@Parameter(description = "Include followed names only") @QueryParam("followedonly") Boolean followedOnly,
			@Parameter(description = "Exclude blocked content") @QueryParam("excludeblocked") Boolean excludeBlocked,
			@Parameter(description = "Include status") @QueryParam("includestatus") Boolean includeStatus,
			@Parameter(description = "Include metadata") @QueryParam("includemetadata") Boolean includeMetadata,
			@Parameter(description = "Creation date before timestamp") @QueryParam("before") Long before,
			@Parameter(description = "Creation date after timestamp") @QueryParam("after") Long after,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {

		try (final Repository repository = RepositoryManager.getRepository()) {

			boolean defaultRes = Boolean.TRUE.equals(defaultResource);
			boolean usePrefixOnly = Boolean.TRUE.equals(prefixOnly);

			List<String> exactMatchNames = new ArrayList<>();

			if (nameListFilter != null) {
				// Load names from supplied list of names
				exactMatchNames.addAll(ResourceListManager.getInstance().getStringsInList(nameListFilter));

				// If list is empty (or doesn't exist) we can shortcut with empty response
				if (exactMatchNames.isEmpty()) {
					return new ArrayList<>();
				}
			}

			// Move names to exact match list, if requested
			if (exactMatchNamesOnly != null && exactMatchNamesOnly && names != null) {
				exactMatchNames.addAll(names);
				names = null;
			}

			List<ArbitraryResourceData> resources = repository.getArbitraryRepository()
					.searchArbitraryResources(service, query, identifier, names, title, description, usePrefixOnly,
							exactMatchNames, defaultRes, mode, minLevel, followedOnly, excludeBlocked, includeMetadata, includeStatus,
							before, after, limit, offset, reverse);

			if (resources == null) {
				return new ArrayList<>();
			}

			return resources;

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/resources/searchsimple")
	@Operation(
			summary = "Search arbitrary resources available on chain, optionally filtered by service.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryResourceData.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<ArbitraryResourceData> searchResourcesSimple(
			@QueryParam("service") Service service,
			@Parameter(description = "Identifier (searches identifier field only)") @QueryParam("identifier") String identifier,
			@Parameter(description = "Name (searches name field only)") @QueryParam("name") List<String> names,
			@Parameter(description = "Prefix only (if true, only the beginning of fields are matched)") @QueryParam("prefix") Boolean prefixOnly,
			@Parameter(description = "Case insensitive (ignore leter case on search)") @QueryParam("caseInsensitive") Boolean caseInsensitive,
			@Parameter(description = "Creation date before timestamp") @QueryParam("before") Long before,
			@Parameter(description = "Creation date after timestamp") @QueryParam("after") Long after,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {

		try (final Repository repository = RepositoryManager.getRepository()) {

			boolean usePrefixOnly = Boolean.TRUE.equals(prefixOnly);
			boolean ignoreCase = Boolean.TRUE.equals(caseInsensitive);

			List<ArbitraryResourceData> resources = repository.getArbitraryRepository()
					.searchArbitraryResourcesSimple(service, identifier, names, usePrefixOnly,
							before, after, limit, offset, reverse, ignoreCase);

			if (resources == null) {
				return new ArrayList<>();
			}

			return resources;

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/resource/status/{service}/{name}")
	@Operation(
			summary = "Get status of arbitrary resource with supplied service and name",
			description = "If build is set to true, the resource will be built synchronously before returning the status.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryResourceStatus.class))
					)
			}
	)
	public ArbitraryResourceStatus getDefaultResourceStatus(@PathParam("service") Service service,
															@PathParam("name") String name,
															@QueryParam("build") Boolean build) {

		if (!Settings.getInstance().isQDNAuthBypassEnabled())
			Security.requirePriorAuthorizationOrApiKey(request, name, service, null, null);

		return ArbitraryTransactionUtils.getStatus(service, name, null, build, true);
	}

	@GET
	@Path("/resource/properties/{service}/{name}/{identifier}")
	@Operation(
			summary = "Get properties of a QDN resource",
			description = "This attempts a download of the data if it's not available locally. A filename will only be returned for single file resources. mimeType is only returned when it can be determined.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = FileProperties.class))
					)
			}
	)
	public FileProperties getResourceProperties(@PathParam("service") Service service,
											    @PathParam("name") String name,
											    @PathParam("identifier") String identifier) {

		if (!Settings.getInstance().isQDNAuthBypassEnabled())
			Security.requirePriorAuthorizationOrApiKey(request, name, service, identifier, null);

		return this.getFileProperties(service, name, identifier);
	}

	@GET
	@Path("/resource/status/{service}/{name}/{identifier}")
	@Operation(
			summary = "Get status of arbitrary resource with supplied service, name and identifier",
			description = "If build is set to true, the resource will be built synchronously before returning the status.",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryResourceStatus.class))
					)
			}
	)
	public ArbitraryResourceStatus getResourceStatus(@PathParam("service") Service service,
													 @PathParam("name") String name,
													 @PathParam("identifier") String identifier,
													 @QueryParam("build") Boolean build) {

		if (!Settings.getInstance().isQDNAuthBypassEnabled())
			Security.requirePriorAuthorizationOrApiKey(request, name, service, identifier, null);

		return ArbitraryTransactionUtils.getStatus(service, name, identifier, build, true);
	}


	@GET
	@Path("/search")
	@Operation(
		summary = "Find matching arbitrary transactions",
		description = "Returns transactions that match criteria. At least either service or address or limit <= 20 must be provided. Block height ranges allowed when searching CONFIRMED transactions ONLY.",
		responses = {
			@ApiResponse(
				description = "transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE
	})
	public List<TransactionData> searchTransactions(@QueryParam("startBlock") Integer startBlock, @QueryParam("blockLimit") Integer blockLimit,
			@QueryParam("txGroupId") Integer txGroupId,
			@QueryParam("service") Service service,
			@QueryParam("name") String name,
			@QueryParam("address") String address, @Parameter(
				description = "whether to include confirmed, unconfirmed or both",
				required = true
			) @QueryParam("confirmationStatus") ConfirmationStatus confirmationStatus, @Parameter(
				ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		// Must have at least one of txType / address / limit <= 20
		if (service == null && (address == null || address.isEmpty()) && (limit == null || limit > 20))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// You can't ask for unconfirmed and impose a block height range
		if (confirmationStatus != ConfirmationStatus.CONFIRMED && (startBlock != null || blockLimit != null))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		List<TransactionType> txTypes = new ArrayList<>();
		txTypes.add(TransactionType.ARBITRARY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(startBlock, blockLimit, txGroupId, txTypes,
					service, name, address, confirmationStatus, limit, offset, reverse);

			// Expand signatures to transactions
			List<TransactionData> transactions = new ArrayList<>(signatures.size());
			for (byte[] signature : signatures)
				transactions.add(repository.getTransactionRepository().fromSignature(signature));

			return transactions;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Operation(
		summary = "Build raw, unsigned, ARBITRARY transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = ArbitraryTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.INVALID_DATA, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String createArbitrary(ArbitraryTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		if (transactionData.getDataType() == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = ArbitraryTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/relaymode")
	@Operation(
			summary = "Returns whether relay mode is enabled or not",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public boolean getRelayMode(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		return Settings.getInstance().isRelayModeEnabled();
	}

	@GET
	@Path("/categories")
	@Operation(
			summary = "List arbitrary transaction categories",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryCategoryInfo.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<ArbitraryCategoryInfo> getCategories() {
		List<ArbitraryCategoryInfo> categories = new ArrayList<>();
		for (Category category : Category.values()) {
			ArbitraryCategoryInfo arbitraryCategory = new ArbitraryCategoryInfo();
			arbitraryCategory.id = category.toString();
			arbitraryCategory.name = category.getName();
			categories.add(arbitraryCategory);
		}
		return categories;
	}

	@GET
	@Path("/hosted/transactions")
	@Operation(
			summary = "List arbitrary transactions hosted by this node",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryTransactionData.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<ArbitraryTransactionData> getHostedTransactions(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
																@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
																@Parameter(ref = "offset") @QueryParam("offset") Integer offset) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {

			List<ArbitraryTransactionData> hostedTransactions = ArbitraryDataStorageManager.getInstance().listAllHostedTransactions(repository, limit, offset);

			return hostedTransactions;

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/hosted/resources")
	@Operation(
			summary = "List arbitrary resources hosted by this node",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryResourceData.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<ArbitraryResourceData> getHostedResources(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@QueryParam("query") String query) {
		Security.checkApiCallAllowed(request);

		List<ArbitraryResourceData> resources = new ArrayList<>();

		try (final Repository repository = RepositoryManager.getRepository()) {
			
			List<ArbitraryTransactionData> transactionDataList;

			if (query == null || query.isEmpty()) {
				transactionDataList = ArbitraryDataStorageManager.getInstance().listAllHostedTransactions(repository, limit, offset);
			} else {
				transactionDataList = ArbitraryDataStorageManager.getInstance().searchHostedTransactions(repository,query, limit, offset);
			}

			for (ArbitraryTransactionData transactionData : transactionDataList) {
				if (transactionData.getService() == null) {
					continue;
				}
				ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
				arbitraryResourceData.name = transactionData.getName();
				arbitraryResourceData.service = transactionData.getService();
				arbitraryResourceData.identifier = transactionData.getIdentifier();
				if (!resources.contains(arbitraryResourceData)) {
					resources.add(arbitraryResourceData);
				}
			}

			return resources;

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}



	@DELETE
	@Path("/resource/{service}/{name}/{identifier}")
	@Operation(
			summary = "Delete arbitrary resource with supplied service, name and identifier",
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public boolean deleteResource(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
								  @PathParam("service") Service service,
								  @PathParam("name") String name,
								  @PathParam("identifier") String identifier) {

		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ArbitraryDataResource resource = new ArbitraryDataResource(name, ResourceIdType.NAME, service, identifier);
			return resource.delete(repository, false);

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/compute")
	@Operation(
			summary = "Compute nonce for raw, unsigned ARBITRARY transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "raw, unsigned ARBITRARY transaction in base58 encoding",
									example = "raw transaction base58"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.TRANSACTION_INVALID, ApiError.INVALID_DATA, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String computeNonce(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String rawBytes58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rawBytes = Base58.decode(rawBytes58);
			// We're expecting unsigned transaction, so append empty signature prior to decoding
			rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			if (transactionData.getType() != TransactionType.ARBITRARY)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			ArbitraryTransaction arbitraryTransaction = (ArbitraryTransaction) Transaction.fromData(repository, transactionData);

			// Quicker validity check first before we compute nonce
			ValidationResult result = arbitraryTransaction.isValid();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			LOGGER.info("Computing nonce...");
			arbitraryTransaction.computeNonce();

			// Re-check, but ignores signature
			result = arbitraryTransaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			// Strip zeroed signature
			transactionData.setSignature(null);

			byte[] bytes = ArbitraryTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}


	@GET
	@Path("/{service}/{name}")
	@Operation(
			summary = "Fetch raw data from file with supplied service, name, and relative path",
			description = "An optional rebuild boolean can be supplied. If true, any existing cached data will be invalidated.",
			responses = {
					@ApiResponse(
							description = "Path to file structure containing requested data",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	public HttpServletResponse get(@PathParam("service") Service service,
								   @PathParam("name") String name,
								   @QueryParam("filepath") String filepath,
								   @QueryParam("encoding") String encoding,
								   @QueryParam("rebuild") boolean rebuild,
								   @QueryParam("async") boolean async,
								   @QueryParam("attempts") Integer attempts) {

		// Authentication can be bypassed in the settings, for those running public QDN nodes
		if (!Settings.getInstance().isQDNAuthBypassEnabled()) {
			Security.checkApiCallAllowed(request);
		}

		return this.download(service, name, null, filepath, encoding, rebuild, async, attempts);
	}

	@GET
	@Path("/{service}/{name}/{identifier}")
	@Operation(
			summary = "Fetch raw data from file with supplied service, name, identifier, and relative path",
			description = "An optional rebuild boolean can be supplied. If true, any existing cached data will be invalidated.",
			responses = {
					@ApiResponse(
							description = "Path to file structure containing requested data",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	public HttpServletResponse get(@PathParam("service") Service service,
								   @PathParam("name") String name,
								   @PathParam("identifier") String identifier,
								   @QueryParam("filepath") String filepath,
								   @QueryParam("encoding") String encoding,
								   @QueryParam("rebuild") boolean rebuild,
								   @QueryParam("async") boolean async,
								   @QueryParam("attempts") Integer attempts) {

		// Authentication can be bypassed in the settings, for those running public QDN nodes
		if (!Settings.getInstance().isQDNAuthBypassEnabled()) {
			Security.checkApiCallAllowed(request, null);
		}

		return this.download(service, name, identifier, filepath, encoding, rebuild, async, attempts);
	}


	// Metadata

	@GET
	@Path("/metadata/{service}/{name}/{identifier}")
	@Operation(
			summary = "Fetch raw metadata from resource with supplied service, name, identifier, and relative path",
			responses = {
					@ApiResponse(
							description = "Path to file structure containing requested data",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(
											implementation = ArbitraryDataTransactionMetadata.class
									)
							)
					)
			}
	)
	public ArbitraryResourceMetadata getMetadata(@PathParam("service") Service service,
							  					 @PathParam("name") String name,
							  					 @PathParam("identifier") String identifier) {
		ArbitraryDataResource resource = new ArbitraryDataResource(name, ResourceIdType.NAME, service, identifier);

		try {
			ArbitraryDataTransactionMetadata transactionMetadata = ArbitraryMetadataManager.getInstance().fetchMetadata(resource, true);
			if (transactionMetadata != null) {
				ArbitraryResourceMetadata resourceMetadata = ArbitraryResourceMetadata.fromTransactionMetadata(transactionMetadata, true);
				if (resourceMetadata != null) {
					return resourceMetadata;
				}
				else {
					// The metadata file doesn't contain title, description, category, or tags
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FILE_NOT_FOUND);
				}
			}
		} catch (IllegalArgumentException e) {
			// No metadata exists for this resource
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND, e.getMessage());
		}

		throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FILE_NOT_FOUND);
	}



	// Upload data at supplied path

	@POST
	@Path("/{service}/{name}")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on a user-supplied path",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string", example = "/Users/user/Documents/MyDirectoryOrFile"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String post(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
					   @PathParam("service") String serviceString,
					   @PathParam("name") String name,
					   @QueryParam("title") String title,
					   @QueryParam("description") String description,
					   @QueryParam("tags") List<String> tags,
					   @QueryParam("category") Category category,
					   @QueryParam("fee") Long fee,
					   @QueryParam("preview") Boolean preview,
					   String path) {
		Security.checkApiCallAllowed(request);

		if (path == null || path.isEmpty()) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Path not supplied");
		}

		return this.upload(Service.valueOf(serviceString), name, null, path, null, null, false,
				fee, null, title, description, tags, category, preview);
	}

	@POST
	@Path("/{service}/{name}/{identifier}")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on a user-supplied path",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string", example = "/Users/user/Documents/MyDirectoryOrFile"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String post(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
					   @PathParam("service") String serviceString,
					   @PathParam("name") String name,
					   @PathParam("identifier") String identifier,
					   @QueryParam("title") String title,
					   @QueryParam("description") String description,
					   @QueryParam("tags") List<String> tags,
					   @QueryParam("category") Category category,
					   @QueryParam("fee") Long fee,
					   @QueryParam("preview") Boolean preview,
					   String path) {
		Security.checkApiCallAllowed(request);

		if (path == null || path.isEmpty()) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Path not supplied");
		}

		return this.upload(Service.valueOf(serviceString), name, identifier, path, null, null, false,
				fee, null, title, description, tags, category, preview);
	}



	// Upload base64-encoded data

	@POST
	@Path("/{service}/{name}/base64")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on user-supplied base64 encoded data",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_OCTET_STREAM,
							schema = @Schema(type = "string", format = "byte")
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String postBase64EncodedData(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
										@PathParam("service") String serviceString,
										@PathParam("name") String name,
										@QueryParam("title") String title,
										@QueryParam("description") String description,
										@QueryParam("tags") List<String> tags,
										@QueryParam("category") Category category,
										@QueryParam("filename") String filename,
										@QueryParam("fee") Long fee,
										@QueryParam("preview") Boolean preview,
										String base64) {
		Security.checkApiCallAllowed(request);

		if (base64 == null) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Data not supplied");
		}

		return this.upload(Service.valueOf(serviceString), name, null, null, null, base64, false,
				fee, filename, title, description, tags, category, preview);
	}

	@POST
	@Path("/{service}/{name}/{identifier}/base64")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on user supplied base64 encoded data",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_OCTET_STREAM,
							schema = @Schema(type = "string", format = "byte")
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String postBase64EncodedData(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
										@PathParam("service") String serviceString,
										@PathParam("name") String name,
										@PathParam("identifier") String identifier,
										@QueryParam("title") String title,
										@QueryParam("description") String description,
										@QueryParam("tags") List<String> tags,
										@QueryParam("category") Category category,
										@QueryParam("filename") String filename,
										@QueryParam("fee") Long fee,
										@QueryParam("preview") Boolean preview,
										String base64) {
		Security.checkApiCallAllowed(request);

		if (base64 == null) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Data not supplied");
		}

		return this.upload(Service.valueOf(serviceString), name, identifier, null, null, base64, false,
				fee, filename, title, description, tags, category, preview);
	}


	// Upload zipped data

	@POST
	@Path("/{service}/{name}/zip")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on user-supplied zip file, encoded as base64",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_OCTET_STREAM,
							schema = @Schema(type = "string", format = "byte")
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String postZippedData(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
								 @PathParam("service") String serviceString,
								 @PathParam("name") String name,
								 @QueryParam("title") String title,
								 @QueryParam("description") String description,
								 @QueryParam("tags") List<String> tags,
								 @QueryParam("category") Category category,
								 @QueryParam("fee") Long fee,
								 @QueryParam("preview") Boolean preview,
								 String base64Zip) {
		Security.checkApiCallAllowed(request);

		if (base64Zip == null) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Data not supplied");
		}

		return this.upload(Service.valueOf(serviceString), name, null, null, null, base64Zip, true,
				fee, null, title, description, tags, category, preview);
	}

	@POST
	@Path("/{service}/{name}/{identifier}/zip")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on user supplied zip file, encoded as base64",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_OCTET_STREAM,
							schema = @Schema(type = "string", format = "byte")
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String postZippedData(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
								 @PathParam("service") String serviceString,
								 @PathParam("name") String name,
								 @PathParam("identifier") String identifier,
								 @QueryParam("title") String title,
								 @QueryParam("description") String description,
								 @QueryParam("tags") List<String> tags,
								 @QueryParam("category") Category category,
								 @QueryParam("fee") Long fee,
								 @QueryParam("preview") Boolean preview,
								 String base64Zip) {
		Security.checkApiCallAllowed(request);

		if (base64Zip == null) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Data not supplied");
		}

		return this.upload(Service.valueOf(serviceString), name, identifier, null, null, base64Zip, true,
				fee, null, title, description, tags, category, preview);
	}



	// Upload plain-text data in string form

	@POST
	@Path("/{service}/{name}/string")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on a user-supplied string",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string", example = "{\"title\":\"\", \"description\":\"\", \"tags\":[]}"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String postString(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
							 @PathParam("service") String serviceString,
							 @PathParam("name") String name,
							 @QueryParam("title") String title,
							 @QueryParam("description") String description,
							 @QueryParam("tags") List<String> tags,
							 @QueryParam("category") Category category,
							 @QueryParam("filename") String filename,
							 @QueryParam("fee") Long fee,
							 @QueryParam("preview") Boolean preview,
							 String string) {
		Security.checkApiCallAllowed(request);

		if (string == null || string.isEmpty()) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Data string not supplied");
		}

		return this.upload(Service.valueOf(serviceString), name, null, null, string, null, false,
				fee, filename, title, description, tags, category, preview);
	}

	@POST
	@Path("/{service}/{name}/{identifier}/string")
	@Operation(
			summary = "Build raw, unsigned, ARBITRARY transaction, based on user supplied string",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string", example = "{\"title\":\"\", \"description\":\"\", \"tags\":[]}"
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String postString(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
							 @PathParam("service") String serviceString,
							 @PathParam("name") String name,
							 @PathParam("identifier") String identifier,
							 @QueryParam("title") String title,
							 @QueryParam("description") String description,
							 @QueryParam("tags") List<String> tags,
							 @QueryParam("category") Category category,
							 @QueryParam("filename") String filename,
							 @QueryParam("fee") Long fee,
							 @QueryParam("preview") Boolean preview,
							 String string) {
		Security.checkApiCallAllowed(request);

		if (string == null || string.isEmpty()) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Data string not supplied");
		}

		return this.upload(Service.valueOf(serviceString), name, identifier, null, string, null, false,
				fee, filename, title, description, tags, category, preview);
	}


	@POST
	@Path("/resources/cache/rebuild")
	@Operation(
			summary = "Rebuild arbitrary resources cache from transactions",
			responses = {
					@ApiResponse(
							description = "true on success",
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
	public String rebuildCache(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ArbitraryDataCacheManager.getInstance().buildArbitraryResourcesCache(repository, true);

			return "true";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}


	// Shared methods

	private String preview(String directoryPath, Service service) {
		Security.checkApiCallAllowed(request);
		ArbitraryTransactionData.Method method = ArbitraryTransactionData.Method.PUT;
		ArbitraryTransactionData.Compression compression = ArbitraryTransactionData.Compression.ZIP;

		ArbitraryDataWriter arbitraryDataWriter = new ArbitraryDataWriter(Paths.get(directoryPath),
				null, service, null, method, compression,
				null, null, null, null);
		try {
			arbitraryDataWriter.save();
		} catch (IOException | DataException | InterruptedException | MissingDataException e) {
			LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		} catch (RuntimeException e) {
			LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
		}

		ArbitraryDataFile arbitraryDataFile = arbitraryDataWriter.getArbitraryDataFile();
		if (arbitraryDataFile != null) {
			String digest58 = arbitraryDataFile.digest58();
			if (digest58 != null) {
				// Pre-authorize resource
				ArbitraryDataResource resource = new ArbitraryDataResource(digest58, null, null, null);
				ArbitraryDataRenderManager.getInstance().addToAuthorizedResources(resource);

				return "/render/hash/" + digest58 + "?secret=" + Base58.encode(arbitraryDataFile.getSecret());
			}
		}
		return "Unable to generate preview URL";
	}

	private String upload(Service service, String name, String identifier,
						  String path, String string, String base64, boolean zipped, Long fee, String filename,
						  String title, String description, List<String> tags, Category category,
						  Boolean preview) {
		// Fetch public key from registered name
		try (final Repository repository = RepositoryManager.getRepository()) {
			NameData nameData = repository.getNameRepository().fromName(name);
			if (nameData == null) {
				String error = String.format("Name not registered: %s", name);
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, error);
			}

			final Long now = NTP.getTime();
			if (now == null) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NO_TIME_SYNC);
			}
			final Long minLatestBlockTimestamp = now - (60 * 60 * 1000L);
			if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp)) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);
			}

			AccountData accountData = repository.getAccountRepository().getAccount(nameData.getOwner());
			if (accountData == null) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);
			}
			byte[] publicKey = accountData.getPublicKey();
			String publicKey58 = Base58.encode(publicKey);

			if (path == null) {
				// See if we have a string instead
				if (string != null) {
					if (filename == null) {
						// Use current time as filename
						filename = String.format("qortal-%d", NTP.getTime());
					}
					java.nio.file.Path tempDirectory = Files.createTempDirectory("qortal-");
					File tempFile = Paths.get(tempDirectory.toString(), filename).toFile();
					tempFile.deleteOnExit();
					BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile.toPath().toString()));
					writer.write(string);
					writer.newLine();
					writer.close();
					path = tempFile.toPath().toString();
				}
				// ... or base64 encoded raw data
				else if (base64 != null) {
					if (filename == null) {
						// Use current time as filename
						filename = String.format("qortal-%d", NTP.getTime());
					}
					java.nio.file.Path tempDirectory = Files.createTempDirectory("qortal-");
					File tempFile = Paths.get(tempDirectory.toString(), filename).toFile();
					tempFile.deleteOnExit();
					Files.write(tempFile.toPath(), Base64.decode(base64));
					path = tempFile.toPath().toString();
				}
				else {
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Missing path or data string");
				}
			}

			if (zipped) {
				// Unzip the file
				java.nio.file.Path tempDirectory = Files.createTempDirectory("qortal-");
				tempDirectory.toFile().deleteOnExit();
				LOGGER.info("Unzipping...");
				ZipUtils.unzip(path, tempDirectory.toString());
				path = tempDirectory.toString();

				// Handle directories slightly differently to files
				if (tempDirectory.toFile().isDirectory()) {
					// The actual data will be in a randomly-named subfolder of tempDirectory
					// Remove hidden folders, i.e. starting with "_", as some systems can add them, e.g. "__MACOSX"
					String[] files = tempDirectory.toFile().list((parent, child) -> !child.startsWith("_"));
					if (files != null && files.length == 1) { // Single directory or file only
						path = Paths.get(tempDirectory.toString(), files[0]).toString();
					}
				}
			}

			// Finish here if user has requested a preview
			if (preview != null && preview) {
				return this.preview(path, service);
			}

			// Default to zero fee if not specified
			if (fee == null) {
				fee = 0L;
			}

			try {
				ArbitraryDataTransactionBuilder transactionBuilder = new ArbitraryDataTransactionBuilder(
						repository, publicKey58, fee, Paths.get(path), name, null, service, identifier,
						title, description, tags, category
				);

				transactionBuilder.build();
				// Don't compute nonce - this is done by the client (or via POST /arbitrary/compute)
				ArbitraryTransactionData transactionData = transactionBuilder.getArbitraryTransactionData();
				return Base58.encode(ArbitraryTransactionTransformer.toBytes(transactionData));

			} catch (DataException | TransformationException | IllegalStateException e) {
				LOGGER.info("Unable to upload data: {}", e.getMessage());
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
			}

		} catch (Exception e) {
			LOGGER.info("Exception when publishing data: ", e);
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}

	private HttpServletResponse download(Service service, String name, String identifier, String filepath, String encoding, boolean rebuild, boolean async, Integer maxAttempts) {

		try {
			ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);

			int attempts = 0;
			if (maxAttempts == null) {
				maxAttempts = 5;
			}

			// Loop until we have data
			if (async) {
				// Asynchronous
				arbitraryDataReader.loadAsynchronously(false, 1);
			}
			else {
				// Synchronous
				while (!Controller.isStopping()) {
					attempts++;
					if (!arbitraryDataReader.isBuilding()) {
						try {
							arbitraryDataReader.loadSynchronously(rebuild);
							break;
						} catch (MissingDataException e) {
							if (attempts > maxAttempts) {
								// Give up after 5 attempts
								throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Data unavailable. Please try again later.");
							}
						}
					}
					Thread.sleep(3000L);
				}
			}

			java.nio.file.Path outputPath = arbitraryDataReader.getFilePath();
			if (outputPath == null) {
				// Assume the resource doesn't exist
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND, "File not found");
			}

			if (filepath == null || filepath.isEmpty()) {
				// No file path supplied - so check if this is a single file resource
				String[] files = ArrayUtils.removeElement(outputPath.toFile().list(), ".qortal");
				if (files != null && files.length == 1) {
					// This is a single file resource
					filepath = files[0];
				}
				else {
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
							"filepath is required for resources containing more than one file");
				}
			}

			java.nio.file.Path path = Paths.get(outputPath.toString(), filepath);
			if (!Files.exists(path)) {
				String message = String.format("No file exists at filepath: %s", filepath);
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, message);
			}

			byte[] data;
			int fileSize = (int)path.toFile().length();
			int length = fileSize;

			// Parse "Range" header
			Integer rangeStart = null;
			Integer rangeEnd = null;
			String range = request.getHeader("Range");
			if (range != null) {
				range = range.replace("bytes=", "");
				String[] parts = range.split("-");
				rangeStart = (parts != null && parts.length > 0) ? Integer.parseInt(parts[0]) : null;
				rangeEnd = (parts != null && parts.length > 1) ? Integer.parseInt(parts[1]) : fileSize;
			}

			if (rangeStart != null && rangeEnd != null) {
				// We have a range, so update the requested length
				length = rangeEnd - rangeStart;
			}

			if (length < fileSize && encoding == null) {
				// Partial content requested, and not encoding the data
				response.setStatus(206);
				response.addHeader("Content-Range", String.format("bytes %d-%d/%d", rangeStart, rangeEnd-1, fileSize));
				data = FilesystemUtils.readFromFile(path.toString(), rangeStart, length);
			}
			else {
				// Full content requested (or encoded data)
				response.setStatus(200);
				data = Files.readAllBytes(path); // TODO: limit file size that can be read into memory
			}

			// Encode the data if requested
			if (encoding != null && Objects.equals(encoding.toLowerCase(), "base64")) {
				data = Base64.encode(data);
			}

			response.addHeader("Accept-Ranges", "bytes");
			response.setContentType(context.getMimeType(path.toString()));
			response.setContentLength(data.length);
			response.getOutputStream().write(data);

			return response;
		} catch (Exception e) {
			LOGGER.debug(String.format("Unable to load %s %s: %s", service, name, e.getMessage()));
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND, e.getMessage());
		}
	}

	private FileProperties getFileProperties(Service service, String name, String identifier) {
		try {
			ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
			arbitraryDataReader.loadSynchronously(false);
			java.nio.file.Path outputPath = arbitraryDataReader.getFilePath();
			if (outputPath == null) {
				// Assume the resource doesn't exist
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND, "File not found");
			}

			FileProperties fileProperties = new FileProperties();
			fileProperties.size = FileUtils.sizeOfDirectory(outputPath.toFile());

			String[] files = ArrayUtils.removeElement(outputPath.toFile().list(), ".qortal");
			if (files.length == 1) {
				String filename = files[0];
				java.nio.file.Path filePath = Paths.get(outputPath.toString(), files[0]);
				ContentInfoUtil util = new ContentInfoUtil();
				ContentInfo info = util.findMatch(filePath.toFile());
				String mimeType;
				if (info != null) {
					// Attempt to extract MIME type from file contents
					mimeType = info.getMimeType();
				}
				else {
					// Fall back to using the filename
					FileNameMap fileNameMap = URLConnection.getFileNameMap();
					mimeType = fileNameMap.getContentTypeFor(filename);
				}
				fileProperties.filename = filename;
				fileProperties.mimeType = mimeType;
			}

			return fileProperties;

		} catch (Exception e) {
			LOGGER.debug(String.format("Unable to load %s %s: %s", service, name, e.getMessage()));
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND, e.getMessage());
		}
	}
}
