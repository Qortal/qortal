package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.transaction.ArbitraryHostedDataItemInfo;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class ArbitraryDataHostMonitor
 */
public class ArbitraryDataHostMonitor extends Thread{

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataHostMonitor.class);

    /**
     * Hosted Data Item Infos
     *
     * Where all the monitored data hosts are collected.
     */
    private List<ArbitraryHostedDataItemInfo> hostedDataItemInfos = new ArrayList<>();

    private static ArbitraryDataHostMonitor instance;
    private volatile boolean isStopping = false;

    /**
     * Get Instance
     *
     * @return the singleton for this class
     */
    public static ArbitraryDataHostMonitor getInstance() {
        if (instance == null)
            instance = new ArbitraryDataHostMonitor();

        return instance;
    }


    @Override
    public void run() {

        if( !Settings.getInstance().isQdnEnabled() || !Settings.getInstance().isHostMonitorEnabled() ) return;

        Thread.currentThread().setName("Arbitrary Data Host Manager");
        Thread.currentThread().setPriority(MIN_PRIORITY);

        final Path dataPath = Paths.get(Settings.getInstance().getDataPath());
        final Path tempPath = Paths.get(Settings.getInstance().getTempDataPath());

        while( true ) {

            // Walk through 3 levels of the file tree and find directories that are greater than 32 characters in length
            // Also exclude the _temp and _misc paths if present

            try {
                // Wait 10 minutes (600 seconds)
                Thread.sleep(600_000);

                if( isStopping ) break;

                LOGGER.info("Collecting index paths ...");

                List<Path> indexPaths
                        = Files.walk(dataPath, 2)
                        .filter(Files::isDirectory)
                        .filter(path -> !path.toAbsolutePath().toString().contains(tempPath.toAbsolutePath().toString())
                                && !path.toString().contains("_misc"))
                        .collect(Collectors.toList());

                LOGGER.info("Collected {} index paths", indexPaths.size());

                Map<String,ArbitraryDataFolderInfo> currentSignatures = new HashMap<>();

                // for each index path, pause and get the valid signatures and folder info
                for (Path indexPath : indexPaths) {

                    if( isStopping ) break;

                    Thread.sleep(10);

                    List<Path> paths = Files.walk(indexPath, 1)
                            .filter(Files::isDirectory)
                            .filter(path -> path.getFileName().toString().length() > 32)
                            .collect(Collectors.toList());

                    for (Path path : paths) {
                        String[] contents = path.toFile().list();
                        if (contents == null || contents.length == 0) {
                            // Ignore empty directories
                            continue;
                        }

                        String signature58 = path.getFileName().toString();

                        currentSignatures.put(signature58, new ArbitraryDataFolderInfo(getDirectorySize(path), contents.length));
                    }
                }

                if( isStopping ) break;

                LOGGER.info("current signatures updated {}", currentSignatures.size());

                List<byte[]> currentSignaturesDecoded = new ArrayList<>(currentSignatures.size());

                // decode all current data signatures
                for( String currentSignature : currentSignatures.keySet() ) {
                    currentSignaturesDecoded.add(Base58.decode(currentSignature));
                }

                // get all transaction data for current signatures
                List<TransactionData> currentTransactions;
                try (final Repository repository = RepositoryManager.getRepository()) {

                    currentTransactions = repository.getTransactionRepository().fromSignatures(currentSignaturesDecoded);

                    LOGGER.info("data count = {}", currentTransactions.size());
                }

                List<ArbitraryHostedDataItemInfo> hostedTransactions = new ArrayList<>(currentTransactions.size());

                // for each current transaction, create hosted data item info
                for( TransactionData currentTransaction : currentTransactions ) {

                    String signature58 = Base58.encode(currentTransaction.getSignature());
                    ArbitraryDataFolderInfo dataFolderInfo = currentSignatures.get(signature58);

                    hostedTransactions.add(new ArbitraryHostedDataItemInfo((ArbitraryTransactionData) currentTransaction, dataFolderInfo));
                }

                synchronized (this.hostedDataItemInfos) {
                    this.hostedDataItemInfos.clear();
                    this.hostedDataItemInfos.addAll(hostedTransactions);
                }

                // Wait 100 minutes (6,000 seconds)
                Thread.sleep(6_000_000);
            } catch (IOException | UncheckedIOException e) {
                LOGGER.warn("Unable to walk through hosted data: {}", e.getMessage());
            } catch(InterruptedException e) {
                if(!isStopping) {
                    LOGGER.error(e.getMessage());
                }
            } catch (Exception e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }

    /**
     * Get Directory Size
     *
     * @param path the path to the directory
     *
     * @return the directory size in bytes
     *
     * @throws IOException
     */
    public static long getDirectorySize(Path path) throws IOException {
        long totalSize = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    totalSize += getDirectorySize(entry);
                } else {
                    totalSize += Files.size(entry);
                }
            }
        }

        return totalSize;
    }

    /**
     * Get Hosted Data Item Infos
     *
     * @return a copy of the infos
     */
    public List<ArbitraryHostedDataItemInfo> getHostedDataItemInfos() {

        List<ArbitraryHostedDataItemInfo> copy;

        synchronized (this.hostedDataItemInfos){
            copy = new ArrayList<>(this.hostedDataItemInfos);
        }

        return copy;
    }

    /**
     * Shutdown
     *
     * Shutdown this thread.
     */
    public void shutdown() {
        isStopping = true;
        this.interrupt();
        instance = null;
    }
}