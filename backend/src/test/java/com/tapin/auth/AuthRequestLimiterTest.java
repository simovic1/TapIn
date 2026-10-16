package com.tapin.auth;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class AuthRequestLimiterTest {
    @Test void limitsTargetAndIpIndependentlyAndSeparatesOperations() {
        var limiter = new AuthRequestLimiter(Clock.systemUTC());
        for (int i = 0; i < 5; i++) limiter.acquire("invitation-send", "ip-" + i, "actor:1");
        assertThatThrownBy(() -> limiter.acquire("invitation-send", "different-ip", "actor:1"))
                .isInstanceOf(com.tapin.shared.error.RateLimitedException.class);
        for (int i = 0; i < 20; i++) limiter.acquire("reset-request", "same-ip", "email-" + i);
        assertThatThrownBy(() -> limiter.acquire("reset-request", "same-ip", "another-email"))
                .isInstanceOf(com.tapin.shared.error.RateLimitedException.class);
        limiter.acquire("reset-confirm", "same-ip", "token");
    }
}
