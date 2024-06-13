package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.model.GroupMembers;
import org.qortal.api.model.GroupMembers.MemberInfo;
import org.qortal.crypto.Crypto;
import org.qortal.data.group.*;
import org.qortal.data.transaction.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.*;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Path("/groups")
@Tag(name = "Groups")
public class GroupsResource {

	@Context
	HttpServletRequest request;

	@GET
	@Operation(
		summary = "List all groups",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupData> getAllGroups(@Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> allGroupData = repository.getGroupRepository().getAllGroups(limit, offset, reverse);
			allGroupData.forEach(groupData -> {
				try {
					groupData.memberCount = repository.getGroupRepository().countGroupMembers(groupData.getGroupId());
				} catch (DataException e) {
					// Exclude memberCount for this group
				}
			});
			return allGroupData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/owner/{address}")
	@Operation(
		summary = "List all groups owned by address",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<GroupData> getGroupsByOwner(@PathParam("address") String owner) {
		if (!Crypto.isValidAddress(owner))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getGroupsByOwner(owner);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/member/{address}")
	@Operation(
		summary = "List all groups where address is a member",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<GroupData> getGroupsWithMember(@PathParam("address") String member) {
		if (!Crypto.isValidAddress(member))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> allGroupData = repository.getGroupRepository().getGroupsWithMember(member);
			allGroupData.forEach(groupData -> {
				try {
					groupData.memberCount = repository.getGroupRepository().countGroupMembers(groupData.getGroupId());
				} catch (DataException e) {
					// Exclude memberCount for this group
				}
			});
			return allGroupData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/{groupid}")
	@Operation(
		summary = "Info on group",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(implementation = GroupData.class)
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public GroupData getGroupData(@PathParam("groupid") int groupId) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
			if (groupData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.GROUP_UNKNOWN);

			groupData.memberCount = repository.getGroupRepository().countGroupMembers(groupId);
			return groupData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/search")
	@Operation(
		summary = "Search group name and description fields",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupData> searchGroups(@Parameter(
		description = "Query (searches name and description fields)"
	) @QueryParam("query") String query, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> searchResults = repository.getGroupRepository().searchGroups(query, limit, offset, reverse);
			searchResults.forEach(result -> {
				try {
					result.memberCount = repository.getGroupRepository().countGroupMembers(result.getGroupId());
				} catch (DataException e) {
					// Exclude memberCount for this group
				}
			});
			return searchResults;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/members/{groupid}")
	@Operation(
		summary = "List group members",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(implementation = GroupMembers.class)
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public GroupMembers getGroup(@PathParam("groupid") int groupId, @QueryParam("onlyAdmins") Boolean onlyAdmins,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit, @Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref="reverse") @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (!repository.getGroupRepository().groupExists(groupId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.GROUP_UNKNOWN);

			int adminCount = repository.getGroupRepository().countGroupAdmins(groupId);
			int memberCount = repository.getGroupRepository().countGroupMembers(groupId);

			if (onlyAdmins != null && onlyAdmins) {
				// Shortcut
				List<GroupAdminData> admins = repository.getGroupRepository().getGroupAdmins(groupId, limit, offset, reverse);

				// Convert form
				List<MemberInfo> membersInfo = admins.stream().map(admin -> new MemberInfo(admin.getAdmin(), null, true)).collect(Collectors.toList());

				return new GroupMembers(membersInfo, memberCount, adminCount);
			}

			final List<GroupAdminData> admins = repository.getGroupRepository().getGroupAdmins(groupId, limit, offset, reverse);

			List<GroupMemberData> members = repository.getGroupRepository().getGroupMembers(groupId, limit, offset, reverse);

			// Convert form
			Predicate<GroupMemberData> memberIsAdmin = member -> admins.stream().anyMatch(admin -> admin.getAdmin().equals(member.getMember()));
			List<MemberInfo> membersInfo = members.stream().map(member -> new MemberInfo(member.getMember(), member.getJoined(), memberIsAdmin.test(member))).collect(Collectors.toList());

			return new GroupMembers(membersInfo, memberCount, adminCount);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/create")
	@Operation(
		summary = "Build raw, unsigned, CREATE_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CreateGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CREATE_GROUP transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String createGroup(CreateGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = CreateGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/update")
	@Operation(
		summary = "Build raw, unsigned, UPDATE_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = UpdateGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, UPDATE_GROUP transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateGroup(UpdateGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = UpdateGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/addadmin")
	@Operation(
		summary = "Build raw, unsigned, ADD_GROUP_ADMIN transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = AddGroupAdminTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, ADD_GROUP_ADMIN transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String addGroupAdmin(AddGroupAdminTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = AddGroupAdminTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/removeadmin")
	@Operation(
		summary = "Build raw, unsigned, REMOVE_GROUP_ADMIN transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RemoveGroupAdminTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, REMOVE_GROUP_ADMIN transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String removeGroupAdmin(RemoveGroupAdminTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = RemoveGroupAdminTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/ban")
	@Operation(
		summary = "Build raw, unsigned, GROUP_BAN transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = GroupBanTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, GROUP_BAN transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String groupBan(GroupBanTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = GroupBanTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/ban/cancel")
	@Operation(
		summary = "Build raw, unsigned, CANCEL_GROUP_BAN transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CancelGroupBanTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CANCEL_GROUP_BAN transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String cancelGroupBan(CancelGroupBanTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = CancelGroupBanTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/kick")
	@Operation(
		summary = "Build raw, unsigned, GROUP_KICK transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = GroupKickTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, GROUP_KICK transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String groupKick(GroupKickTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = GroupKickTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/invite")
	@Operation(
		summary = "Build raw, unsigned, GROUP_INVITE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = GroupInviteTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, GROUP_INVITE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String groupInvite(GroupInviteTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = GroupInviteTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/invite/cancel")
	@Operation(
		summary = "Build raw, unsigned, CANCEL_GROUP_INVITE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CancelGroupInviteTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CANCEL_GROUP_INVITE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String cancelGroupInvite(CancelGroupInviteTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = CancelGroupInviteTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/join")
	@Operation(
		summary = "Build raw, unsigned, JOIN_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = JoinGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, JOIN_GROUP transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String joinGroup(JoinGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = JoinGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/leave")
	@Operation(
		summary = "Build raw, unsigned, LEAVE_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = LeaveGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, LEAVE_GROUP transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String leaveGroup(LeaveGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = LeaveGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/invites/{address}")
	@Operation(
		summary = "Pending group invites",
		responses = {
			@ApiResponse(
				description = "group invite",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupInviteData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupInviteData> getInvitesByInvitee(@PathParam("address") String invitee) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getInvitesByInvitee(invitee);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/invites/group/{groupid}")
	@Operation(
		summary = "Pending group invites",
		responses = {
			@ApiResponse(
				description = "group invite",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupInviteData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupInviteData> getInvitesByGroupId(@PathParam("groupid") int groupId) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getInvitesByGroupId(groupId);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/joinrequests/{groupid}")
	@Operation(
		summary = "Pending group join requests",
		responses = {
			@ApiResponse(
				description = "group join requests",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupJoinRequestData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupJoinRequestData> getJoinRequests(@PathParam("groupid") int groupId) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getGroupJoinRequests(groupId);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/bans/{groupid}")
	@Operation(
		summary = "Current group join bans",
		responses = {
			@ApiResponse(
				description = "group bans",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupJoinRequestData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupBanData> getBans(@PathParam("groupid") int groupId) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getGroupBans(groupId);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/approval")
	@Operation(
		summary = "Build raw, unsigned, GROUP_APPROVAL transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = GroupApprovalTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, GROUP_APPROVAL transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String groupApproval(GroupApprovalTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = GroupApprovalTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/setdefault")
	@Operation(
		summary = "Build raw, unsigned, SET_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = SetGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, SET_GROUP transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String setGroup(SetGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = SetGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}
