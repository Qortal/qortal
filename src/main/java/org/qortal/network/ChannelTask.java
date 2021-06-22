package org.qortal.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

class ChannelTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(ChannelTask.class);
    private final SelectionKey selectionKey;

    ChannelTask(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    @Override
    public String getName() {
        SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        Peer peer = Network.getInstance().getPeerFromChannel(socketChannel);
        return "ChannelTask::" + peer;
    }

    @Override
    public void perform() throws InterruptedException {
        try {
            LOGGER.trace("Thread {} has pending channel: {}, with ops {}",
                    Thread.currentThread().getId(), selectionKey.channel(), selectionKey.readyOps());

            // process pending channel task
            if (selectionKey.isReadable()) {
                connectionRead((SocketChannel) selectionKey.channel());
            } else if (selectionKey.isAcceptable()) {
                Network.getInstance().acceptConnection((ServerSocketChannel) selectionKey.channel());
            }

            LOGGER.trace("Thread {} processed channel: {}",
                    Thread.currentThread().getId(), selectionKey.channel());
        } catch (CancelledKeyException e) {
            LOGGER.trace("Thread {} encountered cancelled channel: {}",
                    Thread.currentThread().getId(), selectionKey.channel());
        }
    }

    private void connectionRead(SocketChannel socketChannel) {
        Peer peer = Network.getInstance().getPeerFromChannel(socketChannel);
        if (peer == null) {
            return;
        }

        try {
            peer.readChannel();
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("connection reset")) {
                peer.disconnect("Connection reset");
                return;
            }

            LOGGER.trace("[{}] Network thread {} encountered I/O error: {}", peer.getPeerConnectionId(),
                    Thread.currentThread().getId(), e.getMessage(), e);
            peer.disconnect("I/O error");
        }
    }

}