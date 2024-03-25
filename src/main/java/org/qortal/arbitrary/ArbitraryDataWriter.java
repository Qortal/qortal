package org.qortal.arbitrary;

import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile.ValidationResult;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.crypto.AES;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.ArbitraryTransactionData.Compression;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.FilesystemUtils;
import org.qortal.utils.ZipUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ArbitraryDataWriter {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataWriter.class);

    private Path filePath;
    private final String name;
    private final Service service;
    private final String identifier;
    private final Method method;
    private final Compression compression;

    // Metadata
    private final String title;
    private final String description;
    private final List<String> tags;
    private final Category category;
    private List<String> files;
    private String mimeType;

    private int chunkSize = ArbitraryDataFile.CHUNK_SIZE;

    private SecretKey aesKey;
    private ArbitraryDataFile arbitraryDataFile;

    // Intermediate paths to cleanup
    private Path workingPath;
    private Path compressedPath;
    private Path encryptedPath;

    public ArbitraryDataWriter(Path filePath, String name, Service service, String identifier, Method method, Compression compression,
                               String title, String description, List<String> tags, Category category) {
        this.filePath = filePath;
        this.name = name;
        this.service = service;
        this.method = method;
        this.compression = compression;

        // If identifier is a blank string, or reserved keyword "default", treat it as null
        if (identifier == null || identifier.equals("") || identifier.equals("default")) {
            identifier = null;
        }
        this.identifier = identifier;

        // Metadata (optional)
        this.title = ArbitraryDataTransactionMetadata.limitTitle(title);
        this.description = ArbitraryDataTransactionMetadata.limitDescription(description);
        this.tags = ArbitraryDataTransactionMetadata.limitTags(tags);
        this.category = category;
        this.files = new ArrayList<>(); // Populated in buildFileList()
        this.mimeType = null; // Populated in buildFileList()
    }

    public void save() throws IOException, DataException, InterruptedException, MissingDataException {
        try {
            this.preExecute();
            this.validateService();
            this.buildFileList();
            this.process();
            this.compress();
            this.encrypt();
            this.split();
            this.createMetadataFile();
            this.validate();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() throws DataException {
        this.checkEnabled();

        // Enforce compression when uploading multiple files
        if (!FilesystemUtils.isSingleFileResource(this.filePath, false) && compression == Compression.NONE) {
            throw new DataException("Unable to publish multiple files without compression");
        }

        // Create temporary working directory
        this.createWorkingDirectory();
    }

    private void postExecute() throws IOException {
        this.cleanupFilesystem();
    }

    private void checkEnabled() throws DataException {
        if (!Settings.getInstance().isQdnEnabled()) {
            throw new DataException("QDN is disabled in settings");
        }
    }

    private void createWorkingDirectory() throws DataException {
        // Use the user-specified temp dir, as it is deterministic, and is more likely to be located on reusable storage hardware
        String baseDir = Settings.getInstance().getTempDataPath();
        String identifier = Base58.encode(Crypto.digest(this.filePath.toString().getBytes()));
        Path tempDir = Paths.get(baseDir, "writer", identifier);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new DataException("Unable to create temp directory");
        }
        this.workingPath = tempDir;
    }

    private void validateService() throws IOException, DataException {
        if (this.service.isValidationRequired()) {
            Service.ValidationResult result = this.service.validate(this.filePath);
            if (result != Service.ValidationResult.OK) {
                throw new DataException(String.format("Validation of %s failed: %s", this.service, result.toString()));
            }
        }
    }

    private void buildFileList() throws IOException {
        // Check if the path already points to a single file
        boolean isSingleFile = this.filePath.toFile().isFile();
        Path singleFilePath = null;
        if (isSingleFile) {
            this.files.add(this.filePath.getFileName().toString());
            singleFilePath = this.filePath;
        }
        else {
            // Multi file resources (or a single file in a directory) require a walk through the directory tree
            try (Stream<Path> stream = Files.walk(this.filePath)) {
                this.files = stream
                        .filter(Files::isRegularFile)
                        .map(p -> this.filePath.relativize(p).toString())
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());

                if (this.files.size() == 1) {
                    singleFilePath = Paths.get(this.filePath.toString(), this.files.get(0));

                    // Update filePath to point to the single file (instead of the directory containing the file)
                    this.filePath = singleFilePath;
                }
            }
        }

        if (singleFilePath != null) {
            // Single file resource, so try and determine the MIME type
            ContentInfoUtil util = new ContentInfoUtil();
            ContentInfo info = util.findMatch(singleFilePath.toFile());
            if (info != null) {
                // Attempt to extract MIME type from file contents
                this.mimeType = info.getMimeType();
            }
            else {
                // Fall back to using the filename
                FileNameMap fileNameMap = URLConnection.getFileNameMap();
                this.mimeType = fileNameMap.getContentTypeFor(singleFilePath.toFile().getName());
            }
        }
    }

    private void process() throws DataException, IOException, MissingDataException {
        switch (this.method) {

            case PUT:
                // Nothing to do
                break;

            case PATCH:
                this.processPatch();
                break;

            default:
                throw new DataException(String.format("Unknown method specified: %s", method.toString()));
        }
    }

    private void processPatch() throws DataException, IOException, MissingDataException {

        // Build the existing state using past transactions
        ArbitraryDataBuilder builder = new ArbitraryDataBuilder(this.name, this.service, this.identifier);
        builder.build();
        Path builtPath = builder.getFinalPath();

        // Obtain the latest signature, so this can be included in the patch
        byte[] latestSignature = builder.getLatestSignature();

        // Compute a diff of the latest changes on top of the previous state
        // Then use only the differences as our data payload
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(builtPath, this.filePath, latestSignature);
        patch.create();
        this.filePath = patch.getFinalPath();

        // Delete the input directory
        if (FilesystemUtils.pathInsideDataOrTempPath(builtPath)) {
            File directory = new File(builtPath.toString());
            FileUtils.deleteDirectory(directory);
        }

        // Validate the patch
        this.validatePatch();
    }

    private void validatePatch() throws DataException {
        if (this.filePath == null) {
            throw new DataException("Null path after creating patch");
        }

        File qortalMetadataDirectoryFile = Paths.get(this.filePath.toString(), ".qortal").toFile();
        if (!qortalMetadataDirectoryFile.exists()) {
            throw new DataException("Qortal metadata folder doesn't exist in patch");
        }
        if (!qortalMetadataDirectoryFile.isDirectory()) {
            throw new DataException("Qortal metadata folder isn't a directory");
        }

        File qortalPatchMetadataFile = Paths.get(this.filePath.toString(), ".qortal", "patch").toFile();
        if (!qortalPatchMetadataFile.exists()) {
            throw new DataException("Qortal patch metadata file doesn't exist in patch");
        }
        if (!qortalPatchMetadataFile.isFile()) {
            throw new DataException("Qortal patch metadata file isn't a file");
        }
    }

    private void compress() throws InterruptedException, DataException {
        // Compress the data if requested
        if (this.compression != Compression.NONE) {
            this.compressedPath = Paths.get(this.workingPath.toString(), "data.zip");
            try {

                if (this.compression == Compression.ZIP) {
                    LOGGER.info("Compressing...");
                    String enclosingFolderName = "data";
                    ZipUtils.zip(this.filePath.toString(), this.compressedPath.toString(), enclosingFolderName);
                }
                else {
                    throw new DataException(String.format("Unknown compression type specified: %s", compression.toString()));
                }
                // FUTURE: other compression types

                // Delete the input directory
                if (FilesystemUtils.pathInsideDataOrTempPath(this.filePath)) {
                    File directory = new File(this.filePath.toString());
                    FileUtils.deleteDirectory(directory);
                }
                // Replace filePath pointer with the zipped file path
                this.filePath = this.compressedPath;

            } catch (IOException | DataException e) {
                throw new DataException("Unable to zip directory", e);
            }
        }
    }

    private void encrypt() throws DataException {
        this.encryptedPath = Paths.get(this.workingPath.toString(), "data.zip.encrypted");
        try {
            // Encrypt the file with AES
            LOGGER.info("Encrypting...");
            this.aesKey = AES.generateKey(256);
            AES.encryptFile("AES/GCM/NoPadding", this.aesKey, this.filePath.toString(), this.encryptedPath.toString());

            // Delete the input file
            if (FilesystemUtils.pathInsideDataOrTempPath(this.filePath)) {
                Files.delete(this.filePath);
            }
            // Replace filePath pointer with the encrypted file path
            this.filePath = this.encryptedPath;

        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchPaddingException
                | BadPaddingException | IllegalBlockSizeException | IOException | InvalidKeyException e) {
            throw new DataException(String.format("Unable to encrypt file %s: %s", this.filePath, e.getMessage()));
        }
    }

    private void split() throws IOException, DataException {
        // We don't have a signature yet, so use null to put the file in a generic folder
        this.arbitraryDataFile = ArbitraryDataFile.fromPath(this.filePath, null);
        if (this.arbitraryDataFile == null) {
            throw new IOException("No file available when trying to split");
        }

        int chunkCount = this.arbitraryDataFile.split(this.chunkSize);
        if (chunkCount > 0) {
            LOGGER.info(String.format("Successfully split into %d chunk%s", chunkCount, (chunkCount == 1 ? "" : "s")));
        }
    }

    private void createMetadataFile() throws IOException, DataException {
        // If we have at least one chunk, we need to create an index file containing their hashes
        if (this.needsMetadataFile()) {
            // Create the JSON file
            Path chunkFilePath = Paths.get(this.workingPath.toString(), "metadata.json");
            ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(chunkFilePath);
            metadata.setTitle(this.title);
            metadata.setDescription(this.description);
            metadata.setTags(this.tags);
            metadata.setCategory(this.category);
            metadata.setChunks(this.arbitraryDataFile.chunkHashList());
            metadata.setFiles(this.files);
            metadata.setMimeType(this.mimeType);
            metadata.write();

            // Create an ArbitraryDataFile from the JSON file (we don't have a signature yet)
            ArbitraryDataFile metadataFile = ArbitraryDataFile.fromPath(chunkFilePath, null);
            this.arbitraryDataFile.setMetadataFile(metadataFile);
        }
    }

    private void validate() throws IOException, DataException {
        if (this.arbitraryDataFile == null) {
            throw new DataException("No file available when validating");
        }
        this.arbitraryDataFile.setSecret(this.aesKey.getEncoded());

        // Validate the file
        ValidationResult validationResult = this.arbitraryDataFile.isValid();
        if (validationResult != ValidationResult.OK) {
            throw new DataException(String.format("File %s failed validation: %s", this.arbitraryDataFile, validationResult));
        }
        LOGGER.info("Whole file hash is valid: {}", this.arbitraryDataFile.digest58());

        // Validate each chunk
        for (ArbitraryDataFileChunk chunk : this.arbitraryDataFile.getChunks()) {
            validationResult = chunk.isValid();
            if (validationResult != ValidationResult.OK) {
                throw new DataException(String.format("Chunk %s failed validation: %s", chunk, validationResult));
            }
        }
        LOGGER.info("Chunk hashes are valid");

        // Validate chunks metadata file
        if (this.arbitraryDataFile.chunkCount() > 1) {
            ArbitraryDataFile metadataFile = this.arbitraryDataFile.getMetadataFile();
            if (metadataFile == null || !metadataFile.exists()) {
                throw new DataException("No metadata file available, but there are multiple chunks");
            }
            // Read the file
            ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(metadataFile.getFilePath());
            metadata.read();
            // Check all chunks exist
            for (byte[] chunk : this.arbitraryDataFile.chunkHashList()) {
                if (!metadata.containsChunk(chunk)) {
                    throw new DataException(String.format("Missing chunk %s in metadata file", Base58.encode(chunk)));
                }
            }

            // Check that the metadata is correct
            if (!Objects.equals(metadata.getTitle(), this.title)) {
                throw new DataException("Metadata mismatch: title");
            }
            if (!Objects.equals(metadata.getDescription(), this.description)) {
                throw new DataException("Metadata mismatch: description");
            }
            if (!Objects.equals(metadata.getTags(), this.tags)) {
                throw new DataException("Metadata mismatch: tags");
            }
            if (!Objects.equals(metadata.getCategory(), this.category)) {
                throw new DataException("Metadata mismatch: category");
            }
        }
    }

    private void cleanupFilesystem() throws IOException {
        // Clean up
        if (FilesystemUtils.pathInsideDataOrTempPath(this.compressedPath)) {
            File zippedFile = new File(this.compressedPath.toString());
            if (zippedFile.exists()) {
                zippedFile.delete();
            }
        }
        if (FilesystemUtils.pathInsideDataOrTempPath(this.encryptedPath)) {
            File encryptedFile = new File(this.encryptedPath.toString());
            if (encryptedFile.exists()) {
                encryptedFile.delete();
            }
        }
        if (FilesystemUtils.pathInsideDataOrTempPath(this.workingPath)) {
            FileUtils.deleteDirectory(new File(this.workingPath.toString()));
        }
    }

    private boolean needsMetadataFile() {
        if (this.arbitraryDataFile.chunkCount() > 1) {
            return true;
        }
        if (this.title != null || this.description != null || this.tags != null || this.category != null) {
            return true;
        }
        return false;
    }


    public ArbitraryDataFile getArbitraryDataFile() {
        return this.arbitraryDataFile;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

}
