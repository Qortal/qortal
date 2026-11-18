package org.qortal.api.resource;

import org.junit.Test;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.repository.DataException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ArbitraryDownloadWaitTests {

    @Test
    public void queuedBuildTimesOutWithoutBusyPolling() throws Exception {
        ArbitraryDataReader reader = mock(ArbitraryDataReader.class);
        when(reader.isBuilding()).thenReturn(true);
        try {
            ArbitraryResource.loadWithRetries(reader, false, 5, 50);
            fail("A queued build must time out");
        } catch (DataException failure) {
            assertTrue(failure.getMessage().contains("Timed out"));
        }
        verify(reader, atMost(2)).isBuilding();
        verify(reader, never()).loadSynchronously(anyBoolean());
    }

    @Test
    public void queuedBuildCanServeCompletedCache() throws Exception {
        ArbitraryDataReader reader = mock(ArbitraryDataReader.class);
        when(reader.isBuilding()).thenReturn(true);
        when(reader.isCachedDataAvailable()).thenReturn(true);
        ArbitraryResource.loadWithRetries(reader, false, 5, 5000);
        verify(reader, never()).loadSynchronously(anyBoolean());
    }

    @Test
    public void forcedRebuildDoesNotAcceptTheOldCache() throws Exception {
        ArbitraryDataReader reader = mock(ArbitraryDataReader.class);
        when(reader.isBuilding()).thenReturn(true, false);
        ArbitraryResource.loadWithRetries(reader, true, 5, 5000);
        verify(reader, never()).isCachedDataAvailable();
        verify(reader).loadSynchronously(true);
    }

    @Test
    public void missingDataRetriesAreCountedAsLoads() throws Exception {
        ArbitraryDataReader reader = mock(ArbitraryDataReader.class);
        doThrow(new MissingDataException("missing chunks")).when(reader).loadSynchronously(false);
        try {
            ArbitraryResource.loadWithRetries(reader, false, 2, 5000);
            fail("Missing chunks must exhaust the retry budget");
        } catch (MissingDataException expected) {
            assertEquals("missing chunks", expected.getMessage());
        }
        verify(reader, times(3)).loadSynchronously(false);
    }

    @Test
    public void waitingDoesNotConsumeTheLoadRetryBudget() throws Exception {
        ArbitraryDataReader reader = mock(ArbitraryDataReader.class);
        when(reader.isBuilding()).thenReturn(true, false);
        ArbitraryResource.loadWithRetries(reader, false, 0, 5000);
        verify(reader).loadSynchronously(false);
    }

    @Test
    public void interruptionReleasesAWaitingRequest() throws Exception {
        ArbitraryDataReader reader = mock(ArbitraryDataReader.class);
        CountDownLatch polled = new CountDownLatch(1);
        when(reader.isBuilding()).thenAnswer(invocation -> {
            polled.countDown();
            return true;
        });
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                ArbitraryResource.loadWithRetries(reader, false, 5, 60_000);
            } catch (Throwable e) {
                result.set(e);
            }
        });
        request.start();
        try {
            assertTrue(polled.await(5, TimeUnit.SECONDS));
            request.interrupt();
            request.join(5000);
            assertFalse(request.isAlive());
            assertTrue(result.get() instanceof InterruptedException);
        } finally {
            request.interrupt();
            request.join(5000);
        }
    }
}
