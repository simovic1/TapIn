package com.tapin.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BJJ-13: weekly rules and materialisation of sessions (spec §4.1-4.2, AC-12, AC-19, decision D-02). */
class RecurringScheduleIntegrationTest extends SchedulingTestSupport {

    // ---------------------------------------------------------------- materialisation

    @Test
    void materialisesTuesdaysAndThursdaysSixtyDaysAheadAt1830Local() {
        int created = materializer.materialize();

        // 16 Oct 2026 + 60 days = 15 Dec 2026: 9 Tuesdays (20 Oct .. 15 Dec) and 8 Thursdays (22 Oct .. 10 Dec).
        assertThat(created).isEqualTo(17);
        assertThat(sessionsOf(tuesdayRule)).hasSize(9).allSatisfy(s -> {
            assertThat(localStart(s)).endsWith("T18:30");
            assertThat(Duration.between(instant(s.get("start_at")), instant(s.get("end_at")))).hasMinutes(90);
            assertThat(s.get("status")).isEqualTo("SCHEDULED");
            assertThat(s.get("created_by_user_id")).isNull();
        });
        assertThat(sessionsOf(thursdayRule)).hasSize(8);
        assertThat(sessionsOf(tuesdayRule).getFirst().get("occurrence_date").toString()).isEqualTo("2026-10-20");
        assertThat(sessionsOf(tuesdayRule).getLast().get("occurrence_date").toString()).isEqualTo("2026-12-15");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_session_events WHERE event_type = 'CREATED'",
                Integer.class)).isEqualTo(17);
    }

    @Test
    void repeatedMaterialisationCreatesNoDuplicates() {
        materializer.materialize();

        assertThat(materializer.materialize()).isZero();
        clock.advance(Duration.ofDays(7));
        // A week later the horizon moved by a week: exactly one new Tuesday and Thursday, nothing else.
        assertThat(materializer.materialize()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_sessions", Integer.class)).isEqualTo(19);
    }

    @Test
    void concurrentMaterialisationCreatesNoDuplicates() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                return materializer.materialize();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            int total = 0;
            for (Future<Integer> future : futures) {
                total += future.get();
            }
            assertThat(total).isEqualTo(17);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT schedule_id, occurrence_date FROM training_sessions
                                      GROUP BY schedule_id, occurrence_date HAVING count(*) > 1) d
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_sessions", Integer.class)).isEqualTo(17);
    }

    @Test
    void localStartStaysAt1830AcrossTheAutumnDstChange() {
        materializer.materialize();

        // Summer time ends on Sunday 25 Oct 2026: UTC offset +2 before, +1 after.
        Map<String, Object> before = occurrence(tuesdayRule, LocalDate.parse("2026-10-20"));
        Map<String, Object> after = occurrence(tuesdayRule, LocalDate.parse("2026-10-27"));
        assertThat(instant(before.get("start_at"))).isEqualTo(Instant.parse("2026-10-20T16:30:00Z"));
        assertThat(instant(after.get("start_at"))).isEqualTo(Instant.parse("2026-10-27T17:30:00Z"));
        assertThat(instant(after.get("end_at"))).isEqualTo(Instant.parse("2026-10-27T19:00:00Z"));
        assertThat(localStart(before)).isEqualTo("2026-10-20T18:30");
        assertThat(localStart(after)).isEqualTo("2026-10-27T18:30");
    }

    @Test
    void localStartStaysAt1830AcrossTheSpringDstChange() {
        clock.set(Instant.parse("2027-03-20T10:00:00Z"));
        materializer.materialize();

        // Summer time starts on Sunday 28 Mar 2027.
        assertThat(instant(occurrence(thursdayRule, LocalDate.parse("2027-03-25")).get("start_at")))
                .isEqualTo(Instant.parse("2027-03-25T17:30:00Z"));
        assertThat(instant(occurrence(tuesdayRule, LocalDate.parse("2027-03-30")).get("start_at")))
                .isEqualTo(Instant.parse("2027-03-30T16:30:00Z"));
    }

    @Test
    void onlyFutureOccurrencesAreCreatedAndThePastIsNeverBackFilled() {
        // Tuesday 20 Oct 2026, 19:00 local: today's session has already started.
        clock.set(Instant.parse("2026-10-20T17:00:00Z"));
        materializer.materialize();

        assertThat(sessionsOf(tuesdayRule).getFirst().get("occurrence_date").toString()).isEqualTo("2026-10-27");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_sessions WHERE start_at < ?", Integer.class,
                java.sql.Timestamp.from(clock.instant()))).isZero();
    }

    @Test
    void movedOrCancelledOccurrenceIsNeverCreatedAgain() {
        materializer.materialize();
        LocalDate moved = LocalDate.parse("2026-10-20");
        LocalDate cancelled = LocalDate.parse("2026-10-27");
        // Tuesday 20 Oct moved to Wednesday 21 Oct; Tuesday 27 Oct cancelled.
        jdbc.update("""
                UPDATE training_sessions SET start_at = start_at + interval '1 day', end_at = end_at + interval '1 day',
                       time_overridden = TRUE WHERE schedule_id = ? AND occurrence_date = ?""", tuesdayRule, moved);
        jdbc.update("""
                UPDATE training_sessions SET status = 'CANCELLED', cancelled_at = now()
                WHERE schedule_id = ? AND occurrence_date = ?""", tuesdayRule, cancelled);

        assertThat(materializer.materialize()).isZero();
        assertThat(sessionsOf(tuesdayRule)).hasSize(9);
        assertThat(localStart(occurrence(tuesdayRule, moved))).isEqualTo("2026-10-21T18:30");
        assertThat(occurrence(tuesdayRule, cancelled).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void occurrenceOverlappingAnExtraSessionIsSkipped() {
        jdbc.update("""
                INSERT INTO training_sessions (club_id, start_at, end_at, title)
                VALUES ((SELECT id FROM clubs), TIMESTAMPTZ '2026-10-22T16:00:00Z', TIMESTAMPTZ '2026-10-22T17:00:00Z',
                        'Seminar')""");

        assertThat(materializer.materialize()).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_sessions WHERE schedule_id = ? "
                + "AND occurrence_date = DATE '2026-10-22'", Integer.class, thursdayRule)).isZero();
    }

    // ---------------------------------------------------------------- rules over HTTP

    @Test
    void anySignedInUserReadsRulesButOnlyStaffChangesThem() throws Exception {
        users.active("clan@klub.hr", "MEMBER");
        MockHttpSession member = login("clan@klub.hr");

        mvc.perform(get("/api/v1/recurring-schedules").session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].weekday").value("TUESDAY"))
                .andExpect(jsonPath("$.items[0].startTime").value("18:30"))
                .andExpect(jsonPath("$.items[0].endTime").value("20:00"))
                .andExpect(jsonPath("$.items[1].weekday").value("THURSDAY"))
                .andExpect(jsonPath("$.items[1].validTo").isEmpty());
        postJson(member, "/api/v1/recurring-schedules", Map.of("weekday", "SATURDAY", "startTime", "10:00",
                "endTime", "11:30", "validFrom", "2026-10-17")).andExpect(status().isForbidden());
        patchJson(member, "/api/v1/recurring-schedules/" + tuesdayRule, Map.of("expectedVersion", 0,
                "startTime", "19:00")).andExpect(status().isForbidden());
    }

    @Test
    void newRuleIsMaterialisedImmediately() throws Exception {
        MockHttpSession trainer = trainer();

        JsonNode rule = body(postJson(trainer, "/api/v1/recurring-schedules", Map.of("weekday", "SATURDAY",
                "startTime", "10:00", "endTime", "11:30", "validFrom", "2026-10-17", "validTo", "2026-10-31"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.version").value(0)));

        List<Map<String, Object>> saturdays = sessionsOf(rule.get("scheduleId").asLong());
        assertThat(saturdays).extracting(SchedulingTestSupport::localStart)
                .containsExactly("2026-10-17T10:00", "2026-10-24T10:00", "2026-10-31T10:00");
    }

    @Test
    void invalidRulesAreRejectedWithFieldErrors() throws Exception {
        MockHttpSession trainer = trainer();

        postJson(trainer, "/api/v1/recurring-schedules", Map.of("weekday", "MONDAY", "startTime", "20:00",
                "endTime", "18:30", "validFrom", "2026-10-19"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.endTime").exists());
        postJson(trainer, "/api/v1/recurring-schedules", Map.of("weekday", "MONDAY", "startTime", "18:30",
                "endTime", "20:00", "validFrom", "2026-10-19", "validTo", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.validTo").exists());
        postJson(trainer, "/api/v1/recurring-schedules", Map.of("weekday", "MONDAY", "startTime", "25:00",
                "endTime", "20:00", "validFrom", "2026-10-19"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.startTime").exists());
        // Overlaps the Tuesday rule.
        postJson(trainer, "/api/v1/recurring-schedules", Map.of("weekday", "TUESDAY", "startTime", "19:30",
                "endTime", "21:00", "validFrom", "2026-10-19"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRAINING_OVERLAP"));
    }

    @Test
    void staleExpectedVersionIsAConflictWithCurrentState() throws Exception {
        MockHttpSession trainer = trainer();
        patchJson(trainer, "/api/v1/recurring-schedules/" + tuesdayRule,
                Map.of("expectedVersion", 0, "endTime", "20:30")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        patchJson(trainer, "/api/v1/recurring-schedules/" + tuesdayRule,
                Map.of("expectedVersion", 0, "endTime", "21:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentState.endTime").value("20:30"))
                .andExpect(jsonPath("$.currentState.version").value(1));
    }

    // ---------------------------------------------------------------- D-02: rule changes and existing sessions

    @Test
    void ruleTimeChangeMovesFutureUntouchedSessionsButNeverThePast() throws Exception {
        // Tuesday 20 Oct was held last week; the rule changes on Wednesday 21 Oct.
        clock.set(Instant.parse("2026-10-14T10:00:00Z"));
        materializer.materialize();
        clock.set(Instant.parse("2026-10-21T10:00:00Z"));
        jdbc.update("UPDATE training_sessions SET status = 'HELD' WHERE end_at < ?",
                java.sql.Timestamp.from(clock.instant()));
        // Tuesday 3 Nov was moved individually to 17:00 and keeps that time.
        jdbc.update("""
                UPDATE training_sessions SET start_at = TIMESTAMPTZ '2026-11-03T16:00:00Z',
                       end_at = TIMESTAMPTZ '2026-11-03T17:30:00Z', time_overridden = TRUE
                WHERE schedule_id = ? AND occurrence_date = DATE '2026-11-03'""", tuesdayRule);
        MockHttpSession trainer = trainer();

        patchJson(trainer, "/api/v1/recurring-schedules/" + tuesdayRule,
                Map.of("expectedVersion", 0, "startTime", "19:00", "endTime", "20:30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startTime").value("19:00"));

        assertThat(localStart(occurrence(tuesdayRule, LocalDate.parse("2026-10-20")))).isEqualTo("2026-10-20T18:30");
        assertThat(localStart(occurrence(tuesdayRule, LocalDate.parse("2026-10-27")))).isEqualTo("2026-10-27T19:00");
        assertThat(localStart(occurrence(tuesdayRule, LocalDate.parse("2026-11-03")))).isEqualTo("2026-11-03T17:00");
        assertThat(localStart(occurrence(tuesdayRule, LocalDate.parse("2026-11-10")))).isEqualTo("2026-11-10T19:00");
        // New occurrences beyond the old horizon use the new time; Thursdays are untouched.
        assertThat(localStart(occurrence(tuesdayRule, LocalDate.parse("2026-12-15")))).isEqualTo("2026-12-15T19:00");
        assertThat(sessionsOf(thursdayRule)).allSatisfy(s -> assertThat(localStart(s)).endsWith("T18:30"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_session_events WHERE event_type = 'RESCHEDULED'",
                Integer.class)).isEqualTo(6); // 27 Oct, 10 Nov .. 8 Dec (not 20 Oct, not 3 Nov)
    }

    @Test
    void endingTheRuleCancelsLaterSessionsWithoutDeletingThem() throws Exception {
        materializer.materialize();
        MockHttpSession trainer = trainer();

        patchJson(trainer, "/api/v1/recurring-schedules/" + thursdayRule,
                Map.of("expectedVersion", 0, "validTo", "2026-11-05")).andExpect(status().isOk())
                .andExpect(jsonPath("$.validTo").value("2026-11-05"));

        List<Map<String, Object>> thursdays = sessionsOf(thursdayRule);
        assertThat(thursdays).hasSize(8);
        Map<String, String> statusByDate = new HashMap<>();
        thursdays.forEach(s -> statusByDate.put(s.get("occurrence_date").toString(), (String) s.get("status")));
        assertThat(statusByDate).containsEntry("2026-10-22", "SCHEDULED").containsEntry("2026-11-05", "SCHEDULED")
                .containsEntry("2026-11-12", "CANCELLED").containsEntry("2026-12-10", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM training_sessions WHERE schedule_id = ? "
                + "AND occurrence_date = DATE '2026-11-12'", String.class, thursdayRule))
                .isEqualTo(RecurringScheduleService.REMOVED_FROM_SCHEDULE);
        // The rule itself still exists.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_schedules", Integer.class)).isEqualTo(2);
    }

    @Test
    void disablingTheRuleCancelsItsFutureSessionsAndStopsMaterialisation() throws Exception {
        materializer.materialize();
        MockHttpSession trainer = trainer();

        patchJson(trainer, "/api/v1/recurring-schedules/" + tuesdayRule,
                Map.of("expectedVersion", 0, "enabled", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(sessionsOf(tuesdayRule)).hasSize(9).allSatisfy(s -> assertThat(s.get("status")).isEqualTo("CANCELLED"));
        clock.advance(Duration.ofDays(14));
        assertThat(materializer.materialize()).isEqualTo(2); // Thursdays only
        assertThat(sessionsOf(tuesdayRule)).hasSize(9);
    }

    @Test
    void ruleUpdateMayClearTheEndDate() throws Exception {
        MockHttpSession trainer = trainer();
        patchJson(trainer, "/api/v1/recurring-schedules/" + tuesdayRule,
                Map.of("expectedVersion", 0, "validTo", "2026-11-01")).andExpect(status().isOk());

        Map<String, Object> clear = new HashMap<>();
        clear.put("expectedVersion", 1);
        clear.put("validTo", null);
        patchJson(trainer, "/api/v1/recurring-schedules/" + tuesdayRule, clear)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validTo").isEmpty());
        mvc.perform(get("/api/v1/recurring-schedules").session(trainer).with(csrf()))
                .andExpect(jsonPath("$.items[0].validTo").isEmpty());
    }
}
