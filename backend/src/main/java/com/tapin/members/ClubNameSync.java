package com.tapin.members;

import com.tapin.shared.config.ClubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code tapin.club.name} ({@code CLUB_NAME}) is the one place to name the club. The single club row seeded by V003
 * takes that name at startup, so the header, e-mails and {@code /system/info} always agree. Never rename the club by
 * editing an applied migration: Flyway then refuses to start (checksum mismatch).
 */
@Component
class ClubNameSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClubNameSync.class);

    private final ClubProperties club;
    private final JdbcTemplate jdbc;

    ClubNameSync(ClubProperties club, JdbcTemplate jdbc) {
        this.club = club;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        int renamed = jdbc.update("UPDATE clubs SET name = ?, updated_at = now() WHERE name <> ?", club.name(), club.name());
        if (renamed > 0) {
            log.info("Club renamed to '{}' (tapin.club.name)", club.name());
        }
    }
}
