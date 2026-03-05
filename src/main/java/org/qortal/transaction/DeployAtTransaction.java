package org.qortal.transaction;

import com.google.common.base.Utf8;
import org.ciyam.at.MachineState;
import org.ciyam.at.Timestamp;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.at.AT;
import org.qortal.at.QortalATAPI;
import org.qortal.at.QortalAtLoggerFactory;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.data.asset.AssetData;
import org.qortal.data.at.ATData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;

import java.util.Collections;
import java.util.List;

public class DeployAtTransaction extends Transaction {

	// Properties
	private DeployAtTransactionData deployAtTransactionData;

	// Other useful constants
	public static final int MAX_NAME_SIZE = 200;
	public static final int MAX_DESCRIPTION_SIZE = 2000;
	public static final int MAX_AT_TYPE_SIZE = 200;
	public static final int MAX_TAGS_SIZE = 200;
	public static final int MAX_CREATION_BYTES_SIZE = 4096;
	public static final int MAX_CODE_BYTES_LENGTH = 1024;
	public static final int MAX_AT_STATE_LENGTH = 1024;

	private final static List<String> APPROVED_CODE_HASHES
		= List.of(
			//q-fund version 1 - refund
			"9gS2L74FdaG3zuEeYv815xVyHkhvLguq7ZGD6pf24i8F",

			//q-fund version 2 - no refund
			"HaqJBVVr9gZqgARZ5UZd7EU9ybyvVK2fCo9sx3gMMFsr",

			//lottery
			"GA2GF79hfJeTy1TezS4sUZWS4vxJKs4qqwJ49h3NN8H9",

			//ltc
			"4KdJETRAdymE7dodDmJbf5d9L1bp4g5Nxky8m47TBkvA",

			//btc
			"7xmoVpA6aR7cm7E9XZXAz3BRMQufdkTiku5CGxouFjcT",

			//doge
			"FTe4QDjNre2vRrGJQLKgfNdVYahvbd8CnVdhir9Zk7D",

			//digi
			"GXPEHes39dxiasWDSAZXyhGjaT44LKvZ7cJnHYgBYtng",

			//raven
			"Amtrk89znaGrHqsRqtEhEKQZzKfbWAHdetB5SSN8cYL6",

			//pirate
			"HyKCg5oiwMKkRrZKk1e5gET27kfrEVUwCNXGQALXqg9x"

	);

	// Constructors

	public DeployAtTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.deployAtTransactionData = (DeployAtTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.deployAtTransactionData.getAtAddress());
	}

	/** Returns AT version from the header bytes */
	private short getVersion() {
		byte[] creationBytes = deployAtTransactionData.getCreationBytes();
		return (short) ((creationBytes[0] << 8) | (creationBytes[1] & 0xff)); // Big-endian
	}

	/** Make sure deployATTransactionData has an ATAddress */
	public static void ensureATAddress(DeployAtTransactionData deployAtTransactionData) throws DataException {
		if (deployAtTransactionData.getAtAddress() != null)
			return;

		// Use transaction transformer
		try {
			String atAddress = Crypto.toATAddress(TransactionTransformer.toBytesForSigning(deployAtTransactionData));
			deployAtTransactionData.setAtAddress(atAddress);
		} catch (TransformationException e) {
			throw new DataException("Unable to generate AT address");
		}
	}

	// Navigation

	public Account getATAccount() throws DataException {
		ensureATAddress(this.deployAtTransactionData);

		return new Account(this.repository, this.deployAtTransactionData.getAtAddress());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check name size bounds
		int nameLength = Utf8.encodedLength(this.deployAtTransactionData.getName());
		if (nameLength < 1 || nameLength > MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check description size bounds
		int descriptionlength = Utf8.encodedLength(this.deployAtTransactionData.getDescription());
		if (descriptionlength < 1 || descriptionlength > MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		// Check AT-type size bounds
		int atTypeLength = Utf8.encodedLength(this.deployAtTransactionData.getAtType());
		if (atTypeLength < 1 || atTypeLength > MAX_AT_TYPE_SIZE)
			return ValidationResult.INVALID_AT_TYPE_LENGTH;

		// Check tags size bounds
		int tagsLength = Utf8.encodedLength(this.deployAtTransactionData.getTags());
		if (tagsLength < 1 || tagsLength > MAX_TAGS_SIZE)
			return ValidationResult.INVALID_TAGS_LENGTH;

		// Check amount is positive
		if (this.deployAtTransactionData.getAmount() <= 0)
			return ValidationResult.NEGATIVE_AMOUNT;

		long assetId = this.deployAtTransactionData.getAssetId();
		AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		// Check asset even exists
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Unspendable assets are not valid
		if (assetData.isUnspendable())
			return ValidationResult.ASSET_NOT_SPENDABLE;

		// Check asset amount is integer if asset is not divisible
		if (!assetData.isDivisible() && this.deployAtTransactionData.getAmount() % Amounts.MULTIPLIER != 0)
			return ValidationResult.INVALID_AMOUNT;

		Account creator = this.getCreator();

		// Check creator has enough funds
		if (assetId == Asset.QORT) {
			// Simple case: amount and fee both in QORT
			long minimumBalance = this.deployAtTransactionData.getFee() + this.deployAtTransactionData.getAmount();

			if (creator.getConfirmedBalance(Asset.QORT) < minimumBalance)
				return ValidationResult.NO_BALANCE;
		} else {
			if (creator.getConfirmedBalance(Asset.QORT) < this.deployAtTransactionData.getFee())
				return ValidationResult.NO_BALANCE;

			if (creator.getConfirmedBalance(assetId) < this.deployAtTransactionData.getAmount())
				return ValidationResult.NO_BALANCE;
		}

		// Check version from creation bytes
		if (this.getVersion() < 2)
			return ValidationResult.INVALID_CREATION_BYTES;

		// Check creation bytes are valid (for v2+)
		ensureATAddress(this.deployAtTransactionData);

		// Just enough AT data to allow API to query initial balances, etc.
		String atAddress = this.deployAtTransactionData.getAtAddress();
		byte[] creatorPublicKey = this.deployAtTransactionData.getCreatorPublicKey();
		long creation = this.deployAtTransactionData.getTimestamp();
		ATData skeletonAtData = new ATData(atAddress, creatorPublicKey, creation, assetId);

		int height = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		long blockTimestamp = Timestamp.toLong(height, 0);

		QortalATAPI api = new QortalATAPI(repository, skeletonAtData, blockTimestamp);
		QortalAtLoggerFactory loggerFactory = QortalAtLoggerFactory.getInstance();

		try {
			MachineState state = new MachineState(api, loggerFactory, this.deployAtTransactionData.getCreationBytes());

			byte[] codeBytes = state.getCodeBytes();
			if (codeBytes == null || codeBytes.length > MAX_CODE_BYTES_LENGTH)
				return ValidationResult.INVALID_CREATION_BYTES;

			byte[] atStateBytes = state.toBytes();
			if (atStateBytes == null || atStateBytes.length > MAX_AT_STATE_LENGTH)
				return ValidationResult.INVALID_CREATION_BYTES;

			if(height > BlockChain.getInstance().getAtValidateHeight()) {
				byte[] codeHash = Crypto.digest(codeBytes);
				String codeHash58 = Base58.encode(codeHash);

				// if passed the validation feature trigger and the code hash has not been approved, then invalidate
				if(!APPROVED_CODE_HASHES.contains(codeHash58)) {
					return ValidationResult.INVALID_CREATION_BYTES;
				}
			}
		} catch (IllegalArgumentException e) {
			// Not valid
			return ValidationResult.INVALID_CREATION_BYTES;
		}

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		Account creator = getCreator();
		long assetId = this.deployAtTransactionData.getAssetId();

		// Check creator has enough funds
		if (assetId == Asset.QORT) {
			// Simple case: amount and fee both in QORT
			long minimumBalance = this.deployAtTransactionData.getFee() + this.deployAtTransactionData.getAmount();

			if (creator.getConfirmedBalance(Asset.QORT) < minimumBalance)
				return ValidationResult.NO_BALANCE;
		} else {
			if (creator.getConfirmedBalance(Asset.QORT) < this.deployAtTransactionData.getFee())
				return ValidationResult.NO_BALANCE;

			if (creator.getConfirmedBalance(assetId) < this.deployAtTransactionData.getAmount())
				return ValidationResult.NO_BALANCE;
		}

		// Check AT doesn't already exist
		if (this.repository.getATRepository().exists(this.deployAtTransactionData.getAtAddress()))
			return ValidationResult.AT_ALREADY_EXISTS;

		return ValidationResult.OK;
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public void process() throws DataException {
		ensureATAddress(this.deployAtTransactionData);

		// Deploy AT, saving into repository
		AT at = new AT(this.repository, this.deployAtTransactionData);
		at.deploy();

		long assetId = this.deployAtTransactionData.getAssetId();

		// Update creator's balance regarding initial payment to AT
		Account creator = getCreator();
		creator.modifyAssetBalance(assetId, - this.deployAtTransactionData.getAmount());

		// Update AT's reference, which also creates AT account
		Account atAccount = this.getATAccount();
		atAccount.setLastReference(this.deployAtTransactionData.getSignature());

		// Update AT's balance
		atAccount.setConfirmedBalance(assetId, this.deployAtTransactionData.getAmount());
	}

	@Override
	public void orphan() throws DataException {
		// Delete AT from repository
		AT at = new AT(this.repository, this.deployAtTransactionData);
		at.undeploy();

		long assetId = this.deployAtTransactionData.getAssetId();

		// Update creator's balance regarding initial payment to AT
		Account creator = getCreator();
		creator.modifyAssetBalance(assetId, this.deployAtTransactionData.getAmount());

		// Delete AT's account (and hence its balance)
		this.repository.getAccountRepository().delete(this.deployAtTransactionData.getAtAddress());
	}

}
