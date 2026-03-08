package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.SearchMode;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.arbitrary.ArbitraryResourceCache;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Compression;
import org.qortal.data.transaction.ArbitraryTransactionData.DataType;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.ArbitraryRepository;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction.ApprovalStatus;
import org.qortal.utils.Base58;
import org.qortal.utils.ListUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class HSQLDBArbitraryRepository implements ArbitraryRepository {

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBArbitraryRepository.class);

	protected HSQLDBRepository repository;
	
	public HSQLDBArbitraryRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	private ArbitraryTransactionData getTransactionData(byte[] signature) throws DataException {
		TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(signature);
		if (transactionData == null)
			return null;

		return (ArbitraryTransactionData) transactionData;
	}


	// Utils

	@Override
	public boolean isDataLocal(byte[] signature) throws DataException {
		ArbitraryTransactionData transactionData = getTransactionData(signature);
		if (transactionData == null) {
			return false;
		}

		// Raw data is always available
		if (transactionData.getDataType() == DataType.RAW_DATA) {
			return true;
		}

		// Load data file(s)
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(transactionData);

		// Check if we already have the complete data file or all chunks
		if (arbitraryDataFile.allFilesExist()) {
			return true;
		}

		return false;
	}

	@Override
	public byte[] fetchData(byte[] signature) {
		try {
			ArbitraryTransactionData transactionData = getTransactionData(signature);
			if (transactionData == null) {
				return null;
			}

			// Raw data is always available
			if (transactionData.getDataType() == DataType.RAW_DATA) {
				return transactionData.getData();
			}

			// Load data file(s)
			ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(transactionData);

			// If we have the complete data file, return it
			if (arbitraryDataFile.exists()) {
				// Ensure the file's size matches the size reported by the transaction (throws a DataException if not)
				arbitraryDataFile.validateFileSize(transactionData.getSize());

				return arbitraryDataFile.getBytes();
			}

			// Alternatively, if we have all the chunks, combine them into a single file
			if (arbitraryDataFile.allChunksExist()) {
				arbitraryDataFile.join();

				// Verify that the combined hash matches the expected hash
				byte[] digest = transactionData.getData();
				if (!digest.equals(arbitraryDataFile.digest())) {
					LOGGER.info(String.format("Hash mismatch for transaction: %s", Base58.encode(signature)));
					return null;
				}

				// Ensure the file's size matches the size reported by the transaction
				arbitraryDataFile.validateFileSize(transactionData.getSize());

				return arbitraryDataFile.getBytes();
			}

		} catch (DataException e) {
			LOGGER.info("Unable to fetch data for transaction {}: {}", Base58.encode(signature), e.getMessage());
			return null;
		}

		return null;
	}


	// Transaction related

	@Override
	public void save(ArbitraryTransactionData arbitraryTransactionData) throws DataException {
		// Already hashed? Nothing to do
		if (arbitraryTransactionData.getDataType() == DataType.DATA_HASH) {
			return;
		}

		// Trivial-sized payloads can remain in raw form
		if (arbitraryTransactionData.getDataType() == DataType.RAW_DATA && arbitraryTransactionData.getData().length <= ArbitraryTransaction.MAX_DATA_SIZE) {
			return;
		}

		throw new IllegalStateException(String.format("Supplied data is larger than maximum size (%d bytes). Please use ArbitraryDataWriter.", ArbitraryTransaction.MAX_DATA_SIZE));
	}

	@Override
	public void delete(ArbitraryTransactionData arbitraryTransactionData) throws DataException {
		// No need to do anything if we still only have raw data, and hence nothing saved in filesystem
		if (arbitraryTransactionData.getDataType() == DataType.RAW_DATA) {
			return;
		}

		// Load data file(s)
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(arbitraryTransactionData);

		// Delete file, chunks, and metadata
		arbitraryDataFile.deleteAll(true);
	}

	@Override
	public List<ArbitraryTransactionData> getArbitraryTransactions(String name, Service service, String identifier, long since) throws DataException {
		String sql = "SELECT type, reference, signature, creator, created_when, fee, " +
				"tx_group_id, block_height, approval_status, approval_height, " +
				"version, nonce, service, size, is_data_raw, data, metadata_hash, " +
				"name, identifier, update_method, secret, compression FROM ArbitraryTransactions " +
				"JOIN Transactions USING (signature) " +
				"WHERE lower(name) = ? AND service = ?" +
				"AND (identifier = ? OR (identifier IS NULL AND ? IS NULL))" +
				"AND created_when >= ? ORDER BY created_when ASC";
		List<ArbitraryTransactionData> arbitraryTransactionData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, name.toLowerCase(), service.value, identifier, identifier, since)) {
			if (resultSet == null)
				return null;

			do {
				//TransactionType type = TransactionType.valueOf(resultSet.getInt(1));

				byte[] reference = resultSet.getBytes(2);
				byte[] signature = resultSet.getBytes(3);
				byte[] creatorPublicKey = resultSet.getBytes(4);
				long timestamp = resultSet.getLong(5);

				Long fee = resultSet.getLong(6);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(7);

				Integer blockHeight = resultSet.getInt(8);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(9));
				Integer approvalHeight = resultSet.getInt(10);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, approvalStatus, blockHeight, approvalHeight, signature);

				int version = resultSet.getInt(11);
				int nonce = resultSet.getInt(12);
				int serviceInt = resultSet.getInt(13);
				int size = resultSet.getInt(14);
				boolean isDataRaw = resultSet.getBoolean(15); // NOT NULL, so no null to false
				DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
				byte[] data = resultSet.getBytes(16);
				byte[] metadataHash = resultSet.getBytes(17);
				String nameResult = resultSet.getString(18);
				String identifierResult = resultSet.getString(19);
				Method method = Method.valueOf(resultSet.getInt(20));
				byte[] secret = resultSet.getBytes(21);
				Compression compression = Compression.valueOf(resultSet.getInt(22));
				// FUTURE: get payments from signature if needed. Avoiding for now to reduce database calls.

				ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
						version, serviceInt, nonce, size, nameResult, identifierResult, method, secret,
						compression, data, dataType, metadataHash, null);

				arbitraryTransactionData.add(transactionData);
			} while (resultSet.next());

			return arbitraryTransactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transactions from repository", e);
		}
	}

	@Override
	public List<ArbitraryTransactionData> getLatestArbitraryTransactions() throws DataException {
		String sql = "SELECT type, reference, signature, creator, created_when, fee, " +
				"tx_group_id, block_height, approval_status, approval_height, " +
				"version, nonce, service, size, is_data_raw, data, metadata_hash, " +
				"name, identifier, update_method, secret, compression FROM ArbitraryTransactions " +
				"JOIN Transactions USING (signature) " +
				"WHERE name IS NOT NULL " +
				"ORDER BY created_when DESC";
		List<ArbitraryTransactionData> arbitraryTransactionData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return new ArrayList<>(0);

			do {
				byte[] reference = resultSet.getBytes(2);
				byte[] signature = resultSet.getBytes(3);
				byte[] creatorPublicKey = resultSet.getBytes(4);
				long timestamp = resultSet.getLong(5);

				Long fee = resultSet.getLong(6);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(7);

				Integer blockHeight = resultSet.getInt(8);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(9));
				Integer approvalHeight = resultSet.getInt(10);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, approvalStatus, blockHeight, approvalHeight, signature);

				int version = resultSet.getInt(11);
				int nonce = resultSet.getInt(12);
				int serviceInt = resultSet.getInt(13);
				int size = resultSet.getInt(14);
				boolean isDataRaw = resultSet.getBoolean(15); // NOT NULL, so no null to false
				DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
				byte[] data = resultSet.getBytes(16);
				byte[] metadataHash = resultSet.getBytes(17);
				String nameResult = resultSet.getString(18);
				String identifierResult = resultSet.getString(19);
				Method method = Method.valueOf(resultSet.getInt(20));
				byte[] secret = resultSet.getBytes(21);
				Compression compression = Compression.valueOf(resultSet.getInt(22));
				// FUTURE: get payments from signature if needed. Avoiding for now to reduce database calls.

				ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
						version, serviceInt, nonce, size, nameResult, identifierResult, method, secret,
						compression, data, dataType, metadataHash, null);

				arbitraryTransactionData.add(transactionData);
			} while (resultSet.next());

			return arbitraryTransactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transactions from repository", e);
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			return new ArrayList<>(0);
		}
	}

	@Override
	public List<ArbitraryTransactionData> getLatestArbitraryTransactionsByName( String name ) throws DataException {
		String sql = "SELECT type, reference, signature, creator, created_when, fee, " +
				"tx_group_id, block_height, approval_status, approval_height, " +
				"version, nonce, service, size, is_data_raw, data, metadata_hash, " +
				"name, identifier, update_method, secret, compression FROM ArbitraryTransactions " +
				"JOIN Transactions USING (signature) " +
				"WHERE name = ? " +
				"ORDER BY created_when DESC";
		List<ArbitraryTransactionData> arbitraryTransactionData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, name)) {
			if (resultSet == null)
				return new ArrayList<>(0);

			do {
				byte[] reference = resultSet.getBytes(2);
				byte[] signature = resultSet.getBytes(3);
				byte[] creatorPublicKey = resultSet.getBytes(4);
				long timestamp = resultSet.getLong(5);

				Long fee = resultSet.getLong(6);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(7);

				Integer blockHeight = resultSet.getInt(8);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(9));
				Integer approvalHeight = resultSet.getInt(10);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, approvalStatus, blockHeight, approvalHeight, signature);

				int version = resultSet.getInt(11);
				int nonce = resultSet.getInt(12);
				int serviceInt = resultSet.getInt(13);
				int size = resultSet.getInt(14);
				boolean isDataRaw = resultSet.getBoolean(15); // NOT NULL, so no null to false
				DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
				byte[] data = resultSet.getBytes(16);
				byte[] metadataHash = resultSet.getBytes(17);
				String nameResult = resultSet.getString(18);
				String identifierResult = resultSet.getString(19);
				Method method = Method.valueOf(resultSet.getInt(20));
				byte[] secret = resultSet.getBytes(21);
				Compression compression = Compression.valueOf(resultSet.getInt(22));
				// FUTURE: get payments from signature if needed. Avoiding for now to reduce database calls.

				ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
						version, serviceInt, nonce, size, nameResult, identifierResult, method, secret,
						compression, data, dataType, metadataHash, null);

				arbitraryTransactionData.add(transactionData);
			} while (resultSet.next());

			return arbitraryTransactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transactions from repository", e);
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			return new ArrayList<>(0);
		}
	}

	private ArbitraryTransactionData getSingleTransaction(String name, Service service, Method method, String identifier, boolean firstNotLast) throws DataException {
		if (name == null || service == null) {
			// Required fields
			return null;
		}

		StringBuilder sql = new StringBuilder(1024);

		sql.append("SELECT type, reference, signature, creator, created_when, fee, " +
				"tx_group_id, block_height, approval_status, approval_height, " +
				"version, nonce, service, size, is_data_raw, data, metadata_hash, " +
				"name, identifier, update_method, secret, compression FROM ArbitraryTransactions " +
				"JOIN Transactions USING (signature) " +
				"WHERE lower(name) = ? AND service = ? " +
				"AND (identifier = ? OR (identifier IS NULL AND ? IS NULL))");

		if (method != null) {
			sql.append(" AND update_method = ");
			sql.append(method.value);
		}

		sql.append(" ORDER BY created_when");

		if (firstNotLast) {
			sql.append(" ASC");
		}
		else {
			sql.append(" DESC");
		}

		sql.append(" LIMIT 1");

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), name.toLowerCase(), service.value, identifier, identifier)) {
			if (resultSet == null)
				return null;

			//TransactionType type = TransactionType.valueOf(resultSet.getInt(1));

			byte[] reference = resultSet.getBytes(2);
			byte[] signature = resultSet.getBytes(3);
			byte[] creatorPublicKey = resultSet.getBytes(4);
			long timestamp = resultSet.getLong(5);

			Long fee = resultSet.getLong(6);
			if (fee == 0 && resultSet.wasNull())
				fee = null;

			int txGroupId = resultSet.getInt(7);

			Integer blockHeight = resultSet.getInt(8);
			if (blockHeight == 0 && resultSet.wasNull())
				blockHeight = null;

			ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(9));
			Integer approvalHeight = resultSet.getInt(10);
			if (approvalHeight == 0 && resultSet.wasNull())
				approvalHeight = null;

			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, approvalStatus, blockHeight, approvalHeight, signature);

			int version = resultSet.getInt(11);
			int nonce = resultSet.getInt(12);
			int serviceInt = resultSet.getInt(13);
			int size = resultSet.getInt(14);
			boolean isDataRaw = resultSet.getBoolean(15); // NOT NULL, so no null to false
			DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
			byte[] data = resultSet.getBytes(16);
			byte[] metadataHash = resultSet.getBytes(17);
			String nameResult = resultSet.getString(18);
			String identifierResult = resultSet.getString(19);
			Method methodResult = Method.valueOf(resultSet.getInt(20));
			byte[] secret = resultSet.getBytes(21);
			Compression compression = Compression.valueOf(resultSet.getInt(22));
			// FUTURE: get payments from signature if needed. Avoiding for now to reduce database calls.

			ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
					version, serviceInt, nonce, size, nameResult, identifierResult, methodResult, secret,
					compression, data, dataType, metadataHash, null);

			return transactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transactions from repository", e);
		}
	}

	@Override
	public ArbitraryTransactionData getSingleTransactionBySignature(byte[] signature) throws DataException {
		StringBuilder sql = new StringBuilder(1024);

		sql.append("SELECT type, reference, signature, creator, created_when, fee, " +
				"tx_group_id, block_height, approval_status, approval_height, " +
				"version, nonce, service, size, is_data_raw, data, metadata_hash, " +
				"name, identifier, update_method, secret, compression FROM ArbitraryTransactions atx " +
				"JOIN Transactions tx USING (signature) " +
				"WHERE atx.signature = ? AND tx.signature = ? LIMIT 1");

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), signature, signature)) {
			if (resultSet == null)
				return null;

			byte[] reference = resultSet.getBytes(2);
			byte[] creatorPublicKey = resultSet.getBytes(4);
			long timestamp = resultSet.getLong(5);

			Long fee = resultSet.getLong(6);
			if (fee == 0 && resultSet.wasNull())
				fee = null;

			int txGroupId = resultSet.getInt(7);

			Integer blockHeight = resultSet.getInt(8);
			if (blockHeight == 0 && resultSet.wasNull())
				blockHeight = null;

			ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(9));
			Integer approvalHeight = resultSet.getInt(10);
			if (approvalHeight == 0 && resultSet.wasNull())
				approvalHeight = null;

			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, approvalStatus, blockHeight, approvalHeight, signature);

			int version = resultSet.getInt(11);
			int nonce = resultSet.getInt(12);
			int serviceInt = resultSet.getInt(13);
			int size = resultSet.getInt(14);
			boolean isDataRaw = resultSet.getBoolean(15); // NOT NULL, so no null to false
			DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
			byte[] data = resultSet.getBytes(16);
			byte[] metadataHash = resultSet.getBytes(17);
			String nameResult = resultSet.getString(18);
			String identifierResult = resultSet.getString(19);
			Method methodResult = Method.valueOf(resultSet.getInt(20));
			byte[] secret = resultSet.getBytes(21);
			Compression compression = Compression.valueOf(resultSet.getInt(22));
			// TODO: get payments from signature if needed. Avoiding for now to reduce database calls.

			ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
					version, serviceInt, nonce, size, nameResult, identifierResult, methodResult, secret,
					compression, data, dataType, metadataHash, null);

			return transactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transactions from repository", e);
		}
	}

	@Override
	public ArbitraryTransactionData getInitialTransaction(String name, Service service, Method method, String identifier) throws DataException {
		return this.getSingleTransaction(name, service, method, identifier, true);
	}

	@Override
	public ArbitraryTransactionData getLatestTransaction(String name, Service service, Method method, String identifier) throws DataException {
		return this.getSingleTransaction(name, service, method, identifier, false);
	}

	public List<ArbitraryTransactionData> getArbitraryTransactions(boolean requireName, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		sql.append("SELECT type, reference, signature, creator, created_when, fee, " +
			"tx_group_id, block_height, approval_status, approval_height, " +
			"version, nonce, service, size, is_data_raw, data, metadata_hash, " +
			"name, identifier, update_method, secret, compression FROM ArbitraryTransactions " +
			"JOIN Transactions USING (signature)");

		if (requireName) {
			sql.append(" WHERE name IS NOT NULL");
		}

		sql.append(" ORDER BY created_when");

		if (reverse != null && reverse) {
			sql.append(" DESC");
		}

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ArbitraryTransactionData> arbitraryTransactionData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return null;

			do {
				//TransactionType type = TransactionType.valueOf(resultSet.getInt(1));

				byte[] reference = resultSet.getBytes(2);
				byte[] signature = resultSet.getBytes(3);
				byte[] creatorPublicKey = resultSet.getBytes(4);
				long timestamp = resultSet.getLong(5);

				Long fee = resultSet.getLong(6);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(7);

				Integer blockHeight = resultSet.getInt(8);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(9));
				Integer approvalHeight = resultSet.getInt(10);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, approvalStatus, blockHeight, approvalHeight, signature);

				int version = resultSet.getInt(11);
				int nonce = resultSet.getInt(12);
				int serviceInt = resultSet.getInt(13);
				int size = resultSet.getInt(14);
				boolean isDataRaw = resultSet.getBoolean(15); // NOT NULL, so no null to false
				DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
				byte[] data = resultSet.getBytes(16);
				byte[] metadataHash = resultSet.getBytes(17);
				String nameResult = resultSet.getString(18);
				String identifierResult = resultSet.getString(19);
				Method method = Method.valueOf(resultSet.getInt(20));
				byte[] secret = resultSet.getBytes(21);
				Compression compression = Compression.valueOf(resultSet.getInt(22));
				// FUTURE: get payments from signature if needed. Avoiding for now to reduce database calls.

				ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
						version, serviceInt, nonce, size, nameResult, identifierResult, method, secret,
						compression, data, dataType, metadataHash, null);

				arbitraryTransactionData.add(transactionData);
			} while (resultSet.next());

			return arbitraryTransactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transactions from repository", e);
		}
	}


	// Resource related

	@Override
	public ArbitraryResourceData getArbitraryResource(Service service, String name, String identifier) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		// Name is required
		if (name == null) {
			return null;
		}

		sql.append("SELECT name, service, identifier, size, status, created_when, updated_when, " +
				"title, description, category, tag1, tag2, tag3, tag4, tag5, latest_signature " +
				"FROM ArbitraryResourcesCache " +
				"LEFT JOIN ArbitraryMetadataCache USING (service, name, identifier) " +
				"WHERE ArbitraryResourcesCache.service = ? AND ArbitraryResourcesCache.name = ?");

		bindParams.add(service.value);
		bindParams.add(name);

		if (identifier != null) {
			sql.append(" AND identifier = ?");
			bindParams.add(identifier);
		}
		else {
			sql.append(" AND (identifier IS NULL OR identifier = 'default')");
		}

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return null;

			String nameResult = resultSet.getString(1);
			Service serviceResult = Service.valueOf(resultSet.getInt(2));
			String identifierResult = resultSet.getString(3);
			Integer sizeResult = resultSet.getInt(4);
			Integer status = resultSet.getInt(5);
			Long created = resultSet.getLong(6);
			Long updated = resultSet.getLong(7);

			// Optional metadata fields
			String title = resultSet.getString(8);
			String description = resultSet.getString(9);
			String category = resultSet.getString(10);
			String tag1 = resultSet.getString(11);
			String tag2 = resultSet.getString(12);
			String tag3 = resultSet.getString(13);
			String tag4 = resultSet.getString(14);
			String tag5 = resultSet.getString(15);

			byte[] latestSignature = resultSet.getBytes(16);

			if (Objects.equals(identifierResult, "default")) {
				// Map "default" back to null. This is optional but probably less confusing than returning "default".
				identifierResult = null;
			}

			ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
			arbitraryResourceData.name = nameResult;
			arbitraryResourceData.service = serviceResult;
			arbitraryResourceData.identifier = identifierResult;
			arbitraryResourceData.size = sizeResult;
			arbitraryResourceData.setStatus(ArbitraryResourceStatus.Status.valueOf(status));
			arbitraryResourceData.created = created;
			arbitraryResourceData.updated = (updated == 0) ? null : updated;
			arbitraryResourceData.latestSignature = latestSignature;

			ArbitraryResourceMetadata metadata = new ArbitraryResourceMetadata();
			metadata.setTitle(title);
			metadata.setDescription(description);
			metadata.setCategory(Category.uncategorizedValueOf(category));

			List<String> tags = new ArrayList<>();
			if (tag1 != null) tags.add(tag1);
			if (tag2 != null) tags.add(tag2);
			if (tag3 != null) tags.add(tag3);
			if (tag4 != null) tags.add(tag4);
			if (tag5 != null) tags.add(tag5);
			metadata.setTags(!tags.isEmpty() ? tags : null);

			if (metadata.hasMetadata()) {
				arbitraryResourceData.metadata = metadata;
			}

			return arbitraryResourceData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary resource from repository", e);
		}
	}

	@Override
	public byte[] getLatestSignature(Service service, String name, String identifier) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		// Name is required
		if (name == null) {
			return null;
		}

		sql.append("SELECT latest_signature " +
				"FROM ArbitraryResourcesCache " +
				"WHERE lower_case_name = ? AND service = ? " +
				"AND identifier = ?");

		bindParams.add(name.toLowerCase());
		bindParams.add(service.value);
		bindParams.add(identifier != null ? identifier : "default");

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return null;

			return resultSet.getBytes(1);

		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary resource from repository", e);
		}
	}

	@Override
	public List<ArbitraryResourceData> getArbitraryResources(Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT name, service, identifier, size, status, created_when, updated_when, " +
				"title, description, category, tag1, tag2, tag3, tag4, tag5,latest_signature " +
				"FROM ArbitraryResourcesCache " +
				"LEFT JOIN ArbitraryMetadataCache USING (service, name, identifier) " +
				"WHERE name IS NOT NULL ORDER BY created_when");

		if (reverse != null && reverse) {
			sql.append(" DESC");
		}

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ArbitraryResourceData> arbitraryResources = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return arbitraryResources;

			do {
				String nameResult = resultSet.getString(1);
				Service serviceResult = Service.valueOf(resultSet.getInt(2));
				String identifierResult = resultSet.getString(3);
				Integer sizeResult = resultSet.getInt(4);
				Integer status = resultSet.getInt(5);
				Long created = resultSet.getLong(6);
				Long updated = resultSet.getLong(7);

				// Optional metadata fields
				String title = resultSet.getString(8);
				String description = resultSet.getString(9);
				String category = resultSet.getString(10);
				String tag1 = resultSet.getString(11);
				String tag2 = resultSet.getString(12);
				String tag3 = resultSet.getString(13);
				String tag4 = resultSet.getString(14);
				String tag5 = resultSet.getString(15);

				byte[] latestSignatureResult = resultSet.getBytes(16);

				if (Objects.equals(identifierResult, "default")) {
					// Map "default" back to null. This is optional but probably less confusing than returning "default".
					identifierResult = null;
				}

				ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
				arbitraryResourceData.name = nameResult;
				arbitraryResourceData.service = serviceResult;
				arbitraryResourceData.identifier = identifierResult;
				arbitraryResourceData.size = sizeResult;
				arbitraryResourceData.setStatus(ArbitraryResourceStatus.Status.valueOf(status));
				arbitraryResourceData.created = created;
				arbitraryResourceData.updated = (updated == 0) ? null : updated;
				arbitraryResourceData.latestSignature = latestSignatureResult;

				ArbitraryResourceMetadata metadata = new ArbitraryResourceMetadata();
				metadata.setTitle(title);
				metadata.setDescription(description);
				metadata.setCategory(Category.uncategorizedValueOf(category));

				List<String> tags = new ArrayList<>();
				if (tag1 != null) tags.add(tag1);
				if (tag2 != null) tags.add(tag2);
				if (tag3 != null) tags.add(tag3);
				if (tag4 != null) tags.add(tag4);
				if (tag5 != null) tags.add(tag5);
				metadata.setTags(!tags.isEmpty() ? tags : null);

				if (metadata.hasMetadata()) {
					arbitraryResourceData.metadata = metadata;
				}

				arbitraryResources.add(arbitraryResourceData);
			} while (resultSet.next());

			return arbitraryResources;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary resources from repository", e);
		}
	}

	@Override
	public List<ArbitraryResourceData> getArbitraryResources(Service service, String identifier, List<String> names,
															 boolean defaultResource, Boolean followedOnly, Boolean excludeBlocked,
															 Boolean includeMetadata, Boolean includeStatus,
															 Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT name, service, identifier, size, status, created_when, updated_when, " +
				"title, description, category, tag1, tag2, tag3, tag4, tag5, latest_signature " +
				"FROM ArbitraryResourcesCache " +
				"LEFT JOIN ArbitraryMetadataCache USING (service, name, identifier) " +
				"WHERE name IS NOT NULL");

		if (service != null) {
			sql.append(" AND service = ?");
			bindParams.add(service.value);
		}

		if (defaultResource) {
			// Default resource requested - use NULL identifier
			sql.append(" AND identifier='default'");
		}
		else {
			// Non-default resource requested
			// Use an exact match identifier, or list all if supplied identifier is null
			sql.append(" AND (identifier = ? OR (? IS NULL))");
			bindParams.add(identifier);
			bindParams.add(identifier);
		}

		if (names != null && !names.isEmpty()) {
			sql.append(" AND name IN (?");
			bindParams.add(names.get(0));

			for (int i = 1; i < names.size(); ++i) {
				sql.append(", ?");
				bindParams.add(names.get(i));
			}

			sql.append(")");
		}

		// Handle "followed only"
		if (followedOnly != null && followedOnly) {
			List<String> followedNames = ListUtils.followedNames();
			if (followedNames != null && !followedNames.isEmpty()) {
				sql.append(" AND name IN (?");
				bindParams.add(followedNames.get(0));

				for (int i = 1; i < followedNames.size(); ++i) {
					sql.append(", ?");
					bindParams.add(followedNames.get(i));
				}
				sql.append(")");
			}
		}

		// Handle "exclude blocked"
		if (excludeBlocked != null && excludeBlocked) {
			List<String> blockedNames = ListUtils.blockedNames();
			if (blockedNames != null && !blockedNames.isEmpty()) {
				sql.append(" AND name NOT IN (?");
				bindParams.add(blockedNames.get(0));

				for (int i = 1; i < blockedNames.size(); ++i) {
					sql.append(", ?");
					bindParams.add(blockedNames.get(i));
				}
				sql.append(")");
			}
		}

		sql.append(" ORDER BY name COLLATE SQL_TEXT_UCC_NO_PAD");

		if (reverse != null && reverse) {
			sql.append(" DESC");
		}

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ArbitraryResourceData> arbitraryResources = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return arbitraryResources;

			do {
				String nameResult = resultSet.getString(1);
				Service serviceResult = Service.valueOf(resultSet.getInt(2));
				String identifierResult = resultSet.getString(3);
				Integer sizeResult = resultSet.getInt(4);
				Integer status = resultSet.getInt(5);
				Long created = resultSet.getLong(6);
				Long updated = resultSet.getLong(7);

				// Optional metadata fields
				String title = resultSet.getString(8);
				String description = resultSet.getString(9);
				String category = resultSet.getString(10);
				String tag1 = resultSet.getString(11);
				String tag2 = resultSet.getString(12);
				String tag3 = resultSet.getString(13);
				String tag4 = resultSet.getString(14);
				String tag5 = resultSet.getString(15);

				byte[] latestSignatureResult = resultSet.getBytes(16);

				if (Objects.equals(identifierResult, "default")) {
					// Map "default" back to null. This is optional but probably less confusing than returning "default".
					identifierResult = null;
				}

				ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
				arbitraryResourceData.name = nameResult;
				arbitraryResourceData.service = serviceResult;
				arbitraryResourceData.identifier = identifierResult;
				arbitraryResourceData.size = sizeResult;
				arbitraryResourceData.created = created;
				arbitraryResourceData.updated = (updated == 0) ? null : updated;
				arbitraryResourceData.latestSignature = latestSignatureResult;

				if (includeStatus != null && includeStatus) {
					arbitraryResourceData.setStatus(ArbitraryResourceStatus.Status.valueOf(status));
				}

				if (includeMetadata != null && includeMetadata) {
					// TODO: we could avoid the join altogether
					ArbitraryResourceMetadata metadata = new ArbitraryResourceMetadata();
					metadata.setTitle(title);
					metadata.setDescription(description);
					metadata.setCategory(Category.uncategorizedValueOf(category));

					List<String> tags = new ArrayList<>();
					if (tag1 != null) tags.add(tag1);
					if (tag2 != null) tags.add(tag2);
					if (tag3 != null) tags.add(tag3);
					if (tag4 != null) tags.add(tag4);
					if (tag5 != null) tags.add(tag5);
					metadata.setTags(!tags.isEmpty() ? tags : null);

					if (metadata.hasMetadata()) {
						arbitraryResourceData.metadata = metadata;
					}
				}

				arbitraryResources.add(arbitraryResourceData);
			} while (resultSet.next());

			return arbitraryResources;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary resources from repository", e);
		}
	}

	@Override
	public List<ArbitraryResourceData> searchArbitraryResources(Service service, String query, String identifier, List<String> names, String title, String description, List<String> keywords, boolean prefixOnly,
																List<String> exactMatchNames, boolean defaultResource, SearchMode mode, Integer minLevel, Boolean followedOnly, Boolean excludeBlocked,
																Boolean includeMetadata, Boolean includeStatus, Long before, Long after, Integer limit, Integer offset, Boolean reverse) throws DataException {

		if(Settings.getInstance().isDbCacheEnabled()) {
			List<ArbitraryResourceData> list
				= HSQLDBCacheUtils.callCache(
					ArbitraryResourceCache.getInstance(),
					service, query, identifier, names, title, description, prefixOnly, exactMatchNames,
					defaultResource, mode, minLevel, followedOnly, excludeBlocked, includeMetadata, includeStatus,
					before, after, limit, offset, reverse);

			if( !list.isEmpty() ) {
				List<ArbitraryResourceData> results
					= HSQLDBCacheUtils.filterList(
						list,
						ArbitraryResourceCache.getInstance().getLevelByName(),
						Optional.ofNullable(mode),
						Optional.ofNullable(service),
						Optional.ofNullable(query),
						Optional.ofNullable(identifier),
						Optional.ofNullable(names),
						Optional.ofNullable(title),
						Optional.ofNullable(description),
						prefixOnly,
						Optional.ofNullable(exactMatchNames),
						Optional.ofNullable(keywords),
						defaultResource,
						Optional.ofNullable(minLevel),
						followedOnly != null ? Optional.ofNullable(() -> ListUtils.followedNames()) : Optional.empty(),
						excludeBlocked != null ? Optional.ofNullable(ListUtils::blockedNames) : Optional.empty(),
						Optional.ofNullable(includeMetadata),
						Optional.ofNullable(includeStatus),
						Optional.ofNullable(before),
						Optional.ofNullable(after),
						Optional.ofNullable(limit),
						Optional.ofNullable(offset),
						Optional.ofNullable(reverse)
				);

				return results;
			}
			else {
				LOGGER.info("Db Enabled Cache has zero candidates.");
			}
		}


		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT name, service, identifier, size, status, created_when, updated_when, " +
				"title, description, category, tag1, tag2, tag3, tag4, tag5, latest_signature " +
				"FROM ArbitraryResourcesCache");

		// Default to "latest" mode
		if (mode == null) {
			mode = SearchMode.LATEST;
		}

		switch (mode) {
			case LATEST:
				// Include latest item only for a name/service combination
				sql.append(" JOIN (SELECT name, service, MAX(created_when) AS latest " +
						"FROM ArbitraryResourcesCache GROUP BY name, service) LatestResources " +
						"ON name=LatestResources.name AND service=LatestResources.service " +
						"AND created_when=LatestResources.latest");
				break;

			case ALL:
				break;
		}

		if (minLevel != null) {
			// Join tables necessary for level filter
			sql.append(" JOIN Names USING (name) JOIN Accounts ON Accounts.account=Names.owner");
		}

		sql.append(" LEFT JOIN ArbitraryMetadataCache USING (service, name, identifier) WHERE name IS NOT NULL");

		if (minLevel != null) {
			// Add level filter
			sql.append(" AND Accounts.level >= ?");
			bindParams.add(minLevel);
		}

		if (service != null) {
			sql.append(" AND service = ?");
			bindParams.add(service.value);
		}

		// Handle general query matches
		if (query != null) {
			// Search anywhere in the fields, unless "prefixOnly" has been requested
			// Note that without prefixOnly it will bypass any indexes so may not scale well
			// Longer term we probably want to copy resources to their own table anyway
			String queryWildcard = prefixOnly ? String.format("%s%%", query.toLowerCase()) : String.format("%%%s%%", query.toLowerCase());

			if (defaultResource) {
				// Default resource requested - use NULL identifier and search name only
				sql.append(" AND LCASE(name) LIKE ? AND identifier='default'");
				bindParams.add(queryWildcard);
			} else {
				// Non-default resource requested
				// In this case we search the identifier as well as the name
				sql.append(" AND (LCASE(name) LIKE ? OR LCASE(identifier) LIKE ? OR LCASE(title) LIKE ? OR LCASE(description) LIKE ?)");
				bindParams.add(queryWildcard); bindParams.add(queryWildcard); bindParams.add(queryWildcard); bindParams.add(queryWildcard);
			}
		}

		// Handle identifier matches
		if (identifier != null) {
			// Search anywhere in the identifier, unless "prefixOnly" has been requested
			String queryWildcard = prefixOnly ? String.format("%s%%", identifier.toLowerCase()) : String.format("%%%s%%", identifier.toLowerCase());
			sql.append(" AND LCASE(identifier) LIKE ?");
			bindParams.add(queryWildcard);
		}

		// Handle title metadata matches
		if (title != null) {
			// Search anywhere in the title, unless "prefixOnly" has been requested
			String queryWildcard = prefixOnly ? String.format("%s%%", title.toLowerCase()) : String.format("%%%s%%", title.toLowerCase());
			sql.append(" AND LCASE(title) LIKE ?");
			bindParams.add(queryWildcard);
		}

		// Handle description metadata matches
		if (description != null) {
			// Search anywhere in the description, unless "prefixOnly" has been requested
			String queryWildcard = prefixOnly ? String.format("%s%%", description.toLowerCase()) : String.format("%%%s%%", description.toLowerCase());
			sql.append(" AND LCASE(description) LIKE ?");
			bindParams.add(queryWildcard);
		}

		if (keywords != null && !keywords.isEmpty()) {
			List<String> searchKeywords = new ArrayList<>(keywords); 
		
			List<String> conditions = new ArrayList<>();
			List<String> bindValues = new ArrayList<>();
		
			for (int i = 0; i < searchKeywords.size(); i++) {
				conditions.add("LOWER(description) LIKE ?"); 
				bindValues.add("%" + searchKeywords.get(i).trim().toLowerCase() + "%"); 
			}
		
			String finalCondition = String.join(" OR ", conditions);
			sql.append(" AND (").append(finalCondition).append(")");
		
			bindParams.addAll(bindValues); 
		}
		
		
		

		// Handle name searches
		if (names != null && !names.isEmpty()) {
			sql.append(" AND (");

			for (int i = 0; i < names.size(); ++i) {
				// Search anywhere in the name, unless "prefixOnly" has been requested
				String queryWildcard = prefixOnly ? String.format("%s%%", names.get(i).toLowerCase()) : String.format("%%%s%%", names.get(i).toLowerCase());
				if (i > 0) sql.append(" OR ");
				sql.append("LCASE(name) LIKE ?");
				bindParams.add(queryWildcard);
			}
			sql.append(")");
		}

		// Handle name exact matches
		if (exactMatchNames != null && !exactMatchNames.isEmpty()) {
			sql.append(" AND LCASE(name) IN (?");
			bindParams.add(exactMatchNames.get(0).toLowerCase());

			for (int i = 1; i < exactMatchNames.size(); ++i) {
				sql.append(", ?");
				bindParams.add(exactMatchNames.get(i).toLowerCase());
			}
			sql.append(")");
		}

		// Timestamp range
		if (before != null) {
			sql.append(" AND created_when < ?");
			bindParams.add(before);
		}
		if (after != null) {
			sql.append(" AND created_when > ?");
			bindParams.add(after);
		}

		// Handle "followed only"
		if (followedOnly != null && followedOnly) {
			List<String> followedNames = ListUtils.followedNames();
			if (followedNames != null && !followedNames.isEmpty()) {
				sql.append(" AND LCASE(name) IN (?");
				bindParams.add(followedNames.get(0).toLowerCase());

				for (int i = 1; i < followedNames.size(); ++i) {
					sql.append(", ?");
					bindParams.add(followedNames.get(i).toLowerCase());
				}
				sql.append(")");
			}
		}

		// Handle "exclude blocked"
		if (excludeBlocked != null && excludeBlocked) {
			List<String> blockedNames = ListUtils.blockedNames();
			if (blockedNames != null && !blockedNames.isEmpty()) {
				sql.append(" AND LCASE(name) NOT IN (?");
				bindParams.add(blockedNames.get(0).toLowerCase());

				for (int i = 1; i < blockedNames.size(); ++i) {
					sql.append(", ?");
					bindParams.add(blockedNames.get(i).toLowerCase());
				}
				sql.append(")");
			}
		}

		sql.append(" ORDER BY created_when");

		if (reverse != null && reverse) {
			sql.append(" DESC");
		}

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ArbitraryResourceData> arbitraryResources = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return arbitraryResources;

			do {
				String nameResult = resultSet.getString(1);
				Service serviceResult = Service.valueOf(resultSet.getInt(2));
				String identifierResult = resultSet.getString(3);
				Integer sizeResult = resultSet.getInt(4);
				Integer status = resultSet.getInt(5);
				Long created = resultSet.getLong(6);
				Long updated = resultSet.getLong(7);

				// Optional metadata fields
				String titleResult = resultSet.getString(8);
				String descriptionResult = resultSet.getString(9);
				String category = resultSet.getString(10);
				String tag1 = resultSet.getString(11);
				String tag2 = resultSet.getString(12);
				String tag3 = resultSet.getString(13);
				String tag4 = resultSet.getString(14);
				String tag5 = resultSet.getString(15);

				byte[] latestSignatureResult = resultSet.getBytes(16);

				if (Objects.equals(identifierResult, "default")) {
					// Map "default" back to null. This is optional but probably less confusing than returning "default".
					identifierResult = null;
				}

				ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
				arbitraryResourceData.name = nameResult;
				arbitraryResourceData.service = serviceResult;
				arbitraryResourceData.identifier = identifierResult;
				arbitraryResourceData.size = sizeResult;
				arbitraryResourceData.created = created;
				arbitraryResourceData.updated = (updated == 0) ? null : updated;
				arbitraryResourceData.latestSignature = latestSignatureResult;

				if (includeStatus != null && includeStatus) {
					arbitraryResourceData.setStatus(ArbitraryResourceStatus.Status.valueOf(status));
				}

				if (includeMetadata != null && includeMetadata) {
					// TODO: we could avoid the join altogether
					ArbitraryResourceMetadata metadata = new ArbitraryResourceMetadata();
					metadata.setTitle(titleResult);
					metadata.setDescription(descriptionResult);
					metadata.setCategory(Category.uncategorizedValueOf(category));

					List<String> tags = new ArrayList<>();
					if (tag1 != null) tags.add(tag1);
					if (tag2 != null) tags.add(tag2);
					if (tag3 != null) tags.add(tag3);
					if (tag4 != null) tags.add(tag4);
					if (tag5 != null) tags.add(tag5);
					metadata.setTags(!tags.isEmpty() ? tags : null);

					if (metadata.hasMetadata()) {
						arbitraryResourceData.metadata = metadata;
					}
				}

				arbitraryResources.add(arbitraryResourceData);
			} while (resultSet.next());

			return arbitraryResources;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary resources from repository", e);
		}
	}

	@Override
	public List<ArbitraryResourceData> searchArbitraryResourcesSimple(
			Service service,
			String identifier,
			List<String> names,
			boolean prefixOnly,
			Long before,
			Long after,
			Integer limit,
			Integer offset,
			Boolean reverse,
			Boolean caseInsensitive) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT name, service, identifier, size, status, created_when, updated_when, latest_signature ");
		sql.append("FROM ArbitraryResourcesCache ");
		sql.append("WHERE name IS NOT NULL");

		if (service != null) {
			sql.append(" AND service = ?");
			bindParams.add(service.value);
		}

		// Handle identifier matches
		if (identifier != null) {
			if(caseInsensitive || prefixOnly) {
				// Search anywhere in the identifier, unless "prefixOnly" has been requested
				String queryWildcard = getQueryWildcard(identifier, prefixOnly, caseInsensitive);
				sql.append(caseInsensitive ? " AND LCASE(identifier) LIKE ?" : " AND identifier LIKE ?");
				bindParams.add(queryWildcard);
			}
			else {
				sql.append(" AND identifier = ?");
				bindParams.add(identifier);
			}
		}

		// Handle name searches
		if (names != null && !names.isEmpty()) {
			sql.append(" AND (");

			if( caseInsensitive || prefixOnly ) {
				for (int i = 0; i < names.size(); ++i) {
					// Search anywhere in the name, unless "prefixOnly" has been requested
					String queryWildcard = getQueryWildcard(names.get(i), prefixOnly, caseInsensitive);
					if (i > 0) sql.append(" OR ");
					sql.append(caseInsensitive ? "LCASE(name) LIKE ?" : "name LIKE ?");
					bindParams.add(queryWildcard);
				}
			}
			else {
				for (int i = 0; i < names.size(); ++i) {
					if (i > 0) sql.append(" OR ");
					sql.append("name = ?");
					bindParams.add(names.get(i));
				}
			}

			sql.append(")");
		}

		// Timestamp range
		if (before != null) {
			sql.append(" AND created_when < ?");
			bindParams.add(before);
		}
		if (after != null) {
			sql.append(" AND created_when > ?");
			bindParams.add(after);
		}

		sql.append(" ORDER BY created_when");

		if (reverse != null && reverse) {
			sql.append(" DESC");
		}

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ArbitraryResourceData> arbitraryResources = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return arbitraryResources;

			do {
				String nameResult = resultSet.getString(1);
				Service serviceResult = Service.valueOf(resultSet.getInt(2));
				String identifierResult = resultSet.getString(3);
				Integer sizeResult = resultSet.getInt(4);
				Integer status = resultSet.getInt(5);
				Long created = resultSet.getLong(6);
				Long updated = resultSet.getLong(7);
				byte[] latestSignatureResult = resultSet.getBytes(8);

				if (Objects.equals(identifierResult, "default")) {
					// Map "default" back to null. This is optional but probably less confusing than returning "default".
					identifierResult = null;
				}

				ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
				arbitraryResourceData.name = nameResult;
				arbitraryResourceData.service = serviceResult;
				arbitraryResourceData.identifier = identifierResult;
				arbitraryResourceData.size = sizeResult;
				arbitraryResourceData.created = created;
				arbitraryResourceData.updated = (updated == 0) ? null : updated;
				arbitraryResourceData.latestSignature = latestSignatureResult;

				arbitraryResources.add(arbitraryResourceData);
			} while (resultSet.next());

			return arbitraryResources;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch simple arbitrary resources from repository", e);
		}
	}

	private static String getQueryWildcard(String value, boolean prefixOnly, boolean caseInsensitive) {
		String valueToUse = caseInsensitive ? value.toLowerCase() : value;
		return prefixOnly ? String.format("%s%%", valueToUse) : valueToUse;
	}


	// Arbitrary resources cache save/load

	@Override
	public void save(ArbitraryResourceData arbitraryResourceData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("ArbitraryResourcesCache");

		// "status" isn't saved here as we update this field separately
		saveHelper.bind("service", arbitraryResourceData.service.value).bind("name", arbitraryResourceData.name)
				.bind("lower_case_name", arbitraryResourceData.name.toLowerCase())
				.bind("identifier", arbitraryResourceData.identifier).bind("size", arbitraryResourceData.size)
				.bind("created_when", arbitraryResourceData.created).bind("updated_when", arbitraryResourceData.updated)
				.bind( "latest_signature", arbitraryResourceData.latestSignature);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save arbitrary resource info into repository", e);
		}
	}

	@Override
	public void setStatus(ArbitraryResourceData arbitraryResourceData, ArbitraryResourceStatus.Status status) throws DataException {
		if (status == null) {
			return;
		}
		String updateSql = "UPDATE ArbitraryResourcesCache SET status = ? WHERE service = ? AND LCASE(name) = ? AND LCASE(identifier) = ?";

		try {
			this.repository.executeCheckedUpdate(updateSql, status.value, arbitraryResourceData.service.value, arbitraryResourceData.name.toLowerCase(), arbitraryResourceData.identifier.toLowerCase());
		} catch (SQLException e) {
			throw new DataException("Unable to set status for arbitrary resource", e);
		}
	}

	@Override
	public void delete(ArbitraryResourceData arbitraryResourceData) throws DataException {
		// NOTE: arbitrary metadata are deleted automatically by the database thanks to "ON DELETE CASCADE"
		// in ArbitraryMetadataCache' FOREIGN KEY definition.
		try {
			this.repository.delete("ArbitraryResourcesCache", "service = ? AND name = ? AND identifier = ?",
					arbitraryResourceData.service.value, arbitraryResourceData.name, arbitraryResourceData.identifier);

		} catch (SQLException e) {
			throw new DataException("Unable to delete account from repository", e);
		}
	}


	/* Arbitrary metadata cache */

	@Override
	public void save(ArbitraryResourceMetadata metadata) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("ArbitraryMetadataCache");

		ArbitraryResourceData arbitraryResourceData = metadata.getArbitraryResourceData();
		if (arbitraryResourceData == null) {
			throw new DataException("Can't save metadata without a referenced resource");
		}

		// Trim metadata values if they are too long to fit in the db
		String title = ArbitraryDataTransactionMetadata.limitTitle(metadata.getTitle());
		String description = ArbitraryDataTransactionMetadata.limitDescription(metadata.getDescription());
		List<String> tags = ArbitraryDataTransactionMetadata.limitTags(metadata.getTags());

		String tag1 = null;
		String tag2 = null;
		String tag3 = null;
		String tag4 = null;
		String tag5 = null;

		if (tags != null) {
			if (!tags.isEmpty()) tag1 = tags.get(0);
			if (tags.size() > 1) tag2 = tags.get(1);
			if (tags.size() > 2) tag3 = tags.get(2);
			if (tags.size() > 3) tag4 = tags.get(3);
			if (tags.size() > 4) tag5 = tags.get(4);
		}

		String category = metadata.getCategory() != null ? metadata.getCategory().toString() : null;

		saveHelper.bind("service", arbitraryResourceData.service.value).bind("name", arbitraryResourceData.name)
				.bind("identifier", arbitraryResourceData.identifier).bind("title", title)
				.bind("description", description).bind("category", category)
				.bind("tag1", tag1).bind("tag2", tag2).bind("tag3", tag3).bind("tag4", tag4)
				.bind("tag5", tag5);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save arbitrary metadata into repository", e);
		}
	}

	@Override
	public void delete(ArbitraryResourceMetadata metadata) throws DataException {
		ArbitraryResourceData arbitraryResourceData = metadata.getArbitraryResourceData();
		if (arbitraryResourceData == null) {
			throw new DataException("Can't delete metadata without a referenced resource");
		}

		try {
			this.repository.delete("ArbitraryMetadataCache", "service = ? AND name = ? AND identifier = ?",
					arbitraryResourceData.service.value, arbitraryResourceData.name, arbitraryResourceData.identifier);

		} catch (SQLException e) {
			throw new DataException("Unable to delete account from repository", e);
		}
	}
}
