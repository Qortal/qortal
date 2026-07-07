package org.qortal.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.crypto.Crypto;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.DataType;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.naming.Name;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.PaymentTransformer;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ArbitraryTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int SERVICE_LENGTH = INT_LENGTH;
	private static final int NONCE_LENGTH = INT_LENGTH;
	private static final int DATA_TYPE_LENGTH = BYTE_LENGTH;
	private static final int DATA_SIZE_LENGTH = INT_LENGTH;
	private static final int RAW_DATA_SIZE_LENGTH = INT_LENGTH;
	private static final int METADATA_HASH_SIZE_LENGTH = INT_LENGTH;
	private static final int NUMBER_PAYMENTS_LENGTH = INT_LENGTH;
	private static final int NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int IDENTIFIER_SIZE_LENGTH = INT_LENGTH;
	private static final int COMPRESSION_LENGTH = INT_LENGTH;
	private static final int METHOD_LENGTH = INT_LENGTH;
	private static final int SECRET_SIZE_LENGTH = INT_LENGTH;

	private static final int EXTRAS_LENGTH = SERVICE_LENGTH + DATA_TYPE_LENGTH + DATA_SIZE_LENGTH;

	private static final int EXTRAS_V5_LENGTH = NONCE_LENGTH + NAME_SIZE_LENGTH + IDENTIFIER_SIZE_LENGTH +
			METHOD_LENGTH + SECRET_SIZE_LENGTH + COMPRESSION_LENGTH + RAW_DATA_SIZE_LENGTH + METADATA_HASH_SIZE_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.ARBITRARY.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("reference", TransformationType.SIGNATURE);
		layout.add("sender's public key", TransformationType.PUBLIC_KEY);
		layout.add("nonce", TransformationType.INT); // Version 5+

		layout.add("name length", TransformationType.INT); // Version 5+
		layout.add("name", TransformationType.DATA); // Version 5+
		layout.add("identifier length", TransformationType.INT); // Version 5+
		layout.add("identifier", TransformationType.DATA); // Version 5+
		layout.add("method", TransformationType.INT); // Version 5+
		layout.add("secret length", TransformationType.INT); // Version 5+
		layout.add("secret", TransformationType.DATA); // Version 5+
		layout.add("compression", TransformationType.INT); // Version 5+

		layout.add("number of payments", TransformationType.INT);
		layout.add("* recipient", TransformationType.ADDRESS);
		layout.add("* asset ID of payment", TransformationType.LONG);
		layout.add("* payment amount", TransformationType.AMOUNT);

		layout.add("service ID", TransformationType.INT);
		layout.add("is data raw?", TransformationType.BOOLEAN);
		layout.add("data length", TransformationType.INT);
		layout.add("data", TransformationType.DATA);

		layout.add("raw data size", TransformationType.INT); // Version 5+
		layout.add("metadata hash length", TransformationType.INT); // Version 5+
		layout.add("metadata hash", TransformationType.DATA); // Version 5+

		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int version = Transaction.getVersionByTimestamp(timestamp);

		int txGroupId = byteBuffer.getInt();

		byte[] reference = new byte[REFERENCE_LENGTH];
		byteBuffer.get(reference);

		byte[] senderPublicKey = Serialization.deserializePublicKey(byteBuffer);

		int nonce = 0;
		String name = null;
		String identifier = null;
		ArbitraryTransactionData.Method method = null;
		byte[] secret = null;
		ArbitraryTransactionData.Compression compression = null;

		if (version >= 5) {
			nonce = byteBuffer.getInt();

			name = Serialization.deserializeSizedStringV2(byteBuffer, Name.MAX_NAME_SIZE);

			identifier = Serialization.deserializeSizedStringV2(byteBuffer, ArbitraryTransaction.MAX_IDENTIFIER_LENGTH);

			method =  ArbitraryTransactionData.Method.valueOf(byteBuffer.getInt());

			int secretLength = byteBuffer.getInt();

			if(secretLength > 0) {

				if(secretLength > TransactionTransformer.PRIVATE_KEY_LENGTH) {
					throw new TransformationException("excessive secret length " + secretLength);
				}

				secret = new byte[secretLength];
				byteBuffer.get(secret);
			}

			compression = ArbitraryTransactionData.Compression.valueOf(byteBuffer.getInt());
		}

		// Always return a list of payments, even if empty
		List<PaymentData> payments = new ArrayList<>();
		if (version != 1) {
			int paymentsCount = byteBuffer.getInt();

			for (int i = 0; i < paymentsCount; ++i)
				payments.add(PaymentTransformer.fromByteBuffer(byteBuffer));
		}

		int service = byteBuffer.getInt();

		// We might be receiving hash of data instead of actual raw data
		boolean isRaw = byteBuffer.get() != 0;

		DataType dataType = isRaw ? DataType.RAW_DATA : DataType.DATA_HASH;

		int dataSize = byteBuffer.getInt();
		// Don't allow invalid dataSize here to avoid run-time issues
		if (dataSize > ArbitraryTransaction.MAX_DATA_SIZE)
			throw new TransformationException("ArbitraryTransaction data size too large");

		byte[] data = new byte[dataSize];
		byteBuffer.get(data);

		int size = 0;
		byte[] metadataHash = null;

		if (version >= 5) {
			size = byteBuffer.getInt();

			int metadataHashLength = byteBuffer.getInt();

			if(metadataHashLength > 0) {

				if(metadataHashLength > Transformer.SHA256_LENGTH) {
					throw new TransformationException("excessive metadata hash length " + metadataHashLength);
				}

				metadataHash = new byte[metadataHashLength];
				byteBuffer.get(metadataHash);
			}
		}

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, senderPublicKey, fee, signature);

		return new ArbitraryTransactionData(baseTransactionData, version, service, nonce, size, name, identifier,
				method, secret, compression, data, dataType, metadataHash, payments);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

		int nameLength = (arbitraryTransactionData.getName() != null) ? Utf8.encodedLength(arbitraryTransactionData.getName()) : 0;
		int identifierLength = (arbitraryTransactionData.getIdentifier() != null) ? Utf8.encodedLength(arbitraryTransactionData.getIdentifier()) : 0;
		int secretLength = (arbitraryTransactionData.getSecret() != null) ? arbitraryTransactionData.getSecret().length : 0;
		int dataLength = (arbitraryTransactionData.getData() != null) ? arbitraryTransactionData.getData().length : 0;
		int metadataHashLength = (arbitraryTransactionData.getMetadataHash() != null) ? arbitraryTransactionData.getMetadataHash().length : 0;

		int length = getBaseLength(transactionData) + EXTRAS_LENGTH + nameLength + identifierLength + secretLength + dataLength + metadataHashLength;

		if (arbitraryTransactionData.getVersion() >= 5) {
			length += EXTRAS_V5_LENGTH;
		}

		// Optional payments
		length += NUMBER_PAYMENTS_LENGTH + arbitraryTransactionData.getPayments().size() * PaymentTransformer.getDataLength();

		return length;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			if (arbitraryTransactionData.getVersion() >= 5) {
				bytes.write(Ints.toByteArray(arbitraryTransactionData.getNonce()));

				Serialization.serializeSizedStringV2(bytes, arbitraryTransactionData.getName());

				Serialization.serializeSizedStringV2(bytes, arbitraryTransactionData.getIdentifier());

				bytes.write(Ints.toByteArray(arbitraryTransactionData.getMethod().value));

				byte[] secret = arbitraryTransactionData.getSecret();
				int secretLength = (secret != null) ? secret.length : 0;
				bytes.write(Ints.toByteArray(secretLength));

				if (secretLength > 0) {
					bytes.write(secret);
				}

				bytes.write(Ints.toByteArray(arbitraryTransactionData.getCompression().value));
			}

			List<PaymentData> payments = arbitraryTransactionData.getPayments();
			bytes.write(Ints.toByteArray(payments.size()));

			for (PaymentData paymentData : payments)
				bytes.write(PaymentTransformer.toBytes(paymentData));

			bytes.write(Ints.toByteArray(arbitraryTransactionData.getServiceInt()));

			bytes.write((byte) (arbitraryTransactionData.getDataType() == DataType.RAW_DATA ? 1 : 0));

			bytes.write(Ints.toByteArray(arbitraryTransactionData.getData().length));
			bytes.write(arbitraryTransactionData.getData());

			if (arbitraryTransactionData.getVersion() >= 5) {
				bytes.write(Ints.toByteArray(arbitraryTransactionData.getSize()));

				byte[] metadataHash = arbitraryTransactionData.getMetadataHash();
				int metadataHashLength = (metadataHash != null) ? metadataHash.length : 0;
				bytes.write(Ints.toByteArray(metadataHashLength));

				if (metadataHashLength > 0) {
					bytes.write(metadataHash);
				}
			}

			bytes.write(Longs.toByteArray(arbitraryTransactionData.getFee()));

			if (arbitraryTransactionData.getSignature() != null)
				bytes.write(arbitraryTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

	/**
	 * Signature for ArbitraryTransactions always uses hash of data, not raw data itself.
	 * 
	 * @param transactionData
	 * @return byte[]
	 * @throws TransformationException
	 */
	protected static byte[] toBytesForSigningImpl(TransactionData transactionData) throws TransformationException {
		ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(arbitraryTransactionData, bytes);

			if (arbitraryTransactionData.getVersion() >= 5) {
				bytes.write(Ints.toByteArray(arbitraryTransactionData.getNonce()));

				Serialization.serializeSizedStringV2(bytes, arbitraryTransactionData.getName());

				Serialization.serializeSizedStringV2(bytes, arbitraryTransactionData.getIdentifier());

				bytes.write(Ints.toByteArray(arbitraryTransactionData.getMethod().value));

				byte[] secret = arbitraryTransactionData.getSecret();
				int secretLength = (secret != null) ? secret.length : 0;
				bytes.write(Ints.toByteArray(secretLength));

				if (secretLength > 0) {
					bytes.write(secret);
				}

				bytes.write(Ints.toByteArray(arbitraryTransactionData.getCompression().value));
			}

			if (arbitraryTransactionData.getVersion() != 1) {
				List<PaymentData> payments = arbitraryTransactionData.getPayments();
				bytes.write(Ints.toByteArray(payments.size()));

				for (PaymentData paymentData : payments)
					bytes.write(PaymentTransformer.toBytes(paymentData));
			}

			bytes.write(Ints.toByteArray(arbitraryTransactionData.getServiceInt()));

			bytes.write(Ints.toByteArray(arbitraryTransactionData.getData().length));

			// Signature uses hash of data, not raw data itself
			switch (arbitraryTransactionData.getDataType()) {
				case DATA_HASH:
					bytes.write(arbitraryTransactionData.getData());
					break;

				case RAW_DATA:
					bytes.write(Crypto.digest(arbitraryTransactionData.getData()));
					break;
			}

			if (arbitraryTransactionData.getVersion() >= 5) {
				bytes.write(Ints.toByteArray(arbitraryTransactionData.getSize()));

				byte[] metadataHash = arbitraryTransactionData.getMetadataHash();
				int metadataHashLength = (metadataHash != null) ? metadataHash.length : 0;
				bytes.write(Ints.toByteArray(metadataHashLength));

				if (metadataHashLength > 0) {
					bytes.write(metadataHash);
				}
			}

			bytes.write(Longs.toByteArray(arbitraryTransactionData.getFee()));

			// Never append signature

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

	public static void clearNonce(byte[] transactionBytes) {
		int nonceIndex = TYPE_LENGTH + TIMESTAMP_LENGTH + GROUPID_LENGTH + REFERENCE_LENGTH + PUBLIC_KEY_LENGTH;

		transactionBytes[nonceIndex++] = (byte) 0;
		transactionBytes[nonceIndex++] = (byte) 0;
		transactionBytes[nonceIndex++] = (byte) 0;
		transactionBytes[nonceIndex++] = (byte) 0;
	}

}
