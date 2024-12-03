package org.qortal.data.block;

import org.qortal.account.Account;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import java.util.Arrays;

@XmlAccessorType(XmlAccessType.FIELD)
public class BlockSummaryData {

	// Properties
	private int height;
	private byte[] signature;
	private byte[] minterPublicKey;

	// Optional, set during construction
	private Integer onlineAccountsCount;
	private Long timestamp;
	private Integer transactionCount;
	private byte[] reference;

	// Optional, set after construction
	private Integer minterLevel;

	// Constructors

	protected BlockSummaryData() {
	}

	/** Constructor typically populated with fields from HeightV2Message */
	public BlockSummaryData(int height, byte[] signature, byte[] minterPublicKey, long timestamp) {
		this.height = height;
		this.signature = signature;
		this.minterPublicKey = minterPublicKey;
		this.timestamp = timestamp;
	}

	/** Constructor typically populated with fields from BlockSummariesMessage */
	public BlockSummaryData(int height, byte[] signature, byte[] minterPublicKey, int onlineAccountsCount) {
		this.height = height;
		this.signature = signature;
		this.minterPublicKey = minterPublicKey;
		this.onlineAccountsCount = onlineAccountsCount;
	}

	/** Constructor typically populated with fields from BlockSummariesV2Message */
	public BlockSummaryData(int height, byte[] signature, byte[] minterPublicKey, Integer onlineAccountsCount,
							Long timestamp, Integer transactionCount, byte[] reference) {
		this.height = height;
		this.signature = signature;
		this.minterPublicKey = minterPublicKey;
		this.onlineAccountsCount = onlineAccountsCount;
		this.timestamp = timestamp;
		this.transactionCount = transactionCount;
		this.reference = reference;
	}

	public BlockSummaryData(BlockData blockData) {
		this.height = blockData.getHeight();
		this.signature = blockData.getSignature();
		this.minterPublicKey = blockData.getMinterPublicKey();
		this.onlineAccountsCount = blockData.getOnlineAccountsCount();

		this.timestamp = blockData.getTimestamp();
		this.transactionCount = blockData.getTransactionCount();
		this.reference = blockData.getReference();
	}

	// Getters / setters

	public int getHeight() {
		return this.height;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public byte[] getMinterPublicKey() {
		return this.minterPublicKey;
	}

	public Integer getOnlineAccountsCount() {
		return this.onlineAccountsCount;
	}

	public Long getTimestamp() {
		return this.timestamp;
	}

	public Integer getTransactionCount() {
		return this.transactionCount;
	}

	public byte[] getReference() {
		return this.reference;
	}

	public Integer getMinterLevel() {
		return this.minterLevel;
	}

	public void setMinterLevel(Integer minterLevel) {
		this.minterLevel = minterLevel;
	}

	@XmlElement(name = "minterAddress")
	public String getMinterAddress() {
		if (this.minterPublicKey == null) {
			return "Unknown";
		}
		try (final Repository repository = RepositoryManager.getRepository()) {
			return Account.getRewardShareMintingAddress(repository, this.minterPublicKey);
		} catch (DataException e) {
			// Log the exception as needed
			return "Unknown";
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o == null || getClass() != o.getClass())
			return false;

		BlockSummaryData otherBlockSummary = (BlockSummaryData) o;
		if (this.getSignature() == null || otherBlockSummary.getSignature() == null)
			return false;

		// Treat two block summaries as equal if they have matching signatures
		return Arrays.equals(this.getSignature(), otherBlockSummary.getSignature());
	}

}
