package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.data.group.GroupData;
import org.qortal.data.group.GroupJoinRequestData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import java.util.List;

@Schema(description = "Group where the caller is admin, with its pending join requests")
@XmlAccessorType(XmlAccessType.FIELD)
public class GroupWithJoinRequests {

	@XmlElement(name = "group")
	@Schema(description = "Group info")
	public GroupData group;

	@XmlElement(name = "joinRequests")
	@Schema(description = "Pending join requests for this group")
	public List<GroupJoinRequestData> joinRequests;

	@SuppressWarnings("unused")
	protected GroupWithJoinRequests() {
	}

	public GroupWithJoinRequests(GroupData group, List<GroupJoinRequestData> joinRequests) {
		this.group = group;
		this.joinRequests = joinRequests;
	}
}
