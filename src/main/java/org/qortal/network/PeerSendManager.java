package org.qortal.network;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.message.Message;

public class PeerSendManager {
    private static final Logger LOGGER = LogManager.getLogger(PeerSendManager.class);

    private static final int MAX_FAILURES = 15;
    private static final int MAX_MESSAGE_ATTEMPTS = 2;
    private static final int SEND_TIMEOUT_MS = 500;
    private static final int RETRY_DELAY_MS = 100;
    private static final long MAX_QUEUE_DURATION_MS = 20_000;
    private static final long COOLDOWN_DURATION_MS = 20_000;

    private final Peer peer;
    private final BlockingQueue<TimedMessage> queue = new LinkedBlockingQueue<>();
    private final ExecutorService executor;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private static final AtomicInteger threadCount = new AtomicInteger(1);

    private volatile boolean coolingDown = false;
    private volatile long lastUsed = System.currentTimeMillis();

    public PeerSendManager(Peer peer) {
        this.peer = peer;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("PeerSendManager-" + peer.getResolvedAddress().getHostString() + "-" + threadCount.getAndIncrement());
            return t;
        });
        start();
    }

    private void start() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimedMessage timedMessage = queue.take();
                    long age = System.currentTimeMillis() - timedMessage.timestamp;

                    if (age > MAX_QUEUE_DURATION_MS) {
                        LOGGER.debug("Dropping stale message {} ({}ms old)", timedMessage.message.getId(), age);
                        continue;
                    }

                    Message message = timedMessage.message;
                    boolean success = false;

                    for (int attempt = 1; attempt <= MAX_MESSAGE_ATTEMPTS; attempt++) {
                        try {
                            if (peer.sendMessageWithTimeout(message, SEND_TIMEOUT_MS)) {
                                success = true;
                                failureCount.set(0); // reset on success
                                break;
                            }
                        } catch (Exception e) {
                            LOGGER.warn("Attempt {} failed for message {} to peer {}: {}", attempt, message.getId(), peer, e.getMessage());
                        }

                        Thread.sleep(RETRY_DELAY_MS);
                    }

                    if (!success) {
                        int totalFailures = failureCount.incrementAndGet();
                        LOGGER.warn("Failed to send message {} to peer {}. Total failures: {}", message.getId(), peer, totalFailures);

                        if (totalFailures >= MAX_FAILURES) {
                            LOGGER.warn("Peer {} exceeded failure limit ({}). Disconnecting...", peer, totalFailures);
                            peer.disconnect("Too many message send failures");
                            coolingDown = true;
                            queue.clear();

                            try {
                                Thread.sleep(COOLDOWN_DURATION_MS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            } finally {
                                coolingDown = false;
                                failureCount.set(0);
                            }
                        }
                    }

                    Thread.sleep(50); // small throttle
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.warn("Unexpected error in PeerSendManager for peer {}: {}", peer, e.getMessage(), e);
                }
            }
        });
    }

    public void queueMessage(Message message) {
        if (coolingDown) {
            LOGGER.debug("In cooldown, ignoring message {}", message.getId());
            return;
        }

        lastUsed = System.currentTimeMillis();
        if (!queue.offer(new TimedMessage(message))) {
            LOGGER.warn("Send queue full, dropping message {}", message.getId());
        }
    }

    public boolean isIdle(long cutoffMillis) {
        return System.currentTimeMillis() - lastUsed > cutoffMillis;
    }

    public void shutdown() {
        queue.clear();
        executor.shutdownNow();
    }

    private static class TimedMessage {
        final Message message;
        final long timestamp;

        TimedMessage(Message message) {
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
