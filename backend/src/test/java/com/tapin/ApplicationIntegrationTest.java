package com.tapin;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the full application against a real PostgreSQL (Testcontainers) and applies all Flyway migrations. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ApplicationIntegrationTest {

    @Autowired
    Flyway flyway;

    @Test
    void contextLoadsAndMigrationsApply() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).isNotEmpty();
    }
}
