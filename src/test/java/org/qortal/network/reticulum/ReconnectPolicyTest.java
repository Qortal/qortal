package org.qortal.network.reticulum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the per-aspect reconnect backoff state.
 * <p>
 * Lives in the production package because {@link ReconnectPolicy} is package-private — the same
 * reason as {@link RNSPeerRegistryTest} and {@link RNSPeerPrunerTest}. Unlike those two the class
 * is pure, so no mocks are needed at all.
 * <p>
 * The eviction tests are the point of this class. {@code RNSAspectRunner} calls
 * {@code evictOlderThan(24 h)} once per reconnect cycle, which means the {@code removeIf} inside
 * it cannot execute below 24 h of uptime — the 6 h soak never reached it, and a longer soak would
 * only show it not crashing, not that it evicted the right entries. Passing a small age here
 * reaches the same code in milliseconds.
 * <p>
 * Timing: the eviction cases sleep and then evict against a cutoff at half the elapsed time, so a
 * scheduling hiccup would have to be tens of milliseconds long to change the outcome.
 */
class ReconnectPolicyTest {

    private static final long BASE_BACKOFF_MS = 60_000L;
    private static final long MAX_BACKOFF_MS = 30 * 60_000L;

    private final ReconnectPolicy policy = new ReconnectPolicy();

    // --- backoff window ---

    @Test
    void unknownPeerIsNotBackingOff() {
        assertFalse(policy.isBackingOff("deadbeef"));
        assertEquals(BASE_BACKOFF_MS, policy.backoffMs("deadbeef"));
        assertEquals(0, policy.size());
    }

    @Test
    void firstFailureStartsTheBaseWindow() {
        policy.recordFailure("aaaa");

        assertTrue(policy.isBackingOff("aaaa"));
        assertEquals(BASE_BACKOFF_MS, policy.backoffMs("aaaa"));
        assertEquals(1, policy.size());
    }

    @Test
    void windowDoublesPerConsecutiveFailure() {
        policy.recordFailure("aaaa");
        assertEquals(BASE_BACKOFF_MS, policy.backoffMs("aaaa"));

        policy.recordFailure("aaaa");
        assertEquals(2 * BASE_BACKOFF_MS, policy.backoffMs("aaaa"));

        policy.recordFailure("aaaa");
        assertEquals(4 * BASE_BACKOFF_MS, policy.backoffMs("aaaa"));

        // still one peer, however many failures it has accumulated
        assertEquals(1, policy.size());
    }

    @Test
    void windowIsCappedAtThirtyMinutes() {
        for (int i = 0; i < 50; i++)
            policy.recordFailure("aaaa");

        assertEquals(MAX_BACKOFF_MS, policy.backoffMs("aaaa"));
    }

    @Test
    void failureStateIsPerPeer() {
        policy.recordFailure("aaaa");
        policy.recordFailure("aaaa");
        policy.recordFailure("bbbb");

        assertEquals(2 * BASE_BACKOFF_MS, policy.backoffMs("aaaa"));
        assertEquals(BASE_BACKOFF_MS, policy.backoffMs("bbbb"));
        assertEquals(2, policy.size());
    }

    @Test
    void clearResetsOnePeerOnly() {
        policy.recordFailure("aaaa");
        policy.recordFailure("aaaa");
        policy.recordFailure("bbbb");

        policy.clear("aaaa");

        assertFalse(policy.isBackingOff("aaaa"));
        assertEquals(BASE_BACKOFF_MS, policy.backoffMs("aaaa"), "counter is cleared, not just the timestamp");
        assertTrue(policy.isBackingOff("bbbb"));
        assertEquals(1, policy.size());
    }

    @Test
    void clearOfAnUnknownPeerIsANoOp() {
        policy.recordFailure("aaaa");

        policy.clear("never-seen");

        assertEquals(1, policy.size());
        assertTrue(policy.isBackingOff("aaaa"));
    }

    // --- eviction (§9 item 15) ---

    @Test
    void evictionDropsStateOlderThanTheAge() throws InterruptedException {
        policy.recordFailure("aaaa");
        assertEquals(1, policy.size());

        Thread.sleep(50L);
        policy.evictOlderThan(25L);

        assertEquals(0, policy.size());
        assertFalse(policy.isBackingOff("aaaa"));
    }

    @Test
    void evictionAlsoDropsTheFailureCounter() throws InterruptedException {
        policy.recordFailure("aaaa");
        policy.recordFailure("aaaa");
        policy.recordFailure("aaaa");
        assertEquals(4 * BASE_BACKOFF_MS, policy.backoffMs("aaaa"));

        Thread.sleep(50L);
        policy.evictOlderThan(25L);

        // an evicted peer must restart from the base window, not from where it left off
        assertEquals(BASE_BACKOFF_MS, policy.backoffMs("aaaa"));
    }

    @Test
    void evictionKeepsRecentState() {
        policy.recordFailure("aaaa");

        policy.evictOlderThan(24 * 60 * 60 * 1000L); // the production age

        assertEquals(1, policy.size());
        assertTrue(policy.isBackingOff("aaaa"));
    }

    @Test
    void evictionIsSelective() throws InterruptedException {
        policy.recordFailure("old");
        Thread.sleep(50L);
        policy.recordFailure("recent");

        policy.evictOlderThan(25L);

        assertEquals(1, policy.size());
        assertFalse(policy.isBackingOff("old"));
        assertTrue(policy.isBackingOff("recent"));
    }

    @Test
    void evictionOnEmptyStateIsANoOp() {
        policy.evictOlderThan(0L);

        assertEquals(0, policy.size());
    }

    @Test
    void failureAfterEvictionStartsAFreshWindow() throws InterruptedException {
        policy.recordFailure("aaaa");
        policy.recordFailure("aaaa");

        Thread.sleep(50L);
        policy.evictOlderThan(25L);
        policy.recordFailure("aaaa");

        assertEquals(1, policy.size());
        assertTrue(policy.isBackingOff("aaaa"));
        assertEquals(BASE_BACKOFF_MS, policy.backoffMs("aaaa"));
    }
}
