package org.qortal.network.reticulum;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Persisted destination hashes of peers, one store per aspect (BASE, DATA).
 * <p>
 * A restarted node can call {@code requestPath()} for these immediately rather than waiting up to
 * 15 minutes for a natural announce. Two sets are kept deliberately:
 * <ul>
 *   <li><b>confirmed</b> — added only when a peer's buffer is confirmed ACTIVE this session (see
 *       {@code ReticulumPeer.createPeerBuffer()} → {@code RNS.confirmPeerHash()}), so transient or
 *       failed-only peers never accumulate on disk.</li>
 *   <li><b>loaded</b> — read from disk at startup and possibly stale. Used for path recovery
 *       alongside the confirmed set, but never written back directly: saving the confirmed set
 *       instead is what lets stale entries age out over restarts.</li>
 * </ul>
 * The one exception is a session so short that nothing was confirmed, where the loaded set is
 * written back unchanged rather than truncating the file to nothing.
 */
@Slf4j
final class KnownPeerStore {

    private final String label;
    private final Path file;
    private final Set<String> confirmed = ConcurrentHashMap.newKeySet();
    private final Set<String> loaded = ConcurrentHashMap.newKeySet();

    /**
     * @param storagePath Reticulum's storage directory
     * @param fileName    file within it, e.g. "known_peer_hashes.txt"
     * @param label       used in log lines to tell the BASE and DATA stores apart
     */
    KnownPeerStore(Path storagePath, String fileName, String label) {
        this.file = storagePath.resolve(fileName);
        this.label = label;
    }

    /** Read persisted hashes. Missing or unreadable files are not an error — we simply start empty. */
    void load() {
        try {
            if (!Files.isReadable(file)) return;
            List<String> lines = Files.readAllLines(file, UTF_8);
            int count = 0;
            for (String line : lines) {
                String hex = line.trim();
                if (!hex.isEmpty()) {
                    loaded.add(hex);
                    count++;
                }
            }
            if (count > 0) {
                log.info("Loaded {} known {} peer hashes from {}", count, label, file);
            }
        } catch (IOException e) {
            log.warn("Failed to load known {} peer hashes: {}", label, e.getMessage());
        }
    }

    /**
     * Write the confirmed-active hashes, falling back to the loaded ones only when nothing was
     * confirmed this session (e.g. shutdown before any peer became ACTIVE) — otherwise a short run
     * would erase the file and cost the next start its fast reconnect.
     */
    void save() {
        try {
            Set<String> toSave = confirmed.isEmpty() ? loaded : confirmed;
            Files.write(file, toSave, UTF_8);
            log.debug("Saved {} known {} peer hashes to {}", toSave.size(), label, file);
        } catch (IOException e) {
            log.warn("Failed to save known {} peer hashes: {}", label, e.getMessage());
        }
    }

    /**
     * Record a peer whose link is confirmed ACTIVE, persisting immediately when it is new.
     *
     * @return true when this hash had not been confirmed before
     */
    boolean confirm(String hashHex) {
        boolean isNew = confirmed.add(hashHex);
        if (isNew) {
            save();
            log.debug("Confirmed ACTIVE {} peer hash {}", label, hashHex);
        }
        return isNew;
    }

    /** Every hash worth attempting a reconnect to: confirmed this session plus loaded from disk. */
    Set<String> reconnectTargets() {
        Set<String> targets = new HashSet<>(confirmed);
        targets.addAll(loaded);
        return targets;
    }

    /**
     * Whether the previous session left us any hashes. Drives the announce-timer seeding in
     * {@code start()}: with hashes on disk the first path requests fire at ~15 s, otherwise the
     * full 30 s window applies.
     */
    boolean hasLoadedHashes() {
        return !loaded.isEmpty();
    }
}
