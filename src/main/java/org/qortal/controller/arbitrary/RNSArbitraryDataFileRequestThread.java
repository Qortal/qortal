package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.RNSArbitraryFileListResponseInfo;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.event.DataMonitorEvent;
import org.qortal.event.EventBus;
import org.qortal.network.RNSPeer;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;

import static java.lang.Thread.NORM_PRIORITY;

public class RNSArbitraryDataFileRequestThread implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileRequestThread.class);

    public RNSArbitraryDataFileRequestThread() {

    }

    @Override
    public void run() {
        Thread.currentThread().setName("Arbitrary Data File Request Thread");
        Thread.currentThread().setPriority(NORM_PRIORITY);

        try {
            while (!Controller.isStopping()) {
                Long now = NTP.getTime();
                this.processFileHashes(now);
            }
        } catch (InterruptedException e) {
            // Fall-through to exit thread...
        }
    }

    private void processFileHashes(Long now) throws InterruptedException {
		if (Controller.isStopping()) {
            return;
        }

        RNSArbitraryDataFileManager arbitraryDataFileManager = RNSArbitraryDataFileManager.getInstance();
        String signature58 = null;
        String hash58 = null;
        RNSPeer peer = null;
        boolean shouldProcess = false;

        synchronized (arbitraryDataFileManager.arbitraryDataFileHashResponses) {
            if (!arbitraryDataFileManager.arbitraryDataFileHashResponses.isEmpty()) {

                // Sort by lowest number of node hops first
                Comparator<RNSArbitraryFileListResponseInfo> lowestHopsFirstComparator =
                        Comparator.comparingInt(RNSArbitraryFileListResponseInfo::getRequestHops);
                arbitraryDataFileManager.arbitraryDataFileHashResponses.sort(lowestHopsFirstComparator);

                Iterator iterator = arbitraryDataFileManager.arbitraryDataFileHashResponses.iterator();
                while (iterator.hasNext()) {
                    if (Controller.isStopping()) {
                        return;
                    }

                    RNSArbitraryFileListResponseInfo responseInfo = (RNSArbitraryFileListResponseInfo) iterator.next();
                    if (responseInfo == null) {
                        iterator.remove();
                        continue;
                    }

                    hash58 = responseInfo.getHash58();
                    peer = responseInfo.getPeer();
                    signature58 = responseInfo.getSignature58();
                    Long timestamp = responseInfo.getTimestamp();

                    if (now - timestamp >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT || signature58 == null || peer == null) {
                        // Ignore - to be deleted
                        iterator.remove();
                        continue;
                    }

                    // Skip if already requesting, but don't remove, as we might want to retry later
                    if (arbitraryDataFileManager.arbitraryDataFileRequests.containsKey(hash58)) {
                        // Already requesting - leave this attempt for later
                        continue;
                    }

                    // We want to process this file
                    shouldProcess = true;
                    iterator.remove();
                    break;
                }
            }
        }

        if (!shouldProcess) {
            // Nothing to do
            Thread.sleep(1000L);
            return;
        }

        byte[] hash = Base58.decode(hash58);
        byte[] signature = Base58.decode(signature58);

        // Fetch the transaction data
        try (final Repository repository = RepositoryManager.getRepository()) {
            ArbitraryTransactionData arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);
            if (arbitraryTransactionData == null) {
                return;
            }

            if (signature == null || hash == null || peer == null || arbitraryTransactionData == null) {
                return;
            }

            LOGGER.trace("Fetching file {} from peer {} via request thread...", hash58, peer);
            arbitraryDataFileManager.fetchArbitraryDataFiles(repository, peer, signature, arbitraryTransactionData, Arrays.asList(hash));

        } catch (DataException e) {
            LOGGER.debug("Unable to process file hashes: {}", e.getMessage());
        }
    }
}
