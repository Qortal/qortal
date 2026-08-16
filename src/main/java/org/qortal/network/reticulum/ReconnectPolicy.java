package org.qortal.network.reticulum;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-peer link-failure state and the backoff window derived from it. One instance per aspect.
 * <p>
 * Aspect separation is free: BASE and DATA destination hashes are distinct (the aspect string goes
 * into {@code hashFromNameAndIdentity}), so a hash can only ever appear in one aspect's policy. The
 * shared failure counter the two aspects used before was therefore never actually shared.
 */
final class ReconnectPolicy {

    // Tracks hashes of peers whose PENDING links were pruned as stuck (>60 s without establishing).
    // When a peer is unreachable, createLinkedPeerFromIdentity() creates a PENDING link that the
    // Reticulum library times out at ~75 s → expirePath() → 60-120 s cull → cascade.
    // After a stuck-PENDING failure or immediate send failure, we back off to requestPath() for
    // PENDING_FAILURE_BACKOFF_MS so the backbone can provide a fresh announce path.
    private static final long PENDING_FAILURE_BACKOFF_MS = 60_000L; // base backoff (first failure); 60s
    // Consecutive PENDING/link failures per peer hash. Drives CAPPED EXPONENTIAL backoff: a
    // permanently-unreachable peer (e.g. a mis-configured/partitioned mesh) would otherwise be retried
    // every ~120s forever, each retry firing a PENDING link → expirePath() cull cascade → sustained
    // reconnect-thread CPU. Backoff doubles per failure up to MAX_PENDING_FAILURE_BACKOFF_MS, so stale
    // peers become effectively dormant. Reset on a successful ACTIVE connection (confirmPeerHash) so
    // transient outages aren't penalised long-term.
    private static final long MAX_PENDING_FAILURE_BACKOFF_MS = 30 * 60_000L; // 30 min cap

    private final Map<String, Long> lastFailureMs = new ConcurrentHashMap<>();
    private final Map<String, Integer> failureCount = new ConcurrentHashMap<>();

    /** Stamp a PENDING/link-establishment failure and widen this peer's backoff window. */
    void recordFailure(String hashHex) {
        lastFailureMs.put(hashHex, System.currentTimeMillis());
        failureCount.merge(hashHex, 1, Integer::sum);
    }

    /** Whether this peer is still inside its backoff window and should get requestPath() instead. */
    boolean isBackingOff(String hashHex) {
        long lastFailure = lastFailureMs.getOrDefault(hashHex, 0L);
        return (System.currentTimeMillis() - lastFailure) < backoffMs(hashHex);
    }

    /**
     * Capped exponential backoff window for a peer: {@code 60s, 120s, 240s, … , 30min}. The window
     * grows with the consecutive-failure count so peers that never connect are retried ever less
     * frequently (bounding PENDING-link creation and its expirePath cull cascade), while a
     * first/occasional failure still retries quickly.
     */
    long backoffMs(String hashHex) {
        int count = failureCount.getOrDefault(hashHex, 0);
        if (count <= 1) return PENDING_FAILURE_BACKOFF_MS;
        int shift = Math.min(count - 1, 9); // guard against overflow; cap below clamps the value anyway
        long ms = PENDING_FAILURE_BACKOFF_MS << shift;
        return Math.min(ms, MAX_PENDING_FAILURE_BACKOFF_MS);
    }

    /** Clear failure/backoff state for a peer that has connected successfully. */
    void clear(String hashHex) {
        failureCount.remove(hashHex);
        lastFailureMs.remove(hashHex);
    }

    /**
     * Drop state for peers that have not failed within {@code ageMs}.
     * <p>
     * Both maps are keyed by peer hash and only ever grew before: a node that meets announces from a
     * large mesh accumulates an entry per peer it ever failed to reach, for the process lifetime. A
     * peer evicted here simply starts from the 60s base window again, which is the right answer for
     * one that has been quiet for a day.
     */
    void evictOlderThan(long ageMs) {
        long cutoff = System.currentTimeMillis() - ageMs;
        lastFailureMs.entrySet().removeIf(entry -> {
            if (entry.getValue() >= cutoff) return false;
            failureCount.remove(entry.getKey());
            return true;
        });
    }

    /** Number of peers currently carrying failure state — for tests and diagnostics. */
    int size() {
        return lastFailureMs.size();
    }
}
