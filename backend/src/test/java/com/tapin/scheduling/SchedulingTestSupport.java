package com.tapin.scheduling;

import com.tapin.MutableClock;
import com.tapin.TestcontainersConfiguration;
import com.tapin.auth.AuthRequestLimiter;
import com.tapin.auth.TestUsers;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Common setup for scheduling integration tests: real PostgreSQL, a settable clock, base rules re-seeded. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class})
@Testcontainers(disabledWithoutDocker = true)
abstract class SchedulingTestSupport {

    static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");
    /** Friday 16 October 2026, 12:00 in Zagreb. */
    static final Instant FRIDAY_NOON = Instant.parse("2026-10-16T10:00:00Z");

    @MockitoBean AuthRequestLimiter requestLimiter;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;
    @Autowired SessionMaterializer materializer;

    TestUsers users;
    long tuesdayRule;
    long thursdayRule;

    @BeforeEach
    void resetSchedulingData() {
        jdbc.execute("TRUNCATE email_outbox, training_session_events, session_coaches, training_sessions, "
                + "recurring_schedules, users RESTART IDENTITY CASCADE");
        tuesdayRule = insertRule("TUESDAY");
        thursdayRule = insertRule("THURSDAY");
        users = new TestUsers(jdbc, passwordEncoder);
        clock.set(FRIDAY_NOON);
    }

    long insertRule(String weekday) {
        return jdbc.queryForObject("""
                INSERT INTO recurring_schedules (club_id, weekday, start_time, end_time, valid_from)
                VALUES ((SELECT id FROM clubs), ?, TIME '18:30', TIME '20:00', DATE '2026-01-01') RETURNING id
                """, Long.class, weekday);
    }

    List<Map<String, Object>> sessionsOf(long scheduleId) {
        return jdbc.queryForList(SESSION_COLUMNS + " WHERE schedule_id = ? ORDER BY occurrence_date", scheduleId);
    }

    Map<String, Object> occurrence(long scheduleId, LocalDate date) {
        return jdbc.queryForMap(SESSION_COLUMNS + " WHERE schedule_id = ? AND occurrence_date = ?", scheduleId, date);
    }

    /** Instants are read as ISO text in UTC, independent of the JVM time zone. */
    static final String SESSION_COLUMNS = """
            SELECT id, schedule_id, occurrence_date, status, created_by_user_id, cancellation_reason, version,
                   to_char(start_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS start_at,
                   to_char(end_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS end_at
            FROM training_sessions""";

    static Instant instant(Object utcText) {
        return Instant.parse((String) utcText);
    }

    static String localStart(Map<String, Object> session) {
        return instant(session.get("start_at")).atZone(ZAGREB).toLocalDateTime().toString();
    }

    MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    MockHttpSession trainer() throws Exception {
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        return login("trener@klub.hr");
    }

    ResultActions postJson(MockHttpSession session, String url, Object body) throws Exception {
        return mvc.perform(post(url).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    ResultActions patchJson(MockHttpSession session, String url, Object body) throws Exception {
        return mvc.perform(patch(url).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
