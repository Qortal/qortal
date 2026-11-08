package org.qortal.test.arbitrary;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.ArbitraryDataDigest;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.ArbitraryDataMetadataEditor;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.block.BlockChain;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ArbitraryMetadataEditTests extends Common {

    private static final String NAME = "TEST";
    private static final Service SERVICE = Service.WEBSITE;
    private static final int CHUNK_SIZE = 100;

    @Before
    public void beforeTest() throws DataException, IllegalAccessException {
        Common.useDefaultSettings();

        // Set difficulty to 1 to speed up the tests
        FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);
    }

    @Test
    public void testEditAllMetadata() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice);

            Path sitePath = generateWebsite();
            ArbitraryTransactionData original = publish(repository, alice, sitePath, null,
                    "Original title", "Original description", Arrays.asList("one", "two"), Category.QORTAL);

            ArbitraryTransactionData edited = editAndMint(repository, alice, null,
                    "New title", "New description", Arrays.asList("three"), Category.TECHNOLOGY);

            // A new transaction is now the latest for this resource
            assertFalse(Arrays.equals(original.getSignature(), edited.getSignature()));
            ArbitraryTransactionData latest = repository.getArbitraryRepository()
                    .getLatestTransaction(NAME, SERVICE, null, null);
            assertArrayEquals(edited.getSignature(), latest.getSignature());
            assertEquals(ArbitraryTransactionData.Method.PUT, latest.getMethod());

            ArbitraryDataTransactionMetadata metadata = readMetadata(latest);
            assertEquals("New title", metadata.getTitle());
            assertEquals("New description", metadata.getDescription());
            assertEquals(Arrays.asList("three"), metadata.getTags());
            assertEquals(Category.TECHNOLOGY, metadata.getCategory());

            // Content is unchanged
            assertContentMatches(sitePath, null);
        }
    }

    @Test
    public void testPartialEditKeepsExistingValues() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice);

            Path sitePath = generateWebsite();
            List<String> tags = Arrays.asList("one", "two");
            publish(repository, alice, sitePath, null, "Original title", "Original description", tags, Category.QORTAL);

            // Only change the title
            ArbitraryTransactionData edited = editAndMint(repository, alice, null,
                    "New title", null, null, null);

            ArbitraryDataTransactionMetadata metadata = readMetadata(edited);
            assertEquals("New title", metadata.getTitle());
            assertEquals("Original description", metadata.getDescription());
            assertEquals(tags, metadata.getTags());
            assertEquals(Category.QORTAL, metadata.getCategory());

            assertContentMatches(sitePath, null);
        }
    }

    @Test
    public void testAddMetadataToResourceWithoutMetadata() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice);

            Path sitePath = generateWebsite();
            publish(repository, alice, sitePath, null, null, null, null, null);

            ArbitraryTransactionData edited = editAndMint(repository, alice, null,
                    "Added title", null, null, Category.GAMING);

            ArbitraryDataTransactionMetadata metadata = readMetadata(edited);
            assertEquals("Added title", metadata.getTitle());
            assertNull(metadata.getDescription());
            assertNull(metadata.getTags());
            assertEquals(Category.GAMING, metadata.getCategory());

            assertContentMatches(sitePath, null);
        }
    }

    @Test
    public void testEditWithIdentifier() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice);

            String identifier = "my-site";
            Path sitePath = generateWebsite();
            publish(repository, alice, sitePath, identifier, "Original title", null, null, null);

            ArbitraryTransactionData edited = editAndMint(repository, alice, identifier,
                    "New title", null, null, null);
            assertEquals(identifier, edited.getIdentifier());
            assertEquals("New title", readMetadata(edited).getTitle());

            assertContentMatches(sitePath, identifier);
        }
    }

    @Test
    public void testRepeatedEdits() throws DataException, IOException, MissingDataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice);

            Path sitePath = generateWebsite();
            publish(repository, alice, sitePath, null, "Title 1", null, null, null);

            editAndMint(repository, alice, null, "Title 2", null, null, null);
            ArbitraryTransactionData edited = editAndMint(repository, alice, null, "Title 3", null, null, null);

            assertEquals("Title 3", readMetadata(edited).getTitle());
            assertContentMatches(sitePath, null);
        }
    }

    @Test
    public void testUnchangedMetadataIsRejected() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice);

            publish(repository, alice, generateWebsite(), null, "Same title", null, null, null);

            ArbitraryDataMetadataEditor editor = newEditor(repository, alice, null, "Same title", null, null, null);
            try {
                editor.build();
                fail("Expected unchanged metadata to be rejected");
            } catch (DataException e) {
                assertTrue(e.getMessage().contains("unchanged"));
            }
        }
    }

    @Test
    public void testNoMetadataSuppliedIsRejected() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice);

            publish(repository, alice, generateWebsite(), null, null, null, null, null);

            ArbitraryDataMetadataEditor editor = newEditor(repository, alice, null, null, "", null, null);
            try {
                editor.build();
                fail("Expected missing metadata to be rejected");
            } catch (DataException e) {
                assertTrue(e.getMessage().contains("No metadata"));
            }
        }
    }

    @Test
    public void testMissingResourceIsRejected() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice);

            ArbitraryDataMetadataEditor editor = newEditor(repository, alice, null, "Title", null, null, null);
            try {
                editor.build();
                fail("Expected a missing resource to be rejected");
            } catch (DataException e) {
                assertTrue(e.getMessage().contains("No existing resource"));
            }
        }
    }


    // Helpers

    private static void registerName(Repository repository, PrivateKeyAccount account) throws DataException {
        RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(account), NAME, "");
        transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
        TransactionUtils.signAndMint(repository, transactionData, account);
    }

    private static Path generateWebsite() throws IOException {
        Path dir = Files.createTempDirectory("metadataEditSite");
        Files.write(dir.resolve("index.html"),
                ("<html><body>" + ArbitraryUtils.generateRandomString(500) + "</body></html>\n").getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("style.css"),
                ("body { color: #" + ArbitraryUtils.generateRandomString(6) + "; }\n").getBytes(StandardCharsets.UTF_8));
        Path sub = Files.createDirectories(dir.resolve("js"));
        Files.write(sub.resolve("app.js"),
                ("console.log('" + ArbitraryUtils.generateRandomString(300) + "');\n").getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static ArbitraryTransactionData publish(Repository repository, PrivateKeyAccount account, Path path,
                                                    String identifier, String title, String description,
                                                    List<String> tags, Category category) throws DataException {
        long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
        ArbitraryDataFile file = ArbitraryUtils.createAndMintTxn(repository, Base58.encode(account.getPublicKey()), path,
                NAME, identifier, ArbitraryTransactionData.Method.PUT, SERVICE, account, CHUNK_SIZE, fee, false,
                title, description, tags, category);
        assertNotNull(file);
        return repository.getArbitraryRepository().getLatestTransaction(NAME, SERVICE, null, identifier);
    }

    private static ArbitraryDataMetadataEditor newEditor(Repository repository, PrivateKeyAccount account, String identifier,
                                                         String title, String description, List<String> tags,
                                                         Category category) {
        long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
        ArbitraryDataMetadataEditor editor = new ArbitraryDataMetadataEditor(repository, Base58.encode(account.getPublicKey()),
                fee, NAME, SERVICE, identifier, title, description, tags, category);
        editor.setChunkSize(CHUNK_SIZE);
        return editor;
    }

    private static ArbitraryTransactionData editAndMint(Repository repository, PrivateKeyAccount account, String identifier,
                                                        String title, String description, List<String> tags,
                                                        Category category) throws DataException {
        ArbitraryDataMetadataEditor editor = newEditor(repository, account, identifier, title, description, tags, category);
        editor.build();

        ArbitraryTransactionData transactionData = editor.getArbitraryTransactionData();
        assertNotNull(transactionData);

        Transaction.ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, account);
        assertEquals(Transaction.ValidationResult.OK, result);
        BlockUtils.mintBlock(repository);

        return repository.getArbitraryRepository().getLatestTransaction(NAME, SERVICE, null, identifier);
    }

    private static ArbitraryDataTransactionMetadata readMetadata(ArbitraryTransactionData transactionData) throws DataException {
        assertNotNull(transactionData.getMetadataHash());
        ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(transactionData.getMetadataHash(), transactionData.getSignature());
        assertTrue(metadataFile.exists());
        ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(metadataFile.getFilePath());
        try {
            metadata.read();
        } catch (IOException e) {
            throw new DataException(e);
        }
        return metadata;
    }

    private static void assertContentMatches(Path originalPath, String identifier) throws DataException, IOException, MissingDataException {
        ArbitraryDataReader reader = new ArbitraryDataReader(NAME, ResourceIdType.NAME, SERVICE, identifier);
        reader.loadSynchronously(true);

        ArbitraryDataDigest builtDigest = new ArbitraryDataDigest(reader.getFilePath());
        builtDigest.compute();
        ArbitraryDataDigest originalDigest = new ArbitraryDataDigest(originalPath);
        originalDigest.compute();
        assertEquals(originalDigest.getHash58(), builtDigest.getHash58());
    }
}
