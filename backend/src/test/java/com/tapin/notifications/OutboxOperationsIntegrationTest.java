package com.tapin.notifications;

import com.tapin.MutableClock;
import com.tapin.TestcontainersConfiguration;
import com.tapin.auth.AuthRequestLimiter;
import com.tapin.auth.TestUsers;
import com.tapin.notifications.email.EmailSender;
import com.tapin.notifications.email.SendResult;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BJJ-30: operating the e-mail queue. A provider outage is simulated with the scripted provider; attendance keeps
 * being recorded, the operator API shows attempts and sanitized errors (never the payload), the long-failure alert
 * goes on and off, and the queue drains by itself once the provider is back. Manual retry is safe to repeat.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class, OutboxOperationsIntegrationTest.Provider.class})
@Testcontainers(disabledWithoutDocker = true)
class OutboxOperationsIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Provider {
        @Bean
        @Primary
        ScriptedEmailSender scriptedEmailSender() {
            return new ScriptedEmailSender();
        }
    }

    private static final String SECRET_LINK = "https://tapin.test/pozivnica#Zx81secretInvitationTokenValue0123456789";
    private static final SendResult OUTAGE = new SendResult.TransientFailure(
            "SMTP server unreachable: auth user=tapin password=hunter2 while sending " + SECRET_LINK, false);

    @MockitoBean AuthRequestLimiter requestLimiter;
    @MockitoBean PushDelivery pushDelivery;
    @Autowired EmailTemplateRenderer emailRenderer;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;
    @Autowired OutboxProcessor processor;
    @Autowired OutboxMonitor monitor;
    @Autowired NotificationOutbox outbox;
    @Autowired TransactionTemplate tx;
    @Autowired EmailSender sender;

    ScriptedEmailSender provider;
    long ana;
    long anaUser;
    long ivo;
    MockHttpSession trainer;
    MockHttpSession admin;
    long training;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE attendance_audit_logs, training_attendances, registration_events, training_registrations, "
                + "training_session_events, session_coaches, training_sessions, email_outbox, users RESTART IDENTITY CASCADE");
        clock.set(Instant.parse("2026-10-20T17:00:00Z"));
        provider = (ScriptedEmailSender) sender;
        provider.reset();
        org.mockito.Mockito.when(pushDelivery.deliver(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    OutboxEntry entry = invocation.getArgument(0);
                    var rendered = emailRenderer.render(entry.eventType(), entry.payload());
                    return provider.send(new com.tapin.notifications.email.EmailMessage(entry.recipientEmail(),
                            rendered.subject(), rendered.body(), "test", entry.deduplicationKey()));
                });
        TestUsers users = new TestUsers(jdbc, passwordEncoder);
        TestUsers.Created anaCreated = users.active("ana@klub.hr", "MEMBER");
        ana = anaCreated.membershipId();
        anaUser = anaCreated.userId();
        ivo = users.active("ivo@klub.hr", "MEMBER").membershipId();
        for (long id : new long[] {ana, ivo}) {
            jdbc.update("INSERT INTO membership_status_events (membership_id, old_status, new_status, effective_at) "
                    + "VALUES (?, NULL, 'ACTIVE', TIMESTAMPTZ '2024-03-01T00:00:00Z')", id);
        }
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        users.active("admin@klub.hr", "ADMIN");
        trainer = login("trener@klub.hr");
        admin = login("admin@klub.hr");
        training = jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at, status)
                VALUES ((SELECT id FROM clubs), TIMESTAMPTZ '2026-10-20T16:30:00Z', TIMESTAMPTZ '2026-10-20T18:00:00Z', 'SCHEDULED')
                RETURNING id""", Long.class);
    }

    @Test
    void providerOutageNeverBlocksRecordsIsVisibleToTheOperatorAndRecoversByItself() throws Exception {
        provider.answer(message -> OUTAGE);

        // Records are saved while the provider is down; the API only promises "queued".
        JsonNode recorded = body(trainer, attendance(ana, "PRESENT", 0));
        assertThat(recorded.get("notificationStatus").asString()).isEqualTo("QUEUED");
        long secret = enqueueInvitation();
        processor.processDue();

        JsonNode overview = body(admin, get("/api/v1/admin/outbox"));
        assertThat(overview.get("counts").get("PENDING").asInt()).isEqualTo(2);
        JsonNode attendanceMail = item(overview, "ATTENDANCE_RECORDED");
        assertThat(attendanceMail.get("status").asString()).isEqualTo("PENDING");
        assertThat(attendanceMail.get("attemptCount").asInt()).isEqualTo(1);
        assertThat(attendanceMail.get("maxAttempts").asInt()).isEqualTo(8);
        assertThat(attendanceMail.get("nextAttemptAt").isNull()).isFalse();
        assertThat(attendanceMail.get("acceptedByProviderAt").isNull()).isTrue();
        assertThat(attendanceMail.get("lastError").asString())
                .startsWith("[push] SMTP server unreachable").contains("password=***", "https://tapin.test/***");
        assertNoSecrets(overview.toString());
        assertThat(jdbc.queryForObject("SELECT last_error FROM email_outbox WHERE id = ?", String.class, secret))
                .doesNotContain("hunter2", "Zx81secret");
        assertThat(overview.get("alert").get("degraded").asBoolean()).isFalse();

        // The outage lasts longer than tapin.outbox.alert.after (30 min): alert on, records still work.
        for (int minute = 1; minute <= 32; minute++) {
            clock.advance(Duration.ofMinutes(1));
            processor.processDue();
        }
        assertThat(monitor.check().degraded()).isTrue();
        JsonNode corrected = body(trainer, attendance(ana, "ABSENT", recorded.get("version").asLong()));
        assertThat(corrected.get("notificationStatus").asString()).isEqualTo("QUEUED");
        JsonNode degraded = body(admin, get("/api/v1/admin/outbox?status=PENDING"));
        assertThat(degraded.get("alert").get("degraded").asBoolean()).isTrue();
        assertThat(degraded.get("alert").get("stuckPending").asInt()).isEqualTo(2);
        assertThat(degraded.get("alert").get("latestError").asString()).doesNotContain("hunter2");
        assertThat(item(degraded, "INVITATION").get("attemptCount").asInt()).isGreaterThan(1);

        // Provider back: the queue drains on the regular retry schedule, nothing to do by hand.
        provider.answer(message -> new SendResult.Accepted("provider-42"));
        clock.advance(Duration.ofHours(2));
        processor.processDue();
        assertThat(monitor.check().degraded()).isFalse();
        JsonNode healthy = body(admin, get("/api/v1/admin/outbox?status=SENT"));
        assertThat(healthy.get("counts").get("PENDING").asInt()).isZero();
        assertThat(healthy.get("counts").get("SENT").asInt()).isEqualTo(3);
        assertThat(healthy.get("alert").get("degraded").asBoolean()).isFalse();
        JsonNode sent = item(healthy, "ATTENDANCE_RECORDED");
        assertThat(sent.get("acceptedByProviderAt").isNull()).isFalse();
        assertThat(sent.get("providerMessageId").asString()).isEqualTo("provider-42");
        assertNoSecrets(healthy.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_attendances WHERE membership_id = ? AND status = 'ABSENT'",
                Long.class, ana)).isEqualTo(1);
    }

    @Test
    void manualRetryIsSafeAndRefusedWhereItCouldNotHelp() throws Exception {
        provider.answer(message -> new SendResult.PermanentFailure("Recipient rejected: 550 mailbox unavailable"));
        body(trainer, attendance(ivo, "PRESENT", 0));
        long secret = enqueueInvitation();
        processor.processDue();

        JsonNode failed = body(admin, get("/api/v1/admin/outbox?status=FAILED"));
        assertThat(failed.get("counts").get("FAILED").asInt()).isEqualTo(2);
        assertThat(failed.get("failedLast24Hours").asInt()).isEqualTo(2);
        JsonNode mail = item(failed, "ATTENDANCE_RECORDED");
        assertThat(mail.get("retryable").asBoolean()).isTrue();
        assertThat(mail.get("attemptCount").asInt()).isEqualTo(1);
        assertThat(item(failed, "INVITATION").get("retryable").asBoolean()).isFalse();
        long id = mail.get("outboxId").asLong();

        // After fixing the cause: retry once, a double click is rejected and never queues a second e-mail.
        provider.answer(message -> new SendResult.Accepted("ok"));
        JsonNode retried = body(admin, post("/api/v1/admin/outbox/" + id + "/retry"));
        assertThat(retried.get("status").asString()).isEqualTo("PENDING");
        assertThat(problem(admin, post("/api/v1/admin/outbox/" + id + "/retry"))).isEqualTo(422);
        int before = provider.sent.size();
        processor.processDue();
        assertThat(provider.sent.size() - before).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE id = ?", String.class, id)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE event_type = 'ATTENDANCE_RECORDED'",
                Long.class)).isEqualTo(1);

        // A secret link was wiped after the failure: the business action must be repeated instead.
        assertThat(problem(admin, post("/api/v1/admin/outbox/" + secret + "/retry"))).isEqualTo(422);
        assertThat(problem(admin, post("/api/v1/admin/outbox/999999/retry"))).isEqualTo(404);
    }

    @Test
    void onlyAdministratorsSeeTheQueue() throws Exception {
        assertThat(problem(trainer, get("/api/v1/admin/outbox"))).isEqualTo(403);
        assertThat(problem(login("ana@klub.hr"), get("/api/v1/admin/outbox"))).isEqualTo(403);
        assertThat(problem(trainer, post("/api/v1/admin/outbox/1/retry"))).isEqualTo(403);
    }

    @Test
    void sanitizerMasksSecretsButKeepsTheDiagnosis() {
        assertThat(OutboxErrors.sanitize("535 Authentication failed for password=hunter2; token: abc"))
                .isEqualTo("535 Authentication failed for password=***; token: ***");
        assertThat(OutboxErrors.sanitize("Authorization: Bearer eyJhbGciOi.payload.sig rejected"))
                .isEqualTo("Authorization: *** rejected");
        assertThat(OutboxErrors.sanitize("link " + SECRET_LINK + " failed")).isEqualTo("link https://tapin.test/*** failed");
        assertThat(OutboxErrors.sanitize("SMTP timeout (delivery state unknown): Read timed out"))
                .isEqualTo("SMTP timeout (delivery state unknown): Read timed out");
    }

    // ---------------------------------------------------------------- helpers

    private long enqueueInvitation() {
        tx.executeWithoutResult(status -> outbox.enqueue(OutboxMessage.of(EmailEventType.INVITATION, anaUser, "ana@klub.hr",
                Map.of("firstName", "Ana", "clubName", "Test klub", "invitationUrl", SECRET_LINK,
                        "expiresAt", "27. 10. 2026."), "INVITATION:invitation-1").withSecret()));
        return jdbc.queryForObject("SELECT id FROM email_outbox WHERE event_type = 'INVITATION'", Long.class);
    }

    private static void assertNoSecrets(String body) {
        assertThat(body).doesNotContain("hunter2", "Zx81secret", "invitationUrl", "payload");
    }

    private static JsonNode item(JsonNode overview, String eventType) {
        for (JsonNode item : overview.get("items")) {
            if (item.get("eventType").asString().equals(eventType)) {
                return item;
            }
        }
        throw new AssertionError("no " + eventType + " in " + overview);
    }

    private MockHttpServletRequestBuilder attendance(long membershipId, String status, long version) {
        return put("/api/v1/trainings/" + training + "/attendance/" + membershipId).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("status", status, "expectedVersion", version)));
    }

    private JsonNode body(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.session(session).with(csrf())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isLessThan(300);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int problem(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.session(session).with(csrf())).andReturn().getResponse().getStatus();
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
