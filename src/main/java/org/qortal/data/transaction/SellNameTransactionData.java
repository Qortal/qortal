package org.qortal.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
public class SellNameTransactionData extends TransactionData {

	// Properties

	@Schema(description = "owner's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] ownerPublicKey;

	@Schema(description = "which name to sell", example = "my-name")
	private String name;

	@Schema(description = "selling price", example = "123.456")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long amount;

	@Schema(description = "if sale is for a specific buyer", example = "true")
	private boolean isPrivateSale;

	@Schema(description = "intended buyer's address", example = "QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v")
	private String saleRecipient;

	// Constructors

	// For JAXB
	protected SellNameTransactionData() {
		super(TransactionType.SELL_NAME);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
	}

	public SellNameTransactionData(BaseTransactionData baseTransactionData, String name, long amount) {
		super(TransactionType.SELL_NAME, baseTransactionData);

		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.name = name;
		this.amount = amount;
		this.isPrivateSale = false;
		this.saleRecipient = null;
	}

	public SellNameTransactionData(BaseTransactionData baseTransactionData, String name, long amount, boolean isPrivateSale, String saleRecipient) {
		super(TransactionType.SELL_NAME, baseTransactionData);

		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.name = name;
		this.amount = amount;
		this.isPrivateSale = isPrivateSale;
		this.saleRecipient = saleRecipient;
	}

	// Getters / setters

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public String getName() {
		return this.name;
	}

	public long getAmount() {
		return this.amount;
	}

	public boolean getIsPrivateSale() {
		return this.isPrivateSale;
	}

	public String getSaleRecipient() {
		return this.isPrivateSale ? this.saleRecipient : null;
	}

}
