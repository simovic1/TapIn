package com.tapin.registrations;

import com.tapin.MutableClock;
import com.tapin.TestcontainersConfiguration;
import com.tapin.auth.AuthRequestLimiter;
import com.tapin.auth.TestUsers;
import com.tapin.notifications.OutboxTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BJJ-17: own registration, cancellation and re-registration (spec §5, AC-03/04/05). Commits for real. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class, RegistrationIntegrationTest.Attendance.class})
@Testcontainers(disabledWithoutDocker = true)
class RegistrationIntegrationTest {

    /**
     * Stands in for the attendance module (BJJ-19). Backed by a real (test-only) table, so recorded attendance is
     * transactional: other transactions see it only after commit, exactly like the future attendance table.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Attendance {

        @Bean
        AttendanceStatusLookup stubAttendance(JdbcTemplate jdbc) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS test_attendance (session_id BIGINT, membership_id BIGINT, "
                    + "status VARCHAR(16), PRIMARY KEY (session_id, membership_id))");
            return new AttendanceStatusLookup() {
                @Override
                public String statusFor(long trainingId, long membershipId) {
                    return jdbc.queryForList("SELECT status FROM test_attendance WHERE session_id = ? AND membership_id = ?",
                            String.class, trainingId, membershipId).stream().findFirst().orElse(null);
                }

                @Override
                public Map<Long, String> statusesFor(long membershipId, Collection<Long> trainingIds) {
                    Map<Long, String> result = new HashMap<>();
                    trainingIds.forEach(id -> {
                        String s = statusFor(id, membershipId);
                        if (s != null) {
                            result.put(id, s);
                        }
                    });
                    return result;
                }
            };
        }
    }

    void recordAttendance(long trainingId, long membershipId, String status) {
        jdbc.update("DELETE FROM test_attendance WHERE session_id = ? AND membership_id = ?", trainingId, membershipId);
        jdbc.update("INSERT INTO test_attendance VALUES (?, ?, ?)", trainingId, membershipId, status);
    }

    /** Friday 16 Oct 2026, 12:00 in Zagreb. */
    static final Instant FRIDAY_NOON = Instant.parse("2026-10-16T10:00:00Z");

    @MockitoBean AuthRequestLimiter requestLimiter;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired ParticipationLock participationLock;

    TestUsers users;
    long tuesday;
    long membership;
    MockHttpSession ana;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE test_attendance");
        jdbc.execute("TRUNCATE email_outbox, registration_events, training_registrations, training_session_events, "
                + "session_coaches, training_sessions, users RESTART IDENTITY CASCADE");
        clock.set(FRIDAY_NOON);
        users = new TestUsers(jdbc, passwordEncoder);
        // Tuesday 20 Oct 2026, 18:30-20:00 Zagreb.
        tuesday = jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at)
                VALUES ((SELECT id FROM clubs), TIMESTAMPTZ '2026-10-20T16:30:00Z', TIMESTAMPTZ '2026-10-20T18:00:00Z')
                RETURNING id""", Long.class);
        membership = users.active("ana@klub.hr", "MEMBER").membershipId();
        jdbc.update("UPDATE users SET first_name = 'Ana' WHERE email = 'ana@klub.hr'");
        ana = login("ana@klub.hr");
    }

    // ---------------------------------------------------------------- AC-03: once, idempotent

    @Test
    void memberRegistersOnceAndGetsOneConfirmation() throws Exception {
        register(ana)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainingId").value(tuesday))
                .andExpect(jsonPath("$.membershipId").value(membership))
                .andExpect(jsonPath("$.registrationStatus").value("REGISTERED"))
                .andExpect(jsonPath("$.attendanceStatus").value("UNMARKED"))
                .andExpect(jsonPath("$.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED"));
        register(ana)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.notificationStatus").value("NOT_REQUIRED"));

        assertThat(count("training_registrations")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM registration_events", String.class)).containsExactly("REGISTERED");
        assertThat(jdbc.queryForList("SELECT event_type FROM email_outbox", String.class))
                .containsExactly("REGISTRATION_CONFIRMED");
        assertThat(renderedOutbox("REGISTRATION_CONFIRMED").body())
                .contains("Bok Ana").contains("utorak 20. 10. 2026. u 18:30");

        // The schedule shows the member's state; staff sees the count but members never see others.
        mvc.perform(get("/api/v1/trainings/" + tuesday).session(ana))
                .andExpect(jsonPath("$.myRegistrationStatus").value("REGISTERED"))
                .andExpect(jsonPath("$.canRegister").value(false))
                .andExpect(jsonPath("$.canCancelRegistration").value(true))
                .andExpect(jsonPath("$.registeredCount").doesNotExist());
        users.active("trener@klub.hr", "TRAINER");
        mvc.perform(get("/api/v1/trainings/" + tuesday).session(login("trener@klub.hr")))
                .andExpect(jsonPath("$.registeredCount").value(1));
    }

    @Test
    void concurrentDoubleClicksCreateOneRegistrationEventAndEmail() throws Exception {
        List<Integer> statuses = concurrently(8, () -> register(ana).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(count("training_registrations")).isEqualTo(1);
        assertThat(count("registration_events")).isEqualTo(1);
        assertThat(count("email_outbox")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC-04: cancel and re-register

    @Test
    void cancelAndReRegisterReuseTheSameRegistration() throws Exception {
        register(ana).andExpect(status().isOk());

        cancel(ana)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED"));
        cancel(ana).andExpect(jsonPath("$.changed").value(false)).andExpect(jsonPath("$.notificationStatus").value("NOT_REQUIRED"));
        mvc.perform(get("/api/v1/trainings/" + tuesday).session(ana))
                .andExpect(jsonPath("$.myRegistrationStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.canRegister").value(true));
        register(ana).andExpect(jsonPath("$.registrationStatus").value("REGISTERED")).andExpect(jsonPath("$.changed").value(true));

        assertThat(count("training_registrations")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM registration_events ORDER BY id", String.class))
                .containsExactly("REGISTERED", "CANCELLED", "RE_REGISTERED");
        assertThat(jdbc.queryForList("SELECT event_type FROM email_outbox ORDER BY id", String.class))
                .containsExactly("REGISTRATION_CONFIRMED", "REGISTRATION_CANCELLED", "REGISTRATION_CONFIRMED");
        assertThat(renderedOutbox("REGISTRATION_CANCELLED").subject()).contains("je otkazana");
    }

    @Test
    void cancellingIsAllowedAfterTheStartAndEvenAfterTheEndWhileUnmarked() throws Exception {
        register(ana).andExpect(status().isOk());
        clock.set(Instant.parse("2026-10-20T17:00:00Z")); // 19:00 local, in progress
        cancel(ana).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        // Registering a session in progress is still allowed (spec §5.1: "planiran ili započet").
        register(ana).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));

        clock.set(Instant.parse("2026-10-20T19:00:00Z")); // ended
        cancel(ana).andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        register(ana)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void cancellingWithoutRegistrationIsNotFound() throws Exception {
        cancel(ana).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/trainings/999999/my-registration").session(ana).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- AC-05: attendance blocks own changes

    @Test
    void recordedAttendanceBlocksOwnChanges() throws Exception {
        register(ana).andExpect(status().isOk());
        recordAttendance(tuesday, membership, "PRESENT");

        cancel(ana)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ATTENDANCE_ALREADY_RECORDED"));
        mvc.perform(get("/api/v1/trainings/" + tuesday).session(ana))
                .andExpect(jsonPath("$.myRegistrationStatus").value("REGISTERED"))
                .andExpect(jsonPath("$.myAttendanceStatus").value("PRESENT"))
                .andExpect(jsonPath("$.canCancelRegistration").value(false))
                .andExpect(jsonPath("$.canRegister").value(false));

        // ABSENT as well; back to UNMARKED (trainer reset) allows cancelling again.
        recordAttendance(tuesday, membership, "ABSENT");
        cancel(ana).andExpect(status().isConflict());
        jdbc.execute("TRUNCATE test_attendance");
        cancel(ana).andExpect(status().isOk());

        // Re-registering is refused once attendance is recorded (e.g. trainer marked PRESENT after the cancel).
        recordAttendance(tuesday, membership, "PRESENT");
        register(ana).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ATTENDANCE_ALREADY_RECORDED"));
        assertThat(jdbc.queryForObject("SELECT status FROM training_registrations", String.class)).isEqualTo("CANCELLED");
    }

    @Test
    void concurrentCancelAndAttendanceNeverBypassTheCheck() throws Exception {
        register(ana).andExpect(status().isOk());
        CountDownLatch attendanceLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // "Trainer" marks PRESENT the way the attendance module must: participation lock first, commit later.
            Future<?> trainer = pool.submit(() -> tx.executeWithoutResult(status -> {
                participationLock.acquire(tuesday, membership);
                recordAttendance(tuesday, membership, "PRESENT");
                attendanceLocked.countDown();
                sleep(700);
            }));
            assertThat(attendanceLocked.await(5, TimeUnit.SECONDS)).isTrue();
            // The member cancels while the trainer's transaction is still open: it must wait and then see PRESENT.
            Future<Integer> member = pool.submit(() -> cancel(ana).andReturn().getResponse().getStatus());
            trainer.get(10, TimeUnit.SECONDS);
            assertThat(member.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM training_registrations", String.class)).isEqualTo("REGISTERED");
        assertThat(count("registration_events")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- cancelled session, inactive membership

    @Test
    void cancelledSessionAndInactiveMembershipBlockNewRegistrations() throws Exception {
        long thursday = jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at, status, cancelled_at)
                VALUES ((SELECT id FROM clubs), TIMESTAMPTZ '2026-10-22T16:30:00Z', TIMESTAMPTZ '2026-10-22T18:00:00Z',
                        'CANCELLED', now()) RETURNING id""", Long.class);
        mvc.perform(put("/api/v1/trainings/" + thursday + "/my-registration").session(ana).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("TRAINING_CANCELLED"));

        register(ana).andExpect(status().isOk());
        cancel(ana).andExpect(status().isOk());
        jdbc.update("UPDATE club_memberships SET status = 'INACTIVE' WHERE id = ?", membership);
        // Re-registering follows the same rules as registering.
        register(ana)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("MEMBERSHIP_INACTIVE"));
        mvc.perform(get("/api/v1/trainings/" + tuesday).session(ana)).andExpect(jsonPath("$.canRegister").value(false));
    }

    @Test
    void onlyMembersRegisterThemselves() throws Exception {
        users.active("trener@klub.hr", "TRAINER");
        MockHttpSession trainer = login("trener@klub.hr");
        mvc.perform(put("/api/v1/trainings/" + tuesday + "/my-registration").session(trainer).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- integration with session cancellation

    @Test
    void cancellingTheSessionNotifiesRegisteredMembers() throws Exception {
        register(ana).andExpect(status().isOk());
        users.active("trener@klub.hr", "TRAINER");
        mvc.perform(post("/api/v1/trainings/" + tuesday + "/cancel").session(login("trener@klub.hr")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED"));

        assertThat(jdbc.queryForObject("SELECT recipient_email FROM email_outbox WHERE event_type = 'TRAINING_CANCELLED'",
                String.class)).isEqualTo("ana@klub.hr");
        // The registration itself is kept.
        assertThat(jdbc.queryForObject("SELECT status FROM training_registrations", String.class)).isEqualTo("REGISTERED");
    }

    @Test
    void registrationRacingASessionCancellationIsEitherRefusedOrNotified() throws Exception {
        users.active("trener@klub.hr", "TRAINER");
        MockHttpSession trainer = login("trener@klub.hr");
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM email_outbox");
            jdbc.update("DELETE FROM registration_events");
            jdbc.update("DELETE FROM training_registrations");
            jdbc.update("UPDATE training_sessions SET status = 'SCHEDULED', cancelled_at = NULL WHERE id = ?", tuesday);
            long version = jdbc.queryForObject("SELECT version FROM training_sessions WHERE id = ?", Long.class, tuesday);
            List<Callable<Integer>> tasks = List.of(
                    () -> register(ana).andReturn().getResponse().getStatus(),
                    () -> mvc.perform(post("/api/v1/trainings/" + tuesday + "/cancel").session(trainer).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + version + "}"))
                            .andReturn().getResponse().getStatus());
            List<Integer> statuses = concurrently(tasks);

            assertThat(statuses.get(1)).isEqualTo(200);
            if (statuses.get(0) == 200) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE event_type = 'TRAINING_CANCELLED'",
                        Integer.class)).as("registered before the cancel committed -> notified").isEqualTo(1);
            } else {
                assertThat(statuses.get(0)).isEqualTo(422);
                assertThat(count("training_registrations")).isZero();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    ResultActions register(MockHttpSession session) throws Exception {
        return mvc.perform(put("/api/v1/trainings/" + tuesday + "/my-registration").session(session).with(csrf()));
    }

    ResultActions cancel(MockHttpSession session) throws Exception {
        return mvc.perform(delete("/api/v1/trainings/" + tuesday + "/my-registration").session(session).with(csrf()));
    }

    MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    OutboxTestSupport.RenderedEmail renderedOutbox(String type) {
        String payload = jdbc.queryForObject("SELECT payload::text FROM email_outbox WHERE event_type = ? ORDER BY id LIMIT 1",
                String.class, type);
        return OutboxTestSupport.render(context, type, json.readValue(payload, new TypeReference<Map<String, Object>>() { }));
    }

    List<Integer> concurrently(int threads, Callable<Integer> task) throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return concurrently(tasks);
    }

    List<Integer> concurrently(List<Callable<Integer>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Integer>> futures = tasks.stream().map(t -> pool.submit(() -> {
                start.await();
                return t.call();
            })).toList();
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
