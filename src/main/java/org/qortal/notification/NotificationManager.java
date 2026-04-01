package org.qortal.notification;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;

import org.qortal.utils.Base58;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central notification dispatch system.
 *
 * <h3>Multi-level index layout</h3>
 * <pre>
 *   eventIndex:  event  -&gt;  Map&lt;serviceKey, Map&lt;nameKey, List&lt;SubscriptionEntry&gt;&gt;&gt;
 * </pre>
 *
 * <p>For {@code RESOURCE_PUBLISHED}, the service and name keys from the subscription's
 * {@link ResourcePublishedFilter} are used so the index pre-narrows candidates before
 * the full filter evaluation.  For all other events the generic {@code filters} map
 * values for {@code "service"} and {@code "name"} are used as before.
 *
 * <p>Subscriptions without a service/name restriction are stored under the
 * wildcard key {@value #WILDCARD} so they are always visited.
 *
 * <h3>Deduplication</h3>
 * <p>{@code RESOURCE_PUBLISHED} can fire from multiple places (unconfirmed import,
 * block processing, metadata pipeline).  A per-session seen-set with a
 * {@value #DEDUP_WINDOW_MS}ms TTL ensures each unique
 * {@code service+name+identifier} is delivered at most once per window per session.
 */
public class NotificationManager {

    private static final Logger LOGGER = LogManager.getLogger(NotificationManager.class);

    static final String WILDCARD = "*";

    /** Notifications for the same resource within this window are deduplicated per session. */
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes

    private static NotificationManager instance;

    // session -> SessionSubscriptions
    private final Map<Session, SessionSubscriptions> sessions = new ConcurrentHashMap<>();

    /**
     * Three-level index: event type -> service -> name -> entries.
     */
    private final Map<String, Map<String, Map<String, List<SubscriptionEntry>>>> eventIndex = new ConcurrentHashMap<>();

    /**
     * Deduplication map: session -> (resourceKey -> lastSentTimestamp).
     * resourceKey = "service\0name\0identifier"
     */
    private final Map<Session, Map<String, Long>> recentlySent = new ConcurrentHashMap<>();

    /**
     * Per-session dedup map for generic events: session -> ("type\0dedupKey" -> lastDispatchedTimestamp).
     * Prevents the same transaction firing multiple notifications to the same session when process() is
     * called more than once for the same tx (e.g. sync + group-approval paths).
     * Entries are removed on session close and swept periodically for entries older than DEDUP_WINDOW_MS.
     */
    private final Map<Session, Map<String, Long>> recentlySentEvents = new ConcurrentHashMap<>();

    private NotificationManager() {
    }

    public static synchronized NotificationManager getInstance() {
        if (instance == null) {
            instance = new NotificationManager();
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    public void onSessionOpen(Session session, String address) {
        sessions.put(session, new SessionSubscriptions(session, address, Collections.emptyList()));
        LOGGER.debug("Notification session opened: address={}", address);
    }

    public void onSessionClose(Session session) {
        SessionSubscriptions subs = sessions.remove(session);
        if (subs != null) {
            removeFromIndex(session);
            recentlySent.remove(session);
            recentlySentEvents.remove(session);
            LOGGER.debug("Notification session closed: address={}", subs.address);
        }
    }

    // -------------------------------------------------------------------------
    // Subscription management
    // -------------------------------------------------------------------------

    /**
     * Sweeps stale dedup entries older than {@link #DEDUP_WINDOW_MS} from both
     * per-session dedup maps. Call periodically (e.g. every minute) to bound
     * memory for long-lived sessions.
     */
    public void cleanupOldEntries(long now) {
        long cutoff = now - DEDUP_WINDOW_MS;
        for (Map<String, Long> sessionMap : recentlySentEvents.values()) {
            sessionMap.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        for (Map<String, Long> sessionMap : recentlySent.values()) {
            sessionMap.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    // -------------------------------------------------------------------------
    // Subscription management
    // -------------------------------------------------------------------------

    public void setSubscriptions(Session session, List<NotificationSubscription> rules) {
        if (rules == null) {
            rules = Collections.emptyList();
        }

        SessionSubscriptions subs = sessions.get(session);
        if (subs == null) {
            LOGGER.warn("setSubscriptions called for unknown session");
            return;
        }

        removeFromIndex(session);
        subs.subscriptions = new ArrayList<>(rules);
        addToIndex(session, rules);

        LOGGER.debug("Subscriptions updated for address={}: {} rules", subs.address, rules.size());
    }

    /**
     * Merges incoming rules into the session's existing subscription list.
     * A rule replaces an existing one only when all of {@code notificationId}, {@code appName},
     * and {@code appService} match (null-safe). Otherwise it is appended. This allows different
     * apps to use the same notificationId without overwriting each other.
     */
    public void mergeSubscriptions(Session session, List<NotificationSubscription> incoming) {
        if (incoming == null || incoming.isEmpty()) return;

        SessionSubscriptions subs = sessions.get(session);
        if (subs == null) {
            LOGGER.warn("mergeSubscriptions called for unknown session");
            return;
        }

        List<NotificationSubscription> merged = new ArrayList<>(subs.subscriptions);

        for (NotificationSubscription rule : incoming) {
            boolean replaced = false;
            for (int i = 0; i < merged.size(); i++) {
                if (sameSubscriptionKey(rule, merged.get(i))) {
                    merged.set(i, rule);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                merged.add(rule);
            }
        }

        removeFromIndex(session);
        subs.subscriptions = merged;
        addToIndex(session, merged);

        LOGGER.debug("Subscriptions merged for address={}: {} total rules", subs.address, merged.size());
    }

    /** True when both subscriptions share the same (notificationId, appName, appService) for merge/replace. */
    private static boolean sameSubscriptionKey(NotificationSubscription a, NotificationSubscription b) {
        return Objects.equals(a.getNotificationId(), b.getNotificationId())
                && Objects.equals(a.getAppName(), b.getAppName())
                && Objects.equals(a.getAppService(), b.getAppService());
    }

    /** Returns a snapshot of the current subscription list for the given session. */
    public List<NotificationSubscription> getSubscriptions(Session session) {
        SessionSubscriptions subs = sessions.get(session);
        if (subs == null) return Collections.emptyList();
        return Collections.unmodifiableList(subs.subscriptions);
    }

    /**
     * Removes subscriptions whose (notificationId, appName, appService) match any of the given keys.
     * Re-indexes the session after removal.
     */
    public void removeSubscriptions(Session session, List<NotificationSubscription> keys) {
        if (keys == null || keys.isEmpty()) return;

        SessionSubscriptions subs = sessions.get(session);
        if (subs == null) return;

        List<NotificationSubscription> kept = new ArrayList<>();
        for (NotificationSubscription sub : subs.subscriptions) {
            boolean remove = false;
            for (NotificationSubscription key : keys) {
                if (sameSubscriptionKey(key, sub)) {
                    remove = true;
                    break;
                }
            }
            if (!remove) {
                kept.add(sub);
            }
        }

        removeFromIndex(session);
        subs.subscriptions = kept;
        addToIndex(session, kept);

        LOGGER.debug("Subscriptions removed for address={}: {} remaining", subs.address, kept.size());
    }

    // -------------------------------------------------------------------------
    // Index management
    // -------------------------------------------------------------------------

    private void addToIndex(Session session, List<NotificationSubscription> rules) {
        for (NotificationSubscription rule : rules) {
            String event = rule.getEvent();
            if (event == null || event.isEmpty()) {
                continue;
            }

            String serviceKey;
            String nameKey;

            if ("RESOURCE_PUBLISHED".equals(event) && rule.getResourceFilter() != null) {
                ResourcePublishedFilter f = rule.getResourceFilter();
                serviceKey = (f.service != null && !f.service.isEmpty()) ? f.service : WILDCARD;
                if (f.names != null && f.names.size() == 1) {
                    nameKey = f.names.get(0).toLowerCase();
                } else {
                    nameKey = WILDCARD;
                }
            } else {
                serviceKey = filterValue(rule.getFilters(), "service");
                nameKey    = filterValue(rule.getFilters(), "name");
            }

            SubscriptionEntry entry = new SubscriptionEntry(session, rule);

            eventIndex
                .computeIfAbsent(event,      e -> new ConcurrentHashMap<>())
                .computeIfAbsent(serviceKey, s -> new ConcurrentHashMap<>())
                .computeIfAbsent(nameKey,    n -> Collections.synchronizedList(new ArrayList<>()))
                .add(entry);

        }
    }

    private void removeFromIndex(Session session) {
        for (Map<String, Map<String, List<SubscriptionEntry>>> serviceMap : eventIndex.values()) {
            for (Map<String, List<SubscriptionEntry>> nameMap : serviceMap.values()) {
                for (List<SubscriptionEntry> entries : nameMap.values()) {
                    entries.removeIf(e -> e.session == session);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Event dispatch — RESOURCE_PUBLISHED (typed)
    // -------------------------------------------------------------------------

    /**
     * Dispatches a {@code RESOURCE_PUBLISHED} event to subscriptions that do NOT
     * require metadata fields (title, description, query, keywords).
     * <p>
     * Called immediately when an ARBITRARY transaction arrives so clients with
     * service/name/identifier-only filters get notified without waiting for the
     * cache pipeline.  Subscriptions that need metadata fields are skipped here
     * and will be evaluated later via {@link #processResourcePublished(ResourcePublishedEvent)}.
     */
    public void processResourcePublishedEarly(ResourcePublishedEvent ev) {
        Map<String, Map<String, List<SubscriptionEntry>>> serviceMap = eventIndex.get("RESOURCE_PUBLISHED");
        if (serviceMap == null) {
            return;
        }

        List<String> serviceKeys = new ArrayList<>(2);
        if (ev.service != null && !ev.service.isEmpty()) {
            serviceKeys.add(ev.service);
        }
        serviceKeys.add(WILDCARD);

        List<String> nameKeys = new ArrayList<>(2);
        if (ev.name != null && !ev.name.isEmpty()) {
            nameKeys.add(ev.name.toLowerCase());
        }
        nameKeys.add(WILDCARD);


        for (String serviceKey : serviceKeys) {
            Map<String, List<SubscriptionEntry>> nameMap = serviceMap.get(serviceKey);
            if (nameMap == null) {
                continue;
            }

            for (String nameKey : nameKeys) {
                List<SubscriptionEntry> entries = nameMap.get(nameKey);
                if (entries == null) {
                    continue;
                }

                for (SubscriptionEntry entry : new ArrayList<>(entries)) {
                    if (!entry.session.isOpen()) {
                        continue;
                    }

                    if (entry.rule.isExpired()) {
                        entries.remove(entry);
                        continue;
                    }

                    ResourcePublishedFilter filter = entry.rule.getResourceFilter();

                    if (filter != null && filter.requiresMetadata()) {
                        continue;
                    }

                    boolean matched = filter == null || filter.matches(ev);

                    if (matched) {
                        sendResourcePublished(entry.session, ev, entry.rule);
                    }
                }
            }
        }
    }

    /**
     * Dispatches a {@code RESOURCE_PUBLISHED} event to ALL matching subscriptions,
     * including those that require metadata fields.
     * <p>
     * Called after the cache pipeline completes and metadata is available in the event.
     */
    public void processResourcePublished(ResourcePublishedEvent ev) {
        Map<String, Map<String, List<SubscriptionEntry>>> serviceMap = eventIndex.get("RESOURCE_PUBLISHED");
        if (serviceMap == null) {
            return;
        }

        // Narrow by service (exact match + wildcard)
        List<String> serviceKeys = new ArrayList<>(2);
        if (ev.service != null && !ev.service.isEmpty()) {
            serviceKeys.add(ev.service);
        }
        serviceKeys.add(WILDCARD);

        // Narrow by name (exact lower-case + wildcard)
        List<String> nameKeys = new ArrayList<>(2);
        if (ev.name != null && !ev.name.isEmpty()) {
            nameKeys.add(ev.name.toLowerCase());
        }
        nameKeys.add(WILDCARD);

        for (String serviceKey : serviceKeys) {
            Map<String, List<SubscriptionEntry>> nameMap = serviceMap.get(serviceKey);
            if (nameMap == null) continue;

            for (String nameKey : nameKeys) {
                List<SubscriptionEntry> entries = nameMap.get(nameKey);
                if (entries == null) continue;

                for (SubscriptionEntry entry : new ArrayList<>(entries)) {
                    if (!entry.session.isOpen()) continue;

                    if (entry.rule.isExpired()) {
                        entries.remove(entry);
                        continue;
                    }

                    ResourcePublishedFilter filter = entry.rule.getResourceFilter();

                    // Skip subscriptions that don't need metadata — they were already
                    // notified by the early fire in ArbitraryTransaction.process().
                    if (filter != null && !filter.requiresMetadata()) continue;

                    if (filter == null || filter.matches(ev)) {
                        sendResourcePublished(entry.session, ev, entry.rule);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Event dispatch — generic (PAYMENT_RECEIVED, etc.)
    // -------------------------------------------------------------------------

    /**
     * Dispatches a generic event (keyed by {@code type} and carrying a simple
     * string-map {@code data}) to all matching subscriptions.
     */
    public void processEvent(NotificationEvent event) {
        // Build the dedup key once (null means no dedup for this event type)
        final String dedupEventKey = event.getDedupKey() != null
                ? event.getType() + "\0" + event.getDedupKey()
                : null;

        Map<String, Map<String, List<SubscriptionEntry>>> serviceMap = eventIndex.get(event.getType());
        if (serviceMap == null) {
            return;
        }

        long now = System.currentTimeMillis();
        String eventService = event.getData().get("service");
        String eventName    = event.getData().get("name");

        List<String> serviceKeys = new ArrayList<>(2);
        if (eventService != null && !eventService.isEmpty()) {
            serviceKeys.add(eventService);
        }
        serviceKeys.add(WILDCARD);

        for (String serviceKey : serviceKeys) {
            Map<String, List<SubscriptionEntry>> nameMap = serviceMap.get(serviceKey);
            if (nameMap == null) continue;

            List<String> nameKeys = new ArrayList<>(2);
            if (eventName != null && !eventName.isEmpty()) {
                nameKeys.add(eventName);
            }
            nameKeys.add(WILDCARD);

            for (String nameKey : nameKeys) {
                List<SubscriptionEntry> entries = nameMap.get(nameKey);
                if (entries == null) continue;

                for (SubscriptionEntry entry : new ArrayList<>(entries)) {
                    if (entry.session.isOpen() && matchesGeneric(entry.rule.getFilters(), event.getData())) {
                        if (entry.rule.isExpired()) {
                            entries.remove(entry);
                            continue;
                        }

                        // Per-session dedup: skip if this session already received this event within the window
                        if (dedupEventKey != null) {
                            Map<String, Long> sessionSeen = recentlySentEvents
                                    .computeIfAbsent(entry.session, s -> new ConcurrentHashMap<>());
                            Long last = sessionSeen.put(dedupEventKey, now);
                            if (last != null && (now - last) < DEDUP_WINDOW_MS) {
                                continue;
                            }
                        }

                        sendGenericNotification(entry.session, event, entry.rule);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Filter matching — generic
    // -------------------------------------------------------------------------

    private boolean matchesGeneric(Map<String, String> filters, Map<String, String> data) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String dataValue = data.get(filter.getKey());
            if (dataValue == null || !dataValue.equalsIgnoreCase(filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Sending — RESOURCE_PUBLISHED
    // -------------------------------------------------------------------------

    private void sendResourcePublished(Session session, ResourcePublishedEvent ev, NotificationSubscription sub) {
        if (!session.isOpen()) return;

        // Deduplicate: skip if we sent a notification for this tx signature to this session within the window.
        // Falls back to service+name+identifier if signature is unavailable.
        String dedupKey = ev.signature != null
                ? ev.signature
                : ev.service + "\0" + ev.name + "\0" + ev.identifier;
        long now = System.currentTimeMillis();
        Map<String, Long> seen = recentlySent.computeIfAbsent(session, s -> new ConcurrentHashMap<>());
        Long lastSent = seen.get(dedupKey);
        if (lastSent != null && (now - lastSent) < DEDUP_WINDOW_MS) {
            return;
        }
        seen.put(dedupKey, now);

        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"type\":\"notification\",\"event\":\"RESOURCE_PUBLISHED\",\"data\":{");
            sb.append("\"service\":\"").append(jsonEscape(ev.service)).append("\"");
            sb.append(",\"name\":\"").append(jsonEscape(ev.name)).append("\"");
            sb.append(",\"identifier\":\"").append(jsonEscape(ev.identifier)).append("\"");
            if (ev.title != null) {
                sb.append(",\"title\":\"").append(jsonEscape(ev.title)).append("\"");
            }
            if (ev.description != null) {
                sb.append(",\"description\":\"").append(jsonEscape(ev.description)).append("\"");
            }
            if (ev.category != null) {
                sb.append(",\"category\":\"").append(jsonEscape(ev.category)).append("\"");
            }
            if (ev.tags != null && !ev.tags.isEmpty()) {
                sb.append(",\"tags\":[");
                for (int i = 0; i < ev.tags.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(jsonEscape(ev.tags.get(i))).append("\"");
                }
                sb.append("]");
            }
            if (ev.created != null) {
                sb.append(",\"created\":").append(ev.created);
            }
            if (ev.updated != null) {
                sb.append(",\"updated\":").append(ev.updated);
            }
            if (ev.signature != null) {
                sb.append(",\"signature\":\"").append(jsonEscape(ev.signature)).append("\"");
            }
            sb.append(",\"timestamp\":").append(System.currentTimeMillis());
            sb.append("}");

            // Echo back the subscription's display hints if present (with {name}/{identifier} substitution)
            if (sub != null && sub.getMessage() != null && !sub.getMessage().isEmpty()) {
                sb.append(",\"message\":{");
                boolean firstMsg = true;
                for (Map.Entry<String, String> msg : sub.getMessage().entrySet()) {
                    if (!firstMsg) sb.append(",");
                    sb.append("\"").append(jsonEscape(msg.getKey())).append("\":");
                    sb.append("\"").append(jsonEscape(substitutePlaceholders(msg.getValue(), ev.name, ev.identifier))).append("\"");
                    firstMsg = false;
                }
                sb.append("}");
            }
            if (sub != null && sub.getImage() != null) {
                sb.append(",\"image\":\"").append(jsonEscape(substitutePlaceholders(sub.getImage(), ev.name, ev.identifier))).append("\"");
            }
            if (sub != null && sub.getLink() != null) {
                sb.append(",\"link\":\"").append(jsonEscape(substitutePlaceholdersForLink(sub.getLink(), ev.name, ev.identifier))).append("\"");
            }
            if (sub != null && sub.getAppService() != null) {
                sb.append(",\"appService\":\"").append(jsonEscape(sub.getAppService())).append("\"");
            }
            if (sub != null && sub.getAppName() != null) {
                sb.append(",\"appName\":\"").append(jsonEscape(sub.getAppName())).append("\"");
            }
            if (sub != null && sub.getNotificationId() != null) {
                sb.append(",\"notificationId\":\"").append(jsonEscape(sub.getNotificationId())).append("\"");
            }
            if (sub != null && sub.getExpiresWhen() != null) {
                sb.append(",\"expiresWhen\":").append(sub.getExpiresWhen());
            }

            sb.append("}");

            session.getRemote().sendString(sb.toString(), WriteCallback.NOOP);
        } catch (Exception e) {
            LOGGER.debug("Failed to send RESOURCE_PUBLISHED notification: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Sending — generic
    // -------------------------------------------------------------------------

    private void sendGenericNotification(Session session, NotificationEvent event, NotificationSubscription sub) {
        if (!session.isOpen()) return;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"notification\"");
            sb.append(",\"event\":\"").append(jsonEscape(event.getType())).append("\"");
            sb.append(",\"data\":{");

            boolean first = true;
            for (Map.Entry<String, String> entry : event.getData().entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(jsonEscape(entry.getKey())).append("\":");
                sb.append("\"").append(jsonEscape(entry.getValue())).append("\"");
                first = false;
            }
            sb.append(",\"timestamp\":").append(System.currentTimeMillis());

            sb.append("}");

            if (sub != null && sub.getNotificationId() != null) {
                sb.append(",\"notificationId\":\"").append(jsonEscape(sub.getNotificationId())).append("\"");
            }

            sb.append("}");

            session.getRemote().sendString(sb.toString(), WriteCallback.NOOP);
        } catch (Exception e) {
            LOGGER.debug("Failed to send notification to session: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Notification history
    // -------------------------------------------------------------------------

    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int DEFAULT_PAYMENT_RECEIVED_LIMIT = 20;
    private static final int MAX_PAYMENT_RECEIVED_LIMIT = 100;

    /**
     * Handles the {@code notification-history} WebSocket action.
     *
     * <p>For every {@code RESOURCE_PUBLISHED} subscription it queries the in-memory
     * {@code ArbitraryResourceCache} (zero DB cost).  For every {@code PAYMENT_RECEIVED}
     * subscription it performs one indexed DB query filtered by recipient.
     *
     * <p>All results are merged, sorted most-recent-first and trimmed to {@code limit},
     * then sent as a single JSON envelope.
     */
    private static final long HISTORY_DEFAULT_AFTER_MS = 7L  * 24 * 60 * 60 * 1000;  // 7 days
    private static final long HISTORY_MAX_LOOKBACK_MS  = 30L * 24 * 60 * 60 * 1000;  // 30 days

    public void handleNotificationHistory(Session session, Integer limit, Long after, Integer paymentReceivedLimit) {
        if (!session.isOpen()) return;

        SessionSubscriptions subs = sessions.get(session);
        if (subs == null) return;

        long t0 = System.currentTimeMillis();

        int effectiveLimit = (limit != null && limit > 0) ? limit : DEFAULT_HISTORY_LIMIT;
        int effectivePaymentLimit = (paymentReceivedLimit != null && paymentReceivedLimit > 0)
                ? Math.min(paymentReceivedLimit, MAX_PAYMENT_RECEIVED_LIMIT)
                : DEFAULT_PAYMENT_RECEIVED_LIMIT;

        long now = System.currentTimeMillis();
        long maxLookback = now - HISTORY_MAX_LOOKBACK_MS;
        long defaultAfter = now - HISTORY_DEFAULT_AFTER_MS;

        // If no after supplied, default to 7 days ago.
        // If after is supplied but older than 30 days, cap it at 30 days ago.
        long effectiveAfter = (after == null)
                ? defaultAfter
                : Math.max(after, maxLookback);

        List<long[]> timestamps = new ArrayList<>();
        List<String> jsons      = new ArrayList<>();

        for (NotificationSubscription sub : new ArrayList<>(subs.subscriptions)) {
            if (sub.isExpired()) continue;

            if ("RESOURCE_PUBLISHED".equals(sub.getEvent())) {
                collectResourceHistoryPaired(sub, effectiveAfter, effectiveLimit, timestamps, jsons);
            } else if ("PAYMENT_RECEIVED".equals(sub.getEvent())) {
                collectPaymentHistoryPaired(sub, effectiveAfter, effectivePaymentLimit, timestamps, jsons);
            }
        }


        // Sort indices by timestamp descending
        Integer[] indices = new Integer[timestamps.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        java.util.Arrays.sort(indices, (a, b) -> Long.compare(timestamps.get(b)[0], timestamps.get(a)[0]));


        int resultCount = Math.min(effectiveLimit, indices.length);

        try {
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\"type\":\"history\",\"results\":[");
            for (int i = 0; i < resultCount; i++) {
                if (i > 0) sb.append(",");
                sb.append(jsons.get(indices[i]));
            }
            sb.append("]}");
            session.getRemote().sendString(sb.toString(), WriteCallback.NOOP);
        } catch (Exception e) {
            LOGGER.debug("Error sending notification history: {}", e.getMessage());
        }
    }

    private void collectResourceHistoryPaired(
            NotificationSubscription sub, Long after, int limit,
            List<long[]> timestamps, List<String> jsons) {

        long t0 = System.currentTimeMillis();
        ResourcePublishedFilter f = sub.getResourceFilter();
        if (f == null || f.service == null || f.service.isEmpty()) return;

        org.qortal.arbitrary.misc.Service serviceEnum;
        try {
            serviceEnum = org.qortal.arbitrary.misc.Service.valueOf(f.service);
        } catch (IllegalArgumentException e) {
            LOGGER.debug("Unknown service in resourceFilter: {}", f.service);
            return;
        }

        org.qortal.data.arbitrary.ArbitraryResourceCache cache =
                org.qortal.data.arbitrary.ArbitraryResourceCache.getInstance();

        List<org.qortal.data.arbitrary.ArbitraryResourceData> candidates = new ArrayList<>();
        synchronized (cache.getDataByService()) {
            java.util.Map<String, org.qortal.data.arbitrary.ArbitraryResourceData> serviceMap =
                    cache.getDataByService().get(serviceEnum.value);
            if (serviceMap != null) {
                candidates.addAll(serviceMap.values());
            }
        }

        if (candidates.isEmpty()) return;

        List<String> blockedNames = (f.excludeBlocked != null && f.excludeBlocked)
                ? org.qortal.utils.ListUtils.blockedNames()
                : null;
        List<String> followedNames = (f.followedOnly != null && f.followedOnly)
                ? org.qortal.utils.ListUtils.followedNames()
                : null;


        long afterMs = after != null ? after : 0L;
        List<org.qortal.data.arbitrary.ArbitraryResourceData> results =
                org.qortal.repository.hsqldb.HSQLDBCacheUtils.getRecentForNotificationHistory(
                        candidates,
                        afterMs,
                        limit,
                        f.identifier,
                        Boolean.TRUE.equals(f.prefix),
                        blockedNames,
                        f.names,
                        f.query,
                        f.defaultResource,
                        f.title,
                        f.description,
                        f.keywords,
                        f.before,
                        followedNames);


        for (org.qortal.data.arbitrary.ArbitraryResourceData r : results) {
            long ts = r.created != null ? r.created : 0L;
            timestamps.add(new long[]{ts});
            jsons.add(buildResourcePublishedHistoryJson(r, sub));
        }
    }

    private void collectPaymentHistoryPaired(
            NotificationSubscription sub, Long after, int paymentLimit,
            List<long[]> timestamps, List<String> jsons) {

        long t0 = System.currentTimeMillis();
        Map<String, String> filters = sub.getFilters();
        if (filters == null) return;
        String recipient = filters.get("recipient");
        if (recipient == null || recipient.isEmpty()) return;

        try (org.qortal.repository.Repository repository =
                     org.qortal.repository.RepositoryManager.getRepository()) {


            List<String[]> payments =
                    repository.getTransactionRepository()
                            .getReceivedPaymentsForNotifications(recipient, after, paymentLimit);


            for (String[] row : payments) {
                // row: [sender, recipient, amountPretty, timestampMs, signature]
                long ts = Long.parseLong(row[3]);
                String sig = row.length > 4 ? row[4] : null;
                timestamps.add(new long[]{ts});
                jsons.add(buildPaymentHistoryJson(row[0], row[1], row[2], ts, sig, sub));
            }
        } catch (Exception e) {
            LOGGER.debug("Error fetching payment history: {}", e.getMessage());
        }
    }

    private String buildResourcePublishedHistoryJson(
            org.qortal.data.arbitrary.ArbitraryResourceData r,
            NotificationSubscription sub) {

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"notification\",\"event\":\"RESOURCE_PUBLISHED\",\"data\":{");
        sb.append("\"service\":\"").append(jsonEscape(r.service != null ? r.service.name() : "")).append("\"");
        sb.append(",\"name\":\"").append(jsonEscape(r.name != null ? r.name : "")).append("\"");
        sb.append(",\"identifier\":\"").append(jsonEscape(r.identifier != null ? r.identifier : "")).append("\"");
        if (r.created != null) sb.append(",\"created\":").append(r.created);
        if (r.updated != null) sb.append(",\"updated\":").append(r.updated);
        if (r.latestSignature != null) sb.append(",\"signature\":\"").append(jsonEscape(Base58.encode(r.latestSignature))).append("\"");
        sb.append(",\"timestamp\":").append(System.currentTimeMillis());
        sb.append("}");
        appendSubDisplayHints(sb, sub, r.name, r.identifier);
        sb.append("}");
        return sb.toString();
    }

    private String buildPaymentHistoryJson(
            String sender, String recipient, String amount, long timestamp, String signature,
            NotificationSubscription sub) {

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"notification\",\"event\":\"PAYMENT_RECEIVED\",\"data\":{");
        sb.append("\"sender\":\"").append(jsonEscape(sender)).append("\"");
        sb.append(",\"recipient\":\"").append(jsonEscape(recipient)).append("\"");
        sb.append(",\"amount\":\"").append(jsonEscape(amount)).append("\"");
        sb.append(",\"created\":").append(timestamp);
        if (signature != null) sb.append(",\"signature\":\"").append(jsonEscape(signature)).append("\"");
        sb.append(",\"timestamp\":").append(System.currentTimeMillis());
        sb.append("}");
        appendSubDisplayHints(sb, sub, null, null);
        sb.append("}");
        return sb.toString();
    }

    private void appendSubDisplayHints(StringBuilder sb, NotificationSubscription sub, String name, String identifier) {
        if (sub == null) return;
        if (sub.getNotificationId() != null)
            sb.append(",\"notificationId\":\"").append(jsonEscape(sub.getNotificationId())).append("\"");
        if (sub.getMessage() != null && !sub.getMessage().isEmpty()) {
            sb.append(",\"message\":{");
            boolean first = true;
            for (Map.Entry<String, String> msg : sub.getMessage().entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(jsonEscape(msg.getKey())).append("\":");
                String value = (name != null || identifier != null)
                        ? substitutePlaceholders(msg.getValue(), name, identifier)
                        : msg.getValue();
                sb.append("\"").append(jsonEscape(value)).append("\"");
                first = false;
            }
            sb.append("}");
        }
        if (sub.getImage() != null) {
            String image = (name != null || identifier != null)
                    ? substitutePlaceholders(sub.getImage(), name, identifier)
                    : sub.getImage();
            sb.append(",\"image\":\"").append(jsonEscape(image)).append("\"");
        }
        if (sub.getLink() != null) {
            String link = (name != null || identifier != null)
                    ? substitutePlaceholdersForLink(sub.getLink(), name, identifier)
                    : sub.getLink();
            sb.append(",\"link\":\"").append(jsonEscape(link)).append("\"");
        }
        if (sub.getAppService() != null)
            sb.append(",\"appService\":\"").append(jsonEscape(sub.getAppService())).append("\"");
        if (sub.getAppName() != null)
            sb.append(",\"appName\":\"").append(jsonEscape(sub.getAppName())).append("\"");
        if (sub.getExpiresWhen() != null)
            sb.append(",\"expiresWhen\":").append(sub.getExpiresWhen());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String filterValue(Map<String, String> filters, String key) {
        if (filters == null) return WILDCARD;
        String v = filters.get(key);
        return (v != null && !v.isEmpty()) ? v : WILDCARD;
    }

    static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Replaces {name} and {identifier} in template with the given values (null treated as ""). */
    private static String substitutePlaceholders(String template, String name, String identifier) {
        if (template == null) return null;
        String n = name != null ? name : "";
        String i = identifier != null ? identifier : "";
        return template.replace("{name}", n).replace("{identifier}", i);
    }

    /** Like substitutePlaceholders but URI-encodes name and identifier for use in links. */
    private static String substitutePlaceholdersForLink(String template, String name, String identifier) {
        if (template == null) return null;
        String n = uriEncode(name);
        String i = uriEncode(identifier);
        return template.replace("{name}", n).replace("{identifier}", i);
    }

    /** Encodes like JavaScript encodeURIComponent: space -> %20, leaves - _ . ! ~ * ' ( ) unencoded. */
    private static String uriEncode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '!' || c == '~'
                    || c == '*' || c == '\'' || c == '(' || c == ')') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    public int getSessionCount() {
        return sessions.size();
    }
}
