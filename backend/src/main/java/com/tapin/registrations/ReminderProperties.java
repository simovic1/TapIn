package com.tapin.registrations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Training reminder (spec §8.2, decision D-04).
 *
 * @param enabled  switch for the whole application (each member can also switch it off on the profile)
 * @param lead     how long before the start the reminder is due ({@code start_at - 3h})
 * @param interval how often the job looks for due reminders; a missed run is caught up until the training starts
 */
@ConfigurationProperties(prefix = "tapin.reminders")
record ReminderProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3h") Duration lead,
        @DefaultValue("1m") Duration interval) {
}
