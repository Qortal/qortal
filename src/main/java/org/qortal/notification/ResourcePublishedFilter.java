package org.qortal.notification;

import org.qortal.utils.ListUtils;

import java.util.List;

/**
 * Typed filter for {@code RESOURCE_PUBLISHED} subscriptions.
 * <p>
 * Mirrors the parameters of
 * {@code HSQLDBArbitraryRepository.searchArbitraryResources()} so a client can
 * subscribe with the same richness it would use when searching QDN.
 *
 * <pre>
 * {
 *   "event": "RESOURCE_PUBLISHED",
 *   "resourceFilter": {
 *     "service": "BLOG",
 *     "query": "qortal",
 *     "names": ["alice", "bob"],
 *     "identifier": "post",
 *     "title": "hello",
 *     "description": "world",
 *     "keywords": ["news", "update"],
 *     "prefix": false,
 *     "defaultResource": false,
 *     "followedOnly": true,
 *     "excludeBlocked": true,
 *     "after": 1700000000000,
 *     "before": 1800000000000
 *   }
 * }
 * </pre>
 *
 * All fields are optional — absent means "no restriction on this dimension".
 */
public class ResourcePublishedFilter {

    /** Exact service match (e.g. "BLOG", "APP"). Null = any service. */
    public String service;

    /**
     * General fuzzy query — matched against name, identifier, title, description
     * (or name only when {@code defaultResource=true}).
     */
    public String query;

    /** Fuzzy/prefix match on the resource identifier. */
    public String identifier;

    /** Exact (case-insensitive) name match — resource name must be one of these. */
    public List<String> names;

    /** Fuzzy/prefix match on metadata title. */
    public String title;

    /** Fuzzy/prefix match on metadata description. */
    public String description;

    /**
     * OR-style keyword search in metadata description.
     * At least one keyword must appear.
     */
    public List<String> keywords;

    /**
     * When {@code true}, all string comparisons use prefix matching instead of
     * substring matching (i.e. value must <em>start</em> with the filter string).
     * When {@code false} or absent, substring (contains) matching is used.
     */
    public Boolean prefix;

    /**
     * When {@code true}, only the default resource (identifier = "default") is
     * matched and {@code query} is applied to the name only.
     * Defaults to {@code false}.
     */
    public boolean defaultResource = false;

    /**
     * When {@code true}, only resources whose creator name is in the local
     * "followed names" list are matched.
     */
    public Boolean followedOnly;

    /**
     * When {@code true}, resources whose creator name is in the local
     * "blocked names" list are excluded.
     */
    public Boolean excludeBlocked;

    /** Resource {@code created_when} must be after this epoch-ms timestamp. */
    public Long after;

    /** Resource {@code created_when} must be before this epoch-ms timestamp. */
    public Long before;

    // -------------------------------------------------------------------------
    // Metadata dependency check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if any active filter field requires metadata
     * (title, description, tags, category) that is not available from the
     * raw {@code ArbitraryTransactionData} alone.
     * <p>
     * When this returns {@code false}, the subscription can be evaluated
     * immediately on transaction arrival without waiting for the cache pipeline.
     */
    public boolean requiresMetadata() {
        return (query != null && !query.isEmpty())
            || (title != null && !title.isEmpty())
            || (description != null && !description.isEmpty())
            || (keywords != null && !keywords.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Matching
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the supplied {@link ResourcePublishedEvent} satisfies
     * every constraint defined in this filter.
     */
    public boolean matches(ResourcePublishedEvent ev) {

        // --- service (exact) ---
        if (service != null && !service.isEmpty()) {
            if (!service.equalsIgnoreCase(ev.service)) {
                return false;
            }
        }

        // --- defaultResource: only match identifier "default" (or null) ---
        if (defaultResource) {
            if (ev.identifier != null && !ev.identifier.isEmpty() && !ev.identifier.equalsIgnoreCase("default")) {
                return false;
            }
        }

        // --- query: broad fuzzy search across name, identifier, title, description ---
        if (query != null && !query.isEmpty()) {
            String lq = query.toLowerCase();
            if (defaultResource) {
                // name only when defaultResource
                if (!stringMatches(ev.name, lq)) return false;
            } else {
                if (!stringMatches(ev.name, lq)
                        && !stringMatches(ev.identifier, lq)
                        && !stringMatches(ev.title, lq)
                        && !stringMatches(ev.description, lq)) {
                    return false;
                }
            }
        }

        // --- identifier fuzzy/prefix ---
        if (identifier != null && !identifier.isEmpty()) {
            if (!stringMatches(ev.identifier, identifier.toLowerCase())) return false;
        }

        // --- names (exact, case-insensitive — resource name must be one of these) ---
        if (names != null && !names.isEmpty()) {
            String lName = ev.name != null ? ev.name.toLowerCase() : "";
            boolean found = false;
            for (String n : names) {
                if (n != null && n.toLowerCase().equals(lName)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }

        // --- title fuzzy/prefix ---
        if (title != null && !title.isEmpty()) {
            if (!stringMatches(ev.title, title.toLowerCase())) return false;
        }

        // --- description fuzzy/prefix ---
        if (description != null && !description.isEmpty()) {
            if (!stringMatches(ev.description, description.toLowerCase())) return false;
        }

        // --- keywords (OR, in description) ---
        if (keywords != null && !keywords.isEmpty()) {
            boolean anyKeyword = false;
            String lDesc = ev.description != null ? ev.description.toLowerCase() : "";
            for (String kw : keywords) {
                if (lDesc.contains(kw.trim().toLowerCase())) {
                    anyKeyword = true;
                    break;
                }
            }
            if (!anyKeyword) return false;
        }

        // --- timestamp range ---
        if (after != null && ev.created != null && ev.created <= after) return false;
        if (before != null && ev.created != null && ev.created >= before) return false;

        // --- followedOnly ---
        if (Boolean.TRUE.equals(followedOnly)) {
            List<String> followed = ListUtils.followedNames();
            if (followed == null || followed.isEmpty()) return false;
            String lName = ev.name != null ? ev.name.toLowerCase() : "";
            boolean isFollowed = followed.stream().anyMatch(f -> f.toLowerCase().equals(lName));
            if (!isFollowed) return false;
        }

        // --- excludeBlocked ---
        if (Boolean.TRUE.equals(excludeBlocked)) {
            List<String> blocked = ListUtils.blockedNames();
            if (blocked != null && !blocked.isEmpty()) {
                String lName = ev.name != null ? ev.name.toLowerCase() : "";
                boolean isBlocked = blocked.stream().anyMatch(b -> b.toLowerCase().equals(lName));
                if (isBlocked) return false;
            }
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Tests whether {@code field} contains (or starts with, if {@code prefix})
     * the lower-cased {@code pattern}.
     * Returns {@code false} if {@code field} is null.
     */
    private boolean stringMatches(String field, String pattern) {
        if (field == null) return false;
        String lField = field.toLowerCase();
        return Boolean.TRUE.equals(prefix) ? lField.startsWith(pattern) : lField.contains(pattern);
    }
}
