package com.tapin.notifications.push;

import java.time.Duration;

/** Sends one encrypted Web Push message to one subscription. */
public interface PushSender {

    /** The browser side of a subscription (PushSubscription.toJSON()). */
    record Target(String endpoint, String p256dh, String auth) {
    }

    /**
     * @param payload JSON shown by the service worker ({@code title}, {@code body}, {@code url}, {@code tag})
     * @param ttl     how long the push service keeps the message for an offline device
     */
    PushResult send(Target target, String payload, Duration ttl);
}
