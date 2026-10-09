package org.qortal.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.AccessMode;
import org.qortal.crypto.Crypto;
import org.qortal.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
public class ChatTransactionData extends TransactionData {

	// Properties
	@Schema(description = "sender's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] senderPublicKey;

	@Schema(accessMode = AccessMode.READ_ONLY)
	private String sender;

	@Schema(accessMode = AccessMode.READ_ONLY)
	private int nonce;

	private String recipient; // can be null

	private byte[] chatReference; // can be null

	@Schema(description = "raw message data, possibly UTF8 text", example = "2yGEbwRFyhPZZckKA")
	private byte[] data;

	private boolean isText;
	private boolean isEncrypted;

	// Constructors

	// For JAXB
	protected ChatTransactionData() {
		super(TransactionType.CHAT);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.senderPublicKey;

		if (this.senderPublicKey != null)
			this.sender = Crypto.toAddress(this.senderPublicKey);
	}

	public ChatTransactionData(BaseTransactionData baseTransactionData,
			String sender, int nonce, String recipient, byte[] chatReference, byte[] data, boolean isText, boolean isEncrypted) {
		super(TransactionType.CHAT, baseTransactionData);

		this.senderPublicKey = baseTransactionData.creatorPublicKey;
		this.sender = sender;
		this.nonce = nonce;
		this.recipient = recipient;
		this.chatReference = chatReference;
		this.data = data;
		this.isText = isText;
		this.isEncrypted = isEncrypted;
	}

	// Getters/Setters

	public byte[] getSenderPublicKey() {
		return this.senderPublicKey;
	}

	public String getSender() {
		return this.sender;
	}

	public int getNonce() {
		return this.nonce;
	}

	public void setNonce(int nonce) {
		this.nonce = nonce;
	}

	public String getRecipient() {
		return this.recipient;
	}

	public byte[] getChatReference() {
		return this.chatReference;
	}

	public void setChatReference(byte[] chatReference) {
		this.chatReference = chatReference;
	}

	public byte[] getData() {
		return this.data;
	}

	public boolean getIsText() {
		return this.isText;
	}

	public boolean getIsEncrypted() {
		return this.isEncrypted;
	}

}
