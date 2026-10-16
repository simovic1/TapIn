package com.tapin.members;

import com.tapin.TestcontainersConfiguration;
import com.tapin.members.AdminBootstrapService.Result;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AdminBootstrapIntegrationTest {

    private static final String PASSWORD = "a-strong-test-password";

    @Autowired
    AdminBootstrapService bootstrap;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void withoutConfigurationNothingIsCreated() {
        assertThat(bootstrap.bootstrap(new AdminBootstrapProperties("", "", "A", "B"))).isEqualTo(Result.NOT_CONFIGURED);
        assertThat(count("users")).isZero();
    }

    @Test
    void createsActiveAdministratorWithHashedPassword() {
        Result result = run(config("admin@klub.hr", PASSWORD));

        assertThat(result).isEqualTo(Result.CREATED);
        Map<String, Object> user = jdbc.queryForMap("SELECT id, account_status, password_hash FROM users");
        String hash = (String) user.get("password_hash");
        assertThat(user).containsEntry("account_status", "ACTIVE");
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();

        Map<String, Object> membership = jdbc.queryForMap("""
                SELECT m.status, r.role, c.time_zone FROM club_memberships m
                JOIN membership_roles r ON r.membership_id = m.id
                JOIN clubs c ON c.id = m.club_id
                WHERE m.user_id = ?""", user.get("id"));
        assertThat(membership).containsEntry("status", "ACTIVE").containsEntry("role", "ADMIN")
                .containsEntry("time_zone", "Europe/Zagreb");
        assertThat(count("membership_status_events")).isEqualTo(1);
    }

    @Test
    void secondRunIsANoOpAndNeverResetsThePassword() {
        run(config("admin@klub.hr", PASSWORD));
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM users", String.class);

        Result second = run(config("admin@klub.hr", "another-password-123"));

        assertThat(second).isEqualTo(Result.SKIPPED_ADMIN_EXISTS);
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("club_memberships")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isEqualTo(hashBefore);
    }

    @Test
    void missingOrShortPasswordFailsWithoutLeakingIt() {
        assertThatThrownBy(() -> bootstrap.bootstrap(config("admin@klub.hr", "short")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD")
                .hasMessageNotContaining("short");
        assertThatThrownBy(() -> bootstrap.bootstrap(config("admin@klub.hr", "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void existingUserIsPromotedCaseInsensitivelyWithoutTouchingPassword() {
        jdbc.update("""
                INSERT INTO users (email, first_name, last_name, account_status, password_hash)
                VALUES ('Trener@Klub.hr', 'Ivo', 'Ivić', 'ACTIVE', '{bcrypt}existing')""");

        Result result = run(config("trener@KLUB.hr", PASSWORD));

        assertThat(result).isEqualTo(Result.PROMOTED_EXISTING_USER);
        assertThat(count("users")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isEqualTo("{bcrypt}existing");
        assertThat(jdbc.queryForList("SELECT role FROM membership_roles", String.class)).containsExactly("ADMIN");
    }

    @Test
    void propertiesNeverPrintThePassword() {
        assertThat(config("admin@klub.hr", PASSWORD).toString()).doesNotContain(PASSWORD);
    }

    /** Runs the bootstrap and flushes JPA so plain JDBC assertions in the same transaction see the rows. */
    private Result run(AdminBootstrapProperties config) {
        Result result = bootstrap.bootstrap(config);
        entityManager.flush();
        return result;
    }

    private static AdminBootstrapProperties config(String email, String password) {
        return new AdminBootstrapProperties(email, password, "Admin", "Klub");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
