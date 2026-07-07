package org.qortal.transaction;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.asset.Order;
import org.qortal.block.BlockChain;
import org.qortal.data.asset.AssetData;
import org.qortal.data.asset.OrderData;
import org.qortal.data.transaction.CreateAssetOrderTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.AssetRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Amounts;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

public class CreateAssetOrderTransaction extends Transaction {

	// Properties
	private CreateAssetOrderTransactionData createOrderTransactionData;

	// Constructors

	public CreateAssetOrderTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.createOrderTransactionData = (CreateAssetOrderTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Order getOrder() throws DataException {
		// orderId is the transaction signature
		OrderData orderData = this.repository.getAssetRepository().fromOrderId(this.createOrderTransactionData.getSignature());
		return new Order(this.repository, orderData);
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		long haveAssetId = this.createOrderTransactionData.getHaveAssetId();
		long wantAssetId = this.createOrderTransactionData.getWantAssetId();

		// Check have/want assets are not the same
		if (haveAssetId == wantAssetId)
			return ValidationResult.HAVE_EQUALS_WANT;

		// Check amount is positive
		if (this.createOrderTransactionData.getAmount() <= 0)
			return ValidationResult.NEGATIVE_AMOUNT;

		// Check price is positive
		if (this.createOrderTransactionData.getPrice() <= 0)
			return ValidationResult.NEGATIVE_PRICE;

		boolean boundsFixEnabled = this.repository.getBlockRepository().getBlockchainHeight() + 1 >= BlockChain.getInstance().getAssetOrderBoundsHeight();
		if (boundsFixEnabled) {
			if (this.createOrderTransactionData.getAmount() > Asset.MAX_QUANTITY)
				return ValidationResult.INVALID_AMOUNT;

			if (this.createOrderTransactionData.getPrice() > Asset.MAX_QUANTITY)
				return ValidationResult.INVALID_AMOUNT;
		}

		AssetRepository assetRepository = this.repository.getAssetRepository();

		// Check "have" asset exists
		AssetData haveAssetData = assetRepository.fromAssetId(haveAssetId);
		if (haveAssetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Check "want" asset exists
		AssetData wantAssetData = assetRepository.fromAssetId(wantAssetId);
		if (wantAssetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Unspendable assets are not tradable
		if (haveAssetData.isUnspendable() || wantAssetData.isUnspendable())
			return ValidationResult.ASSET_NOT_SPENDABLE;

		Account creator = getCreator();

		/*
		 * "amount" might be either have-asset or want-asset, whichever has the highest assetID.
		 * 
		 * e.g. with assetID 11 "GOLD":
		 * haveAssetId: 0 (QORT), wantAssetId: 11 (GOLD), amount: 123 (GOLD), price: 400 (QORT/GOLD)
		 * stake 49200 QORT, return 123 GOLD
		 * 
		 * haveAssetId: 11 (GOLD), wantAssetId: 0 (QORT), amount: 123 (GOLD), price: 400 (QORT/GOLD)
		 * stake 123 GOLD, return 49200 QORT
		 */
		boolean isAmountWantAsset = haveAssetId < wantAssetId;
		BigInteger amount = BigInteger.valueOf(this.createOrderTransactionData.getAmount());
		BigInteger price = BigInteger.valueOf(this.createOrderTransactionData.getPrice());

		BigInteger committedCost;
		BigInteger roundedCommittedCost;
		BigInteger maxOtherAmount;

		if (isAmountWantAsset) {
			// have/commit 49200 QORT, want/return 123 GOLD
			committedCost = amount.multiply(price).divide(Amounts.MULTIPLIER_BI);
			roundedCommittedCost = amount.multiply(price).add(Amounts.ROUNDING).divide(Amounts.MULTIPLIER_BI);
			maxOtherAmount = amount;
		} else {
			// have/commit 123 GOLD, want/return 49200 QORT
			committedCost = amount;
			roundedCommittedCost = committedCost;
			maxOtherAmount = amount.multiply(price).divide(Amounts.MULTIPLIER_BI);
		}

		Long committedCostAsLong = null;
		Long maxOtherAmountAsLong = null;
		if (boundsFixEnabled) {
			committedCostAsLong = toPositiveLongOrNull(roundedCommittedCost);
			if (committedCostAsLong == null)
				return ValidationResult.INVALID_AMOUNT;

			maxOtherAmountAsLong = toPositiveLongOrNull(maxOtherAmount);
			if (maxOtherAmountAsLong == null)
				return ValidationResult.INVALID_AMOUNT;
		}

		BigInteger divisibilityCommittedCost = boundsFixEnabled ? roundedCommittedCost : committedCost;

		// Check amount is integer if amount's asset is not divisible
		if (!haveAssetData.isDivisible() && divisibilityCommittedCost.mod(Amounts.MULTIPLIER_BI).signum() != 0)
			return ValidationResult.INVALID_AMOUNT;

		// Check total return from fulfilled order would be integer if return's asset is not divisible
		if (!wantAssetData.isDivisible() && maxOtherAmount.mod(Amounts.MULTIPLIER_BI).signum() != 0)
			return ValidationResult.INVALID_RETURN;

		// Check order creator has enough asset balance AFTER removing fee, in case asset is QORT
		// If asset is QORT then we need to check amount + fee in one go
		if (haveAssetId == Asset.QORT) {
			long committedCostLong = boundsFixEnabled ? committedCostAsLong : committedCost.longValue();

			if (boundsFixEnabled && Long.MAX_VALUE - committedCostLong < this.createOrderTransactionData.getFee())
				return ValidationResult.NO_BALANCE;

			// Check creator has enough funds for amount + fee in QORT
			if (creator.getConfirmedBalance(Asset.QORT) < committedCostLong + this.createOrderTransactionData.getFee())
				return ValidationResult.NO_BALANCE;
		} else {
			long committedCostLong = boundsFixEnabled ? committedCostAsLong : committedCost.longValue();

			// Check creator has enough funds for amount in whatever asset
			if (creator.getConfirmedBalance(haveAssetId) < committedCostLong)
				return ValidationResult.NO_BALANCE;

			// Check creator has enough funds for fee in QORT
			if (creator.getConfirmedBalance(Asset.QORT) < this.createOrderTransactionData.getFee())
				return ValidationResult.NO_BALANCE;
		}

		return ValidationResult.OK;
	}

	private static Long toPositiveLongOrNull(BigInteger value) {
		if (value.signum() <= 0 || value.bitLength() > 63)
			return null;

		return value.longValue();
	}

	@Override
	public void preProcess() throws DataException {
		// Nothing to do
	}

	@Override
	public void process() throws DataException {
		// Order Id is transaction's signature
		byte[] orderId = this.createOrderTransactionData.getSignature();

		// Process the order itself
		OrderData orderData = new OrderData(orderId, this.createOrderTransactionData.getCreatorPublicKey(),
				this.createOrderTransactionData.getHaveAssetId(), this.createOrderTransactionData.getWantAssetId(),
				this.createOrderTransactionData.getAmount(), this.createOrderTransactionData.getPrice(),
				this.createOrderTransactionData.getTimestamp());

		new Order(this.repository, orderData).process();
	}

	@Override
	public void orphan() throws DataException {
		// Order Id is transaction's signature
		byte[] orderId = this.createOrderTransactionData.getSignature();

		// Orphan the order itself
		OrderData orderData = this.repository.getAssetRepository().fromOrderId(orderId);
		new Order(this.repository, orderData).orphan();
	}

}
