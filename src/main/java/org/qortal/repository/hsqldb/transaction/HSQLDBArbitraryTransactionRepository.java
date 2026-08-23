package org.qortal.repository.hsqldb.transaction;

import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.DataType;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class HSQLDBArbitraryTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBArbitraryTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT version, nonce, service, size, is_data_raw, data, metadata_hash, " +
				"name, identifier, update_method, secret, compression from ArbitraryTransactions " +
				"WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int version = resultSet.getInt(1);
			int nonce = resultSet.getInt(2);
			int serviceInt = resultSet.getInt(3);
			int size = resultSet.getInt(4);
			boolean isDataRaw = resultSet.getBoolean(5); // NOT NULL, so no null to false
			DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
			byte[] data = resultSet.getBytes(6);
			byte[] metadataHash = resultSet.getBytes(7);
			String name = resultSet.getString(8);
			String identifier = resultSet.getString(9);
			ArbitraryTransactionData.Method method = ArbitraryTransactionData.Method.valueOf(resultSet.getInt(10));
			byte[] secret = resultSet.getBytes(11);
			ArbitraryTransactionData.Compression compression = ArbitraryTransactionData.Compression.valueOf(resultSet.getInt(12));

			List<PaymentData> payments = this.getPaymentsFromSignature(baseTransactionData.getSignature());
			return new ArbitraryTransactionData(baseTransactionData, version, serviceInt, nonce, size, name,
					identifier, method, secret, compression, data, dataType, metadataHash, payments);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

		// For V4+, we might not store raw data in the repository but elsewhere
		if (arbitraryTransactionData.getVersion() >= 4)
			this.repository.getArbitraryRepository().save(arbitraryTransactionData);

		// method and compression use NOT NULL DEFAULT 0, so fall back to these values if null
		Integer method = arbitraryTransactionData.getMethod() != null ? arbitraryTransactionData.getMethod().value : 0;
		Integer compression = arbitraryTransactionData.getCompression() != null ? arbitraryTransactionData.getCompression().value : 0;

		HSQLDBSaver saveHelper = new HSQLDBSaver("ArbitraryTransactions");

		saveHelper.bind("signature", arbitraryTransactionData.getSignature()).bind("sender", arbitraryTransactionData.getSenderPublicKey())
				.bind("version", arbitraryTransactionData.getVersion()).bind("service", arbitraryTransactionData.getServiceInt())
				.bind("nonce", arbitraryTransactionData.getNonce()).bind("size", arbitraryTransactionData.getSize())
				.bind("is_data_raw", arbitraryTransactionData.getDataType() == DataType.RAW_DATA).bind("data", arbitraryTransactionData.getData())
				.bind("metadata_hash", arbitraryTransactionData.getMetadataHash()).bind("name", arbitraryTransactionData.getName())
				.bind("identifier", arbitraryTransactionData.getIdentifier()).bind("update_method", method)
				.bind("secret", arbitraryTransactionData.getSecret()).bind("compression", compression)
				.bind("created_when", arbitraryTransactionData.getTimestamp());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save arbitrary transaction into repository", e);
		}

		if (arbitraryTransactionData.getVersion() != 1)
			// Save payments. If this fails then it is the caller's responsibility to catch the DataException as the underlying transaction will have been lost.
			this.savePayments(transactionData.getSignature(), arbitraryTransactionData.getPayments());
	}

	@Override
	public void delete(TransactionData transactionData) throws DataException {
		ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

		// Potentially delete raw data stored locally too
		this.repository.getArbitraryRepository().delete(arbitraryTransactionData);
	}

}
