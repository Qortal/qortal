package org.qortal.data.group;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.group.Group.ApprovalThreshold;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class GroupData {

	// Properties
	private Integer groupId;
	private String owner;
	private String groupName;
	private String description;
	private long created;
	private Long updated;
	private boolean isOpen;
	private ApprovalThreshold approvalThreshold;
	private int minimumBlockDelay;
	private int maximumBlockDelay;
	public int memberCount;

	/** Reference to CREATE_GROUP or UPDATE_GROUP transaction, used to rebuild group during orphaning. */
	// No need to ever expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private byte[] reference;

	// For internal use
	@XmlTransient
	@Schema(hidden = true)
	private int creationGroupId;

	// For internal use
	@XmlTransient
	@Schema(hidden = true)
	private String reducedGroupName;

	// We abuse GroupData for API purposes by adding this unrelated field. Not always present.
	private Boolean isAdmin;

	/** Owner's primary registered name. API-only, not always present. */
	private String ownerPrimaryName;

	// Constructors

	// necessary for JAX-RS serialization
	protected GroupData() {
	}

	/** Constructs new GroupData with nullable groupId and nullable updated [timestamp] */
	public GroupData(Integer groupId, String owner, String groupName, String description, long created, Long updated,
			boolean isOpen, ApprovalThreshold approvalThreshold, int minBlockDelay, int maxBlockDelay, byte[] reference,
			int creationGroupId, String reducedGroupName) {
		this.groupId = groupId;
		this.owner = owner;
		this.groupName = groupName;
		this.description = description;
		this.created = created;
		this.updated = updated;
		this.isOpen = isOpen;
		this.approvalThreshold = approvalThreshold;
		this.reference = reference;
		this.minimumBlockDelay = minBlockDelay;
		this.maximumBlockDelay = maxBlockDelay;
		this.creationGroupId = creationGroupId;
		this.reducedGroupName = reducedGroupName;
	}

	/** Constructs new GroupData with unassigned groupId */
	public GroupData(String owner, String groupName, String description, long created, boolean isOpen,
			ApprovalThreshold approvalThreshold, int minBlockDelay, int maxBlockDelay, byte[] reference,
			int creationGroupId, String reducedGroupName) {
		this(null, owner, groupName, description, created, null, isOpen, approvalThreshold, minBlockDelay,
				maxBlockDelay, reference, creationGroupId, reducedGroupName);
	}

	// Getters / setters

	public Integer getGroupId() {
		return this.groupId;
	}

	public void setGroupId(Integer groupId) {
		this.groupId = groupId;
	}

	public String getOwner() {
		return this.owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public String getGroupName() {
		return this.groupName;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public long getCreated() {
		return this.created;
	}

	public Long getUpdated() {
		return this.updated;
	}

	public void setUpdated(Long updated) {
		this.updated = updated;
	}

	public byte[] getReference() {
		return this.reference;
	}

	public void setReference(byte[] reference) {
		this.reference = reference;
	}

	public boolean isOpen() {
		return this.isOpen;
	}

	public void setIsOpen(boolean isOpen) {
		this.isOpen = isOpen;
	}

	public ApprovalThreshold getApprovalThreshold() {
		return this.approvalThreshold;
	}

	public void setApprovalThreshold(ApprovalThreshold approvalThreshold) {
		this.approvalThreshold = approvalThreshold;
	}

	public int getMinimumBlockDelay() {
		return this.minimumBlockDelay;
	}

	public int getMaximumBlockDelay() {
		return this.maximumBlockDelay;
	}

	public int getCreationGroupId() {
		return this.creationGroupId;
	}

	public String getReducedGroupName() {
		return this.reducedGroupName;
	}

	public void setReducedGroupName(String reducedGroupName) {
		this.reducedGroupName = reducedGroupName;
	}

	// This is for API call GET /groups/member/{address}

	public Boolean isAdmin() {
		return this.isAdmin;
	}

	public void setIsAdmin(boolean isAdmin) {
		this.isAdmin = isAdmin;
	}

	public String getOwnerPrimaryName() {
		return this.ownerPrimaryName;
	}

	public void setOwnerPrimaryName(String ownerPrimaryName) {
		this.ownerPrimaryName = ownerPrimaryName;
	}

}
