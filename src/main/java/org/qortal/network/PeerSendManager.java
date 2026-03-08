package org.qortal.network;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageException;
import org.qortal.network.message.MessageType;


import org.qortal.network.ReticulumPeer;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

public class PeerSendManager {
    private static final Logger LOGGER = LogManager.getLogger(PeerSendManager.class);

    private static final long MAX_MESSAGE_AGE_MS = 30_000L; // 30 seconds - drop messages older than this to prevent sending stale data
    
    // Two-stage pipeline architecture: disk I/O threads -> sender threads
    private static final int DISK_IO_THREAD_COUNT_NETWORK = 1; // Blockchain peers: low chunk volume
    private static final int DISK_IO_THREAD_COUNT_DATA = 2; // NetworkData peers: chunk streams (25–50/sec)
    private static final int PREFETCH_QUEUE_SIZE = 8; // 4MB memory overhead (8 × 500KB chunks) - balanced for single peer
    private static final int SENDER_THREAD_COUNT = 2; // Number of parallel sender threads per peer

    private final Peer peer;
    private final int diskIOThreadCount;
    private final BlockingQueue<TimedMessage> queue = new PriorityBlockingQueue<>(2000); // Thread-safe priority queue for lazy loading
    private final BlockingQueue<PreloadedMessage> preloadedQueue = new LinkedBlockingQueue<>(PREFETCH_QUEUE_SIZE); // Queue of pre-loaded chunks ready to send
    private final ExecutorService diskIOExecutor; // Stage 1: Parallel disk I/O
    private final ExecutorService executor; // Stage 2: Network send (non-blocking)
    private static final AtomicInteger threadCount = new AtomicInteger(1);
    
    // Hash tracking for efficient cleanup checks across all pipeline stages
    // Maps hash58 → timestamp when queued (allows O(1) lookup instead of O(n) queue scanning)
    private final ConcurrentHashMap<String, Long> queuedHashes = new ConcurrentHashMap<>();

    private volatile long lastUsed = System.currentTimeMillis();

    // Some constants, but any integer 1 (best) to 10 (none/append last) are available
    public static final int HIGH_PRIORITY = 1;
    public static final int MEDIUM_PRIORITY = 5;
    public static final int NO_PRIORITY = 10;

    /**
     * Manages outbound message transmission for a single {@link Peer}, using a
     * two-stage pipeline architecture to maximize throughput.
     *
     * <p><b>Two-Stage Pipeline Architecture:</b>
     * <ul>
     *   <li><b>Stage 1 - Disk I/O:</b> 1 thread (network peer) or 4 threads (data peer) read chunks from disk in parallel</li>
     *   <li><b>Stage 2 - Network Send:</b> 2 threads send pre-loaded chunks over network with zero disk blocking</li>
     * </ul>
     *
     * <p>This class implements <b>lazy loading</b> for large messages (like chunk data).
     * Messages are created from {@link MessageFactory} instances only when they're about
     * to be sent, significantly reducing memory usage. For example:
     * <ul>
     *   <li>Traditional approach: 2000 × 0.5MB chunks = 1 GB in memory</li>
     *   <li>Lazy loading: 2000 × ~150 bytes metadata = ~300 KB in memory</li>
     * </ul>
     *
     * <p>This class is responsible for:
     * <ul>
     *   <li>Queuing messages with optional priority ordering.</li>
     *   <li>Parallel disk I/O to hide latency (1 or 4 threads depending on peer type).</li>
     *   <li>Non-blocking network transmission (2 threads).</li>
     *   <li>Gracefully shutting down when requested.</li>
     * </ul>
     *
     * <p>Internally uses:
     * <ul>
     *   <li>A {@code PriorityBlockingQueue} for thread-safe priority-based message queuing.</li>
     *   <li>A {@code LinkedBlockingQueue} for pre-loaded messages ready to send.</li>
     *   <li>Two {@code ExecutorService} instances: one for disk I/O, one for network send.</li>
     *   <li>A custom {@code TimedMessage} class implementing {@code Comparable} to track queue timing and scheduling.</li>
     *   <li>{@link MessageFactory} for lazy message creation from disk.</li>
     * </ul>
     *
     * <p>Usage typically involves calling {@code queueMessage()} or {@code queueMessageWithPriority()}
     * to enqueue messages, while the internal workers process them asynchronously.
     *
     * @see org.qortal.network.Peer
     * @see org.qortal.network.message.Message
     * @see MessageFactory
     *
     * @since v5.0.1
     * @author Ice & Phil
     * @updated v5.0.3 - Added lazy loading support for large messages
     * @updated v5.0.8 - Refactored to two-stage pipeline architecture for 5-10× performance improvement
     */
    public PeerSendManager(Peer peer, boolean isNetworkDataPeer) {
        this.peer = peer;
        this.diskIOThreadCount = isNetworkDataPeer ? DISK_IO_THREAD_COUNT_DATA : DISK_IO_THREAD_COUNT_NETWORK;

        // Stage 1: Disk I/O thread pool (thread count depends on network vs data peer)
        this.diskIOExecutor = Executors.newFixedThreadPool(this.diskIOThreadCount, r -> {
            Thread t = new Thread(r);
            //t.setName("DiskIO-" + peer.getResolvedAddress().getHostString() + "-" + threadCount.getAndIncrement());
            t.setName("DiskIO-" + peer.getPeerIndexString() + "-" + threadCount.getAndIncrement());
            return t;
        });
        
        // Stage 2: Sender thread pool (2 threads for network transmission)
        this.executor = Executors.newFixedThreadPool(SENDER_THREAD_COUNT, r -> {
            Thread t = new Thread(r);
            t.setName("PeerSender-" + peer.getResolvedAddress().getHostString() + "-" + threadCount.getAndIncrement());
            LOGGER.trace("Starting new sender thread: {}", peer.toString());
            return t;
        });
        
        start();
    }
    
    /**
     * Starts the disk I/O stage that prefetches chunks in parallel.
     * 
     * <p>This is Stage 1 of the two-stage pipeline. Multiple threads read chunks from disk
     * in parallel, hiding disk I/O latency. Pre-loaded chunks are placed into the
     * {@code preloadedQueue} where they can be immediately sent by sender threads.
     * 
     * <p>Benefits:
     * <ul>
     *   <li>Multiple concurrent disk reads hide 10-100ms disk latency (data peers)</li>
     *   <li>Sender threads never block on disk I/O</li>
     *   <li>Network continuously fed with data</li>
     *   <li>Bounded memory usage (32 chunks × 500KB = 16MB max)</li>
     * </ul>
     *
     * @since v5.0.8
     * @author Ice
     */
    private void startDiskIOStage() {
        for (int i = 0; i < this.diskIOThreadCount; i++) {
            diskIOExecutor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // Guard: stop processing if peer is no longer connected
                        if (peer.getSocketChannel() == null || 
                            !peer.getSocketChannel().isOpen() || 
                            peer.isStopping()) {
                            LOGGER.trace("Peer {} no longer connected in disk I/O stage, clearing {} queued messages", 
                                       peer, queue.size());
                            queue.clear();
                            queuedHashes.clear(); // Allow request timeout to retry chunks from other peers
                            return;
                        }
                        
                        // Take metadata from original queue (blocks until available)
                        TimedMessage timedMessage = queue.take();
                        
                        long currentTime = System.currentTimeMillis();
                        
                        // Drop messages based on age (time since queued)
                        long age = currentTime - timedMessage.timestamp;
                        if (age > MAX_MESSAGE_AGE_MS) {
                            LOGGER.trace("Dropped stale message in disk I/O stage to peer {}: queued {}ms ago (exceeds max age of {}ms)", 
                                       peer.toString(), age, MAX_MESSAGE_AGE_MS);
                            // Remove from hash tracking when dropping stale message
                            if (timedMessage.hash58 != null) {
                                queuedHashes.remove(timedMessage.hash58);
                                LOGGER.trace("Removed hash {} from tracking (message too old)", timedMessage.hash58);
                            }
                            continue;
                        }
                        
                        // This is where blocking disk I/O happens (10-100ms on slow disks)
                        // But with 8 parallel threads, we hide this latency
                        long loadStart = System.currentTimeMillis();
                        final Message message = timedMessage.createMessage();
                        long messageCreateTime = System.currentTimeMillis() - loadStart;
                        
                        if (message == null) {
                            LOGGER.warn("Failed to create message in disk I/O stage for peer: {}, skipping", peer.toString());
                            // Remove from tracking if message creation failed
                            if (timedMessage.hash58 != null) {
                                queuedHashes.remove(timedMessage.hash58);
                                LOGGER.trace("Removed hash {} from tracking (message creation failed)", timedMessage.hash58);
                            }
                            continue;
                        }
                        
                        // Only log for ARBITRARY_DATA_FILE (actual chunks) to reduce log noise
                        if (message.getType() == MessageType.ARBITRARY_DATA_FILE) {
                            LOGGER.trace("RESPONDER DISK IO: messageId={}, diskLoadTime={}ms, threadId={}", 
                                message.getId(), messageCreateTime, Thread.currentThread().getId());
                        }
                        
                        // Check peer connection again before serializing
                        if (peer.getSocketChannel() == null || 
                            !peer.getSocketChannel().isOpen() || 
                            peer.isStopping()) {
                            LOGGER.debug("Peer {} socket closed during disk I/O, dropping message {}", peer, message.getId());
                            continue;
                        }
                        
                        // Pre-serialize the message (includes any remaining disk I/O)
                        // This ensures sender threads have zero blocking operations
                        long serializeStart = System.currentTimeMillis();
                        byte[] messageBytes = message.toBytes();
                        long serializeTime = System.currentTimeMillis() - serializeStart;
                        
                        if (messageBytes == null) {
                            LOGGER.warn("Failed to serialize message {} in disk I/O stage", message.getId());
                            continue;
                        }
                        
                      
                        
                        // Create lightweight pre-loaded message (no Message reference!)
                        PreloadedMessage preloaded = new PreloadedMessage(
                            message.getId(),
                            message.getType(),
                            messageBytes,
                            timedMessage.hash58  // Pass hash through pipeline
                        );
                        
                        // Put into sender queue (blocks if queue is full - provides backpressure control)
                        // This is intentional: if senders can't keep up, we slow down disk reads
                        preloadedQueue.put(preloaded);
                        
                        long totalTime = System.currentTimeMillis() - loadStart;
                        LOGGER.debug("Preloaded message {} from disk: create {}ms, serialize {}ms, total {}ms, queue size: {}", 
                                    message.getId(), messageCreateTime, serializeTime, totalTime, preloadedQueue.size());
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOGGER.debug("Disk I/O stage interrupted for peer {}", peer);
                        break;
                    } catch (Exception e) {
                        LOGGER.error("Error in disk I/O stage for peer {}: {}", peer, e.getMessage(), e);
                    }
                }
            });
        }
    }

    //private void start() {
    //    executor.submit(() -> {
    /**
     * Starts the sender stage that transmits pre-loaded messages over the network.
     *
     * <p>This is Stage 2 of the two-stage pipeline. This method initializes sender threads that
     * consume pre-loaded messages from the {@code preloadedQueue}. Since messages are already
     * loaded from disk and serialized, sender threads perform ZERO blocking operations.
     * 
     * <p>Benefits:
     * <ul>
     *   <li>No disk I/O blocking - data already in memory</li>
     *   <li>Network continuously saturated</li>
     *   <li>5-10× throughput improvement on systems with slow disk I/O</li>
     * </ul>
     *
     * <p>The method is invoked once in the constructor and should not be called again manually.
     *
     * @since v5.0.1
     * @author Phil
     * @updated v5.0.2
     * @updater Ice
     * @updated v5.0.7 - Changed to multiple threads for parallel processing
     * @updated v5.0.8 - Refactored to two-stage pipeline (disk I/O separated from network send)
     */
    private void startSenderStage() {
        // Submit the sender task multiple times (once per thread in the pool)
        // Each thread consumes pre-loaded messages from the shared queue
        for (int i = 0; i < SENDER_THREAD_COUNT; i++) {
            executor.submit(() -> {
            // Periodic queue size logging for debugging
            long lastLogTime = System.currentTimeMillis();
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Guard: stop processing if peer is no longer connected
                    if (peer.getSocketChannel() == null || 
                        !peer.getSocketChannel().isOpen() || 
                        peer.isStopping()) {
                        LOGGER.trace("Peer {} no longer connected in sender stage, clearing {} preloaded messages", 
                                   peer, preloadedQueue.size());
                        preloadedQueue.clear();
                        queuedHashes.clear(); // Allow request timeout to retry chunks from other peers
                        return;
                    }
                    
                    // Periodic queue size logging
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime >= 10_000) {
                        int inputQueueSize = queue.size();
                        int preloadedQueueSize = preloadedQueue.size();
                        if (inputQueueSize > 0 || preloadedQueueSize > 0) {
                            LOGGER.trace("PeerSendManager for {} has {} pending, {} preloaded", 
                                       peer, inputQueueSize, preloadedQueueSize);
                        }
                        lastLogTime = now;
                    }
                    
                    // Take pre-loaded message (data already in memory - NO DISK I/O!)
                    PreloadedMessage preloaded = preloadedQueue.take();
                    
                    // Calculate how long message waited in preload queue
                    long queueWaitTotal = System.currentTimeMillis() - preloaded.timestamp;
                    
                    // Only log for ARBITRARY_DATA_FILE (actual chunks) to reduce log noise
                    if (preloaded.messageType == MessageType.ARBITRARY_DATA_FILE) {
                        LOGGER.trace("RESPONDER QUEUE WAIT: messageId={}, queuedFor={}ms (from creation to take)", 
                            preloaded.messageId, queueWaitTotal);
                    }
                    
                    // Check peer connection before attempting to send
                    if (peer.getSocketChannel() == null || 
                        !peer.getSocketChannel().isOpen() || 
                        peer.isStopping()) {
                        LOGGER.trace("Peer {} socket closed in sender stage, dropping message {}", 
                                   peer, preloaded.messageId);
                        continue; // Skip this message, continue with next
                    }

                    // Track send time (should be fast since no disk I/O)
                    long sendStartTime = System.currentTimeMillis();

                    // Try to send the pre-serialized message - never block the sender thread
                    // This uses the optimized API that accepts pre-serialized bytes
                    try {
                        if (peer.sendPreSerializedMessage(
                                preloaded.messageId, 
                                preloaded.messageType,
                                preloaded.serializedBytes, 
                                0)) {  // timeout unused - pass 0
                            
                            // Remove hash from tracking AFTER successful send
                            if (preloaded.hash58 != null) {
                                queuedHashes.remove(preloaded.hash58);
                                LOGGER.trace("Removed hash {} from tracking (successfully sent to peer {})",
                                            preloaded.hash58, peer);
                            }
                            
                            // Log timing stats for successful sends - only for ARBITRARY_DATA_FILE
                            long totalTime = System.currentTimeMillis() - preloaded.timestamp;
                            long sendTime = System.currentTimeMillis() - sendStartTime;
                            if (preloaded.messageType == MessageType.ARBITRARY_DATA_FILE) {
                                LOGGER.trace("RESPONDER CHUNK COMPLETE: messageId={}, queueWait={}ms, sendCall={}ms, TOTAL={}ms", 
                                    preloaded.messageId, queueWaitTotal, sendTime, totalTime);
                            }
                        } else {
                            // Backpressure (Peer.sendQueue full)
                            // For simplicity, we drop the message since it's already been loaded
                            // Alternative: could re-queue to preloadedQueue, but risks memory buildup
                            LOGGER.trace("Backpressure for message {} to peer {}, dropping (already loaded)", 
                                        preloaded.messageId, peer);
                            // Remove from tracking since we're dropping it
                            if (preloaded.hash58 != null) {
                                queuedHashes.remove(preloaded.hash58);
                                LOGGER.trace("Removed hash {} from tracking (dropped due to backpressure)", preloaded.hash58);
                            }
                            // Note: With 8 disk I/O threads and only 2 sender threads, this should be rare.
                        }
                    } catch (IOException e) {
                        // TERMINAL — peer socket is closed
                        LOGGER.debug("Peer {} socket closed in sender stage, dropping message {}", 
                                   peer, preloaded.messageId);
                        // Remove from tracking since peer is gone
                        if (preloaded.hash58 != null) {
                            queuedHashes.remove(preloaded.hash58);
                        }
                        return; // stop processing for this peer
                    }
                    
                    // Message and serialized bytes will be garbage collected automatically
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    int lostMessages = preloadedQueue.size();
                    LOGGER.debug("Caught InterruptedException in sender stage for peer {} - {} preloaded messages lost", 
                                peer, lostMessages);
                    break;
                } catch (Exception e) {
                    LOGGER.error("Unexpected error in sender stage for peer {}: {}", peer, e.getMessage(), e);
                }
            }
            });
        }
    }
    
    /**
     * Starts both stages of the two-stage pipeline.
     * 
     * <p>Stage 1 (Disk I/O): 8 threads read chunks from disk in parallel
     * <p>Stage 2 (Network Send): 2 threads send pre-loaded chunks over network
     * 
     * <p>This architecture eliminates blocking disk I/O from the network send path,
     * improving throughput by 5-10× on systems with slow disk I/O (Raspberry Pi, HDDs).
     *
     * @since v5.0.8
     * @author Ice
     */
    private void start() {
        startDiskIOStage();
        startSenderStage();
    }


    /**
     * Queues a message to be sent to the associated peer with the default priority ({@code NO_PRIORITY}).
     *
     * <p>This is a convenience method that delegates to {@link #queueMessageWithPriority(int, Message)},
     * placing the message at the end of the send queue. The message will be scheduled for transmission
     * according to its estimated send time and the current state of the queue.
     *
     * @param message the message to be queued
     * @throws MessageException if the message cannot be properly prepared or queued
     *
     * @since v5.0.1
     * @author Phil
     */
    public void queueMessage(Message message) throws MessageException {
        queueMessageWithPriority(NO_PRIORITY, message, null);
    }
    
    /**
     * Queues a message to be sent with hash tracking for efficient cleanup.
     *
     * @param message the message to be queued
     * @param hash58 Base58-encoded hash for tracking (null if not applicable)
     * @throws MessageException if the message cannot be properly prepared or queued
     *
     * @since v5.0.7
     * @author Ice
     */
    public void queueMessage(Message message, String hash58) throws MessageException {
        queueMessageWithPriority(NO_PRIORITY, message, hash58);
    }
    
    /**
     * Queues a message factory to be sent to the associated peer with the default priority ({@code NO_PRIORITY}).
     *
     * <p>This version accepts a MessageFactory for lazy message creation, significantly reducing
     * memory usage when queueing large messages (like chunk data). The message is only created
     * when it's actually about to be sent.
     *
     * @param messageFactory the factory that will create the message when needed
     * @param estimatedSize the estimated size of the message in bytes (for timing calculations)
     * @throws MessageException if the message factory cannot be properly queued
     *
     * @since v5.0.3
     * @author Ice
     */
    public void queueMessageFactory(MessageFactory messageFactory, int estimatedSize) throws MessageException {
        queueMessageFactoryWithPriority(NO_PRIORITY, messageFactory, estimatedSize, null);
    }
    
    /**
     * Queues a message factory with hash tracking for efficient cleanup.
     *
     * @param messageFactory the factory that will create the message when needed
     * @param estimatedSize the estimated size of the message in bytes (for timing calculations)
     * @param hash58 Base58-encoded hash for tracking (null if not applicable)
     * @throws MessageException if the message factory cannot be properly queued
     *
     * @since v5.0.7
     * @author Ice
     */
    public void queueMessageFactory(MessageFactory messageFactory, int estimatedSize, String hash58) throws MessageException {
        queueMessageFactoryWithPriority(NO_PRIORITY, messageFactory, estimatedSize, hash58);
    }

    /**
     * Queues a message with a given priority into the send queue.
     *
     * <p>Priority ranges from {@code 1} (highest) to {@code 10} (lowest), where
     * {@code HIGH_PRIORITY} (1) inserts at the front of the queue,
     * {@code NO_PRIORITY} (10) simply appends the message, and other values are
     * placed proportionally in the queue based on their priority rank.
     *
     * @param priority the priority value, where 1 is highest and 10 is lowest
     * @param message the message to be queued
     * @param hash58 Base58-encoded hash for tracking (null if not applicable)
     *
     * @since v5.0.2
     * @author Ice
     */
    public void queueMessageWithPriority(int priority, Message message, String hash58) throws MessageException {
        // Wrap the message in a factory that just returns it
        int messageSize = message.toBytes().length;
        MessageFactory factory = () -> message;
        queueMessageFactoryWithPriority(priority, factory, messageSize, hash58);
    }
    
    /**
     * Queues a message factory with a given priority into the send queue.
     *
     * <p>This version uses lazy message creation for memory efficiency.
     * Priority ranges from {@code 1} (highest) to {@code 10} (lowest), where
     * {@code HIGH_PRIORITY} (1) inserts at the front of the queue,
     * {@code NO_PRIORITY} (10) simply appends the message, and other values are
     * placed proportionally in the queue based on their priority rank.
     *
     * @param priority the priority value, where 1 is highest and 10 is lowest
     * @param messageFactory the factory that will create the message when needed
     * @param estimatedSize the estimated size of the message in bytes
     * @param hash58 Base58-encoded hash for tracking (null if not applicable)
     *
     * @since v5.0.3
     * @author Ice
     */
    public void queueMessageFactoryWithPriority(int priority, MessageFactory messageFactory, int estimatedSize, String hash58) throws MessageException {
        lastUsed = System.currentTimeMillis();
        
        // No artificial delay - let pipeline handle natural backpressure through bounded queues
        // Messages are ordered by priority first, then FIFO within same priority
        TimedMessage newTimedMessage = new TimedMessage(
                messageFactory,
                priority,
                hash58);  // Pass hash for tracking (null if not applicable)
        
        // Track hash if provided (INSTANT - no disk I/O, just map insert)
        if (hash58 != null) {
            queuedHashes.put(hash58, System.currentTimeMillis());
            LOGGER.trace("Tracking hash {} in PeerSendManager for peer {}", hash58, peer);
        }
        
        // Simply offer to queue - PriorityBlockingQueue handles all ordering thread-safely
        if (!queue.offer(newTimedMessage)) {
            LOGGER.warn("Queue full ({} messages) for peer {}, dropping message", queue.size(), peer);
            // Remove from tracking if we couldn't queue
            if (hash58 != null) {
                queuedHashes.remove(hash58);
            }
        }
    }

    /**
     * Returns the current number of messages in the send queue.
     *
     * <p>This reflects how many messages are currently waiting to be sent
     * to the associated peer.
     *
     * @return the number of messages in the queue
     *
     * @since v5.0.2
     * @author Ice
     */
    public int getQueueMessageSize() {
        return queue.size();
    }

    /**
     * Checks if a specific hash is currently queued in this PeerSendManager.
     * Uses O(1) hash map lookup instead of scanning queues.
     * 
     * <p>This method tracks hashes across all pipeline stages (Stage 1: queue, 
     * Stage 2: preloadedQueue, Stage 3: Peer.sendQueue) via an internal map
     * that's updated when hashes enter/exit the pipeline.
     *
     * @param hash58 Base58-encoded hash to check
     * @return true if hash is queued (in any stage), false otherwise
     * 
     * @since v5.0.4
     * @author Ice
     */
    public boolean isHashQueued(String hash58) {
        return queuedHashes.containsKey(hash58);
    }

    /**
     * Returns all currently queued hashes for diagnostics.
     * Used for debugging and monitoring pipeline state.
     *
     * @return set of Base58-encoded hashes currently queued
     * 
     * @since v5.0.4
     * @author Ice
     */
    public Set<String> getQueuedHashes() {
        return new HashSet<>(queuedHashes.keySet());
    }

    /**
     * Returns the number of hashes currently being tracked in this PeerSendManager.
     * 
     * @return count of tracked hashes
     * 
     * @since v5.0.4
     * @author Ice
     */
    public int getTrackedHashCount() {
        return queuedHashes.size();
    }

    /**
     * Periodically clean up stale hash tracking entries.
     * Should be called by a maintenance thread to prevent memory leaks.
     * Removes entries older than 60 seconds.
     * 
     * @since v5.0.4
     * @author Ice
     */
    public void cleanupStaleHashTracking() {
        long now = System.currentTimeMillis();
        int countBefore = queuedHashes.size();
        queuedHashes.entrySet().removeIf(entry ->
            (now - entry.getValue()) > 60000L  // Remove hashes older than 60s
        );
        int removed = countBefore - queuedHashes.size();
        if (removed > 0) {
            LOGGER.debug("Cleaned up {} stale hash tracking entries for peer {}", removed, peer);
        }
    }

    /**
     * Returns the peer associated with this PeerSendManager.
     *
     * @return the peer instance
     */
    public Peer getPeer() {
        return peer;
    }

    /**
     * Determines whether the {@code PeerSendManager} has been idle for longer than the specified duration.
     *
     * <p>This method compares the current system time with the timestamp of the last message-related
     * activity. If the elapsed time exceeds the provided {@code cutoffMillis}, the method returns {@code true}.
     * Otherwise, it returns {@code false}.
     *
     * <p>This can be useful for detecting inactive peers and performing cleanup or resource reallocation.
     *
     * @param cutoffMillis the duration in milliseconds to compare against the last activity timestamp
     * @return {@code true} if idle time exceeds {@code cutoffMillis}, otherwise {@code false}
     *
     * @since v5.0.1
     * @author Phil
     */
    public boolean isIdle(long cutoffMillis) {
        return System.currentTimeMillis() - lastUsed > cutoffMillis;
    }

    /**
     * Shuts down the {@code PeerSendManager}, stopping all message processing and clearing both queues.
     *
     * <p>This method immediately halts both the disk I/O and sender executor threads using
     * {@code shutdownNow()} and clears any messages currently pending in both queues.
     * After shutdown, no further message processing will occur, and the instance should
     * be considered unusable.
     *
     * <p>Use this method during application shutdown or when the peer connection is being
     * permanently closed to ensure that system resources are properly released.
     *
     * @since v5.0.1
     * @author Phil
     * @updated v5.0.8 - Now shuts down both disk I/O and sender thread pools
     */
    public void shutdown() {
        queue.clear();
        preloadedQueue.clear();
        queuedHashes.clear();  // Clear hash tracking
        diskIOExecutor.shutdownNow();
        executor.shutdownNow();
        LOGGER.debug("PeerSendManager shutdown complete for peer {}, cleared {} tracked hashes",
                    peer, queuedHashes.size());
    }

    /**
     * Internal helper class representing a message factory with priority and hash tracking.
     *
     * <p>This class tracks when a message was enqueued and its priority for ordering.
     * The message itself is created lazily when needed. Messages are ordered first by 
     * priority (lower number = higher priority), then FIFO by timestamp within same priority.
     *
     * @since v5.0.2
     * @author Ice
     * @updated v5.0.3 - Refactored to use MessageFactory for lazy loading
     * @updated v5.0.9 - Simplified to remove artificial timing delays
     */
    private static class TimedMessage implements Comparable<TimedMessage> {
        private final MessageFactory messageFactory;
        final long timestamp;
        final int priority;  // Lower number = higher priority (1 is best, 10 is worst)
        final String hash58;  // Hash for tracking (null for non-tracked messages)

        /**
         * Constructs a {@code TimedMessage} with priority and hash tracking.
         *
         * @param messageFactory factory to create the message when needed
         * @param priority       priority value (1 = highest, 10 = lowest)
         * @param hash58         Base58-encoded hash for tracking (null if not applicable)
         *
         * @since v5.0.9
         * @author Ice
         */
        TimedMessage(MessageFactory messageFactory, int priority, String hash58) {
            this.messageFactory = messageFactory;
            this.timestamp = System.currentTimeMillis();
            this.priority = priority;
            this.hash58 = hash58;
        }
        
        /**
         * Compares this TimedMessage with another for ordering in the priority queue.
         * Lower priority number = higher priority (1 is best, 10 is worst).
         * If priorities are equal, earlier timestamp wins (FIFO).
         *
         * @param other the other TimedMessage to compare
         * @return negative if this has higher priority, positive if lower, 0 if equal
         */
        @Override
        public int compareTo(TimedMessage other) {
            // Lower priority number = higher priority (1 is best, 10 is worst)
            int priorityCompare = Integer.compare(this.priority, other.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            // If same priority, earlier timestamp wins (FIFO within same priority)
            return Long.compare(this.timestamp, other.timestamp);
        }
        
        /**
         * Creates the actual message by calling the factory.
         * This is only called when the message is about to be sent,
         * minimizing memory usage for large messages.
         * 
         * @return the created message, or null if creation fails
         * @since v5.0.3
         */
        Message createMessage() {
            try {
                return messageFactory.createMessage();
            } catch (MessageException e) {
                LOGGER.warn("Failed to create message: {}", e.getMessage());
                return null;
            }
        }
    }
    
    /**
     * Internal helper class representing a pre-loaded message that has already been
     * read from disk and serialized, ready to send over the network.
     * 
     * <p>This lightweight class holds only the essential data needed for sending,
     * without keeping references to the original Message or ArbitraryDataFile objects.
     * This significantly reduces memory usage, especially in relay scenarios where
     * the same chunks are being sent to multiple peers.
     * 
     * <p>Memory footprint: ~500 KB (serialized bytes) + ~20 bytes (metadata)
     * vs. previous ~1 MB (Message object + serialized bytes)
     *
     * @since v5.0.8
     * @author Ice
     * @updated v5.0.9 - Refactored to lightweight structure for relay optimization, removed unused timing fields
     */
    private static class PreloadedMessage {
        final int messageId;
        final MessageType messageType;
        final byte[] serializedBytes;
        final long timestamp;
        final String hash58;  // Hash for tracking (null for non-tracked messages)
        
        /**
         * Constructs a PreloadedMessage with pre-serialized data ready to send.
         *
         * @param messageId the message ID for tracking and logging
         * @param messageType the type of message (for statistics and logging)
         * @param serializedBytes pre-serialized message bytes (complete, ready to send)
         * @param hash58 Base58-encoded hash for tracking (null if not applicable)
         */
        PreloadedMessage(int messageId, MessageType messageType, byte[] serializedBytes, String hash58) {
            this.messageId = messageId;
            this.messageType = messageType;
            this.serializedBytes = serializedBytes;
            this.timestamp = System.currentTimeMillis();
            this.hash58 = hash58;
        }
    }
}
