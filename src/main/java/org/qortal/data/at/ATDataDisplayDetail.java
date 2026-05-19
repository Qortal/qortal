package org.qortal.data.at;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class ATDataDisplayDetail {

	// Properties
	private String ATAddress;
	private byte[] creatorPublicKey;
	private long creation;
	private boolean isSleeping;
	private boolean isFinished;
	private boolean hadFatalError;
	private boolean isFrozen;
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private Long frozenBalance;
	private String codeHash58;
	private String atName;

	// Constructors

	// necessary for JAXB serialization
	protected ATDataDisplayDetail() {
	}

	public ATDataDisplayDetail(String ATAddress, byte[] creatorPublicKey, long creation,
                               boolean isSleeping, boolean isFinished, boolean hadFatalError, boolean isFrozen, Long frozenBalance, String codeHash58, String atName) {
		this.ATAddress = ATAddress;
		this.creatorPublicKey = creatorPublicKey;
		this.creation = creation;
		this.isSleeping = isSleeping;
		this.isFinished = isFinished;
		this.hadFatalError = hadFatalError;
		this.isFrozen = isFrozen;
		this.frozenBalance = frozenBalance;
		this.codeHash58 = codeHash58;
		this.atName = atName;
	}

	// Getters / setters

	public String getATAddress() {
		return this.ATAddress;
	}

	public byte[] getCreatorPublicKey() {
		return this.creatorPublicKey;
	}

	public long getCreation() {
		return this.creation;
	}

	public boolean getIsSleeping() {
		return this.isSleeping;
	}

	public void setIsSleeping(boolean isSleeping) {
		this.isSleeping = isSleeping;
	}

	public boolean getIsFinished() {
		return this.isFinished;
	}

	public void setIsFinished(boolean isFinished) {
		this.isFinished = isFinished;
	}

	public boolean getHadFatalError() {
		return this.hadFatalError;
	}

	public void setHadFatalError(boolean hadFatalError) {
		this.hadFatalError = hadFatalError;
	}

	public boolean getIsFrozen() {
		return this.isFrozen;
	}

	public void setIsFrozen(boolean isFrozen) {
		this.isFrozen = isFrozen;
	}

	public Long getFrozenBalance() {
		return this.frozenBalance;
	}

	public void setFrozenBalance(Long frozenBalance) {
		this.frozenBalance = frozenBalance;
	}

	public String getCodeHash58() {
		return codeHash58;
	}

	public void setCodeHash58(String codeHash58) {
		this.codeHash58 = codeHash58;
	}
}
