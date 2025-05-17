package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.arbitrary.ArbitraryDataCombiner;
import org.qortal.arbitrary.ArbitraryDataCreatePatch;
import org.qortal.arbitrary.ArbitraryDataDigest;
import org.qortal.crypto.Crypto;
import org.qortal.repository.DataException;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.Common;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryDataMergeTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testCreateAndMergePatch() throws IOException, DataException {
        Path path1 = Paths.get("src/test/resources/arbitrary/demo1");
        Path path2 = Paths.get("src/test/resources/arbitrary/demo2");

        // Generate random signature for the purposes of validation
        byte[] signature = new byte[32];
        new Random().nextBytes(signature);

        // Create a patch using the differences in path2 compared with path1
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(path1, path2, signature);
        patch.create();
        Path patchPath = patch.getFinalPath();
        assertTrue(Files.exists(patchPath));

        // Check that lorem1, 2, 4, and 5 exist
        assertTrue(Files.exists(Paths.get(patchPath.toString(), "lorem1.txt")));
        assertTrue(Files.exists(Paths.get(patchPath.toString(), "lorem2.txt")));
        assertTrue(Files.exists(Paths.get(patchPath.toString(), "dir1", "lorem4.txt")));
        assertTrue(Files.exists(Paths.get(patchPath.toString(), "dir1", "dir2", "lorem5.txt")));

        // Ensure that lorem3 doesn't exist, as this file is identical in the original paths
        assertFalse(Files.exists(Paths.get(patchPath.toString(), "lorem3.txt")));

        // Ensure that the patch files differ from the first path (except for lorem3, which is missing)
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path1.toString(), "lorem1.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "lorem1.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path1.toString(), "lorem2.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "lorem2.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path1.toString(), "dir1", "lorem4.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "dir1", "lorem4.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path1.toString(), "dir1", "dir2", "lorem5.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "dir1", "dir2", "lorem5.txt").toFile())
        ));

        // Ensure that patch files 1 and 4 differ from the original files
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path2.toString(), "lorem1.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "lorem1.txt").toFile())
        ));
        assertFalse(Arrays.equals(
                Crypto.digest(Paths.get(path2.toString(), "dir1", "lorem4.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "dir1", "lorem4.txt").toFile())
        ));

        // Files 2 and 5 should match the original files, because their contents were
        // too small to create a patch file smaller than the original file
        assertArrayEquals(
                Crypto.digest(Paths.get(path2.toString(), "lorem2.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "lorem2.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(path2.toString(), "dir1", "dir2", "lorem5.txt").toFile()),
                Crypto.digest(Paths.get(patchPath.toString(), "dir1", "dir2", "lorem5.txt").toFile())
        );

        // Now merge the patch with the original path
        ArbitraryDataCombiner combiner = new ArbitraryDataCombiner(path1, patchPath, signature);
        combiner.setShouldValidateHashes(true);
        combiner.combine();
        Path finalPath = combiner.getFinalPath();

        // Ensure that all files exist in the final path (including lorem3)
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "lorem1.txt")));
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "lorem2.txt")));
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "lorem3.txt")));
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "dir1", "lorem4.txt")));
        assertTrue(Files.exists(Paths.get(finalPath.toString(), "dir1", "dir2", "lorem5.txt")));

        // Ensure that the files match those in path2 exactly
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "lorem1.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "lorem1.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "lorem2.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "lorem2.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "lorem3.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "lorem3.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "dir1", "lorem4.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "dir1", "lorem4.txt").toFile())
        );
        assertArrayEquals(
                Crypto.digest(Paths.get(finalPath.toString(), "dir1", "dir2", "lorem5.txt").toFile()),
                Crypto.digest(Paths.get(path2.toString(), "dir1", "dir2", "lorem5.txt").toFile())
        );

        // Also check that the directory digests match
        ArbitraryDataDigest path2Digest = new ArbitraryDataDigest(path2);
        path2Digest.compute();
        ArbitraryDataDigest finalPathDigest = new ArbitraryDataDigest(finalPath);
        finalPathDigest.compute();
        assertEquals(path2Digest.getHash58(), finalPathDigest.getHash58());
    }

    @Test
    public void testIdenticalPaths() throws IOException, DataException {
        Path path = Paths.get("src/test/resources/arbitrary/demo1");

        // Create a patch from two identical paths
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(path, path, new byte[16]);

        // Ensure that an exception is thrown due to matching states
        try {
            patch.create();
            fail("Creating patch should fail due to matching states");

        } catch (DataException expectedException) {
            assertEquals("Current state matches previous state. Nothing to do.", expectedException.getMessage());
        }

    }

    @Test
    public void testMergeBinaryFiles() throws IOException, DataException {
        // Create two files in random temp directories
        Path tempDir1 = Files.createTempDirectory("testMergeBinaryFiles1");
        Path tempDir2 = Files.createTempDirectory("testMergeBinaryFiles2");
        File file1 = new File(Paths.get(tempDir1.toString(), "file.bin").toString());
        File file2 = new File(Paths.get(tempDir2.toString(), "file.bin").toString());
        file1.deleteOnExit();
        file2.deleteOnExit();

        // Write random data to the first file
        byte[] initialData = new byte[1024];
        new Random().nextBytes(initialData);
        Files.write(file1.toPath(), initialData);
        byte[] file1Digest = Crypto.digest(file1);

        // Write slightly modified data to the second file (bytes 100-116 are zeroed out)
        byte[] updatedData = Arrays.copyOf(initialData, initialData.length);
        final ByteBuffer byteBuffer = ByteBuffer.wrap(updatedData);
        byteBuffer.position(100);
        byteBuffer.put(new byte[16]);
        updatedData = byteBuffer.array();
        Files.write(file2.toPath(), updatedData);
        byte[] file2Digest = Crypto.digest(file2);

        // Make sure the two arrays are different
        assertFalse(Arrays.equals(initialData, updatedData));

        // And double check that they are both 1024 bytes long
        assertEquals(1024, initialData.length);
        assertEquals(1024, updatedData.length);

        // Ensure both files exist
        assertTrue(Files.exists(file1.toPath()));
        assertTrue(Files.exists(file2.toPath()));

        // Generate random signature for the purposes of validation
        byte[] signature = new byte[32];
        new Random().nextBytes(signature);

        // Create a patch from the two paths
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(tempDir1, tempDir2, signature);
        patch.create();
        Path patchPath = patch.getFinalPath();
        assertTrue(Files.exists(patchPath));

        // Check that the patch file exists
        Path patchFilePath = Paths.get(patchPath.toString(), "file.bin");
        assertTrue(Files.exists(patchFilePath));
        byte[] patchDigest = Crypto.digest(patchFilePath.toFile());

        // Ensure that the patch file matches file2 exactly
        // This is because binary files cannot currently be patched, and so the complete file
        // is included instead
        assertArrayEquals(patchDigest, file2Digest);

        // Make sure that the patch file is different from file1
        assertFalse(Arrays.equals(patchDigest, file1Digest));

        // Now merge the patch with the original path
        ArbitraryDataCombiner combiner = new ArbitraryDataCombiner(tempDir1, patchPath, signature);
        combiner.setShouldValidateHashes(true);
        combiner.combine();
        Path finalPath = combiner.getFinalPath();

        // Check that the directory digests match
        ArbitraryDataDigest path2Digest = new ArbitraryDataDigest(tempDir2);
        path2Digest.compute();
        ArbitraryDataDigest finalPathDigest = new ArbitraryDataDigest(finalPath);
        finalPathDigest.compute();
        assertEquals(path2Digest.getHash58(), finalPathDigest.getHash58());
    }

    @Test
    public void testMergeRandomStrings() throws IOException, DataException {
        // Create two files in random temp directories
        Path tempDir1 = Files.createTempDirectory("testMergeRandomStrings");
        Path tempDir2 = Files.createTempDirectory("testMergeRandomStrings");
        File file1 = new File(Paths.get(tempDir1.toString(), "file.txt").toString());
        File file2 = new File(Paths.get(tempDir2.toString(), "file.txt").toString());
        file1.deleteOnExit();
        file2.deleteOnExit();

        // Write a random string to the first file
        BufferedWriter file1Writer = new BufferedWriter(new FileWriter(file1));
        String initialString = ArbitraryUtils.generateRandomString(1024);
        // Add a newline every 50 chars
        initialString = initialString.replaceAll("(.{50})", "$1" + System.lineSeparator());
        file1Writer.write(initialString);
        file1Writer.newLine();
        file1Writer.close();
        byte[] file1Digest = Crypto.digest(file1);

        // Write a slightly modified string to the second file
        BufferedWriter file2Writer = new BufferedWriter(new FileWriter(file2));
        String updatedString = initialString.concat("-edit");
        file2Writer.write(updatedString);
        file2Writer.newLine();
        file2Writer.close();
        byte[] file2Digest = Crypto.digest(file2);

        // Make sure the two strings are different
        assertFalse(Objects.equals(initialString, updatedString));

        // Ensure both files exist
        assertTrue(Files.exists(file1.toPath()));
        assertTrue(Files.exists(file2.toPath()));

        // Generate random signature for the purposes of validation
        byte[] signature = new byte[32];
        new Random().nextBytes(signature);

        // Create a patch from the two paths
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(tempDir1, tempDir2, signature);
        patch.create();
        Path patchPath = patch.getFinalPath();
        assertTrue(Files.exists(patchPath));

        // Check that the patch file exists
        Path patchFilePath = Paths.get(patchPath.toString(), "file.txt");
        assertTrue(Files.exists(patchFilePath));
        byte[] patchDigest = Crypto.digest(patchFilePath.toFile());

        // Make sure that the patch file is different from file1 and file2
        assertFalse(Arrays.equals(patchDigest, file1Digest));
        assertFalse(Arrays.equals(patchDigest, file2Digest));

        // Now merge the patch with the original path
        ArbitraryDataCombiner combiner = new ArbitraryDataCombiner(tempDir1, patchPath, signature);
        combiner.setShouldValidateHashes(true);
        combiner.combine();
        Path finalPath = combiner.getFinalPath();

        // Check that the directory digests match
        ArbitraryDataDigest path2Digest = new ArbitraryDataDigest(tempDir2);
        path2Digest.compute();
        ArbitraryDataDigest finalPathDigest = new ArbitraryDataDigest(finalPath);
        finalPathDigest.compute();
        assertEquals(path2Digest.getHash58(), finalPathDigest.getHash58());

    }

    @Test
    public void testMergeRandomStringsWithoutTrailingNewlines() throws IOException, DataException {
        // Create two files in random temp directories
        Path tempDir1 = Files.createTempDirectory("testMergeRandomStrings");
        Path tempDir2 = Files.createTempDirectory("testMergeRandomStrings");
        File file1 = new File(Paths.get(tempDir1.toString(), "file.txt").toString());
        File file2 = new File(Paths.get(tempDir2.toString(), "file.txt").toString());
        file1.deleteOnExit();
        file2.deleteOnExit();

        // Write a random string to the first file, 1024 characters, \n every 50 chars
        BufferedWriter file1Writer = new BufferedWriter(new FileWriter(file1));
        String initialString = ArbitraryUtils.generateRandomString(1024);
        // Add a newline every 50 chars
        initialString = initialString.replaceAll("(.{50})", "$1" + System.lineSeparator());
        // Remove newline at end of string
        initialString = initialString.stripTrailing();  // Remove Trailing White Space
        file1Writer.write(initialString);
        // No newline
        file1Writer.close();
        byte[] file1Digest = Crypto.digest(file1);

        // Write a slightly modified string to the second file, append "-edit" to the string
        BufferedWriter file2Writer = new BufferedWriter(new FileWriter(file2));
        String updatedString = initialString.concat("-edit");
        file2Writer.write(updatedString);
        // No newline - This is wrong, both files contain \n every 50 chars
        file2Writer.close();
        byte[] file2Digest = Crypto.digest(file2);

        // Make sure the two strings are different
        assertFalse(Objects.equals(initialString, updatedString));

        // Ensure both files exist
        assertTrue(Files.exists(file1.toPath()));
        assertTrue(Files.exists(file2.toPath()));

        // Generate random signature for the purposes of validation
        byte[] signature = new byte[32];
        new Random().nextBytes(signature);

        // Create a patch from the two paths
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(tempDir1, tempDir2, signature);
        patch.create();
        Path patchPath = patch.getFinalPath();

        assertTrue(Files.exists(patchPath));

        // Check that the patch file exists
        Path patchFilePath = Paths.get(patchPath.toString(), "file.txt");
        assertTrue(Files.exists(patchFilePath));
        byte[] patchDigest = Crypto.digest(patchFilePath.toFile());

        // Make sure that the patch file is different from file1
        assertFalse(Arrays.equals(patchDigest, file1Digest));

        // Make sure that the patch file is different from file2
        // This assert fails, patchFile and file2 have the same contents so the digest will match
        assertFalse(Arrays.equals(patchDigest, file2Digest));

        // Now merge the patch with the original path
        ArbitraryDataCombiner combiner = new ArbitraryDataCombiner(tempDir1, patchPath, signature);
        combiner.setShouldValidateHashes(true);
        combiner.combine();
        Path finalPath = combiner.getFinalPath();

        // Check that the directory digests match
        ArbitraryDataDigest path2Digest = new ArbitraryDataDigest(tempDir2);
        path2Digest.compute();
        ArbitraryDataDigest finalPathDigest = new ArbitraryDataDigest(finalPath);
        finalPathDigest.compute();
        assertEquals(path2Digest.getHash58(), finalPathDigest.getHash58());

    }

    @Test
    public void testMergeRandomLargeStrings() throws IOException, DataException {
        // Create two files in random temp directories
        Path tempDir1 = Files.createTempDirectory("testMergeRandomStrings");
        Path tempDir2 = Files.createTempDirectory("testMergeRandomStrings");
        File file1 = new File(Paths.get(tempDir1.toString(), "file.txt").toString());
        File file2 = new File(Paths.get(tempDir2.toString(), "file.txt").toString());
        file1.deleteOnExit();
        file2.deleteOnExit();

        // Write a random string to the first file
        BufferedWriter file1Writer = new BufferedWriter(new FileWriter(file1));
        String initialString = ArbitraryUtils.generateRandomString(110 * 1024);
        // Add a newline every 50 chars
        initialString = initialString.replaceAll("(.{50})", "$1" + System.lineSeparator());
        file1Writer.write(initialString);
        file1Writer.newLine();
        file1Writer.close();
        byte[] file1Digest = Crypto.digest(file1);

        // Write a slightly modified string to the second file
        BufferedWriter file2Writer = new BufferedWriter(new FileWriter(file2));
        String updatedString = initialString.concat("-edit");
        file2Writer.write(updatedString);
        file2Writer.newLine();
        file2Writer.close();
        byte[] file2Digest = Crypto.digest(file2);

        // Make sure the two strings are different
        assertFalse(Objects.equals(initialString, updatedString));

        // Ensure both files exist
        assertTrue(Files.exists(file1.toPath()));
        assertTrue(Files.exists(file2.toPath()));

        // Generate random signature for the purposes of validation
        byte[] signature = new byte[32];
        new Random().nextBytes(signature);

        // Create a patch from the two paths
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(tempDir1, tempDir2, signature);
        patch.create();
        Path patchPath = patch.getFinalPath();
        assertTrue(Files.exists(patchPath));

        // Check that the patch file exists
        Path patchFilePath = Paths.get(patchPath.toString(), "file.txt");
        assertTrue(Files.exists(patchFilePath));
        byte[] patchDigest = Crypto.digest(patchFilePath.toFile());

        // The patch file should be identical to file2 because the source files
        // were over the maximum size limit for creating patches
        assertArrayEquals(patchDigest, file2Digest);

        // Make sure that the patch file is different from file1
        assertFalse(Arrays.equals(patchDigest, file1Digest));

        // Now merge the patch with the original path
        ArbitraryDataCombiner combiner = new ArbitraryDataCombiner(tempDir1, patchPath, signature);
        combiner.setShouldValidateHashes(true);
        combiner.combine();
        Path finalPath = combiner.getFinalPath();

        // Check that the directory digests match
        ArbitraryDataDigest path2Digest = new ArbitraryDataDigest(tempDir2);
        path2Digest.compute();
        ArbitraryDataDigest finalPathDigest = new ArbitraryDataDigest(finalPath);
        finalPathDigest.compute();
        assertEquals(path2Digest.getHash58(), finalPathDigest.getHash58());

    }

}
