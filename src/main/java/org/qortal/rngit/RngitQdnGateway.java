package org.qortal.rngit;

import lombok.extern.slf4j.Slf4j;
import org.qortal.rngit.RngitPermissions.PermissionSet;
import org.qortal.rngit.RngitRepositories.Group;
import org.qortal.rngit.RngitRepositories.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves QDN-backed repositories: every registered Qortal name is a group, and
 * {@code <name>/<repo>} is the repository QDN holds under that name.
 * <p>
 * A repository is materialised into a local bare repository under the cache
 * directory by applying its bundles in order and then setting refs and HEAD
 * exactly as the descriptor states. The cache is refreshed when the
 * descriptor's latest transaction changes.
 * <p>
 * Everyone may read: QDN data is public. A name is writable only on a node
 * whose publisher account owns it ({@link RngitQdnPublisher}); there its group
 * rules come from the node's {@code [access]} config and its repositories'
 * rules from their descriptors. Elsewhere it is read-only.
 * <p>
 * The per-repository {@linkplain #lock lock} is held while the cache is
 * refreshed, written by a push, or published, so a refresh can never roll back
 * a push that was acknowledged but not yet published.
 */
@Slf4j
final class RngitQdnGateway {

    static final long READ_TIMEOUT_MS = 60_000;
    /** Applied bundle identifiers, one per line, kept inside the cached bare repository. */
    static final String APPLIED_FILE = "rngit-qdn-applied";

    private final Path cacheRoot;
    private final Map<String, Cached> repositories = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile RngitRepositories registry;
    private volatile RngitQdnPublisher publisher;
    private volatile RngitQdnStaging staging;

    private static final class Cached {
        final byte[] descriptorSignature;
        final RngitQdn.Descriptor descriptor;
        final Path path;

        Cached(byte[] descriptorSignature, RngitQdn.Descriptor descriptor, Path path) {
            this.descriptorSignature = descriptorSignature;
            this.descriptor = descriptor;
            this.path = path;
        }
    }

    RngitQdnGateway(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    void attach(RngitRepositories registry) {
        this.registry = registry;
    }

    void setPublisher(RngitQdnPublisher publisher) {
        this.publisher = publisher;
    }

    RngitQdnPublisher getPublisher() {
        return publisher;
    }

    void setStaging(RngitQdnStaging staging) {
        this.staging = staging;
    }

    RngitQdnStaging getStaging() {
        return staging;
    }

    static String key(String name, String repositoryName) {
        return name + "/" + repositoryName;
    }

    /** The lock guarding one cached repository. */
    Object lock(String name, String repositoryName) {
        return locks.computeIfAbsent(key(name, repositoryName), k -> new Object());
    }

    Path cachePath(String name, String repositoryName) {
        return cacheRoot.resolve(name).resolve(repositoryName);
    }

    boolean isWritableHere(String name) {
        RngitQdnPublisher p = this.publisher;
        return p != null && p.ownsName(name);
    }

    private PermissionSet groupPermissions(String name) {
        RngitRepositories r = this.registry;
        return r == null ? RngitRepositories.readAllPermissions() : r.qdnGroupPermissions(name, isWritableHere(name));
    }

    /**
     * A repository's rules come from its descriptor on every node; whether an
     * allowed write is published here or staged for the owner depends on
     * {@link #isWritableHere}.
     */
    private PermissionSet repositoryPermissions(String name, RngitQdn.Descriptor descriptor) {
        RngitRepositories r = this.registry;
        return r == null ? RngitRepositories.readAllPermissions() : r.qdnRepositoryPermissions(descriptor.allowed, true);
    }

    /** The group for a registered name, or null. */
    Group group(String name) {
        if (name == null || name.isEmpty() || name.contains("/")) return null;
        if (RngitQdn.nameOwner(name) == null) return null;
        return RngitRepositories.qdnGroup(name, cacheRoot.resolve(name), groupPermissions(name));
    }

    /** The materialised repository, or null if QDN holds none under that name. */
    Repository repository(String name, String repositoryName) {
        Cached cached = refresh(name, repositoryName);
        return cached == null ? null
                : RngitRepositories.qdnRepository(repositoryName, name, cached.path, repositoryPermissions(name, cached.descriptor));
    }

    /** The descriptor the cache currently reflects, or null. */
    RngitQdn.Descriptor descriptor(String name, String repositoryName) {
        Cached cached = refresh(name, repositoryName);
        return cached == null ? null : cached.descriptor;
    }

    /** The descriptor last materialised here, without refreshing from QDN, or null. */
    RngitQdn.Descriptor cachedDescriptor(String name, String repositoryName) {
        Cached cached = repositories.get(key(name, repositoryName));
        return cached == null ? null : cached.descriptor;
    }

    /**
     * The cache reflects this descriptor and is still on disk. Deleting the cache
     * directory while the node runs (runbook step 7) must rebuild it, not leave
     * every request failing on a repository that is gone.
     */
    private static boolean isCurrent(Cached cached, byte[] signature) {
        return cached != null && Arrays.equals(cached.descriptorSignature, signature)
                && Files.isDirectory(cached.path);
    }

    private Cached refresh(String name, String repositoryName) {
        if (group(name) == null || !RngitQdn.isValidRepositoryName(repositoryName)) return null;

        byte[] signature = RngitQdn.latestSignature(name, repositoryName);
        if (signature == null) return null;

        String key = key(name, repositoryName);
        Cached cached = repositories.get(key);
        if (isCurrent(cached, signature)) return cached;

        synchronized (lock(name, repositoryName)) {
            cached = repositories.get(key);
            if (isCurrent(cached, signature)) return cached;
            try {
                RngitQdn.Descriptor descriptor = materialise(name, repositoryName);
                cached = new Cached(signature, descriptor, cachePath(name, repositoryName));
                repositories.put(key, cached);
                return cached;
            } catch (Exception e) {
                log.warn("Could not materialise QDN repository {}: {}", key, e.getMessage());
                return null;
            }
        }
    }

    /**
     * Records a descriptor this node just published, so the cache, which already
     * holds its state, is not rebuilt from QDN. Call while holding the lock.
     */
    void markPublished(String name, String repositoryName, byte[] descriptorSignature, RngitQdn.Descriptor descriptor,
                       String publishedBundle) throws IOException {
        Path path = cachePath(name, repositoryName);
        if (publishedBundle != null) {
            Files.writeString(path.resolve(APPLIED_FILE), publishedBundle + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        repositories.put(key(name, repositoryName), new Cached(descriptorSignature, descriptor, path));
    }

    private RngitQdn.Descriptor materialise(String name, String repositoryName) throws Exception {
        Path descriptorDir = RngitQdn.readResource(name, repositoryName, READ_TIMEOUT_MS);
        RngitQdn.Descriptor descriptor = RngitQdn.parseDescriptor(descriptorDir);

        Path path = cachePath(name, repositoryName);
        if (!RngitGit.isGitRepository(path)) {
            Files.createDirectories(path);
            RngitGit.initBare(path);
        }

        Path appliedFile = path.resolve(APPLIED_FILE);
        Set<String> applied = new HashSet<>(Files.exists(appliedFile)
                ? Files.readAllLines(appliedFile, StandardCharsets.UTF_8) : List.of());

        for (String bundleId : descriptor.bundles) {
            if (applied.contains(bundleId)) continue;
            if (!bundleId.startsWith(repositoryName + "~b~")) {
                throw new IOException("Bundle " + bundleId + " does not belong to " + repositoryName);
            }
            Path bundleDir = RngitQdn.readResource(name, bundleId, READ_TIMEOUT_MS);
            RngitGit.Result result = RngitGit.applyBundleAllRefs(path, RngitQdn.fileIn(bundleDir, RngitQdn.BUNDLE_FILE));
            if (!result.ok) throw new IOException("Could not apply " + bundleId + ": " + result.message);
            Files.writeString(appliedFile, bundleId + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            applied.add(bundleId);
        }

        // The descriptor is authoritative for refs: bundles may carry older tips
        for (String ref : RngitGit.refs(path).keySet()) {
            if (!descriptor.refs.containsKey(ref)) RngitGit.deleteRef(path, ref);
        }
        for (Map.Entry<String, String> ref : descriptor.refs.entrySet()) {
            if (RngitRefs.sanRef(ref.getKey()) == null || RngitRefs.sanSha(ref.getValue()) == null) {
                throw new IOException("Invalid ref in descriptor: " + ref.getKey());
            }
            if (!RngitGit.hasObject(path, ref.getValue())) {
                throw new IOException("Descriptor ref " + ref.getKey() + " points at a missing object");
            }
            RngitGit.Result result = RngitGit.updateRef(path, ref.getKey(), ref.getValue());
            if (!result.ok) throw new IOException("Could not set " + ref.getKey() + ": " + result.message);
        }
        RngitGit.updateHead(path, descriptor.head);

        log.info("Materialised QDN repository {}/{} ({} bundles, {} refs)", name, repositoryName,
                descriptor.bundles.size(), descriptor.refs.size());
        return descriptor;
    }
}
