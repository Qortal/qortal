package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.api.resource.GroupsResource;
import org.qortal.test.common.ApiCommon;

import static org.junit.Assert.assertNotNull;

public class GroupApiTests extends ApiCommon {

	private GroupsResource groupsResource;

	@Before
	public void buildResource() {
		this.groupsResource = (GroupsResource) ApiCommon.buildResource(GroupsResource.class);
	}

	@Test
	public void test() {
		assertNotNull(this.groupsResource);
	}

	@Test
	public void testGetAllGroups() {
		assertNotNull(this.groupsResource.getAllGroups(null, null, null));
		assertNotNull(this.groupsResource.getAllGroups(1, 1, true));
	}

	@Test
	public void testGetBans() {
		assertNotNull(this.groupsResource.getBans(1));
	}

	@Test
	public void testGetGroup() {
		for (Boolean onlyAdmins : ALL_BOOLEAN_VALUES) {
			assertNotNull(this.groupsResource.getGroup(1, onlyAdmins, null, null, null));
			assertNotNull(this.groupsResource.getGroup(1, onlyAdmins, 1, 1, true));
		}
	}

	@Test
	public void testGetGroupData() {
		assertNotNull(this.groupsResource.getGroupData(1));
	}

	@Test
	public void testGetGroupsByOwner() {
		assertNotNull(this.groupsResource.getGroupsByOwner(aliceAddress));
	}

	@Test
	public void testGetGroupsWithMember() {
		assertNotNull(this.groupsResource.getGroupsWithMember(aliceAddress, null, null));
	}

	@Test
	public void testGetInvitesByGroupId() {
		assertNotNull(this.groupsResource.getInvitesByGroupId(1));
	}

	@Test
	public void testGetInvitesByInvitee() {
		assertNotNull(this.groupsResource.getInvitesByInvitee(aliceAddress));
	}

	@Test
	public void testGetJoinRequests() {
		assertNotNull(this.groupsResource.getJoinRequests(1));
	}

}
