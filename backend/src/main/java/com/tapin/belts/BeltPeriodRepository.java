package com.tapin.belts;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Plain SQL access to {@code member_belt_periods} and {@code belt_period_audit_logs}. */
@Repository
class BeltPeriodRepository {

    record Period(long id, long membershipId, Belt belt, LocalDate awardedOn, LocalDate effectiveFromDate,
                  Long awardedByUserId, String note, long createdByUserId, Instant createdAt, Instant updatedAt,
                  long version) {
    }

    record AuditRow(long auditId, long beltPeriodId, long membershipId, long actorUserId, String action,
                    Map<String, Object> oldValues, Map<String, Object> newValues, String reason, Instant occurredAt) {
    }

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final RowMapper<Period> mapper = this::map;

    BeltPeriodRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Chronological, oldest first. */
    List<Period> findByMembership(long membershipId) {
        return jdbc.query("SELECT * FROM member_belt_periods WHERE membership_id = :m ORDER BY effective_from_date",
                new MapSqlParameterSource("m", membershipId), mapper);
    }

    Optional<Period> find(long membershipId, long periodId) {
        return jdbc.query("SELECT * FROM member_belt_periods WHERE id = :id AND membership_id = :m",
                new MapSqlParameterSource("id", periodId).addValue("m", membershipId), mapper).stream().findFirst();
    }

    long insert(long membershipId, Belt belt, LocalDate awardedOn, LocalDate effectiveFrom, Long awardedBy,
                String note, long createdBy, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO member_belt_periods (membership_id, belt, awarded_on, effective_from_date,
                                                 awarded_by_user_id, note, created_by_user_id, created_at, updated_at)
                VALUES (:m, :belt, :awarded, :effective, :awardedBy, :note, :createdBy, :now, :now)
                RETURNING id
                """, new MapSqlParameterSource("m", membershipId).addValue("belt", belt.name())
                .addValue("awarded", awardedOn).addValue("effective", effectiveFrom).addValue("awardedBy", awardedBy)
                .addValue("note", note).addValue("createdBy", createdBy).addValue("now", Timestamp.from(now)), Long.class);
    }

    /** @return false if the row no longer has {@code expectedVersion} */
    boolean update(long id, long expectedVersion, Belt belt, LocalDate awardedOn, LocalDate effectiveFrom,
                   Long awardedBy, String note, Instant now) {
        return jdbc.update("""
                UPDATE member_belt_periods SET belt = :belt, awarded_on = :awarded, effective_from_date = :effective,
                       awarded_by_user_id = :awardedBy, note = :note, updated_at = :now, version = version + 1
                WHERE id = :id AND version = :expected
                """, new MapSqlParameterSource("id", id).addValue("expected", expectedVersion)
                .addValue("belt", belt.name()).addValue("awarded", awardedOn).addValue("effective", effectiveFrom)
                .addValue("awardedBy", awardedBy).addValue("note", note).addValue("now", Timestamp.from(now))) == 1;
    }

    void insertAudit(long periodId, long membershipId, long actorUserId, String action, Map<String, Object> oldValues,
                     Map<String, Object> newValues, String reason, Instant now) {
        jdbc.update("""
                INSERT INTO belt_period_audit_logs (belt_period_id, membership_id, actor_user_id, action, old_values,
                                                    new_values, reason, occurred_at)
                VALUES (:period, :m, :actor, :action, CAST(:old AS jsonb), CAST(:new AS jsonb), :reason, :now)
                """, new MapSqlParameterSource("period", periodId).addValue("m", membershipId)
                .addValue("actor", actorUserId).addValue("action", action)
                .addValue("old", oldValues == null ? null : json.writeValueAsString(oldValues))
                .addValue("new", json.writeValueAsString(newValues)).addValue("reason", reason)
                .addValue("now", Timestamp.from(now)));
    }

    /** Newest first. */
    List<AuditRow> audit(long membershipId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM belt_period_audit_logs WHERE membership_id = :m
                ORDER BY occurred_at DESC, id DESC LIMIT :size OFFSET :offset
                """, new MapSqlParameterSource("m", membershipId).addValue("size", size)
                .addValue("offset", (long) page * size), (rs, row) -> new AuditRow(rs.getLong("id"),
                rs.getLong("belt_period_id"), rs.getLong("membership_id"), rs.getLong("actor_user_id"),
                rs.getString("action"), readJson(rs.getString("old_values")), readJson(rs.getString("new_values")),
                rs.getString("reason"), instant(rs, "occurred_at")));
    }

    long auditCount(long membershipId) {
        return jdbc.queryForObject("SELECT count(*) FROM belt_period_audit_logs WHERE membership_id = :m",
                new MapSqlParameterSource("m", membershipId), Long.class);
    }

    /** Latest period per membership = current belt. */
    Map<Long, Period> currentOf(Collection<Long> membershipIds) {
        Map<Long, Period> result = new HashMap<>();
        if (!membershipIds.isEmpty()) {
            jdbc.query("""
                    SELECT DISTINCT ON (membership_id) * FROM member_belt_periods
                    WHERE membership_id IN (:ids) ORDER BY membership_id, effective_from_date DESC
                    """, new MapSqlParameterSource("ids", membershipIds), (ResultSet rs) -> {
                Period p = map(rs, 0);
                result.put(p.membershipId(), p);
            });
        }
        return result;
    }

    Set<Long> withCurrentBelt(Belt belt) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT membership_id FROM (SELECT DISTINCT ON (membership_id) membership_id, belt
                                           FROM member_belt_periods ORDER BY membership_id, effective_from_date DESC) c
                WHERE belt = :belt
                """, new MapSqlParameterSource("belt", belt.name()), Long.class));
    }

    Set<Long> withHistory() {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT membership_id FROM member_belt_periods",
                new MapSqlParameterSource(), Long.class));
    }

    private Map<String, Object> readJson(String value) {
        return value == null ? null : json.readValue(value, JSON_MAP);
    }

    private Period map(ResultSet rs, int row) throws SQLException {
        return new Period(rs.getLong("id"), rs.getLong("membership_id"), Belt.valueOf(rs.getString("belt")),
                rs.getObject("awarded_on", LocalDate.class), rs.getObject("effective_from_date", LocalDate.class),
                rs.getObject("awarded_by_user_id", Long.class), rs.getString("note"), rs.getLong("created_by_user_id"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
