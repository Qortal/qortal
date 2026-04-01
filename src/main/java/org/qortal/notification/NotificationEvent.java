package org.qortal.notification;

import java.util.Map;

/**
 * A normalized event produced by a hook point (metadata save, transaction, etc.)
 * and consumed by the NotificationManager dispatcher.
 *
 * <p>An optional {@code dedupKey} can be supplied. When present, the
 * NotificationManager will suppress duplicate dispatches of the same
 * {@code type + dedupKey} within {@code DEDUP_WINDOW_MS} milliseconds.
 * Use the transaction signature (Base58) as the dedup key for tx-based events.
 */
public class NotificationEvent {

    private final String type;
    private final Map<String, String> data;
    private final String dedupKey;

    public NotificationEvent(String type, Map<String, String> data) {
        this(type, data, null);
    }

    public NotificationEvent(String type, Map<String, String> data, String dedupKey) {
        this.type = type;
        this.data = data;
        this.dedupKey = dedupKey;
    }

    public String getType() {
        return type;
    }

    public Map<String, String> getData() {
        return data;
    }

    public String getDedupKey() {
        return dedupKey;
    }
}
