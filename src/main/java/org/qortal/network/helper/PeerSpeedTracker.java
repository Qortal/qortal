package org.qortal.network.helper;

import java.util.Arrays;

/**
 * Tracks recent data transfer speeds for a peer and estimates future transfer times
 * based on a rolling window of recent transfer metrics.
 *
 * <p>This class maintains a fixed-size buffer of the most recent transfer durations,
 * calculates the average time per chunk, and applies an optional margin to account for
 * variability or overhead. It is designed for use in arbitrary data transfer systems
 * where estimating peer performance is beneficial.
 *
 * <p>Not thread-safe — external synchronization is required if used concurrently.
 *
 * <p>Custom settings such as maximum chunk size, history length, and time margin can
 * be configured via setters or constructor parameters.
 *
 * @since v5.0.2
 * @author Ice
 */
public class PeerSpeedTracker {

    private static final int DEFAULT_TRANSFER_TIMEOUT_MS = 180 * 1000; // Default 10s - conservative but reasonable for modern networks (was 180s for dial-up)
    private static final int DEFAULT_MAX_CHUNK_SIZE = 524288;       // Default 512 * 1K bytes

    private int historyCount = 3;
    private int[] transferTimes = new int[historyCount]; // Last 5 Max chunk times
    private int transferTimeOut = 180 * 1000; // Default 180s/3min this is calculated by 512KB at 33.6kbps
    private int averageTransferTime;
    private int maxChunkSize = 512000;
    private int timeMargin = 30;

    /**
     * Constructs a {@code PeerSpeedTracker} using the default transfer timeout.
     *
     * <p>This constructor initializes the tracker with a baseline timeout value
     * (e.g., 120 seconds) for estimating transfer performance, and pre-fills the
     * internal history with this default value to ensure initial averages are valid.
     *
     * @since v5.0.2
     * @author Ice
     */
    public PeerSpeedTracker () {
        this(DEFAULT_TRANSFER_TIMEOUT_MS);
    }

    /**
     * Constructs a PeerSpeedTracker with a custom default transfer timeout.
     *
     * <p>Initializes the transfer time history array and ensures all values
     * are at least the given timeout, providing a starting baseline for estimation.
     *
     * @param defaultTransferTimeout the default timeout in milliseconds
     *
     * @since v5.0.2
     * @author Ice
     */
    public PeerSpeedTracker(int defaultTransferTimeout) {
        this.transferTimeOut = defaultTransferTimeout;
        this.transferTimes = new int[historyCount];
        this.maxChunkSize = DEFAULT_MAX_CHUNK_SIZE;

        // Initialize all entries to at least defaultTransferTimeout
        Arrays.fill(transferTimes, defaultTransferTimeout);
        averageTransferTime = defaultTransferTimeout;   // Set the averageTransferTimer
    }

    /**
     * Returns the current average transfer time for a full data chunk.
     *
     * <p>This average is calculated based on recent transfer metrics and adjusted
     * by the configured time margin once enough data has been collected.
     *
     * @return the estimated average transfer time in milliseconds
     *
     * @since v5.0.2
     * @author Ice
     */
    public int getAvgTransferTime() {
        return this.averageTransferTime;
    }

    /**
     * Adds a new time metric based on a file chunk transfer, scales it to represent a full
     * chunk of size {@code maxChunkSize}, and updates the average transfer time.
     *
     * <p>This method keeps a moving window of recent transfer times in the {@code transferTimes} array.
     * If the array is not yet full, the new metric is added to the next available slot.
     * If the array is full, the oldest metric is removed (FIFO) to make room for the new one.
     *
     * <p>The method also recalculates the {@code averageTransferTime} when the array is full,
     * applying a margin defined by {@code timeMargin}.
     *
     * @param bytes The number of bytes transferred in the latest measurement.
     * @param ms The time in milliseconds taken to transfer the {@code bytes}.
     *
     * @since v5.0.2
     * @author Ice
     */
    public void addNewTimeMetric(int bytes, int ms) {
        // expand the time for chunk to time for MAX_CHUNK
        int maxTime = ms + ((100 - bytes/ maxChunkSize) * ms);


       for (int j = 0; j < transferTimes.length -1; j++) {
           transferTimes[j] = transferTimes[j+1];
       }
       transferTimes[transferTimes.length - 1] = maxTime;

        recalculateAverage();
        averageTransferTime = averageTransferTime + (averageTransferTime * timeMargin /100);

    }

    /**
     * Estimates the time required to transfer a given number of bytes based on the
     * current average transfer time per chunk of size {@code maxChunkSize}.
     *
     * <p>If transfer time data has not been fully loaded (i.e., {@code isLoaded()} returns false),
     * this method returns {@code 0} as an indication that estimation is not yet available.
     *
     * @param bytes The number of bytes for which to estimate the transfer time.
     * @return The estimated transfer time in milliseconds, or {@code 0} if insufficient data is available.
     *
     * @since v5.0.2
     * @author Ice
     */
    public int getEstimatedTime(int bytes) {
        if (averageTransferTime == 0) { // We don't have an average yet
            return DEFAULT_TRANSFER_TIMEOUT_MS;
        }
        return bytes / maxChunkSize * averageTransferTime;
    }

    /**
     * Returns the maximum size (in bytes) of a data chunk used for transfer calculations.
     *
     * <p>This value is used as the standard unit for scaling partial transfers and
     * estimating transfer times in related methods.
     *
     * @return The maximum chunk size in bytes.
     *
     * @since v5.0.2
     * @author Ice
     */
    public int getMaxChunkSize() {
        return this.maxChunkSize;
    }

    /**
     * Sets the maximum chunk size (in bytes) used for transfer calculations.
     *
     * <p>This allows dynamic adjustment of the {@code maxChunkSize}, which may be useful
     * if the system supports variable file chunking strategies in the future.
     *
     * @param chunkSize the new maximum chunk size in bytes
     *
     * @since v5.0.2
     * @author Ice
     */
    public void setMaxChunkSize(int chunkSize) {
        this.maxChunkSize = chunkSize;
    }

    /**
     * Returns the margin multiplier used to adjust the average transfer time.
     *
     * <p>This margin is applied to compensate for variability or overhead in data transfer
     * estimations. It represents a percentage increase over the computed average.
     *
     * @return the time margin multiplier (e.g., 0.15 for a 15% margin)
     *
     * @since v5.0.2
     * @author Ice
     */
    public int getTimeMargin() {
        return this.timeMargin;
    }

    /**
     * Sets the time margin multiplier used to adjust the average transfer time.
     *
     * <p>This margin allows the system to apply a buffer to the calculated average
     * transfer time, accounting for potential overhead or variability in transfer conditions.
     *
     * @param margin the new time margin multiplier (e.g., 15 for a 15% margin)
     *
     * @since v5.0.2
     * @author Ice
     */
    public void setTimeMargin(int margin) {
        // read all times, get base time by removing previous margin amount
        // calculate new time based on new margin
        // put back into array
        this.timeMargin = margin;
    }

    /**
     * Returns the number of data chunks currently tracked in the transfer history.
     *
     * <p>This value reflects how many transfer time entries are being considered for
     * average time calculations and performance estimation.
     *
     * @return the number of transfer history chunks being tracked
     *
     * @since v5.0.2
     * @author Ice
     */
    public int getTransferHistorySize() {
        return this.historyCount;
    }

    /**
     * Resizes the internal transfer history buffer to the specified count.
     *
     * <p>If the new count is greater than the current, the existing data is retained
     * and new slots are added (uninitialized), causing {@code averageTransferTime}
     * to be reset until enough new samples are collected.
     *
     * <p>If the new count is smaller, the most recent entries are kept,
     * and the {@code averageTransferTime} is recalculated from them.
     *
     * @param count the new number of transfer history entries to track
     *
     * @since v5.0.2
     * @author Ice
     */
    public void setTransferHistorySize(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("History count must be greater than zero.");
        }
        if (historyCount == count)
            return;

        int copySize = transferTimes.length;
        int[] newTransferTimes = new int[count];

        if (count > historyCount) {
            // Copy all existing data to the new array
            System.arraycopy(transferTimes, 0, newTransferTimes, 0, copySize);
            // Fill remaining with transferTimeout
            Arrays.fill(newTransferTimes, transferTimes.length, count, transferTimeOut);
            // fill the new array elements with the transferTimeout
            averageTransferTime = 0;  // Change this to recalculate instead of zerp
        } else {
            // Shrinking: copy most recent 'count' values from end of old array
            int startIdx = transferTimes.length - count;
            System.arraycopy(transferTimes, startIdx, newTransferTimes, 0, count);
        }

        this.transferTimes = newTransferTimes;
        this.historyCount = count;
        recalculateAverage();

    }

    /**
     * Returns the configured transfer timeout value in milliseconds (default or set).
     *
     * <p>This timeout represents the  duration expected for completing
     * a full data chunk transfer. It is used to initialize the internal history
     * and can be used as a fallback in estimation or timeout logic.
     *
     * @return the default transfer timeout in milliseconds
     *
     * @since v5.0.2
     * @author Ice
     */
    int getTransferTimeout() {
        return this.transferTimeOut;
    }

    /**
     * Updates the default transfer timeout value and adjusts the transfer history
     * to ensure all recorded times meet or exceed the new timeout.
     *
     * <p>Any existing transfer time in the history that is less than the specified
     * timeout will be increased to match the new minimum. This ensures consistency
     * in performance estimation after increasing the baseline expectation.
     *
     * @param ms the new default transfer timeout in milliseconds
     *
     * @since v5.0.3
     * @author Ice
     */
    void setTransferTimeOut(int ms) {
        this.transferTimeOut = ms;
        for (int i = 0; i < transferTimes.length; i++) {
            if (transferTimes[i] < ms) {
                transferTimes[i] = ms;
            }
        }
        // recalculate the average:
        recalculateAverage();
    }

    /**
     * Applies the configured time margin percentage to the provided base transfer time.
     *
     * <p>This method increases the given {@code baseTime} by a percentage specified by
     * the {@code timeMargin} field. It is used to conservatively estimate transfer durations,
     * accounting for variability, latency, or other environmental factors.
     *
     * <p>For example, if the base time is 1000 ms and the time margin is 30%, the result
     * will be 1300 ms.
     *
     * @param baseTime the base transfer time in milliseconds
     * @return the transfer time with the margin applied
     *
     * @since v5.0.2
     * @author Ice
     */
    private int applyTimeMargin(int baseTime) {
        return baseTime + (baseTime * timeMargin / 100);
    }

    /**
     * Recalculates the average transfer time from the current transfer history buffer.
     *
     * <p>This method sums all values in the {@code transferTimes} array and computes
     * the average duration, then applies the configured {@code timeMargin} to produce
     * a conservative estimate. It is used whenever the buffer is modified or resized.
     *
     * <p>This method assumes the transfer history is fully populated and should not be called
     * unless the {@code transferTimes} array is complete.
     *
     * @since v5.0.2
     * @author Ice
     */
    private void recalculateAverage() {
        int sum = 0;
        for (int time : transferTimes) {
            sum += time;
        }
        averageTransferTime = applyTimeMargin(sum / transferTimes.length);
    }
}
