package com.tapin.scheduling;

import com.tapin.members.MembershipDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Materialises sessions from enabled rules for the next {@code tapin.schedule.horizon-days} days (spec §4.2).
 * <ul>
 *   <li>Idempotent and safe under concurrency: {@code INSERT ... ON CONFLICT DO NOTHING} against
 *       {@code uk_training_sessions_schedule_occurrence}, so repeated or parallel runs (several instances, a rule
 *       change during the daily job) never create a duplicate.</li>
 *   <li>A moved or cancelled occurrence keeps its row (sessions are never deleted), so it is never re-created
 *       (AC-12).</li>
 *   <li>The local date and time are converted with the club zone per occurrence, so the local start stays 18:30
 *       across DST changes (AC-19).</li>
 *   <li>Only occurrences that have not started yet are created; the past is never back-filled (historical sessions
 *       are entered manually).</li>
 *   <li>An occurrence that would overlap an active extra session is skipped (the exclusion constraint also counts
 *       as a conflict for {@code ON CONFLICT DO NOTHING}); it is tried again on the next run.</li>
 * </ul>
 */
@Component
class SessionMaterializer {

    private static final Logger log = LoggerFactory.getLogger(SessionMaterializer.class);

    private final RecurringScheduleRepository schedules;
    private final MembershipDirectory directory;
    private final JdbcTemplate jdbc;
    private final ScheduleProperties properties;
    private final Clock clock;

    SessionMaterializer(RecurringScheduleRepository schedules, MembershipDirectory directory, JdbcTemplate jdbc,
                        ScheduleProperties properties, Clock clock) {
        this.schedules = schedules;
        this.directory = directory;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return number of sessions created by this run */
    @Transactional
    public int materialize() {
        ZoneId zone = directory.theClub().timeZone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate horizonEnd = today.plusDays(properties.horizonDays());
        int created = 0;
        for (RecurringSchedule rule : schedules.findByEnabledTrue()) {
            created += materialize(rule, today, horizonEnd, zone, now);
        }
        if (created > 0) {
            log.info("Materialised {} training session(s) up to {}", created, horizonEnd);
        }
        return created;
    }

    private int materialize(RecurringSchedule rule, LocalDate today, LocalDate horizonEnd, ZoneId zone, Instant now) {
        LocalDate from = rule.getValidFrom().isAfter(today) ? rule.getValidFrom() : today;
        LocalDate to = rule.getValidTo() != null && rule.getValidTo().isBefore(horizonEnd) ? rule.getValidTo() : horizonEnd;
        int created = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!rule.occursOn(date)) {
                continue;
            }
            Instant startAt = toInstant(date, rule.getStartTime(), zone);
            if (!startAt.isAfter(now)) {
                continue;
            }
            Instant endAt = toInstant(date, rule.getEndTime(), zone);
            List<Long> ids = jdbc.queryForList("""
                    INSERT INTO training_sessions (club_id, schedule_id, occurrence_date, start_at, end_at, status,
                                                   created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING id
                    """, Long.class, rule.getClubId(), rule.getId(), date, Timestamp.from(startAt),
                    Timestamp.from(endAt), Timestamp.from(now), Timestamp.from(now));
            if (ids.isEmpty()) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO training_session_events (session_id, event_type, new_values, occurred_at)
                    VALUES (?, 'CREATED', CAST(? AS jsonb), ?)
                    """, ids.getFirst(), "{\"source\":\"SCHEDULE\",\"scheduleId\":" + rule.getId() + "}",
                    Timestamp.from(now));
            created++;
        }
        return created;
    }

    static Instant toInstant(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toInstant();
    }
}
