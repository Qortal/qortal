package org.qortal.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.AccessMode;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortal.block.GenesisBlock;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.Unicode;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(
	allOf = {
		TransactionData.class
	}
)
//JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("CREATE_GROUP")
public class CreateGroupTransactionData extends TransactionData {

	// Properties
	// groupId can be null but assigned during save() or during load from repository
	@Schema(accessMode = AccessMode.READ_ONLY, description = "assigned group ID")
	private Integer groupId = null;

	@Schema(description = "group name", example = "miner-group")
	private String groupName;

	@Schema(description = "short description of group", example = "this group is for block miners")
	private String description;

	@Schema(description = "whether anyone can join group (open) or group is invite-only (closed)", example = "true")
	private boolean isOpen;

	@Schema(description = "how many group admins are required to approve group member transactions")
	private ApprovalThreshold approvalThreshold;

	@Schema(description = "minimum block delay before approval takes effect")
	private int minimumBlockDelay;

	@Schema(description = "maximum block delay before which transaction approval must be reached")
	private int maximumBlockDelay;

	@Schema(description = "fee required to join the group", example = "0")
	private long joinFee;

	// For internal use
	@XmlTransient
	@Schema(hidden = true)
	private String reducedGroupName;

	// Constructors

	// For JAXB
	protected CreateGroupTransactionData() {
		super(TransactionType.CREATE_GROUP);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		/*
		 *  If we're being constructed as part of the genesis block info inside blockchain config
		 *  then we need to construct 'reduced' group name.
		 */
		if (parent instanceof GenesisBlock.GenesisInfo && this.reducedGroupName == null)
			this.reducedGroupName = Unicode.sanitize(this.groupName);
	}

	/** From repository */
	public CreateGroupTransactionData(BaseTransactionData baseTransactionData,
			String groupName, String description, boolean isOpen,
			ApprovalThreshold approvalThreshold, int minimumBlockDelay, int maximumBlockDelay, long joinFee,
			Integer groupId, String reducedGroupName) {
		super(TransactionType.CREATE_GROUP, baseTransactionData);

		this.groupName = groupName;
		this.description = description;
		this.isOpen = isOpen;
		this.approvalThreshold = approvalThreshold;
		this.minimumBlockDelay = minimumBlockDelay;
		this.maximumBlockDelay = maximumBlockDelay;
		this.joinFee = joinFee;
		this.groupId = groupId;
		this.reducedGroupName = reducedGroupName;
	}

	/** From network/API */
	public CreateGroupTransactionData(BaseTransactionData baseTransactionData,
			String groupName, String description, boolean isOpen,
			ApprovalThreshold approvalThreshold, int minimumBlockDelay, int maximumBlockDelay, long joinFee) {
		this(baseTransactionData, groupName, description, isOpen, approvalThreshold, minimumBlockDelay,
				maximumBlockDelay, joinFee, null, Unicode.sanitize(groupName));
	}

	// Getters / setters

	public String getGroupName() {
		return this.groupName;
	}

	public String getDescription() {
		return this.description;
	}

	public boolean isOpen() {
		return this.isOpen;
	}

	public ApprovalThreshold getApprovalThreshold() {
		return this.approvalThreshold;
	}

	public int getMinimumBlockDelay() {
		return this.minimumBlockDelay;
	}

	public int getMaximumBlockDelay() {
		return this.maximumBlockDelay;
	}

	public Integer getGroupId() {
		return this.groupId;
	}

	public void setGroupId(Integer groupId) {
		this.groupId = groupId;
	}

	public String getReducedGroupName() {
		return this.reducedGroupName;
	}

	// Re-expose creatorPublicKey for this transaction type for JAXB
	@XmlElement(name = "creatorPublicKey")
	@Schema(name = "creatorPublicKey", description = "group creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	public byte[] getGroupCreatorPublicKey() {
		return this.creatorPublicKey;
	}

	@XmlElement(name = "creatorPublicKey")
	@Schema(name = "creatorPublicKey", description = "group creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	public void setGroupCreatorPublicKey(byte[] creatorPublicKey) {
		this.creatorPublicKey = creatorPublicKey;
	}

	public long getJoinFee() {
		return this.joinFee;
	}

	public void setJoinFee(long joinFee) {
		this.joinFee = joinFee;
	}

}
