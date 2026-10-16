package com.tapin.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/**
 * Club-level settings. Business dates (training day, belt effective date) are computed in {@link #timeZone()},
 * while all instants are stored in UTC.
 */
@Validated
@ConfigurationProperties(prefix = "tapin.club")
public record ClubProperties(@NotBlank String name, @NotNull ZoneId timeZone) {
}
