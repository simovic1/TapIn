package com.tapin.attendance;

import com.tapin.MutableClock;
import com.tapin.TestcontainersConfiguration;
import com.tapin.auth.AuthRequestLimiter;
import com.tapin.auth.TestUsers;
import com.tapin.notifications.OutboxTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BJJ-19: single attendance writes, roster and audit (spec §6-7, AC-06/08/09/10). Commits for real. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class AttendanceIntegrationTest {

    /** Tuesday 20 Oct 2026, 18:45 in Zagreb: the session (18:30-20:00) is in progress. */
    static final Instant DURING_TRAINING = Instant.parse("2026-10-20T16:45:00Z");

    @MockitoBean AuthRequestLimiter requestLimiter;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;
    @Autowired ApplicationContext context;

    TestUsers users;
    long training;
    long ana;
    long anaUser;
    MockHttpSession trainer;
    long trainerUser;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE email_outbox, attendance_audit_logs, training_attendances, registration_events, "
                + "training_registrations, training_session_events, session_coaches, training_sessions, users "
                + "RESTART IDENTITY CASCADE");
        clock.set(DURING_TRAINING);
        users = new TestUsers(jdbc, passwordEncoder);
        training = insertSession("2026-10-20T16:30:00Z", "2026-10-20T18:00:00Z", "SCHEDULED");
        TestUsers.Created created = users.active("ana@klub.hr", "MEMBER");
        ana = created.membershipId();
        anaUser = created.userId();
        jdbc.update("UPDATE users SET first_name = 'Ana', last_name = 'Horvat' WHERE id = ?", anaUser);
        trainerUser = users.active("trener@klub.hr", "MEMBER", "TRAINER").userId();
        trainer = login("trener@klub.hr");
    }

    // ---------------------------------------------------------------- AC-06: walk-in, cancelled registration

    @Test
    void trainerRecordsAWalkInWithFullAuditAndOneEmail() throws Exception {
        mark(trainer, ana, "PRESENT", 0, "Došla bez prijave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainingId").value(training))
                .andExpect(jsonPath("$.membershipId").value(ana))
                .andExpect(jsonPath("$.userId").value(anaUser))
                .andExpect(jsonPath("$.status").value("PRESENT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-20T16:45:00Z"))
                .andExpect(jsonPath("$.updatedByUserId").value(trainerUser))
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED"));

        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM attendance_audit_logs");
        assertThat(audit).containsEntry("actor_user_id", trainerUser).containsEntry("old_status", "UNMARKED")
                .containsEntry("new_status", "PRESENT").containsEntry("action_source", "SINGLE")
                .containsEntry("note", "Došla bez prijave");
        assertThat(jdbc.queryForObject("SELECT occurred_at = TIMESTAMPTZ '2026-10-20T16:45:00Z' FROM attendance_audit_logs",
                Boolean.class)).isTrue();
        assertThat(rendered("ATTENDANCE_RECORDED").body()).contains("Bok Ana").contains("utorak 20. 10. 2026. u 18:30")
                .contains("dolazak (prisutni)");

        mvc.perform(get("/api/v1/trainings/" + training + "/roster").session(trainer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.training.trainingId").value(training))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].firstName").value("Ana"))
                .andExpect(jsonPath("$.entries[0].registrationStatus").isEmpty())
                .andExpect(jsonPath("$.entries[0].attendanceStatus").value("PRESENT"))
                .andExpect(jsonPath("$.entries[0].attendanceVersion").value(1))
                .andExpect(jsonPath("$.entries[0].attendanceUpdatedByUserId").value(trainerUser))
                .andExpect(jsonPath("$.counts.present").value(1))
                .andExpect(jsonPath("$.counts.registered").value(0));
    }

    @Test
    void rosterShowsRegisteredCancelledAndRecordedMembersWithCounts() throws Exception {
        MockHttpSession anaSession = login("ana@klub.hr");
        register(anaSession).andExpect(status().isOk());
        long ivo = users.active("ivo@klub.hr", "MEMBER").membershipId();
        jdbc.update("UPDATE users SET first_name = 'Ivo', last_name = 'Babić' WHERE email = 'ivo@klub.hr'");
        MockHttpSession ivoSession = login("ivo@klub.hr");
        register(ivoSession).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/trainings/" + training + "/my-registration").session(ivoSession).with(csrf()))
                .andExpect(status().isOk());
        // Ivo cancelled but came anyway (spec §5.2).
        mark(trainer, ivo, "PRESENT", 0, null).andExpect(status().isOk());

        mvc.perform(get("/api/v1/trainings/" + training + "/roster").session(trainer))
                .andExpect(jsonPath("$.entries[0].lastName").value("Babić"))
                .andExpect(jsonPath("$.entries[0].registrationStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.entries[0].attendanceStatus").value("PRESENT"))
                .andExpect(jsonPath("$.entries[1].lastName").value("Horvat"))
                .andExpect(jsonPath("$.entries[1].registrationStatus").value("REGISTERED"))
                .andExpect(jsonPath("$.entries[1].attendanceStatus").value("UNMARKED"))
                .andExpect(jsonPath("$.entries[1].attendanceVersion").value(0))
                .andExpect(jsonPath("$.counts.registered").value(1))
                .andExpect(jsonPath("$.counts.present").value(1))
                .andExpect(jsonPath("$.counts.absent").value(0))
                .andExpect(jsonPath("$.counts.unmarked").value(1));
    }

    // ---------------------------------------------------------------- AC-10, AC-08: idempotent, corrections

    @Test
    void sameStatusAgainChangesNothingEvenWithAnOldVersion() throws Exception {
        mark(trainer, ana, "ABSENT", 0, null).andExpect(status().isOk());

        // A double tap sends the same request (same old version) again: no event, no e-mail, no 409.
        mark(trainer, ana, "ABSENT", 0, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.notificationStatus").value("NOT_REQUIRED"));
        assertThat(count("attendance_audit_logs")).isEqualTo(1);
        assertThat(count("email_outbox")).isEqualTo(1);
    }

    @Test
    void correctionFromAbsentToPresentKeepsBothStatesInTheAudit() throws Exception {
        mark(trainer, ana, "ABSENT", 0, null).andExpect(status().isOk());
        clock.set(Instant.parse("2026-10-21T08:00:00Z"));

        putAttendance(trainer, ana, Map.of("status", "PRESENT", "expectedVersion", 1, "source", "CORRECTION",
                "note", "Ipak je bila"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("PRESENT"));

        assertThat(jdbc.queryForList("SELECT old_status || '>' || new_status || ':' || action_source "
                + "FROM attendance_audit_logs ORDER BY id", String.class))
                .containsExactly("UNMARKED>ABSENT:SINGLE", "ABSENT>PRESENT:CORRECTION");
        assertThat(count("email_outbox")).isEqualTo(2);
        mvc.perform(get("/api/v1/trainings/" + training + "/attendance/audit").session(trainer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].previousStatus").value("ABSENT"))
                .andExpect(jsonPath("$.items[0].newStatus").value("PRESENT"))
                .andExpect(jsonPath("$.items[0].source").value("CORRECTION"))
                .andExpect(jsonPath("$.items[0].note").value("Ipak je bila"))
                .andExpect(jsonPath("$.items[0].actorUserId").value(trainerUser))
                .andExpect(jsonPath("$.items[0].occurredAt").value("2026-10-21T08:00:00Z"))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mvc.perform(get("/api/v1/trainings/" + training + "/attendance/audit").session(trainer)
                        .param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.items[0].newStatus").value("ABSENT"))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void auditRowsCannotBeChangedOrDeleted() throws Exception {
        mark(trainer, ana, "PRESENT", 0, null).andExpect(status().isOk());

        assertThatThrownBy(() -> jdbc.update("UPDATE attendance_audit_logs SET new_status = 'ABSENT'"))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM attendance_audit_logs"))
                .rootCause().hasMessageContaining("append-only");
        assertThat(count("attendance_audit_logs")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- AC-09: optimistic locking

    @Test
    void staleVersionIsAConflictWithTheFreshRosterRow() throws Exception {
        mark(trainer, ana, "PRESENT", 0, null).andExpect(status().isOk());

        // A second trainer still sees "UNMARKED, version 0" and wants ABSENT: no silent overwrite.
        users.active("drugi@klub.hr", "TRAINER");
        mark(login("drugi@klub.hr"), ana, "ABSENT", 0, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentState.membershipId").value(ana))
                .andExpect(jsonPath("$.currentState.firstName").value("Ana"))
                .andExpect(jsonPath("$.currentState.attendanceStatus").value("PRESENT"))
                .andExpect(jsonPath("$.currentState.attendanceVersion").value(1))
                .andExpect(jsonPath("$.currentState.attendanceUpdatedByUserId").value(trainerUser));
        assertThat(jdbc.queryForObject("SELECT status FROM training_attendances", String.class)).isEqualTo("PRESENT");
        assertThat(count("attendance_audit_logs")).isEqualTo(1);
    }

    @Test
    void returningToUnmarkedKeepsTheStaleRequestProtection() throws Exception {
        mark(trainer, ana, "PRESENT", 0, null).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/trainings/" + training + "/attendance/" + ana).session(trainer).with(csrf())
                        .param("expectedVersion", "1").param("note", "Krivo označena"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNMARKED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.changed").value(true));
        // An old request that was prepared before any mark ("version 0") must not succeed after the reset.
        mark(trainer, ana, "ABSENT", 0, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentState.attendanceStatus").value("UNMARKED"))
                .andExpect(jsonPath("$.currentState.attendanceVersion").value(2));
        mark(trainer, ana, "ABSENT", 2, null).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));

        // Reset with a stale version is a conflict too; resetting something already UNMARKED is a no-op.
        mvc.perform(delete("/api/v1/trainings/" + training + "/attendance/" + ana).session(trainer).with(csrf())
                        .param("expectedVersion", "2"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/trainings/" + training + "/attendance/" + ana).session(trainer).with(csrf())
                        .param("expectedVersion", "3"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/trainings/" + training + "/attendance/" + ana).session(trainer).with(csrf())
                        .param("expectedVersion", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));
        assertThat(jdbc.queryForList("SELECT new_status FROM attendance_audit_logs ORDER BY id", String.class))
                .containsExactly("PRESENT", "UNMARKED", "ABSENT", "UNMARKED");
        assertThat(jdbc.queryForObject("SELECT note FROM attendance_audit_logs WHERE id = 2", String.class))
                .isEqualTo("Krivo označena");
        assertThat(rendered("ATTENDANCE_RECORDED").body()).contains("dolazak (prisutni)");
    }

    @Test
    void concurrentFirstInsertsByDifferentTrainersLeaveOneWinner() throws Exception {
        users.active("drugi@klub.hr", "TRAINER");
        MockHttpSession second = login("drugi@klub.hr");
        List<Callable<JsonNode>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            MockHttpSession who = i % 2 == 0 ? trainer : second;
            String status = i % 2 == 0 ? "PRESENT" : "ABSENT";
            tasks.add(() -> {
                MvcResult r = mark(who, ana, status, 0, null).andReturn();
                JsonNode body = json.readTree(r.getResponse().getContentAsString());
                return json.createObjectNode().put("http", r.getResponse().getStatus())
                        .put("changed", body.path("changed").asBoolean(false));
            });
        }
        List<JsonNode> results = concurrently(tasks);

        assertThat(results).filteredOn(r -> r.get("changed").asBoolean()).hasSize(1);
        assertThat(results).allSatisfy(r -> assertThat(r.get("http").asInt()).isIn(200, 409));
        assertThat(count("training_attendances")).isEqualTo(1);
        assertThat(count("attendance_audit_logs")).isEqualTo(1);
        assertThat(count("email_outbox")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- integration with own registration (BJJ-17)

    @Test
    void recordedAttendanceBlocksAndResetReallowsTheMembersOwnCancel() throws Exception {
        MockHttpSession anaSession = login("ana@klub.hr");
        register(anaSession).andExpect(status().isOk());
        mark(trainer, ana, "PRESENT", 0, null).andExpect(status().isOk());

        mvc.perform(get("/api/v1/trainings/" + training).session(anaSession))
                .andExpect(jsonPath("$.myAttendanceStatus").value("PRESENT"))
                .andExpect(jsonPath("$.canCancelRegistration").value(false));
        mvc.perform(delete("/api/v1/trainings/" + training + "/my-registration").session(anaSession).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ATTENDANCE_ALREADY_RECORDED"));

        mvc.perform(delete("/api/v1/trainings/" + training + "/attendance/" + ana).session(trainer).with(csrf())
                        .param("expectedVersion", "1"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/trainings/" + training + "/my-registration").session(anaSession).with(csrf()))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- business rules

    @Test
    void inactiveMembersAreNotAddedToNewSessionsButHistoryIsAllowed() throws Exception {
        jdbc.update("UPDATE club_memberships SET status = 'INACTIVE' WHERE id = ?", ana);
        mark(trainer, ana, "PRESENT", 0, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("MEMBERSHIP_INACTIVE"));

        long past = insertSession("2026-09-15T16:30:00Z", "2026-09-15T18:00:00Z", "HELD");
        putAttendance(trainer, ana, past, Map.of("status", "PRESENT", "expectedVersion", 0, "source", "HISTORICAL_IMPORT"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT action_source FROM attendance_audit_logs", String.class))
                .isEqualTo("HISTORICAL_IMPORT");
    }

    @Test
    void cancelledSessionsUnknownMembersAndInvalidRequestsAreRefused() throws Exception {
        long cancelled = jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at, status, cancelled_at)
                VALUES ((SELECT id FROM clubs), TIMESTAMPTZ '2026-10-22T16:30:00Z', TIMESTAMPTZ '2026-10-22T18:00:00Z',
                        'CANCELLED', now()) RETURNING id""", Long.class);
        putAttendance(trainer, ana, cancelled, Map.of("status", "PRESENT", "expectedVersion", 0))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("TRAINING_CANCELLED"));
        mark(trainer, 999_999, "PRESENT", 0, null).andExpect(status().isNotFound());
        putAttendance(trainer, ana, 999_999, Map.of("status", "PRESENT", "expectedVersion", 0)).andExpect(status().isNotFound());
        putAttendance(trainer, ana, Map.of("status", "UNMARKED", "expectedVersion", 0)).andExpect(status().isBadRequest());
        putAttendance(trainer, ana, Map.of("status", "PRESENT", "expectedVersion", 0, "source", "BULK"))
                .andExpect(status().isBadRequest());
        putAttendance(trainer, ana, Map.of("status", "PRESENT")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/trainings/" + training + "/attendance/" + ana).session(trainer).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(count("attendance_audit_logs")).isZero();
    }

    @Test
    void membersCannotReadTheRosterOrRecordAttendance() throws Exception {
        MockHttpSession anaSession = login("ana@klub.hr");
        mvc.perform(get("/api/v1/trainings/" + training + "/roster").session(anaSession)).andExpect(status().isForbidden());
        mark(anaSession, ana, "PRESENT", 0, null).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/trainings/" + training + "/attendance/audit").session(anaSession))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    long insertSession(String start, String end, String status) {
        return jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at, status)
                VALUES ((SELECT id FROM clubs), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), ?) RETURNING id
                """, Long.class, start, end, status);
    }

    ResultActions mark(MockHttpSession session, long membershipId, String status, long expectedVersion, String note)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("expectedVersion", expectedVersion);
        body.put("note", note);
        return putAttendance(session, membershipId, body);
    }

    ResultActions putAttendance(MockHttpSession session, long membershipId, Map<String, Object> body) throws Exception {
        return putAttendance(session, membershipId, training, body);
    }

    ResultActions putAttendance(MockHttpSession session, long membershipId, long trainingId, Map<String, Object> body)
            throws Exception {
        return mvc.perform(put("/api/v1/trainings/" + trainingId + "/attendance/" + membershipId).session(session)
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    ResultActions register(MockHttpSession session) throws Exception {
        return mvc.perform(put("/api/v1/trainings/" + training + "/my-registration").session(session).with(csrf()));
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

    OutboxTestSupport.RenderedEmail rendered(String type) {
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM email_outbox WHERE event_type = ? ORDER BY id LIMIT 1", String.class, type);
        return OutboxTestSupport.render(context, type, json.readValue(payload, new TypeReference<Map<String, Object>>() { }));
    }

    <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = tasks.stream().map(t -> pool.submit(() -> {
                start.await();
                return t.call();
            })).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
