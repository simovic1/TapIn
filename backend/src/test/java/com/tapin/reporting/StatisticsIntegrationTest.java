package com.tapin.reporting;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BJJ-31: own statistics and training history (spec §9.1, §9.3, decision D-05). "Today" is Friday 16. 10. 2026 in
 * Zagreb. Ana joined on 1. 9. 2026 and was inactive from 20. 9. to 27. 9.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class StatisticsIntegrationTest {

    @MockitoBean AuthRequestLimiter requestLimiter;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;

    TestUsers users;
    long ana;
    long trainerUser;
    MockHttpSession anaSession;
    MockHttpSession trainer;
    long s0, s1, s2, s3, s4, s5, s6, s7, s8, s9;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE belt_period_audit_logs, member_belt_periods, attendance_audit_logs, training_attendances, "
                + "registration_events, training_registrations, training_session_events, session_coaches, "
                + "training_sessions, email_outbox, users RESTART IDENTITY CASCADE");
        clock.set(Instant.parse("2026-10-16T10:00:00Z"));
        users = new TestUsers(jdbc, passwordEncoder);
        ana = users.active("ana@klub.hr", "MEMBER").membershipId();
        jdbc.update("UPDATE club_memberships SET joined_on = DATE '2026-09-01' WHERE id = ?", ana);
        statusEvent(ana, null, "ACTIVE", "2026-08-31T22:00:00Z"); // 1. 9. 00:00 Zagreb
        statusEvent(ana, "ACTIVE", "INACTIVE", "2026-09-20T00:00:00Z");
        statusEvent(ana, "INACTIVE", "ACTIVE", "2026-09-27T00:00:00Z");
        TestUsers.Created coach = users.active("trener@klub.hr", "MEMBER", "TRAINER");
        trainerUser = coach.userId();
        anaSession = login("ana@klub.hr");
        trainer = login("trener@klub.hr");

        s1 = session("2026-08-25", "HELD");            // before joining: not in the denominator
        s0 = session("2026-09-05", "HELD");            // registered, PRESENT
        s2 = session("2026-09-18", "HELD");            // registered, PRESENT
        s3 = session("2026-09-22", "HELD");            // inactive: not in the denominator
        s4 = session("2026-10-01", "HELD");            // registered, ABSENT
        s5 = session("2026-10-05", "CANCELLED");       // registered, cancelled training: never counted
        s7 = session("2026-10-08", "HELD");            // walk-in PRESENT
        s6 = session("2026-10-12", "HELD");            // registered, UNMARKED
        s8 = session("2026-10-15", "HELD");            // cancelled registration, came anyway: PRESENT
        s9 = session("2026-10-20", "SCHEDULED");       // future: not counted, not in the history
        for (long s : List.of(s0, s2, s4, s5, s6, s9)) {
            register(s, "REGISTERED");
        }
        register(s8, "CANCELLED");
        mark(s0, "PRESENT");
        mark(s2, "PRESENT");
        mark(s4, "ABSENT");
        mark(s7, "PRESENT");
        mark(s8, "PRESENT");
    }

    @Test
    void ownStatisticsFollowTheCountingRules() throws Exception {
        JsonNode stats = body(anaSession, get("/api/v1/me/statistics"));

        assertThat(stats.get("membershipId").asLong()).isEqualTo(ana);
        assertThat(stats.get("memberSince").asString()).isEqualTo("2026-09-01");
        JsonNode windows = stats.get("windows");
        assertThat(windows.get("today").asString()).isEqualTo("2026-10-16");
        assertThat(windows.get("thisMonthFrom").asString()).isEqualTo("2026-10-01");
        assertThat(windows.get("last7DaysFrom").asString()).isEqualTo("2026-10-10");
        assertThat(windows.get("last30DaysFrom").asString()).isEqualTo("2026-09-17");

        assertThat(stats.get("totalPresent").asInt()).isEqualTo(4);        // s0 s2 s7 s8
        assertThat(stats.get("presentThisMonth").asInt()).isEqualTo(2);    // s7 s8
        assertThat(stats.get("presentLast7Days").asInt()).isEqualTo(1);    // s8
        assertThat(stats.get("presentLast30Days").asInt()).isEqualTo(3);   // s2 s7 s8
        assertThat(stats.get("registeredButAbsent").asInt()).isEqualTo(1); // s4; UNMARKED s6 is not an absence

        JsonNode registrations = stats.get("registrations");
        assertThat(registrations.get("total").asInt()).isEqualTo(4);      // s0 s2 s4 s6 (s5 cancelled training, s8 cancelled)
        assertThat(registrations.get("present").asInt()).isEqualTo(2);
        assertThat(registrations.get("absent").asInt()).isEqualTo(1);
        assertThat(registrations.get("unmarked").asInt()).isEqualTo(1);

        // Held while active since joining: s0 s2 s4 s7 s6 s8 (not s1 before joining, not s3 while inactive).
        assertRate(stats.get("attendanceRate"), 4, 6, "HELD_SESSIONS_WHILE_ACTIVE");
        assertRate(stats.get("registrationAttendanceRate"), 2, 3, "REGISTRATIONS");

        assertThat(stats.get("currentBelt").asString()).isEqualTo("UNKNOWN");
        assertThat(stats.get("unassignedTrainings").asInt()).isEqualTo(4); // belts and totals agree
    }

    @Test
    void correctionsAndCancellationsAreReflectedImmediately() throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM training_attendances WHERE session_id = ? AND membership_id = ?",
                Long.class, s4, ana);
        call(trainer, put("/api/v1/trainings/" + s4 + "/attendance/" + ana).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("status", "PRESENT", "expectedVersion", version))));

        JsonNode stats = body(anaSession, get("/api/v1/me/statistics"));
        assertThat(stats.get("totalPresent").asInt()).isEqualTo(5);
        assertThat(stats.get("presentThisMonth").asInt()).isEqualTo(3);
        assertThat(stats.get("registeredButAbsent").asInt()).isZero();
        assertRate(stats.get("attendanceRate"), 5, 6, "HELD_SESSIONS_WHILE_ACTIVE");
        assertRate(stats.get("registrationAttendanceRate"), 3, 3, "REGISTRATIONS");

        // A held session marked cancelled afterwards (data correction) drops out of every number.
        jdbc.update("UPDATE training_sessions SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?", s8);
        stats = body(anaSession, get("/api/v1/me/statistics"));
        assertThat(stats.get("totalPresent").asInt()).isEqualTo(4);
        assertRate(stats.get("attendanceRate"), 4, 5, "HELD_SESSIONS_WHILE_ACTIVE");
    }

    @Test
    void emptyDenominatorIsNotAnError() throws Exception {
        long bob = users.active("bob@klub.hr", "MEMBER").membershipId();
        jdbc.update("UPDATE club_memberships SET joined_on = DATE '2026-10-16' WHERE id = ?", bob);
        statusEvent(bob, null, "ACTIVE", "2026-10-15T22:00:00Z");

        JsonNode stats = body(login("bob@klub.hr"), get("/api/v1/me/statistics"));
        assertThat(stats.get("membershipId").asLong()).isEqualTo(bob);
        assertThat(stats.get("totalPresent").asInt()).isZero();
        assertRate(stats.get("attendanceRate"), 0, 0, "HELD_SESSIONS_WHILE_ACTIVE");
        assertRate(stats.get("registrationAttendanceRate"), 0, 0, "REGISTRATIONS");
        assertThat(body(login("bob@klub.hr"), get("/api/v1/me/attendance")).get("items")).isEmpty();
    }

    @Test
    void historyListsStartedTrainingsWithRegistrationAndAttendance() throws Exception {
        JsonNode page = body(anaSession, get("/api/v1/me/attendance"));
        assertThat(page.get("page").get("totalElements").asInt()).isEqualTo(7);
        List<String> rows = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            rows.add(item.get("localDate").asString() + " " + item.get("trainingStatus").asString() + " "
                    + item.get("registrationStatus").asString() + " " + item.get("attendanceStatus").asString());
        }
        assertThat(rows).containsExactly(
                "2026-10-15 HELD CANCELLED PRESENT",
                "2026-10-12 HELD REGISTERED UNMARKED",
                "2026-10-08 HELD NONE PRESENT",
                "2026-10-05 CANCELLED REGISTERED UNMARKED",
                "2026-10-01 HELD REGISTERED ABSENT",
                "2026-09-18 HELD REGISTERED PRESENT",
                "2026-09-05 HELD REGISTERED PRESENT");

        JsonNode october = body(anaSession, get("/api/v1/me/attendance?from=2026-10-01&to=2026-10-13&size=2"));
        assertThat(october.get("page").get("totalElements").asInt()).isEqualTo(4);
        assertThat(october.get("page").get("totalPages").asInt()).isEqualTo(2);
        assertThat(october.get("items").get(0).get("trainingId").asLong()).isEqualTo(s6);
    }

    @Test
    void aMemberOnlyEverGetsTheirOwnNumbers() throws Exception {
        long bob = users.active("bob@klub.hr", "MEMBER").membershipId();
        MockHttpSession bobSession = login("bob@klub.hr");
        assertThat(body(bobSession, get("/api/v1/me/statistics")).get("membershipId").asLong()).isEqualTo(bob);
        assertThat(statusOf(bobSession, get("/api/v1/members/" + ana + "/statistics"))).isEqualTo(403);
        assertThat(statusOf(bobSession, get("/api/v1/members/" + ana + "/attendance"))).isEqualTo(403);
        assertThat(statusOf(bobSession, get("/api/v1/me/statistics?membershipId=" + ana))).isEqualTo(200);
        assertThat(body(bobSession, get("/api/v1/me/statistics?membershipId=" + ana)).get("membershipId").asLong())
                .isEqualTo(bob);

        // Staff see any member; unknown member is 404.
        assertThat(body(trainer, get("/api/v1/members/" + ana + "/statistics")).get("totalPresent").asInt()).isEqualTo(4);
        assertThat(statusOf(trainer, get("/api/v1/members/999999/statistics"))).isEqualTo(404);
    }

    // ---------------------------------------------------------------- helpers

    private static void assertRate(JsonNode rate, int numerator, int denominator, String basis) {
        assertThat(rate.get("numerator").asInt()).isEqualTo(numerator);
        assertThat(rate.get("denominator").asInt()).isEqualTo(denominator);
        assertThat(rate.get("basis").asString()).isEqualTo(basis);
        if (denominator == 0) {
            assertThat(rate.get("rate").isNull()).isTrue();
        } else {
            assertThat(rate.get("rate").asDouble()).isCloseTo((double) numerator / denominator,
                    org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    private void statusEvent(long membershipId, String oldStatus, String newStatus, String effectiveAt) {
        jdbc.update("INSERT INTO membership_status_events (membership_id, old_status, new_status, effective_at) "
                + "VALUES (?, ?, ?, CAST(? AS TIMESTAMPTZ))", membershipId, oldStatus, newStatus, effectiveAt);
    }

    /** 16:30 UTC = 18:30 Zagreb (summer time lasts until 25. 10.), so the local date is the given one. */
    private long session(String localDate, String status) {
        return jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at, status, cancelled_at)
                VALUES ((SELECT id FROM clubs), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ) + interval '90 minutes', ?,
                        CASE WHEN ? = 'CANCELLED' THEN now() END)
                RETURNING id""", Long.class, localDate + "T16:30:00Z", localDate + "T16:30:00Z", status, status);
    }

    private void register(long session, String status) {
        jdbc.update("""
                INSERT INTO training_registrations (session_id, club_id, membership_id, status, registered_at, cancelled_at)
                VALUES (?, (SELECT id FROM clubs), ?, ?, now(), CASE WHEN ? = 'CANCELLED' THEN now() END)
                """, session, ana, status, status);
    }

    private void mark(long session, String status) {
        jdbc.update("""
                INSERT INTO training_attendances (session_id, club_id, membership_id, status, updated_by_user_id, updated_at)
                VALUES (?, (SELECT id FROM clubs), ?, ?, ?, now())
                """, session, ana, status, trainerUser);
    }

    private JsonNode body(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.session(session).with(csrf())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void call(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        body(session, request);
    }

    private int statusOf(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.session(session).with(csrf())).andReturn().getResponse().getStatus();
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
