package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataBuildQueueItem;
import org.qortal.utils.NTP;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArbitraryDataBuildManager extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataBuildManager.class);

    private static ArbitraryDataBuildManager instance;

    private volatile boolean isStopping = false;
    private boolean buildInProgress = false;

    /**
     * Map to keep track of arbitrary transaction resources currently being built (or queued).
     */
    public Map<String, ArbitraryDataBuildQueueItem> arbitraryDataBuildQueue = Collections.synchronizedMap(new HashMap<>());

    /**
     * Map to keep track of failed arbitrary transaction builds.
     */
    public Map<String, ArbitraryDataBuildQueueItem> arbitraryDataFailedBuilds = Collections.synchronizedMap(new HashMap<>());


    public ArbitraryDataBuildManager() {

    }

    @Override
    public void run() {
        Thread.currentThread().setName("Arbitrary Data Build Manager");

        try {
            // Use a fixed thread pool to execute the arbitrary data build actions
            // Combined approach: 1 thread reserved for large files + 4 threads with priority aging
            // This prevents small files from being blocked while ensuring large files don't starve
            int threadCount = 5;
            ExecutorService arbitraryDataBuildExecutor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                // Thread 0 is reserved for large files, threads 1-4 use priority aging
                boolean isLargeFileThread = (i == 0);
                arbitraryDataBuildExecutor.execute(new ArbitraryDataBuilderThread(isLargeFileThread));
            }

            while (!isStopping) {
                // Nothing to do yet
                Thread.sleep(5000);
            }

        } catch (InterruptedException e) {
            // Fall-through to exit thread...
        }
    }

    public static ArbitraryDataBuildManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataBuildManager();

        return instance;
    }

    public void shutdown() {
        isStopping = true;
        this.interrupt();
    }


    public void cleanupQueues(Long now) {
        if (now == null) {
            return;
        }
        arbitraryDataBuildQueue.entrySet().removeIf(entry -> entry.getValue().hasReachedBuildTimeout(now));
        arbitraryDataFailedBuilds.entrySet().removeIf(entry -> entry.getValue().hasReachedFailureTimeout(now));
    }

    // Build queue

    public boolean addToBuildQueue(ArbitraryDataBuildQueueItem queueItem) {
        String key = queueItem.getUniqueKey();
        if (key == null) {
            return false;
        }

        if (this.arbitraryDataBuildQueue == null) {
            return false;
        }

        if (NTP.getTime() == null) {
            // Can't use queues until we have synced the time
            return false;
        }

        // Don't add builds that have failed recently
        if (this.isInFailedBuildsList(queueItem)) {
            return false;
        }

        if (this.arbitraryDataBuildQueue.put(key, queueItem) != null) {
            // Already in queue
            return true;
        }

        log(queueItem, String.format("Added %s to build queue", queueItem));

        // Added to queue
        return true;
    }

    public boolean isInBuildQueue(ArbitraryDataBuildQueueItem queueItem) {
        String key = queueItem.getUniqueKey();
        if (key == null) {
            return false;
        }

        if (this.arbitraryDataBuildQueue == null) {
            return false;
        }

        if (this.arbitraryDataBuildQueue.containsKey(key)) {
            // Already in queue
            return true;
        }

        // Not in queue
        return false;
    }


    // Failed builds

    public boolean addToFailedBuildsList(ArbitraryDataBuildQueueItem queueItem) {
        String key = queueItem.getUniqueKey();
        if (key == null) {
            return false;
        }

        if (this.arbitraryDataFailedBuilds == null) {
            return false;
        }

        if (NTP.getTime() == null) {
            // Can't use queues until we have synced the time
            return false;
        }

        if (this.arbitraryDataFailedBuilds.put(key, queueItem) != null) {
            // Already in list
            return true;
        }

        log(queueItem, String.format("Added %s to failed builds list", queueItem));

        // Added to queue
        return true;
    }

    public boolean isInFailedBuildsList(ArbitraryDataBuildQueueItem queueItem) {
        String key = queueItem.getUniqueKey();
        if (key == null) {
            return false;
        }

        if (this.arbitraryDataFailedBuilds == null) {
            return false;
        }

        if (this.arbitraryDataFailedBuilds.containsKey(key)) {
            // Already in list
            return true;
        }

        // Not in list
        return false;
    }


    public void setBuildInProgress(boolean buildInProgress) {
        this.buildInProgress = buildInProgress;
    }

    public boolean getBuildInProgress() {
        return this.buildInProgress;
    }

    private void log(ArbitraryDataBuildQueueItem queueItem, String message) {
        if (queueItem == null) {
            return;
        }

        if (queueItem.isHighPriority()) {
            LOGGER.trace(message);
        }
        else {
            LOGGER.debug(message);
        }
    }
}
