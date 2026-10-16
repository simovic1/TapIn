package com.tapin.registrations;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plain SQL access to {@code training_registrations} and {@code registration_events}. Callers hold the
 * {@link ParticipationLock} for (session, membership) before reading and changing a registration.
 */
@Repository
class RegistrationRepository {

    record Registration(long id, long trainingId, long membershipId, RegistrationStatus status, Instant registeredAt,
                        Instant cancelledAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<Registration> mapper = this::map;

    RegistrationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Registration> find(long trainingId, long membershipId) {
        return jdbc.query(
                "SELECT * FROM training_registrations WHERE session_id = :session AND membership_id = :membership",
                params(trainingId, membershipId), mapper).stream().findFirst();
    }

    /** @return the new registration id, or empty if one already exists (unique key) */
    Optional<Long> insertRegistered(long trainingId, long clubId, long membershipId, Instant now) {
        return jdbc.queryForList("""
                INSERT INTO training_registrations (session_id, club_id, membership_id, status, registered_at,
                                                    created_at, updated_at)
                VALUES (:session, :club, :membership, 'REGISTERED', :now, :now, :now)
                ON CONFLICT (session_id, membership_id) DO NOTHING
                RETURNING id
                """, params(trainingId, membershipId).addValue("club", clubId).addValue("now", ts(now)), Long.class)
                .stream().findFirst();
    }

    void reRegister(long id, Instant now) {
        jdbc.update("""
                UPDATE training_registrations SET status = 'REGISTERED', registered_at = :now, cancelled_at = NULL,
                       updated_at = :now, version = version + 1
                WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("now", ts(now)));
    }

    void cancel(long id, Instant now) {
        jdbc.update("""
                UPDATE training_registrations SET status = 'CANCELLED', cancelled_at = :now, updated_at = :now,
                       version = version + 1
                WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("now", ts(now)));
    }

    long insertEvent(long registrationId, String eventType, Long actorUserId, String source, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO registration_events (registration_id, event_type, actor_user_id, source, occurred_at)
                VALUES (:registration, :type, :actor, :source, :now)
                RETURNING id
                """, new MapSqlParameterSource("registration", registrationId).addValue("type", eventType)
                .addValue("actor", actorUserId).addValue("source", source).addValue("now", ts(now)), Long.class);
    }

    List<Long> activeMemberships(long trainingId) {
        return jdbc.queryForList("""
                SELECT membership_id FROM training_registrations
                WHERE session_id = :session AND status = 'REGISTERED' ORDER BY registered_at, id
                """, new MapSqlParameterSource("session", trainingId), Long.class);
    }

    Map<Long, Integer> activeCounts(Collection<Long> trainingIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (!trainingIds.isEmpty()) {
            jdbc.query("""
                    SELECT session_id, count(*) AS n FROM training_registrations
                    WHERE session_id IN (:ids) AND status = 'REGISTERED' GROUP BY session_id
                    """, new MapSqlParameterSource("ids", trainingIds),
                    (ResultSet rs) -> {
                        counts.put(rs.getLong("session_id"), rs.getInt("n"));
                    });
        }
        return counts;
    }

    Map<Long, RegistrationStatus> statusesBySession(long trainingId) {
        Map<Long, RegistrationStatus> statuses = new HashMap<>();
        jdbc.query("SELECT membership_id, status FROM training_registrations WHERE session_id = :session",
                new MapSqlParameterSource("session", trainingId),
                (ResultSet rs) -> {
                    statuses.put(rs.getLong("membership_id"), RegistrationStatus.valueOf(rs.getString("status")));
                });
        return statuses;
    }

    Map<Long, RegistrationStatus> statusesOf(long membershipId, Collection<Long> trainingIds) {
        Map<Long, RegistrationStatus> statuses = new HashMap<>();
        if (!trainingIds.isEmpty()) {
            jdbc.query("""
                    SELECT session_id, status FROM training_registrations
                    WHERE membership_id = :membership AND session_id IN (:ids)
                    """, new MapSqlParameterSource("membership", membershipId).addValue("ids", trainingIds),
                    (ResultSet rs) -> {
                        statuses.put(rs.getLong("session_id"), RegistrationStatus.valueOf(rs.getString("status")));
                    });
        }
        return statuses;
    }

    private Registration map(ResultSet rs, int row) throws SQLException {
        return new Registration(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("membership_id"),
                RegistrationStatus.valueOf(rs.getString("status")), instant(rs, "registered_at"),
                instant(rs, "cancelled_at"));
    }

    private static MapSqlParameterSource params(long trainingId, long membershipId) {
        return new MapSqlParameterSource("session", trainingId).addValue("membership", membershipId);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
