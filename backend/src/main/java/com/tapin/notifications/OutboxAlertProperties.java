package com.tapin.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Alert on long-lasting delivery problems ({@code tapin.outbox.alert.*}, BJJ-30).
 *
 * @param after    a PENDING message older than this means sending has been failing (or not running) for too long;
 *                 the default 30 min is about five failed attempts of the retry schedule
 * @param interval how often the queue is checked
 * @param repeat   how often the alert is logged again while the problem lasts
 */
@ConfigurationProperties(prefix = "tapin.outbox.alert")
public record OutboxAlertProperties(
        @DefaultValue("30m") Duration after,
        @DefaultValue("1m") Duration interval,
        @DefaultValue("1h") Duration repeat) {
}
