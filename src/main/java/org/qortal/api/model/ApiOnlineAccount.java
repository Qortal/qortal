package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class ApiOnlineAccount {

	protected long timestamp;
	protected byte[] signature;
	protected byte[] rewardSharePublicKey;
	protected String minterAddress;
	protected String recipientAddress;
	protected int minterLevel;

	// Constructors

	// necessary for JAXB serialization
	protected ApiOnlineAccount() {
	}

	public ApiOnlineAccount(long timestamp, byte[] signature, byte[] rewardSharePublicKey, String minterAddress, String recipientAddress, int minterLevel) {
		this.timestamp = timestamp;
		this.signature = signature;
		this.rewardSharePublicKey = rewardSharePublicKey;
		this.minterAddress = minterAddress;
		this.recipientAddress = recipientAddress;
		this.minterLevel = minterLevel;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public byte[] getPublicKey() {
		return this.rewardSharePublicKey;
	}

	public String getMinterAddress() {
		return this.minterAddress;
	}

	public String getRecipientAddress() {
		return this.recipientAddress;
	}

	public int getMinterLevel() {
		return this.minterLevel;
	}

}
