package org.qortal.data.arbitrary;

import org.qortal.network.Peer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.*;

public class FileFetchPeerStatsManager {

    public static class FilePeerStats {
        private static final int MAX_HISTORY = 20;
        private static final int MIN_REQUIRED_ATTEMPTS = 10;

        private final Deque<Boolean> resultHistory = new ArrayDeque<>(MAX_HISTORY);
        private long lastUsed = System.currentTimeMillis();

        public synchronized void recordResult(boolean success) {
            if (resultHistory.size() >= MAX_HISTORY) {
                resultHistory.removeFirst();
            }
            resultHistory.addLast(success);
            lastUsed = System.currentTimeMillis();
        }

        public synchronized double getSuccessRate() {
            if (resultHistory.isEmpty()) return 1.0;
            long successCount = resultHistory.stream().filter(b -> b).count();
            return (double) successCount / resultHistory.size();
        }

        public synchronized boolean hasEnoughHistory() {
            return resultHistory.size() >= MIN_REQUIRED_ATTEMPTS;
        }

        public synchronized long getLastUsed() {
            return lastUsed;
        }
    }

    private final ConcurrentMap<String, FilePeerStats> statsMap = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final ScheduledExecutorService cleanupScheduler;

    public FileFetchPeerStatsManager(long ttlMillis) {
        this.ttlMillis = ttlMillis;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
        startCleanupTask();
    }

    private String makeKey(String signature58, Peer peer, int hops) {
        return signature58 + "|" + peer.toString() + "|hops=" + hops;
    }

    public void recordSuccess(String signature58, Peer peer, int hops) {
        getOrCreateStats(signature58, peer, hops).recordResult(true);
    }

    public void recordFailure(String signature58, Peer peer, int hops) {
        getOrCreateStats(signature58, peer, hops).recordResult(false);
    }

    private FilePeerStats getOrCreateStats(String signature58, Peer peer, int hops) {
        String key = makeKey(signature58, peer, hops);
        return statsMap.computeIfAbsent(key, k -> new FilePeerStats());
    }

    public FilePeerStats getStats(String signature58, Peer peer, int hops) {
        String key = makeKey(signature58, peer, hops);
        return statsMap.computeIfAbsent(key, k -> new FilePeerStats());
    }

    public double getSuccessRate(String signature58, Peer peer, int hops) {
        return getStats(signature58, peer, hops).getSuccessRate();
    }

    public boolean hasEnoughHistory(String signature58, Peer peer, int hops) {
        return getStats(signature58, peer, hops).hasEnoughHistory();
    }

    public void clearStatsForSignature(String signature58) {
        statsMap.keySet().removeIf(key -> key.startsWith(signature58 + "|"));
    }

    private void startCleanupTask() {
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                statsMap.entrySet().removeIf(entry -> now - entry.getValue().getLastUsed() > ttlMillis);
            } catch (Exception e) {
                System.err.println("Error during FilePeerStats cleanup: " + e.getMessage());
                e.printStackTrace();
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    public void shutdown() {
        cleanupScheduler.shutdownNow();
    }
}
