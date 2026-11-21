package org.qortal.controller.arbitrary;

import org.junit.Test;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.BaseTransactionData;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ArbitraryDataCacheManagerTests {

    @Test
    public void coalescesChunkNotificationsBySignature() {
        ArbitraryDataCacheManager manager = new ArbitraryDataCacheManager();
        for (int i = 0; i < 10_000; i++) {
            manager.addToUpdateQueue(transaction(1));
        }
        List<ArbitraryTransactionData> batch = manager.drainUpdateQueue();
        assertEquals(1, batch.size());
        assertArrayEquals(transaction(1).getSignature(), batch.get(0).getSignature());
        assertTrue(manager.drainUpdateQueue().isEmpty());
    }

    @Test
    public void preservesDifferentTransactionsForTheSameResourceAndBoundsBatches() {
        ArbitraryDataCacheManager manager = new ArbitraryDataCacheManager();
        List<ArbitraryTransactionData> expected = new ArrayList<>();
        for (int i = 0; i < ArbitraryDataCacheManager.MAX_UPDATE_BATCH_SIZE + 3; i++) {
            ArbitraryTransactionData transaction = transaction(i);
            expected.add(transaction);
            manager.addToUpdateQueue(transaction);
            manager.addToUpdateQueue(transaction);
        }
        List<ArbitraryTransactionData> first = manager.drainUpdateQueue();
        assertEquals(ArbitraryDataCacheManager.MAX_UPDATE_BATCH_SIZE, first.size());
        first.addAll(manager.drainUpdateQueue());
        assertEquals(expected, first);
        assertTrue(manager.drainUpdateQueue().isEmpty());
    }

    @Test
    public void notificationDuringAnUpdateIsRetainedForTheNextPass() {
        ArbitraryDataCacheManager manager = new ArbitraryDataCacheManager();
        ArbitraryTransactionData first = transaction(1);
        manager.addToUpdateQueue(first);
        assertEquals(Collections.singletonList(first), manager.drainUpdateQueue());
        ArbitraryTransactionData updated = transaction(1);
        manager.addToUpdateQueue(updated);
        assertSame(updated, manager.drainUpdateQueue().get(0));
    }

    @Test
    public void concurrentReceiptsDoNotLoseDistinctTransactions() throws Exception {
        ArbitraryDataCacheManager manager = new ArbitraryDataCacheManager();
        ExecutorService producers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                results.add(producers.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        manager.addToUpdateQueue(transaction(i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) result.get(5, TimeUnit.SECONDS);
            List<ArbitraryTransactionData> batch = manager.drainUpdateQueue();
            assertEquals(100, batch.size());
            assertEquals(100, batch.stream().map(t -> Arrays.toString(t.getSignature())).distinct().count());
        } finally {
            producers.shutdownNow();
            assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ArbitraryTransactionData transaction(int id) {
        byte[] signature = new byte[64];
        ByteBuffer.wrap(signature).putInt(id);
        BaseTransactionData base = new BaseTransactionData(id, 0, new byte[64], new byte[32], 0L, signature);
        return new ArbitraryTransactionData(base, 5, Service.DOCUMENT.value, 0, 1, "same-name", "same-id",
                id % 2 == 0 ? ArbitraryTransactionData.Method.PUT : ArbitraryTransactionData.Method.PATCH,
                null, ArbitraryTransactionData.Compression.NONE,
                new byte[]{1}, ArbitraryTransactionData.DataType.RAW_DATA, null, Collections.emptyList());
    }
}
