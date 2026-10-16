package com.tapin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code tapin.invitation.*}
 *
 * @param validity                  how long an invitation link works (spec §3.2: 7 days)
 * @param maxInvalidTokensPerIp     invalid preview/accept attempts per IP within {@code window} before 429
 */
@ConfigurationProperties(prefix = "tapin.invitation")
public record InvitationProperties(
        @DefaultValue("7d") Duration validity,
        @DefaultValue("20") int maxInvalidTokensPerIp,
        @DefaultValue("15m") Duration window) {
}
