package com.tapin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Login throttling ({@code tapin.security.login.*}): after {@code maxFailures} failed attempts for the same e-mail
 * or from the same IP within {@code window}, further attempts get 429 until the window passes.
 */
@ConfigurationProperties(prefix = "tapin.security.login")
public record LoginAttemptProperties(
        @DefaultValue("5") int maxFailuresPerEmail,
        @DefaultValue("20") int maxFailuresPerIp,
        @DefaultValue("15m") Duration window) {
}
