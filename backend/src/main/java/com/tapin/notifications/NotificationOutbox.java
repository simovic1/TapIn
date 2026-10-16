package com.tapin.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Public API of the notifications module for other modules.
 * <p>
 * {@link #enqueue} must be called inside the business transaction that causes the notification
 * ({@code Propagation.MANDATORY} enforces it): if that transaction rolls back, the outbox row disappears with it;
 * once it commits, the row is durable and the e-mail is sent asynchronously by {@link OutboxWorker}, so a provider
 * outage can never undo the business change (spec §8.3, AC-15).
 */
@Service
public class NotificationOutbox {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutbox.class);

    private final OutboxRepository repository;
    private final Clock clock;

    NotificationOutbox(OutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Writes the message to the outbox in the caller's transaction. Idempotent per
     * {@link OutboxMessage#deduplicationKey()}: a repeated business event returns {@link EnqueueResult#DUPLICATE}
     * and writes nothing (no second e-mail).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EnqueueResult enqueue(OutboxMessage message) {
        boolean inserted = repository.insertIfAbsent(message, clock.instant());
        if (!inserted) {
            log.debug("Outbox: duplicate event ignored, key={}", message.deduplicationKey());
        }
        return inserted ? EnqueueResult.QUEUED : EnqueueResult.DUPLICATE;
    }

    /**
     * Puts a FAILED message back into the queue with a fresh attempt budget (operator action after fixing the cause).
     * Messages whose secret payload was already wiped cannot be re-sent; the business action must be repeated
     * (e.g. resend the invitation).
     *
     * @return true if the message was re-queued
     */
    @Transactional
    public boolean retryFailed(long outboxId) {
        boolean requeued = repository.requeueFailed(outboxId, clock.instant());
        log.info("Outbox: manual retry of id={} {}", outboxId, requeued ? "queued" : "not possible");
        return requeued;
    }
}
