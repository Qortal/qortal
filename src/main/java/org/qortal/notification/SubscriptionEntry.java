package org.qortal.notification;

import org.eclipse.jetty.websocket.api.Session;

/**
 * An index entry pointing from an event/service bucket back to a session + rule.
 */
public class SubscriptionEntry {

    public final Session session;
    public final NotificationSubscription rule;

    public SubscriptionEntry(Session session, NotificationSubscription rule) {
        this.session = session;
        this.rule = rule;
    }
}
