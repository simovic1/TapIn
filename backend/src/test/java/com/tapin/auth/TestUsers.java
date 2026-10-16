package com.tapin.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/** Creates users with a membership and roles directly in the database for auth tests. */
public final class TestUsers {

    public static final String PASSWORD = "test-password-123";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public TestUsers(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public record Created(long userId, long membershipId, String email) {
    }

    public Created create(String email, String accountStatus, String... roles) {
        long userId = jdbc.queryForObject("""
                INSERT INTO users (email, first_name, last_name, account_status, password_hash)
                VALUES (?, 'Test', 'Korisnik', ?, ?) RETURNING id""", Long.class,
                email, accountStatus, passwordEncoder.encode(PASSWORD));
        long membershipId = jdbc.queryForObject("""
                INSERT INTO club_memberships (club_id, user_id, joined_on, status)
                VALUES ((SELECT id FROM clubs), ?, DATE '2024-03-01', 'ACTIVE') RETURNING id""", Long.class, userId);
        setRoles(membershipId, roles);
        return new Created(userId, membershipId, email);
    }

    public Created active(String email, String... roles) {
        return create(email, "ACTIVE", roles);
    }

    public void setRoles(long membershipId, String... roles) {
        jdbc.update("DELETE FROM membership_roles WHERE membership_id = ?", membershipId);
        for (String role : List.of(roles)) {
            jdbc.update("INSERT INTO membership_roles (membership_id, role) VALUES (?, ?)", membershipId, role);
        }
    }
}
