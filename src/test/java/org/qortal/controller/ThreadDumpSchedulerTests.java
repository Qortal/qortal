package org.qortal.controller;

import org.junit.Test;
import org.qortal.test.common.Common;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.*;

public class ThreadDumpSchedulerTests extends Common {

    @Test
    public void testFullStackPreservesMonitorsAndSynchronizers() throws Exception {
        Object monitor = new Object();
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            synchronized (monitor) {
                lock.lock();
                try {
                    deepStack(20, ready, release);
                } finally {
                    lock.unlock();
                }
            }
        }, "full-stack-test");
        thread.start();
        try {
            assertTrue("Worker did not reach deep stack", ready.await(5, TimeUnit.SECONDS));
            ThreadInfo info = threadInfo(thread);
            assertTrue(info.getStackTrace().length > 20);
            String dump = ThreadDumpScheduler.formatThreadInfo(info);
            for (StackTraceElement frame : info.getStackTrace()) {
                assertTrue("Missing frame: " + frame, dump.contains("\tat " + frame + "\n"));
            }
            assertTrue(dump.contains("locked " + info.getLockedMonitors()[0]));
            assertTrue(dump.contains("Number of locked synchronizers = 1"));
            assertTrue(dump.contains(info.getLockedSynchronizers()[0].toString()));
            assertFalse(dump.contains("\t..."));
        } finally {
            release.countDown();
            thread.join(5000);
        }
        assertFalse("Worker did not terminate", thread.isAlive());
    }

    @Test
    public void testBlockedThreadIncludesLockOwner() throws Exception {
        Object monitor = new Object();
        CountDownLatch ready = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            ready.countDown();
            synchronized (monitor) {
                // The test holds this monitor until after taking the snapshot.
            }
        }, "blocked-stack-test");
        try {
            synchronized (monitor) {
                thread.start();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                ThreadInfo info = threadInfo(thread);
                assertEquals(Thread.State.BLOCKED, info.getThreadState());
                String dump = ThreadDumpScheduler.formatThreadInfo(info);
                assertTrue(dump.contains("owned by \"" + Thread.currentThread().getName() + "\" Id="
                        + Thread.currentThread().getId()));
                assertTrue(dump.contains("blocked on " + info.getLockInfo()));
            }
        } finally {
            thread.join(5000);
        }
        assertFalse("Worker did not terminate", thread.isAlive());
    }

    private static ThreadInfo threadInfo(Thread thread) {
        return ManagementFactory.getThreadMXBean().getThreadInfo(new long[]{thread.getId()}, true, true)[0];
    }

    private static void deepStack(int depth, CountDownLatch ready, CountDownLatch release) {
        if (depth > 0) {
            deepStack(depth - 1, ready, release);
            return;
        }
        ready.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
