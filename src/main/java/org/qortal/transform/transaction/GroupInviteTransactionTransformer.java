package org.qortal.transform.transaction;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class GroupInviteTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int GROUPID_LENGTH = INT_LENGTH;
	private static final int INVITEE_LENGTH = ADDRESS_LENGTH;
	private static final int TTL_LENGTH = INT_LENGTH;
	private static final int JOIN_FEE_LENGTH = LONG_LENGTH;

	private static final int EXTRAS_LENGTH = GROUPID_LENGTH + INVITEE_LENGTH + TTL_LENGTH + JOIN_FEE_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.GROUP_INVITE.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("reference", TransformationType.SIGNATURE);
		layout.add("group admin's public key", TransformationType.PUBLIC_KEY);
		layout.add("group ID", TransformationType.INT);
		layout.add("account to invite (invitee)", TransformationType.ADDRESS);
		layout.add("invite lifetime (seconds)", TransformationType.INT);
		layout.add("join fee", TransformationType.AMOUNT);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();

		byte[] reference = new byte[REFERENCE_LENGTH];
		byteBuffer.get(reference);

		byte[] adminPublicKey = Serialization.deserializePublicKey(byteBuffer);

		int groupId = byteBuffer.getInt();

		String invitee = Serialization.deserializeAddress(byteBuffer);

		int timeToLive = byteBuffer.getInt();

		long joinFee = byteBuffer.getLong();

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, adminPublicKey, fee, signature);

		return new GroupInviteTransactionData(baseTransactionData, groupId, invitee, timeToLive, joinFee);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return getBaseLength(transactionData) + EXTRAS_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			GroupInviteTransactionData groupInviteTransactionData = (GroupInviteTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(groupInviteTransactionData.getGroupId()));

			Serialization.serializeAddress(bytes, groupInviteTransactionData.getInvitee());

			bytes.write(Ints.toByteArray(groupInviteTransactionData.getTimeToLive()));

			bytes.write(Longs.toByteArray(groupInviteTransactionData.getJoinFee()));

			bytes.write(Longs.toByteArray(groupInviteTransactionData.getFee()));

			if (groupInviteTransactionData.getSignature() != null)
				bytes.write(groupInviteTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
