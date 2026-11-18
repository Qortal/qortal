package org.qortal.rngit;

import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.identity.Identity;
import io.reticulum.identity.IdentityKnownDestination;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.link.RequestReceipt;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.qortal.rngit.RngitProtocol.APP_NAME;
import static org.qortal.rngit.RngitProtocol.ASPECT;

/**
 * Core as a client of another rngit node: a link to its
 * {@code git.repositories} destination, identified with a client identity, and
 * synchronous requests over it ({@code client.py} {@code connect_server} and
 * {@code send_request}).
 * <p>
 * Requests block their caller, so this must not run on an interface I/O thread;
 * request handlers run on the library's request worker threads.
 */
@Slf4j
public final class RngitClient implements AutoCloseable {

    static final long PATH_TIMEOUT_MS = 15_000;
    static final long LINK_TIMEOUT_MS = 15_000;

    /** A response and the metadata of a file response, if any. */
    public static final class Reply {
        public final byte[] response;
        public final Object metadata;

        Reply(byte[] response, Object metadata) {
            this.response = response;
            this.metadata = metadata;
        }
    }

    private final Link link;

    private RngitClient(Link link) {
        this.link = link;
    }

    /** Resolves a path to the destination, establishes a link and identifies on it. */
    public static RngitClient connect(byte[] destinationHash, Identity clientIdentity) throws IOException {
        String hex = encodeHexString(destinationHash);
        if (!Transport.getInstance().awaitPath(destinationHash, PATH_TIMEOUT_MS, null)) {
            throw new IOException("Could not resolve path to <" + hex + ">");
        }

        Identity remoteIdentity = IdentityKnownDestination.recall(destinationHash);
        if (remoteIdentity == null) {
            throw new IOException("Could not recall remote identity for <" + hex + ">. Is the server announcing?");
        }

        Destination destination = new Destination(remoteIdentity, Direction.OUT, DestinationType.SINGLE, APP_NAME, ASPECT);
        Link link = new Link(destination);

        long deadline = System.currentTimeMillis() + LINK_TIMEOUT_MS;
        while (link.getStatus() != LinkStatus.ACTIVE) {
            if (link.getStatus() == LinkStatus.CLOSED || System.currentTimeMillis() > deadline) {
                link.teardown();
                throw new IOException("Could not establish link to <" + hex + ">");
            }
            sleep(100);
        }

        link.identify(clientIdentity);
        return new RngitClient(link);
    }

    /**
     * Sends a request and waits for its response.
     *
     * @throws IOException if the request fails or times out
     */
    public Reply request(String path, Object data, long timeoutMs) throws IOException {
        CompletableFuture<RequestReceipt> answer = new CompletableFuture<>();
        RequestReceipt receipt = link.request(path, data,
                answer::complete,
                failed -> answer.completeExceptionally(new IOException("Request " + path + " failed")),
                null, timeoutMs);
        if (receipt == null) {
            throw new IOException("Request " + path + " could not be sent");
        }

        try {
            RequestReceipt done = answer.get(timeoutMs + 5_000, TimeUnit.MILLISECONDS);
            return new Reply(done.getResponse(), done.getMetadata());
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Request " + path + " timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for " + path, e);
        }
    }

    @Override
    public void close() {
        link.teardown();
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** {@code rns://<hash>/<group>/<repo>} split into its parts, or null if malformed. */
    static String[] parseRnsUrl(String url) {
        if (url == null || !url.toLowerCase(java.util.Locale.ROOT).startsWith("rns://")) return null;
        String[] parts = url.substring(6).split("/", 3);
        if (parts.length != 3 || !RngitPermissions.isIdentityHashHex(parts[0]) || parts[1].isEmpty() || parts[2].isEmpty()) {
            return null;
        }
        return parts;
    }

    static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
