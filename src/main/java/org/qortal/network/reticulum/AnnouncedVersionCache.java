package org.qortal.network.reticulum;

import io.reticulum.identity.Identity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * Version strings learned from announces, keyed by identity hash.
 * <p>
 * An incoming peer has no announce at construction time, so it cannot know its own remote's
 * version; it learns the identity only once the initiator calls {@code link.identify()}. This cache
 * bridges the two: {@link RNSAnnounceHandler} writes every announced version here, and the inbound
 * path reads it back when the identity finally resolves.
 * <p>
 * Bounded LRU (512 entries): every node on the mesh announces every ~30s, so an unbounded map would
 * grow with mesh size for the process lifetime. Display-only data — losing an eldest entry costs a
 * version string in {@code /peers/reticulum}, nothing more.
 */
final class AnnouncedVersionCache {

    private static final int MAX_ENTRIES = 512;

    private final Map<String, String> versions = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > MAX_ENTRIES;
                }
            });

    /** Record a peer's announced version. Null identity, hash or version are ignored. */
    void put(Identity identity, String version) {
        if (identity == null || identity.getHash() == null || version == null) return;
        versions.put(encodeHexString(identity.getHash()), version);
    }

    /** Announced version for a remote identity, or null if we haven't seen its announce yet. */
    String get(Identity identity) {
        if (identity == null || identity.getHash() == null) return null;
        return versions.get(encodeHexString(identity.getHash()));
    }
}
