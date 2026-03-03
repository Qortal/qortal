package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.repository.DataException;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.nio.file.Files;


public class ArbitraryDataFileChunk extends ArbitraryDataFile {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileChunk.class);

    public ArbitraryDataFileChunk(String hash58, byte[] signature) throws DataException {
        super(hash58, signature);
    }

    public ArbitraryDataFileChunk(byte[] fileContent, byte[] signature) throws DataException {
        super(fileContent, signature, false);
    }

    public static ArbitraryDataFileChunk fromHash58(String hash58, byte[] signature) throws DataException {
        return new ArbitraryDataFileChunk(hash58, signature);
    }

    public static ArbitraryDataFileChunk fromHash(byte[] hash, byte[] signature) throws DataException {
        return ArbitraryDataFileChunk.fromHash58(Base58.encode(hash), signature);
    }

    @Override
    public ValidationResult isValid() {
        // DataChunk validation applies here too
        ValidationResult superclassValidationResult = super.isValid();
        if (superclassValidationResult != ValidationResult.OK) {
            return superclassValidationResult;
        }

        try {
            // Validate the file size (chunks have stricter limits)
            long fileSize = Files.size(this.filePath);
            if (fileSize > MAX_CHUNK_SIZE) {
                LOGGER.error(String.format("DataFileChunk is too large: %d bytes (max chunk size: %d bytes)", fileSize, MAX_CHUNK_SIZE));
                return ValidationResult.FILE_TOO_LARGE;
            }

        } catch (IOException e) {
            LOGGER.info("IOException - Validating");
            return ValidationResult.FILE_NOT_FOUND;
        }

        return ValidationResult.OK;
    }
}
