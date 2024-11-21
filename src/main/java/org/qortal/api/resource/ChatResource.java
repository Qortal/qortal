package org.qortal.api.resource;

import com.google.common.primitives.Bytes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.qortal.crypto.Crypto;
import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ChatTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.List;

import static org.qortal.data.chat.ChatMessage.Encoding;

@Path("/chat")
@Tag(name = "Chat")
public class ChatResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/messages")
	@Operation(
		summary = "Find chat messages",
		description = "Returns CHAT messages that match criteria. Must provide EITHER 'txGroupId' OR two 'involving' addresses.",
		responses = {
			@ApiResponse(
				description = "CHAT messages",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = ChatMessage.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<ChatMessage> searchChat(@QueryParam("before") Long before, @QueryParam("after") Long after,
			@QueryParam("txGroupId") Integer txGroupId,
			@QueryParam("involving") List<String> involvingAddresses,
			@QueryParam("reference") String reference,
			@QueryParam("chatreference") String chatReference,
			@QueryParam("haschatreference") Boolean hasChatReference,
			@QueryParam("sender") String sender,
			@QueryParam("encoding") Encoding encoding,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		// Check args meet expectations
		if ((txGroupId == null && involvingAddresses.size() != 2)
				|| (txGroupId != null && !involvingAddresses.isEmpty()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// Check any provided addresses are valid
		if (involvingAddresses.stream().anyMatch(address -> !Crypto.isValidAddress(address)))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (before != null && before < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (after != null && after < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] referenceBytes = null;
		if (reference != null)
			referenceBytes = Base58.decode(reference);

		byte[] chatReferenceBytes = null;
		if (chatReference != null)
			chatReferenceBytes = Base58.decode(chatReference);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getChatRepository().getMessagesMatchingCriteria(
					before,
					after,
					txGroupId,
					referenceBytes,
					chatReferenceBytes,
					hasChatReference,
					involvingAddresses,
					sender,
					encoding,
					limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/messages/count")
	@Operation(
			summary = "Count chat messages",
			description = "Returns count of CHAT messages that match criteria. Must provide EITHER 'txGroupId' OR two 'involving' addresses.",
			responses = {
					@ApiResponse(
							description = "count of messages",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "integer"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public int countChatMessages(@QueryParam("before") Long before, @QueryParam("after") Long after,
										@QueryParam("txGroupId") Integer txGroupId,
										@QueryParam("involving") List<String> involvingAddresses,
										@QueryParam("reference") String reference,
										@QueryParam("chatreference") String chatReference,
										@QueryParam("haschatreference") Boolean hasChatReference,
										@QueryParam("sender") String sender,
										@QueryParam("encoding") Encoding encoding,
										@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
										@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
										@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		// Check args meet expectations
		if ((txGroupId == null && involvingAddresses.size() != 2)
				|| (txGroupId != null && !involvingAddresses.isEmpty()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// Check any provided addresses are valid
		if (involvingAddresses.stream().anyMatch(address -> !Crypto.isValidAddress(address)))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (before != null && before < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (after != null && after < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] referenceBytes = null;
		if (reference != null)
			referenceBytes = Base58.decode(reference);

		byte[] chatReferenceBytes = null;
		if (chatReference != null)
			chatReferenceBytes = Base58.decode(chatReference);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getChatRepository().getMessagesMatchingCriteria(
					before,
					after,
					txGroupId,
					referenceBytes,
					chatReferenceBytes,
					hasChatReference,
					involvingAddresses,
					sender,
					encoding,
					limit, offset, reverse).size();
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/message/{signature}")
	@Operation(
			summary = "Find chat message by signature",
			responses = {
					@ApiResponse(
							description = "CHAT message",
							content = @Content(
										schema = @Schema(
												implementation = ChatMessage.class
										)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public ChatMessage getMessageBySignature(@PathParam("signature") String signature58, @QueryParam("encoding") Encoding encoding) {
		byte[] signature = Base58.decode(signature58);

		try (final Repository repository = RepositoryManager.getRepository()) {

			ChatTransactionData chatTransactionData = (ChatTransactionData) repository.getTransactionRepository().fromSignature(signature);
			if (chatTransactionData == null) {
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Message not found");
			}

			return repository.getChatRepository().toChatMessage(chatTransactionData, encoding);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/active/{address}")
	@Operation(
		summary = "Find active chats (group/direct) involving address",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = ActiveChats.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public ActiveChats getActiveChats(@PathParam("address") String address, @QueryParam("encoding") Encoding encoding) {
		if (address == null || !Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getChatRepository().getActiveChats(address, encoding);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Operation(
		summary = "Build raw, unsigned, CHAT transaction",
		description = "Builds a raw, unsigned CHAT transaction but does NOT compute proof-of-work nonce. See POST /chat/compute.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = ChatTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CHAT transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String buildChat(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ChatTransactionData transactionData) {
		if (!Settings.getInstance().isChatAuthBypassEnabled())
			Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChatTransaction chatTransaction = (ChatTransaction) Transaction.fromData(repository, transactionData);

			ValidationResult result = chatTransaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = ChatTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/compute")
	@Operation(
		summary = "Compute nonce for raw, unsigned CHAT transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					description = "raw, unsigned CHAT transaction in base58 encoding",
					example = "raw transaction base58"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CHAT transaction encoded in Base58",
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
	public String buildChat(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String rawBytes58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rawBytes = Base58.decode(rawBytes58);
			// We're expecting unsigned transaction, so append empty signature prior to decoding
			rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			if (transactionData.getType() != TransactionType.CHAT)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			ChatTransaction chatTransaction = (ChatTransaction) Transaction.fromData(repository, transactionData);

			// Quicker validity check first before we compute nonce
			ValidationResult result = chatTransaction.isValid();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			chatTransaction.computeNonce();

			// Re-check, but ignores signature
			result = chatTransaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			// Strip zeroed signature
			transactionData.setSignature(null);

			byte[] bytes = ChatTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}