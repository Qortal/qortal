package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.crypto.AES;
import org.qortal.crypto.Crypto;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryEncryptionTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testCbcEncryption() throws IOException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {
        String enclosingFolderName = "data";
        Path inputFilePath = Files.createTempFile("inputFile", null);
        Path outputDirectory = Files.createTempDirectory("outputDirectory");
        Path outputFilePath = Paths.get(outputDirectory.toString(), enclosingFolderName);
        inputFilePath.toFile().deleteOnExit();
        outputDirectory.toFile().deleteOnExit();

        // Write random data to the input file
        byte[] data = new byte[10];
        new Random().nextBytes(data);
        Files.write(inputFilePath, data, StandardOpenOption.CREATE);

        assertTrue(Files.exists(inputFilePath));
        assertFalse(Files.exists(outputFilePath));

        // Encrypt...
        String algorithm = "AES/CBC/PKCS5Padding";
        SecretKey aesKey = AES.generateKey(256);
        AES.encryptFile(algorithm, aesKey, inputFilePath.toString(), outputFilePath.toString());

        assertTrue(Files.exists(inputFilePath));
        assertTrue(Files.exists(outputFilePath));

        // Ensure encrypted file's hash differs from the original
        assertFalse(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(outputFilePath.toFile())));

        // Create paths for decrypting
        Path decryptedDirectory = Files.createTempDirectory("decryptedDirectory");
        Path decryptedFile = Paths.get(decryptedDirectory.toString(), enclosingFolderName, inputFilePath.getFileName().toString());
        decryptedDirectory.toFile().deleteOnExit();
        assertFalse(Files.exists(decryptedFile));

        // Now decrypt...
        AES.decryptFile(algorithm, aesKey, outputFilePath.toString(), decryptedFile.toString());

        // Ensure resulting file exists
        assertTrue(Files.exists(decryptedFile));

        // And make sure it matches the original input file
        assertTrue(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(decryptedFile.toFile())));
    }

    @Test
    public void testCbcEncryptionSizeOverhead() throws IOException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {
        for (int size = 1; size < 256; size++) {
            String enclosingFolderName = "data";
            Path inputFilePath = Files.createTempFile("inputFile", null);
            Path outputDirectory = Files.createTempDirectory("outputDirectory");
            Path outputFilePath = Paths.get(outputDirectory.toString(), enclosingFolderName);
            inputFilePath.toFile().deleteOnExit();
            outputDirectory.toFile().deleteOnExit();

            // Write random data to the input file
            byte[] data = new byte[size];
            new Random().nextBytes(data);
            Files.write(inputFilePath, data, StandardOpenOption.CREATE);

            assertTrue(Files.exists(inputFilePath));
            assertFalse(Files.exists(outputFilePath));

            // Ensure input file is the same size as the data
            assertEquals(size, inputFilePath.toFile().length());

            // Encrypt...
            String algorithm = "AES/CBC/PKCS5Padding";
            SecretKey aesKey = AES.generateKey(256);
            AES.encryptFile(algorithm, aesKey, inputFilePath.toString(), outputFilePath.toString());

            assertTrue(Files.exists(inputFilePath));
            assertTrue(Files.exists(outputFilePath));

            final long expectedSize = AES.getCbcEncryptedFileSize(inputFilePath.toFile().length());
            System.out.println(String.format("Plaintext size: %d bytes, Ciphertext size: %d bytes", inputFilePath.toFile().length(), outputFilePath.toFile().length()));

            // Ensure encryption added a fixed amount of space to the output file
            assertEquals(expectedSize, outputFilePath.toFile().length());

            // Ensure encrypted file's hash differs from the original
            assertFalse(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(outputFilePath.toFile())));

            // Create paths for decrypting
            Path decryptedDirectory = Files.createTempDirectory("decryptedDirectory");
            Path decryptedFile = Paths.get(decryptedDirectory.toString(), enclosingFolderName, inputFilePath.getFileName().toString());
            decryptedDirectory.toFile().deleteOnExit();
            assertFalse(Files.exists(decryptedFile));

            // Now decrypt...
            AES.decryptFile(algorithm, aesKey, outputFilePath.toString(), decryptedFile.toString());

            // Ensure resulting file exists
            assertTrue(Files.exists(decryptedFile));

            // And make sure it matches the original input file
            assertTrue(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(decryptedFile.toFile())));
        }
    }

    @Test
    public void testGcmEncryption() throws IOException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {
        String enclosingFolderName = "data";
        Path inputFilePath = Files.createTempFile("inputFile", null);
        Path outputDirectory = Files.createTempDirectory("outputDirectory");
        Path outputFilePath = Paths.get(outputDirectory.toString(), enclosingFolderName);
        inputFilePath.toFile().deleteOnExit();
        outputDirectory.toFile().deleteOnExit();

        // Write random data to the input file
        byte[] data = new byte[10];
        new Random().nextBytes(data);
        Files.write(inputFilePath, data, StandardOpenOption.CREATE);

        assertTrue(Files.exists(inputFilePath));
        assertFalse(Files.exists(outputFilePath));

        // Encrypt...
        String algorithm = "AES/GCM/NoPadding";
        SecretKey aesKey = AES.generateKey(256);
        AES.encryptFile(algorithm, aesKey, inputFilePath.toString(), outputFilePath.toString());

        assertTrue(Files.exists(inputFilePath));
        assertTrue(Files.exists(outputFilePath));

        // Ensure encrypted file's hash differs from the original
        assertFalse(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(outputFilePath.toFile())));

        // Create paths for decrypting
        Path decryptedDirectory = Files.createTempDirectory("decryptedDirectory");
        Path decryptedFile = Paths.get(decryptedDirectory.toString(), enclosingFolderName, inputFilePath.getFileName().toString());
        decryptedDirectory.toFile().deleteOnExit();
        assertFalse(Files.exists(decryptedFile));

        // Now decrypt...
        AES.decryptFile(algorithm, aesKey, outputFilePath.toString(), decryptedFile.toString());

        // Ensure resulting file exists
        assertTrue(Files.exists(decryptedFile));

        // And make sure it matches the original input file
        assertTrue(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(decryptedFile.toFile())));
    }

    @Test
    public void testGcmEncryptionSizeOverhead() throws IOException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {
        for (int size = 1; size < 256; size++) {
            String enclosingFolderName = "data";
            Path inputFilePath = Files.createTempFile("inputFile", null);
            Path outputDirectory = Files.createTempDirectory("outputDirectory");
            Path outputFilePath = Paths.get(outputDirectory.toString(), enclosingFolderName);
            inputFilePath.toFile().deleteOnExit();
            outputDirectory.toFile().deleteOnExit();

            // Write random data to the input file
            byte[] data = new byte[size];
            new Random().nextBytes(data);
            Files.write(inputFilePath, data, StandardOpenOption.CREATE);

            assertTrue(Files.exists(inputFilePath));
            assertFalse(Files.exists(outputFilePath));

            // Ensure input file is the same size as the data
            assertEquals(size, inputFilePath.toFile().length());

            // Encrypt...
            String algorithm = "AES/GCM/NoPadding";
            SecretKey aesKey = AES.generateKey(256);
            AES.encryptFile(algorithm, aesKey, inputFilePath.toString(), outputFilePath.toString());

            assertTrue(Files.exists(inputFilePath));
            assertTrue(Files.exists(outputFilePath));

            final long expectedSize = AES.getGcmEncryptedFileSize(inputFilePath.toFile().length());
            System.out.println(String.format("Plaintext size: %d bytes, Ciphertext size: %d bytes", inputFilePath.toFile().length(), outputFilePath.toFile().length()));

            // Ensure encryption added a fixed amount of space to the output file
            assertEquals(expectedSize, outputFilePath.toFile().length());

            // Ensure encrypted file's hash differs from the original
            assertFalse(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(outputFilePath.toFile())));

            // Create paths for decrypting
            Path decryptedDirectory = Files.createTempDirectory("decryptedDirectory");
            Path decryptedFile = Paths.get(decryptedDirectory.toString(), enclosingFolderName, inputFilePath.getFileName().toString());
            decryptedDirectory.toFile().deleteOnExit();
            assertFalse(Files.exists(decryptedFile));

            // Now decrypt...
            AES.decryptFile(algorithm, aesKey, outputFilePath.toString(), decryptedFile.toString());

            // Ensure resulting file exists
            assertTrue(Files.exists(decryptedFile));

            // And make sure it matches the original input file
            assertTrue(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(decryptedFile.toFile())));
        }
    }

}
