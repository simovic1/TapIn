package com.tapin;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flyway migrations V001-V003 on a real PostgreSQL, plus the integrity rules they must enforce. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationIntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment environment;

    @Test
    void migrationsApplyOnEmptyDatabaseAndRerunIsANoOp() {
        assertThat(Arrays.stream(flyway.info().applied()).map(m -> m.getVersion().getVersion()))
                .containsExactly("001", "002", "003");
        assertThat(flyway.info().pending()).isEmpty();

        // Simulates the next application start against the same database.
        Flyway restart = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        restart.validate();
        MigrateResult result = restart.migrate();

        assertThat(result.migrationsExecuted).isZero();
        assertThat(result.success).isTrue();
    }

    @Test
    void hibernateOnlyValidatesTheSchema() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    @Transactional
    void exactlyOneClubExistsInZagrebTimeZone() {
        List<Map<String, Object>> clubs = jdbc.queryForList("SELECT name, time_zone FROM clubs");

        assertThat(clubs).hasSize(1);
        assertThat(clubs.getFirst()).containsEntry("time_zone", "Europe/Zagreb");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO clubs (name, time_zone) VALUES ('Drugi', 'Europe/Zagreb')"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_clubs_single_club");
    }

    @Test
    @Transactional
    void emailIsUniqueRegardlessOfCase() {
        insertUser("Ana.Horvat@Example.com");

        assertThatThrownBy(() -> insertUser("ana.horvat@EXAMPLE.COM"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_users_email_lower");
    }

    @Test
    @Transactional
    void membershipIsUniquePerClubAndUser() {
        long userId = insertUser("clan@example.com");
        insertMembership(userId);

        assertThatThrownBy(() -> insertMembership(userId))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_club_memberships_club_user");
    }

    @Test
    @Transactional
    void roleIsUniquePerMembership() {
        long membershipId = insertMembership(insertUser("trener@example.com"));
        jdbc.update("INSERT INTO membership_roles (membership_id, role) VALUES (?, 'TRAINER')", membershipId);

        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO membership_roles (membership_id, role) VALUES (?, 'TRAINER')", membershipId))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("pk_membership_roles");
    }

    private long insertUser(String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, first_name, last_name, account_status)
                VALUES (?, 'Ime', 'Prezime', 'INVITED') RETURNING id
                """, Long.class, email);
    }

    private long insertMembership(long userId) {
        return jdbc.queryForObject("""
                INSERT INTO club_memberships (club_id, user_id, joined_on, status)
                VALUES ((SELECT id FROM clubs), ?, CURRENT_DATE, 'ACTIVE') RETURNING id
                """, Long.class, userId);
    }
}
