package org.qortal.transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataResource;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Service;
import org.qortal.block.BlockChain;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
import org.qortal.controller.arbitrary.ArbitraryTransactionDataHashWrapper;
import org.qortal.controller.repository.NamesDatabaseIntegrityCheck;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.PaymentData;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.payment.Payment;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.ArbitraryTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ArbitraryTransaction extends Transaction {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryTransaction.class);

	// Properties
	private ArbitraryTransactionData arbitraryTransactionData;

	// Other useful constants
	public static final int MAX_DATA_SIZE = 256;
	public static final int MAX_METADATA_LENGTH = 32;
	public static final int HASH_LENGTH = TransactionTransformer.SHA256_LENGTH;
	public static final int MAX_IDENTIFIER_LENGTH = 64;

	/** If time difference between transaction and now is greater than this then we don't verify proof-of-work. */
	public static final long HISTORIC_THRESHOLD = 2 * 7 * 24 * 60 * 60 * 1000L;
	public static final int POW_BUFFER_SIZE = 8 * 1024 * 1024; // bytes


	// Constructors

	public ArbitraryTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.arbitraryTransactionData = (ArbitraryTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return this.arbitraryTransactionData.getPayments().stream().map(PaymentData::getRecipient).collect(Collectors.toList());
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	// Processing

	public void computeNonce() throws DataException {
		byte[] transactionBytes;

		try {
			transactionBytes = TransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		// Clear nonce from transactionBytes
		ArbitraryTransactionTransformer.clearNonce(transactionBytes);

		// Calculate nonce
		int difficulty = ArbitraryDataManager.getInstance().getPowDifficulty();
		this.arbitraryTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, POW_BUFFER_SIZE, difficulty));
	}

	@Override
	public ValidationResult isFeeValid() throws DataException {
		if (this.transactionData.getFee() < 0)
			return ValidationResult.NEGATIVE_FEE;

		// As of the mempow transaction updates timestamp, a nonce is no longer supported, so a valid fee must be included
		if (this.transactionData.getTimestamp() >= BlockChain.getInstance().getMemPoWTransactionUpdatesTimestamp()) {
			// Validate the fee
			return super.isFeeValid();
		}

		// After the earlier "optional fee" feature trigger, we required the fee to be sufficient if it wasn't 0.
		// If the fee was zero, then the nonce was validated in isSignatureValid() as an alternative to a fee
		if (this.arbitraryTransactionData.getTimestamp() >= BlockChain.getInstance().getArbitraryOptionalFeeTimestamp() && this.arbitraryTransactionData.getFee() != 0L) {
			return super.isFeeValid();
		}

		return ValidationResult.OK;
	}

	@Override
	public boolean hasValidReference() throws DataException {
		// We shouldn't really get this far, but just in case:

		// Disable reference checking after feature trigger timestamp
		if (this.arbitraryTransactionData.getTimestamp() >= BlockChain.getInstance().getDisableReferenceTimestamp()) {
			// Allow any value as long as it is the correct length
			return this.arbitraryTransactionData.getReference() != null &&
					this.arbitraryTransactionData.getReference().length == Transformer.SIGNATURE_LENGTH;
		}

		if (this.arbitraryTransactionData.getReference() == null) {
			return false;
		}

		// If the account current doesn't have a last reference, and the fee is 0, we will allow any value.
		// This ensures that the first transaction for an account will be valid whilst still validating
		// the last reference from the second transaction onwards. By checking for a zero fee, we ensure
		// standard last reference validation when fee > 0.
		Account creator = getCreator();
		Long fee = this.arbitraryTransactionData.getFee();
		if (creator.getLastReference() == null && fee == 0) {
			return true;
		}

		return super.hasValidReference();
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Check that some data - or a data hash - has been supplied
		if (arbitraryTransactionData.getData() == null) {
			return ValidationResult.INVALID_DATA_LENGTH;
		}

		// Check data length
		if (arbitraryTransactionData.getData().length < 1 || arbitraryTransactionData.getData().length > MAX_DATA_SIZE) {
			return ValidationResult.INVALID_DATA_LENGTH;
		}

		// Check hashes and metadata
		if (arbitraryTransactionData.getDataType() == ArbitraryTransactionData.DataType.DATA_HASH) {
			// Check length of data hash
			if (arbitraryTransactionData.getData().length != HASH_LENGTH) {
				return ValidationResult.INVALID_DATA_LENGTH;
			}

			// Version 5+
			if (arbitraryTransactionData.getVersion() >= 5) {
				byte[] metadata = arbitraryTransactionData.getMetadataHash();

				// Check maximum length of metadata hash
				if (metadata != null && metadata.length > MAX_METADATA_LENGTH) {
					return ValidationResult.INVALID_DATA_LENGTH;
				}
			}
		}

		// Check raw data
		if (arbitraryTransactionData.getDataType() == ArbitraryTransactionData.DataType.RAW_DATA) {
			// Version 5+
			if (arbitraryTransactionData.getVersion() >= 5) {
				// Check reported length of the raw data
				// We should not download the raw data, so validation of that will be performed later
				if (arbitraryTransactionData.getSize() > ArbitraryDataFile.MAX_FILE_SIZE) {
					return ValidationResult.INVALID_DATA_LENGTH;
				}
			}
		}

		// Check name if one has been included
		if (arbitraryTransactionData.getName() != null) {
			NameData nameData = this.repository.getNameRepository().fromName(arbitraryTransactionData.getName());

			// Check the name is registered
			if (nameData == null) {
				return ValidationResult.NAME_DOES_NOT_EXIST;
			}

			// Check that the transaction signer owns the name
			if (!Objects.equals(this.getCreator().getAddress(), nameData.getOwner())) {
				return ValidationResult.INVALID_NAME_OWNER;
			}
		}

		// Wrap and delegate final payment validity checks to Payment class
		return new Payment(this.repository).isValid(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments(),
				arbitraryTransactionData.getFee());
	}

	@Override
	public boolean isSignatureValid() {
		byte[] signature = this.transactionData.getSignature();
		if (signature == null) {
			return false;
		}

		byte[] transactionBytes;

		try {
			transactionBytes = ArbitraryTransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		if (!Crypto.verify(this.transactionData.getCreatorPublicKey(), signature, transactionBytes)) {
			return false;
		}

		// Nonce wasn't added until version 5+
		if (arbitraryTransactionData.getVersion() >= 5) {

			int nonce = arbitraryTransactionData.getNonce();

			// Clear nonce from transactionBytes
			ArbitraryTransactionTransformer.clearNonce(transactionBytes);

			// As of the mempow transaction updates timestamp, a nonce is no longer supported, so a fee must be included
			if (this.transactionData.getTimestamp() >= BlockChain.getInstance().getMemPoWTransactionUpdatesTimestamp()) {
				// Require that the fee is a positive number. Fee checking itself is performed in isFeeValid()
				return (this.arbitraryTransactionData.getFee() > 0L);
			}

			// As of the earlier "optional fee" feature-trigger timestamp, we only required a nonce when the fee was zero
			boolean beforeFeatureTrigger = this.arbitraryTransactionData.getTimestamp() < BlockChain.getInstance().getArbitraryOptionalFeeTimestamp();
			if (beforeFeatureTrigger || this.arbitraryTransactionData.getFee() == 0L) {
				// We only need to check nonce for recent transactions due to PoW verification overhead
				if (NTP.getTime() - this.arbitraryTransactionData.getTimestamp() < HISTORIC_THRESHOLD) {
					int difficulty = ArbitraryDataManager.getInstance().getPowDifficulty();
					return MemoryPoW.verify2(transactionBytes, POW_BUFFER_SIZE, difficulty, nonce);
				}
			}
		}

		return true;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Wrap and delegate final payment processable checks to Payment class
		return new Payment(this.repository).isProcessable(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments(),
				arbitraryTransactionData.getFee());
	}

	@Override
	protected void onImportAsUnconfirmed() throws DataException {
		// We may need to move files from the misc_ folder
		ArbitraryTransactionUtils.checkAndRelocateMiscFiles(arbitraryTransactionData);

		// Update caches
		updateCaches(true);
	}

	@Override
	public void preProcess() throws DataException {
		ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;
		if (arbitraryTransactionData.getName() == null)
			return;

		// Rebuild this name in the Names table from the transaction history
		// This is necessary because in some rare cases names can be missing from the Names table after registration
		// but we have been unable to reproduce the issue and track down the root cause
		NamesDatabaseIntegrityCheck namesDatabaseIntegrityCheck = new NamesDatabaseIntegrityCheck();
		namesDatabaseIntegrityCheck.rebuildName(arbitraryTransactionData.getName(), this.repository);
	}

	@Override
	public void process() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).process(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments());

		// Update caches
		this.updateCaches(false);
	}

	private void updateCaches(boolean autoInvalidate) {
		// Very important to use a separate repository instance from the one being used for validation/processing
		try (final Repository repository = RepositoryManager.getRepository()) {
			// If the data is local, we need to perform a few actions
			if (isDataLocal()) {

				// We have the data for this transaction, so invalidate the file cache
				if (arbitraryTransactionData.getName() != null) {
					ArbitraryDataManager.getInstance().invalidateCache(arbitraryTransactionData, true);
				}
			}
			else if( autoInvalidate ) {
				if (arbitraryTransactionData.getName() != null) {
					ArbitraryDataManager.getInstance().invalidateCache(arbitraryTransactionData, false);
				}
			}

			// Add/update arbitrary resource caches, but don't update the status as this involves time-consuming
			// disk reads, and is more prone to failure. The status will be updated on metadata retrieval, or when
			// accessing the resource.
			// Also, must add this transaction as a latest transaction, since the it has not been saved to the
			// repository yet.
			this.updateArbitraryResourceCacheIncludingMetadata(
				repository,
				Set.of(new ArbitraryTransactionDataHashWrapper(arbitraryTransactionData)),
				new HashMap<>(0)
			);

			repository.saveChanges();

		} catch (Exception e) {
			// Log and ignore all exceptions. The cache is updated from other places too, and can be rebuilt if needed.
			LOGGER.info("Unable to update arbitrary caches", e);
		}
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// Wrap and delegate reference and fee processing to Payment class. Always update recipients' last references regardless of asset.
		new Payment(this.repository).processReferencesAndFees(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments(),
				arbitraryTransactionData.getFee(), arbitraryTransactionData.getSignature(), true);
	}

	@Override
	public void orphan() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).orphan(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Wrap and delegate reference and fee processing to Payment class. Always revert recipients' last references regardless of asset.
		new Payment(this.repository).orphanReferencesAndFees(arbitraryTransactionData.getSenderPublicKey(), arbitraryTransactionData.getPayments(),
				arbitraryTransactionData.getFee(), arbitraryTransactionData.getSignature(), arbitraryTransactionData.getReference(), true);
	}

	// Data access

	public boolean isDataLocal() throws DataException {
		return this.repository.getArbitraryRepository().isDataLocal(this.transactionData.getSignature());
	}

	/** Returns arbitrary data payload, fetching from network if needed. Can block for a while! */
	public byte[] fetchData() throws DataException {
		// If local, read from file
		if (isDataLocal()) {
			return this.repository.getArbitraryRepository().fetchData(this.transactionData.getSignature());
		}
		return null;
	}

	/**
	 * Update the arbitrary resources cache.
	 * This finds the latest transaction and replaces the
	 * majority of the data in the cache. The current
	 * transaction is used for the created time,
	 * if it has a lower timestamp than the existing value.
	 * It's also used to identify the correct
	 * service/name/identifier combination.
	 *
	 * @throws DataException
	 */
	public void updateArbitraryResourceCacheIncludingMetadata(
			Repository repository,
			Set<ArbitraryTransactionDataHashWrapper> latestTransactionWrappers,
			Map<ArbitraryTransactionDataHashWrapper, ArbitraryResourceData> resourceByWrapper) throws DataException {
		// Don't cache resources without a name (such as auto updates)
		if (arbitraryTransactionData.getName() == null) {
			return;
		}

		Service service = arbitraryTransactionData.getService();
		String name = arbitraryTransactionData.getName();
		String identifier = arbitraryTransactionData.getIdentifier();

		if (service == null) {
			// Unsupported service - ignore this resource
			return;
		}

		// In the cache we store null identifiers as "default", as it is part of the primary key
		if (identifier == null) {
			identifier = "default";
		}

		ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
		arbitraryResourceData.service = service;
		arbitraryResourceData.name = name;
		arbitraryResourceData.identifier = identifier;

		final ArbitraryTransactionDataHashWrapper wrapper = new ArbitraryTransactionDataHashWrapper(arbitraryTransactionData);

		ArbitraryTransactionData latestTransactionData;
		if( latestTransactionWrappers.contains(wrapper)) {
			latestTransactionData
				= latestTransactionWrappers.stream()
					.filter( latestWrapper -> latestWrapper.equals(wrapper))
					.findAny().get()
					.getData();
		}
		else {
			// Get the latest transaction
			latestTransactionData = repository.getArbitraryRepository().getLatestTransaction(arbitraryTransactionData.getName(), arbitraryTransactionData.getService(), null, arbitraryTransactionData.getIdentifier());
			if (latestTransactionData == null) {
				LOGGER.info("We don't have a latest transaction, so delete from cache: arbitraryResourceData = " + arbitraryResourceData);
				// We don't have a latest transaction, so delete from cache
				repository.getArbitraryRepository().delete(arbitraryResourceData);
				return;
			}
		}
		ArbitraryResourceData existingArbitraryResourceData = resourceByWrapper.get(wrapper);

		if( existingArbitraryResourceData == null ) {
			// Get existing cached entry if it exists
			existingArbitraryResourceData = repository.getArbitraryRepository()
					.getArbitraryResource(service, name, identifier);
		}

		// Check for existing cached data
		if (existingArbitraryResourceData == null) {
			// Nothing exists yet, so set creation date from the current transaction (it will be reduced later if needed)
			arbitraryResourceData.created = arbitraryTransactionData.getTimestamp();
			arbitraryResourceData.updated = null;
		}
		else {
			resourceByWrapper.put(wrapper, existingArbitraryResourceData);
			// An entry already exists - update created time from current transaction if this is older
			arbitraryResourceData.created = Math.min(existingArbitraryResourceData.created, arbitraryTransactionData.getTimestamp());

			// Set updated time to the latest transaction's timestamp, unless it matches the creation time
			if (existingArbitraryResourceData.created == latestTransactionData.getTimestamp()) {
				// Latest transaction matches created time, so it hasn't been updated
				arbitraryResourceData.updated = null;
			}
			else {
				arbitraryResourceData.updated = latestTransactionData.getTimestamp();
			}
		}

		arbitraryResourceData.size = latestTransactionData.getSize();

		// Save
		repository.getArbitraryRepository().save(arbitraryResourceData);

		// Update metadata for latest transaction if it is local
		if (latestTransactionData.getMetadataHash() != null) {
			ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(latestTransactionData.getMetadataHash(), latestTransactionData.getSignature());
			if (metadataFile.exists()) {
				ArbitraryDataTransactionMetadata transactionMetadata = new ArbitraryDataTransactionMetadata(metadataFile.getFilePath());
				try {
					transactionMetadata.read();

					ArbitraryResourceMetadata metadata = new ArbitraryResourceMetadata();
					metadata.setArbitraryResourceData(arbitraryResourceData);
					metadata.setTitle(transactionMetadata.getTitle());
					metadata.setDescription(transactionMetadata.getDescription());
					metadata.setCategory(transactionMetadata.getCategory());
					metadata.setTags(transactionMetadata.getTags());
					repository.getArbitraryRepository().save(metadata);

				} catch (IOException e) {
					// Ignore, as we can add it again later
				}
			} else {
				// We don't have a local copy of this metadata file, so delete it from the cache
				// It will be re-added if the file later arrives via the network
				ArbitraryResourceMetadata metadata = new ArbitraryResourceMetadata();
				metadata.setArbitraryResourceData(arbitraryResourceData);
				repository.getArbitraryRepository().delete(metadata);
			}
		}
	}

	public void updateArbitraryResourceStatus(Repository repository) throws DataException {
		// Don't cache resources without a name (such as auto updates)
		if (arbitraryTransactionData.getName() == null) {
			return;
		}

		Service service = arbitraryTransactionData.getService();
		String name = arbitraryTransactionData.getName();
		String identifier = arbitraryTransactionData.getIdentifier();

		if (service == null) {
			// Unsupported service - ignore this resource
			return;
		}

		// In the cache we store null identifiers as "default", as it is part of the primary key
		if (identifier == null) {
			identifier = "default";
		}

		ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
		arbitraryResourceData.service = service;
		arbitraryResourceData.name = name;
		arbitraryResourceData.identifier = identifier;

		// Update status
		ArbitraryDataResource resource = new ArbitraryDataResource(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
		ArbitraryResourceStatus arbitraryResourceStatus = resource.getStatus(repository);
		ArbitraryResourceStatus.Status status = arbitraryResourceStatus != null ? arbitraryResourceStatus.getStatus() : null;
		repository.getArbitraryRepository().setStatus(arbitraryResourceData, status);
	}

}
