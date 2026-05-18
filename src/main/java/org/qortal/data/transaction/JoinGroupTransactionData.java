package org.qortal.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortal.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("JOIN_GROUP")
public class JoinGroupTransactionData extends TransactionData {

	// Properties
	@Schema(description = "joiner's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] joinerPublicKey;
	@Schema(description = "which group to join", example = "my-group")
	private int groupId;
	@Schema(description = "fee to join group", example = "100000000")
	private Long joinFee;
	/** Reference to GROUP_INVITE transaction, used to rebuild invite during orphaning. */
	// No need to ever expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private byte[] inviteReference;
	/** Joiner's previous defaultGroupId, set only if this transaction changed it from NO_GROUP. */
	// No need to expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private Integer previousGroupId;

	// Constructors

	// For JAXB
	protected JoinGroupTransactionData() {
		super(TransactionType.JOIN_GROUP);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.joinerPublicKey;
	}

	/** From repository */
	public JoinGroupTransactionData(BaseTransactionData baseTransactionData, int groupId, Long joinFee, byte[] inviteReference, Integer previousGroupId) {
		super(TransactionType.JOIN_GROUP, baseTransactionData);

		this.joinerPublicKey = baseTransactionData.creatorPublicKey;
		this.groupId = groupId;
		this.joinFee = joinFee;
		this.inviteReference = inviteReference;
		this.previousGroupId = previousGroupId;
	}

	/** From network/API */
	public JoinGroupTransactionData(BaseTransactionData baseTransactionData, int groupId, Long joinFee) {
		this(baseTransactionData, groupId, joinFee, null, null);
	}

	// Getters / setters

	public byte[] getJoinerPublicKey() {
		return this.joinerPublicKey;
	}

	public int getGroupId() {
		return this.groupId;
	}

	public Long getJoinFee() {
		return this.joinFee;
	}

	public byte[] getInviteReference() {
		return this.inviteReference;
	}

	public void setInviteReference(byte[] inviteReference) {
		this.inviteReference = inviteReference;
	}

	public Integer getPreviousGroupId() {
		return this.previousGroupId;
	}

	public void setPreviousGroupId(Integer previousGroupId) {
		this.previousGroupId = previousGroupId;
	}

}
