package org.qortal.test.arbitrary;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.ArbitraryDataDigest;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.block.BlockChain;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryTransactionMetadataTests extends Common {

    @Before
    public void beforeTest() throws DataException, IllegalAccessException {
        Common.useDefaultSettings();

        // Set difficulty to 1 to speed up the tests
        FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);
    }

    @Test
    public void testMultipleChunks() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize);

            // Check the chunk count is correct
            assertEquals(10, arbitraryDataFile.chunkCount());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);
            Path initialLayerPath = arbitraryDataReader.getFilePath();
            ArbitraryDataDigest initialLayerDigest = new ArbitraryDataDigest(initialLayerPath);
            initialLayerDigest.compute();

            // Its directory hash should match the original directory hash
            ArbitraryDataDigest path1Digest = new ArbitraryDataDigest(path1);
            path1Digest.compute();
            assertEquals(path1Digest.getHash58(), initialLayerDigest.getHash58());
        }
    }

    @Test
    public void testSingleChunkWithMetadata() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 10000;
            int dataLength = 1000; // Actual data length will be longer due to encryption

            String title = "Test title";
            String description = "Test description";
            List<String> tags = Arrays.asList("Test", "tag", "another tag");
            Category category = Category.QORTAL;

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    title, description, tags, category);

            // Check the chunk count is correct
            assertEquals(0, arbitraryDataFile.chunkCount());

            // Check the metadata is correct
            assertEquals(title, arbitraryDataFile.getMetadata().getTitle());
            assertEquals(description, arbitraryDataFile.getMetadata().getDescription());
            assertEquals(tags, arbitraryDataFile.getMetadata().getTags());
            assertEquals(category, arbitraryDataFile.getMetadata().getCategory());
            assertEquals("text/plain", arbitraryDataFile.getMetadata().getMimeType());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);
            Path initialLayerPath = arbitraryDataReader.getFilePath();
            ArbitraryDataDigest initialLayerDigest = new ArbitraryDataDigest(initialLayerPath);
            initialLayerDigest.compute();

            // Its directory hash should match the original directory hash
            ArbitraryDataDigest path1Digest = new ArbitraryDataDigest(path1);
            path1Digest.compute();
            assertEquals(path1Digest.getHash58(), initialLayerDigest.getHash58());
        }
    }

    @Test
    public void testSingleNonLocalChunkWithMetadata() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 10000;
            int dataLength = 1000; // Actual data length will be longer due to encryption

            String title = "Test title";
            String description = "Test description";
            List<String> tags = Arrays.asList("Test", "tag", "another tag");
            Category category = Category.QORTAL;

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    title, description, tags, category);

            // Check the chunk count is correct
            assertEquals(0, arbitraryDataFile.chunkCount());

            // Check the metadata is correct
            assertEquals(title, arbitraryDataFile.getMetadata().getTitle());
            assertEquals(description, arbitraryDataFile.getMetadata().getDescription());
            assertEquals(tags, arbitraryDataFile.getMetadata().getTags());
            assertEquals(category, arbitraryDataFile.getMetadata().getCategory());
            assertEquals("text/plain", arbitraryDataFile.getMetadata().getMimeType());

            // Delete the file, to simulate that it hasn't been fetched from the network yet
            arbitraryDataFile.delete();

            boolean missingDataExceptionCaught = false;
            boolean ioExceptionCaught = false;

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ResourceIdType.NAME, service, identifier);
            try {
                arbitraryDataReader.loadSynchronously(true);
            }
            catch (MissingDataException e) {
                missingDataExceptionCaught = true;
            }
            catch (IOException e) {
                ioExceptionCaught = true;
            }

            // We expect a MissingDataException, not an IOException.
            // This is because MissingDataException means that the core has correctly identified a file is missing,
            // whereas an IOException would be due to trying to build without first having everything that is needed.
            assertTrue(missingDataExceptionCaught);
            assertFalse(ioExceptionCaught);
        }
    }

    @Test
    public void testDescriptiveMetadata() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            String title = "Test title";
            String description = "Test description";
            List<String> tags = Arrays.asList("Test", "tag", "another tag");
            Category category = Category.QORTAL;

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    title, description, tags, category);

            // Check the chunk count is correct
            assertEquals(10, arbitraryDataFile.chunkCount());

            // Check the metadata is correct
            assertEquals(title, arbitraryDataFile.getMetadata().getTitle());
            assertEquals(description, arbitraryDataFile.getMetadata().getDescription());
            assertEquals(tags, arbitraryDataFile.getMetadata().getTags());
            assertEquals(category, arbitraryDataFile.getMetadata().getCategory());
            assertEquals("text/plain", arbitraryDataFile.getMetadata().getMimeType());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);
            Path initialLayerPath = arbitraryDataReader.getFilePath();
            ArbitraryDataDigest initialLayerDigest = new ArbitraryDataDigest(initialLayerPath);
            initialLayerDigest.compute();

            // Its directory hash should match the original directory hash
            ArbitraryDataDigest path1Digest = new ArbitraryDataDigest(path1);
            path1Digest.compute();
            assertEquals(path1Digest.getHash58(), initialLayerDigest.getHash58());
        }
    }

    @Test
    public void testUTF8Metadata() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Example (modified) strings from real world content
            String title = "Доля юаня в трансграничных Доля юаня в трансграничных";
            String description = "Когда рыночек порешал";
            List<String> tags = Arrays.asList("Доля", "юаня", "трансграничных");
            Category category = Category.OTHER;

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    title, description, tags, category);

            // Check the chunk count is correct
            assertEquals(10, arbitraryDataFile.chunkCount());

            // Check the metadata is correct
            String expectedTrimmedTitle = "Доля юаня в трансграничных Доля юаня в тран";
            assertEquals(expectedTrimmedTitle, arbitraryDataFile.getMetadata().getTitle());
            assertEquals(description, arbitraryDataFile.getMetadata().getDescription());
            assertEquals(tags, arbitraryDataFile.getMetadata().getTags());
            assertEquals(category, arbitraryDataFile.getMetadata().getCategory());
            assertEquals("text/plain", arbitraryDataFile.getMetadata().getMimeType());
        }
    }

    @Test
    public void testMetadataLengths() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            String title = "title Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent feugiat pretium";
            String description = "description Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent feugiat pretium massa, non pulvinar mi pretium id. Ut gravida sapien vitae dui posuere tincidunt. Quisque in nibh est. Curabitur at blandit nunc, id aliquet neque. Nulla condimentum eget dolor a egestas. Vestibulum vel tincidunt ex. Vestibulum ante ipsum primis in faucibus orci luctus et ultrices posuere cubilia curae; Cras congue lacus in risus mattis suscipit. Quisque nisl eros, facilisis a lorem quis, vehicula bibendum.";
            List<String> tags = Arrays.asList("tag 1", "tag 2", "tag 3 that is longer than the 20 character limit", "tag 4", "tag 5", "tag 6", "tag 7");
            Category category = Category.CRYPTOCURRENCY;

            String expectedTitle = "title Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent feugiat "; // 80 chars
            String expectedDescription = "description Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent feugiat pretium massa, non pulvinar mi pretium id. Ut gravida sapien vitae dui posuere tincidunt. Quisque in nibh est. Curabitur at blandit nunc, id aliquet neque"; // 240 chars
            List<String> expectedTags = Arrays.asList("tag 1", "tag 2", "tag 4", "tag 5", "tag 6");

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    title, description, tags, category);

            // Check the metadata is correct
            assertEquals(expectedTitle, arbitraryDataFile.getMetadata().getTitle());
            assertEquals(expectedDescription, arbitraryDataFile.getMetadata().getDescription());
            assertEquals(expectedTags, arbitraryDataFile.getMetadata().getTags());
            assertEquals(category, arbitraryDataFile.getMetadata().getCategory());
        }
    }

    @Test
    public void testSingleFileList() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Add a few files at multiple levels
            byte[] data = new byte[1024];
            new Random().nextBytes(data);
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            Path file1 = Paths.get(path1.toString(), "file.txt");

            // Create PUT transaction
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, file1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize);

            // Check the file list metadata is correct
            assertEquals(1, arbitraryDataFile.getMetadata().getFiles().size());
            assertTrue(arbitraryDataFile.getMetadata().getFiles().contains("file.txt"));

            // Ensure the file list can be read back out again, when specified to be included
            ArbitraryResourceMetadata resourceMetadata = ArbitraryResourceMetadata.fromTransactionMetadata(arbitraryDataFile.getMetadata(), true);
            assertTrue(resourceMetadata.getFiles().contains("file.txt"));

            // Ensure it's not returned when specified to be excluded
            ArbitraryResourceMetadata resourceMetadataSimple = ArbitraryResourceMetadata.fromTransactionMetadata(arbitraryDataFile.getMetadata(), false);
            assertNull(resourceMetadataSimple.getFiles());

            // Single-file resources should have a MIME type
            assertEquals("text/plain", arbitraryDataFile.getMetadata().getMimeType());
        }
    }

    @Test
    public void testMultipleFileList() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Add a few files at multiple levels
            byte[] data = new byte[1024];
            new Random().nextBytes(data);
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            Files.write(Paths.get(path1.toString(), "image1.jpg"), data, StandardOpenOption.CREATE);

            Path subdirectory = Paths.get(path1.toString(), "subdirectory");
            Files.createDirectories(subdirectory);
            Files.write(Paths.get(subdirectory.toString(), "config.json"), data, StandardOpenOption.CREATE);

            // Create PUT transaction
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize);

            // Check the file list metadata is correct
            assertEquals(3, arbitraryDataFile.getMetadata().getFiles().size());
            assertTrue(arbitraryDataFile.getMetadata().getFiles().contains("file.txt"));
            assertTrue(arbitraryDataFile.getMetadata().getFiles().contains("image1.jpg"));
            assertTrue(arbitraryDataFile.getMetadata().getFiles().contains("subdirectory" + File.separator + "config.json"));

            // Ensure the file list can be read back out again, when specified to be included
            ArbitraryResourceMetadata resourceMetadata = ArbitraryResourceMetadata.fromTransactionMetadata(arbitraryDataFile.getMetadata(), true);
            assertTrue(resourceMetadata.getFiles().contains("file.txt"));
            assertTrue(resourceMetadata.getFiles().contains("image1.jpg"));
            assertTrue(resourceMetadata.getFiles().contains("subdirectory" + File.separator + "config.json"));

            // Ensure it's not returned when specified to be excluded
            // The entire object will be null because there is no metadata
            ArbitraryResourceMetadata resourceMetadataSimple = ArbitraryResourceMetadata.fromTransactionMetadata(arbitraryDataFile.getMetadata(), false);
            assertNull(resourceMetadataSimple);

            // Multi-file resources won't have a MIME type
            assertEquals(null, arbitraryDataFile.getMetadata().getMimeType());
        }
    }

    @Test
    public void testExistingCategories() {
        // Matching categories should be correctly located
        assertEquals(Category.QORTAL, Category.uncategorizedValueOf("QORTAL"));
        assertEquals(Category.TECHNOLOGY, Category.uncategorizedValueOf("TECHNOLOGY"));
    }

    @Test
    public void testMissingCategory() {
        // Missing or invalid categories should fall back to UNCATEGORIZED
        assertEquals(Category.UNCATEGORIZED, Category.uncategorizedValueOf("INVALID_CATEGORY"));
        assertEquals(Category.UNCATEGORIZED, Category.uncategorizedValueOf("Qortal")); // Case-sensitive match required
    }

}
