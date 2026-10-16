package com.tapin.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Outbox worker tuning ({@code tapin.outbox.*}).
 *
 * @param lease          how long a claimed row is reserved for one worker; must comfortably exceed the provider
 *                       timeout, otherwise another worker may re-send a message that is still in flight
 * @param maxAttempts    attempts before a transient failure becomes FAILED
 * @param initialBackoff delay after the first failed attempt; doubled per attempt up to {@code maxBackoff}
 */
@ConfigurationProperties(prefix = "tapin.outbox")
public record OutboxProperties(
        @DefaultValue("10s") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("1m") Duration initialBackoff,
        @DefaultValue("1h") Duration maxBackoff) {

    public OutboxProperties {
        if (batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("tapin.outbox.batch-size and max-attempts must be >= 1");
        }
    }
}
