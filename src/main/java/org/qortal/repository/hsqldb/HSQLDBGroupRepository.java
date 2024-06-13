package org.qortal.repository.hsqldb;

import org.qortal.data.group.*;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.GroupRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HSQLDBGroupRepository implements GroupRepository {

	protected HSQLDBRepository repository;

	public HSQLDBGroupRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	// Groups

	@Override
	public GroupData fromGroupId(int groupId) throws DataException {
		String sql = "SELECT group_name, owner, description, created_when, updated_when, reference, is_open, "
				+ "approval_threshold, min_block_delay, max_block_delay, creation_group_id, reduced_group_name "
				+ "FROM Groups WHERE group_id = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, groupId)) {
			if (resultSet == null)
				return null;

			String groupName = resultSet.getString(1);
			String owner = resultSet.getString(2);
			String description = resultSet.getString(3);
			long created = resultSet.getLong(4);

			// Special handling for possibly-NULL "updated" column
			Long updated = resultSet.getLong(5);
			if (updated == 0 && resultSet.wasNull())
				updated = null;

			byte[] reference = resultSet.getBytes(6);
			boolean isOpen = resultSet.getBoolean(7);

			ApprovalThreshold approvalThreshold = ApprovalThreshold.valueOf(resultSet.getInt(8));

			int minBlockDelay = resultSet.getInt(9);
			int maxBlockDelay = resultSet.getInt(10);

			int creationGroupId = resultSet.getInt(11);
			String reducedGroupName = resultSet.getString(12);

			return new GroupData(groupId, owner, groupName, description, created, updated, isOpen,
					approvalThreshold, minBlockDelay, maxBlockDelay, reference, creationGroupId, reducedGroupName);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group info from repository", e);
		}
	}

	@Override
	public GroupData fromGroupName(String groupName) throws DataException {
		String sql = "SELECT group_id, owner, description, created_when, updated_when, reference, is_open, "
				+ "approval_threshold, min_block_delay, max_block_delay, creation_group_id, reduced_group_name "
				+ "FROM Groups WHERE group_name = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, groupName)) {
			if (resultSet == null)
				return null;

			int groupId = resultSet.getInt(1);
			String owner = resultSet.getString(2);
			String description = resultSet.getString(3);
			long created = resultSet.getLong(4);

			// Special handling for possibly-NULL "updated" column
			Long updated = resultSet.getLong(5);
			if (updated == 0 && resultSet.wasNull())
				updated = null;

			byte[] reference = resultSet.getBytes(6);
			boolean isOpen = resultSet.getBoolean(7);

			ApprovalThreshold approvalThreshold = ApprovalThreshold.valueOf(resultSet.getInt(8));

			int minBlockDelay = resultSet.getInt(9);
			int maxBlockDelay = resultSet.getInt(10);

			int creationGroupId = resultSet.getInt(11);
			String reducedGroupName = resultSet.getString(12);

			return new GroupData(groupId, owner, groupName, description, created, updated, isOpen,
					approvalThreshold, minBlockDelay, maxBlockDelay, reference, creationGroupId, reducedGroupName);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group info from repository", e);
		}
	}

	@Override
	public boolean groupExists(int groupId) throws DataException {
		try {
			return this.repository.exists("Groups", "group_id = ?", groupId);
		} catch (SQLException e) {
			throw new DataException("Unable to check for group in repository", e);
		}
	}

	@Override
	public boolean groupExists(String groupName) throws DataException {
		try {
			return this.repository.exists("Groups", "group_name = ?", groupName);
		} catch (SQLException e) {
			throw new DataException("Unable to check for group in repository", e);
		}
	}

	@Override
	public boolean reducedGroupNameExists(String reducedGroupName) throws DataException {
		try {
			return this.repository.exists("Groups", "reduced_group_name = ?", reducedGroupName);
		} catch (SQLException e) {
			throw new DataException("Unable to check for reduced group name in repository", e);
		}
	}

	@Override
	public List<GroupData> getAllGroups(Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT group_id, owner, group_name, description, created_when, updated_when, reference, is_open, "
				+ "approval_threshold, min_block_delay, max_block_delay, creation_group_id, reduced_group_name "
				+ "FROM Groups ORDER BY group_name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupData> groups = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return groups;

			do {
				int groupId = resultSet.getInt(1);
				String owner = resultSet.getString(2);
				String groupName = resultSet.getString(3);
				String description = resultSet.getString(4);
				long created = resultSet.getLong(5);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(6);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				byte[] reference = resultSet.getBytes(7);
				boolean isOpen = resultSet.getBoolean(8);

				ApprovalThreshold approvalThreshold = ApprovalThreshold.valueOf(resultSet.getInt(9));

				int minBlockDelay = resultSet.getInt(10);
				int maxBlockDelay = resultSet.getInt(11);

				int creationGroupId = resultSet.getInt(12);
				String reducedGroupName = resultSet.getString(13);

				groups.add(new GroupData(groupId, owner, groupName, description, created, updated, isOpen,
						approvalThreshold, minBlockDelay, maxBlockDelay, reference, creationGroupId, reducedGroupName));
			} while (resultSet.next());

			return groups;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch groups from repository", e);
		}
	}

	@Override
	public List<GroupData> getGroupsByOwner(String owner, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT group_id, group_name, description, created_when, updated_when, reference, is_open, "
				+ "approval_threshold, min_block_delay, max_block_delay, creation_group_id, reduced_group_name "
				+ "FROM Groups WHERE owner = ? ORDER BY group_name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupData> groups = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), owner)) {
			if (resultSet == null)
				return groups;

			do {
				int groupId = resultSet.getInt(1);
				String groupName = resultSet.getString(2);
				String description = resultSet.getString(3);
				long created = resultSet.getLong(4);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(5);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				byte[] reference = resultSet.getBytes(6);
				boolean isOpen = resultSet.getBoolean(7);

				ApprovalThreshold approvalThreshold = ApprovalThreshold.valueOf(resultSet.getInt(8));

				int minBlockDelay = resultSet.getInt(9);
				int maxBlockDelay = resultSet.getInt(10);

				int creationGroupId = resultSet.getInt(11);
				String reducedGroupName = resultSet.getString(12);

				groups.add(new GroupData(groupId, owner, groupName, description, created, updated, isOpen,
						approvalThreshold, minBlockDelay, maxBlockDelay, reference, creationGroupId, reducedGroupName));
			} while (resultSet.next());

			return groups;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's groups from repository", e);
		}
	}

	@Override
	public List<GroupData> getGroupsWithMember(String member, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT group_id, owner, group_name, description, created_when, updated_when, reference, is_open, "
				+ "approval_threshold, min_block_delay, max_block_delay, creation_group_id, reduced_group_name, admin FROM Groups "
				+ "JOIN GroupMembers USING (group_id) "
				+ "LEFT OUTER JOIN GroupAdmins ON GroupAdmins.group_id = GroupMembers.group_id AND GroupAdmins.admin = GroupMembers.address "
				+ "WHERE address = ? ORDER BY group_name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupData> groups = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), member)) {
			if (resultSet == null)
				return groups;

			do {
				int groupId = resultSet.getInt(1);
				String owner = resultSet.getString(2);
				String groupName = resultSet.getString(3);
				String description = resultSet.getString(4);
				long created = resultSet.getLong(5);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(6);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				byte[] reference = resultSet.getBytes(7);
				boolean isOpen = resultSet.getBoolean(8);

				ApprovalThreshold approvalThreshold = ApprovalThreshold.valueOf(resultSet.getInt(9));

				int minBlockDelay = resultSet.getInt(10);
				int maxBlockDelay = resultSet.getInt(11);

				int creationGroupId = resultSet.getInt(12);
				String reducedGroupName = resultSet.getString(13);

				resultSet.getString(14); // 'admin'
				boolean isAdmin = !resultSet.wasNull();

				GroupData groupData = new GroupData(groupId, owner, groupName, description, created, updated, isOpen,
						approvalThreshold, minBlockDelay, maxBlockDelay, reference, creationGroupId, reducedGroupName);

				groupData.setIsAdmin(isAdmin);

				groups.add(groupData);
			} while (resultSet.next());

			return groups;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's groups from repository", e);
		}
	}

	@Override
	public List<GroupData> searchGroups(String query, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT group_id, owner, group_name, description, created_when, updated_when, reference, is_open, "
				+ "approval_threshold, min_block_delay, max_block_delay, creation_group_id, reduced_group_name "
				+ "FROM Groups");

		// Handle general query matches
		if (query != null) {
			String queryWildcard = String.format("%%%s%%", query.toLowerCase());
			sql.append(" WHERE (LCASE(group_name) LIKE ? OR LCASE(description) LIKE ?)");
			bindParams.add(queryWildcard); bindParams.add(queryWildcard);
		}

		sql.append(" ORDER BY group_name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupData> groups = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return groups;

			do {
				int groupId = resultSet.getInt(1);
				String owner = resultSet.getString(2);
				String groupName = resultSet.getString(3);
				String description = resultSet.getString(4);
				long created = resultSet.getLong(5);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(6);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				byte[] reference = resultSet.getBytes(7);
				boolean isOpen = resultSet.getBoolean(8);

				ApprovalThreshold approvalThreshold = ApprovalThreshold.valueOf(resultSet.getInt(9));

				int minBlockDelay = resultSet.getInt(10);
				int maxBlockDelay = resultSet.getInt(11);

				int creationGroupId = resultSet.getInt(12);
				String reducedGroupName = resultSet.getString(13);

				groups.add(new GroupData(groupId, owner, groupName, description, created, updated, isOpen,
						approvalThreshold, minBlockDelay, maxBlockDelay,
						reference, creationGroupId, reducedGroupName));
			} while (resultSet.next());

			return groups;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch groups from repository", e);
		}
	}

	@Override
	public void save(GroupData groupData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Groups");

		saveHelper.bind("group_id", groupData.getGroupId()).bind("owner", groupData.getOwner()).bind("group_name", groupData.getGroupName())
				.bind("description", groupData.getDescription()).bind("created_when", groupData.getCreated()).bind("updated_when", groupData.getUpdated())
				.bind("reference", groupData.getReference()).bind("is_open", groupData.isOpen()).bind("approval_threshold", groupData.getApprovalThreshold().value)
				.bind("min_block_delay", groupData.getMinimumBlockDelay()).bind("max_block_delay", groupData.getMaximumBlockDelay())
				.bind("creation_group_id", groupData.getCreationGroupId()).bind("reduced_group_name", groupData.getReducedGroupName());

		try {
			saveHelper.execute(this.repository);

			if (groupData.getGroupId() == null) {
				// Fetch new groupId
				try (ResultSet resultSet = this.repository.checkedExecute("SELECT group_id FROM Groups WHERE reference = ?", groupData.getReference())) {
					if (resultSet == null)
						throw new DataException("Unable to fetch new group ID from repository");

					groupData.setGroupId(resultSet.getInt(1));
				}
			}
		} catch (SQLException e) {
			throw new DataException("Unable to save group info into repository", e);
		}
	}

	@Override
	public void delete(int groupId) throws DataException {
		try {
			// Remove group
			this.repository.delete("Groups", "group_id = ?", groupId);
		} catch (SQLException e) {
			throw new DataException("Unable to delete group info from repository", e);
		}
	}

	@Override
	public void delete(String groupName) throws DataException {
		try {
			// Remove group
			this.repository.delete("Groups", "group_name = ?", groupName);
		} catch (SQLException e) {
			throw new DataException("Unable to delete group info from repository", e);
		}
	}

	// Group Owner

	@Override
	public String getOwner(int groupId) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT owner FROM Groups WHERE group_id = ?", groupId)) {
			if (resultSet == null)
				return null;

			return resultSet.getString(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group owner from repository", e);
		}
	}

	// Group Admins

	@Override
	public GroupAdminData getAdmin(int groupId, String address) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT admin, reference FROM GroupAdmins WHERE group_id = ?", groupId)) {
			if (resultSet == null)
				return null;

			String admin = resultSet.getString(1);
			byte[] reference = resultSet.getBytes(2);

			return new GroupAdminData(groupId, admin, reference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group admin from repository", e);
		}
	}

	@Override
	public boolean adminExists(int groupId, String address) throws DataException {
		try {
			return this.repository.exists("GroupAdmins", "group_id = ? AND admin = ?", groupId, address);
		} catch (SQLException e) {
			throw new DataException("Unable to check for group admin in repository", e);
		}
	}

	@Override
	public List<GroupAdminData> getGroupAdmins(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT admin, reference FROM GroupAdmins WHERE group_id = ? ORDER BY admin");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupAdminData> admins = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), groupId)) {
			if (resultSet == null)
				return admins;

			do {
				String admin = resultSet.getString(1);
				byte[] reference = resultSet.getBytes(2);

				admins.add(new GroupAdminData(groupId, admin, reference));
			} while (resultSet.next());

			return admins;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group admins from repository", e);
		}
	}

	@Override
	public Integer countGroupAdmins(int groupId) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT COUNT(*) FROM GroupAdmins WHERE group_id = ?", groupId)) {
			int count = resultSet.getInt(1);

			if (count == 0)
				// There must be at least one admin: the group owner
				return null;

			return count;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group admin count from repository", e);
		}
	}

	@Override
	public void save(GroupAdminData groupAdminData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("GroupAdmins");

		saveHelper.bind("group_id", groupAdminData.getGroupId()).bind("admin", groupAdminData.getAdmin()).bind("reference", groupAdminData.getReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save group admin info into repository", e);
		}
	}

	@Override
	public void deleteAdmin(int groupId, String address) throws DataException {
		try {
			this.repository.delete("GroupAdmins", "group_id = ? AND admin = ?", groupId, address);
		} catch (SQLException e) {
			throw new DataException("Unable to delete group admin info from repository", e);
		}
	}

	// Group Members

	@Override
	public GroupMemberData getMember(int groupId, String address) throws DataException {
		String sql = "SELECT address, joined_when, reference FROM GroupMembers WHERE group_id = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, groupId)) {
			if (resultSet == null)
				return null;

			String member = resultSet.getString(1);
			long joined = resultSet.getLong(2);
			byte[] reference = resultSet.getBytes(3);

			return new GroupMemberData(groupId, member, joined, reference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group members from repository", e);
		}
	}

	@Override
	public boolean memberExists(int groupId, String address) throws DataException {
		try {
			return this.repository.exists("GroupMembers", "group_id = ? AND address = ?", groupId, address);
		} catch (SQLException e) {
			throw new DataException("Unable to check for group member in repository", e);
		}
	}

	@Override
	public List<GroupMemberData> getGroupMembers(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT address, joined_when, reference FROM GroupMembers WHERE group_id = ? ORDER BY address");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupMemberData> members = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), groupId)) {
			if (resultSet == null)
				return members;

			do {
				String member = resultSet.getString(1);
				long joined = resultSet.getLong(2);
				byte[] reference = resultSet.getBytes(3);

				members.add(new GroupMemberData(groupId, member, joined, reference));
			} while (resultSet.next());

			return members;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group members from repository", e);
		}
	}

	@Override
	public Integer countGroupMembers(int groupId) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT COUNT(*) FROM GroupMembers WHERE group_id = ?", groupId)) {
			int count = resultSet.getInt(1);

			if (count == 0)
				// There must be at least one member: the group owner
				return null;

			return count;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group member count from repository", e);
		}
	}

	@Override
	public void save(GroupMemberData groupMemberData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("GroupMembers");

		saveHelper.bind("group_id", groupMemberData.getGroupId()).bind("address", groupMemberData.getMember())
				.bind("joined_when", groupMemberData.getJoined()).bind("reference", groupMemberData.getReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save group member info into repository", e);
		}
	}

	@Override
	public void deleteMember(int groupId, String address) throws DataException {
		try {
			this.repository.delete("GroupMembers", "group_id = ? AND address = ?", groupId, address);
		} catch (SQLException e) {
			throw new DataException("Unable to delete group member info from repository", e);
		}
	}

	// Group Invites

	@Override
	public GroupInviteData getInvite(int groupId, String invitee) throws DataException {
		String sql = "SELECT inviter, expires_when, reference FROM GroupInvites WHERE group_id = ? AND invitee = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, groupId, invitee)) {
			if (resultSet == null)
				return null;

			String inviter = resultSet.getString(1);

			Long expiry = resultSet.getLong(2);
			if (expiry == 0 && resultSet.wasNull())
				expiry = null;

			byte[] reference = resultSet.getBytes(3);

			return new GroupInviteData(groupId, inviter, invitee, expiry, reference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group invite from repository", e);
		}
	}

	@Override
	public boolean inviteExists(int groupId, String invitee) throws DataException {
		try {
			return this.repository.exists("GroupInvites", "group_id = ? AND invitee = ?", groupId, invitee);
		} catch (SQLException e) {
			throw new DataException("Unable to check for group invite in repository", e);
		}
	}

	@Override
	public List<GroupInviteData> getInvitesByGroupId(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT inviter, invitee, expires_when, reference FROM GroupInvites WHERE group_id = ? ORDER BY invitee");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupInviteData> invites = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), groupId)) {
			if (resultSet == null)
				return invites;

			do {
				String inviter = resultSet.getString(1);
				String invitee = resultSet.getString(2);

				Long expiry = resultSet.getLong(3);
				if (expiry == 0 && resultSet.wasNull())
					expiry = null;

				byte[] reference = resultSet.getBytes(4);

				invites.add(new GroupInviteData(groupId, inviter, invitee, expiry, reference));
			} while (resultSet.next());

			return invites;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group invites from repository", e);
		}
	}

	@Override
	public List<GroupInviteData> getInvitesByInvitee(String invitee, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT group_id, inviter, expires_when, reference FROM GroupInvites WHERE invitee = ? ORDER BY group_id");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupInviteData> invites = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), invitee)) {
			if (resultSet == null)
				return invites;

			do {
				int groupId = resultSet.getInt(1);
				String inviter = resultSet.getString(2);

				Long expiry = resultSet.getLong(3);
				if (expiry == 0 && resultSet.wasNull())
					expiry = null;

				byte[] reference = resultSet.getBytes(4);

				invites.add(new GroupInviteData(groupId, inviter, invitee, expiry, reference));
			} while (resultSet.next());

			return invites;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group invites from repository", e);
		}
	}

	@Override
	public void save(GroupInviteData groupInviteData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("GroupInvites");

		saveHelper.bind("group_id", groupInviteData.getGroupId()).bind("inviter", groupInviteData.getInviter())
				.bind("invitee", groupInviteData.getInvitee()).bind("expires_when", groupInviteData.getExpiry())
				.bind("reference", groupInviteData.getReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save group invite into repository", e);
		}
	}

	@Override
	public void deleteInvite(int groupId, String invitee) throws DataException {
		try {
			this.repository.delete("GroupInvites", "group_id = ? AND invitee = ?", groupId, invitee);
		} catch (SQLException e) {
			throw new DataException("Unable to delete group invite from repository", e);
		}
	}

	// Group Join Requests

	@Override
	public GroupJoinRequestData getJoinRequest(Integer groupId, String joiner) throws DataException {
		String sql = "SELECT reference FROM GroupJoinRequests WHERE group_id = ? AND joiner = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, groupId, joiner)) {
			if (resultSet == null)
				return null;

			byte[] reference = resultSet.getBytes(1);

			return new GroupJoinRequestData(groupId, joiner, reference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group join requests from repository", e);
		}
	}

	@Override
	public boolean joinRequestExists(int groupId, String joiner) throws DataException {
		try {
			return this.repository.exists("GroupJoinRequests", "group_id = ? AND joiner = ?", groupId, joiner);
		} catch (SQLException e) {
			throw new DataException("Unable to check for group join request in repository", e);
		}
	}

	@Override
	public List<GroupJoinRequestData> getGroupJoinRequests(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT joiner, reference FROM GroupJoinRequests WHERE group_id = ? ORDER BY joiner");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupJoinRequestData> joinRequests = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), groupId)) {
			if (resultSet == null)
				return joinRequests;

			do {
				String joiner = resultSet.getString(1);
				byte[] reference = resultSet.getBytes(2);

				joinRequests.add(new GroupJoinRequestData(groupId, joiner, reference));
			} while (resultSet.next());

			return joinRequests;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group join requests from repository", e);
		}
	}

	@Override
	public void save(GroupJoinRequestData groupJoinRequestData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("GroupJoinRequests");

		saveHelper.bind("group_id", groupJoinRequestData.getGroupId()).bind("joiner", groupJoinRequestData.getJoiner())
				.bind("reference", groupJoinRequestData.getReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save group join request into repository", e);
		}
	}

	@Override
	public void deleteJoinRequest(int groupId, String joiner) throws DataException {
		try {
			this.repository.delete("GroupJoinRequests", "group_id = ? AND joiner = ?", groupId, joiner);
		} catch (SQLException e) {
			throw new DataException("Unable to delete group join request from repository", e);
		}
	}

	// Group Bans

	@Override
	public GroupBanData getBan(int groupId, String offender) throws DataException {
		String sql = "SELECT admin, banned_when, reason, expires_when, reference FROM GroupBans WHERE group_id = ? AND offender = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, groupId, offender)) {
			String admin = resultSet.getString(1);
			long banned = resultSet.getLong(2);
			String reason = resultSet.getString(3);

			Long expiry = resultSet.getLong(4);
			if (expiry == 0 && resultSet.wasNull())
				expiry = null;

			byte[] reference = resultSet.getBytes(5);

			return new GroupBanData(groupId, offender, admin, banned, reason, expiry, reference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group bans from repository", e);
		}
	}

	@Override
	public boolean banExists(int groupId, String offender, long timestamp) throws DataException {
		try {
			return this.repository.exists("GroupBans", "group_id = ? AND offender = ? AND (expires_when IS NULL OR expires_when > ?)", groupId, offender, timestamp);
		} catch (SQLException e) {
			throw new DataException("Unable to check for group ban in repository", e);
		}
	}

	@Override
	public List<GroupBanData> getGroupBans(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT offender, admin, banned_when, reason, expires_when, reference FROM GroupBans WHERE group_id = ? ORDER BY offender");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupBanData> bans = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), groupId)) {
			if (resultSet == null)
				return bans;

			do {
				String offender = resultSet.getString(1);
				String admin = resultSet.getString(2);
				long banned = resultSet.getLong(3);
				String reason = resultSet.getString(4);

				Long expiry = resultSet.getLong(5);
				if (expiry == 0 && resultSet.wasNull())
					expiry = null;

				byte[] reference = resultSet.getBytes(6);

				bans.add(new GroupBanData(groupId, offender, admin, banned, reason, expiry, reference));
			} while (resultSet.next());

			return bans;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group bans from repository", e);
		}
	}

	@Override
	public void save(GroupBanData groupBanData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("GroupBans");

		saveHelper.bind("group_id", groupBanData.getGroupId()).bind("offender", groupBanData.getOffender()).bind("admin", groupBanData.getAdmin())
				.bind("banned_when", groupBanData.getBanned()).bind("reason", groupBanData.getReason()).bind("expires_when", groupBanData.getExpiry())
				.bind("reference", groupBanData.getReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save group ban into repository", e);
		}
	}

	@Override
	public void deleteBan(int groupId, String offender) throws DataException {
		try {
			this.repository.delete("GroupBans", "group_id = ? AND offender = ?", groupId, offender);
		} catch (SQLException e) {
			throw new DataException("Unable to delete group ban from repository", e);
		}
	}

}
