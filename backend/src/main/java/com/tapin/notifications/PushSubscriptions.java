package com.tapin.notifications;

import com.tapin.notifications.push.PushProperties;
import com.tapin.notifications.push.WebPushCrypto;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Public API of the notifications module for web push subscriptions (one per browser/device of a member).
 * A subscription is the member's opt-in for push on that device: training reminders go out as push to every
 * subscription of the member, independently of the e-mail reminder setting.
 */
@Service
public class PushSubscriptions {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptions.class);
    private static final int MAX_USER_AGENT = 255;

    private final PushSubscriptionRepository repository;
    private final PushProperties properties;
    private final Clock clock;

    PushSubscriptions(PushSubscriptionRepository repository, PushProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** True when VAPID keys are configured; without them push is neither offered nor queued. */
    public boolean enabled() {
        return properties.enabled();
    }

    /** The VAPID application server key the browser subscribes with (base64url), if push is enabled. */
    public Optional<String> publicKey() {
        return enabled() ? Optional.of(WebPushCrypto.encode(WebPushCrypto.decode(properties.vapidPublicKey())))
                : Optional.empty();
    }

    /**
     * Saves the browser's subscription for {@code userId}. Idempotent; an endpoint already registered for another
     * person moves to this one (shared device).
     *
     * @throws ApiException 422 when push is not configured, 400 for an endpoint outside the known push services or
     *                      malformed keys
     */
    @Transactional
    public void register(long userId, String endpoint, String p256dh, String auth, String userAgent) {
        if (!enabled()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Push obavijesti nisu uključene na poslužitelju.");
        }
        if (!properties.isAllowedEndpoint(endpoint)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Adresa pretplate nije poznati servis za push obavijesti.");
        }
        if (!validKey(p256dh, 65) || !validKey(auth, 16)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ključevi pretplate nisu ispravni.");
        }
        String agent = userAgent == null || userAgent.isBlank() ? null
                : userAgent.substring(0, Math.min(userAgent.length(), MAX_USER_AGENT));
        repository.upsert(userId, endpoint, p256dh, auth, agent, clock.instant());
        log.info("Push subscription saved for user {}", userId);
    }

    /** Removes this browser's subscription of {@code userId}; nothing happens if it does not exist. */
    @Transactional
    public void remove(long userId, String endpoint) {
        if (repository.delete(userId, endpoint) > 0) {
            log.info("Push subscription removed for user {}", userId);
        }
    }

    /** Users (of {@code userIds}) with at least one subscription; empty when push is disabled. */
    @Transactional(readOnly = true)
    public Set<Long> usersWithSubscription(Collection<Long> userIds) {
        return enabled() ? repository.usersWithSubscription(userIds) : Set.of();
    }

    private static boolean validKey(String value, int expectedBytes) {
        if (value == null || value.length() > 128) {
            return false;
        }
        try {
            byte[] bytes = WebPushCrypto.decode(value);
            return bytes.length == expectedBytes && (expectedBytes != 65 || bytes[0] == 0x04);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
