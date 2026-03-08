package org.qortal.data.block;

import com.google.common.primitives.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.io.Serializable;

// All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class BlockData implements Serializable {

	private static final long serialVersionUID = -7678329659124664620L;

	// Properties

	private byte[] signature;
	private int version;
	private byte[] reference;
	private int transactionCount;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long totalFees;

	private byte[] transactionsSignature;
	private Integer height;
	private long timestamp;
	private byte[] minterPublicKey;
	private byte[] minterSignature;
	private int atCount;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long atFees;

	private byte[] encodedOnlineAccounts;
	private int onlineAccountsCount;
	private Long onlineAccountsTimestamp;
	private byte[] onlineAccountsSignatures;

    private static final Logger LOGGER = LogManager.getLogger(BlockData.class);
	// Constructors

	// necessary for JAX-RS serialization
	protected BlockData() {
	}

	public BlockData(int version, byte[] reference, int transactionCount, long totalFees, byte[] transactionsSignature, Integer height, long timestamp,
			byte[] minterPublicKey, byte[] minterSignature, int atCount, long atFees,
			byte[] encodedOnlineAccounts, int onlineAccountsCount, Long onlineAccountsTimestamp, byte[] onlineAccountsSignatures) {
		this.version = version;
		this.reference = reference;
		this.transactionCount = transactionCount;
		this.totalFees = totalFees;
		this.transactionsSignature = transactionsSignature;
		this.height = height;
		this.timestamp = timestamp;
		this.minterPublicKey = minterPublicKey;
		this.minterSignature = minterSignature;
		this.atCount = atCount;
		this.atFees = atFees;
		this.encodedOnlineAccounts = encodedOnlineAccounts;
		this.onlineAccountsCount = onlineAccountsCount;
		this.onlineAccountsTimestamp = onlineAccountsTimestamp;
		this.onlineAccountsSignatures = onlineAccountsSignatures;

		if (this.minterSignature != null && this.transactionsSignature != null)
			this.signature = Bytes.concat(this.minterSignature, this.transactionsSignature);
		else
			this.signature = null;
	}

	public BlockData(int version, byte[] reference, int transactionCount, long totalFees, byte[] transactionsSignature, Integer height, long timestamp,
			byte[] minterPublicKey, byte[] minterSignature, int atCount, long atFees) {
		this(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp, minterPublicKey, minterSignature, atCount, atFees,
				null, 0, null, null);
	}

	public BlockData(BlockData other) {
		this.version = other.version;
		this.reference = other.reference;
		this.transactionCount = other.transactionCount;
		this.totalFees = other.totalFees;
		this.transactionsSignature = other.transactionsSignature;
		this.height = other.height;
		this.timestamp = other.timestamp;
		this.minterPublicKey = other.minterPublicKey;
		this.minterSignature = other.minterSignature;
		this.atCount = other.atCount;
		this.atFees = other.atFees;
		this.encodedOnlineAccounts = other.encodedOnlineAccounts;
		this.onlineAccountsCount = other.onlineAccountsCount;
		this.onlineAccountsTimestamp = other.onlineAccountsTimestamp;
		this.onlineAccountsSignatures = other.onlineAccountsSignatures;
		this.signature = other.signature;
	}

	// Getters/setters

	public byte[] getSignature() {
		return this.signature;
	}

	public void setSignature(byte[] signature) {
		this.signature = signature;
	}

	public int getVersion() {
		return this.version;
	}

	public byte[] getReference() {
		return this.reference;
	}

	public void setReference(byte[] reference) {
		this.reference = reference;
	}

	public int getTransactionCount() {
		return this.transactionCount;
	}

	public void setTransactionCount(int transactionCount) {
		this.transactionCount = transactionCount;
	}

	public long getTotalFees() {
		return this.totalFees;
	}

	public void setTotalFees(long totalFees) {
		this.totalFees = totalFees;
	}

	public byte[] getTransactionsSignature() {
		return this.transactionsSignature;
	}

	public void setTransactionsSignature(byte[] transactionsSignature) {
		this.transactionsSignature = transactionsSignature;
	}

	public Integer getHeight() {
		return this.height;
	}

	public void setHeight(Integer height) {
		this.height = height;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public byte[] getMinterPublicKey() {
		return this.minterPublicKey;
	}

	public byte[] getMinterSignature() {
		return this.minterSignature;
	}

	public void setMinterSignature(byte[] minterSignature) {
		this.minterSignature = minterSignature;
	}

	public int getATCount() {
		return this.atCount;
	}

	public void setATCount(int atCount) {
		this.atCount = atCount;
	}

	public long getATFees() {
		return this.atFees;
	}

	public void setATFees(long atFees) {
		this.atFees = atFees;
	}

	public byte[] getEncodedOnlineAccounts() {
		return this.encodedOnlineAccounts;
	}

	public void setEncodedOnlineAccounts(byte[] encodedOnlineAccounts) {
		this.encodedOnlineAccounts = encodedOnlineAccounts;
	}

	public int getOnlineAccountsCount() {
		return this.onlineAccountsCount;
	}

	public void setOnlineAccountsCount(int onlineAccountsCount) {
		this.onlineAccountsCount = onlineAccountsCount;
	}

	public Long getOnlineAccountsTimestamp() {
		return this.onlineAccountsTimestamp;
	}

	public void setOnlineAccountsTimestamp(Long onlineAccountsTimestamp) {
		this.onlineAccountsTimestamp = onlineAccountsTimestamp;
	}

	public byte[] getOnlineAccountsSignatures() {
		return this.onlineAccountsSignatures;
	}

	public void setOnlineAccountsSignatures(byte[] onlineAccountsSignatures) {
		this.onlineAccountsSignatures = onlineAccountsSignatures;
	}

	public int getOnlineAccountsSignaturesCount() {
		if (this.onlineAccountsSignatures != null && this.onlineAccountsSignatures.length > 0) {
			// Blocks use a single online accounts signature, so there is no need for this to be dynamic
			return 1;
		}
		return 0;
	}
	
	public boolean isTrimmed() {
		long onlineAccountSignaturesTrimmedTimestamp = NTP.getTime() - BlockChain.getInstance().getOnlineAccountSignaturesMaxLifetime();
		long currentTrimmableTimestamp = NTP.getTime() - Settings.getInstance().getAtStatesMaxLifetime();
		long blockTimestamp = this.getTimestamp();

        return blockTimestamp < onlineAccountSignaturesTrimmedTimestamp && blockTimestamp < currentTrimmableTimestamp;
	}

	public String getMinterAddressFromPublicKey() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return Account.getRewardShareMintingAddress(repository, this.minterPublicKey);
		} catch (DataException e) {
			return "Unknown";
		}
	}

	public int getMinterLevelFromPublicKey() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return Account.getRewardShareEffectiveMintingLevel(repository, this.minterPublicKey);
		} catch (DataException e) {
			return 0;
		}
	}
	
	// JAXB special

	@XmlElement(name = "minterAddress")
	protected String getMinterAddress() {
		return getMinterAddressFromPublicKey();
	}

	@XmlElement(name = "minterLevel")
	protected int getMinterLevel() {
		return getMinterLevelFromPublicKey();
	}
}
