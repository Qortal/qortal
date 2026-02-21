package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataDiff.DiffType;
import org.qortal.arbitrary.ArbitraryDataDiff.ModifiedPath;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataPatch;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.crypto.AES;
import org.qortal.crypto.Crypto;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Compression;
import org.qortal.data.transaction.ArbitraryTransactionData.DataType;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;
import org.qortal.utils.FilesystemUtils;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class ArbitraryDataTransactionBuilder {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataTransactionBuilder.class);

    // Min transaction version required
    private static final int MIN_TRANSACTION_VERSION = 5;

    // Maximum number of PATCH layers allowed
    private static final int MAX_LAYERS = 10;
    // Maximum size difference (out of 1) allowed for PATCH transactions
    private static final double MAX_SIZE_DIFF = 0.2f;
    // Maximum proportion of files modified relative to total
    private static final double MAX_FILE_DIFF = 0.5f;

    private final String publicKey58;
    private final long fee;
    private final Path path;
    private final String name;
    private Method method;
    private final Service service;
    private final String identifier;
    private final Repository repository;

    // Metadata
    private final String title;
    private final String description;
    private final List<String> tags;
    private final Category category;

    private int chunkSize = ArbitraryDataFile.CHUNK_SIZE;

    private ArbitraryTransactionData arbitraryTransactionData;
    private ArbitraryDataFile arbitraryDataFile;

    public ArbitraryDataTransactionBuilder(Repository repository, String publicKey58, long fee, Path path, String name,
                                           Method method, Service service, String identifier,
                                           String title, String description, List<String> tags, Category category) {
        this.repository = repository;
        this.publicKey58 = publicKey58;
        this.fee = fee;
        this.path = path;
        this.name = name;
        this.method = method;
        this.service = service;

        // If identifier is a blank string, or reserved keyword "default", treat it as null
        if (identifier == null || identifier.isEmpty() || identifier.equals("default")) {
            identifier = null;
        }
        this.identifier = identifier;

        // Metadata (optional)
        this.title = ArbitraryDataTransactionMetadata.limitTitle(title);
        this.description = ArbitraryDataTransactionMetadata.limitDescription(description);
        this.tags = ArbitraryDataTransactionMetadata.limitTags(tags);
        this.category = category;
    }

    public void build() throws DataException {
        try {
            this.preExecute();
            this.checkMethod();
            this.createTransaction();
        }
        finally {
            this.postExecute();
        }
    }

    private void preExecute() {

    }

    private void postExecute() {

    }

    private void checkMethod() throws DataException {
        if (this.method == null) {
            // We need to automatically determine the method
            this.method = this.determineMethodAutomatically();
        }
    }

    private Method determineMethodAutomatically() throws DataException {
        return Method.PUT;
    }

    private void createTransaction() throws DataException {
        arbitraryDataFile = null;
        try {
            Long now = NTP.getTime();
            if (now == null) {
                throw new DataException("NTP time not synced yet");
            }

            // Ensure that this chain supports transactions necessary for complex arbitrary data
            int transactionVersion = Transaction.getVersionByTimestamp(now);
            if (transactionVersion < MIN_TRANSACTION_VERSION) {
                throw new DataException("Transaction version unsupported on this blockchain.");
            }

            if (publicKey58 == null || path == null) {
                throw new DataException("Missing public key or path");
            }
            byte[] creatorPublicKey = Base58.decode(publicKey58);
            final String creatorAddress = Crypto.toAddress(creatorPublicKey);
            byte[] lastReference = repository.getAccountRepository().getLastReference(creatorAddress);
            if (lastReference == null) {
                // Use a random last reference on the very first transaction for an account
                // Code copied from CrossChainResource.buildAtMessage()
                // We already require PoW on all arbitrary transactions, so no additional logic is needed
                Random random = new Random();
                lastReference = new byte[Transformer.SIGNATURE_LENGTH];
                random.nextBytes(lastReference);
            }

            // Single file resources are handled differently, especially for very small data payloads, as these go on chain
            final boolean isSingleFileResource = FilesystemUtils.isSingleFileResource(path, false);
            final boolean shouldUseOnChainData = (isSingleFileResource && AES.getEncryptedFileSize(Files.size(path)) <= ArbitraryTransaction.MAX_DATA_SIZE);

            // Use zip compression if data isn't going on chain
            Compression compression = shouldUseOnChainData ? Compression.NONE : Compression.ZIP;

            ArbitraryDataWriter arbitraryDataWriter = new ArbitraryDataWriter(path, name, service, identifier, method,
                    compression, title, description, tags, category);
            try {
                arbitraryDataWriter.setChunkSize(this.chunkSize);
                arbitraryDataWriter.save();
            } catch (IOException | DataException | InterruptedException | RuntimeException | MissingDataException e) {
                LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
                throw new DataException(e.getMessage());
            }

            // Get main file
            arbitraryDataFile = arbitraryDataWriter.getArbitraryDataFile();
            if (arbitraryDataFile == null) {
                throw new DataException("Arbitrary data file is null");
            }

            // Get metadata file
            ArbitraryDataFile metadataFile = arbitraryDataFile.getMetadataFile();
            if (metadataFile == null && arbitraryDataFile.chunkCount() > 1) {
                throw new DataException(String.format("Chunks metadata data file is null but there are %d chunks", arbitraryDataFile.chunkCount()));
            }

            // Default to using a data hash, with data held off-chain
            ArbitraryTransactionData.DataType dataType = ArbitraryTransactionData.DataType.DATA_HASH;
            byte[] data = arbitraryDataFile.digest();

            // For small, single-chunk resources, we can store the data directly on chain
            if (shouldUseOnChainData && arbitraryDataFile.getBytes().length <= ArbitraryTransaction.MAX_DATA_SIZE && arbitraryDataFile.chunkCount() == 0) {
                // Within allowed on-chain data size
                dataType = DataType.RAW_DATA;
                data = arbitraryDataFile.getBytes();
            }

            final BaseTransactionData baseTransactionData = new BaseTransactionData(now, Group.NO_GROUP,
                    lastReference, creatorPublicKey, fee, null);
            final int size = (int) arbitraryDataFile.size();
            final int version = 5;
            final int nonce = 0;
            byte[] secret = arbitraryDataFile.getSecret();

            final byte[] metadataHash = (metadataFile != null) ? metadataFile.getHash() : null;
            final List<PaymentData> payments = new ArrayList<>();

            ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
                    version, service.value, nonce, size, name, identifier, method,
                    secret, compression, data, dataType, metadataHash, payments);

            this.arbitraryTransactionData = transactionData;

        } catch (DataException | IOException e) {
            if (arbitraryDataFile != null) {
                arbitraryDataFile.deleteAll(true);
            }
            throw new DataException(e);
        }

    }

    private boolean isMetadataEqual(ArbitraryDataTransactionMetadata existingMetadata) {
        if (existingMetadata == null) {
            return !this.hasMetadata();
        }
        if (!Objects.equals(existingMetadata.getTitle(), this.title)) {
            return false;
        }
        if (!Objects.equals(existingMetadata.getDescription(), this.description)) {
            return false;
        }
        if (!Objects.equals(existingMetadata.getCategory(), this.category)) {
            return false;
        }
        if (!Objects.equals(existingMetadata.getTags(), this.tags)) {
            return false;
        }
        return true;
    }

    private boolean hasMetadata() {
        return (this.title != null || this.description != null || this.category != null || this.tags != null);
    }

    public void computeNonce() throws DataException {
        if (this.arbitraryTransactionData == null) {
            throw new DataException("Arbitrary transaction data is required to compute nonce");
        }

        ArbitraryTransaction transaction = (ArbitraryTransaction) Transaction.fromData(repository, this.arbitraryTransactionData);
        LOGGER.info("Computing nonce...");
        transaction.computeNonce();

        Transaction.ValidationResult result = transaction.isValidUnconfirmed();
        if (result != Transaction.ValidationResult.OK) {
            arbitraryDataFile.deleteAll(true);
            throw new DataException(String.format("Arbitrary transaction invalid: %s", result));
        }
        LOGGER.info("Transaction is valid");
    }

    public ArbitraryTransactionData getArbitraryTransactionData() {
        return this.arbitraryTransactionData;
    }

    public ArbitraryDataFile getArbitraryDataFile() {
        return this.arbitraryDataFile;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

}
