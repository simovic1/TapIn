package com.tapin;

import org.springframework.boot.SpringApplication;

/**
 * Runs the backend locally with a throw-away PostgreSQL container instead of docker-compose:
 * {@code ./mvnw spring-boot:test-run}.
 */
public class TestTapInApplication {

    public static void main(String[] args) {
        SpringApplication.from(TapInApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
