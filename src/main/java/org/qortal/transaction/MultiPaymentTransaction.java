package org.qortal.transaction;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.MultiPaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.payment.Payment;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.List;
import java.util.stream.Collectors;

public class MultiPaymentTransaction extends Transaction {

	// Properties
	private MultiPaymentTransactionData multiPaymentTransactionData;

	// Useful constants
	private static final int MAX_PAYMENTS_COUNT = 400;

	// Constructors

	public MultiPaymentTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.multiPaymentTransactionData = (MultiPaymentTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return this.multiPaymentTransactionData.getPayments().stream().map(PaymentData::getRecipient).collect(Collectors.toList());
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		List<PaymentData> payments = this.multiPaymentTransactionData.getPayments();

		// Check number of payments
		if (payments.isEmpty() || payments.size() > MAX_PAYMENTS_COUNT)
			return ValidationResult.INVALID_PAYMENTS_COUNT;

		Account sender = getSender();

		// Check sender has enough funds for fee
		if (sender.getConfirmedBalance(Asset.QORT) < this.multiPaymentTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return new Payment(this.repository).isValid(this.multiPaymentTransactionData.getSenderPublicKey(), payments, this.multiPaymentTransactionData.getFee());
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		List<PaymentData> payments = this.multiPaymentTransactionData.getPayments();

		return new Payment(this.repository).isProcessable(this.multiPaymentTransactionData.getSenderPublicKey(), payments, this.multiPaymentTransactionData.getFee());
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public void process() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).process(this.multiPaymentTransactionData.getSenderPublicKey(), this.multiPaymentTransactionData.getPayments());
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// Wrap and delegate reference processing to Payment class. Always update recipients' last references regardless of asset.
		new Payment(this.repository).processReferencesAndFees(this.multiPaymentTransactionData.getSenderPublicKey(), this.multiPaymentTransactionData.getPayments(),
				this.multiPaymentTransactionData.getFee(), this.multiPaymentTransactionData.getSignature(), true);
	}

	@Override
	public void orphan() throws DataException {
		// Wrap and delegate payment processing to Payment class. Always revert recipients' last references regardless of asset.
		new Payment(this.repository).orphan(this.multiPaymentTransactionData.getSenderPublicKey(), this.multiPaymentTransactionData.getPayments());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Wrap and delegate reference processing to Payment class. Always revert recipients' last references regardless of asset.
		new Payment(this.repository).orphanReferencesAndFees(this.multiPaymentTransactionData.getSenderPublicKey(), this.multiPaymentTransactionData.getPayments(),
				this.multiPaymentTransactionData.getFee(), this.multiPaymentTransactionData.getSignature(), this.multiPaymentTransactionData.getReference(), true);
	}

}
