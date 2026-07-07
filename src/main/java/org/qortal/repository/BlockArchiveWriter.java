package org.qortal.repository;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.Block;
import org.qortal.controller.Controller;
import org.qortal.controller.Synchronizer;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockArchiveData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.settings.Settings;
import org.qortal.transform.TransformationException;
import org.qortal.transform.block.BlockTransformation;
import org.qortal.transform.block.BlockTransformer;
import org.qortal.utils.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class BlockArchiveWriter {

    public enum BlockArchiveWriteResult {
        OK,
        STOPPING,
        NOT_ENOUGH_BLOCKS,
        BLOCK_NOT_FOUND
    }

    public enum BlockArchiveDataSource {
        BLOCK_REPOSITORY, // To build an archive from the Blocks table
        BLOCK_ARCHIVE // To build a new archive from an existing archive
    }

    private static final Logger LOGGER = LogManager.getLogger(BlockArchiveWriter.class);

    public static final long DEFAULT_FILE_SIZE_TARGET_V1 = 100 * 1024 * 1024; // 100MiB
    public static final long DEFAULT_FILE_SIZE_TARGET_V2 = 10 * 1024 * 1024; // 10MiB

    private int startHeight;
    private final int endHeight;
    private final Integer serializationVersion;
    private final Path archivePath;
    private final Repository repository;

    private long fileSizeTarget = DEFAULT_FILE_SIZE_TARGET_V1;
    private boolean shouldEnforceFileSizeTarget = true;

    // Default data source to BLOCK_REPOSITORY; can optionally be overridden
    private BlockArchiveDataSource dataSource = BlockArchiveDataSource.BLOCK_REPOSITORY;

    private boolean shouldLogProgress = false;

    private int writtenCount;
    private int lastWrittenHeight;
    private Path outputPath;

    /**
     * Instantiate a BlockArchiveWriter using a custom archive path
     * @param startHeight
     * @param endHeight
     * @param repository
     */
    public BlockArchiveWriter(int startHeight, int endHeight, Integer serializationVersion, Path archivePath, Repository repository) {
        this.startHeight = startHeight;
        this.endHeight = endHeight;
        this.archivePath = archivePath.toAbsolutePath();
        this.repository = repository;

        if (serializationVersion == null) {
            // When serialization version isn't specified, fetch it from the existing archive
            serializationVersion = this.findSerializationVersion();
        }

        // Reduce default file size target if we're using V2, as the average block size is over 90% smaller
        if (serializationVersion == 2) {
            this.setFileSizeTarget(DEFAULT_FILE_SIZE_TARGET_V2);
        }

        this.serializationVersion = serializationVersion;
    }

    /**
     * Instantiate a BlockArchiveWriter using the default archive path and version
     * @param startHeight
     * @param endHeight
     * @param repository
     */
    public BlockArchiveWriter(int startHeight, int endHeight, Repository repository) {
        this(startHeight, endHeight, null, Paths.get(Settings.getInstance().getRepositoryPath(), "archive"), repository);
    }

    private int findSerializationVersion() {
        // Attempt to fetch the serialization version from the existing archive
        Integer block2SerializationVersion = BlockArchiveReader.getInstance().fetchSerializationVersionForHeight(2);
        if (block2SerializationVersion != null) {
            return block2SerializationVersion;
        }

        // Default to version specified in settings
        return Settings.getInstance().getDefaultArchiveVersion();
    }

    public static int getMaxArchiveHeight(Repository repository) throws DataException {
        // We must only archive trimmed blocks, or the archive will grow far too large
        final int accountSignaturesTrimStartHeight = repository.getBlockRepository().getOnlineAccountsSignaturesTrimHeight();
        final int atTrimStartHeight = repository.getATRepository().getAtTrimHeight();
        final int trimStartHeight = Math.min(accountSignaturesTrimStartHeight, atTrimStartHeight);
        return trimStartHeight - 1; // subtract 1 because these values represent the first _untrimmed_ block
    }

    public static boolean isArchiverUpToDate(Repository repository) throws DataException {
        final int maxArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);
        final int actualArchiveHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight();
        final float progress = (float)actualArchiveHeight / (float) maxArchiveHeight;
        LOGGER.debug(String.format("maxArchiveHeight: %d, actualArchiveHeight: %d, progress: %f",
                maxArchiveHeight, actualArchiveHeight, progress));

        // If archiver is within 95% of the maximum, treat it as up to date
        // We need several percent as an allowance because the archiver will only
        // save files when they reach the target size
        return (progress >= 0.95);
    }

    public BlockArchiveWriteResult write() throws DataException, IOException, TransformationException, InterruptedException {
        // Create the archive folder if it doesn't exist
        // This is generally a subfolder of the db directory, to make bootstrapping easier
        try {
            Files.createDirectories(archivePath);
        } catch (IOException e) {
            LOGGER.info("Unable to create archive folder");
            throw new DataException("Unable to create archive folder");
        }

        // Determine start height of blocks to fetch
        if (startHeight <= 2) {
            // Skip genesis block, as it's not designed to be transmitted, and we can build that from blockchain.json
            // TODO: include genesis block if we can
            startHeight = 2;
        }

        // Header bytes will store the block indexes
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        // Bytes will store the actual block data
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        LOGGER.info(String.format("Fetching blocks from height %d...", startHeight));
        int i = 0;
        while (headerBytes.size() + bytes.size() < this.fileSizeTarget) {

            // pause, since this can be a long process and other processes need to execute
            Thread.sleep(Settings.getInstance().getArchivingPause());

            if (Controller.isStopping()) {
                return BlockArchiveWriteResult.STOPPING;
            }

            // wait until the Synchronizer stops
            if( Synchronizer.getInstance().isSynchronizing() )
                continue;

            int currentHeight = startHeight + i;
            if (currentHeight > endHeight) {
                break;
            }

            //LOGGER.info("Fetching block {}...", currentHeight);

            BlockData blockData = null;
            List<TransactionData> transactions = null;
            List<ATStateData> atStates = null;
            byte[] atStatesHash = null;

            switch (this.dataSource) {
                case BLOCK_ARCHIVE:
                    BlockTransformation archivedBlock = BlockArchiveReader.getInstance().fetchBlockAtHeight(currentHeight);
                    if (archivedBlock != null) {
                        blockData = archivedBlock.getBlockData();
                        transactions = archivedBlock.getTransactions();
                        atStates = archivedBlock.getAtStates();
                        atStatesHash = archivedBlock.getAtStatesHash();
                    }
                    break;

                case BLOCK_REPOSITORY:
                default:
                    blockData = repository.getBlockRepository().fromHeight(currentHeight);
                    break;
            }

            if (blockData == null) {
                return BlockArchiveWriteResult.BLOCK_NOT_FOUND;
            }

            // Write the signature and height into the BlockArchive table
            BlockArchiveData blockArchiveData = new BlockArchiveData(blockData);
            repository.getBlockArchiveRepository().save(blockArchiveData);
            repository.saveChanges();

            // Build the block
            Block block;
            if (atStatesHash != null) {
                block = new Block(repository, blockData, transactions, atStatesHash);
            }
            else if (atStates != null) {
                block = new Block(repository, blockData, transactions, atStates);
            }
            else {
                block = new Block(repository, blockData);
            }

            // Write the block data to some byte buffers
            int blockIndex = bytes.size();
            // Write block index to header
            headerBytes.write(Ints.toByteArray(blockIndex));
            // Write block height
            bytes.write(Ints.toByteArray(block.getBlockData().getHeight()));

            // Get serialized block bytes
            byte[] blockBytes;
            switch (serializationVersion) {
                case 1:
                    blockBytes = BlockTransformer.toBytes(block);
                    break;

                case 2:
                    blockBytes = BlockTransformer.toBytesV2(block);
                    break;

                default:
                    throw new DataException("Invalid serialization version");
            }

            // Write block length
            bytes.write(Ints.toByteArray(blockBytes.length));
            // Write block bytes
            bytes.write(blockBytes);

            // Log every 1000 blocks
            if (this.shouldLogProgress && i % 1000 == 0) {
                LOGGER.info("Archived up to block height {}. Size of current file: {}", currentHeight, StringUtils.formatBytes(headerBytes.size() + bytes.size()));
            }

            i++;

        }
        int totalLength = headerBytes.size() + bytes.size();
        LOGGER.info("Total length of {} blocks is {}", i, StringUtils.formatBytes(totalLength));

        // Validate file size, in case something went wrong
        if (totalLength < fileSizeTarget && this.shouldEnforceFileSizeTarget) {
            return BlockArchiveWriteResult.NOT_ENOUGH_BLOCKS;
        }

        // We have enough blocks to create a new file
        int endHeight = startHeight + i - 1;
        String filePath = String.format("%s/%d-%d.dat", archivePath.toString(), startHeight, endHeight);
        FileOutputStream fileOutputStream = new FileOutputStream(filePath);
        // Write version number
        fileOutputStream.write(Ints.toByteArray(serializationVersion));
        // Write start height
        fileOutputStream.write(Ints.toByteArray(startHeight));
        // Write end height
        fileOutputStream.write(Ints.toByteArray(endHeight));
        // Write total count
        fileOutputStream.write(Ints.toByteArray(i));
        // Write dynamic header (block indexes) segment length
        fileOutputStream.write(Ints.toByteArray(headerBytes.size()));
        // Write dynamic header (block indexes) data
        headerBytes.writeTo(fileOutputStream);
        // Write data segment (block data) length
        fileOutputStream.write(Ints.toByteArray(bytes.size()));
        // Write data
        bytes.writeTo(fileOutputStream);
        // Close the file
        fileOutputStream.close();

        // Invalidate cache so that the rest of the app picks up the new file
        BlockArchiveReader.getInstance().invalidateFileListCache();

        this.writtenCount = i;
        this.lastWrittenHeight = endHeight;
        this.outputPath = Paths.get(filePath);
        return BlockArchiveWriteResult.OK;
    }

    public int getWrittenCount() {
        return this.writtenCount;
    }

    public int getLastWrittenHeight() {
        return this.lastWrittenHeight;
    }

    public Path getOutputPath() {
        return this.outputPath;
    }

    public void setFileSizeTarget(long fileSizeTarget) {
        this.fileSizeTarget = fileSizeTarget;
    }

    // For testing, to avoid having to pre-calculate file sizes
    public void setShouldEnforceFileSizeTarget(boolean shouldEnforceFileSizeTarget) {
        this.shouldEnforceFileSizeTarget = shouldEnforceFileSizeTarget;
    }

    public void setDataSource(BlockArchiveDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setShouldLogProgress(boolean shouldLogProgress) {
        this.shouldLogProgress = shouldLogProgress;
    }

}
