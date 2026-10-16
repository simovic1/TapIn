package com.tapin.notifications;

import java.time.Instant;
import java.util.Map;

/** A row of {@code email_outbox} as seen by the worker. */
record OutboxEntry(
        long id,
        EmailEventType eventType,
        long recipientUserId,
        String recipientEmail,
        Long sessionId,
        Map<String, Object> payload,
        boolean redactPayloadAfterSend,
        String deduplicationKey,
        OutboxStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        String lockedBy,
        Instant lockedUntil,
        Instant sentAt) {
}
