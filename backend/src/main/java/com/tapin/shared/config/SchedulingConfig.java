package com.tapin.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables in-process scheduled jobs (outbox worker, later reminders/materialisation). Off in tests. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "tapin.scheduling.enabled", matchIfMissing = true)
class SchedulingConfig {
}
