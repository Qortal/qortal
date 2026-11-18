package org.qortal.rngit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Push flow 2: writes to a QDN repository on a node that cannot publish for
 * its name are staged, for the name's owner to publish from Hub.
 * <p>
 * A staged change is checked against the repository's current state in a
 * scratch repository that borrows the cache's objects, so a bundle must apply
 * and a non-fast-forward update must be forced, exactly as for a direct push.
 * It is stored under {@code <rngit>/qdn-staging/<name>/<repo>/<id>/}. The pusher
 * is told it was staged; nothing changes on QDN until the owner publishes.
 * <p>
 * {@link #prepare} computes, against the descriptor current at that moment, the
 * bundle and descriptor that would publish the change, as resources ready for
 * Hub's {@code PUBLISH_MULTIPLE_QDN_RESOURCES}. Once the published descriptor
 * reflects a staged change it is dropped from the list.
 */
@Slf4j
final class RngitQdnStaging {

    static final int MAX_PENDING = 32;
    static final long EXPIRY_MILLIS = 14L * 24 * 3600 * 1000;
    static final String CHANGE_FILE = "change.json";
    static final String BUNDLE_FILE = "push.bundle";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One staged change. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class Change {
        public long id;
        /** {@code bundle}, {@code update_ref} or {@code delete}. */
        public String kind;
        public String pusher;
        public long created;
        public String ref;
        /** The ref's SHA after the change; null for a delete. */
        public String sha;
        /** The ref's SHA in the descriptor when staged; null if it did not exist. */
        public String base;
        public boolean force;
        /** For a bundle: the ref inside the bundle that becomes {@link #ref}. */
        public String localRef;
    }

    /** Raised when a change no longer applies to the current state. */
    static final class ConflictException extends Exception {
        ConflictException(String message) {
            super(message);
        }
    }

    private final Path root;
    private final RngitQdnGateway gateway;

    RngitQdnStaging(Path root, RngitQdnGateway gateway) {
        this.root = root;
        this.gateway = gateway;
    }

    private Path dir(String name, String repositoryName) {
        return root.resolve(name).resolve(repositoryName);
    }

    // ------------------------------------------------------------------
    // Scratch repository

    /** A bare repository borrowing the cache's objects, with refs set as the descriptor states. */
    private static Path scratch(Path cache, Map<String, String> refs) throws Exception {
        Path scratch = Files.createTempDirectory("rngit-staging-");
        RngitGit.initBare(scratch);
        Path alternates = scratch.resolve("objects").resolve("info").resolve("alternates");
        Files.createDirectories(alternates.getParent());
        Files.writeString(alternates, cache.resolve("objects").toAbsolutePath() + "\n", StandardCharsets.UTF_8);
        for (Map.Entry<String, String> ref : refs.entrySet()) {
            RngitGit.Result result = RngitGit.updateRef(scratch, ref.getKey(), ref.getValue());
            if (!result.ok) throw new IOException("Could not set " + ref.getKey() + ": " + result.message);
        }
        return scratch;
    }

    /**
     * Applies a change to a scratch repository: a staged bundle is fetched, a
     * ref update or delete applied, with the same fast-forward rule as a push.
     */
    private static void apply(Path scratch, Change change, Path bundle) throws Exception {
        String current = RngitGit.resolveRef(scratch, change.ref);
        switch (change.kind) {
            case "bundle": {
                RngitGit.Result result = RngitGit.applyBundle(scratch, bundle, change.localRef, change.ref, change.force);
                if (!result.ok) throw new ConflictException(result.message);
                break;
            }
            case "update_ref": {
                if (!RngitGit.hasObject(scratch, change.sha)) throw new ConflictException("Object " + change.sha + " does not exist");
                if (current != null && !current.equals(change.sha) && !change.force) {
                    throw new ConflictException("Ref " + change.ref + " already exists at different SHA (force required)");
                }
                RngitGit.Result result = RngitGit.updateRef(scratch, change.ref, change.sha);
                if (!result.ok) throw new ConflictException(result.message);
                break;
            }
            case "delete": {
                RngitGit.Result result = RngitGit.deleteRef(scratch, change.ref);
                if (!result.ok) throw new ConflictException(result.message);
                break;
            }
            default:
                throw new IOException("Unknown change kind " + change.kind);
        }
    }

    // ------------------------------------------------------------------
    // Staging

    /**
     * Stages a change after checking it applies to the current state.
     *
     * @return the stored change
     */
    Change stage(String name, String repositoryName, Change change, byte[] bundleData) throws Exception {
        synchronized (gateway.lock(name, repositoryName)) {
            RngitQdn.Descriptor descriptor = gateway.descriptor(name, repositoryName);
            if (descriptor == null) throw new IOException("No descriptor for " + name + "/" + repositoryName);
            List<Change> pending = list(name, repositoryName);
            if (pending.size() >= MAX_PENDING) throw new ConflictException("Too many staged changes for this repository");

            Path base = dir(name, repositoryName);
            Files.createDirectories(base);
            Path incoming = Files.createTempFile(base, "incoming-", ".bundle");
            Path scratch = null;
            try {
                if (bundleData != null) Files.write(incoming, bundleData);
                scratch = scratch(gateway.cachePath(name, repositoryName), descriptor.refs);
                apply(scratch, change, incoming);

                change.id = pending.stream().mapToLong(c -> c.id).max().orElse(nextId(base) - 1) + 1;
                change.created = System.currentTimeMillis();
                change.base = descriptor.refs.get(change.ref);
                change.sha = "delete".equals(change.kind) ? null : RngitGit.resolveRef(scratch, change.ref);

                Path changeDir = base.resolve(Long.toString(change.id));
                Files.createDirectories(changeDir);
                if (bundleData != null) Files.move(incoming, changeDir.resolve(BUNDLE_FILE));
                JSON.writerWithDefaultPrettyPrinter().writeValue(changeDir.resolve(CHANGE_FILE).toFile(), change);
                log.info("Staged change #{} to {}/{} by {}: {} {}", change.id, name, repositoryName, change.pusher,
                        change.kind, change.ref);
                return change;
            } finally {
                Files.deleteIfExists(incoming);
                if (scratch != null) deleteTree(scratch);
            }
        }
    }

    /** Ids keep counting even after earlier changes were published or expired. */
    private static long nextId(Path base) throws IOException {
        long max = 0;
        try (Stream<Path> entries = Files.list(base)) {
            for (Path p : (Iterable<Path>) entries::iterator) {
                String n = p.getFileName().toString();
                if (n.chars().allMatch(Character::isDigit) && !n.isEmpty()) max = Math.max(max, Long.parseLong(n));
            }
        }
        return max + 1;
    }

    /**
     * The staged changes still to publish, oldest first. Changes the current
     * descriptor already reflects, and expired ones, are removed.
     */
    List<Change> list(String name, String repositoryName) throws IOException {
        Path base = dir(name, repositoryName);
        List<Change> out = new ArrayList<>();
        if (!Files.isDirectory(base)) return out;

        RngitQdn.Descriptor descriptor = gateway.descriptor(name, repositoryName);
        long now = System.currentTimeMillis();
        try (Stream<Path> entries = Files.list(base)) {
            for (Path changeDir : (Iterable<Path>) entries::iterator) {
                Path file = changeDir.resolve(CHANGE_FILE);
                if (!Files.isRegularFile(file)) continue;
                Change change = JSON.readValue(file.toFile(), Change.class);
                boolean published = descriptor != null && java.util.Objects.equals(descriptor.refs.get(change.ref), change.sha);
                if (published || now - change.created > EXPIRY_MILLIS) {
                    log.info("Dropping staged change #{} to {}/{}: {}", change.id, name, repositoryName,
                            published ? "published" : "expired");
                    deleteTree(changeDir);
                    continue;
                }
                out.add(change);
            }
        }
        out.sort(Comparator.comparingLong(c -> c.id));
        return out;
    }

    /** Whether a staged change still applies to the current state. */
    boolean applies(String name, String repositoryName, Change change) {
        try {
            prepareResources(name, repositoryName, change);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The resources that publish a staged change: the bundle with the objects
     * the current descriptor does not reach (if any), and the new descriptor.
     *
     * @throws ConflictException if the change no longer applies, e.g. it no longer fast-forwards
     */
    Map<String, Object> prepare(String name, String repositoryName, long id) throws Exception {
        Change change = null;
        for (Change c : list(name, repositoryName)) {
            if (c.id == id) change = c;
        }
        if (change == null) return null;
        return prepareResources(name, repositoryName, change);
    }

    private Map<String, Object> prepareResources(String name, String repositoryName, Change change) throws Exception {
        synchronized (gateway.lock(name, repositoryName)) {
            RngitQdn.Descriptor descriptor = gateway.descriptor(name, repositoryName);
            if (descriptor == null) throw new IOException("No descriptor for " + name + "/" + repositoryName);
            Path changeDir = dir(name, repositoryName).resolve(Long.toString(change.id));
            Path scratch = scratch(gateway.cachePath(name, repositoryName), descriptor.refs);
            try {
                apply(scratch, change, changeDir.resolve(BUNDLE_FILE));
                Map<String, String> refs = RngitGit.refs(scratch);

                RngitQdn.Descriptor next = RngitQdnPublisher.copy(descriptor);
                next.refs = new LinkedHashMap<>(refs);

                List<Map<String, Object>> resources = new ArrayList<>();
                Path out = scratch.resolve("rngit-out.bundle");
                List<String> haves = new ArrayList<>(descriptor.refs.values());
                if (!refs.isEmpty() && RngitGit.createBundle(scratch, new ArrayList<>(refs.keySet()), haves, out)) {
                    String bundleId = RngitQdn.bundleIdentifier(repositoryName, RngitQdnPublisher.nextBundleNumber(descriptor));
                    next.bundles.add(bundleId);
                    resources.add(resource(name, bundleId, RngitQdn.BUNDLE_FILE, Files.readAllBytes(out), repositoryName + " bundle"));
                }
                resources.add(resource(name, repositoryName, RngitQdn.DESCRIPTOR_FILE, RngitQdn.descriptorBytes(next), repositoryName));

                Map<String, Object> prepared = new LinkedHashMap<>();
                prepared.put("id", change.id);
                prepared.put("action", "PUBLISH_MULTIPLE_QDN_RESOURCES");
                prepared.put("resources", resources);
                prepared.put("descriptor", next);
                return prepared;
            } finally {
                deleteTree(scratch);
            }
        }
    }

    private static Map<String, Object> resource(String name, String identifier, String filename, byte[] data, String title) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("name", name);
        resource.put("service", RngitQdn.SERVICE.name());
        resource.put("identifier", identifier);
        resource.put("filename", filename);
        resource.put("title", title);
        resource.put("data64", Base64.getEncoder().encodeToString(data));
        return resource;
    }

    /** Removes a staged change; false if there is none with that id. */
    boolean remove(String name, String repositoryName, long id) {
        Path changeDir = dir(name, repositoryName).resolve(Long.toString(id));
        if (!Files.isDirectory(changeDir)) return false;
        deleteTree(changeDir);
        log.info("Removed staged change #{} to {}/{}", id, name, repositoryName);
        return true;
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
