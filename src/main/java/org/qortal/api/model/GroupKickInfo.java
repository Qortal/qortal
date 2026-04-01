package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

@Schema(description = "Group kick transaction summary for a kicked member")
@XmlAccessorType(XmlAccessType.FIELD)
public class GroupKickInfo {

	@XmlElement(name = "member")
	@Schema(description = "Address of the kicked member", example = "QixPbJUwsaHsVEofJdozU9zgVqkK6aYhrK")
	public String member;

	@XmlElement(name = "groupId")
	@Schema(description = "Group ID")
	public int groupId;

	@XmlElement(name = "reason")
	@Schema(description = "Reason for the kick")
	public String reason;

	@XmlElement(name = "timestamp")
	@Schema(description = "Transaction timestamp (milliseconds since epoch)")
	public long timestamp;

	// For JAX-RS / JSON
	protected GroupKickInfo() {
	}

	public GroupKickInfo(String member, int groupId, String reason, long timestamp) {
		this.member = member;
		this.groupId = groupId;
		this.reason = reason;
		this.timestamp = timestamp;
	}
}
