package com.tapin.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only statistics queries (spec §9.1, §9.3). Nothing is stored: every number is computed from the latest
 * attendance, registration, session and membership data, so corrections show up on the next read.
 * <p>
 * Common rules: a training counts only when the session is {@code HELD} (cancelled and not yet held sessions never
 * count); one row per (session, member) is guaranteed by {@code uk_training_attendances_session_membership}, and the
 * queries count distinct sessions anyway. Periods use the session's local date in the club time zone, never the time
 * the attendance was entered or corrected.
 */
@Repository
class StatisticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    StatisticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record PresentCounts(int total, int thisMonth, int last7Days, int last30Days) {
    }

    record RegistrationCounts(int total, int present, int absent, int unmarked) {
    }

    record HeldWhileActive(int heldSessions, int present) {
    }

    record HistoryRow(long trainingId, LocalDate localDate, Instant startAt, String title, String trainingStatus,
                      String registrationStatus, String attendanceStatus) {
    }

    record Member(long membershipId, long userId, LocalDate joinedOn) {
    }

    java.util.Optional<Member> member(long membershipId) {
        return jdbc.query("SELECT id, user_id, joined_on FROM club_memberships WHERE id = :m",
                new MapSqlParameterSource("m", membershipId),
                (rs, n) -> new Member(rs.getLong("id"), rs.getLong("user_id"), rs.getObject("joined_on", LocalDate.class)))
                .stream().findFirst();
    }

    /** PRESENT on HELD sessions: in total and since the given local dates (inclusive). */
    PresentCounts present(long membershipId, LocalDate monthFrom, LocalDate last7From, LocalDate last30From) {
        return jdbc.queryForObject("""
                WITH present AS (
                    SELECT DISTINCT s.id, (s.start_at AT TIME ZONE c.time_zone)::date AS local_date
                    FROM training_attendances a
                    JOIN training_sessions s ON s.id = a.session_id
                    JOIN clubs c ON c.id = s.club_id
                    WHERE a.membership_id = :m AND a.status = 'PRESENT' AND s.status = 'HELD'
                )
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE local_date >= :monthFrom) AS this_month,
                       count(*) FILTER (WHERE local_date >= :last7From) AS last7,
                       count(*) FILTER (WHERE local_date >= :last30From) AS last30
                FROM present
                """, new MapSqlParameterSource("m", membershipId)
                .addValue("monthFrom", Date.valueOf(monthFrom))
                .addValue("last7From", Date.valueOf(last7From))
                .addValue("last30From", Date.valueOf(last30From)),
                (rs, n) -> new PresentCounts(rs.getInt("total"), rs.getInt("this_month"), rs.getInt("last7"),
                        rs.getInt("last30")));
    }

    /**
     * Active (not cancelled) registrations on HELD sessions by the attendance outcome. UNMARKED (no decision) is kept
     * apart: it is neither a training nor a confirmed absence.
     */
    RegistrationCounts registrations(long membershipId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE a.status = 'PRESENT') AS present,
                       count(*) FILTER (WHERE a.status = 'ABSENT') AS absent,
                       count(*) FILTER (WHERE a.status IS NULL OR a.status = 'UNMARKED') AS unmarked
                FROM training_registrations r
                JOIN training_sessions s ON s.id = r.session_id
                LEFT JOIN training_attendances a ON a.session_id = r.session_id AND a.membership_id = r.membership_id
                WHERE r.membership_id = :m AND r.status = 'REGISTERED' AND s.status = 'HELD'
                """, new MapSqlParameterSource("m", membershipId),
                (rs, n) -> new RegistrationCounts(rs.getInt("total"), rs.getInt("present"), rs.getInt("absent"),
                        rs.getInt("unmarked")));
    }

    /**
     * Denominator of the attendance rate: HELD sessions of the club whose local date is on or after {@code joined_on}
     * and at whose start the membership was ACTIVE (latest status event at or before the start - the same rule as
     * attendance entry). The numerator is PRESENT on exactly those sessions, so the rate never exceeds 100 %.
     */
    HeldWhileActive heldWhileActive(long membershipId) {
        return jdbc.queryForObject("""
                WITH held AS (
                    SELECT s.id
                    FROM club_memberships m
                    JOIN clubs c ON c.id = m.club_id
                    JOIN training_sessions s ON s.club_id = m.club_id AND s.status = 'HELD'
                    WHERE m.id = :m
                      AND (s.start_at AT TIME ZONE c.time_zone)::date >= m.joined_on
                      AND COALESCE((SELECT e.new_status = 'ACTIVE' FROM membership_status_events e
                                    WHERE e.membership_id = m.id AND e.effective_at <= s.start_at
                                    ORDER BY e.effective_at DESC, e.id DESC LIMIT 1), false)
                )
                SELECT count(*) AS held,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM training_attendances a
                                                      WHERE a.session_id = held.id AND a.membership_id = :m
                                                        AND a.status = 'PRESENT')) AS present
                FROM held
                """, new MapSqlParameterSource("m", membershipId),
                (rs, n) -> new HeldWhileActive(rs.getInt("held"), rs.getInt("present")));
    }

    private static final String HISTORY_FROM = """
            FROM training_sessions s
            JOIN clubs c ON c.id = s.club_id
            LEFT JOIN training_registrations r ON r.session_id = s.id AND r.membership_id = :m
            LEFT JOIN training_attendances a ON a.session_id = s.id AND a.membership_id = :m
            WHERE (r.id IS NOT NULL OR (a.id IS NOT NULL AND a.status <> 'UNMARKED'))
              AND s.start_at <= :now
              AND (CAST(:from AS DATE) IS NULL OR (s.start_at AT TIME ZONE c.time_zone)::date >= CAST(:from AS DATE))
              AND (CAST(:to AS DATE) IS NULL OR (s.start_at AT TIME ZONE c.time_zone)::date < CAST(:to AS DATE))
            """;

    /**
     * Trainings the member registered for or was marked at and that have started, newest first. Includes cancelled
     * sessions (shown as cancelled, never counted) and cancelled registrations.
     */
    List<HistoryRow> history(long membershipId, Instant now, LocalDate from, LocalDate to, int page, int size) {
        return jdbc.query("SELECT s.id, (s.start_at AT TIME ZONE c.time_zone)::date AS local_date, s.start_at, s.title, "
                        + "s.status AS training_status, COALESCE(r.status, 'NONE') AS registration_status, "
                        + "COALESCE(a.status, 'UNMARKED') AS attendance_status "
                        + HISTORY_FROM + " ORDER BY s.start_at DESC, s.id DESC LIMIT :size OFFSET :offset",
                historyParams(membershipId, now, from, to).addValue("size", size).addValue("offset", (long) page * size),
                (rs, n) -> new HistoryRow(rs.getLong("id"), rs.getObject("local_date", LocalDate.class),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(), rs.getString("title"),
                        rs.getString("training_status"), rs.getString("registration_status"),
                        rs.getString("attendance_status")));
    }

    long historyCount(long membershipId, Instant now, LocalDate from, LocalDate to) {
        Long count = jdbc.queryForObject("SELECT count(*) " + HISTORY_FROM, historyParams(membershipId, now, from, to),
                Long.class);
        return count == null ? 0 : count;
    }

    private static MapSqlParameterSource historyParams(long membershipId, Instant now, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("m", membershipId)
                .addValue("now", Timestamp.from(now))
                .addValue("from", from == null ? null : Date.valueOf(from))
                .addValue("to", to == null ? null : Date.valueOf(to));
    }
}
