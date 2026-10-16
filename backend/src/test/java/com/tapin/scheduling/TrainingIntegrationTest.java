package com.tapin.scheduling;

import com.tapin.notifications.OutboxTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BJJ-14: managing individual sessions (spec §4, §11.3, AC-11, AC-12, decision D-03). */
@Import(TrainingIntegrationTest.Participants.class)
class TrainingIntegrationTest extends SchedulingTestSupport {

    /** Stands in for the registrations module (BJJ-17/18): registered members of a session. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Participants {
        static final List<TrainingParticipants.Participant> REGISTERED = new CopyOnWriteArrayList<>();

        @Bean
        TrainingParticipants stubParticipants() {
            return new TrainingParticipants() {
                @Override
                public List<Participant> activeRegistrations(long trainingId) {
                    return List.copyOf(REGISTERED);
                }

                @Override
                public Map<Long, Integer> registeredCounts(java.util.Collection<Long> trainingIds) {
                    Map<Long, Integer> counts = new HashMap<>();
                    trainingIds.forEach(id -> counts.put(id, REGISTERED.size()));
                    return counts;
                }
            };
        }
    }

    @Autowired TrainingService trainingService;
    @Autowired ApplicationContext context;

    MockHttpSession trainer;
    long trainerMembership;

    @BeforeEach
    void setUp() throws Exception {
        Participants.REGISTERED.clear();
        trainerMembership = users.active("trener@klub.hr", "MEMBER", "TRAINER").membershipId();
        trainer = login("trener@klub.hr");
        materializer.materialize();
    }

    long tuesday(String date) {
        return (Long) occurrence(tuesdayRule, LocalDate.parse(date)).get("id");
    }

    long version(long id) {
        return jdbc.queryForObject("SELECT version FROM training_sessions WHERE id = ?", Long.class, id);
    }

    void registerAna() {
        long userId = users.active("ana@klub.hr", "MEMBER").userId();
        Participants.REGISTERED.add(new TrainingParticipants.Participant(99, userId, "Ana", "ana@klub.hr"));
    }

    // ---------------------------------------------------------------- reading

    @Test
    void membersSeeTheScheduleWithoutRegistrationCountsAndStaffSeesCounts() throws Exception {
        registerAna();
        MockHttpSession member = login("ana@klub.hr");

        mvc.perform(get("/api/v1/trainings").session(member))
                .andExpect(status().isOk())
                // Default range: now .. now + 14 days -> Tue 20, Thu 22, Tue 27, Thu 29 Oct.
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].startAt").value("2026-10-20T16:30:00Z"))
                .andExpect(jsonPath("$.items[0].localDate").value("2026-10-20"))
                .andExpect(jsonPath("$.items[0].occurrenceDate").value("2026-10-20"))
                .andExpect(jsonPath("$.items[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.items[0].myRegistrationStatus").value("NONE"))
                .andExpect(jsonPath("$.items[0].myAttendanceStatus").value("UNMARKED"))
                .andExpect(jsonPath("$.items[0].registeredCount").doesNotExist())
                .andExpect(jsonPath("$.items[2].startAt").value("2026-10-27T17:30:00Z"));
        mvc.perform(get("/api/v1/trainings").session(trainer))
                .andExpect(jsonPath("$.items[0].registeredCount").value(1));
        mvc.perform(get("/api/v1/trainings/" + tuesday("2026-10-20")).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdByUserId").isEmpty())
                .andExpect(jsonPath("$.cancellationReason").isEmpty())
                .andExpect(jsonPath("$.registeredCount").doesNotExist());
        mvc.perform(get("/api/v1/trainings/999999").session(member)).andExpect(status().isNotFound());
    }

    @Test
    void rangeIsHalfOpenLimitedAndFilterableByStatus() throws Exception {
        mvc.perform(get("/api/v1/trainings").session(trainer)
                        .param("from", "2026-10-20T16:30:00Z").param("to", "2026-10-22T16:30:00Z"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/trainings").session(trainer)
                        .param("from", "2026-10-01T00:00:00Z").param("to", "2027-01-15T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        long id = tuesday("2026-10-20");
        postJson(trainer, "/api/v1/trainings/" + id + "/cancel", Map.of("expectedVersion", 0)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/trainings").session(trainer).param("status", "CANCELLED"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].trainingId").value(id));
    }

    // ---------------------------------------------------------------- creating

    @Test
    void trainerCreatesAnExtraSessionWithSeveralCoaches() throws Exception {
        long second = users.active("drugi.trener@klub.hr", "TRAINER").membershipId();

        JsonNode created = body(postJson(trainer, "/api/v1/trainings", Map.of(
                "startAt", "2026-10-24T08:00:00Z", "endAt", "2026-10-24T10:00:00Z", "title", "Open mat",
                "coachMembershipIds", List.of(trainerMembership, second), "note", "Subota"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/v1/trainings/\\d+")))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.scheduleId").isEmpty())
                .andExpect(jsonPath("$.occurrenceDate").isEmpty())
                .andExpect(jsonPath("$.title").value("Open mat"))
                .andExpect(jsonPath("$.coaches.length()").value(2))
                .andExpect(jsonPath("$.coaches[0].firstName").value("Test"))
                .andExpect(jsonPath("$.localDate").value("2026-10-24")));

        long id = created.get("trainingId").asLong();
        assertThat(created.get("createdByUserId").asLong()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_coaches WHERE session_id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT event_type FROM training_session_events WHERE session_id = ?",
                String.class, id)).isEqualTo("CREATED");
    }

    @Test
    void invalidTimesAndOverlapsAreRejected() throws Exception {
        postJson(trainer, "/api/v1/trainings", Map.of("startAt", "2026-10-24T10:00:00Z", "endAt", "2026-10-24T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endAt").exists());
        postJson(trainer, "/api/v1/trainings", Map.of("startAt", "2026-10-10T10:00:00Z", "endAt", "2026-10-10T11:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.startAt").exists());
        postJson(trainer, "/api/v1/trainings", Map.of("startAt", "2026-10-24T10:00:00Z", "endAt", "2026-10-24T11:00:00Z",
                "historical", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endAt").exists());
        postJson(trainer, "/api/v1/trainings", Map.of("startAt", "2026-10-20T17:00:00Z", "endAt", "2026-10-20T19:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRAINING_OVERLAP"));
        // Back to back with the Tuesday session is not an overlap ([start, end)).
        postJson(trainer, "/api/v1/trainings", Map.of("startAt", "2026-10-20T18:00:00Z", "endAt", "2026-10-20T19:00:00Z"))
                .andExpect(status().isCreated());
    }

    @Test
    void historicalSessionIsHeldImmediately() throws Exception {
        postJson(trainer, "/api/v1/trainings", Map.of("startAt", "2026-09-15T16:30:00Z", "endAt", "2026-09-15T18:00:00Z",
                "historical", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void concurrentOverlappingCreationsLeaveExactlyOneSession() throws Exception {
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int offset = i * 10;
            tasks.add(() -> {
                start.await();
                Instant from = Instant.parse("2026-10-24T08:00:00Z").plus(Duration.ofMinutes(offset));
                return postJson(trainer, "/api/v1/trainings", Map.of("startAt", from.toString(),
                        "endAt", from.plus(Duration.ofHours(2)).toString()))
                        .andReturn().getResponse().getStatus();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_sessions WHERE schedule_id IS NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void coachesMustBeActiveStaffButAssignmentDoesNotRestrictAccess() throws Exception {
        long member = users.active("clan@klub.hr", "MEMBER").membershipId();
        long id = tuesday("2026-10-20");

        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 0, "coachMembershipIds", List.of(member)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
        long other = users.active("drugi@klub.hr", "TRAINER").membershipId();
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 0, "coachMembershipIds", List.of(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.training.coaches[0].membershipId").value(other));

        // The first trainer is not assigned but still manages the session; an admin too.
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 1, "title", "Tehnika"))
                .andExpect(status().isOk());
        users.active("admin@klub.hr", "ADMIN");
        MockHttpSession admin = login("admin@klub.hr");
        postJson(admin, "/api/v1/trainings/" + id + "/cancel", Map.of("expectedVersion", 2)).andExpect(status().isOk());
    }

    @Test
    void coachCandidatesAreActiveStaffOnly() throws Exception {
        users.active("clan@klub.hr", "MEMBER");
        long admin = users.active("admin@klub.hr", "ADMIN").membershipId();
        long inactive = users.active("bivsi@klub.hr", "TRAINER").membershipId();
        jdbc.update("UPDATE club_memberships SET status = 'INACTIVE' WHERE id = ?", inactive);

        mvc.perform(get("/api/v1/trainings/coaches").session(trainer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].membershipId", org.hamcrest.Matchers.containsInAnyOrder(
                        (int) trainerMembership, (int) admin)));
        mvc.perform(get("/api/v1/trainings/coaches").session(login("clan@klub.hr"))).andExpect(status().isForbidden());
    }

    @Test
    void membersCannotManageSessions() throws Exception {
        users.active("clan@klub.hr", "MEMBER");
        MockHttpSession member = login("clan@klub.hr");
        long id = tuesday("2026-10-20");

        postJson(member, "/api/v1/trainings", Map.of("startAt", "2026-10-24T08:00:00Z", "endAt", "2026-10-24T10:00:00Z"))
                .andExpect(status().isForbidden());
        patchJson(member, "/api/v1/trainings/" + id, Map.of("expectedVersion", 0, "title", "x")).andExpect(status().isForbidden());
        postJson(member, "/api/v1/trainings/" + id + "/cancel", Map.of("expectedVersion", 0)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- moving

    @Test
    void movingAnOccurrenceNotifiesRegisteredMembersAndSurvivesMaterialisation() throws Exception {
        registerAna();
        long id = tuesday("2026-10-20");

        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 0,
                "startAt", "2026-10-21T16:30:00Z", "endAt", "2026-10-21T18:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED"))
                .andExpect(jsonPath("$.training.localDate").value("2026-10-21"))
                .andExpect(jsonPath("$.training.occurrenceDate").value("2026-10-20"));

        assertThat(materializer.materialize()).isZero();
        assertThat(sessionsOf(tuesdayRule)).hasSize(9);
        Map<String, Object> outbox = jdbc.queryForMap("SELECT event_type, session_id, payload::text AS payload FROM email_outbox");
        assertThat(outbox).containsEntry("event_type", "TRAINING_RESCHEDULED").containsEntry("session_id", id);
        Map<String, Object> payload = json.readValue((String) outbox.get("payload"), new TypeReference<>() { });
        assertThat(OutboxTestSupport.render(context, "TRAINING_RESCHEDULED", payload).body())
                .contains("utorak 20. 10. 2026. u 18:30").contains("srijeda 21. 10. 2026. u 18:30");

        // The same values again: no change, no e-mail.
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", version(id),
                "startAt", "2026-10-21T16:30:00Z", "endAt", "2026-10-21T18:00:00Z"))
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.notificationStatus").value("NOT_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void movingOntoAnotherSessionIsAnOverlap() throws Exception {
        patchJson(trainer, "/api/v1/trainings/" + tuesday("2026-10-20"), Map.of("expectedVersion", 0,
                "startAt", "2026-10-22T16:00:00Z", "endAt", "2026-10-22T17:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRAINING_OVERLAP"));
        patchJson(trainer, "/api/v1/trainings/" + tuesday("2026-10-20"), Map.of("expectedVersion", 0,
                "endAt", "2026-10-20T16:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endAt").exists());
    }

    @Test
    void staleVersionIsAConflictWithTheCurrentSession() throws Exception {
        long id = tuesday("2026-10-20");
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 0, "title", "A")).andExpect(status().isOk());

        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 0, "title", "B"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentState.title").value("A"))
                .andExpect(jsonPath("$.currentState.version").value(1));
        // Fields that are not sent stay as they are; an explicit null clears.
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 1, "note", "N"))
                .andExpect(jsonPath("$.training.title").value("A"))
                .andExpect(jsonPath("$.training.note").value("N"));
        Map<String, Object> clear = new HashMap<>();
        clear.put("expectedVersion", 2);
        clear.put("title", null);
        patchJson(trainer, "/api/v1/trainings/" + id, clear)
                .andExpect(jsonPath("$.training.title").isEmpty())
                .andExpect(jsonPath("$.training.note").value("N"));
    }

    // ---------------------------------------------------------------- cancelling

    @Test
    void cancellingKeepsRuleSessionAndHistoryAndIsIdempotent() throws Exception {
        registerAna();
        long id = tuesday("2026-10-20");

        postJson(trainer, "/api/v1/trainings/" + id + "/cancel", Map.of("expectedVersion", 0, "reason", "Blagdan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED"))
                .andExpect(jsonPath("$.training.status").value("CANCELLED"))
                .andExpect(jsonPath("$.training.cancellationReason").value("Blagdan"));
        postJson(trainer, "/api/v1/trainings/" + id + "/cancel", Map.of("expectedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.notificationStatus").value("NOT_REQUIRED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE event_type = 'TRAINING_CANCELLED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_schedules WHERE id = ?", Integer.class, tuesdayRule))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM training_session_events WHERE session_id = ? ORDER BY id",
                String.class, id)).containsExactly("CREATED", "CANCELLED");
        assertThat(materializer.materialize()).isZero();
        // Editing a cancelled session is refused; it never becomes HELD, so statistics never count it.
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", version(id), "title", "x"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("TRAINING_CANCELLED"));
        clock.set(Instant.parse("2026-10-21T10:00:00Z"));
        trainingService.markEndedAsHeld();
        assertThat(jdbc.queryForObject("SELECT status FROM training_sessions WHERE id = ?", String.class, id))
                .isEqualTo("CANCELLED");
    }

    @Test
    void cancelledSlotCanBeReusedByAnExtraSession() throws Exception {
        long id = tuesday("2026-10-20");
        postJson(trainer, "/api/v1/trainings/" + id + "/cancel", Map.of("expectedVersion", 0)).andExpect(status().isOk());

        postJson(trainer, "/api/v1/trainings", Map.of("startAt", "2026-10-20T16:30:00Z", "endAt", "2026-10-20T18:00:00Z",
                "title", "Zamjenski trening")).andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- HELD and D-03

    @Test
    void endedSessionsBecomeHeldAndStayEditableExceptTimeAndStatus() throws Exception {
        long id = tuesday("2026-10-20");
        clock.set(Instant.parse("2026-10-20T18:00:00Z")); // 20:00 local: the session just ended

        assertThat(trainingService.markEndedAsHeld()).isEqualTo(1);
        assertThat(trainingService.markEndedAsHeld()).isZero();
        assertThat(jdbc.queryForMap("SELECT status, version FROM training_sessions WHERE id = ?", id))
                .containsEntry("status", "HELD").containsEntry("version", 1L);

        // D-03: a held session cannot be moved or cancelled ...
        postJson(trainer, "/api/v1/trainings/" + id + "/cancel", Map.of("expectedVersion", 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 1,
                "startAt", "2026-10-27T10:00:00Z", "endAt", "2026-10-27T11:00:00Z"))
                .andExpect(status().isUnprocessableContent());
        // ... but its title, note and coaches can still be corrected.
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 1, "note", "Gost: Marko",
                "coachMembershipIds", List.of(trainerMembership)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.training.status").value("HELD"))
                .andExpect(jsonPath("$.training.note").value("Gost: Marko"));
    }

    @Test
    void sessionInProgressCannotBeCancelledOrMoved() throws Exception {
        long id = tuesday("2026-10-20");
        clock.set(Instant.parse("2026-10-20T17:00:00Z")); // 19:00 local, still SCHEDULED

        postJson(trainer, "/api/v1/trainings/" + id + "/cancel", Map.of("expectedVersion", 0))
                .andExpect(status().isUnprocessableContent());
        patchJson(trainer, "/api/v1/trainings/" + id, Map.of("expectedVersion", 0, "endAt", "2026-10-20T18:30:00Z"))
                .andExpect(status().isUnprocessableContent());
    }
}
