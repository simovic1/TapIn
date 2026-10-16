package com.tapin.registrations;

import com.tapin.MutableClock;
import com.tapin.TestcontainersConfiguration;
import com.tapin.auth.AuthRequestLimiter;
import com.tapin.auth.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BJJ-29: reminder 3 h before the training (spec §8.2, AC-13/14/19, decision D-04), with a controlled clock. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class TrainingReminderIntegrationTest {

    @MockitoBean AuthRequestLimiter requestLimiter;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;
    @Autowired TrainingReminderService reminders;

    TestUsers users;
    MockHttpSession ana;
    long tuesday;      // 20 Oct 2026 18:30 Zagreb (summer time) = 16:30 UTC
    long nextTuesday;  // 27 Oct 2026 18:30 Zagreb (winter time) = 17:30 UTC

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE email_outbox, registration_events, training_registrations, training_session_events, "
                + "session_coaches, training_sessions, users RESTART IDENTITY CASCADE");
        clock.set(Instant.parse("2026-10-16T10:00:00Z"));
        users = new TestUsers(jdbc, passwordEncoder);
        users.active("ana@klub.hr", "MEMBER");
        users.active("ivo@klub.hr", "MEMBER");
        users.active("trener@klub.hr", "TRAINER");
        tuesday = session("2026-10-20T16:30:00Z");
        nextTuesday = session("2026-10-27T17:30:00Z");
        ana = login("ana@klub.hr");
        register(ana, tuesday);
        register(ana, nextTuesday);
    }

    // ---------------------------------------------------------------- AC-13 / AC-19: due exactly at start - 3 h

    @Test
    void reminderIsDueAt1530ZagrebInSummerAndWinterTime() {
        clock.set(Instant.parse("2026-10-20T13:29:59Z")); // 15:29:59 local
        assertThat(reminders.enqueueDue()).isZero();
        clock.set(Instant.parse("2026-10-20T13:30:00Z")); // 15:30 local
        assertThat(reminders.enqueueDue()).isEqualTo(1);
        assertThat(reminderRows(tuesday)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expires_at = TIMESTAMPTZ '2026-10-20T16:30:00Z' FROM email_outbox "
                + "WHERE event_type = 'TRAINING_REMINDER'", Boolean.class)).as("expires when the training starts").isTrue();

        // After the end of summer time 15:30 local is 14:30 UTC.
        clock.set(Instant.parse("2026-10-27T14:29:00Z"));
        assertThat(reminders.enqueueDue()).isZero();
        clock.set(Instant.parse("2026-10-27T14:30:00Z"));
        assertThat(reminders.enqueueDue()).isEqualTo(1);
        assertThat(reminderRows(nextTuesday)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC-14: no retroactive reminder

    @Test
    void registrationAfterTheReminderTimeGetsAConfirmationButNoReminder() throws Exception {
        clock.set(Instant.parse("2026-10-20T14:00:00Z")); // 16:00 local
        register(login("ivo@klub.hr"), tuesday);

        assertThat(reminders.enqueueDue()).isEqualTo(1); // Ana only (catch-up, registered before 15:30)
        assertThat(jdbc.queryForList("SELECT recipient_email FROM email_outbox WHERE event_type = 'TRAINING_REMINDER'",
                String.class)).containsExactly("ana@klub.hr");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE event_type = 'REGISTRATION_CONFIRMED' "
                + "AND recipient_email = 'ivo@klub.hr'", Integer.class)).isEqualTo(1);
    }

    @Test
    void reRegisteringAfterTheReminderTimeAlsoGetsNoReminder() throws Exception {
        clock.set(Instant.parse("2026-10-20T12:00:00Z"));
        mvc.perform(delete("/api/v1/trainings/" + tuesday + "/my-registration").session(ana).with(csrf()));
        clock.set(Instant.parse("2026-10-20T14:00:00Z"));
        register(ana, tuesday);
        assertThat(reminders.enqueueDue()).isZero();
    }

    // ---------------------------------------------------------------- D-04: catch up until the start, never after

    @Test
    void missedReminderIsCaughtUpUntilTheTrainingStartsButNotAfter() {
        clock.set(Instant.parse("2026-10-20T16:29:00Z")); // 18:29 local, the job was down since 15:30
        assertThat(reminders.enqueueDue()).isEqualTo(1);

        jdbc.update("DELETE FROM email_outbox");
        clock.set(Instant.parse("2026-10-20T16:30:00Z")); // started
        assertThat(reminders.enqueueDue()).isZero();
    }

    // ---------------------------------------------------------------- one per member and session

    @Test
    void restartsConcurrentWorkersAndMovingTheSessionNeverDuplicate() throws Exception {
        clock.set(Instant.parse("2026-10-20T13:31:00Z"));
        List<Integer> runs = concurrently(4, () -> reminders.enqueueDue());
        assertThat(runs.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(reminders.enqueueDue()).as("restart").isZero();

        // Moved one hour later: the new reminder time (16:30 local) arrives, but Ana was already reminded.
        users.active("drugi@klub.hr", "TRAINER");
        mvc.perform(patch("/api/v1/trainings/" + tuesday).session(login("trener@klub.hr")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"startAt\":\"2026-10-20T17:30:00Z\",\"endAt\":\"2026-10-20T19:00:00Z\"}"))
                .andExpect(status().isOk());
        clock.set(Instant.parse("2026-10-20T14:31:00Z"));
        assertThat(reminders.enqueueDue()).isZero();
        assertThat(reminderRows(tuesday)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- only REGISTERED, not cancelled, not opted out

    @Test
    void cancelledRegistrationCancelledTrainingAndSwitchedOffPreferenceGetNoReminder() throws Exception {
        clock.set(Instant.parse("2026-10-20T12:00:00Z"));
        mvc.perform(delete("/api/v1/trainings/" + tuesday + "/my-registration").session(ana).with(csrf()))
                .andExpect(status().isOk());
        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        assertThat(reminders.enqueueDue()).isZero();

        // Ana switches reminders off on her profile; the phone stays as it is.
        jdbc.update("UPDATE users SET phone = '+385 91 123 4567' WHERE email = 'ana@klub.hr'");
        long version = jdbc.queryForObject("SELECT version FROM users WHERE email = 'ana@klub.hr'", Long.class);
        mvc.perform(patch("/api/v1/me/profile").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + version + ",\"trainingRemindersEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainingRemindersEnabled").value(false))
                .andExpect(jsonPath("$.phone").value("+385 91 123 4567"));
        mvc.perform(get("/api/v1/me").session(ana)).andExpect(jsonPath("$.trainingRemindersEnabled").value(false));
        clock.set(Instant.parse("2026-10-27T14:30:00Z"));
        assertThat(reminders.enqueueDue()).isZero();
    }

    @Test
    void cancellingTheTrainingWithdrawsAWaitingReminder() throws Exception {
        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        assertThat(reminders.enqueueDue()).isEqualTo(1);

        mvc.perform(post("/api/v1/trainings/" + tuesday + "/cancel").session(login("trener@klub.hr")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE event_type = 'TRAINING_REMINDER'",
                String.class)).isEqualTo("FAILED");
        assertThat(reminders.enqueueDue()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    long session(String startUtc) {
        return jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at)
                VALUES ((SELECT id FROM clubs), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ) + interval '90 minutes')
                RETURNING id""", Long.class, startUtc, startUtc);
    }

    int reminderRows(long session) {
        return jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE event_type = 'TRAINING_REMINDER' "
                + "AND session_id = ?", Integer.class, session);
    }

    void register(MockHttpSession session, long training) throws Exception {
        mvc.perform(put("/api/v1/trainings/" + training + "/my-registration").session(session).with(csrf()))
                .andExpect(status().isOk());
    }

    MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    List<Integer> concurrently(int threads, Callable<Integer> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
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
}
