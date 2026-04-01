package org.qortal.arbitrary;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import static java.util.Arrays.stream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static java.util.stream.Collectors.toMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.FilesystemUtils;


public class ArbitraryDataFile {

    // Validation results
    public enum ValidationResult {
        OK(1),
        FILE_TOO_LARGE(10),
        FILE_NOT_FOUND(11);

        public final int value;

        private static final Map<Integer, ArbitraryDataFile.ValidationResult> map = stream(ArbitraryDataFile.ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

        ValidationResult(int value) {
            this.value = value;
        }

        public static ArbitraryDataFile.ValidationResult valueOf(int value) {
            return map.get(value);
        }
    }

    // Resource ID types
    public enum ResourceIdType {
        SIGNATURE,
        FILE_HASH,
        TRANSACTION_DATA,
        NAME
    }

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFile.class);

    public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024; // 2 GiB
    protected static final int MAX_CHUNK_SIZE = 1 * 1024 * 1024; // 1MiB
    public static final int CHUNK_SIZE = 512 * 1024; // 0.5MiB
    public static int SHORT_DIGEST_LENGTH = 8;

    protected Path filePath;
    protected byte[] fileContent;
    private boolean useTemporaryFile;

    protected String hash58;
    protected byte[] signature;
    private ArrayList<ArbitraryDataFileChunk> chunks;
    private byte[] secret;

    // Metadata
    private byte[] metadataHash;
    private ArbitraryDataFile metadataFile;
    private ArbitraryDataTransactionMetadata metadata;
    
    // Cached digest to avoid redundant hash computations (significant speedup for large files)
    private byte[] cachedDigest;
    
    // Cached result of allChunksExist() to avoid redundant file system checks
    private Boolean cachedAllChunksExist;


    public ArbitraryDataFile() {
    }

    public ArbitraryDataFile(String hash58, byte[] signature) throws DataException {
        this.filePath = ArbitraryDataFile.getOutputFilePath(hash58, signature, false);
        this.chunks = new ArrayList<>();
        this.hash58 = hash58;
        this.signature = signature;
    }

    public ArbitraryDataFile(byte[] fileContent, byte[] signature, boolean useTemporaryFile) throws DataException {
        if (fileContent == null) {
            LOGGER.error("fileContent is null");
            return;
        }

        this.chunks = new ArrayList<>();
        this.hash58 = Base58.encode(Crypto.digest(fileContent));
        this.signature = signature;
        LOGGER.trace(String.format("File digest: %s, size: %d bytes", this.hash58, fileContent.length));

        this.fileContent = fileContent;
        this.useTemporaryFile = useTemporaryFile;
    }

    public void save() throws DataException {
        Path outputFilePath;
        if (this.useTemporaryFile) {
            try {
                outputFilePath = Files.createTempFile("qortalRawData", null);
                outputFilePath.toFile().deleteOnExit();
            }
            catch (IOException e) {
                throw new DataException(String.format("Unable to write data with hash %s to temporary file: %s", this.hash58, e.getMessage()));
            }
        }
        else {
            outputFilePath = getOutputFilePath(this.hash58, signature, true);
        }

        File outputFile = outputFilePath.toFile();
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(fileContent);
            this.filePath = outputFilePath;
        } catch (IOException e) {
            this.delete();
            throw new DataException(String.format("Unable to write data with hash %s: %s", this.hash58, e.getMessage()));
        }
    }

    public static ArbitraryDataFile fromHash58(String hash58, byte[] signature) throws DataException {
        return new ArbitraryDataFile(hash58, signature);
    }

    public static ArbitraryDataFile fromHash(byte[] hash, byte[] signature) throws DataException {
        if (hash == null) {
            return null;
        }
        return ArbitraryDataFile.fromHash58(Base58.encode(hash), signature);
    }

    public static ArbitraryDataFile fromRawData(byte[] data, byte[] signature) throws DataException {
        if (data == null) {
            return null;
        }
        return new ArbitraryDataFile(data, signature, true);
    }

    public static ArbitraryDataFile fromTransactionData(ArbitraryTransactionData transactionData) throws DataException {
        ArbitraryDataFile arbitraryDataFile = null;
        byte[] signature = transactionData.getSignature();
        byte[] data = transactionData.getData();

        if (data == null) {
            return null;
        }

        // Create data file
        switch (transactionData.getDataType()) {
            case DATA_HASH:
                arbitraryDataFile = ArbitraryDataFile.fromHash(data, signature);
                break;

            case RAW_DATA:
                arbitraryDataFile = ArbitraryDataFile.fromRawData(data, signature);
                arbitraryDataFile.save();
                ValidationResult result = arbitraryDataFile.isValid();
                if (result == ValidationResult.OK) {
                    // Clear fileContent after validation - data is safely on disk
                    arbitraryDataFile.clearFileContent();
                } else {
                    throw new DataException("Failed to validate saved RAW_DATA");
                }
                break;
        }

        // Set metadata hash
        if (arbitraryDataFile != null) {
            arbitraryDataFile.setMetadataHash(transactionData.getMetadataHash());
        }

        return arbitraryDataFile;
    }

    public static ArbitraryDataFile fromPath(Path path, byte[] signature) {
        if (path == null) {
            return null;
        }
        File file = path.toFile();
        if (file.exists()) {
            try {
                byte[] digest = Crypto.digestFileStream(file);
                ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest, signature);

                // Copy file to data directory if needed
                if (Files.exists(path) && !arbitraryDataFile.isInBaseDirectory(path)) {
                    arbitraryDataFile.copyToDataDirectory(path, signature);
                }
                // Or, if it's already in the data directory, we may need to move it
                else if (!path.equals(arbitraryDataFile.getFilePath())) {
                    // Wrong path, so relocate (but don't cleanup, as the source folder may still be needed by the caller)
                    Path dest = arbitraryDataFile.getFilePath();
                    FilesystemUtils.moveFile(path, dest, false);
                }
                return arbitraryDataFile;

            } catch (IOException | DataException e) {
                LOGGER.error("Couldn't compute digest for ArbitraryDataFile");
            }
        }
        return null;
    }

    public static ArbitraryDataFile fromFile(File file, byte[] signature) {
        return ArbitraryDataFile.fromPath(Paths.get(file.getPath()), signature);
    }

    private Path copyToDataDirectory(Path sourcePath, byte[] signature) throws DataException {
        if (this.hash58 == null || this.filePath == null) {
            return null;
        }
        Path outputFilePath = getOutputFilePath(this.hash58, signature, true);
        sourcePath = sourcePath.toAbsolutePath();
        Path destPath = outputFilePath.toAbsolutePath();
        try {
            return Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DataException(String.format("Unable to copy file %s to data directory %s", sourcePath, destPath));
        }
    }

    public static Path getOutputFilePath(String hash58, byte[] signature, boolean createDirectories) throws DataException {
        Path directory;

        if (hash58 == null) {
            return null;
        }
        if (signature != null) {
            // Key by signature
            String signature58 = Base58.encode(signature);
            String sig58First2Chars = signature58.substring(0, 2).toLowerCase();
            String sig58Next2Chars = signature58.substring(2, 4).toLowerCase();
            directory = Paths.get(Settings.getInstance().getDataPath(), sig58First2Chars, sig58Next2Chars, signature58);
        }
        else {
            // Put files without signatures in a "_misc" directory, and the files will be relocated later
            String hash58First2Chars = hash58.substring(0, 2).toLowerCase();
            String hash58Next2Chars = hash58.substring(2, 4).toLowerCase();
            directory = Paths.get(Settings.getInstance().getDataPath(), "_misc", hash58First2Chars, hash58Next2Chars);
        }

        if (createDirectories) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new DataException("Unable to create data subdirectory");
            }
        }
        return Paths.get(directory.toString(), hash58);
    }

    public ValidationResult isValid() {
        try {
            // Ensure the file exists on disk
            if (!Files.exists(this.filePath)) {
                LOGGER.error("File doesn't exist at path {}", this.filePath);
                return ValidationResult.FILE_NOT_FOUND;
            }

            // Validate the file size
            long fileSize = Files.size(this.filePath);
            if (fileSize > MAX_FILE_SIZE) {
                LOGGER.error(String.format("ArbitraryDataFile is too large: %d bytes (max size: %d bytes)", fileSize, MAX_FILE_SIZE));
                return ArbitraryDataFile.ValidationResult.FILE_TOO_LARGE;
            }

        } catch (IOException e) {
            return ValidationResult.FILE_NOT_FOUND;
        }

        return ValidationResult.OK;
    }

    public void validateFileSize(long expectedSize) throws DataException {
        // Verify that we can determine the file's size
        long fileSize = 0;
        try {
            fileSize = Files.size(this.getFilePath());
        } catch (IOException e) {
            throw new DataException(String.format("Couldn't get file size for transaction %s", Base58.encode(signature)));
        }

        // Ensure the file's size matches the size reported by the transaction
        if (fileSize != expectedSize) {
            throw new DataException(String.format("File size mismatch for transaction %s", Base58.encode(signature)));
        }
    }

    private void addChunk(ArbitraryDataFileChunk chunk) {
        this.chunks.add(chunk);
    }

    private void addChunkHashes(List<byte[]> chunkHashes) throws DataException {
        if (chunkHashes == null || chunkHashes.isEmpty()) {
            return;
        }
        for (byte[] chunkHash : chunkHashes) {
            ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash(chunkHash, this.signature);
            this.addChunk(chunk);
        }
    }

    public List<byte[]> getChunkHashes() {
        List<byte[]> hashes = new ArrayList<>();
        if (this.chunks == null || this.chunks.isEmpty()) {
            return hashes;
        }

        for (ArbitraryDataFileChunk chunkData : this.chunks) {
            hashes.add(chunkData.getHash());
        }

        return hashes;
    }

    public int split(int chunkSize) throws DataException {
        try {

            File file = this.getFile();
            byte[] buffer = new byte[chunkSize];
            this.chunks = new ArrayList<>();

            if (file != null) {
                if (file.exists() && file.length() <= chunkSize) {
                    // No need to split into chunks if we're already below the chunk size
                    return 0;
                }

                try (FileInputStream fileInputStream = new FileInputStream(file);
                     BufferedInputStream bis = new BufferedInputStream(fileInputStream)) {

                    int numberOfBytes;
                    while ((numberOfBytes = bis.read(buffer)) > 0) {
                        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                            out.write(buffer, 0, numberOfBytes);
                            out.flush();

                            ArbitraryDataFileChunk chunk = new ArbitraryDataFileChunk(out.toByteArray(), this.signature);
                            chunk.save();
                            ValidationResult validationResult = chunk.isValid();
                            if (validationResult == ValidationResult.OK) {
                                  // Clear fileContent after saving - data is now on disk and can be reloaded if needed
                            chunk.clearFileContent();
                                this.chunks.add(chunk);
                            } else {
                                throw new DataException(String.format("Chunk %s is invalid", chunk));
                            }
                          
                            
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new DataException("Unable to split file into chunks");
        }

        return this.chunks.size();
    }

    /**
     * Joins all chunks into a single file using parallel pre-reading
     * and computes the file hash incrementally during the write process.
     * 
     * This implementation uses multiple reader threads to pre-read chunks from disk
     * while a single writer thread writes them sequentially to the output file.
     * This overlaps I/O operations and can reduce join time by up to 50%.
     * 
     * Incremental hashing: The SHA-256 hash is computed as chunks are written,
     * eliminating the need for a separate full-file read for hash validation.
     * This saves ~1200ms for large files (e.g., 363MB).
     * 
     * Configuration is optimized for Raspberry Pi and similar low-power devices:
     * - 2 reader threads (SD cards don't benefit from more parallelism)
     * - 4 chunk queue capacity (2MB memory overhead)
     * - 512KB buffer matching chunk size
     * 
     * @return true if join succeeded, false otherwise
     */
    public boolean join() {
        if (this.chunks == null || this.chunks.isEmpty()) {
            return false;
        }

        Path outputPath;
        try {
            outputPath = getOutputFilePath(this.hash58, this.signature, true);
        } catch (DataException e) {
            LOGGER.error("Failed to get output file path for join: {}", e.getMessage());
            return false;
        }

        // Initialize hash calculation for incremental hashing during write
        // This eliminates the need for a separate full-file read for hash validation
        MessageDigest fileDigest;
        try {
            fileDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error("SHA-256 algorithm not available for incremental hashing: {}", e.getMessage());
            // Continue without incremental hashing - will compute hash later if needed
            fileDigest = null;
        }

        // Pi-friendly configuration:
        // - 2 reader threads (SD cards don't benefit from more parallelism)
        // - Smaller queue to reduce memory pressure (4 * 512KB = 2MB)
        // - Buffer size matches chunk size for efficient reads
        final int READER_THREADS = 2;
        final int QUEUE_CAPACITY = 4;
        final int BUFFER_SIZE = 512 * 1024; // 512KB

        // Bounded blocking queue for pre-read chunk data (index -> data)
        BlockingQueue<Map.Entry<Integer, byte[]>> preReadQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        AtomicBoolean errorOccurred = new AtomicBoolean(false);
        AtomicInteger nextChunkToRead = new AtomicInteger(0);
        int totalChunks = this.chunks.size();

        // Create thread pool for parallel reading
        ExecutorService readerPool = Executors.newFixedThreadPool(READER_THREADS, r -> {
            Thread t = new Thread(r);
            t.setName("ChunkReader-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        // Submit reader tasks - each thread reads chunks and puts them in the queue
        for (int i = 0; i < READER_THREADS; i++) {
            readerPool.submit(() -> {
                byte[] localBuffer = new byte[BUFFER_SIZE];
                while (!errorOccurred.get() && !Thread.currentThread().isInterrupted()) {
                    int chunkIndex = nextChunkToRead.getAndIncrement();
                    if (chunkIndex >= totalChunks) {
                        break;
                    }

                    ArbitraryDataFileChunk chunk = this.chunks.get(chunkIndex);
                    try {
                        // Read entire chunk into memory
                        ByteArrayOutputStream baos = new ByteArrayOutputStream(BUFFER_SIZE);
                        try (InputStream in = Files.newInputStream(chunk.filePath)) {
                            int bytesRead;
                            while ((bytesRead = in.read(localBuffer)) != -1) {
                                baos.write(localBuffer, 0, bytesRead);
                            }
                        }
                        
                        // Put in queue (blocks if queue is full - provides backpressure)
                        preReadQueue.put(new AbstractMap.SimpleEntry<>(chunkIndex, baos.toByteArray()));
                    } catch (IOException | InterruptedException e) {
                        if (!Thread.currentThread().isInterrupted()) {
                            LOGGER.error("Error reading chunk {}: {}", chunkIndex, e.getMessage());
                        }
                        errorOccurred.set(true);
                        break;
                    }
                }
            });
        }

        // Writer runs on main thread - writes chunks in order and updates hash incrementally
        try (OutputStream out = Files.newOutputStream(outputPath, 
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            
            // TreeMap reorders chunks that may arrive out of order from parallel readers
            TreeMap<Integer, byte[]> pendingWrites = new TreeMap<>();
            int nextChunkToWrite = 0;

            while (nextChunkToWrite < totalChunks && !errorOccurred.get()) {
                // Poll from queue with timeout to allow checking error flag
                Map.Entry<Integer, byte[]> entry = preReadQueue.poll(100, TimeUnit.MILLISECONDS);
                
                if (entry != null) {
                    pendingWrites.put(entry.getKey(), entry.getValue());
                }

                // Write all consecutive chunks we have in order
                while (pendingWrites.containsKey(nextChunkToWrite)) {
                    byte[] data = pendingWrites.remove(nextChunkToWrite);
                    // Write to file
                    out.write(data);
                    // Update hash incrementally (eliminates need for separate full-file read)
                    if (fileDigest != null) {
                        fileDigest.update(data);
                    }
                    nextChunkToWrite++;
                }
            }
            
            out.flush();
            this.filePath = outputPath;
            
            // Finalize hash and cache it (saves ~1200ms for large files by avoiding re-read)
            if (fileDigest != null && !errorOccurred.get()) {
                this.cachedDigest = fileDigest.digest();
                LOGGER.debug("Computed file hash incrementally during join");
            }
            
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error writing joined file: {}", e.getMessage());
            errorOccurred.set(true);
        } finally {
            readerPool.shutdownNow();
            try {
                readerPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Clean up incomplete file if join failed
            if (errorOccurred.get() && outputPath != null && Files.exists(outputPath)) {
                try {
                    Files.delete(outputPath);
                    LOGGER.debug("Cleaned up incomplete joined file after error: {}", outputPath);
                } catch (IOException e) {
                    LOGGER.warn("Failed to clean up incomplete joined file: {}", e.getMessage());
                }
            }
        }

        return !errorOccurred.get();
    }



    public boolean delete() {
        // Delete the complete file
        // ... but only if it's inside the Qortal data or temp directory
        if (FilesystemUtils.pathInsideDataOrTempPath(this.filePath)) {
            if (Files.exists(this.filePath)) {
                try {
                    Files.delete(this.filePath);
                    this.cleanupFilesystem();
                    LOGGER.debug("Deleted file {}", this.filePath);
                    return true;
                } catch (IOException e) {
                    LOGGER.warn("Couldn't delete file at path {}", this.filePath);
                }
            }
        }
        return false;
    }

    public boolean delete(int attempts) {
        if (this.filePath == null) {
            return false;
        }

        // Keep trying to delete the data until it is deleted, or we reach 10 attempts
        for (int i=0; i<attempts; i++) {
            if (this.delete()) {
                return true;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                // Fall through to exit method
            }
        }
        return false;
    }

    public boolean deleteAllChunks() {
        boolean success = false;

        // Delete the individual chunks
        if (this.chunks != null && !this.chunks.isEmpty()) {
            Iterator iterator = this.chunks.iterator();
            while (iterator.hasNext()) {
                ArbitraryDataFileChunk chunk = (ArbitraryDataFileChunk) iterator.next();
                success = chunk.delete();
                iterator.remove();
            }
        }
        return success;
    }

    public boolean deleteMetadata() {
        if (this.metadataFile != null && this.metadataFile.exists()) {
            return this.metadataFile.delete();
        }
        return false;
    }

    public boolean deleteAll(boolean deleteMetadata) {
        // Delete the complete file
        boolean fileDeleted = this.delete();

        // Delete the metadata file if requested
        boolean metadataDeleted = false;
        if (deleteMetadata) {
            metadataDeleted = this.deleteMetadata();
        }

        // Delete the individual chunks
        boolean chunksDeleted = this.deleteAllChunks();

        return fileDeleted || metadataDeleted || chunksDeleted;
    }

    protected void cleanupFilesystem() throws IOException {
        // It is essential that use a separate path reference in this method
        // as we don't want to modify this.filePath
        Path path = this.filePath;
        
        FilesystemUtils.safeDeleteEmptyParentDirectories(path);
    }

    public byte[] getBytes() {
        if (this.fileContent != null) {
            return this.fileContent;
        }

        try {
            return Files.readAllBytes(this.filePath);
        } catch (IOException e) {
            LOGGER.error("Unable to read bytes for file");
            return null;
        }
    }

    /**
     * Validates the hash of this file using streaming (memory-efficient).
     * Uses in-memory data if available, otherwise streams from disk.
     * 
     * @param expectedHash the expected hash to validate against
     * @return true if the hash matches, false otherwise
     */
    public boolean validateHash(byte[] expectedHash) {
        if (expectedHash == null) {
            return false;
        }

        byte[] actualHash;
        
        // If data is already in memory, use it (more efficient than reading from disk)
        if (this.fileContent != null) {
            actualHash = Crypto.digest(this.fileContent);
        } else if (this.filePath != null && Files.exists(this.filePath)) {
            // Use streaming hash for files on disk (memory-efficient)
            try {
                actualHash = Crypto.digest(this.filePath.toFile());
            } catch (IOException e) {
                LOGGER.error("Unable to validate hash for file {}: {}", this.filePath, e.getMessage());
                return false;
            }
        } else {
            // No data available
            return false;
        }

        return Arrays.equals(expectedHash, actualHash);
    }

    /**
     * Clears the in-memory file content to allow garbage collection.
     * The file can still be accessed from disk if needed.
     */
    public void clearFileContent() {
        this.fileContent = null;
    }
    
    /**
     * Clears the cached digest. Call this if the underlying file has been modified.
     */
    public void clearCachedDigest() {
        this.cachedDigest = null;
    }


    /* Helper methods */

    private boolean isInBaseDirectory(Path filePath) {
        Path path = filePath.toAbsolutePath();
        String dataPath = Settings.getInstance().getDataPath();
        String basePath = Paths.get(dataPath).toAbsolutePath().toString();
        return path.startsWith(basePath);
    }

    public boolean exists() {
        File file = this.filePath.toFile();
        return file.exists();
    }

    public boolean chunkExists(byte[] hash) {
        for (ArbitraryDataFileChunk chunk : this.chunks) {
            if (Arrays.equals(hash, chunk.getHash())) {
                return chunk.exists();
            }
        }
        if (Arrays.equals(hash, this.metadataHash)) {
            if (this.metadataFile != null) {
                return this.metadataFile.exists();
            }
        }
        if (Arrays.equals(this.getHash(), hash)) {
            return this.exists();
        }
        return false;
    }

    public boolean allChunksExist() {
        // Return cached result if available (significant speedup for repeated calls)
        if (this.cachedAllChunksExist != null) {
            return this.cachedAllChunksExist;
        }
        
        try {
            if (this.metadataHash == null) {
                // We don't have any metadata so can't check if we have the chunks
                // Even if this transaction has no chunks, we don't have the file either (already checked above)
                this.cachedAllChunksExist = false;
                return false;
            }

            if (this.metadataFile == null) {
                this.metadataFile = ArbitraryDataFile.fromHash(this.metadataHash, this.signature);
            }

            // If the metadata file doesn't exist, we can't check if we have the chunks
            if (!metadataFile.getFilePath().toFile().exists()) {
                this.cachedAllChunksExist = false;
                return false;
            }

            if (this.metadata == null) {
                this.setMetadata(new ArbitraryDataTransactionMetadata(this.metadataFile.getFilePath()));
            }

            // Read the metadata
            List<byte[]> chunks = metadata.getChunks();

            // If the chunks array is empty, then this resource has no chunks,
            // so we must return false to avoid confusing the caller.
            if (chunks.isEmpty()) {
                this.cachedAllChunksExist = false;
                return false;
            }

            // Otherwise, we need to check each chunk individually
            for (byte[] chunkHash : chunks) {
                ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash(chunkHash, this.signature);
                if (!chunk.exists()) {
                    this.cachedAllChunksExist = false;
                    return false;
                }
            }

            this.cachedAllChunksExist = true;
            return true;

        } catch (DataException e) {
            // Something went wrong, so assume we don't have all the chunks
            this.cachedAllChunksExist = false;
            return false;
        }
    }

    public boolean anyChunksExist() throws DataException {
        try {
            if (this.metadataHash == null) {
                // We don't have any metadata so can't check if we have the chunks
                // Even if this transaction has no chunks, we don't have the file either (already checked above)
                return false;
            }

            if (this.metadataFile == null) {
                this.metadataFile = ArbitraryDataFile.fromHash(this.metadataHash, this.signature);
            }

            // If the metadata file doesn't exist, we can't check if we have any chunks
            if (!metadataFile.getFilePath().toFile().exists()) {
                return false;
            }

            if (this.metadata == null) {
                this.setMetadata(new ArbitraryDataTransactionMetadata(this.metadataFile.getFilePath()));
            }

            // Read the metadata
            List<byte[]> chunks = metadata.getChunks();
            for (byte[] chunkHash : chunks) {
                ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash(chunkHash, this.signature);
                if (chunk.exists()) {
                    return true;
                }
            }

            return false;

        } catch (DataException e) {
            // Something went wrong, so assume we don't have all the chunks
            return false;
        }
    }

    public boolean allFilesExist() {
        if (this.exists()) {
            return true;
        }

        // Complete file doesn't exist, so check the chunks
        if (this.allChunksExist()) {
            return true;
        }

        return false;
    }

    /**
     * Retrieve a list of file hashes for this transaction that we do not hold locally
     *
     * @return a List of chunk hashes, or null if we are unable to determine what is missing
     */
    public List<byte[]> missingHashes() {
        List<byte[]> missingHashes = new ArrayList<>();
        try {
            if (this.metadataHash == null) {
                // We don't have any metadata so can't check if we have the chunks
                // Even if this transaction has no chunks, we don't have the file either (already checked above)
                return null;
            }

            if (this.metadataFile == null) {
                this.metadataFile = ArbitraryDataFile.fromHash(this.metadataHash, this.signature);
            }

            // If the metadata file doesn't exist, we can't check if we have the chunks
            if (!metadataFile.getFilePath().toFile().exists()) {
                return null;
            }

            if (this.metadata == null) {
                this.setMetadata(new ArbitraryDataTransactionMetadata(this.metadataFile.getFilePath()));
            }

            // Read the metadata
            List<byte[]> chunks = metadata.getChunks();
            for (byte[] chunkHash : chunks) {
                ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash(chunkHash, this.signature);
                if (!chunk.exists()) {
                    missingHashes.add(chunkHash);
                }
            }

            return missingHashes;

        } catch (DataException e) {
            // Something went wrong, so we can't make a sensible decision
            return null;
        }
    }




    public boolean containsChunk(byte[] hash) {
        for (ArbitraryDataFileChunk chunk : this.chunks) {
            if (Arrays.equals(hash, chunk.getHash())) {
                return true;
            }
        }
        return false;
    }

    public long size() {
        try {
            return Files.size(this.filePath);
        } catch (IOException e) {
            return 0;
        }
    }

    public int chunkCount() {
        return this.chunks.size();
    }

    public int fileCount() {
        int fileCount = this.chunkCount();
        
        if (fileCount == 0) {
            // Transactions without any chunks can already be treated as a complete file
            fileCount++;
        }

        if (this.getMetadataHash() != null) {
            // Add the metadata file
            fileCount++;
        }

        return fileCount;
    }

    public List<ArbitraryDataFileChunk> getChunks() {
        return this.chunks;
    }

    public byte[] chunkHashes() throws DataException {
        if (this.chunks != null && !this.chunks.isEmpty()) {
            // Return null if we only have one chunk, with the same hash as the parent
            if (Arrays.equals(this.digest(), this.chunks.get(0).digest())) {
                return null;
            }

            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                for (ArbitraryDataFileChunk chunk : this.chunks) {
                    byte[] chunkHash = chunk.digest();
                    if (chunkHash.length != 32) {
                        LOGGER.info("Invalid chunk hash length: {}", chunkHash.length);
                        throw new DataException("Invalid chunk hash length");
                    }
                    outputStream.write(chunk.digest());
                }
                return outputStream.toByteArray();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    public List<byte[]> chunkHashList() {
        List<byte[]> chunks = new ArrayList<>();

        if (this.chunks != null && !this.chunks.isEmpty()) {
            // Return null if we only have one chunk, with the same hash as the parent
            if (Arrays.equals(this.digest(), this.chunks.get(0).digest())) {
                return null;
            }

            try {
                for (ArbitraryDataFileChunk chunk : this.chunks) {
                    byte[] chunkHash = chunk.digest();
                    if (chunkHash.length != 32) {
                        LOGGER.info("Invalid chunk hash length: {}", chunkHash.length);
                        throw new DataException("Invalid chunk hash length");
                    }
                    chunks.add(chunkHash);
                }
                return chunks;

            } catch (DataException e) {
                return null;
            }
        }
        return null;
    }

    private void loadMetadata() throws DataException {
        try {
            this.metadata.read();

        } catch (DataException | IOException e) {
            throw new DataException(e);
        }
    }

    private File getFile() {
        File file = this.filePath.toFile();
        if (file.exists()) {
            return file;
        }
        return null;
    }

    public Path getFilePath() {
        return this.filePath;
    }

 public byte[] digest() {
    // Return cached digest if available (avoids re-hashing large files)
    if (this.cachedDigest != null) {
        return this.cachedDigest;
    }
    
    File file = this.getFile();
    if (file != null && file.exists()) {
        try {
            this.cachedDigest = Crypto.digestFileStream(file);
            return this.cachedDigest;
        } catch (IOException e) {
            LOGGER.error("Couldn't compute digest for ArbitraryDataFile");
        }
    }
    return null;
}

    public String digest58() {
        if (this.digest() != null) {
            return Base58.encode(this.digest());
        }
        return null;
    }

    public String shortHash58() {
        if (this.hash58 == null) {
            return null;
        }
        return this.hash58.substring(0, Math.min(this.hash58.length(), SHORT_DIGEST_LENGTH));
    }

    public String getHash58() {
        return this.hash58;
    }

    public byte[] getHash() {
        return Base58.decode(this.hash58);
    }

    public String printChunks() {
        String outputString = "";
        if (this.chunkCount() > 0) {
            for (ArbitraryDataFileChunk chunk : this.chunks) {
                if (!outputString.isEmpty()) {
                    outputString = outputString.concat(",");
                }
                outputString = outputString.concat(chunk.digest58());
            }
        }
        return outputString;
    }

    public void setSecret(byte[] secret) {
        this.secret = secret;
    }

    public byte[] getSecret() {
        return this.secret;
    }

    public byte[] getSignature() {
        return this.signature;
    }

    public void setMetadataFile(ArbitraryDataFile metadataFile) {
        this.metadataFile = metadataFile;
    }

    public ArbitraryDataFile getMetadataFile() {
        return this.metadataFile;
    }

    public void setMetadataHash(byte[] hash) throws DataException {
        this.metadataHash = hash;

        if (hash == null) {
            return;
        }
        this.metadataFile = ArbitraryDataFile.fromHash(hash, this.signature);
        if (metadataFile.exists()) {
            this.setMetadata(new ArbitraryDataTransactionMetadata(this.metadataFile.getFilePath()));
            this.addChunkHashes(this.metadata.getChunks());
        }
    }

    public byte[] getMetadataHash() {
        return this.metadataHash;
    }

    public void setMetadata(ArbitraryDataTransactionMetadata metadata) throws DataException {
        this.metadata = metadata;
        this.loadMetadata();
    }

    public ArbitraryDataTransactionMetadata getMetadata() {
        return this.metadata;
    }

    @Override
    public String toString() {
        return this.shortHash58();
    }
}
