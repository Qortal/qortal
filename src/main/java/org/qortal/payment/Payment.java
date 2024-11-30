package org.qortal.payment;

import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.PaymentData;
import org.qortal.data.asset.AssetData;
import org.qortal.data.at.ATData;
import org.qortal.repository.AssetRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.utils.Amounts;

import java.util.*;
import java.util.Map.Entry;

public class Payment {

    // Properties
    private Repository repository;

    // Constructors
    public Payment(Repository repository) {
        this.repository = repository;
    }

    // isValid

    /** Are payments valid? */
    public ValidationResult isValid(byte[] senderPublicKey, List<PaymentData> payments, long fee, boolean isZeroAmountValid) throws DataException {
        AssetRepository assetRepository = this.repository.getAssetRepository();

        // Check fee is positive or zero
        if (fee < 0)
            return ValidationResult.NEGATIVE_FEE;

        // Total up payment amounts by assetId
        Map<Long, Long> amountsByAssetId = new HashMap<>();
        amountsByAssetId.put(Asset.QORT, fee); // Add transaction fee to the map

        // Grab sender info
        Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

        // Check payments, and calculate amount total by assetId
        for (PaymentData paymentData : payments) {
            // Check amount is zero or positive
            if (paymentData.getAmount() < 0)
                return ValidationResult.NEGATIVE_AMOUNT;

            // Optional zero-amount check
            if (!isZeroAmountValid && paymentData.getAmount() <= 0)
                return ValidationResult.NEGATIVE_AMOUNT;

            // Check recipient address is valid
            if (!Crypto.isValidAddress(paymentData.getRecipient()))
                return ValidationResult.INVALID_ADDRESS;

            boolean recipientIsAT = Crypto.isValidAtAddress(paymentData.getRecipient());
            ATData atData = null;

            // Do not allow payments to finished/dead/nonexistent ATs
            if (recipientIsAT) {
                atData = this.repository.getATRepository().fromATAddress(paymentData.getRecipient());

                if (atData == null)
                    return ValidationResult.AT_UNKNOWN;

                if (atData.getIsFinished())
                    return ValidationResult.AT_IS_FINISHED;
            }

            AssetData assetData = assetRepository.fromAssetId(paymentData.getAssetId());
            // Check asset even exists
            if (assetData == null)
                return ValidationResult.ASSET_DOES_NOT_EXIST;

            // Do not allow non-owner asset holders to use asset
            if (assetData.isUnspendable() && !assetData.getOwner().equals(sender.getAddress()))
                return ValidationResult.ASSET_NOT_SPENDABLE;

            // If we're sending to an AT then assetId must match AT's assetId
            if (atData != null && atData.getAssetId() != paymentData.getAssetId())
                return ValidationResult.ASSET_DOES_NOT_MATCH_AT;

            // Check asset amount is integer if asset is not divisible
            if (!assetData.isDivisible() && paymentData.getAmount() % Amounts.MULTIPLIER != 0)
                return ValidationResult.INVALID_AMOUNT;

            // Set or add amount into amounts-by-asset map
            amountsByAssetId.compute(paymentData.getAssetId(), (assetId, amount) -> amount == null ? paymentData.getAmount() : amount + paymentData.getAmount());
        }

        // Check sender has enough of each asset
        for (Entry<Long, Long> pair : amountsByAssetId.entrySet())
            if (sender.getConfirmedBalance(pair.getKey()) < pair.getValue())
                return ValidationResult.NO_BALANCE;

        return ValidationResult.OK;
    }

    /** Are payments valid? */
    public ValidationResult isValid(byte[] senderPublicKey, List<PaymentData> payments, long fee) throws DataException {
        return isValid(senderPublicKey, payments, fee, false);
    }

    /** Is single payment valid? */
    public ValidationResult isValid(byte[] senderPublicKey, PaymentData paymentData, long fee, boolean isZeroAmountValid) throws DataException {
        return isValid(senderPublicKey, Collections.singletonList(paymentData), fee, isZeroAmountValid);
    }

    /** Is single payment valid? */
    public ValidationResult isValid(byte[] senderPublicKey, PaymentData paymentData, long fee) throws DataException {
        return isValid(senderPublicKey, paymentData, fee, false);
    }

    // process

    /** Multiple payment processing */
    public void process(byte[] senderPublicKey, List<PaymentData> payments) throws DataException {
        Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

        // Process all payments
        for (PaymentData paymentData : payments) {
            Account recipient = new Account(this.repository, paymentData.getRecipient());

            long assetId = paymentData.getAssetId();
            long amount = paymentData.getAmount();

            // Update sender's balance due to amount
            sender.modifyAssetBalance(assetId, -amount);

            // Update recipient's balance
            recipient.modifyAssetBalance(assetId, amount);
        }
    }

    /** Single payment processing */
    public void process(byte[] senderPublicKey, PaymentData paymentData) throws DataException {
        process(senderPublicKey, Collections.singletonList(paymentData));
    }

    // processReferenceAndFees

    /** Multiple payment reference processing */
    public void processReferencesAndFees(byte[] senderPublicKey, List<PaymentData> payments, long fee, byte[] signature, boolean alwaysInitializeRecipientReference)
            throws DataException {
        Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

        // Update sender's balance due to fee
        sender.modifyAssetBalance(Asset.QORT, -fee);

        // Update sender's reference
        sender.setLastReference(signature);

        // Process all recipients
        for (PaymentData paymentData : payments) {
            Account recipient = new Account(this.repository, paymentData.getRecipient());

            long assetId = paymentData.getAssetId();

            // For QORT amounts only: if recipient has no reference yet, then this is their starting reference
            if ((alwaysInitializeRecipientReference || assetId == Asset.QORT) && recipient.getLastReference() == null)
                recipient.setLastReference(signature);
        }
    }

    /** Multiple payment reference processing */
    public void processReferencesAndFees(byte[] senderPublicKey, PaymentData payment, long fee, byte[] signature, boolean alwaysInitializeRecipientReference)
            throws DataException {
        processReferencesAndFees(senderPublicKey, Collections.singletonList(payment), fee, signature, alwaysInitializeRecipientReference);
    }

    // orphan

    public void orphan(byte[] senderPublicKey, List<PaymentData> payments) throws DataException {
        Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

        // Orphan all payments
        for (PaymentData paymentData : payments) {
            Account recipient = new Account(this.repository, paymentData.getRecipient());
            long assetId = paymentData.getAssetId();
            long amount = paymentData.getAmount();

            // Update sender's balance due to amount
            sender.modifyAssetBalance(assetId, amount);

            // Update recipient's balance
            recipient.modifyAssetBalance(assetId, -amount);
        }
    }

    public void orphan(byte[] senderPublicKey, PaymentData paymentData) throws DataException {
        orphan(senderPublicKey, Collections.singletonList(paymentData));
    }

    // orphanReferencesAndFees

    public void orphanReferencesAndFees(byte[] senderPublicKey, List<PaymentData> payments, long fee, byte[] signature, byte[] reference,
            boolean alwaysUninitializeRecipientReference) throws DataException {
        Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

        // Update sender's balance due to fee
        sender.modifyAssetBalance(Asset.QORT, fee);

        // Update sender's reference
        sender.setLastReference(reference);

        // Orphan all recipients
        for (PaymentData paymentData : payments) {
            Account recipient = new Account(this.repository, paymentData.getRecipient());
            long assetId = paymentData.getAssetId();

            // For QORT amounts only: If recipient's last reference is this transaction's signature, then they can't have made any transactions of their own
            // (which would have changed their last reference) thus this is their first reference so remove it.
            if ((alwaysUninitializeRecipientReference || assetId == Asset.QORT) && Arrays.equals(recipient.getLastReference(), signature))
                recipient.setLastReference(null);
        }
    }

    public void orphanReferencesAndFees(byte[] senderPublicKey, PaymentData paymentData, long fee, byte[] signature, byte[] reference,
            boolean alwaysUninitializeRecipientReference) throws DataException {
        orphanReferencesAndFees(senderPublicKey, Collections.singletonList(paymentData), fee, signature, reference, alwaysUninitializeRecipientReference);
    }
}
