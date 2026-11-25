package org.qortal.controller;

import org.json.JSONObject;
import org.junit.Test;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.PirateWallet;
import org.qortal.test.common.Common;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

import static org.junit.Assert.*;

/** Exercises the same select/read/release boundary used by independent Hub accounts. */
public class PirateWalletSessionTests extends Common {

    @Test
    public void backgroundReadsOnlyClaimTheExactCurrentWallet() throws Exception {
        PirateChainWalletController controller = new PirateChainWalletController();
        PirateWallet current = walletIdentity();
        PirateWallet previous = walletIdentity();
        Field selected = PirateChainWalletController.class.getDeclaredField("currentWallet");
        selected.setAccessible(true);
        selected.set(controller, current);
        assertFalse(controller.tryBeginCurrentWalletUse(null));
        assertFalse(controller.tryBeginCurrentWalletUse(previous));
        assertSame(current, controller.getCurrentWallet());
        assertTrue(controller.tryBeginCurrentWalletUse(current));
        try {
            // A failed nested claim must not release the caller's existing lock.
            assertFalse(controller.tryBeginCurrentWalletUse(previous));
            assertSame(current, controller.getCurrentWallet());
        } finally {
            controller.endWalletUse();
        }
        Field lockField = PirateChainWalletController.class.getDeclaredField("walletLock");
        lockField.setAccessible(true);
        assertFalse(((ReentrantLock) lockField.get(controller)).isLocked());
    }

    @Test(timeout = 10000)
    public void backgroundReadsYieldToAnActiveWalletOperation() throws Exception {
        PirateChainWalletController controller = new PirateChainWalletController();
        PirateWallet current = walletIdentity();
        Field selected = PirateChainWalletController.class.getDeclaredField("currentWallet");
        selected.setAccessible(true);
        selected.set(controller, current);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService request = Executors.newSingleThreadExecutor();
        Future<?> foreground = request.submit(() -> {
            assertTrue(controller.tryBeginCurrentWalletUse(current));
            try {
                held.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                controller.endWalletUse();
            }
        });
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS));
            assertFalse(controller.tryBeginCurrentWalletUse(current));
            assertSame(current, controller.getCurrentWallet());
        } finally {
            release.countDown();
            foreground.get(2, TimeUnit.SECONDS);
            request.shutdownNow();
            assertTrue(request.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    // Opaque identity fixtures: constructing a real wallet initializes JNI and
    // storage. These tests must prove the controller never invokes either.
    private static PirateWallet walletIdentity() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (PirateWallet) ((Unsafe) unsafeField.get(null)).allocateInstance(PirateWallet.class);
    }

    @Test
    public void statusReadsKeepTheRequestedWalletSelectedUntilCompletion() throws Exception {
        ProbeController controller = new ProbeController();
        assertEquals("account-a", controller.getSyncStatusWithInit("account-a"));
        assertEquals("account-b", new JSONObject(controller.getSyncStatusJsonWithInit("account-b"))
                .getString("account"));
        assertFalse(controller.session.isLocked());
    }

    @Test
    public void failedSelectionDoesNotReturnThePreviousAccountsStatus() throws Exception {
        ProbeController controller = new ProbeController();
        controller.activeAccount = "previous-account";
        for (boolean json : new boolean[] {false, true}) {
            try {
                if (json) controller.getSyncStatusJsonWithInit("failed-account");
                else controller.getSyncStatusWithInit("failed-account");
                fail("Failed selection must propagate to the requested account");
            } catch (ForeignBlockchainException expected) {
                assertEquals("selection failed", expected.getMessage());
            }
        }
        assertEquals("previous-account", controller.activeAccount);
        assertFalse(controller.session.isLocked());
    }

    @Test(timeout = 10000)
    public void aSecondHubAccountWaitsWithoutChangingTheFirstStatus() throws Exception {
        ProbeController controller = new ProbeController();
        controller.readEntered = new CountDownLatch(1);
        controller.releaseRead = new CountDownLatch(1);
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = requests.submit(() -> controller.getSyncStatusWithInit("account-a"));
            assertTrue(controller.readEntered.await(2, TimeUnit.SECONDS));
            Future<String> second = requests.submit(() -> controller.getSyncStatusJsonWithInit("account-b"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!controller.session.hasQueuedThreads() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue("Second Hub request should queue behind the active read", controller.session.hasQueuedThreads());
            assertEquals("account-a", controller.activeAccount);
            controller.releaseRead.countDown();
            assertEquals("account-a", first.get(2, TimeUnit.SECONDS));
            assertEquals("account-b", new JSONObject(second.get(2, TimeUnit.SECONDS)).getString("account"));
        } finally {
            controller.releaseRead.countDown();
            requests.shutdownNow();
            assertTrue(requests.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static final class ProbeController extends PirateChainWalletController {
        private final ReentrantLock session = new ReentrantLock(true);
        private volatile String activeAccount;
        private CountDownLatch readEntered;
        private CountDownLatch releaseRead;

        @Override
        public void beginWalletUse(String entropy, boolean nullSeed, boolean requireSync, boolean nonNull)
                throws ForeignBlockchainException {
            if ("failed-account".equals(entropy)) throw new ForeignBlockchainException("selection failed");
            try {
                session.lockInterruptibly();
                activeAccount = entropy;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ForeignBlockchainException("interrupted");
            }
        }

        @Override public void endWalletUse() { session.unlock(); }

        @Override public String getSyncStatus() { return readAccount(); }

        @Override public String getSyncStatusJson() {
            return new JSONObject().put("account", readAccount()).toString();
        }

        private String readAccount() {
            assertTrue("Native context must remain locked during the read", session.isHeldByCurrentThread());
            if (readEntered != null && "account-a".equals(activeAccount)) {
                readEntered.countDown();
                try {
                    assertTrue(releaseRead.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return activeAccount;
        }
    }
}
