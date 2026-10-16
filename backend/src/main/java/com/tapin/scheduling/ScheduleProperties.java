package com.tapin.scheduling;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param horizonDays          how many days ahead sessions are materialised from rules (spec §4.2: ~60)
 * @param materializeInterval  how often the materialisation job runs (it is also run after every rule change)
 * @param heldTransitionInterval how often ended sessions are moved from SCHEDULED to HELD
 */
@Validated
@ConfigurationProperties(prefix = "tapin.schedule")
record ScheduleProperties(
        @DefaultValue("60") @Min(1) @Max(366) int horizonDays,
        @DefaultValue("6h") Duration materializeInterval,
        @DefaultValue("1m") Duration heldTransitionInterval) {
}
