package com.tapin.attendance;

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
 * Plain SQL access to {@code training_attendances} and {@code attendance_audit_logs}. Writes are conditional on the
 * expected version, so even without the participation lock a stale write can never overwrite a newer one.
 */
@Repository
class AttendanceRepository {

    record Attendance(long id, long trainingId, long membershipId, AttendanceStatus status, long updatedByUserId,
                      Instant updatedAt, long version) {
    }

    record AuditRow(long auditId, long trainingId, long membershipId, long actorUserId, AttendanceStatus previousStatus,
                    AttendanceStatus newStatus, AttendanceSource source, String note, Instant occurredAt, String actorName, String memberName) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<Attendance> mapper = this::map;

    AttendanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Attendance> find(long trainingId, long membershipId) {
        return jdbc.query(
                "SELECT * FROM training_attendances WHERE session_id = :session AND membership_id = :membership",
                new MapSqlParameterSource("session", trainingId).addValue("membership", membershipId), mapper)
                .stream().findFirst();
    }

    List<Attendance> findBySession(long trainingId) {
        return jdbc.query("SELECT * FROM training_attendances WHERE session_id = :session",
                new MapSqlParameterSource("session", trainingId), mapper);
    }

    /** First write for (session, member); empty if a row appeared meanwhile (unique key). Version becomes 1. */
    Optional<Long> insert(long trainingId, long clubId, long membershipId, AttendanceStatus status, long actorUserId,
                          Instant now) {
        return jdbc.queryForList("""
                INSERT INTO training_attendances (session_id, club_id, membership_id, status, updated_by_user_id,
                                                  updated_at, created_at)
                VALUES (:session, :club, :membership, :status, :actor, :now, :now)
                ON CONFLICT (session_id, membership_id) DO NOTHING
                RETURNING id
                """, new MapSqlParameterSource("session", trainingId).addValue("club", clubId)
                .addValue("membership", membershipId).addValue("status", status.name()).addValue("actor", actorUserId)
                .addValue("now", ts(now)), Long.class).stream().findFirst();
    }

    /** @return false if the row no longer has {@code expectedVersion} (concurrent change) */
    boolean update(long id, long expectedVersion, AttendanceStatus status, long actorUserId, Instant now) {
        return jdbc.update("""
                UPDATE training_attendances SET status = :status, updated_by_user_id = :actor, updated_at = :now,
                       version = version + 1
                WHERE id = :id AND version = :expected
                """, new MapSqlParameterSource("id", id).addValue("expected", expectedVersion)
                .addValue("status", status.name()).addValue("actor", actorUserId).addValue("now", ts(now))) == 1;
    }

    long insertAudit(long trainingId, long membershipId, long actorUserId, AttendanceStatus previous,
                     AttendanceStatus next, AttendanceSource source, String note, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO attendance_audit_logs (session_id, membership_id, actor_user_id, old_status, new_status,
                                                   action_source, note, occurred_at)
                VALUES (:session, :membership, :actor, :old, :new, :source, :note, :now)
                RETURNING id
                """, new MapSqlParameterSource("session", trainingId).addValue("membership", membershipId)
                .addValue("actor", actorUserId).addValue("old", previous.name()).addValue("new", next.name())
                .addValue("source", source.name()).addValue("note", note).addValue("now", ts(now)), Long.class);
    }

    /** Newest first. */
    List<AuditRow> audit(long trainingId, Long membershipId, int page, int size) {
        return jdbc.query("""
                SELECT a.*, u.first_name || ' ' || u.last_name AS actor_name, m.first_name || ' ' || m.last_name AS member_name
                FROM attendance_audit_logs a JOIN users u ON u.id=a.actor_user_id
                JOIN club_memberships cm ON cm.id=a.membership_id JOIN users m ON m.id=cm.user_id
                WHERE session_id = :session AND (CAST(:membership AS BIGINT) IS NULL OR membership_id = :membership)
                ORDER BY occurred_at DESC, a.id DESC
                LIMIT :size OFFSET :offset
                """, auditParams(trainingId, membershipId).addValue("size", size).addValue("offset", (long) page * size),
                (rs, row) -> new AuditRow(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("membership_id"),
                        rs.getLong("actor_user_id"), AttendanceStatus.valueOf(rs.getString("old_status")),
                        AttendanceStatus.valueOf(rs.getString("new_status")),
                        AttendanceSource.valueOf(rs.getString("action_source")), rs.getString("note"),
                        instant(rs, "occurred_at"), rs.getString("actor_name"), rs.getString("member_name")));
    }

    long auditCount(long trainingId, Long membershipId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM attendance_audit_logs
                WHERE session_id = :session AND (CAST(:membership AS BIGINT) IS NULL OR membership_id = :membership)
                """, auditParams(trainingId, membershipId), Long.class);
    }

    /** Recorded PRESENT/ABSENT of one member for several sessions (UNMARKED rows are left out). */
    Map<Long, AttendanceStatus> recordedOf(long membershipId, Collection<Long> trainingIds) {
        Map<Long, AttendanceStatus> result = new HashMap<>();
        if (!trainingIds.isEmpty()) {
            jdbc.query("""
                    SELECT session_id, status FROM training_attendances
                    WHERE membership_id = :membership AND session_id IN (:ids) AND status <> 'UNMARKED'
                    """, new MapSqlParameterSource("membership", membershipId).addValue("ids", trainingIds),
                    (ResultSet rs) -> {
                        result.put(rs.getLong("session_id"), AttendanceStatus.valueOf(rs.getString("status")));
                    });
        }
        return result;
    }

    private static MapSqlParameterSource auditParams(long trainingId, Long membershipId) {
        return new MapSqlParameterSource("session", trainingId).addValue("membership", membershipId);
    }

    private Attendance map(ResultSet rs, int row) throws SQLException {
        return new Attendance(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("membership_id"),
                AttendanceStatus.valueOf(rs.getString("status")), rs.getLong("updated_by_user_id"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
