package com.tapin.notifications;

import com.tapin.notifications.email.EmailMessage;
import com.tapin.notifications.email.EmailSender;
import com.tapin.notifications.email.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drains due outbox rows: claim (short DB statement) -> send via the provider (no DB transaction held) ->
 * record the outcome with a lease-conditional update.
 * <p>
 * Delivery is <b>at-least-once</b>. A message can be delivered twice when the provider accepted it but we did not
 * learn about it (ambiguous error/timeout), or when sending outlived the lease and another worker re-claimed it.
 * Duplicates are reduced by the stable Message-ID / provider idempotency key, never ruled out.
 */
@Component
class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    record BatchResult(int claimed, int sent, int retried, int failed) {
    }

    private final OutboxRepository repository;
    private final EmailSender sender;
    private final EmailTemplateRenderer renderer;
    private final OutboxProperties properties;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final String workerId;

    @Autowired
    OutboxProcessor(OutboxRepository repository, EmailSender sender, EmailTemplateRenderer renderer,
                    OutboxProperties properties, Clock clock) {
        this(repository, sender, renderer, properties, clock, defaultWorkerId());
    }

    OutboxProcessor(OutboxRepository repository, EmailSender sender, EmailTemplateRenderer renderer,
                    OutboxProperties properties, Clock clock, String workerId) {
        this.repository = repository;
        this.sender = sender;
        this.renderer = renderer;
        this.properties = properties;
        this.retryPolicy = new RetryPolicy(properties, () -> ThreadLocalRandom.current().nextDouble());
        this.clock = clock;
        this.workerId = workerId;
    }

    BatchResult processDue() {
        return processDue(clock.instant());
    }

    BatchResult processDue(Instant now) {
        List<OutboxEntry> claimed = repository.claimDue(workerId, now, now.plus(properties.lease()),
                properties.batchSize());
        int sent = 0;
        int retried = 0;
        int failed = 0;
        for (OutboxEntry entry : claimed) {
            switch (deliver(entry, now)) {
                case SENT -> sent++;
                case RETRY -> retried++;
                case FAILED -> failed++;
                case LEASE_LOST -> { }
            }
        }
        if (!claimed.isEmpty()) {
            log.info("Outbox batch by {}: claimed={} sent={} retried={} failed={}", workerId, claimed.size(), sent,
                    retried, failed);
        }
        return new BatchResult(claimed.size(), sent, retried, failed);
    }

    private enum Outcome { SENT, RETRY, FAILED, LEASE_LOST }

    private Outcome deliver(OutboxEntry entry, Instant now) {
        if (entry.expiresAt() != null && !now.isBefore(entry.expiresAt())) {
            // Decision D-04: a message that missed its moment (reminder after the training started) is not sent.
            boolean recorded = repository.markFailed(entry.id(), workerId, now,
                    "Stale: not delivered before " + entry.expiresAt() + "; not sent any more.");
            log.info("Outbox id={} {} expired at {}, not sent", entry.id(), entry.eventType(), entry.expiresAt());
            return recorded ? Outcome.FAILED : Outcome.LEASE_LOST;
        }
        SendResult result;
        try {
            EmailTemplateRenderer.Rendered rendered = renderer.render(entry.eventType(), entry.payload());
            result = sender.send(new EmailMessage(entry.recipientEmail(), rendered.subject(), rendered.body(),
                    messageId(entry), entry.deduplicationKey()));
        } catch (IllegalArgumentException e) {
            result = new SendResult.PermanentFailure("Cannot render message: " + e.getMessage());
        } catch (RuntimeException e) {
            // Unexpected adapter error: we cannot know whether the provider got the message.
            result = new SendResult.TransientFailure("Unexpected error: " + e, true);
        }

        boolean recorded;
        Outcome outcome;
        switch (result) {
            case SendResult.Accepted accepted -> {
                recorded = repository.markSent(entry.id(), workerId, now, accepted.providerMessageId());
                outcome = Outcome.SENT;
            }
            case SendResult.PermanentFailure permanent -> {
                recorded = repository.markFailed(entry.id(), workerId, now, permanent.reason());
                outcome = Outcome.FAILED;
                log.warn("Outbox id={} {} FAILED permanently: {}", entry.id(), entry.eventType(), permanent.reason());
            }
            case SendResult.TransientFailure transientFailure -> {
                String error = "[" + sender.name() + (transientFailure.ambiguous() ? ", ambiguous" : "") + "] "
                        + transientFailure.reason();
                if (retryPolicy.isExhausted(entry.attemptCount())) {
                    recorded = repository.markFailed(entry.id(), workerId, now,
                            "Retries exhausted after " + entry.attemptCount() + " attempts. Last error: " + error);
                    outcome = Outcome.FAILED;
                    log.warn("Outbox id={} {} FAILED after {} attempts: {}", entry.id(), entry.eventType(),
                            entry.attemptCount(), error);
                } else {
                    Instant next = retryPolicy.nextAttemptAt(entry.attemptCount(), now);
                    recorded = repository.reschedule(entry.id(), workerId, now, next, error);
                    outcome = Outcome.RETRY;
                    log.info("Outbox id={} {} attempt {} failed, retry at {}: {}", entry.id(), entry.eventType(),
                            entry.attemptCount(), next, error);
                }
            }
        }
        if (!recorded) {
            log.warn("Outbox id={}: lease lost before the result could be recorded (worker {}); "
                    + "another worker may deliver it again", entry.id(), workerId);
            return Outcome.LEASE_LOST;
        }
        return outcome;
    }

    /** Stable across attempts of the same row, unique per row. */
    static String messageId(OutboxEntry entry) {
        return "<tapin-outbox-" + entry.id() + "@tapin.local>";
    }

    String workerId() {
        return workerId;
    }

    int batchSize() {
        return properties.batchSize();
    }

    private static String defaultWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
