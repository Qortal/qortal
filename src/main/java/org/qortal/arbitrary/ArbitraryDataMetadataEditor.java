package org.qortal.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.utils.FilesystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds a new PUT transaction that republishes the latest version of an existing resource
 * with updated metadata (title, description, tags, category), without the caller having to
 * supply the original data again.
 *
 * The latest state of the resource is rebuilt locally via {@link ArbitraryDataReader}, so this
 * node must hold (or be able to fetch) the resource's data.
 *
 * Any metadata field passed as null (or empty) keeps its existing value.
 */
public class ArbitraryDataMetadataEditor {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataMetadataEditor.class);

    private final Repository repository;
    private final String publicKey58;
    private final long fee;
    private final String name;
    private final Service service;
    private final String identifier;

    private final String title;
    private final String description;
    private final List<String> tags;
    private final Category category;

    private int chunkSize = ArbitraryDataFile.CHUNK_SIZE;

    private ArbitraryDataTransactionBuilder transactionBuilder;

    public ArbitraryDataMetadataEditor(Repository repository, String publicKey58, long fee, String name,
                                       Service service, String identifier,
                                       String title, String description, List<String> tags, Category category) {
        this.repository = repository;
        this.publicKey58 = publicKey58;
        this.fee = fee;
        this.name = name;
        this.service = service;

        // If identifier is a blank string, or reserved keyword "default", treat it as null
        if (identifier == null || identifier.isEmpty() || identifier.equals("default")) {
            identifier = null;
        }
        this.identifier = identifier;

        this.title = ArbitraryDataTransactionMetadata.limitTitle(title);
        this.description = ArbitraryDataTransactionMetadata.limitDescription(description);
        this.tags = ArbitraryDataTransactionMetadata.limitTags(tags);
        this.category = category;
    }

    public void build() throws DataException {
        if (this.name == null || this.service == null) {
            throw new DataException("Missing name or service");
        }

        ArbitraryTransactionData latestTransaction = this.repository.getArbitraryRepository()
                .getLatestTransaction(this.name, this.service, null, this.identifier);
        if (latestTransaction == null) {
            throw new DataException(String.format("No existing resource found for %s %s %s",
                    this.service, this.name, this.identifier != null ? this.identifier : "default"));
        }

        // Merge the requested changes with the existing metadata
        ArbitraryDataTransactionMetadata existing = this.loadExistingMetadata(latestTransaction);
        String newTitle = this.title != null ? this.title : (existing != null ? existing.getTitle() : null);
        String newDescription = this.description != null ? this.description : (existing != null ? existing.getDescription() : null);
        List<String> newTags = this.tags != null ? this.tags : (existing != null ? existing.getTags() : null);
        Category newCategory = this.category != null ? this.category : (existing != null ? existing.getCategory() : null);

        if (newTitle == null && newDescription == null && newTags == null && newCategory == null) {
            throw new DataException("No metadata supplied");
        }
        if (existing != null
                && Objects.equals(existing.getTitle(), newTitle)
                && Objects.equals(existing.getDescription(), newDescription)
                && Objects.equals(existing.getTags(), newTags)
                && Objects.equals(existing.getCategory(), newCategory)) {
            throw new DataException("Metadata is unchanged");
        }

        // Rebuild the latest state of the resource locally
        Path builtPath;
        try {
            ArbitraryDataReader reader = new ArbitraryDataReader(this.name, ResourceIdType.NAME, this.service, this.identifier);
            reader.loadSynchronously(false);
            builtPath = reader.getFilePath();
        } catch (MissingDataException e) {
            throw new DataException("Resource data is not available on this node yet. Load the resource and try again.");
        } catch (IOException e) {
            throw new DataException(String.format("Unable to build existing resource: %s", e.getMessage()));
        }
        if (builtPath == null || !Files.exists(builtPath)) {
            throw new DataException("Unable to build existing resource");
        }

        Path workingPath = null;
        try {
            workingPath = this.copyWithoutQortalDirectory(builtPath);

            ArbitraryDataTransactionBuilder builder = new ArbitraryDataTransactionBuilder(this.repository,
                    this.publicKey58, this.fee, workingPath, this.name, Method.PUT, this.service, this.identifier,
                    newTitle, newDescription, newTags, newCategory);
            builder.setChunkSize(this.chunkSize);
            builder.build();
            this.transactionBuilder = builder;

        } catch (IOException e) {
            throw new DataException(String.format("Unable to prepare resource for republishing: %s", e.getMessage()));
        } finally {
            if (workingPath != null) {
                try {
                    FilesystemUtils.safeDeleteDirectory(workingPath.getParent(), true);
                } catch (IOException e) {
                    LOGGER.info("Unable to delete temporary directory {}: {}", workingPath, e.getMessage());
                }
            }
        }
    }

    private ArbitraryDataTransactionMetadata loadExistingMetadata(ArbitraryTransactionData transactionData) {
        byte[] metadataHash = transactionData.getMetadataHash();
        if (metadataHash == null) {
            return null;
        }
        try {
            ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(metadataHash, transactionData.getSignature());
            if (!metadataFile.exists()) {
                return null;
            }
            ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(metadataFile.getFilePath());
            metadata.read();
            return metadata;
        } catch (DataException | IOException e) {
            LOGGER.info("Unable to read existing metadata: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Copies the built resource into a new temporary directory, excluding the
     * internal .qortal folder, and returns the path of the copy.
     */
    private Path copyWithoutQortalDirectory(Path source) throws IOException, DataException {
        Path baseDir = Paths.get(Settings.getInstance().getTempDataPath(), "metadata-edit");
        Files.createDirectories(baseDir);
        Path tempDir = Files.createTempDirectory(baseDir, "edit-");
        Path dest = tempDir.resolve("data");
        Files.createDirectories(dest);

        Path qortalDir = source.resolve(".qortal");
        try (Stream<Path> paths = Files.walk(source)) {
            paths.filter(p -> !p.startsWith(qortalDir))
                    .forEach(p -> {
                        Path target = dest.resolve(source.relativize(p).toString());
                        try {
                            if (Files.isDirectory(p)) {
                                Files.createDirectories(target);
                            } else {
                                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        if (FilesystemUtils.isDirectoryEmpty(dest)) {
            throw new DataException("Existing resource has no data");
        }
        return dest;
    }

    public void computeNonce() throws DataException {
        if (this.transactionBuilder == null) {
            throw new DataException("Transaction must be built before computing nonce");
        }
        this.transactionBuilder.computeNonce();
    }

    public ArbitraryTransactionData getArbitraryTransactionData() {
        return this.transactionBuilder != null ? this.transactionBuilder.getArbitraryTransactionData() : null;
    }

    public ArbitraryDataFile getArbitraryDataFile() {
        return this.transactionBuilder != null ? this.transactionBuilder.getArbitraryDataFile() : null;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }
}
