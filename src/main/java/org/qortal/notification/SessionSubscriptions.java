package org.qortal.notification;

import org.eclipse.jetty.websocket.api.Session;

import java.util.List;

/**
 * Holds all subscriptions for one WebSocket session.
 */
public class SessionSubscriptions {

    public final Session session;
    public final String address;
    public volatile List<NotificationSubscription> subscriptions;

    public SessionSubscriptions(Session session, String address, List<NotificationSubscription> subscriptions) {
        this.session = session;
        this.address = address;
        this.subscriptions = subscriptions;
    }
}
