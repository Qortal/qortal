package org.qortal.test.arbitrary;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.ArbitraryDataTransactionBuilder;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.block.BlockChain;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
import org.qortal.crypto.Crypto;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryTransactionTests extends Common {

    @Before
    public void beforeTest() throws DataException, IllegalAccessException {
        Common.useDefaultSettings();
    }

    @Test
    public void testNonceAndFee() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction, with a fee
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 10000000; // sufficient
            boolean computeNonce = true;
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);

            // Check that nonce validation succeeds
            byte[] signature = arbitraryDataFile.getSignature();
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);
            assertTrue(transaction.isSignatureValid());

            // Increase difficulty to 15
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 15, true);

            // Make sure that nonce validation still succeeds, as the fee has allowed us to avoid including a nonce
            assertTrue(transaction.isSignatureValid());
        }
    }

    @Test
    public void testNonceAndLowFee() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction, with a fee that is too low
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 9999999; // insufficient
            boolean computeNonce = true;
            boolean insufficientFeeDetected = false;
            try {
                ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);
            }
            catch (DataException e) {
                if (e.getMessage().contains("INSUFFICIENT_FEE")) {
                    insufficientFeeDetected = true;
                }
            }

            // Transaction should be invalid due to an insufficient fee
            assertTrue(insufficientFeeDetected);
        }
    }

    @Test
    public void testFeeNoNonce() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction, with a fee
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 10000000; // sufficient
            boolean computeNonce = false;
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);

            // Check that nonce validation succeeds, even though it wasn't computed. This is because we have included a sufficient fee.
            byte[] signature = arbitraryDataFile.getSignature();
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);
            assertTrue(transaction.isSignatureValid());

            // Increase difficulty to 15
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 15, true);

            // Make sure that nonce validation still succeeds, as the fee has allowed us to avoid including a nonce
            assertTrue(transaction.isSignatureValid());
        }
    }

    @Test
    public void testLowFeeNoNonce() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction, with a fee that is too low. Also, don't compute a nonce.
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 9999999; // insufficient

            ArbitraryDataTransactionBuilder txnBuilder = new ArbitraryDataTransactionBuilder(
                    repository, publicKey58, fee, path1, name, ArbitraryTransactionData.Method.PUT, service, identifier, null, null, null, null);

            txnBuilder.setChunkSize(chunkSize);
            txnBuilder.build();
            ArbitraryTransactionData transactionData = txnBuilder.getArbitraryTransactionData();
            Transaction.ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);

            // Transaction should be invalid due to an insufficient fee
            assertEquals(Transaction.ValidationResult.INSUFFICIENT_FEE, result);
        }
    }

    @Test
    public void testZeroFeeNoNonce() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction, with a fee that is too low. Also, don't compute a nonce.
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 0L;

            ArbitraryDataTransactionBuilder txnBuilder = new ArbitraryDataTransactionBuilder(
                    repository, publicKey58, fee, path1, name, ArbitraryTransactionData.Method.PUT, service, identifier, null, null, null, null);

            txnBuilder.setChunkSize(chunkSize);
            txnBuilder.build();
            ArbitraryTransactionData transactionData = txnBuilder.getArbitraryTransactionData();
            ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);

            // Transaction should be invalid
            assertFalse(arbitraryTransaction.isSignatureValid());
        }
    }

    @Test
    public void testNonceAndFeeBeforeFeatureTrigger() throws IllegalAccessException, DataException, IOException {
        // Use v2-minting settings, as these are pre-feature-trigger
        Common.useSettings("test-settings-v2-minting.json");

        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction, with a fee
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 10000000; // sufficient
            boolean computeNonce = true;
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);

            // Check that nonce validation succeeds
            byte[] signature = arbitraryDataFile.getSignature();
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);
            assertTrue(transaction.isSignatureValid());

            // Increase difficulty to 15
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 15, true);

            // Make sure the nonce validation fails, as we aren't allowing a fee to replace a nonce yet.
            // Note: there is a very tiny chance this could succeed due to being extremely lucky
            // and finding a high difficulty nonce in the first couple of cycles. It will be rare
            // enough that we shouldn't need to account for it.
            assertFalse(transaction.isSignatureValid());

            // Reduce difficulty back to 1, to double check
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);
            assertTrue(transaction.isSignatureValid());
        }
    }

    @Test
    public void testNonceAndInsufficientFeeBeforeFeatureTrigger() throws IllegalAccessException, DataException, IOException {
        // Use v2-minting settings, as these are pre-feature-trigger
        Common.useSettings("test-settings-v2-minting.json");

        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction, with a fee
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 9999999; // insufficient
            boolean computeNonce = true;
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);

            // Check that nonce validation succeeds
            byte[] signature = arbitraryDataFile.getSignature();
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);
            assertTrue(transaction.isSignatureValid());

            // The transaction should be valid because we don't care about the fee (before the feature trigger)
            assertEquals(Transaction.ValidationResult.OK, transaction.isValidUnconfirmed());

            // Increase difficulty to 15
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 15, true);

            // Make sure the nonce validation fails, as we aren't allowing a fee to replace a nonce yet (and it was insufficient anyway)
            // Note: there is a very tiny chance this could succeed due to being extremely lucky
            // and finding a high difficulty nonce in the first couple of cycles. It will be rare
            // enough that we shouldn't need to account for it.
            assertFalse(transaction.isSignatureValid());

            // Reduce difficulty back to 1, to double check
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);
            assertTrue(transaction.isSignatureValid());
        }
    }

    @Test
    public void testNonceAndZeroFeeBeforeFeatureTrigger() throws IllegalAccessException, DataException, IOException {
        // Use v2-minting settings, as these are pre-feature-trigger
        Common.useSettings("test-settings-v2-minting.json");

        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction, with a fee
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 0L;
            boolean computeNonce = true;
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);

            // Check that nonce validation succeeds
            byte[] signature = arbitraryDataFile.getSignature();
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);
            assertTrue(transaction.isSignatureValid());

            // The transaction should be valid because we don't care about the fee (before the feature trigger)
            assertEquals(Transaction.ValidationResult.OK, transaction.isValidUnconfirmed());

            // Increase difficulty to 15
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 15, true);

            // Make sure the nonce validation fails, as we aren't allowing a fee to replace a nonce yet (and it was insufficient anyway)
            // Note: there is a very tiny chance this could succeed due to being extremely lucky
            // and finding a high difficulty nonce in the first couple of cycles. It will be rare
            // enough that we shouldn't need to account for it.
            assertFalse(transaction.isSignatureValid());

            // Reduce difficulty back to 1, to double check
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);
            assertTrue(transaction.isSignatureValid());
        }
    }

    @Test
    public void testInvalidService() {
        byte[] randomHash = new byte[32];
        new Random().nextBytes(randomHash);

        byte[] lastReference = new byte[64];
        new Random().nextBytes(lastReference);

        Long now = NTP.getTime();

        final BaseTransactionData baseTransactionData = new BaseTransactionData(now, Group.NO_GROUP,
                lastReference, randomHash, 0L, null);
        final String name = "test";
        final String identifier = "test";
        final ArbitraryTransactionData.Method method = ArbitraryTransactionData.Method.PUT;
        final ArbitraryTransactionData.Compression compression = ArbitraryTransactionData.Compression.ZIP;
        final int size = 999;
        final int version = 5;
        final int nonce = 0;
        final byte[] secret = randomHash;
        final ArbitraryTransactionData.DataType dataType = ArbitraryTransactionData.DataType.DATA_HASH;
        final byte[] digest = randomHash;
        final byte[] metadataHash = null;
        final List<PaymentData> payments = new ArrayList<>();
        final int validService = Service.IMAGE.value;
        final int invalidService = 99999999;

        // Try with valid service
        ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
                version, validService, nonce, size, name, identifier, method,
                secret, compression, digest, dataType, metadataHash, payments);
        assertEquals(Service.IMAGE, transactionData.getService());

        // Try with invalid service
        transactionData = new ArbitraryTransactionData(baseTransactionData,
                version, invalidService, nonce, size, name, identifier, method,
                secret, compression, digest, dataType, metadataHash, payments);
        assertNull(transactionData.getService());
    }

    @Test
    public void testOnChainData() throws DataException, IOException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Set difficulty to 1 to speed up the tests
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 1000;
            int dataLength = 228; // Max possible size. Becomes 256 bytes after encryption.

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength, true);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    null, null, null, null);

            byte[] signature = arbitraryDataFile.getSignature();
            ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(signature);

            // Check that the data is published on chain
            assertEquals(ArbitraryTransactionData.DataType.RAW_DATA, arbitraryTransactionData.getDataType());
            assertEquals(arbitraryDataFile.getBytes().length, arbitraryTransactionData.getData().length);
            assertArrayEquals(arbitraryDataFile.getBytes(), arbitraryTransactionData.getData());

            // Check that we have no chunks because the complete file is already less than the chunk size
            assertEquals(0, arbitraryDataFile.chunkCount());

            // Check that we have one file total - just the complete file (no chunks or metadata)
            assertEquals(1, arbitraryDataFile.fileCount());

            // Check the metadata isn't present
            assertNull(arbitraryDataFile.getMetadata());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);

            // Filename will be "data" because it's been held as raw bytes in the transaction,
            // so there is nowhere to store the original filename
            File outputFile = Paths.get(arbitraryDataReader.getFilePath().toString(), "data").toFile();

            assertArrayEquals(Crypto.digest(outputFile), Crypto.digest(path1.toFile()));
        }
    }

    @Test
    public void testOnChainDataWithMetadata() throws DataException, IOException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Set difficulty to 1 to speed up the tests
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 1000;
            int dataLength = 228; // Max possible size. Becomes 256 bytes after encryption.

            String title = "Test title";
            String description = "Test description";
            List<String> tags = Arrays.asList("Test", "tag", "another tag");
            Category category = Category.QORTAL;

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength, true);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    title, description, tags, category);

            byte[] signature = arbitraryDataFile.getSignature();
            ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(signature);

            // Check that the data is published on chain
            assertEquals(ArbitraryTransactionData.DataType.RAW_DATA, arbitraryTransactionData.getDataType());
            assertEquals(arbitraryDataFile.getBytes().length, arbitraryTransactionData.getData().length);
            assertArrayEquals(arbitraryDataFile.getBytes(), arbitraryTransactionData.getData());

            // Check that we have no chunks because the complete file is already less than the chunk size
            assertEquals(0, arbitraryDataFile.chunkCount());

            // Check that we have two files total - one for the complete file, and the other for the metadata
            assertEquals(2, arbitraryDataFile.fileCount());

            // Check the metadata is correct
            assertEquals(title, arbitraryDataFile.getMetadata().getTitle());
            assertEquals(description, arbitraryDataFile.getMetadata().getDescription());
            assertEquals(tags, arbitraryDataFile.getMetadata().getTags());
            assertEquals(category, arbitraryDataFile.getMetadata().getCategory());
            assertEquals("text/plain", arbitraryDataFile.getMetadata().getMimeType());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);

            // Filename will be "data" because it's been held as raw bytes in the transaction,
            // so there is nowhere to store the original filename
            File outputFile = Paths.get(arbitraryDataReader.getFilePath().toString(), "data").toFile();

            assertArrayEquals(Crypto.digest(outputFile), Crypto.digest(path1.toFile()));
        }
    }

    @Test
    public void testOffChainData() throws DataException, IOException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Set difficulty to 1 to speed up the tests
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 1000;
            int dataLength = 229; // Min possible size. Becomes 257 bytes after encryption.

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength, true);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    null, null, null, null);

            byte[] signature = arbitraryDataFile.getSignature();
            ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(signature);

            // Check that the data is published on chain
            assertEquals(ArbitraryTransactionData.DataType.DATA_HASH, arbitraryTransactionData.getDataType());
            assertEquals(TransactionTransformer.SHA256_LENGTH, arbitraryTransactionData.getData().length);
            assertFalse(Arrays.equals(arbitraryDataFile.getBytes(), arbitraryTransactionData.getData()));

            // Check that we have no chunks because the complete file is already less than the chunk size
            assertEquals(0, arbitraryDataFile.chunkCount());

            // Check that we have one file total - just the complete file (no chunks or metadata)
            assertEquals(1, arbitraryDataFile.fileCount());

            // Check the metadata isn't present
            assertNull(arbitraryDataFile.getMetadata());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);

            // File content should match original file
            File outputFile = Paths.get(arbitraryDataReader.getFilePath().toString(), "file.txt").toFile();
            assertArrayEquals(Crypto.digest(outputFile), Crypto.digest(path1.toFile()));
        }
    }
}
