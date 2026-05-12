package org.qortal.transaction;

import com.google.common.base.Utf8;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.data.asset.AssetData;
import org.qortal.data.transaction.IssueAssetTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Amounts;
import org.qortal.utils.Unicode;

import java.util.Collections;
import java.util.List;

public class IssueAssetTransaction extends Transaction {

	// Properties

	private IssueAssetTransactionData issueAssetTransactionData;

	// Constructors

	public IssueAssetTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.issueAssetTransactionData = (IssueAssetTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getIssuer() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check name size bounds
		String assetName = this.issueAssetTransactionData.getAssetName();
		int assetNameLength = Utf8.encodedLength(assetName);
		if (assetNameLength < Asset.MIN_NAME_SIZE || assetNameLength > Asset.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check name is in normalized form (no leading/trailing whitespace, etc.)
		if (!assetName.equals(Unicode.normalize(assetName)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		// Check description size bounds
		int assetDescriptionlength = Utf8.encodedLength(this.issueAssetTransactionData.getDescription());
		if (assetDescriptionlength < 1 || assetDescriptionlength > Asset.MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		// Check data field
		String data = this.issueAssetTransactionData.getData();
		int dataLength = Utf8.encodedLength(data);
		if (data == null || dataLength < 1 || dataLength > Asset.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Check quantity
		if (this.issueAssetTransactionData.getQuantity() < 1 || this.issueAssetTransactionData.getQuantity() > Asset.MAX_QUANTITY)
			return ValidationResult.INVALID_QUANTITY;

		// Check quantity versus indivisibility
		if (!this.issueAssetTransactionData.isDivisible() && this.issueAssetTransactionData.getQuantity() % Amounts.MULTIPLIER != 0)
			return ValidationResult.INVALID_QUANTITY;

		Account issuer = getIssuer();

		// Check issuer has enough funds
		if (issuer.getConfirmedBalance(Asset.QORT) < this.issueAssetTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Check the name isn't already taken
		if (this.repository.getAssetRepository().reducedAssetNameExists(this.issueAssetTransactionData.getReducedAssetName()))
			return ValidationResult.ASSET_ALREADY_EXISTS;

		return ValidationResult.OK;
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public void process() throws DataException {
		// Special case for genesis assets
		String assetName = this.issueAssetTransactionData.getAssetName();
		boolean isGenesisAsset = (assetName.equals("QORT") ||
								 assetName.equals("Legacy-QORA") ||
								 assetName.equals("QORT-from-QORA") ||
								 assetName.equals("TEST") ||
								 assetName.equals("OTHER") ||
								 assetName.equals("GOLD"));
		
		if (isGenesisAsset && this.repository.getBlockRepository().getBlockchainHeight() == 0) {
			// Determine the correct ID for this genesis asset
			Long correctAssetId = null;
			if (assetName.equals("QORT")) {
				correctAssetId = 0L;
			} else if (assetName.equals("Legacy-QORA")) {
				correctAssetId = 1L;
			} else if (assetName.equals("QORT-from-QORA")) {
				correctAssetId = 2L;
			} else if (assetName.equals("TEST")) {
				correctAssetId = 3L;
			} else if (assetName.equals("OTHER")) {
				correctAssetId = 4L;
			} else if (assetName.equals("GOLD")) {
				correctAssetId = 5L;
			}
			
			System.out.println("DEBUG: IssueAssetTransaction.process() - Processing genesis asset: " + assetName + " with correct ID: " + correctAssetId);
			
			// Check if asset already exists
			try {
				AssetData existingAsset = this.repository.getAssetRepository().fromAssetName(assetName);
				if (existingAsset != null) {
					// Use existing asset
					System.out.println("DEBUG: IssueAssetTransaction.process() - Asset " + assetName + " already exists with ID: " + existingAsset.getAssetId());
					this.issueAssetTransactionData.setAssetId(existingAsset.getAssetId());
				} else {
					// Create asset with correct ID
					System.out.println("DEBUG: IssueAssetTransaction.process() - Creating asset " + assetName + " with ID: " + correctAssetId);
					AssetData genesisAsset = new AssetData(correctAssetId, this.getCreator().getAddress(),
						this.issueAssetTransactionData.getAssetName(),
						this.issueAssetTransactionData.getDescription(),
						this.issueAssetTransactionData.getQuantity(),
						this.issueAssetTransactionData.isDivisible(),
						this.issueAssetTransactionData.getData(),
						this.issueAssetTransactionData.isUnspendable(),
						0, // creationGroupId
						new byte[0], // reference
						this.issueAssetTransactionData.getReducedAssetName());
					this.repository.getAssetRepository().save(genesisAsset);
					this.issueAssetTransactionData.setAssetId(genesisAsset.getAssetId());
					System.out.println("DEBUG: IssueAssetTransaction.process() - Created asset " + assetName + " with actual ID: " + genesisAsset.getAssetId());
				}
			} catch (DataException e) {
				// Create asset with correct ID
				System.out.println("DEBUG: IssueAssetTransaction.process() - Exception checking asset " + assetName + ", creating with ID: " + correctAssetId);
				AssetData genesisAsset = new AssetData(correctAssetId, this.getCreator().getAddress(),
					this.issueAssetTransactionData.getAssetName(),
					this.issueAssetTransactionData.getDescription(),
					this.issueAssetTransactionData.getQuantity(),
					this.issueAssetTransactionData.isDivisible(),
					this.issueAssetTransactionData.getData(),
					this.issueAssetTransactionData.isUnspendable(),
					0, // creationGroupId
					new byte[0], // reference
					this.issueAssetTransactionData.getReducedAssetName());
				this.repository.getAssetRepository().save(genesisAsset);
				this.issueAssetTransactionData.setAssetId(genesisAsset.getAssetId());
				System.out.println("DEBUG: IssueAssetTransaction.process() - Created asset " + assetName + " with actual ID: " + genesisAsset.getAssetId());
			}
		} else if (isGenesisAsset) {
			// For genesis assets after height 0, check if they already exist with the correct ID
			Long correctAssetId = null;
			if (assetName.equals("QORT")) {
				correctAssetId = 0L;
			} else if (assetName.equals("Legacy-QORA")) {
				correctAssetId = 1L;
			} else if (assetName.equals("QORT-from-QORA")) {
				correctAssetId = 2L;
			} else if (assetName.equals("TEST")) {
				correctAssetId = 3L;
			} else if (assetName.equals("OTHER")) {
				correctAssetId = 4L;
			} else if (assetName.equals("GOLD")) {
				correctAssetId = 5L;
			}
			
			System.out.println("DEBUG: IssueAssetTransaction.process() - Processing genesis asset after height 0: " + assetName + " with correct ID: " + correctAssetId);
			
			// Check if asset already exists
			try {
				AssetData existingAsset = this.repository.getAssetRepository().fromAssetName(assetName);
				if (existingAsset != null && existingAsset.getAssetId() == correctAssetId) {
					// Use existing asset
					System.out.println("DEBUG: IssueAssetTransaction.process() - Asset " + assetName + " already exists with correct ID: " + existingAsset.getAssetId());
					this.issueAssetTransactionData.setAssetId(existingAsset.getAssetId());
					return; // Don't create a new asset
				}
			} catch (DataException e) {
				// Asset doesn't exist, continue with normal processing
			}
		} else {
			// Issue asset normally
			Asset asset = new Asset(this.repository, this.issueAssetTransactionData);
			asset.issue();

			// Note newly assigned asset ID in our transaction record
			this.issueAssetTransactionData.setAssetId(asset.getAssetData().getAssetId());
		}

		// Add asset to issuer
		Account issuer = this.getIssuer();
		issuer.setConfirmedBalance(this.issueAssetTransactionData.getAssetId(), this.issueAssetTransactionData.getQuantity());

		// Save this transaction with newly assigned assetId
		this.repository.getTransactionRepository().save(this.issueAssetTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Check if this is a genesis asset (QORT, Legacy-QORA, etc.)
		// Genesis assets should not be deleted during orphaning
		String assetName = this.issueAssetTransactionData.getAssetName();
		boolean isGenesisAsset = (assetName.equals("QORT") ||
		                         assetName.equals("Legacy-QORA") ||
		                         assetName.equals("QORT-from-QORA") ||
		                         assetName.equals("TEST") ||
		                         assetName.equals("OTHER") ||
		                         assetName.equals("GOLD"));
		
		if (!isGenesisAsset) {
			// Remove asset from issuer
			Account issuer = this.getIssuer();
			issuer.deleteBalance(this.issueAssetTransactionData.getAssetId());

			// Deissue asset
			Asset asset = new Asset(this.repository, this.issueAssetTransactionData.getAssetId());
			asset.deissue();

			// Remove assigned asset ID from transaction info
			this.issueAssetTransactionData.setAssetId(null);

			// Save this transaction, with removed assetId
			this.repository.getTransactionRepository().save(this.issueAssetTransactionData);
		}
	}

}
