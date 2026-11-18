package org.qortal.rngit;

import lombok.extern.slf4j.Slf4j;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Publishes QDN-backed repositories with a node-held key (push flow 1).
 * <p>
 * The key is the account's private key, Base58, in {@code publisher_key} in the
 * rngit config directory. The node can publish only for names that account
 * currently owns; QDN refuses anything else.
 * <p>
 * A push lands in the cached repository and is acknowledged at once; then
 * {@link #schedule} queues a publish, which runs on its own thread. Pushes in
 * quick succession collapse into one publish: it compares the cache with the
 * last published descriptor, publishes one bundle holding every object the
 * descriptor's refs do not already reach, then the updated descriptor. Each
 * publish costs the account the network's fee per transaction.
 * <p>
 * Compaction: once a descriptor lists {@code compact_after} bundles, the next
 * publish that has new objects sends a full bundle instead of a thin one, and
 * the descriptor lists only that. The old bundles stay on QDN, unreferenced.
 */
@Slf4j
final class RngitQdnPublisher {

    static final String KEY_FILE = "publisher_key";
    static final int DEFAULT_COMPACT_AFTER = 16;

    private final PrivateKeyAccount account;
    private final String address;
    private final RngitQdnGateway gateway;
    private final ExecutorService executor;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** Bundles a descriptor may list before the next publish replaces them with one full bundle; 0 never. */
    private volatile int compactAfter = DEFAULT_COMPACT_AFTER;

    private RngitQdnPublisher(byte[] privateKey, RngitQdnGateway gateway) throws DataException {
        try (Repository repository = RepositoryManager.getRepository()) {
            this.account = new PrivateKeyAccount(repository, privateKey);
        }
        this.address = account.getAddress();
        this.gateway = gateway;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rngit-qdn-publisher");
            t.setDaemon(true);
            return t;
        });
    }

    /** The publisher for a config directory, or null when it has no key file. */
    static RngitQdnPublisher load(Path configDir, RngitQdnGateway gateway) throws IOException, DataException {
        Path keyFile = configDir.resolve(KEY_FILE);
        if (!Files.isRegularFile(keyFile)) return null;
        byte[] privateKey = Base58.decode(Files.readString(keyFile, StandardCharsets.UTF_8).strip());
        if (privateKey == null || privateKey.length != 32) {
            throw new IOException(keyFile + " does not hold a Base58 32-byte private key");
        }
        return new RngitQdnPublisher(privateKey, gateway);
    }

    String getAddress() {
        return address;
    }

    void setCompactAfter(int compactAfter) {
        this.compactAfter = Math.max(0, compactAfter);
    }

    boolean ownsName(String name) {
        return address.equals(RngitQdn.nameOwner(name));
    }

    void shutdown() {
        executor.shutdownNow();
    }

    /** Queues a publish of a repository's cached state; a no-op if one is already queued. */
    void schedule(String name, String repositoryName) {
        String key = RngitQdnGateway.key(name, repositoryName);
        if (!pending.add(key)) return;
        executor.submit(() -> {
            pending.remove(key);
            try {
                publish(name, repositoryName);
            } catch (Exception e) {
                log.error("Publishing {} to QDN failed; it is retried on the next change", key, e);
            }
        });
    }

    /** Publishes whatever the cache holds beyond the last published descriptor. */
    void publish(String name, String repositoryName) throws Exception {
        synchronized (gateway.lock(name, repositoryName)) {
            RngitQdn.Descriptor last = gateway.descriptor(name, repositoryName);
            if (last == null) throw new IOException("No descriptor for " + name + "/" + repositoryName);
            Path path = gateway.cachePath(name, repositoryName);

            Map<String, String> refs = RngitGit.refs(path);
            String head = headOf(path);
            if (refs.equals(last.refs) && java.util.Objects.equals(head, last.head)) return;

            RngitQdn.Descriptor next = copy(last);
            next.refs = new LinkedHashMap<>(refs);
            next.head = head;

            String bundleId = null;
            Path tmp = Files.createTempDirectory("rngit-qdn-publish-");
            try {
                Path bundle = tmp.resolve("bundle-dir").resolve(RngitQdn.BUNDLE_FILE);
                Files.createDirectories(bundle.getParent());
                List<String> haves = new ArrayList<>();
                for (String sha : last.refs.values()) {
                    if (RngitGit.hasObject(path, sha)) haves.add(sha);
                }
                if (!refs.isEmpty() && RngitGit.createBundle(path, new ArrayList<>(refs.keySet()), haves, bundle)) {
                    // Compaction: rather than one more thin bundle, a full one that
                    // replaces the whole chain. Same number of transactions; new
                    // nodes then need only this bundle.
                    boolean compact = compactAfter > 0 && last.bundles.size() >= compactAfter;
                    if (compact) {
                        Files.delete(bundle);
                        RngitGit.createBundle(path, new ArrayList<>(refs.keySet()), List.of(), bundle);
                    }
                    bundleId = RngitQdn.bundleIdentifier(repositoryName, nextBundleNumber(last));
                    RngitQdn.publish(account, name, bundleId, bundle.getParent(), repositoryName + " bundle");
                    if (compact) {
                        log.info("Compacting {}/{}: {} bundles replaced by {}", name, repositoryName, last.bundles.size(), bundleId);
                        next.bundles.clear();
                    }
                    next.bundles.add(bundleId);
                }

                Path descriptorDir = tmp.resolve("descriptor");
                RngitQdn.writeDescriptor(next, descriptorDir);
                byte[] signature = RngitQdn.publish(account, name, repositoryName, descriptorDir, repositoryName);
                gateway.markPublished(name, repositoryName, signature, next, bundleId);
                log.info("Published {}/{} to QDN: {} refs{}", name, repositoryName, refs.size(),
                        bundleId == null ? ", no new objects" : ", new bundle " + bundleId);
            } finally {
                deleteTree(tmp);
            }
        }
    }

    /**
     * A new, empty repository under a name this node can publish for, its creator
     * made admin as for a local repository. Published before returning, so the
     * repository exists as soon as the creator is told it does.
     */
    void create(String name, String repositoryName, String creatorHashHex) throws Exception {
        synchronized (gateway.lock(name, repositoryName)) {
            Path path = gateway.cachePath(name, repositoryName);
            if (!RngitGit.isGitRepository(path)) {
                Files.createDirectories(path);
                RngitGit.initBare(path);
            }
            RngitQdn.Descriptor descriptor = new RngitQdn.Descriptor();
            descriptor.repository = repositoryName;
            descriptor.head = "refs/heads/master";
            descriptor.allowed = RngitProtocol.REPO_CREATE_PERMS_TEMPLATE.replace("{IDENTITY_HASH}", creatorHashHex);

            Path tmp = Files.createTempDirectory("rngit-qdn-create-");
            try {
                RngitQdn.writeDescriptor(descriptor, tmp);
                byte[] signature = RngitQdn.publish(account, name, repositoryName, tmp, repositoryName);
                gateway.markPublished(name, repositoryName, signature, descriptor, null);
            } finally {
                deleteTree(tmp);
            }
        }
    }

    /** Replaces a repository's rules and publishes the descriptor before returning. */
    void setAllowed(String name, String repositoryName, String allowed) throws Exception {
        synchronized (gateway.lock(name, repositoryName)) {
            RngitQdn.Descriptor last = gateway.descriptor(name, repositoryName);
            if (last == null) throw new IOException("No descriptor for " + name + "/" + repositoryName);
            RngitQdn.Descriptor next = copy(last);
            next.allowed = allowed;

            Path tmp = Files.createTempDirectory("rngit-qdn-perms-");
            try {
                RngitQdn.writeDescriptor(next, tmp);
                byte[] signature = RngitQdn.publish(account, name, repositoryName, tmp, repositoryName);
                gateway.markPublished(name, repositoryName, signature, next, null);
            } finally {
                deleteTree(tmp);
            }
        }
    }

    private static String headOf(Path path) throws IOException {
        String head = Files.readString(path.resolve("HEAD"), StandardCharsets.UTF_8).strip();
        return head.startsWith("ref: ") ? head.substring(5).strip() : null;
    }

    static long nextBundleNumber(RngitQdn.Descriptor descriptor) {
        long max = 0;
        for (String id : descriptor.bundles) {
            try {
                max = Math.max(max, Long.parseLong(id.substring(id.lastIndexOf('~') + 1)));
            } catch (NumberFormatException e) {
                // not one of ours; ignore
            }
        }
        return max + 1;
    }

    static RngitQdn.Descriptor copy(RngitQdn.Descriptor d) {
        RngitQdn.Descriptor c = new RngitQdn.Descriptor();
        c.format = d.format;
        c.repository = d.repository;
        c.head = d.head;
        c.refs = new LinkedHashMap<>(d.refs);
        c.bundles = new ArrayList<>(d.bundles);
        c.allowed = d.allowed;
        c.type = d.type;
        c.upstream = d.upstream;
        c.description = d.description;
        return c;
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.debug("Could not delete {}", p, e);
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean up {}", dir, e);
        }
    }
}
