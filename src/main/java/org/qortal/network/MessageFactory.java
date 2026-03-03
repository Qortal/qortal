package org.qortal.network;

import org.qortal.network.message.Message;
import org.qortal.network.message.MessageException;

/**
 * Functional interface for lazy creation of network messages.
 * 
 * <p>This interface allows messages to be created on-demand rather than immediately,
 * which is particularly useful for large messages (like chunk data) that would otherwise
 * consume significant memory while queued for transmission.
 * 
 * <p>The factory is only called when the message is actually about to be sent,
 * minimizing memory usage for queued messages.
 * 
 * @since v5.0.3
 * @author Ice
 */
@FunctionalInterface
public interface MessageFactory {
    /**
     * Creates a message instance.
     * 
     * @return the created message, or null if creation should be skipped
     * @throws MessageException if message creation fails
     */
    Message createMessage() throws MessageException;
}

