package org.qortal.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdateGroupTransactionData;
import org.qortal.group.Group;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class UpdateGroupTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int GROUPID_LENGTH = INT_LENGTH;
	private static final int NEW_OWNER_LENGTH = ADDRESS_LENGTH;
	private static final int NEW_DESCRIPTION_SIZE_LENGTH = INT_LENGTH;
	private static final int NEW_IS_OPEN_LENGTH = BOOLEAN_LENGTH;
	private static final int NEW_APPROVAL_THRESHOLD_LENGTH = BYTE_LENGTH;
	private static final int NEW_MINIMUM_BLOCK_DELAY_LENGTH = INT_LENGTH;
	private static final int NEW_MAXIMUM_BLOCK_DELAY_LENGTH = INT_LENGTH;
	private static final int NEW_JOIN_FEE_LENGTH = LONG_LENGTH;

	private static final int EXTRAS_LENGTH = GROUPID_LENGTH + NEW_OWNER_LENGTH + NEW_DESCRIPTION_SIZE_LENGTH + NEW_IS_OPEN_LENGTH
			+ NEW_APPROVAL_THRESHOLD_LENGTH + NEW_MINIMUM_BLOCK_DELAY_LENGTH + NEW_MAXIMUM_BLOCK_DELAY_LENGTH + NEW_JOIN_FEE_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.UPDATE_GROUP.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("reference", TransformationType.SIGNATURE);
		layout.add("group owner's public key", TransformationType.PUBLIC_KEY);
		layout.add("group ID", TransformationType.INT);
		layout.add("group's new owner", TransformationType.ADDRESS);
		layout.add("group's new description length", TransformationType.INT);
		layout.add("group's new description", TransformationType.STRING);
		layout.add("is group \"open\"?", TransformationType.BOOLEAN);
		layout.add("new group transaction approval threshold", TransformationType.BYTE);
		layout.add("new group approval minimum block delay", TransformationType.INT);
		layout.add("new group approval maximum block delay", TransformationType.INT);
		layout.add("new group join fee", TransformationType.AMOUNT);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();

		byte[] reference = new byte[REFERENCE_LENGTH];
		byteBuffer.get(reference);

		byte[] ownerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		int groupId = byteBuffer.getInt();

		String newOwner = Serialization.deserializeAddress(byteBuffer);

		String newDescription = Serialization.deserializeSizedString(byteBuffer, Group.MAX_DESCRIPTION_SIZE);

		boolean newIsOpen = byteBuffer.get() != 0;

		ApprovalThreshold newApprovalThreshold = ApprovalThreshold.valueOf(byteBuffer.get());

		int newMinBlockDelay = byteBuffer.getInt();

		int newMaxBlockDelay = byteBuffer.getInt();

		long newJoinFee = byteBuffer.getLong();

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, ownerPublicKey, fee, signature);

		return new UpdateGroupTransactionData(baseTransactionData, groupId, newOwner, newDescription, newIsOpen,
				newApprovalThreshold, newMinBlockDelay, newMaxBlockDelay, newJoinFee, (byte[]) null);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		UpdateGroupTransactionData updateGroupTransactionData = (UpdateGroupTransactionData) transactionData;

		return getBaseLength(transactionData) + EXTRAS_LENGTH + Utf8.encodedLength(updateGroupTransactionData.getNewDescription());
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			UpdateGroupTransactionData updateGroupTransactionData = (UpdateGroupTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(updateGroupTransactionData.getGroupId()));

			Serialization.serializeAddress(bytes, updateGroupTransactionData.getNewOwner());

			Serialization.serializeSizedString(bytes, updateGroupTransactionData.getNewDescription());

			bytes.write((byte) (updateGroupTransactionData.getNewIsOpen() ? 1 : 0));

			bytes.write((byte) updateGroupTransactionData.getNewApprovalThreshold().value);

			bytes.write(Ints.toByteArray(updateGroupTransactionData.getNewMinimumBlockDelay()));

			bytes.write(Ints.toByteArray(updateGroupTransactionData.getNewMaximumBlockDelay()));

			bytes.write(Longs.toByteArray(updateGroupTransactionData.getNewJoinFee()));

			bytes.write(Longs.toByteArray(updateGroupTransactionData.getFee()));

			if (updateGroupTransactionData.getSignature() != null)
				bytes.write(updateGroupTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
