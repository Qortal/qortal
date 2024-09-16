package org.qortal.repository;

import org.qortal.data.group.*;

import java.util.List;

public interface GroupRepository {

	// Groups

	public GroupData fromGroupId(int groupId) throws DataException;

	public GroupData fromGroupName(String groupName) throws DataException;

	public boolean groupExists(int groupId) throws DataException;

	public boolean groupExists(String groupName) throws DataException;

	public boolean reducedGroupNameExists(String reducedGroupName) throws DataException;

	public List<GroupData> getAllGroups(Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupData> getAllGroups() throws DataException {
		return getAllGroups(null, null, null);
	}

	public List<GroupData> getGroupsByOwner(String address, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupData> getGroupsByOwner(String address) throws DataException {
		return getGroupsByOwner(address, null, null, null);
	}

	public List<GroupData> getGroupsWithMember(String member, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupData> getGroupsWithMember(String member) throws DataException {
		return getGroupsWithMember(member, null, null, null);
	}

	public List<GroupData> searchGroups(String query, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public void save(GroupData groupData) throws DataException;

	public void delete(int groupId) throws DataException;

	public void delete(String groupName) throws DataException;

	// Group Owner

	public String getOwner(int groupId) throws DataException;

	// Group Admins

	public GroupAdminData getAdmin(int groupId, String address) throws DataException;

	public boolean adminExists(int groupId, String address) throws DataException;

	public List<GroupAdminData> getGroupAdmins(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupAdminData> getGroupAdmins(int groupId) throws DataException {
		return getGroupAdmins(groupId, null, null, null);
	}

	/** Returns number of group admins, or null if group doesn't exist */
	public Integer countGroupAdmins(int groupId) throws DataException;

	public void save(GroupAdminData groupAdminData) throws DataException;

	public void deleteAdmin(int groupId, String address) throws DataException;

	// Group Members

	public GroupMemberData getMember(int groupId, String address) throws DataException;

	public boolean memberExists(int groupId, String address) throws DataException;

	public List<GroupMemberData> getGroupMembers(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupMemberData> getGroupMembers(int groupId) throws DataException {
		return getGroupMembers(groupId, null, null, null);
	}

	/** Returns number of group members, or null if group doesn't exist */
	public Integer countGroupMembers(int groupId) throws DataException;

	public void save(GroupMemberData groupMemberData) throws DataException;

	public void deleteMember(int groupId, String address) throws DataException;

	// Group Invites

	public GroupInviteData getInvite(int groupId, String invitee) throws DataException;

	public boolean inviteExists(int groupId, String invitee) throws DataException;

	public List<GroupInviteData> getInvitesByGroupId(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupInviteData> getInvitesByGroupId(int groupId) throws DataException {
		return getInvitesByGroupId(groupId, null, null, null);
	}

	public List<GroupInviteData> getInvitesByInvitee(String invitee, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupInviteData> getInvitesByInvitee(String invitee) throws DataException {
		return getInvitesByInvitee(invitee, null, null, null);
	}

	public void save(GroupInviteData groupInviteData) throws DataException;

	public void deleteInvite(int groupId, String invitee) throws DataException;

	// Group Join Requests

	public GroupJoinRequestData getJoinRequest(Integer groupId, String joiner) throws DataException;

	public boolean joinRequestExists(int groupId, String joiner) throws DataException;

	public List<GroupJoinRequestData> getGroupJoinRequests(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupJoinRequestData> getGroupJoinRequests(int groupId) throws DataException {
		return getGroupJoinRequests(groupId, null, null, null);
	}

	public void save(GroupJoinRequestData groupJoinRequestData) throws DataException;

	public void deleteJoinRequest(int groupId, String joiner) throws DataException;

	// Group Bans

	public GroupBanData getBan(int groupId, String member) throws DataException;

	/**
	 * IMPORTANT: when using banExists() as part of validation, the timestamp must be that of the transaction that
	 * is calling banExists() as part of its validation. It must NOT be the current time, unless this is being
	 * called outside of validation, as part of an on demand check for a ban existing (such as via an API call).
	 * This is because we need to evaluate a ban's status based on the time of the subsequent transaction, as
	 * validation will not occur at a fixed time for every node. For some, it could be months into the future.
	 */
	public boolean banExists(int groupId, String offender, long timestamp) throws DataException;

	public List<GroupBanData> getGroupBans(int groupId, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public default List<GroupBanData> getGroupBans(int groupId) throws DataException {
		return getGroupBans(groupId, null, null, null);
	}

	public void save(GroupBanData groupBanData) throws DataException;

	public void deleteBan(int groupId, String offender) throws DataException;

}