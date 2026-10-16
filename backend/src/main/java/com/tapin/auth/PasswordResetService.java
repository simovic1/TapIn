package com.tapin.auth;

import com.tapin.notifications.*;
import com.tapin.shared.config.AppUrlProperties;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@Service
class PasswordResetService {
    private final JdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final NotificationOutbox outbox;
    private final AppUrlProperties appUrl;
    private final Clock clock;

    PasswordResetService(JdbcTemplate jdbc, UserAccountRepository users, PasswordEncoder passwords,
                         NotificationOutbox outbox, AppUrlProperties appUrl, Clock clock) {
        this.jdbc = jdbc; this.users = users; this.passwords = passwords;
        this.outbox = outbox; this.appUrl = appUrl; this.clock = clock;
    }

    @Transactional
    public void request(String email) {
        users.findByEmailIgnoreCase(email.strip()).filter(u -> u.getAccountStatus() == AccountStatus.ACTIVE)
                .ifPresent(user -> {
                    // Serialize issuance and confirmation for this account, including concurrent re-sends.
                    var locked = jdbc.queryForMap("SELECT email, first_name, account_status FROM users WHERE id = ? FOR UPDATE", user.getId());
                    if (!"ACTIVE".equals(locked.get("account_status"))
                            || !email.strip().equalsIgnoreCase((String) locked.get("email"))) return;
                    var now = clock.instant();
                    jdbc.update("UPDATE password_reset_tokens SET revoked_at = ? WHERE user_id = ? "
                            + "AND consumed_at IS NULL AND revoked_at IS NULL", Timestamp.from(now), user.getId());
                    String token = InvitationTokens.generate();
                    var expires = now.plus(Duration.ofMinutes(30));
                    Long id = jdbc.queryForObject("INSERT INTO password_reset_tokens "
                                    + "(user_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?) RETURNING id",
                            Long.class, user.getId(), InvitationTokens.hash(token), Timestamp.from(now), Timestamp.from(expires));
                    outbox.enqueue(OutboxMessage.of(EmailEventType.PASSWORD_RESET, user.getId(), (String) locked.get("email"),
                            Map.of("firstName", locked.get("first_name"), "resetUrl", appUrl.link("/nova-lozinka#" + token),
                                    "expiresAt", expires.atZone(java.time.ZoneId.of("Europe/Zagreb")).toString()),
                            "PASSWORD_RESET:token-" + id).withSecret());
                });
    }

    @Transactional
    public void confirm(String token, String password) {
        PasswordPolicy.validate(password);
        String hash = InvitationTokens.hash(token);
        var ids = jdbc.queryForList("SELECT user_id FROM password_reset_tokens WHERE token_hash = ?", Long.class, hash);
        if (ids.isEmpty()) throw invalid();
        long userId = ids.getFirst();
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
        var now = Timestamp.from(clock.instant());
        int consumed = jdbc.update("UPDATE password_reset_tokens SET consumed_at = ? WHERE token_hash = ? "
                + "AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ? "
                + "AND EXISTS (SELECT 1 FROM users WHERE id = user_id AND account_status = 'ACTIVE')", now, hash, now);
        if (consumed != 1) throw invalid();
        jdbc.update("UPDATE users SET password_hash = ?, credentials_version = credentials_version + 1, "
                + "version = version + 1, updated_at = ? WHERE id = ?", passwords.encode(password), now, userId);
        jdbc.update("UPDATE password_reset_tokens SET revoked_at = ? WHERE user_id = ? "
                + "AND consumed_at IS NULL AND revoked_at IS NULL", now, userId);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID,
                "Poveznica nije valjana ili je istekla. Zatražite novu promjenu lozinke.");
    }
}
