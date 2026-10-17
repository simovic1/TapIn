package com.tapin.notifications;

import com.tapin.notifications.push.PushSender;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain SQL access to {@code push_subscriptions}. */
@Repository
class PushSubscriptionRepository {

    record Row(long id, PushSender.Target target) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    PushSubscriptionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert, or refresh keys and move the endpoint to {@code userId} (another person on the same device). */
    void upsert(long userId, String endpoint, String p256dh, String auth, String userAgent, Instant now) {
        jdbc.update("""
                INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, user_agent, created_at, updated_at)
                VALUES (:userId, :endpoint, :p256dh, :auth, :userAgent, :now, :now)
                ON CONFLICT (endpoint) DO UPDATE
                SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth,
                    user_agent = EXCLUDED.user_agent, updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource("userId", userId)
                .addValue("endpoint", endpoint)
                .addValue("p256dh", p256dh)
                .addValue("auth", auth)
                .addValue("userAgent", userAgent)
                .addValue("now", Timestamp.from(now)));
    }

    int delete(long userId, String endpoint) {
        return jdbc.update("DELETE FROM push_subscriptions WHERE user_id = :userId AND endpoint = :endpoint",
                new MapSqlParameterSource("userId", userId).addValue("endpoint", endpoint));
    }

    void deleteById(long id) {
        jdbc.update("DELETE FROM push_subscriptions WHERE id = :id", Map.of("id", id));
    }

    List<Row> findByUser(long userId) {
        return jdbc.query("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = :userId ORDER BY id",
                Map.of("userId", userId), (rs, n) -> new Row(rs.getLong("id"),
                        new PushSender.Target(rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth"))));
    }

    Set<Long> usersWithSubscription(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT user_id FROM push_subscriptions WHERE user_id IN (:ids)",
                Map.of("ids", userIds), Long.class));
    }
}
