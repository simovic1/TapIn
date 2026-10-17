package com.tapin.notifications;

import com.tapin.notifications.email.EmailMessage;
import com.tapin.notifications.email.EmailSender;
import com.tapin.notifications.email.SendResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    record BatchResult(int claimed, int sent, int retried, int failed, int skipped) {
    }

    private final OutboxRepository repository;
    private final EmailSender sender;
    private final EmailTemplateRenderer renderer;
    /** Null in focused tests that only exercise e-mail; PUSH rows then fail permanently. */
    private final PushDelivery pushDelivery;
    private final OutboxProperties properties;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final String workerId;
    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);

    @Autowired
    OutboxProcessor(OutboxRepository repository, EmailSender sender, EmailTemplateRenderer renderer,
                    PushDelivery pushDelivery, OutboxProperties properties, Clock clock, MeterRegistry meters) {
        this(repository, sender, renderer, pushDelivery, properties, clock, defaultWorkerId(), meters);
    }

    OutboxProcessor(OutboxRepository repository, EmailSender sender, EmailTemplateRenderer renderer,
                    OutboxProperties properties, Clock clock, String workerId) {
        this(repository, sender, renderer, null, properties, clock, workerId, new SimpleMeterRegistry());
    }

    private OutboxProcessor(OutboxRepository repository, EmailSender sender, EmailTemplateRenderer renderer,
                            PushDelivery pushDelivery, OutboxProperties properties, Clock clock, String workerId,
                            MeterRegistry meters) {
        this.repository = repository;
        this.sender = sender;
        this.renderer = renderer;
        this.pushDelivery = pushDelivery;
        this.properties = properties;
        this.retryPolicy = new RetryPolicy(properties, () -> ThreadLocalRandom.current().nextDouble());
        this.clock = clock;
        this.workerId = workerId;
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, Counter.builder("tapin.outbox.deliveries")
                    .description("Delivery attempts by outcome (SENT = accepted by the provider, not delivered)")
                    .tag("outcome", outcome.name())
                    .register(meters));
        }
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
        int skipped = 0;
        for (OutboxEntry entry : claimed) {
            Outcome outcome = deliver(entry, now);
            outcomes.get(outcome).increment();
            switch (outcome) {
                case SENT -> sent++;
                case RETRY -> retried++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case LEASE_LOST -> { }
            }
        }
        if (!claimed.isEmpty()) {
            log.info("Outbox batch by {}: claimed={} sent={} retried={} failed={} skipped={}", workerId, claimed.size(), sent,
                    retried, failed, skipped);
        }
        return new BatchResult(claimed.size(), sent, retried, failed, skipped);
    }

    private enum Outcome { SENT, RETRY, FAILED, SKIPPED, LEASE_LOST }

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
            result = entry.channel() == OutboxChannel.PUSH ? deliverPush(entry, now) : deliverEmail(entry);
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
            case SendResult.Skipped skipped -> {
                recorded = repository.markSkipped(entry.id(), workerId, now);
                outcome = Outcome.SKIPPED;
                log.debug("Outbox id={} {} push skipped: {}", entry.id(), entry.eventType(), skipped.reason());
            }
            case SendResult.PermanentFailure permanent -> {
                recorded = repository.markFailed(entry.id(), workerId, now, permanent.reason());
                outcome = Outcome.FAILED;
                log.warn("Outbox id={} {} FAILED permanently: {}", entry.id(), entry.eventType(), permanent.reason());
            }
            case SendResult.TransientFailure transientFailure -> {
                String channelName = entry.channel() == OutboxChannel.PUSH ? "push" : sender.name();
                String error = "[" + channelName + (transientFailure.ambiguous() ? ", ambiguous" : "") + "] "
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

    private SendResult deliverEmail(OutboxEntry entry) {
        EmailTemplateRenderer.Rendered rendered = renderer.render(entry.eventType(), entry.payload());
        return sender.send(new EmailMessage(entry.recipientEmail(), rendered.subject(), rendered.body(),
                messageId(entry), entry.deduplicationKey()));
    }

    private SendResult deliverPush(OutboxEntry entry, Instant now) {
        if (pushDelivery == null) {
            return new SendResult.PermanentFailure("Push delivery is not available");
        }
        return pushDelivery.deliver(entry, now);
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
