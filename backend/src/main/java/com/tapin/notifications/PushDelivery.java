package com.tapin.notifications;

import com.tapin.notifications.email.SendResult;
import com.tapin.notifications.push.PushResult;
import com.tapin.notifications.push.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Delivers one PUSH outbox row to every push subscription of its recipient and folds the per-device results into
 * one outcome for the outbox row:
 * <ul>
 *   <li>accepted by at least one push service: {@code Accepted} (devices that failed temporarily are not retried,
 *       otherwise the devices that already got it would get it twice);</li>
 *   <li>otherwise a temporary error on any device: {@code TransientFailure} (normal outbox retry/backoff);</li>
 *   <li>no subscription left: {@code Skipped}; rejected content/configuration: {@code PermanentFailure}.</li>
 * </ul>
 * A subscription the push service reports as gone (404/410) is deleted here.
 */
@Component
class PushDelivery {

    private static final Logger log = LoggerFactory.getLogger(PushDelivery.class);
    /** Messages without an expiry are kept by the push service for an offline device this long. */
    private static final Duration DEFAULT_TTL = Duration.ofDays(1);
    private static final int MAX_PROVIDER_ID = 255;

    private final PushSubscriptionRepository subscriptions;
    private final PushSender sender;
    private final PushTemplateRenderer renderer;

    PushDelivery(PushSubscriptionRepository subscriptions, PushSender sender, PushTemplateRenderer renderer) {
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.renderer = renderer;
    }

    /** @throws IllegalArgumentException when the message cannot be rendered (permanent) */
    SendResult deliver(OutboxEntry entry, Instant now) {
        String message = renderer.render(entry.eventType(), entry.sessionId(), entry.payload());
        List<PushSubscriptionRepository.Row> targets = subscriptions.findByUser(entry.recipientUserId());
        if (targets.isEmpty()) {
            return new SendResult.Skipped("No active push subscription; notification available in inbox");
        }
        Duration ttl = entry.expiresAt() == null ? DEFAULT_TTL : Duration.between(now, entry.expiresAt());

        int accepted = 0;
        int gone = 0;
        String firstMessageId = null;
        List<String> transientErrors = new ArrayList<>();
        List<String> permanentErrors = new ArrayList<>();
        for (PushSubscriptionRepository.Row target : targets) {
            PushResult result;
            try {
                result = sender.send(target.target(), message, ttl);
            } catch (RuntimeException e) {
                result = new PushResult.TransientFailure("Unexpected error: " + e);
            }
            switch (result) {
                case PushResult.Accepted ok -> {
                    accepted++;
                    if (firstMessageId == null) {
                        firstMessageId = ok.providerMessageId();
                    }
                }
                case PushResult.Gone g -> {
                    gone++;
                    subscriptions.deleteById(target.id());
                    log.info("Push subscription {} of user {} removed: {}", target.id(), entry.recipientUserId(),
                            g.reason());
                }
                case PushResult.TransientFailure t -> transientErrors.add(t.reason());
                case PushResult.PermanentFailure p -> permanentErrors.add(p.reason());
            }
        }

        if (accepted > 0) {
            String id = "push " + accepted + "/" + targets.size() + (firstMessageId == null ? "" : " " + firstMessageId);
            return new SendResult.Accepted(id.length() > MAX_PROVIDER_ID ? id.substring(0, MAX_PROVIDER_ID) : id);
        }
        if (!transientErrors.isEmpty()) {
            return new SendResult.TransientFailure(String.join("; ", transientErrors), false);
        }
        if (permanentErrors.isEmpty()) {
            return new SendResult.Skipped("All " + gone + " push subscription(s) expired and were removed");
        }
        return new SendResult.PermanentFailure(String.join("; ", permanentErrors));
    }
}
