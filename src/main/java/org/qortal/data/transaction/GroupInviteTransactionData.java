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
//JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("GROUP_INVITE")
public class GroupInviteTransactionData extends TransactionData {

	// Properties
	@Schema(description = "admin's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] adminPublicKey;
	@Schema(description = "group ID")
	private int groupId;
	@Schema(description = "invitee's address", example = "QixPbJUwsaHsVEofJdozU9zgVqkK6aYhrK")
	private String invitee;
	@Schema(description = "invitation lifetime in seconds")
	private int timeToLive;
	@Schema(description = "fee required to join the group", example = "0")
	private long joinFee;
	/** Reference to JOIN_GROUP transaction, used to rebuild this join request during orphaning. */
	// No need to ever expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private byte[] joinReference;
	/** Invitee's previous defaultGroupId, set only if this transaction changed it from NO_GROUP. */
	// No need to expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private Integer previousGroupId;

	// Constructors

	// For JAXB
	protected GroupInviteTransactionData() {
		super(TransactionType.GROUP_INVITE);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.adminPublicKey;
	}

	/** From repository */
	public GroupInviteTransactionData(BaseTransactionData baseTransactionData,
			int groupId, String invitee, int timeToLive, long joinFee, byte[] joinReference, Integer previousGroupId) {
		super(TransactionType.GROUP_INVITE, baseTransactionData);

		this.adminPublicKey = baseTransactionData.creatorPublicKey;
		this.groupId = groupId;
		this.invitee = invitee;
		this.timeToLive = timeToLive;
		this.joinFee = joinFee;
		this.joinReference = joinReference;
		this.previousGroupId = previousGroupId;
	}

	/** From network/API */
	public GroupInviteTransactionData(BaseTransactionData baseTransactionData, int groupId, String invitee, int timeToLive, long joinFee) {
		this(baseTransactionData, groupId, invitee, timeToLive, joinFee, null, null);
	}

	// Getters / setters

	public byte[] getAdminPublicKey() {
		return this.adminPublicKey;
	}

	public int getGroupId() {
		return this.groupId;
	}

	public String getInvitee() {
		return this.invitee;
	}

	public int getTimeToLive() {
		return this.timeToLive;
	}

	public long getJoinFee() {
		return this.joinFee;
	}

	public void setJoinFee(long joinFee) {
		this.joinFee = joinFee;
	}

	public byte[] getJoinReference() {
		return this.joinReference;
	}

	public void setJoinReference(byte[] joinReference) {
		this.joinReference = joinReference;
	}

	public Integer getPreviousGroupId() {
		return this.previousGroupId;
	}

	public void setPreviousGroupId(Integer previousGroupId) {
		this.previousGroupId = previousGroupId;
	}

}