package org.qortal.test.arbitrary;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.arbitrary.misc.Service.ValidationResult;
import org.qortal.controller.arbitrary.ArbitraryDataManager;
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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryServiceTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testDefaultValidation() throws IOException {
        // We don't validate the ARBITRARY_DATA service specifically, so we can use it to test the default validation method
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write to temp path
        Path path = Files.createTempFile("testDefaultValidation", null);
        path.toFile().deleteOnExit();
        Files.write(path, data, StandardOpenOption.CREATE);

        Service service = Service.ARBITRARY_DATA;
        assertFalse(service.isValidationRequired());
        // Test validation anyway to ensure that no exception is thrown
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateChainCommentIgnoresQortalMetadata() throws IOException {
        Path path = Files.createTempDirectory("testValidateChainCommentIgnoresQortalMetadata");
        path.toFile().deleteOnExit();

        byte[] commentData = new byte[200];
        Arrays.fill(commentData, (byte) 'a');
        Files.write(Paths.get(path.toString(), "comment"), commentData, StandardOpenOption.CREATE);

        Path qortalPath = Paths.get(path.toString(), ".qortal");
        Files.createDirectories(qortalPath);
        byte[] metadata = new byte[512];
        Arrays.fill(metadata, (byte) 'b');
        Files.write(Paths.get(qortalPath.toString(), "cache"), metadata, StandardOpenOption.CREATE);

        Service service = Service.CHAIN_COMMENT;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsite() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsite");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "index.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is an index file in the root
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithoutIndexFile() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFile");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "data1.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.MISSING_INDEX_FILE, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithIndexFile() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFile");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "index.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data1.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithIndexFileOnly() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFile");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "index.html"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithoutIndexFileInRoot() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFileInRoot");
        path.toFile().deleteOnExit();
        Files.createDirectories(Paths.get(path.toString(), "directory"));
        Files.write(Paths.get(path.toString(), "directory", "index.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.MISSING_INDEX_FILE, service.validate(path));
    }

    @Test
    public void testValidateGifRepository() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateGifRepository");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "image1.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "image2.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "image3.gif"), data, StandardOpenOption.CREATE);

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateSingleFileGifRepository() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to a single file in a temp path
        Path path = Files.createTempDirectory("testValidateSingleFileGifRepository");
        path.toFile().deleteOnExit();
        Path imagePath = Paths.get(path.toString(), "image1.gif");
        Files.write(imagePath, data, StandardOpenOption.CREATE);

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(imagePath));
    }

    @Test
    public void testValidateMultiLayerGifRepository() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateMultiLayerGifRepository");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "image1.gif"), data, StandardOpenOption.CREATE);

        Path subdirectory = Paths.get(path.toString(), "subdirectory");
        Files.createDirectories(subdirectory);
        Files.write(Paths.get(subdirectory.toString(), "image2.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(subdirectory.toString(), "image3.gif"), data, StandardOpenOption.CREATE);

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.DIRECTORIES_NOT_ALLOWED, service.validate(path));
    }

    @Test
    public void testValidateEmptyGifRepository() throws IOException {
        Path path = Files.createTempDirectory("testValidateEmptyGifRepository");

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.MISSING_DATA, service.validate(path));
    }

    @Test
    public void testValidateInvalidGifRepository() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateInvalidGifRepository");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "image1.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "image2.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "image3.jpg"), data, StandardOpenOption.CREATE); // Invalid extension

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_EXTENSION, service.validate(path));
    }

    @Test
    public void testValidatePublishedGifRepository() throws IOException, DataException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Generate some random data
            byte[] data = new byte[1024];
            new Random().nextBytes(data);

            // Write the data to several files in a temp path
            Path path = Files.createTempDirectory("testValidateGifRepository");
            path.toFile().deleteOnExit();
            Files.write(Paths.get(path.toString(), "image1.gif"), data, StandardOpenOption.CREATE);
            Files.write(Paths.get(path.toString(), "image2.gif"), data, StandardOpenOption.CREATE);
            Files.write(Paths.get(path.toString(), "image3.gif"), data, StandardOpenOption.CREATE);

            Service service = Service.GIF_REPOSITORY;
            assertTrue(service.isValidationRequired());

            assertEquals(ValidationResult.OK, service.validate(path));

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = "test_identifier";

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, path, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice);

            // Build the latest data state for this name, and no exceptions should be thrown because validation passes
            ArbitraryDataReader arbitraryDataReader1a = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader1a.loadSynchronously(true);
        }
    }

    @Test
    public void testValidateQChatAttachment() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateQChatAttachment");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "document.pdf"), data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateSingleFileQChatAttachment() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateSingleFileQChatAttachment");
        path.toFile().deleteOnExit();
        Path filePath = Paths.get(path.toString(), "document.pdf");
        Files.write(filePath, data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(filePath));
    }

    @Test
    public void testValidateInvalidQChatAttachmentFileExtension() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateInvalidQChatAttachmentFileExtension");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "application.exe"), data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_EXTENSION, service.validate(path));
    }

    @Test
    public void testValidateEmptyQChatAttachment() throws IOException {
        Path path = Files.createTempDirectory("testValidateEmptyQChatAttachment");

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_COUNT, service.validate(path));
    }

    @Test
    public void testValidateMultiLayerQChatAttachment() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateMultiLayerQChatAttachment");
        path.toFile().deleteOnExit();

        Path subdirectory = Paths.get(path.toString(), "subdirectory");
        Files.createDirectories(subdirectory);
        Files.write(Paths.get(subdirectory.toString(), "file.txt"), data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_COUNT, service.validate(path));
    }

    @Test
    public void testValidateMultiFileQChatAttachment() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateMultiFileQChatAttachment");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "file1.txt"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "file2.txt"), data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_COUNT, service.validate(path));
    }

    @Test
    public void testValidatePublishedQChatAttachment() throws IOException, DataException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Generate some random data
            byte[] data = new byte[1024];
            new Random().nextBytes(data);

            // Write the data a single file in a temp path
            Path path = Files.createTempDirectory("testValidateSingleFileQChatAttachment");
            path.toFile().deleteOnExit();
            Path filePath = Paths.get(path.toString(), "document.pdf");
            Files.write(filePath, data, StandardOpenOption.CREATE);

            Service service = Service.QCHAT_ATTACHMENT;
            assertTrue(service.isValidationRequired());

            assertEquals(ValidationResult.OK, service.validate(filePath));

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = "test_identifier";

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficulty", 1, true);

            // Create PUT transaction
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, filePath, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice);

            // Build the latest data state for this name, and no exceptions should be thrown because validation passes
            ArbitraryDataReader arbitraryDataReader1a = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader1a.loadSynchronously(true);
        }
    }

    @Test
    public void testValidateValidJson() throws IOException {
        String invalidJsonString = "{\"test\": true, \"test2\": \"valid\"}";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateValidJson");
        Path filePath = Paths.get(path.toString(), "test.json");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(invalidJsonString);
        writer.close();

        Service service = Service.JSON;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(filePath));
    }
    @Test
    public void testValidateInvalidJson() throws IOException {
        String invalidJsonString = "{\"test\": true, \"test2\": invalid}";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateInvalidJson");
        Path filePath = Paths.get(path.toString(), "test.json");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(invalidJsonString);
        writer.close();

        Service service = Service.JSON;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_CONTENT, service.validate(filePath));
    }

    @Test
    public void testValidateEmptyJson() throws IOException {
        Path path = Files.createTempDirectory("testValidateEmptyJson");

        Service service = Service.JSON;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_COUNT, service.validate(path));
    }

    @Test
    public void testValidPrivateData() throws IOException {
        String dataString = "qortalEncryptedDatabMx4fELNTV+ifJxmv4+GcuOIJOTo+3qAvbWKNY2L1rfla5UBoEcoxbtjgZ9G7FLPb8V/Qfr0bfKWfvMmN06U/pgUdLuv2mGL2V0D3qYd1011MUzGdNG1qERjaCDz8GAi63+KnHHjfMtPgYt6bcqjs4CNV+ZZ4dIt3xxHYyVEBNc=";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidPrivateData");
        Path filePath = Paths.get(path.toString(), "test");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(dataString);
        writer.close();

        Service service = Service.FILE_PRIVATE;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(filePath));
    }

    @Test
    public void testValidPrivateGroupData() throws IOException {
        String dataString = "qortalGroupEncryptedDatabMx4fELNTV+ifJxmv4+GcuOIJOTo+3qAvbWKNY2L1rfla5UBoEcoxbtjgZ9G7FLPb8V/Qfr0bfKWfvMmN06U/pgUdLuv2mGL2V0D3qYd1011MUzGdNG1qERjaCDz8GAi63+KnHHjfMtPgYt6bcqjs4CNV+ZZ4dIt3xxHYyVEBNc=";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidPrivateData");
        Path filePath = Paths.get(path.toString(), "test");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(dataString);
        writer.close();

        Service service = Service.FILE_PRIVATE;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(filePath));
    }

    @Test
    public void testEncryptedData() throws IOException {
        String dataString = "qortalEncryptedDatabMx4fELNTV+ifJxmv4+GcuOIJOTo+3qAvbWKNY2L1rfla5UBoEcoxbtjgZ9G7FLPb8V/Qfr0bfKWfvMmN06U/pgUdLuv2mGL2V0D3qYd1011MUzGdNG1qERjaCDz8GAi63+KnHHjfMtPgYt6bcqjs4CNV+ZZ4dIt3xxHYyVEBNc=";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidPrivateData");
        Path filePath = Paths.get(path.toString(), "test");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(dataString);
        writer.close();

        // Validate a private service
        Service service = Service.FILE_PRIVATE;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.OK, service.validate(filePath));

        // Validate a regular service
        service = Service.FILE;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.DATA_ENCRYPTED, service.validate(filePath));
    }

    @Test
    public void testPlainTextData() throws IOException {
        String dataString = "plaintext";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testInvalidPrivateData");
        Path filePath = Paths.get(path.toString(), "test");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(dataString);
        writer.close();

        // Validate a private service
        Service service = Service.FILE_PRIVATE;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.DATA_NOT_ENCRYPTED, service.validate(filePath));

        // Validate a regular service
        service = Service.FILE;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.OK, service.validate(filePath));
    }

    @Test
    public void testGetPrivateServices() {
        List<Service> privateServices = Service.privateServices();
        for (Service service : privateServices) {
            assertTrue(service.isPrivate());
        }
    }

    @Test
    public void testGetPublicServices() {
        List<Service> publicServices = Service.publicServices();
        for (Service service : publicServices) {
            assertFalse(service.isPrivate());
        }
    }

}
