package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.Peer;
import org.qortal.network.message.MessageType;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.NORM_PRIORITY;

public class ArbitraryDataFileRequestThread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileRequestThread.class);

    private static final Integer FETCHER_LIMIT_PER_PEER = Settings.getInstance().getMaxThreadsForMessageType(MessageType.GET_ARBITRARY_DATA_FILE);
    private static final String FETCHER_THREAD_PREFIX = "Arbitrary Data Fetcher ";

    private ConcurrentHashMap<String, ExecutorService> executorByPeer = new ConcurrentHashMap<>();

    private ArbitraryDataFileRequestThread() {
        cleanupExecutorByPeerScheduler.scheduleAtFixedRate(this::cleanupExecutorsByPeer, 1, 1, TimeUnit.MINUTES);
    }

    private static ArbitraryDataFileRequestThread instance = null;

    public static ArbitraryDataFileRequestThread getInstance() {

        if( instance == null ) {
            instance = new ArbitraryDataFileRequestThread();
        }

        return instance;
    }

    private final ScheduledExecutorService cleanupExecutorByPeerScheduler = Executors.newScheduledThreadPool(1);

    private void cleanupExecutorsByPeer() {

        try {
            this.executorByPeer.forEach((key, value) -> {
                if (value instanceof ThreadPoolExecutor) {
                    ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) value;
                    if (threadPoolExecutor.getActiveCount() == 0) {
                        threadPoolExecutor.shutdown();
                        if (this.executorByPeer.computeIfPresent(key, (k, v) -> null) == null) {
                            LOGGER.trace("removed executor: peer = " + key);
                        }
                    }
                } else {
                    LOGGER.warn("casting issue in cleanup");
                }
            });
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public void processFileHashes(Long now, List<ArbitraryFileListResponseInfo> responseInfos, ArbitraryDataFileManager arbitraryDataFileManager) {
		if (Controller.isStopping()) {
            return;
        }

        Map<String, byte[]> signatureBySignature58 = new HashMap<>(responseInfos.size());
        Map<String, List<ArbitraryFileListResponseInfo>> responseInfoBySignature58 = new HashMap<>();

        for( ArbitraryFileListResponseInfo responseInfo : responseInfos) {

            if( responseInfo == null ) continue;

            if (Controller.isStopping()) {
                return;
            }

            Peer peer = responseInfo.getPeer();

            // if relay timeout, then move on
            if (now - responseInfo.getTimestamp() >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT || responseInfo.getSignature58() == null || peer == null) {
                continue;
            }

            // Skip if already requesting, but don't remove, as we might want to retry later
            if (arbitraryDataFileManager.arbitraryDataFileRequests.containsKey(responseInfo.getHash58())) {
                // Already requesting - leave this attempt for later
                arbitraryDataFileManager.addResponse(responseInfo); // don't remove -> adding back, because it was removed already above
                continue;
            }


            byte[] hash = Base58.decode(responseInfo.getHash58());
            byte[] signature = Base58.decode(responseInfo.getSignature58());

            // check for null
            if (signature == null || hash == null || peer == null) {
                continue;
            }

            // We want to process this file, store and map data to process later
            signatureBySignature58.put(responseInfo.getSignature58(), signature);
            responseInfoBySignature58
                    .computeIfAbsent(responseInfo.getSignature58(), signature58 -> new ArrayList<>())
                    .add(responseInfo);
        }

        // if there are no signatures, then there is nothing to process and nothing query the database
        if( signatureBySignature58.isEmpty() ) return;

        List<ArbitraryTransactionData> arbitraryTransactionDataList = new ArrayList<>();

        // Fetch the transaction data
        try (final Repository repository = RepositoryManager.getRepository()) {
            arbitraryTransactionDataList.addAll(
                ArbitraryTransactionUtils.fetchTransactionDataList(repository, new ArrayList<>(signatureBySignature58.values())));
        } catch (DataException e) {
            LOGGER.warn("Unable to fetch transaction data: {}", e.getMessage());
        }

        if( !arbitraryTransactionDataList.isEmpty() ) {
            long start = System.currentTimeMillis();

            for(ArbitraryTransactionData data : arbitraryTransactionDataList ) {
                String signature58 = Base58.encode(data.getSignature());
                for( ArbitraryFileListResponseInfo responseInfo : responseInfoBySignature58.get(signature58)) {
                    Runnable fetcher = () -> arbitraryDataFileFetcher(arbitraryDataFileManager, responseInfo, data);
                    this.executorByPeer
                            .computeIfAbsent(
                                responseInfo.getPeer().toString(),
                                peer -> Executors.newFixedThreadPool(
                                    FETCHER_LIMIT_PER_PEER,
                                    new NamedThreadFactory(FETCHER_THREAD_PREFIX + responseInfo.getPeer().toString(), NORM_PRIORITY)
                                )
                            )
                            .execute(fetcher);
                }
            }
            long timeLapse = System.currentTimeMillis() - start;
        }
    }

    private void arbitraryDataFileFetcher(ArbitraryDataFileManager arbitraryDataFileManager, ArbitraryFileListResponseInfo responseInfo, ArbitraryTransactionData arbitraryTransactionData)  {
        try {
            Long now = NTP.getTime();

            if (now - responseInfo.getTimestamp() >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT ) {

                Peer peer = responseInfo.getPeer();
                String hash58 = responseInfo.getHash58();
                String signature58 = responseInfo.getSignature58();
                LOGGER.debug("Peer {} version {} didn't fetch data file {} for signature {} due to relay timeout.", peer, peer.getPeersVersionString(), hash58, signature58);
                return;
            }

            arbitraryDataFileManager.fetchArbitraryDataFiles(
                responseInfo.getPeer(),
                arbitraryTransactionData.getSignature(),
                arbitraryTransactionData,
                Arrays.asList(Base58.decode(responseInfo.getHash58())),
                responseInfo
            );
        } catch (DataException e) {
            LOGGER.warn("Unable to process file hashes: {}", e.getMessage());
        }
    }
}