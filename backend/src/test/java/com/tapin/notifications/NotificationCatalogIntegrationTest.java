package com.tapin.notifications;

import com.tapin.MutableClock;
import com.tapin.TestcontainersConfiguration;
import com.tapin.auth.AuthRequestLimiter;
import com.tapin.auth.TestUsers;
import com.tapin.notifications.email.EmailMessage;
import com.tapin.notifications.email.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BJJ-28: the whole e-mail catalogue end to end (spec §8.1). Business actions go through the HTTP API; the real
 * outbox processor renders the Croatian templates and hands them to a test provider that only records them, so
 * recipients, content and links are checked without sending anything to real members.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class, NotificationCatalogIntegrationTest.Provider.class})
@Testcontainers(disabledWithoutDocker = true)
class NotificationCatalogIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Provider {
        @Bean
        @Primary
        ScriptedEmailSender recordingEmailSender() {
            return new ScriptedEmailSender();
        }
    }

    @MockitoBean AuthRequestLimiter requestLimiter;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;
    @Autowired OutboxProcessor processor;
    @Autowired EmailSender sender;

    ScriptedEmailSender recorder;
    TestUsers users;
    long ana;
    long ivo;
    MockHttpSession trainer;
    long tuesday;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE belt_period_audit_logs, member_belt_periods, attendance_audit_logs, training_attendances, "
                + "registration_events, training_registrations, training_session_events, session_coaches, "
                + "training_sessions, email_outbox, users RESTART IDENTITY CASCADE");
        clock.set(Instant.parse("2026-10-16T10:00:00Z"));
        recorder = (ScriptedEmailSender) sender;
        recorder.reset();
        users = new TestUsers(jdbc, passwordEncoder);
        ana = member("ana@klub.hr", "Ana");
        ivo = member("ivo@klub.hr", "Ivo");
        member("marko@klub.hr", "Marko"); // never registered: must never get training mail
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        trainer = login("trener@klub.hr");
        tuesday = session("2026-10-20T16:30:00Z", "SCHEDULED", "Open mat");
    }

    long member(String email, String firstName) {
        long id = users.active(email, "MEMBER").membershipId();
        jdbc.update("UPDATE users SET first_name = ? WHERE email = ?", firstName, email);
        jdbc.update("INSERT INTO membership_status_events (membership_id, old_status, new_status, effective_at) "
                + "VALUES (?, NULL, 'ACTIVE', TIMESTAMPTZ '2024-03-01T00:00:00Z')", id);
        return id;
    }

    long session(String startUtc, String status, String title) {
        return jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at, status, title)
                VALUES ((SELECT id FROM clubs), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ) + interval '90 minutes', ?, ?)
                RETURNING id""", Long.class, startUtc, startUtc, status, title);
    }

    @Test
    void registrationMailsGoToTheMemberAndAreNotConfusedWithACancelledTraining() throws Exception {
        MockHttpSession anaSession = login("ana@klub.hr");
        call(anaSession, put("/api/v1/trainings/" + tuesday + "/my-registration"));
        call(anaSession, delete("/api/v1/trainings/" + tuesday + "/my-registration"));

        List<EmailMessage> mails = deliver();
        assertThat(mails).extracting(EmailMessage::to).containsOnly("ana@klub.hr");
        assertThat(mails.get(0).subject()).isEqualTo("Prijava zaprimljena: Open mat, utorak 20. 10. 2026. u 18:30");
        assertThat(mails.get(0).textBody()).contains("Bok Ana").contains("Prijava nije potvrda dolaska")
                .contains("https://tapin.test/treninzi/" + tuesday);
        assertThat(mails.get(1).subject()).startsWith("Otkazali ste prijavu: Open mat");
        assertThat(mails.get(1).textBody()).contains("Trening se i dalje održava");
    }

    @Test
    void sessionChangesGoOnlyToRegisteredMembers() throws Exception {
        call(login("ana@klub.hr"), put("/api/v1/trainings/" + tuesday + "/my-registration"));
        MockHttpSession ivoSession = login("ivo@klub.hr");
        call(ivoSession, put("/api/v1/trainings/" + tuesday + "/my-registration"));
        call(ivoSession, delete("/api/v1/trainings/" + tuesday + "/my-registration"));
        deliver();

        // Moved to Wednesday 19:00: only Ana (registered) is told; Ivo cancelled, Marko never registered.
        call(trainer, patch("/api/v1/trainings/" + tuesday).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"startAt\":\"2026-10-21T17:00:00Z\",\"endAt\":\"2026-10-21T18:30:00Z\"}"));
        List<EmailMessage> moved = deliver();
        assertThat(moved).extracting(EmailMessage::to).containsExactly("ana@klub.hr");
        assertThat(moved.get(0).subject()).isEqualTo("Promijenjen termin: Open mat, sada srijeda 21. 10. 2026. u 19:00");
        assertThat(moved.get(0).textBody()).contains("s utorak 20. 10. 2026. u 18:30 na srijeda 21. 10. 2026. u 19:00");

        call(trainer, post("/api/v1/trainings/" + tuesday + "/cancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Dvorana je zatvorena\"}"));
        List<EmailMessage> cancelled = deliver();
        assertThat(cancelled).extracting(EmailMessage::to).containsExactly("ana@klub.hr");
        assertThat(cancelled.get(0).subject()).isEqualTo("Trening je otkazan: Open mat, srijeda 21. 10. 2026. u 19:00");
        assertThat(cancelled.get(0).textBody()).contains("trener je otkazao trening").contains("Razlog: Dvorana je zatvorena")
                .doesNotContain("Otkazali ste prijavu");
    }

    @Test
    void everyRealAttendanceChangeIncludingHistoricalEntryInformsOnlyTheAffectedMember() throws Exception {
        long past = session("2026-09-15T16:30:00Z", "HELD", null);
        call(trainer, attendance(past, ana, "PRESENT", 0));
        call(trainer, attendance(past, ana, "ABSENT", 1));
        call(trainer, attendance(past, ana, "ABSENT", 2)); // same status again: no message (AC-10)

        List<EmailMessage> mails = deliver();
        assertThat(mails).extracting(EmailMessage::to).containsExactly("ana@klub.hr", "ana@klub.hr");
        assertThat(mails.get(0).subject()).isEqualTo("Evidencija dolaska: Trening, utorak 15. 9. 2026. u 18:30");
        assertThat(mails.get(0).textBody()).contains("evidentirano je: dolazak (prisutni)")
                .contains("https://tapin.test/treninzi/" + past);
        assertThat(mails.get(1).textBody()).contains("izostanak (odsutni)");
    }

    @Test
    void beltPromotionSendsNoMessage() throws Exception {
        call(trainer, post("/api/v1/members/" + ana + "/belts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"belt\":\"BLUE\",\"awardedOn\":\"2026-10-15\"}"));
        assertThat(deliver()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Integer.class)).isZero();
    }

    @Test
    void invitationAndPasswordResetCarryWorkingLinks() throws Exception {
        call(trainer, post("/api/v1/members/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nova@klub.hr\",\"firstName\":\"Nova\",\"lastName\":\"Članica\",\"roles\":[\"MEMBER\"]}"));
        mvc.perform(post("/api/v1/auth/password-reset/request").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@klub.hr\"}"));

        List<EmailMessage> mails = deliver();
        assertThat(mails).extracting(EmailMessage::to).containsExactlyInAnyOrder("nova@klub.hr", "ana@klub.hr");
        assertThat(mails).filteredOn(m -> m.to().equals("nova@klub.hr")).singleElement()
                .satisfies(m -> assertThat(m.textBody()).containsPattern("https://tapin\\.test/pozivnica#\\S+"));
        assertThat(mails).filteredOn(m -> m.to().equals("ana@klub.hr")).singleElement()
                .satisfies(m -> assertThat(m.textBody()).containsPattern("https://tapin\\.test/nova-lozinka#\\S+"));
    }

    // ---------------------------------------------------------------- helpers

    MockHttpServletRequestBuilder attendance(long session, long membershipId, String status, long version) {
        return put("/api/v1/trainings/" + session + "/attendance/" + membershipId).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("status", status, "expectedVersion", version)));
    }

    void call(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        int status = mvc.perform(request.session(session).with(csrf())).andReturn().getResponse().getStatus();
        assertThat(status).as(request.toString()).isLessThan(300);
    }

    /** Runs the real outbox processor until the queue is empty; returns what the test provider received. */
    List<EmailMessage> deliver() {
        int before = recorder.sent.size();
        while (processor.processDue().claimed() > 0) {
            // drain
        }
        return List.copyOf(recorder.sent.subList(before, recorder.sent.size()));
    }

    MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
