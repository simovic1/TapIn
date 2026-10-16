package com.tapin.notifications;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyAndRendererTest {

    private final OutboxProperties properties = new OutboxProperties(Duration.ofSeconds(10), 20, Duration.ofMinutes(2),
            8, Duration.ofMinutes(1), Duration.ofHours(1));

    @Test
    void backoffDoublesAndIsCappedWithoutJitter() {
        RetryPolicy policy = new RetryPolicy(properties, () -> 0.5); // 0.5 -> jitter factor 1.0

        assertThat(policy.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.backoff(7)).isEqualTo(Duration.ofHours(1));
        assertThat(policy.backoff(40)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void jitterStaysWithinTenPercentAndNeverExceedsTheCap() {
        assertThat(new RetryPolicy(properties, () -> 0.0).backoff(1)).isEqualTo(Duration.ofSeconds(54));
        assertThat(new RetryPolicy(properties, () -> 1.0).backoff(1)).isEqualTo(Duration.ofSeconds(66));
        assertThat(new RetryPolicy(properties, () -> 1.0).backoff(10)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void exhaustionFollowsMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(properties, () -> 0.5);
        assertThat(policy.isExhausted(7)).isFalse();
        assertThat(policy.isExhausted(8)).isTrue();
    }

    @Test
    void rendererFillsCroatianTemplateAndRejectsMissingValues() {
        EmailTemplateRenderer renderer = new EmailTemplateRenderer();

        EmailTemplateRenderer.Rendered rendered = renderer.render(EmailEventType.ATTENDANCE_RECORDED,
                Map.of("firstName", "Ana", "trainingTime", "utorak 20. 10. 2026. u 18:30", "attendanceStatus", "prisutan"));

        assertThat(rendered.subject()).isEqualTo("Evidencija dolaska: Trening, utorak 20. 10. 2026. u 18:30");
        assertThat(rendered.body()).contains("Bok Ana").contains("prisutan").contains("automatski poslana");
        assertThatThrownBy(() -> renderer.render(EmailEventType.TRAINING_REMINDER, Map.of("firstName", "Ana")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trainingTime");
    }
}
