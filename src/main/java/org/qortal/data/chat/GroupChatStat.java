package org.qortal.data.chat;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class GroupChatStat {

	/* Address of sender */
	private int groupId;

	private long size;

	// Constructors

	protected GroupChatStat() {
		/* For JAXB */
	}

	// For repository use
	public GroupChatStat(int groupId, long size) {
		this.groupId = groupId;
		this.size = size;
	}

	public int getGroupId() {
		return groupId;
	}

	public long getSize() {
		return size;
	}
}
