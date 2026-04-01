package org.qortal.data.group;

/**
 * Summary of a group kick transaction for API/list queries.
 * Contains only the fields needed for the kicks list endpoint.
 */
public class GroupKickSummaryData {

	private final String member;
	private final int groupId;
	private final String reason;
	private final long timestamp;

	public GroupKickSummaryData(String member, int groupId, String reason, long timestamp) {
		this.member = member;
		this.groupId = groupId;
		this.reason = reason;
		this.timestamp = timestamp;
	}

	public String getMember() {
		return member;
	}

	public int getGroupId() {
		return groupId;
	}

	public String getReason() {
		return reason;
	}

	public long getTimestamp() {
		return timestamp;
	}
}
