package com.tapin.notifications;

import com.tapin.notifications.OutboxRepository.OperatorRow;
import com.tapin.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Operator view of the e-mail queue (BJJ-30, ADMIN only): counts, the alert state, failed and waiting messages with
 * their attempts and errors, and the safe manual retry. Never exposes the payload (invitation and reset links) or
 * anything else that could carry a token or a password; errors are sanitized ({@link OutboxErrors}).
 * <p>
 * {@code SENT} means <b>accepted by the provider</b>: we have no delivery receipts, so the API calls the timestamp
 * {@code acceptedByProviderAt} and never "delivered".
 */
@Service
class OutboxOperations {

    record Alert(boolean degraded, Instant degradedSince, long stuckPending, long alertAfterMinutes,
                 String latestError) {
    }

    record Item(long outboxId, EmailEventType eventType, OutboxStatus status, long recipientUserId,
                String recipientEmail, Long trainingId, String deduplicationKey, int attemptCount, int maxAttempts,
                Instant createdAt, Instant lastAttemptAt, Instant nextAttemptAt, String lastError,
                Instant acceptedByProviderAt, String providerMessageId, Instant expiresAt, boolean retryable) {
    }

    record PageMetadata(int page, int size, long totalElements, int totalPages) {
    }

    record Overview(Instant checkedAt, Map<OutboxStatus, Long> counts, Long oldestPendingAgeSeconds,
                    long failedLast24Hours, Alert alert, List<Item> items, PageMetadata page) {
    }

    record RetryResult(long outboxId, OutboxStatus status) {
    }

    private final OutboxRepository repository;
    private final OutboxMonitor monitor;
    private final NotificationOutbox outbox;
    private final OutboxProperties properties;
    private final OutboxAlertProperties alertProperties;
    private final Clock clock;

    OutboxOperations(OutboxRepository repository, OutboxMonitor monitor, NotificationOutbox outbox,
                     OutboxProperties properties, OutboxAlertProperties alertProperties, Clock clock) {
        this.repository = repository;
        this.monitor = monitor;
        this.outbox = outbox;
        this.properties = properties;
        this.alertProperties = alertProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Overview overview(Optional<OutboxStatus> status, int page, int size) {
        Instant now = clock.instant();
        OutboxMonitor.State state = monitor.check();
        long total = repository.countForOperator(status);
        List<Item> items = repository.findForOperator(status, page, size, now).stream().map(this::item).toList();
        Long oldestPendingAge = repository.oldestPendingCreatedAt()
                .map(created -> Math.max(0, Duration.between(created, now).toSeconds())).orElse(null);
        Alert alert = new Alert(state.degraded(), state.degradedSince(), state.stuckPending(),
                alertProperties.after().toMinutes(), state.latestError());
        return new Overview(now, repository.countByStatus(), oldestPendingAge,
                repository.failedSince(now.minus(Duration.ofHours(24))), alert, items,
                new PageMetadata(page, size, total, (int) ((total + size - 1) / size)));
    }

    /**
     * Safe manual retry: only a FAILED message whose payload still exists and which has not expired goes back to
     * PENDING (with a fresh attempt budget, same deduplication key and Message-ID). Idempotent for an operator double
     * click: the second call finds it PENDING and is rejected without a second queue entry.
     */
    RetryResult retry(long outboxId) {
        OutboxEntry entry = repository.findById(outboxId)
                .orElseThrow(() -> ApiException.notFound("E-mail poruka ne postoji."));
        if (entry.status() != OutboxStatus.FAILED) {
            throw ApiException.businessRule("Ponovno se može poslati samo poruka u stanju FAILED (trenutačno "
                    + entry.status() + ").");
        }
        if (!outbox.retryFailed(outboxId)) {
            throw ApiException.businessRule(entry.redactPayloadAfterSend()
                    ? "Poruka s tajnom poveznicom ne čuva sadržaj; ponovite poslovnu radnju (npr. ponovno pošaljite pozivnicu)."
                    : "Poruka je istekla (npr. podsjetnik nakon početka treninga) i više se ne šalje.");
        }
        return new RetryResult(outboxId, OutboxStatus.PENDING);
    }

    private Item item(OperatorRow row) {
        return new Item(row.id(), row.eventType(), row.status(), row.recipientUserId(), row.recipientEmail(),
                row.sessionId(), row.deduplicationKey(), row.attemptCount(), properties.maxAttempts(),
                row.createdAt(), row.lastAttemptAt(), row.nextAttemptAt(), row.lastError(), row.sentAt(),
                row.providerMessageId(), row.expiresAt(), row.retryable());
    }
}
