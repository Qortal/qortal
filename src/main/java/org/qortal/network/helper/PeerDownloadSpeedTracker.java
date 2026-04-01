package org.qortal.network.helper;

/**
 * Tracks download speed metrics for a peer, specifically for tracking round trip times.
 *
 * <p>This class tracks round trip times using an exponential moving average (EMA)
 * to smooth out temporary spikes while adapting to changing network conditions.
 * RTT values automatically expire after a period of inactivity to prevent stale
 * measurements from affecting peer selection.
 *
 * <p>It is separate from PeerSpeedTracker which tracks transfer time estimates.
 *
 * <p>Not thread-safe — external synchronization is required if used concurrently.
 *
 * @since v5.0.4
 * @author Auto
 * @updated v5.0.8 - Added exponential moving average and idle reset
 * @updated v5.0.9 - Added assignment tracking to prevent slow in-flight requests from blocking peer recovery
 */
public class PeerDownloadSpeedTracker {

    private Long averageRoundTripTime = null; // Exponential moving average of RTT (null = no data yet)
    private Long lastUpdateTime = null; // Timestamp of last RTT measurement
    private Long lastAssignedTime = null; // Timestamp when work was last assigned to this peer
    
    // EMA smoothing factor: 0.3 means new samples get 30% weight, old average gets 70%
    // This balances responsiveness (adapts to changes) with stability (filters noise)
    private static final double ALPHA = 0.3;
    
    // Reset RTT after 10 seconds of inactivity - matches the exclusion threshold
    // Allows degraded peers to recover quickly while preventing retry loops
    private static final long IDLE_RESET_MS = 14_000; // 14 seconds

    /**
     * Constructs a {@code PeerDownloadSpeedTracker}.
     */
    public PeerDownloadSpeedTracker() {
    }

    /**
     * Records that a chunk was assigned to this peer for download.
     * This is used to track when we stop assigning work to a peer,
     * allowing the RTT to reset after 10 seconds of no new assignments
     * even if old in-flight requests are still completing.
     */
    public void recordChunkAssigned() {
        lastAssignedTime = System.currentTimeMillis();
    }

    /**
     * Records a new transfer time for download tracking.
     * Uses exponential moving average to smooth RTT measurements over time.
     *
     * @param bytes The number of bytes transferred in the latest measurement.
     * @param ms The time in milliseconds taken to transfer the {@code bytes}.
     */
    public void addNewTimeMetric(int bytes, int ms) {
        if (averageRoundTripTime == null) {
            // First measurement - use as initial value
            averageRoundTripTime = (long) ms;
        } else {
            // Exponential moving average: new_avg = alpha * new_value + (1 - alpha) * old_avg
            // This smooths out spikes while still adapting to changing conditions
            averageRoundTripTime = (long)(ALPHA * ms + (1 - ALPHA) * averageRoundTripTime);
        }
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Returns the smoothed round trip time (exponential moving average).
     * Returns null if no data has been received yet or if the RTT measurement
     * is stale (no activity for more than 10 seconds).
     * 
     * Also resets if we haven't assigned new work to this peer for 10 seconds,
     * allowing peers with slow in-flight requests to recover faster.
     *
     * @return the average round trip time in milliseconds, or null if no recent data
     */
    public Long getLatestRoundTripTime() {
        long now = System.currentTimeMillis();
        
        // Check if we should reset due to inactivity
        // Reset if EITHER condition is met:
        // 1. No measurements received for 10 seconds (peer is idle), OR
        // 2. No new chunks assigned for 10 seconds (we stopped using this peer)
        boolean measurementIdle = (lastUpdateTime != null && 
            now - lastUpdateTime > IDLE_RESET_MS);
        boolean assignmentIdle = (lastAssignedTime != null && 
            now - lastAssignedTime > IDLE_RESET_MS);
        
        // Reset if either condition is met - this prevents peers with slow in-flight
        // requests from being excluded for longer than 10 seconds
        if (measurementIdle || assignmentIdle) {
            averageRoundTripTime = null;
            lastUpdateTime = null;
            lastAssignedTime = null;
            return null;
        }
        
        return averageRoundTripTime;
    }
}

