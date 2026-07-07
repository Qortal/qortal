package org.qortal.transform.transaction;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.account.NullAccount;
import org.qortal.data.transaction.ATTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.AtTransaction;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AtTransactionTransformer extends TransactionTransformer {

	protected static final TransactionLayout layout = null;

	// Property lengths

	private static final int MESSAGE_SIZE_LENGTH = INT_LENGTH;
	private static final int TYPE_LENGTH = INT_LENGTH;


	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int version = Transaction.getVersionByTimestamp(timestamp);

		byte[] reference = new byte[REFERENCE_LENGTH];
		byteBuffer.get(reference);

		String atAddress = Serialization.deserializeAddress(byteBuffer);

		String recipient = Serialization.deserializeAddress(byteBuffer);

		// Default to PAYMENT-type, as there were no MESSAGE-type transactions before transaction v6
		boolean isMessageType = false;

		if (version >= 6) {
			// Version 6 supports both PAYMENT-type and MESSAGE-type, specified using an integer.
			// This could be extended to support additional types at a later date, simply by adding
			// additional integer values.
			int type = byteBuffer.getInt();
			isMessageType = (type == 1);
		}

		int messageLength = 0;
		byte[] message = null;
		long assetId = 0L;
		long amount = 0L;

		if (isMessageType) {
			messageLength = byteBuffer.getInt();

			if (messageLength < 0)
				throw new TransformationException("negative message length " + messageLength);

			if (messageLength > AtTransaction.MAX_DATA_SIZE)
				throw new TransformationException("excessive message length " + messageLength);

			if (messageLength > byteBuffer.remaining())
				throw new TransformationException("message length exceeds remaining bytes " + messageLength);

			message = new byte[messageLength];
			byteBuffer.get(message);
		}
		else {
			// Assume PAYMENT-type, as there were no MESSAGE-type transactions until this time
			assetId = byteBuffer.getLong();

			amount = byteBuffer.getLong();
		}

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, NullAccount.PUBLIC_KEY, fee, signature);

		if (isMessageType) {
			// MESSAGE-type
			return new ATTransactionData(baseTransactionData, atAddress, recipient, message);
		}
		else {
			// PAYMENT-type
			return new ATTransactionData(baseTransactionData, atAddress, recipient, amount, assetId);
		}

	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		ATTransactionData atTransactionData = (ATTransactionData) transactionData;
		int version = Transaction.getVersionByTimestamp(transactionData.getTimestamp());

		final int baseLength = TYPE_LENGTH + TIMESTAMP_LENGTH + REFERENCE_LENGTH + ADDRESS_LENGTH + ADDRESS_LENGTH +
				FEE_LENGTH + SIGNATURE_LENGTH;

		int typeSpecificLength = 0;

		byte[] message = atTransactionData.getMessage();
		boolean isMessageType = (message != null);

		// MESSAGE-type and PAYMENT-type transactions will have differing lengths
		if (isMessageType) {
			typeSpecificLength = MESSAGE_SIZE_LENGTH + message.length;
		}
		else {
			typeSpecificLength = ASSET_ID_LENGTH + AMOUNT_LENGTH;
		}

		// V6 transactions include an extra integer to denote the type
		int versionSpecificLength = 0;
		if (version >= 6) {
			versionSpecificLength = TYPE_LENGTH;
		}

		return baseLength + typeSpecificLength + versionSpecificLength;
	}

	// Used for generating fake transaction signatures
	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			ATTransactionData atTransactionData = (ATTransactionData) transactionData;

			int version = Transaction.getVersionByTimestamp(atTransactionData.getTimestamp());

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(atTransactionData.getType().value));
			bytes.write(Longs.toByteArray(atTransactionData.getTimestamp()));
			bytes.write(atTransactionData.getReference());

			Serialization.serializeAddress(bytes, atTransactionData.getATAddress());

			Serialization.serializeAddress(bytes, atTransactionData.getRecipient());

			byte[] message = atTransactionData.getMessage();

			boolean isMessageType = (message != null);
			int type = isMessageType ? 1 : 0;

			if (version >= 6) {
				// Version 6 supports both PAYMENT-type and MESSAGE-type, specified using an integer.
				// This could be extended to support additional types at a later date, simply by adding
				// additional integer values.
				bytes.write(Ints.toByteArray(type));
			}

			if (isMessageType) {
				// MESSAGE-type
				bytes.write(Ints.toByteArray(message.length));
				bytes.write(message);
			} else {
				// PAYMENT-type
				bytes.write(Longs.toByteArray(atTransactionData.getAssetId()));
				bytes.write(Longs.toByteArray(atTransactionData.getAmount()));
			}

			bytes.write(Longs.toByteArray(atTransactionData.getFee()));

			if (atTransactionData.getSignature() != null)
				bytes.write(atTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
