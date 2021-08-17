package org.qortal.arbitrary;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataPatch;
import org.qortal.utils.Base58;
import org.qortal.utils.FilesystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class ArbitraryDataCombiner {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataCombiner.class);

    private Path pathBefore;
    private Path pathAfter;
    private byte[] signatureBefore;
    private Path finalPath;

    public ArbitraryDataCombiner(Path pathBefore, Path pathAfter, byte[] signatureBefore) {
        this.pathBefore = pathBefore;
        this.pathAfter = pathAfter;
        this.signatureBefore = signatureBefore;
    }

    public void combine() throws IOException {
        try {
            this.preExecute();
            this.validatePreviousSignature();
            this.validatePreviousHash();
            this.process();

        } finally {
            this.postExecute();
        }
    }

    public void cleanup() {
        this.cleanupPath(this.pathBefore);
        this.cleanupPath(this.pathAfter);
    }

    private void cleanupPath(Path path) {
        // Delete pathBefore, if it exists in our data/temp directory
        if (FilesystemUtils.pathInsideDataOrTempPath(path)) {
            File directory = new File(path.toString());
            try {
                FileUtils.deleteDirectory(directory);
            } catch (IOException e) {
                // This will eventually be cleaned up by a maintenance process, so log the error and continue
                LOGGER.info("Unable to cleanup directory {}", directory.toString());
            }
        }

        // Delete the parent directory of pathBefore if it is empty (and exists in our data/temp directory)
        Path parentDirectory = path.getParent();
        if (FilesystemUtils.pathInsideDataOrTempPath(parentDirectory)) {
            try {
                Files.deleteIfExists(parentDirectory);
            } catch (DirectoryNotEmptyException e) {
                // No need to log anything
            } catch (IOException e) {
                // This will eventually be cleaned up by a maintenance process, so log the error and continue
                LOGGER.info("Unable to cleanup parent directory {}", parentDirectory.toString());
            }
        }
    }

    private void preExecute() {
        if (this.pathBefore == null || this.pathAfter == null) {
            throw new IllegalStateException(String.format("No paths available to build patch"));
        }
        if (!Files.exists(this.pathBefore) || !Files.exists(this.pathAfter)) {
            throw new IllegalStateException(String.format("Unable to create patch because at least one path doesn't exist"));
        }
    }

    private void postExecute() {

    }

    private void validatePreviousSignature() throws IOException {
        if (this.signatureBefore == null) {
            throw new IllegalStateException("No previous signature passed to the combiner");
        }

        ArbitraryDataMetadataPatch metadata = new ArbitraryDataMetadataPatch(this.pathAfter);
        metadata.read();
        byte[] previousSignature = metadata.getPreviousSignature();
        if (previousSignature == null) {
            throw new IllegalStateException("Unable to extract previous signature from patch metadata");
        }

        // Compare the signatures
        if (!Arrays.equals(previousSignature, this.signatureBefore)) {
            throw new IllegalStateException("Previous signatures do not match - transactions out of order?");
        }
    }

    private void validatePreviousHash() throws IOException {
        ArbitraryDataMetadataPatch metadata = new ArbitraryDataMetadataPatch(this.pathAfter);
        metadata.read();
        byte[] previousHash = metadata.getPreviousHash();
        if (previousHash == null) {
            throw new IllegalStateException("Unable to extract previous hash from patch metadata");
        }

        ArbitraryDataDigest digest = new ArbitraryDataDigest(this.pathBefore);
        digest.compute();
        boolean valid = digest.isHashValid(previousHash);
        if (!valid) {
            String previousHash58 = Base58.encode(previousHash);
            throw new IllegalStateException(String.format("Previous state hash mismatch. " +
                    "Patch prevHash: %s, actual: %s", previousHash58, digest.getHash58()));
        }
    }

    private void process() throws IOException {
        ArbitraryDataMerge merge = new ArbitraryDataMerge(this.pathBefore, this.pathAfter);
        merge.compute();
        this.finalPath = merge.getMergePath();
    }

    public Path getFinalPath() {
        return this.finalPath;
    }

}
