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
import org.qortal.controller.arbitrary.ArbitraryDataHostMonitor;
import org.qortal.controller.arbitrary.ArbitraryDataRenderManager;
import org.qortal.controller.arbitrary.ArbitraryDataStorageManager;
import org.qortal.controller.arbitrary.ArbitraryMetadataManager;
import org.qortal.data.account.AccountData;
import org.qortal.data.arbitrary.ArbitraryCategoryInfo;
import org.qortal.data.arbitrary.ArbitraryDataIndexDetail;
import org.qortal.data.arbitrary.ArbitraryDataIndexScoreKey;
import org.qortal.data.arbitrary.ArbitraryDataIndexScorecard;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.arbitrary.IndexCache;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.ArbitraryHostedDataInfo;
import org.qortal.data.transaction.ArbitraryHostedDataItemInfo;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.apache.tika.Tika;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;

import javax.ws.rs.core.Response;

import org.glassfish.jersey.media.multipart.FormDataParam;

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
			@Parameter(description = "Keyword (searches description metadata field by keywords)") @QueryParam("keywords") List<String> keywords,
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
					.searchArbitraryResources(service, query, identifier, names, title, description, keywords, usePrefixOnly,
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
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ArbitraryHostedDataInfo.class))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public ArbitraryHostedDataInfo getHostedTransactions(@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
																@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
																   @QueryParam("name") String name) {

		Stream<ArbitraryHostedDataItemInfo> stream = ArbitraryDataHostMonitor.getInstance().getHostedDataItemInfos().stream();

		// if name is set, then filter by name
		if( name != null && !"".equals(name)) {
			stream = stream.filter( info -> info.getName().startsWith(name));
		}

		// always sort from the greatest total space to the least total space
		List<ArbitraryHostedDataItemInfo> hostedTransactions
			= stream.sorted(Comparator.comparing(ArbitraryHostedDataItemInfo::getTotalSpace).reversed())
				.collect(Collectors.toList());

		int count = hostedTransactions.size();
		long totalSpace = hostedTransactions.stream().collect(Collectors.summingLong(ArbitraryHostedDataItemInfo::getTotalSpace));

		List<ArbitraryHostedDataItemInfo> items = ArbitraryTransactionUtils.limitOffsetTransactions(hostedTransactions, limit, offset);

		return new ArbitraryHostedDataInfo(count, totalSpace, items);
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
	public void get(@PathParam("service") Service service,
								   @PathParam("name") String name,
								   @QueryParam("filepath") String filepath,
								   @QueryParam("encoding") String encoding,
								   @QueryParam("rebuild") boolean rebuild,
								   @QueryParam("async") boolean async,
								   @QueryParam("attempts") Integer attempts, @QueryParam("attachment") boolean attachment, @QueryParam("attachmentFilename") String attachmentFilename) {

		// Authentication can be bypassed in the settings, for those running public QDN nodes
		if (!Settings.getInstance().isQDNAuthBypassEnabled()) {
			Security.checkApiCallAllowed(request);
		}

		 this.download(service, name, null, filepath, encoding, rebuild, async, attempts, attachment, attachmentFilename);
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
	public void get(@PathParam("service") Service service,
								   @PathParam("name") String name,
								   @PathParam("identifier") String identifier,
								   @QueryParam("filepath") String filepath,
								   @QueryParam("encoding") String encoding,
								   @QueryParam("rebuild") boolean rebuild,
								   @QueryParam("async") boolean async,
								   @QueryParam("attempts") Integer attempts, @QueryParam("attachment") boolean attachment, @QueryParam("attachmentFilename") String attachmentFilename) {

		// Authentication can be bypassed in the settings, for those running public QDN nodes
		if (!Settings.getInstance().isQDNAuthBypassEnabled()) {
			Security.checkApiCallAllowed(request, null);
		}

		this.download(service, name, identifier, filepath, encoding, rebuild, async, attempts, attachment, attachmentFilename);
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


	@GET
	@Path("/check/tmp")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(
		summary = "Check if the disk has enough disk space for an upcoming upload",
		responses = {
			@ApiResponse(description = "OK if sufficient space", responseCode = "200"),
			@ApiResponse(description = "Insufficient space", responseCode = "507") // 507 = Insufficient Storage
		}
	)
	@SecurityRequirement(name = "apiKey")
	public Response checkUploadSpace(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
									 @QueryParam("totalSize") Long totalSize) {
		Security.checkApiCallAllowed(request);
	
		if (totalSize == null || totalSize <= 0) {
			return Response.status(Response.Status.BAD_REQUEST)
					.entity("Missing or invalid totalSize parameter").build();
		}
	
		File uploadDir = new File("uploads-temp");
		if (!uploadDir.exists()) {
			uploadDir.mkdirs(); // ensure the folder exists
		}

		long usableSpace = uploadDir.getUsableSpace();
		long requiredSpace = (long)(((double)totalSize) * 2.2); // estimate for chunks + merge

		if (usableSpace < requiredSpace) {
			return Response.status(507).entity("Insufficient disk space").build();
		}

		return Response.ok("Sufficient disk space").build();
	}

	@POST
@Path("/{service}/{name}/chunk")
@Consumes(MediaType.MULTIPART_FORM_DATA)
@Operation(
    summary = "Upload a single file chunk to be later assembled into a complete arbitrary resource (no identifier)",
    requestBody = @RequestBody(
        required = true,
        content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA,
            schema = @Schema(
                implementation = Object.class
            )
        )
    ),
    responses = {
        @ApiResponse(
            description = "Chunk uploaded successfully",
            responseCode = "200"
        ),
        @ApiResponse(
            description = "Error writing chunk",
            responseCode = "500"
        )
    }
)
@SecurityRequirement(name = "apiKey")
public Response uploadChunkNoIdentifier(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
                                        @PathParam("service") String serviceString,
                                        @PathParam("name") String name,
                                        @FormDataParam("chunk") InputStream chunkStream,
                                        @FormDataParam("index") int index) {
    Security.checkApiCallAllowed(request);

    try {
		String safeService = Paths.get(serviceString).getFileName().toString();
        String safeName = Paths.get(name).getFileName().toString();

		java.nio.file.Path tempDir = Paths.get("uploads-temp", safeService, safeName);
		Files.createDirectories(tempDir);
		
        

        java.nio.file.Path chunkFile = tempDir.resolve("chunk_" + index);
        Files.copy(chunkStream, chunkFile, StandardCopyOption.REPLACE_EXISTING);

        return Response.ok("Chunk " + index + " received").build();
    } catch (IOException e) {
		LOGGER.error("Failed to write chunk {} for service '{}' and name '{}'", index, serviceString, name, e);
        return Response.serverError().entity("Failed to write chunk: " + e.getMessage()).build();
    }
}

@POST
@Path("/{service}/{name}/finalize")
@Produces(MediaType.TEXT_PLAIN)
@Operation(
    summary = "Finalize a chunked upload (no identifier) and build a raw, unsigned, ARBITRARY transaction",
    responses = {
        @ApiResponse(
            description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
            content = @Content(mediaType = MediaType.TEXT_PLAIN)
        )
    }
)
@SecurityRequirement(name = "apiKey")
public String finalizeUploadNoIdentifier(
    @HeaderParam(Security.API_KEY_HEADER) String apiKey,
    @PathParam("service") String serviceString,
    @PathParam("name") String name,
    @QueryParam("title") String title,
    @QueryParam("description") String description,
    @QueryParam("tags") List<String> tags,
    @QueryParam("category") Category category,
    @QueryParam("filename") String filename,
    @QueryParam("fee") Long fee,
    @QueryParam("preview") Boolean preview,
    @QueryParam("isZip") Boolean isZip
) {
    Security.checkApiCallAllowed(request);
    java.nio.file.Path tempFile = null;
    java.nio.file.Path tempDir = null;
	java.nio.file.Path chunkDir = null;
    String safeService = Paths.get(serviceString).getFileName().toString();
	String safeName = Paths.get(name).getFileName().toString();



    try {
		chunkDir = Paths.get("uploads-temp", safeService, safeName);

        if (!Files.exists(chunkDir) || !Files.isDirectory(chunkDir)) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "No chunks found for upload");
        }

        String safeFilename = (filename == null || filename.isBlank()) ? "qortal-" + NTP.getTime() : filename;
        tempDir = Files.createTempDirectory("qortal-");
        String sanitizedFilename = Paths.get(safeFilename).getFileName().toString();
		tempFile = tempDir.resolve(sanitizedFilename);

        try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[65536];
            for (java.nio.file.Path chunk : Files.list(chunkDir)
                    .filter(path -> path.getFileName().toString().startsWith("chunk_"))
                    .sorted(Comparator.comparingInt(path -> {
                        String name2 = path.getFileName().toString();
                        String numberPart = name2.substring("chunk_".length());
                        return Integer.parseInt(numberPart);
                    })).collect(Collectors.toList())) {
                try (InputStream in = Files.newInputStream(chunk)) {
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }
        }

        String detectedExtension = "";
        String uploadFilename = null;
        boolean extensionIsValid = false;

        if (filename != null && !filename.isBlank()) {
            int lastDot = filename.lastIndexOf('.');
            if (lastDot > 0 && lastDot < filename.length() - 1) {
                extensionIsValid = true;
                uploadFilename = filename;
            }
        }

        if (!extensionIsValid) {
            Tika tika = new Tika();
            String mimeType = tika.detect(tempFile.toFile());
            try {
                MimeTypes allTypes = MimeTypes.getDefaultMimeTypes();
                org.apache.tika.mime.MimeType mime = allTypes.forName(mimeType);
                detectedExtension = mime.getExtension();
            } catch (MimeTypeException e) {
                LOGGER.warn("Could not determine file extension for MIME type: {}", mimeType, e);
            }

            if (filename != null && !filename.isBlank()) {
                int lastDot = filename.lastIndexOf('.');
                String baseName = (lastDot > 0) ? filename.substring(0, lastDot) : filename;
                uploadFilename = baseName + (detectedExtension != null ? detectedExtension : "");
            } else {
                uploadFilename = "qortal-" + NTP.getTime() + (detectedExtension != null ? detectedExtension : "");
            }
        }

		Boolean isZipBoolean = false;

		if (isZip != null && isZip) {
			isZipBoolean = true;
		}
        

        // ✅ Call upload with `null` as identifier
        return this.upload(
            Service.valueOf(serviceString),
            name,
            null, // no identifier
            tempFile.toString(),
            null,
            null,
            isZipBoolean,
            fee,
            uploadFilename,
            title,
            description,
            tags,
            category,
            preview
        );

    } catch (IOException e) {
		LOGGER.error("Failed to merge chunks for service='{}', name='{}'", serviceString, name, e);

        throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, "Failed to merge chunks: " + e.getMessage());
    } finally {
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
            } catch (IOException e) {
                LOGGER.warn("Failed to delete temp directory: {}", tempDir, e);
            }
        }

        try {
            Files.walk(chunkDir)
                .sorted(Comparator.reverseOrder())
                .map(java.nio.file.Path::toFile)
                .forEach(File::delete);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete chunk directory: {}", chunkDir, e);
        }
    }
}



	@POST
@Path("/{service}/{name}/{identifier}/chunk")
@Consumes(MediaType.MULTIPART_FORM_DATA)
@Operation(
    summary = "Upload a single file chunk to be later assembled into a complete arbitrary resource",
    requestBody = @RequestBody(
        required = true,
        content = @Content(
            mediaType = MediaType.MULTIPART_FORM_DATA,
            schema = @Schema(
                implementation = Object.class
            )
        )
    ),
    responses = {
        @ApiResponse(
            description = "Chunk uploaded successfully",
            responseCode = "200"
        ),
        @ApiResponse(
            description = "Error writing chunk",
            responseCode = "500"
        )
    }
)
@SecurityRequirement(name = "apiKey")
public Response uploadChunk(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
                            @PathParam("service") String serviceString,
                            @PathParam("name") String name,
                            @PathParam("identifier") String identifier,
                            @FormDataParam("chunk") InputStream chunkStream,
                            @FormDataParam("index") int index) {
    Security.checkApiCallAllowed(request);

    try {
        String safeService = Paths.get(serviceString).getFileName().toString();
        String safeName = Paths.get(name).getFileName().toString();
        String safeIdentifier = Paths.get(identifier).getFileName().toString();

        java.nio.file.Path tempDir = Paths.get("uploads-temp", safeService, safeName, safeIdentifier);

        Files.createDirectories(tempDir);

        java.nio.file.Path chunkFile = tempDir.resolve("chunk_" + index);
        Files.copy(chunkStream, chunkFile, StandardCopyOption.REPLACE_EXISTING);

        return Response.ok("Chunk " + index + " received").build();
    } catch (IOException e) {
		LOGGER.error("Failed to write chunk {} for service='{}', name='{}', identifier='{}'", index, serviceString, name, identifier, e);
        return Response.serverError().entity("Failed to write chunk: " + e.getMessage()).build();
    }
}

@POST
@Path("/{service}/{name}/{identifier}/finalize")
@Produces(MediaType.TEXT_PLAIN)
@Operation(
    summary = "Finalize a chunked upload and build a raw, unsigned, ARBITRARY transaction",
    responses = {
        @ApiResponse(
            description = "raw, unsigned, ARBITRARY transaction encoded in Base58",
            content = @Content(mediaType = MediaType.TEXT_PLAIN)
        )
    }
)
@SecurityRequirement(name = "apiKey")
public String finalizeUpload(
    @HeaderParam(Security.API_KEY_HEADER) String apiKey,
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
	@QueryParam("isZip") Boolean isZip
) {
    Security.checkApiCallAllowed(request);
    java.nio.file.Path tempFile = null;
    java.nio.file.Path tempDir = null;
	java.nio.file.Path chunkDir = null;

	
	
	

    try {
		String safeService = Paths.get(serviceString).getFileName().toString();
	String safeName = Paths.get(name).getFileName().toString();
	String safeIdentifier = Paths.get(identifier).getFileName().toString();
	java.nio.file.Path baseUploadsDir = Paths.get("uploads-temp"); // relative to Qortal working dir
	chunkDir = baseUploadsDir.resolve(safeService).resolve(safeName).resolve(safeIdentifier);
		
        if (!Files.exists(chunkDir) || !Files.isDirectory(chunkDir)) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "No chunks found for upload");
        }

        // Step 1: Determine a safe filename for disk temp file (regardless of extension correctness)
        String safeFilename = filename;
        if (filename == null || filename.isBlank()) {
			safeFilename = "qortal-" + NTP.getTime();
		} 

        tempDir = Files.createTempDirectory("qortal-");
        String sanitizedFilename = Paths.get(safeFilename).getFileName().toString();
		tempFile = tempDir.resolve(sanitizedFilename);


        // Step 2: Merge chunks
  
        try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[65536];
            for (java.nio.file.Path chunk : Files.list(chunkDir)
                    .filter(path -> path.getFileName().toString().startsWith("chunk_"))
                    .sorted(Comparator.comparingInt(path -> {
                        String name2 = path.getFileName().toString();
                        String numberPart = name2.substring("chunk_".length());
                        return Integer.parseInt(numberPart);
                    })).collect(Collectors.toList())) {
                try (InputStream in = Files.newInputStream(chunk)) {
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }
        }
       

        // Step 3: Determine correct extension
        String detectedExtension = "";
        String uploadFilename  = null;
        boolean extensionIsValid = false;

        if (filename != null && !filename.isBlank()) {
            int lastDot = filename.lastIndexOf('.');
            if (lastDot > 0 && lastDot < filename.length() - 1) {
                extensionIsValid = true;
                uploadFilename = filename;
            }
        }

        if (!extensionIsValid) {
            Tika tika = new Tika();
            String mimeType = tika.detect(tempFile.toFile());
            try {
                MimeTypes allTypes = MimeTypes.getDefaultMimeTypes();
                org.apache.tika.mime.MimeType mime = allTypes.forName(mimeType);
                detectedExtension = mime.getExtension();
            } catch (MimeTypeException e) {
                LOGGER.warn("Could not determine file extension for MIME type: {}", mimeType, e);
            }

            if (filename != null && !filename.isBlank()) {
                int lastDot = filename.lastIndexOf('.');
                String baseName = (lastDot > 0) ? filename.substring(0, lastDot) : filename;
                uploadFilename = baseName + (detectedExtension != null ? detectedExtension : "");
            } else {
                uploadFilename = "qortal-" + NTP.getTime() + (detectedExtension != null ? detectedExtension : "");
            }
        }


		Boolean isZipBoolean = false;

		if (isZip != null && isZip) {
			isZipBoolean = true;
		}
        

        return this.upload(
            Service.valueOf(serviceString),
            name,
            identifier,
            tempFile.toString(),
            null,
            null,
            isZipBoolean,
            fee,
            uploadFilename,
            title,
            description,
            tags,
            category,
            preview
        );

    } catch (IOException e) {
		LOGGER.error("Unexpected error in finalizeUpload for service='{}', name='{}', name='{}'", serviceString, name, identifier, e);

        throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, "Failed to merge chunks: " + e.getMessage());
    } finally {
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
            } catch (IOException e) {
                LOGGER.warn("Failed to delete temp directory: {}", tempDir, e);
            }
        }

        try {
            Files.walk(chunkDir)
                .sorted(Comparator.reverseOrder())
                .map(java.nio.file.Path::toFile)
                .forEach(File::delete);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete chunk directory: {}", chunkDir, e);
        }
    }
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

	@GET
	@Path("/indices")
	@Operation(
			summary = "Find matching arbitrary resource indices",
			description = "",
			responses = {
					@ApiResponse(
							description = "indices",
							content = @Content(
									array = @ArraySchema(
											schema = @Schema(
													implementation = ArbitraryDataIndexScorecard.class
											)
									)
							)
					)
			}
	)
	public List<ArbitraryDataIndexScorecard> searchIndices(@QueryParam("terms") String[] terms) {

		List<ArbitraryDataIndexDetail> indices = new ArrayList<>();

		// get index details for each term
		for( String term : terms ) {
			List<ArbitraryDataIndexDetail> details = IndexCache.getInstance().getIndicesByTerm().get(term);

			if( details != null ) {
				indices.addAll(details);
			}
		}

		// sum up the scores for each index with identical attributes
		Map<ArbitraryDataIndexScoreKey, Double> scoreForKey
			= indices.stream()
				.collect(
					Collectors.groupingBy(
						index -> new ArbitraryDataIndexScoreKey(index.name, index.category, index.link),
						Collectors.summingDouble(detail -> 1.0 / detail.rank)
					)
				);

		// create scorecards for each index group and put them in descending order by score
		List<ArbitraryDataIndexScorecard> scorecards
			= scoreForKey.entrySet().stream().map(
				entry
				->
				new ArbitraryDataIndexScorecard(
					entry.getValue(),
					entry.getKey().name,
					entry.getKey().category,
					entry.getKey().link)
				)
				.sorted(Comparator.comparingDouble(ArbitraryDataIndexScorecard::getScore).reversed())
				.collect(Collectors.toList());

		return scorecards;
	}

	@GET
	@Path("/indices/{name}/{idPrefix}")
	@Operation(
			summary = "Find matching arbitrary resource indices for a registered name and identifier prefix",
			description = "",
			responses = {
					@ApiResponse(
							description = "indices",
							content = @Content(
									array = @ArraySchema(
											schema = @Schema(
													implementation = ArbitraryDataIndexDetail.class
											)
									)
							)
					)
			}
	)
	public List<ArbitraryDataIndexDetail> searchIndicesByName(@PathParam("name") String name, @PathParam("idPrefix") String idPrefix) {

		return
			IndexCache.getInstance().getIndicesByIssuer()
				.getOrDefault(name, new ArrayList<>(0)).stream()
					.filter( indexDetail -> indexDetail.indexIdentifer.startsWith(idPrefix))
					.collect(Collectors.toList());
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
					if (filename == null || filename.isBlank()) {
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
					if (filename == null || filename.isBlank()) {
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

	private void download(Service service, String name, String identifier, String filepath, String encoding, boolean rebuild, boolean async, Integer maxAttempts, boolean attachment, String attachmentFilename) {
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
			} else {
				// Synchronous
				while (!Controller.isStopping()) {
					attempts++;
					if (!arbitraryDataReader.isBuilding()) {
						try {
							arbitraryDataReader.loadSynchronously(rebuild);
							break;
						} catch (MissingDataException e) {
							if (attempts > maxAttempts) {
								throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Data unavailable. Please try again later.");
							}
						}
					}
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
				} else {
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "filepath is required for resources containing more than one file");
				}
			}
	
			java.nio.file.Path path = Paths.get(outputPath.toString(), filepath);
			if (!Files.exists(path)) {
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "No file exists at filepath: " + filepath);
			}
	
			if (attachment) {
				String rawFilename;
	
				if (attachmentFilename != null && !attachmentFilename.isEmpty()) {
					// 1. Sanitize first
					String safeAttachmentFilename = attachmentFilename.replaceAll("[\\\\/:*?\"<>|]", "_");
	
					// 2. Check for a valid extension (3–5 alphanumeric chars)
					if (!safeAttachmentFilename.matches(".*\\.[a-zA-Z0-9]{2,5}$")) {
						safeAttachmentFilename += ".bin";
					}
	
					rawFilename = safeAttachmentFilename;
				} else {
					// Fallback if no filename is provided
					String baseFilename = (identifier != null && !identifier.isEmpty())
						? name + "-" + identifier
						: name;
					rawFilename = baseFilename.replaceAll("[\\\\/:*?\"<>|]", "_") + ".bin";
				}
	
				// Optional: trim length
				rawFilename = rawFilename.length() > 100 ? rawFilename.substring(0, 100) : rawFilename;
	
				// 3. Set Content-Disposition header
				response.setHeader("Content-Disposition", "attachment; filename=\"" + rawFilename + "\"");
			}
	
			// Determine the total size of the requested file
			long fileSize = Files.size(path);
			String mimeType = context.getMimeType(path.toString());
	
			// Attempt to read the "Range" header from the request to support partial content delivery (e.g., for video streaming or resumable downloads)
			String range = request.getHeader("Range");
	
			long rangeStart = 0;
			long rangeEnd = fileSize - 1;
			boolean isPartial = false;
	
			// If a Range header is present and no base64 encoding is requested, parse the range values
			if (range != null && encoding == null) {
				range = range.replace("bytes=", ""); // Remove the "bytes=" prefix
				String[] parts = range.split("-"); // Split the range into start and end
	
				// Parse range start
				if (parts.length > 0 && !parts[0].isEmpty()) {
					rangeStart = Long.parseLong(parts[0]);
				}
	
				// Parse range end, if present
				if (parts.length > 1 && !parts[1].isEmpty()) {
					rangeEnd = Long.parseLong(parts[1]);
				}
	
				isPartial = true; // Indicate that this is a partial content request
			}
	
			// Calculate how many bytes should be sent in the response
			long contentLength = rangeEnd - rangeStart + 1;
	
			// Inform the client that byte ranges are supported
			response.setHeader("Accept-Ranges", "bytes");
	
			if (isPartial) {
				// If partial content was requested, return 206 Partial Content with appropriate headers
				response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
				response.setHeader("Content-Range", String.format("bytes %d-%d/%d", rangeStart, rangeEnd, fileSize));
			} else {
				// Otherwise, return the entire file with status 200 OK
				response.setStatus(HttpServletResponse.SC_OK);
			}
	
			// Initialize output streams for writing the file to the response
			OutputStream rawOut = null;
			OutputStream base64Out = null;
			OutputStream gzipOut = null;
	
			try {
				rawOut = response.getOutputStream();
	
				if (encoding != null && "base64".equalsIgnoreCase(encoding)) {
					// If base64 encoding is requested, override content type
					response.setContentType("text/plain");
	
					// Check if the client accepts gzip encoding
					String acceptEncoding = request.getHeader("Accept-Encoding");
					boolean wantsGzip = acceptEncoding != null && acceptEncoding.contains("gzip");
	
					if (wantsGzip) {
						// Wrap output in GZIP and Base64 streams if gzip is accepted
						response.setHeader("Content-Encoding", "gzip");
						gzipOut = new GZIPOutputStream(rawOut);
						base64Out = java.util.Base64.getEncoder().wrap(gzipOut);
					} else {
						// Wrap output in Base64 only
						base64Out = java.util.Base64.getEncoder().wrap(rawOut);
					}
	
					rawOut = base64Out; // Use the wrapped stream for writing
				} else {
					// For raw binary output, set the content type and length
					response.setContentType(mimeType != null ? mimeType : "application/octet-stream");
					response.setContentLength((int) contentLength);
				}
	
				// Stream file content
				try (InputStream inputStream = Files.newInputStream(path)) {
					if (rangeStart > 0) {
						inputStream.skip(rangeStart);
					}
	
					byte[] buffer = new byte[65536];
					long bytesRemaining = contentLength;
					int bytesRead;
	
					while (bytesRemaining > 0 && (bytesRead = inputStream.read(buffer, 0, (int) Math.min(buffer.length, bytesRemaining))) != -1) {
						rawOut.write(buffer, 0, bytesRead);
						bytesRemaining -= bytesRead;
					}
				}
	
				// Stream finished
				if (base64Out != null) {
					base64Out.close(); // Also flushes and closes the wrapped gzipOut
				} else if (gzipOut != null) {
					gzipOut.close(); // Only close gzipOut if it wasn't wrapped by base64Out
				} else {
					rawOut.flush(); // Flush only the base output stream if nothing was wrapped
				}
	
				if (!response.isCommitted()) {
					response.setStatus(HttpServletResponse.SC_OK);
					response.getWriter().write(" ");
				}
	
			} catch (IOException e) {
				// Streaming errors should not rethrow — just log
				LOGGER.warn(String.format("Streaming error for %s %s: %s", service, name, e.getMessage()));
			}
	
		} catch (IOException | ApiException | DataException e) {
			LOGGER.warn(String.format("Unable to load %s %s: %s", service, name, e.getMessage()));
			if (!response.isCommitted()) {
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND, e.getMessage());
			}
		} catch (NumberFormatException e) {
			LOGGER.warn(String.format("Invalid range for %s %s: %s", service, name, e.getMessage()));
			if (!response.isCommitted()) {
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA, e.getMessage());
			}
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
