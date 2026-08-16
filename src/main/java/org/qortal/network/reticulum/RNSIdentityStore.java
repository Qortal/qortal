package org.qortal.network.reticulum;

import io.reticulum.identity.Identity;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * This node's long-term Reticulum identity, persisted under {@code <storage>/identities/<appName>}.
 * <p>
 * The identity is the node's address on the mesh: both destination hashes derive from it, so a node
 * that loses it becomes a different node to every peer that knew it — their persisted peer hashes
 * (see {@link KnownPeerStore}) stop matching and they fall back to waiting for announces. Hence it
 * is generated once and written straight back to disk.
 * <p>
 * A write failure is logged and swallowed rather than thrown: the node runs perfectly well on an
 * in-memory identity for this process, it just comes back with a new address next restart, which is
 * strictly better than refusing to start the mesh.
 * <p>
 * The file is Reticulum's own private-key format, so an identity generated out of band with the
 * {@code rnid} utility can be dropped in at this path and will be picked up as-is.
 */
@Slf4j
final class RNSIdentityStore {

    private static final String IDENTITIES_DIR = "identities";

    private RNSIdentityStore() {
    }

    /**
     * Load this node's identity, creating and persisting one on first run.
     *
     * @param storagePath Reticulum's storage directory
     * @param appName     identity filename — the app name, so testnet and mainnet keep separate keys
     * @return the loaded or newly created identity; never null
     */
    static Identity loadOrCreate(Path storagePath, String appName) {
        Path identityPath = storagePath.resolve(IDENTITIES_DIR).resolve(appName);

        if (Files.isReadable(identityPath)) {
            Identity identity = Identity.fromFile(identityPath);
            log.info("server identity loaded from file {}", identityPath);
            return identity;
        }

        Identity identity = new Identity();
        log.info("APP_NAME: {}, storage path: {}", appName, identityPath);
        log.info("new server identity created dynamically.");
        // save it back to file by default for next start (possibly add setting to override)
        try {
            // The directory is created here rather than at Reticulum construction: it is needed
            // only on this branch, and creating it next to the write keeps the whole first-run
            // path — and its one failure mode — in one place.
            Files.createDirectories(identityPath.getParent());
            Files.write(identityPath, identity.getPrivateKey(), CREATE, WRITE);
            log.info("serverIdentity written back to file");
        } catch (IOException e) {
            log.error("Error while saving serverIdentity to {}", identityPath, e);
        }
        return identity;
    }
}
