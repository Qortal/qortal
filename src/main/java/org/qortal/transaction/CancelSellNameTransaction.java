package org.qortal.transaction;

import com.google.common.base.Utf8;
import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.controller.repository.NamesDatabaseIntegrityCheck;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.CancelSellNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.naming.Name;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Unicode;

import java.util.Collections;
import java.util.List;

public class CancelSellNameTransaction extends Transaction {

	// Properties
	private CancelSellNameTransactionData cancelSellNameTransactionData;

	// Constructors
	public CancelSellNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);
		this.cancelSellNameTransactionData = (CancelSellNameTransactionData) this.transactionData;
	}

	// More information
	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList(); // No recipient address for this transaction
	}

	// Navigation
	public Account getOwner() {
		return this.getCreator(); // The creator of the transaction is the owner
	}

	// Processing
	@Override
	public ValidationResult isValid() throws DataException {
		String name = this.cancelSellNameTransactionData.getName();

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < 1 || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check name is in normalized form (no leading/trailing whitespace, etc.)
		if (!name.equals(Unicode.normalize(name)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		// Retrieve name data from repository
		NameData nameData = this.repository.getNameRepository().fromName(name);

		// Check if name exists
		if (nameData == null)
			return ValidationResult.NAME_DOES_NOT_EXIST;

		// Check name is currently for sale
		if (!nameData.isForSale()) {
			// Validate after feature-trigger timestamp, due to potential double cancellations
			if (this.cancelSellNameTransactionData.getTimestamp() > BlockChain.getInstance().getCancelSellNameValidationTimestamp())
				return ValidationResult.NAME_NOT_FOR_SALE;
		}

		// Check if transaction creator matches the name's current owner
		Account owner = getOwner();
		if (!owner.getAddress().equals(nameData.getOwner()))
			return ValidationResult.INVALID_NAME_OWNER;

		// Check if issuer has enough balance for the transaction fee
		if (owner.getConfirmedBalance(Asset.QORT) < cancelSellNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK; // All validations passed
	}

	@Override
	public void preProcess() throws DataException {
		// Direct access to class field, no need to redeclare
		NamesDatabaseIntegrityCheck namesDatabaseIntegrityCheck = new NamesDatabaseIntegrityCheck();
		namesDatabaseIntegrityCheck.rebuildName(this.cancelSellNameTransactionData.getName(), this.repository);
	}

	@Override
	public void process() throws DataException {
		// Update the Name to reflect the cancellation of the sale
		Name name = new Name(this.repository, cancelSellNameTransactionData.getName());
		name.cancelSell(cancelSellNameTransactionData);

		// Save this transaction with updated "name reference"
		this.repository.getTransactionRepository().save(cancelSellNameTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert the cancellation of the name sale
		Name name = new Name(this.repository, cancelSellNameTransactionData.getName());
		name.uncancelSell(cancelSellNameTransactionData);

		// Save the transaction with the reverted "name reference"
		this.repository.getTransactionRepository().save(cancelSellNameTransactionData);
	}
}
