package com.tapin.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** UTC clock; inject it instead of calling {@code Instant.now()} so time-dependent rules stay testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
