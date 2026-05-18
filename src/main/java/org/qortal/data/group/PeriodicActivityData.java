package org.qortal.data.group;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.group.Group.ApprovalThreshold;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class PeriodicActivityData {

	// Properties
	private String owner;
	private String groupName;
	private int minimumBlockDelay;
	private int maximumBlockDelay;
	// Constructors

	// necessary for JAX-RS serialization
	protected PeriodicActivityData() {
	}

	/** Constructs new GroupData with nullable groupId and nullable updated [timestamp] */
	public PeriodicActivityData(Integer groupId, String owner, String groupName, String description, long created, Long updated,
                                boolean isOpen, ApprovalThreshold approvalThreshold, int minBlockDelay, int maxBlockDelay, byte[] reference,
                                int creationGroupId, String reducedGroupName) {
		this.owner = owner;
		this.groupName = groupName;
		this.minimumBlockDelay = minBlockDelay;
		this.maximumBlockDelay = maxBlockDelay;
	}

	// Getters / setters

	public String getOwner() {
		return this.owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public String getGroupName() {
		return this.groupName;
	}

	public int getMinimumBlockDelay() {
		return this.minimumBlockDelay;
	}

	public int getMaximumBlockDelay() {
		return this.maximumBlockDelay;
	}
}
