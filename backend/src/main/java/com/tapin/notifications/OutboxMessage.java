package com.tapin.notifications;

import java.util.Map;
import java.util.Objects;

/**
 * A notification to be written to the outbox by a business module.
 *
 * @param deduplicationKey stable key of the <em>business event</em> (not of the attempt), e.g.
 *                         {@code ATTENDANCE_RECORDED:audit-981} or {@code TRAINING_REMINDER:session-12:membership-7}.
 *                         Enqueueing the same key again is a no-op.
 * @param payload          template variables, already formatted for display (Croatian, Europe/Zagreb). Values must
 *                         be JSON-serialisable.
 * @param containsSecret   true for messages carrying one-time links (invitation, password reset): the payload is
 *                         wiped once the row is SENT or FAILED.
 */
public record OutboxMessage(
        EmailEventType eventType,
        long recipientUserId,
        String recipientEmail,
        Long sessionId,
        Map<String, Object> payload,
        String deduplicationKey,
        boolean containsSecret) {

    public static final int MAX_DEDUPLICATION_KEY_LENGTH = 255;

    public OutboxMessage {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(recipientEmail, "recipientEmail");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey");
        if (deduplicationKey.isBlank() || deduplicationKey.length() > MAX_DEDUPLICATION_KEY_LENGTH) {
            throw new IllegalArgumentException("deduplicationKey must be 1-" + MAX_DEDUPLICATION_KEY_LENGTH + " chars");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static OutboxMessage of(EmailEventType type, long recipientUserId, String recipientEmail,
                                   Map<String, Object> payload, String deduplicationKey) {
        return new OutboxMessage(type, recipientUserId, recipientEmail, null, payload, deduplicationKey, false);
    }

    public OutboxMessage forSession(long sessionId) {
        return new OutboxMessage(eventType, recipientUserId, recipientEmail, sessionId, payload, deduplicationKey,
                containsSecret);
    }

    public OutboxMessage withSecret() {
        return new OutboxMessage(eventType, recipientUserId, recipientEmail, sessionId, payload, deduplicationKey, true);
    }
}
