package com.tapin.belts;

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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BJJ-24/25: belt history, promotions, corrections and training counts per belt (spec §3.3, §9.3). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class BeltIntegrationTest {

    @MockitoBean AuthRequestLimiter requestLimiter;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;

    TestUsers users;
    long ana;
    long trainerUser;
    MockHttpSession trainer;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE belt_period_audit_logs, member_belt_periods, attendance_audit_logs, training_attendances, "
                + "registration_events, training_registrations, training_session_events, session_coaches, "
                + "training_sessions, email_outbox, users RESTART IDENTITY CASCADE");
        clock.set(Instant.parse("2026-10-16T10:00:00Z")); // Friday 12:00 in Zagreb
        users = new TestUsers(jdbc, passwordEncoder);
        ana = users.active("ana@klub.hr", "MEMBER").membershipId();
        trainerUser = users.active("trener@klub.hr", "MEMBER", "TRAINER").userId();
        trainer = login("trener@klub.hr");
    }

    // ---------------------------------------------------------------- BJJ-24: entry, promotion, rules

    @Test
    void withoutHistoryTheBeltIsUnknown() throws Exception {
        history(trainer, ana)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(ana))
                .andExpect(jsonPath("$.currentBelt").value("UNKNOWN"))
                .andExpect(jsonPath("$.currentBeltAwardedOn").isEmpty())
                .andExpect(jsonPath("$.totalTrainings").value(0))
                .andExpect(jsonPath("$.periods.length()").value(0));
        mvc.perform(get("/api/v1/members/" + ana).session(trainer)).andExpect(jsonPath("$.currentBelt").value("UNKNOWN"));
    }

    @Test
    void promotionAfterTrainingStartsCountingTheNextDayAndKeepsTheRealAwardDate() throws Exception {
        create(trainer, ana, Map.of("belt", "BLUE", "awardedOn", "2026-10-15", "promotionTiming", "AFTER_TRAINING",
                "note", "Promocija nakon redovnog treninga"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.beltPeriod.belt").value("BLUE"))
                .andExpect(jsonPath("$.beltPeriod.awardedOn").value("2026-10-15"))
                .andExpect(jsonPath("$.beltPeriod.effectiveFromDate").value("2026-10-16"))
                .andExpect(jsonPath("$.beltPeriod.effectiveToDate").isEmpty())
                .andExpect(jsonPath("$.beltPeriod.createdByUserId").value(trainerUser))
                .andExpect(jsonPath("$.beltPeriod.version").value(0))
                .andExpect(jsonPath("$.history.currentBelt").value("BLUE"))
                .andExpect(jsonPath("$.history.currentBeltAwardedOn").value("2026-10-15"));

        // The derived belt appears in the directory and in the member's own view (AC-21).
        mvc.perform(get("/api/v1/members/" + ana).session(trainer))
                .andExpect(jsonPath("$.currentBelt").value("BLUE"))
                .andExpect(jsonPath("$.currentBeltAwardedOn").value("2026-10-15"));
        mvc.perform(get("/api/v1/members").session(trainer).param("belt", "BLUE"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/members").session(trainer).param("belt", "UNKNOWN"))
                .andExpect(jsonPath("$.items[0].email").value("trener@klub.hr"));
        mvc.perform(get("/api/v1/me/belts").session(login("ana@klub.hr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBelt").value("BLUE"));
        assertThat(jdbc.queryForObject("SELECT action FROM belt_period_audit_logs", String.class)).isEqualTo("CREATED");
    }

    @Test
    void promotionBeforeTrainingStartsCountingTheSameDay() throws Exception {
        create(trainer, ana, Map.of("belt", "BLUE", "awardedOn", "2026-10-15", "promotionTiming", "BEFORE_TRAINING"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.beltPeriod.effectiveFromDate").value("2026-10-15"));
    }

    @Test
    void unknownAwardDateIsKeptUnknownAndNeedsAStartOfCounting() throws Exception {
        Map<String, Object> missing = new HashMap<>();
        missing.put("belt", "WHITE");
        missing.put("awardedOn", null);
        create(trainer, ana, missing)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.effectiveFromDate").exists());

        missing.put("effectiveFromDate", "2024-03-01");
        create(trainer, ana, missing)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.beltPeriod.awardedOn").isEmpty())
                .andExpect(jsonPath("$.beltPeriod.effectiveFromDate").value("2024-03-01"))
                .andExpect(jsonPath("$.history.currentBeltAwardedOn").isEmpty());
    }

    @Test
    void duplicateStartsAndInvalidChronologyAreRefused() throws Exception {
        create(trainer, ana, Map.of("belt", "BLUE", "awardedOn", "2026-03-01")).andExpect(status().isCreated());

        create(trainer, ana, Map.of("belt", "PURPLE", "awardedOn", "2026-03-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BELT_DUPLICATE_EFFECTIVE_DATE"));
        // Not higher than the previous belt.
        create(trainer, ana, Map.of("belt", "BLUE", "awardedOn", "2026-06-01"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("BELT_CHRONOLOGY_INVALID"));
        create(trainer, ana, Map.of("belt", "WHITE", "awardedOn", "2026-06-01"))
                .andExpect(status().isUnprocessableContent());
        // A higher belt before an existing lower one breaks the order too.
        create(trainer, ana, Map.of("belt", "PURPLE", "awardedOn", "2025-06-01"))
                .andExpect(status().isUnprocessableContent());
        // An earlier, lower belt is fine (initial entry of the history).
        create(trainer, ana, Map.of("belt", "WHITE", "awardedOn", "2024-05-01")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.history.periods[0].belt").value("WHITE"))
                .andExpect(jsonPath("$.history.periods[0].effectiveToDate").value("2026-03-02"));
        // Future award dates are invalid; stripes do not exist (unknown field, nothing stored).
        create(trainer, ana, Map.of("belt", "PURPLE", "awardedOn", "2026-10-20")).andExpect(status().isBadRequest());
        create(trainer, ana, Map.of("belt", "PURPLE", "awardedOn", "2026-10-01", "stripes", 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.beltPeriod.stripes").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'member_belt_periods' AND column_name LIKE '%stripe%'", Integer.class)).isZero();
    }

    @Test
    void correctionKeepsAnAuditTrailAndUsesOptimisticLocking() throws Exception {
        long id = create(trainer, ana, Map.of("belt", "BLUE", "awardedOn", "2026-10-15")).andReturn()
                .getResponse().getContentAsString().transform(this::periodId);

        correct(trainer, ana, id, Map.of("expectedVersion", 0, "awardedOn", "2026-10-14", "reason", "Krivi datum"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beltPeriod.awardedOn").value("2026-10-14"))
                .andExpect(jsonPath("$.beltPeriod.effectiveFromDate").value("2026-10-15")) // keeps AFTER_TRAINING
                .andExpect(jsonPath("$.beltPeriod.version").value(1));
        correct(trainer, ana, id, Map.of("expectedVersion", 0, "belt", "PURPLE", "reason", "Zastarjelo"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentState.awardedOn").value("2026-10-14"))
                .andExpect(jsonPath("$.currentState.version").value(1));
        correct(trainer, ana, id, Map.of("expectedVersion", 1, "belt", "PURPLE")).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/members/" + ana + "/belts/audit").session(trainer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].action").value("CORRECTED"))
                .andExpect(jsonPath("$.items[0].reason").value("Krivi datum"))
                .andExpect(jsonPath("$.items[0].oldValues.awardedOn").value("2026-10-15"))
                .andExpect(jsonPath("$.items[0].newValues.awardedOn").value("2026-10-14"))
                .andExpect(jsonPath("$.items[0].actorUserId").value(trainerUser))
                .andExpect(jsonPath("$.items[1].action").value("CREATED"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM belt_period_audit_logs"))
                .rootCause().hasMessageContaining("append-only");
        // There is no public DELETE of a belt period.
        int deleteStatus = mvc.perform(delete("/api/v1/members/" + ana + "/belts/" + id).session(trainer).with(csrf()))
                .andReturn().getResponse().getStatus();
        assertThat(deleteStatus).isGreaterThanOrEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_belt_periods", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentPromotionsNeverCreateInvalidPeriods() throws Exception {
        users.active("drugi@klub.hr", "TRAINER");
        MockHttpSession second = login("drugi@klub.hr");
        // Two trainers enter the same promotion (BLUE) on different days at the same moment: only one may exist.
        List<Integer> statuses = concurrently(List.of(
                () -> create(trainer, ana, Map.of("belt", "BLUE", "awardedOn", "2026-09-01")).andReturn().getResponse().getStatus(),
                () -> create(second, ana, Map.of("belt", "BLUE", "awardedOn", "2026-09-02")).andReturn().getResponse().getStatus(),
                () -> create(trainer, ana, Map.of("belt", "BLUE", "awardedOn", "2026-09-03")).andReturn().getResponse().getStatus(),
                () -> create(second, ana, Map.of("belt", "BLUE", "awardedOn", "2026-09-01")).andReturn().getResponse().getStatus()));

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s != 201).allSatisfy(s -> assertThat(s).isIn(409, 422));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_belt_periods", Integer.class)).isEqualTo(1);
    }

    @Test
    void membersCannotChangeOrReadOtherMembersBelts() throws Exception {
        long ivo = users.active("ivo@klub.hr", "MEMBER").membershipId();
        MockHttpSession anaSession = login("ana@klub.hr");

        create(anaSession, ana, Map.of("belt", "BLACK", "awardedOn", "2026-10-01")).andExpect(status().isForbidden()); // AC-28
        history(anaSession, ivo).andExpect(status().isForbidden()); // AC-29
        mvc.perform(get("/api/v1/members/" + ivo + "/belts/audit").session(anaSession)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_belt_periods", Integer.class)).isZero();
        history(trainer, 999_999).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- BJJ-25: counts per belt

    long heldSession(String startUtc, String status) {
        return jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at, status, cancelled_at)
                VALUES ((SELECT id FROM clubs), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ) + interval '90 minutes', ?,
                        CASE WHEN ? = 'CANCELLED' THEN now() END) RETURNING id
                """, Long.class, startUtc, startUtc, status, status);
    }

    void attend(long session, String status) {
        jdbc.update("""
                INSERT INTO training_attendances (session_id, club_id, membership_id, status, updated_by_user_id, updated_at)
                VALUES (?, (SELECT id FROM clubs), ?, ?, ?, now())""", session, ana, status, trainerUser);
    }

    @Test
    void countsPresentOnHeldSessionsByLocalDateAndAlwaysAddUp() throws Exception {
        attend(heldSession("2025-12-20T17:30:00Z", "HELD"), "PRESENT");    // before any known belt -> unassigned
        attend(heldSession("2026-03-03T17:30:00Z", "HELD"), "PRESENT");    // white
        long promotionDay = heldSession("2026-10-15T16:30:00Z", "HELD");   // award day 15 Oct
        attend(promotionDay, "PRESENT");
        attend(heldSession("2026-10-15T22:30:00Z", "HELD"), "PRESENT");    // 16 Oct 00:30 in Zagreb -> blue
        attend(heldSession("2026-09-08T16:30:00Z", "HELD"), "ABSENT");     // absent never counts
        attend(heldSession("2026-09-10T16:30:00Z", "CANCELLED"), "PRESENT"); // cancelled never counts
        attend(heldSession("2026-10-20T16:30:00Z", "SCHEDULED"), "PRESENT"); // not held yet
        Map<String, Object> white = new HashMap<>();
        white.put("belt", "WHITE");
        white.put("awardedOn", null);
        white.put("effectiveFromDate", "2026-01-01");
        create(trainer, ana, white).andExpect(status().isCreated());
        long blue = create(trainer, ana, Map.of("belt", "BLUE", "awardedOn", "2026-10-15", "promotionTiming", "AFTER_TRAINING"))
                .andReturn().getResponse().getContentAsString().transform(this::periodId);

        JsonNode h = body(history(trainer, ana));
        assertThat(h.get("totalTrainings").asInt()).isEqualTo(4);
        assertThat(h.get("unassignedTrainings").asInt()).isEqualTo(1);
        assertThat(h.get("periods").get(0).get("trainingsCount").asInt()).isEqualTo(2); // AC-22: promotion day is white
        assertThat(h.get("periods").get(1).get("trainingsCount").asInt()).isEqualTo(1);
        assertThat(h.get("currentBeltTrainings").asInt()).isEqualTo(1);
        assertSumsUp(h);

        // AC-23/26: correcting the promotion to "before training" moves the 15 Oct training to blue at once.
        correct(trainer, ana, blue, Map.of("expectedVersion", 0, "promotionTiming", "BEFORE_TRAINING", "reason", "Prije treninga"))
                .andExpect(jsonPath("$.history.periods[0].trainingsCount").value(1))
                .andExpect(jsonPath("$.history.periods[1].trainingsCount").value(2));

        // AC-24: an attendance correction changes the counts immediately, no stored counter to fix.
        jdbc.update("UPDATE training_attendances SET status = 'ABSENT' WHERE session_id = ?", promotionDay);
        JsonNode after = body(history(trainer, ana));
        assertThat(after.get("totalTrainings").asInt()).isEqualTo(3);
        assertThat(after.get("periods").get(1).get("trainingsCount").asInt()).isEqualTo(1);
        assertSumsUp(after);

        // AC-25: a historical training before the promotion counts for the historical belt.
        attend(heldSession("2026-05-05T16:30:00Z", "HELD"), "PRESENT");
        assertThat(body(history(trainer, ana)).get("periods").get(0).get("trainingsCount").asInt()).isEqualTo(2);
        // The roster shows the derived belt as well.
        mvc.perform(get("/api/v1/trainings/" + promotionDay + "/roster").session(trainer))
                .andExpect(jsonPath("$.entries[0].currentBelt").value("BLUE"));
    }

    void assertSumsUp(JsonNode h) {
        int sum = h.get("unassignedTrainings").asInt();
        for (JsonNode p : h.get("periods")) {
            sum += p.get("trainingsCount").asInt();
        }
        assertThat(sum).isEqualTo(h.get("totalTrainings").asInt());
    }

    // ---------------------------------------------------------------- helpers

    ResultActions history(MockHttpSession session, long membershipId) throws Exception {
        return mvc.perform(get("/api/v1/members/" + membershipId + "/belts").session(session));
    }

    ResultActions create(MockHttpSession session, long membershipId, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/members/" + membershipId + "/belts").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    ResultActions correct(MockHttpSession session, long membershipId, long periodId, Map<String, Object> body)
            throws Exception {
        return mvc.perform(patch("/api/v1/members/" + membershipId + "/belts/" + periodId).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    long periodId(String responseBody) {
        return json.readTree(responseBody).get("beltPeriod").get("beltPeriodId").asLong();
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
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
}
