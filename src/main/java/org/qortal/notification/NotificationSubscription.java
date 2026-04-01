package org.qortal.notification;

import java.util.Map;

/**
 * A single subscription rule sent by a WebSocket client.
 *
 * <p>Two filter shapes are supported, discriminated by the {@code event} field:
 *
 * <ul>
 *   <li>{@code RESOURCE_PUBLISHED} — use {@code resourceFilter} (a rich typed
 *       object mirroring QDN search parameters).</li>
 *   <li>All other events — use the generic {@code filters} string map.</li>
 * </ul>
 *
 * <p>Optional display hints ({@code message}, {@code image}, {@code link}) are
 * stored with the subscription and echoed back in every matching notification
 * so the UI can render them without extra logic.
 *
 * <p>Full example:
 * <pre>
 * {
 *   "event": "RESOURCE_PUBLISHED",
 *   "resourceFilter": {
 *     "service": "MAIL_PRIVATE",
 *     "identifier": "qortal_qmail_alice_mail_",
 *     "prefix": true,
 *     "excludeBlocked": true
 *   },
 *   "message": { "en": "New QMail", "fr": "Nouveau QMail" },
 *   "image": "data:image/png;base64,...",
 *   "link": "qortal://APP/qmail"
 * }
 * </pre>
 */
public class NotificationSubscription {

    private String event;

    /** Rich typed filter used when {@code event = RESOURCE_PUBLISHED}. */
    private ResourcePublishedFilter resourceFilter;

    /** Generic key=value filter used for all other event types. */
    private Map<String, String> filters;

    /**
     * Optional localized display message echoed back in the notification.
     * Keys are BCP-47 language tags (e.g. "en", "fr"), values are the text.
     */
    private Map<String, String> message;

    /** Optional image URL or data-URI echoed back in the notification. */
    private String image;

    /** Optional deep-link or URL echoed back in the notification. */
    private String link;

    /** Optional service name echoed back in the notification (e.g. "MAIL_PRIVATE"). */
    private String appService;

    /** Optional app name echoed back in the notification (e.g. "Q-Mail"). */
    private String appName;

    /** Optional client-defined identifier echoed back in the notification for client-side routing. */
    private String notificationId;

    /**
     * Optional expiry timestamp (epoch millis). When set and the current time exceeds this value,
     * the subscription is automatically removed and no further notifications are sent.
     */
    private Long expiresWhen;

    public NotificationSubscription() {
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public ResourcePublishedFilter getResourceFilter() {
        return resourceFilter;
    }

    public void setResourceFilter(ResourcePublishedFilter resourceFilter) {
        this.resourceFilter = resourceFilter;
    }

    public Map<String, String> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, String> filters) {
        this.filters = filters;
    }

    public Map<String, String> getMessage() {
        return message;
    }

    public void setMessage(Map<String, String> message) {
        this.message = message;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getAppService() {
        return appService;
    }

    public void setAppService(String appService) {
        this.appService = appService;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(String notificationId) {
        this.notificationId = notificationId;
    }

    public Long getExpiresWhen() {
        return expiresWhen;
    }

    public void setExpiresWhen(Long expiresWhen) {
        this.expiresWhen = expiresWhen;
    }

    /** Returns true if this subscription has expired and should be removed. */
    public boolean isExpired() {
        return expiresWhen != null && System.currentTimeMillis() > expiresWhen;
    }
}
