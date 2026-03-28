package org.qortal.api.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.qortal.notification.NotificationManager;
import org.qortal.notification.NotificationSubscription;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * WebSocket endpoint for the Qortal notification system.
 *
 * <p>Clients connect to {@code /websockets/notifications} and send a JSON message
 * to register their subscription rules:
 *
 * <pre>
 * {
 *   "action": "subscribe",
 *   "address": "Qxxxxxxxxx",
 *   "subscriptions": [
 *     { "event": "RESOURCE_PUBLISHED", "filters": { "service": "BLOG" } },
 *     { "event": "PAYMENT_RECEIVED",   "filters": { "recipient": "Qxxxxxxxxx" } }
 *   ]
 * }
 * </pre>
 *
 * <p>The server will push JSON notifications whenever matching events occur:
 *
 * <pre>
 * {
 *   "type": "notification",
 *   "event": "RESOURCE_PUBLISHED",
 *   "data": { "service": "BLOG", "name": "alice", "identifier": "post-123" }
 * }
 * </pre>
 *
 * <p>Supported actions:
 * <ul>
 *   <li>{@code subscribe} – replace the session's subscription set with the supplied list.</li>
 *   <li>{@code unsubscribe} – clear all subscriptions for this session.</li>
 * </ul>
 */
@WebSocket
@SuppressWarnings("serial")
public class NotificationsWebSocket extends ApiWebSocket {

    private static final Logger LOGGER = LogManager.getLogger(NotificationsWebSocket.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    protected void configure(JettyWebSocketServletFactory factory) {
        factory.addMapping("/", (req, res) -> this);
    }

    @OnWebSocketConnect
    @Override
    public void onWebSocketConnect(Session session) {
        super.onWebSocketConnect(session);

        // Address is optional — clients may supply it in the subscribe message instead.
        String address = queryParam(session, "address");
        NotificationManager.getInstance().onSessionOpen(session, address);

        LOGGER.debug("Notification WebSocket connected: address={}", address);
    }

    @OnWebSocketClose
    @Override
    public void onWebSocketClose(Session session, int statusCode, String reason) {
        NotificationManager.getInstance().onSessionClose(session);
        super.onWebSocketClose(session, statusCode, reason);
    }

    @OnWebSocketError
    public void onWebSocketError(Session session, Throwable throwable) {
        // Silence log spam – errors are handled via close callback.
    }

    @OnWebSocketMessage
    public void onWebSocketMessage(Session session, String message) {
        if (Objects.equals(message, "ping")) {
            if (session.isOpen()) {
                session.getRemote().sendString("pong", WriteCallback.NOOP);
            }
            return;
        }

        try {
            Map<String, Object> payload = MAPPER.readValue(message, new TypeReference<Map<String, Object>>() {});
            String action = getString(payload, "action");

            if (action == null) {
                sendError(session, "Missing 'action' field");
                return;
            }

            switch (action) {
                case "subscribe": {
                    List<NotificationSubscription> rules = parseSubscriptions(payload);
                    for (NotificationSubscription rule : rules) {
                        if (rule.getNotificationId() == null || rule.getNotificationId().isEmpty()) {
                            sendError(session, "Every subscription must have a 'notificationId'");
                            return;
                        }
                    }
                    NotificationManager.getInstance().mergeSubscriptions(session, rules);
                    break;
                }

                case "unsubscribe": {
                    List<NotificationSubscription> keys = parseNotificationIdKeys(payload);
                    if (keys != null && !keys.isEmpty()) {
                        NotificationManager.getInstance().removeSubscriptions(session, keys);
                    } else {
                        NotificationManager.getInstance().setSubscriptions(session, Collections.emptyList());
                    }
                    break;
                }

                case "subscriptions": {
                    List<NotificationSubscription> current =
                            NotificationManager.getInstance().getSubscriptions(session);
                    String json = MAPPER.writeValueAsString(
                            Map.of("action", "subscribe", "subscriptions", current));
                    session.getRemote().sendString(json, WriteCallback.NOOP);
                    break;
                }

                case "notification-history": {
                    Integer limit = payload.get("limit") instanceof Number
                            ? ((Number) payload.get("limit")).intValue() : null;
                    Long after = payload.get("after") instanceof Number
                            ? ((Number) payload.get("after")).longValue() : null;
                    Integer paymentReceivedLimit = payload.get("paymentReceivedLimit") instanceof Number
                            ? ((Number) payload.get("paymentReceivedLimit")).intValue() : null;
                    NotificationManager.getInstance().handleNotificationHistory(session, limit, after, paymentReceivedLimit);
                    break;
                }

                default:
                    sendError(session, "Unknown action: " + action);
            }

        } catch (Exception e) {
            LOGGER.debug("Error processing notification WebSocket message: {}", e.getMessage());
            sendError(session, "Invalid message format");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<NotificationSubscription> parseSubscriptions(Map<String, Object> payload) throws Exception {
        Object subsObj = payload.get("subscriptions");
        if (subsObj == null) {
            return Collections.emptyList();
        }
        // Re-serialize just the subscriptions array and deserialize as our model
        String subsJson = MAPPER.writeValueAsString(subsObj);
        return MAPPER.readValue(subsJson, new TypeReference<List<NotificationSubscription>>() {});
    }

    /** Parses optional "notificationIds": [{ appName, appService, notificationId }, ...]. Returns null or empty if absent. */
    private List<NotificationSubscription> parseNotificationIdKeys(Map<String, Object> payload) throws Exception {
        Object idsObj = payload.get("notificationIds");
        if (idsObj == null || !(idsObj instanceof List) || ((List<?>) idsObj).isEmpty()) {
            return null;
        }
        String json = MAPPER.writeValueAsString(idsObj);
        return MAPPER.readValue(json, new TypeReference<List<NotificationSubscription>>() {});
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static String queryParam(Session session, String name) {
        List<String> values = session.getUpgradeRequest().getParameterMap().get(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    private static void sendError(Session session, String message) {
        if (!session.isOpen()) {
            return;
        }
        String json = "{\"type\":\"error\",\"message\":\"" + message.replace("\"", "\\\"") + "\"}";
        session.getRemote().sendString(json, WriteCallback.NOOP);
    }
}
