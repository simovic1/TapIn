package com.tapin.members;

import com.tapin.auth.AccountStatus;
import com.tapin.auth.UserAccountService;
import com.tapin.auth.UserAccountService.UserRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Ensures the club has at least one active administrator (spec §16 Faza 1 "admin seed").
 * <ul>
 *   <li>Does nothing if an active administrator already exists - configured credentials are then ignored,
 *       so a restart never resets anyone's password.</li>
 *   <li>Otherwise creates the configured account, or grants ADMIN to an existing account with that e-mail
 *       (without touching its password).</li>
 *   <li>Fails startup if a new account is needed but the password is missing or too short.</li>
 * </ul>
 * A PostgreSQL advisory lock serialises concurrent starts of several instances.
 */
@Service
public class AdminBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);
    private static final long ADVISORY_LOCK_KEY = 0x7A91_0001L;

    public enum Result { CREATED, PROMOTED_EXISTING_USER, SKIPPED_ADMIN_EXISTS, NOT_CONFIGURED }

    private final ClubRepository clubs;
    private final ClubMembershipRepository memberships;
    private final MembershipStatusEventRepository statusEvents;
    private final UserAccountService userAccounts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    AdminBootstrapService(ClubRepository clubs, ClubMembershipRepository memberships,
                          MembershipStatusEventRepository statusEvents, UserAccountService userAccounts,
                          JdbcTemplate jdbc, Clock clock) {
        this.clubs = clubs;
        this.memberships = memberships;
        this.statusEvents = statusEvents;
        this.userAccounts = userAccounts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Result bootstrap(AdminBootstrapProperties config) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");

        if (hasActiveAdmin()) {
            log.debug("Admin bootstrap skipped: an active administrator exists");
            return Result.SKIPPED_ADMIN_EXISTS;
        }
        if (!config.isConfigured()) {
            log.warn("No active administrator exists. Set ADMIN_EMAIL and ADMIN_PASSWORD to create one on startup.");
            return Result.NOT_CONFIGURED;
        }

        Club club = theClub();
        LocalDate today = LocalDate.now(clock.withZone(club.getTimeZone()));
        var existing = userAccounts.findByEmail(config.email());

        if (existing.isPresent()) {
            promote(club, existing.get(), today);
            log.info("Admin bootstrap: granted ADMIN to existing user id={}", existing.get().userId());
            return Result.PROMOTED_EXISTING_USER;
        }

        validatePassword(config.password());
        UserRef user = userAccounts.createActiveUser(config.email(), config.firstName(), config.lastName(),
                config.password());
        ClubMembership membership = memberships.save(
                ClubMembership.active(club, user.userId(), today, Set.of(Role.ADMIN)));
        recordStatus(membership, null);
        log.info("Admin bootstrap: created administrator user id={} membership id={}", user.userId(),
                membership.getId());
        return Result.CREATED;
    }

    private boolean hasActiveAdmin() {
        List<ClubMembership> admins = memberships.findActiveAdmins();
        return admins.stream()
                .map(m -> userAccounts.findById(m.getUserId()))
                .anyMatch(u -> u.isPresent() && u.get().accountStatus() == AccountStatus.ACTIVE);
    }

    private void promote(Club club, UserRef user, LocalDate today) {
        ClubMembership membership = memberships.findByClubIdAndUserId(club.getId(), user.userId())
                .orElseGet(() -> {
                    ClubMembership created = memberships.save(
                            ClubMembership.active(club, user.userId(), today, Set.of(Role.ADMIN)));
                    recordStatus(created, null);
                    return created;
                });
        membership.grant(Role.ADMIN);
        MembershipStatus previous = membership.activate();
        if (previous != MembershipStatus.ACTIVE) {
            recordStatus(membership, previous);
        }
        if (user.accountStatus() != AccountStatus.ACTIVE) {
            log.warn("Admin bootstrap: user id={} has account status {}; it must be activated before login",
                    user.userId(), user.accountStatus());
        }
    }

    private void recordStatus(ClubMembership membership, MembershipStatus previous) {
        statusEvents.save(new MembershipStatusEvent(membership.getId(), previous, MembershipStatus.ACTIVE,
                Instant.now(clock), null));
    }

    private Club theClub() {
        List<Club> all = clubs.findAll();
        if (all.size() != 1) {
            throw new IllegalStateException("Expected exactly one club, found " + all.size());
        }
        return all.getFirst();
    }

    private static void validatePassword(String password) {
        if (password == null || password.strip().length() < AdminBootstrapProperties.MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD must be set and at least "
                    + AdminBootstrapProperties.MIN_PASSWORD_LENGTH + " characters long to create the first administrator");
        }
    }
}
