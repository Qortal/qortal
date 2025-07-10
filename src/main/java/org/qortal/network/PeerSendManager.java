package org.qortal.network;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.message.Message;

public class PeerSendManager {
    private static final Logger LOGGER = LogManager.getLogger(PeerSendManager.class);

    private static final int MAX_RETRIES = 15;
    private static final int BASE_RETRY_DELAY_MS = 100;

    private final BlockingQueue<Message> queue = new LinkedBlockingQueue<>();
    private final Peer peer;
   private static final AtomicInteger threadCount = new AtomicInteger(1);

private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName("PeerSendManager-" + peer.getResolvedAddress().getHostString() + "-" + threadCount.getAndIncrement());
        return t;
    }
});

    public PeerSendManager(Peer peer) {
        this.peer = peer;
        start();
    }

    private void start() {
        executor.submit(() -> {
            while (true) {
                try {
                    Message message = queue.take(); // Blocks until available
                    boolean success = false;
                    int attempt = 0;

                    while (attempt < MAX_RETRIES) {
                        try {
                            if (peer.sendMessageWithTimeout(message, 5000)) {
                                success = true;
                                break;
                            }
                        } catch (Exception e) {
                            LOGGER.warn("Send attempt {} failed for {} message ID {} to peer {}: {}",
                                    attempt + 1,
                                    message.getType().name(),
                                    message.getId(),
                                    peer,
                                    e.getMessage());
                        }

                        attempt++;
                        try {
                            long delay = Math.min(BASE_RETRY_DELAY_MS * (1L << attempt), 2000);
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    if (!success) {
                        LOGGER.warn("Failed to send {} message ID {} to peer {} after {} attempts. Disconnecting...",
                                message.getType().name(),
                                message.getId(),
                                peer,
                                MAX_RETRIES);
                        peer.disconnect("SendMessage retries exceeded");
                         queue.clear();
                        break;
                    }

                    // Throttle after successful send
                    Thread.sleep(50);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.warn("Unexpected error in PeerSendManager for peer {}: {}", peer, e.getMessage());
                }
            }
        });
    }

   private volatile long lastUsed = System.currentTimeMillis();

public void queueMessage(Message message) {
    lastUsed = System.currentTimeMillis();
    this.queue.offer(message);
}

public boolean isIdle(long cutoffMillis) {
    return System.currentTimeMillis() - lastUsed > cutoffMillis;
}

    public void shutdown() {
        queue.clear();
        executor.shutdownNow();
    }
}
