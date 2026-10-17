package com.tapin.notifications;

import com.tapin.MutableClock;
import com.tapin.TestcontainersConfiguration;
import com.tapin.auth.AuthRequestLimiter;
import com.tapin.auth.TestUsers;
import com.tapin.notifications.push.PushResult;
import com.tapin.notifications.push.PushSender;
import com.tapin.notifications.push.WebPushCrypto;
import com.tapin.registrations.TrainingReminderService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web push as the optional second channel of the training reminder: subscription endpoints, queuing through the
 * outbox next to (and independently of) the e-mail, delivery to every device and clean-up of expired subscriptions.
 * VAPID keys are generated per run, none are committed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.Config.class, PushNotificationIntegrationTest.Provider.class})
@Testcontainers(disabledWithoutDocker = true)
class PushNotificationIntegrationTest {

    static final String FCM = "https://fcm.googleapis.com/fcm/send/";

    @DynamicPropertySource
    static void vapidKeys(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var keys = generator.generateKeyPair();
        byte[] d = ((ECPrivateKey) keys.getPrivate()).getS().toByteArray();
        byte[] scalar = new byte[32];
        int length = Math.min(d.length, 32);
        System.arraycopy(d, d.length - length, scalar, 32 - length, length);
        registry.add("tapin.push.vapid-public-key", () -> WebPushCrypto.encode(WebPushCrypto.uncompressed((ECPublicKey) keys.getPublic())));
        registry.add("tapin.push.vapid-private-key", () -> WebPushCrypto.encode(scalar));
    }

    /** Records push requests and answers with a programmable result. */
    static class ScriptedPushSender implements PushSender {
        record Sent(Target target, String payload, Duration ttl) {
        }

        final List<Sent> sent = new CopyOnWriteArrayList<>();
        volatile Function<Target, PushResult> behaviour = target -> new PushResult.Accepted("loc");

        @Override
        public PushResult send(Target target, String payload, Duration ttl) {
            sent.add(new Sent(target, payload, ttl));
            return behaviour.apply(target);
        }
    }

    @TestConfiguration
    static class Provider {
        @Bean
        @Primary
        ScriptedPushSender scriptedPushSender() {
            return new ScriptedPushSender();
        }
    }

    @MockitoBean AuthRequestLimiter requestLimiter;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;
    @Autowired TrainingReminderService reminders;
    @Autowired OutboxProcessor processor;
    @Autowired ScriptedPushSender push;
    @Autowired JsonMapper json;

    MockHttpSession ana;
    long training; // 20 Oct 2026 18:30 Zagreb = 16:30 UTC, reminder due 13:30 UTC

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE email_outbox, push_subscriptions, registration_events, training_registrations, "
                + "training_session_events, session_coaches, training_sessions, users RESTART IDENTITY CASCADE");
        push.sent.clear();
        push.behaviour = target -> new PushResult.Accepted("loc");
        clock.set(Instant.parse("2026-10-16T10:00:00Z"));
        TestUsers users = new TestUsers(jdbc, passwordEncoder);
        users.active("ana@klub.hr", "MEMBER");
        users.active("ivo@klub.hr", "MEMBER");
        training = jdbc.queryForObject("""
                INSERT INTO training_sessions (club_id, start_at, end_at)
                VALUES ((SELECT id FROM clubs), TIMESTAMPTZ '2026-10-20T16:30:00Z', TIMESTAMPTZ '2026-10-20T18:00:00Z')
                RETURNING id""", Long.class);
        ana = login("ana@klub.hr");
        mvc.perform(put("/api/v1/trainings/" + training + "/my-registration").session(ana).with(csrf()))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM email_outbox"); // registration confirmation, not under test here
    }

    // ---------------------------------------------------------------- subscription endpoints

    @Test
    void memberSubscribesAndUnsubscribesThisDevice() throws Exception {
        mvc.perform(get("/api/v1/me").session(ana)).andExpect(jsonPath("$.webPushPublicKey").isString());

        subscribe(ana, "device-1").andExpect(status().isNoContent());
        subscribe(ana, "device-1").andExpect(status().isNoContent()); // idempotent re-sync on every app start
        assertThat(subscriptions("ana@klub.hr")).containsExactly(FCM + "device-1");

        mvc.perform(delete("/api/v1/me/push-subscription").session(ana).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + FCM + "device-1\"}"))
                .andExpect(status().isNoContent());
        assertThat(subscriptions("ana@klub.hr")).isEmpty();
    }

    @Test
    void anotherPersonOnTheSameDeviceTakesTheSubscriptionOver() throws Exception {
        subscribe(ana, "shared-tablet").andExpect(status().isNoContent());
        MockHttpSession ivo = login("ivo@klub.hr");
        subscribe(ivo, "shared-tablet").andExpect(status().isNoContent());

        assertThat(subscriptions("ana@klub.hr")).isEmpty();
        assertThat(subscriptions("ivo@klub.hr")).containsExactly(FCM + "shared-tablet");
        // Ana cannot remove Ivo's subscription.
        mvc.perform(delete("/api/v1/me/push-subscription").session(ana).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + FCM + "shared-tablet\"}"))
                .andExpect(status().isNoContent());
        assertThat(subscriptions("ivo@klub.hr")).hasSize(1);
    }

    @Test
    void unknownPushServicesAndMalformedKeysAreRejected() throws Exception {
        mvc.perform(put("/api/v1/me/push-subscription").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://169.254.169.254/latest/meta-data", browserKey(), "AAAAAAAAAAAAAAAAAAAAAA")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/push-subscription").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(FCM + "x", "bm90LWEta2V5", "AAAAAAAAAAAAAAAAAAAAAA")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/push-subscription").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(FCM + "x", browserKey(), "AAAAAAAAAAAAAAAAAAAAAA")))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscriptions", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- reminder as push

    @Test
    void reminderGoesAsPushToEveryDeviceWithoutEmail() throws Exception {
        subscribe(ana, "phone").andExpect(status().isNoContent());
        subscribe(ana, "laptop").andExpect(status().isNoContent());

        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        assertThat(reminders.enqueueDue()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT channel FROM email_outbox WHERE event_type = 'TRAINING_REMINDER' ORDER BY channel",
                String.class)).containsExactly("PUSH");
        assertThat(reminders.enqueueDue()).as("one push per member and session").isZero();

        processor.processDue(clock.instant());

        assertThat(push.sent).extracting(s -> s.target().endpoint()).containsExactlyInAnyOrder(FCM + "phone", FCM + "laptop");
        JsonNode message = json.readTree(push.sent.getFirst().payload());
        assertThat(message.get("title").asString()).startsWith("Podsjetnik:");
        assertThat(message.get("body").asString()).contains("18:30");
        assertThat(message.get("url").asString()).isEqualTo("/treninzi/" + training);
        assertThat(message.get("tag").asString()).isEqualTo("training-" + training);
        assertThat(push.sent.getFirst().ttl()).as("kept until the training starts").isEqualTo(Duration.ofHours(3));
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE channel = 'PUSH'", String.class))
                .isEqualTo("SENT");
    }

    @Test
    void reminderPreferenceDisablesPushAndInbox() throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM users WHERE email = 'ana@klub.hr'", Long.class);
        mvc.perform(patch("/api/v1/me/profile").session(ana).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + version + ",\"trainingRemindersEnabled\":false}"))
                .andExpect(status().isOk());
        subscribe(ana, "phone").andExpect(status().isNoContent());

        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        assertThat(reminders.enqueueDue()).isZero();
        assertThat(jdbc.queryForList("SELECT channel FROM email_outbox", String.class)).isEmpty();
    }

    @Test
    void expiredSubscriptionIsRemovedAndThePushIsSkippedWithoutRetry() throws Exception {
        subscribe(ana, "old-phone").andExpect(status().isNoContent());
        push.behaviour = target -> new PushResult.Gone("[webpush] HTTP 410");

        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        reminders.enqueueDue();
        processor.processDue(clock.instant());

        assertThat(subscriptions("ana@klub.hr")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE channel = 'PUSH'", String.class))
                .isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE channel = 'EMAIL'", Integer.class)).isZero();
    }

    @Test
    void temporaryPushServiceErrorIsRetriedLikeAnyOutboxMessage() throws Exception {
        subscribe(ana, "phone").andExpect(status().isNoContent());
        push.behaviour = target -> new PushResult.TransientFailure("[webpush] HTTP 503");

        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        reminders.enqueueDue();
        processor.processDue(clock.instant());

        assertThat(jdbc.queryForMap("SELECT status, attempt_count, last_error FROM email_outbox WHERE channel = 'PUSH'"))
                .containsEntry("status", "PENDING").containsEntry("attempt_count", 1)
                .containsEntry("last_error", "[push] [webpush] HTTP 503");
        assertThat(subscriptions("ana@klub.hr")).hasSize(1);
    }

    @Test
    void cancellingTheTrainingWithdrawsTheWaitingPushToo() throws Exception {
        subscribe(ana, "phone").andExpect(status().isNoContent());
        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        reminders.enqueueDue();

        TestUsers users = new TestUsers(jdbc, passwordEncoder);
        users.active("trener@klub.hr", "TRAINER");
        mvc.perform(post("/api/v1/trainings/" + training + "/cancel").session(login("trener@klub.hr")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT status FROM email_outbox WHERE event_type = 'TRAINING_REMINDER'",
                String.class)).containsExactly("FAILED");
    }

    @Test
    void inboxIsPrivateAndReadStatePersistsWithoutPushPermission() throws Exception {
        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        assertThat(reminders.enqueueDue()).isEqualTo(1);
        long id = jdbc.queryForObject("SELECT id FROM email_outbox", Long.class);
        var batch = processor.processDue(clock.instant());
        assertThat(batch.skipped()).isEqualTo(1);
        assertThat(batch.sent()).isZero();
        assertThat(batch.failed()).isZero();
        assertThat(push.sent).isEmpty();
        assertThat(jdbc.queryForMap("SELECT status, last_error, sent_at FROM email_outbox WHERE id = ?", id))
                .containsEntry("status", "SKIPPED").containsEntry("last_error", null).containsEntry("sent_at", null);
        assertThat(processor.processDue(clock.instant()).claimed()).isZero();
        mvc.perform(get("/api/v1/me/notifications").session(ana))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].title").value(org.hamcrest.Matchers.startsWith("Podsjetnik:")));
        MockHttpSession ivo = login("ivo@klub.hr");
        mvc.perform(get("/api/v1/me/notifications").session(ivo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(put("/api/v1/me/notifications/" + id + "/read").session(ivo).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/notifications").session(ana))
                .andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(put("/api/v1/me/notifications/" + id + "/read").session(ana).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/notifications").session(ana))
                .andExpect(jsonPath("$.unreadCount").value(0)).andExpect(jsonPath("$.items[0].read").value(true));
        mvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE channel = 'EMAIL'", Integer.class)).isZero();
    }

    @Test
    void providerRejectionStillFailsInsteadOfBeingHiddenAsSkipped() throws Exception {
        subscribe(ana, "phone").andExpect(status().isNoContent());
        push.behaviour = target -> new PushResult.PermanentFailure("Invalid VAPID signature");
        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        reminders.enqueueDue();
        assertThat(processor.processDue(clock.instant()).failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox", String.class)).isEqualTo("FAILED");
    }

    @Test
    void migrationRepairsOnlyHistoricalMissingSubscriptionFailures() throws Exception {
        clock.set(Instant.parse("2026-10-20T13:30:00Z"));
        reminders.enqueueDue();
        jdbc.update("UPDATE email_outbox SET status = 'FAILED', last_error = ?",
                "No push subscription (push switched off on every device)");
        jdbc.update("""
                INSERT INTO email_outbox (event_type, recipient_user_id, recipient_email, payload,
                    deduplication_key, channel, status, last_error)
                SELECT event_type, recipient_user_id, recipient_email, payload,
                    'provider-failure', channel, 'FAILED', 'Invalid VAPID signature' FROM email_outbox
                """);
        jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V016__push_without_subscription_is_skipped.sql")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE status = 'SKIPPED' AND last_error IS NULL AND sent_at IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM email_outbox WHERE deduplication_key = 'provider-failure'", String.class)).isEqualTo("FAILED");
        mvc.perform(get("/api/v1/me/notifications").session(ana))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }

    // ---------------------------------------------------------------- helpers

    org.springframework.test.web.servlet.ResultActions subscribe(MockHttpSession session, String device) throws Exception {
        return mvc.perform(put("/api/v1/me/push-subscription").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(FCM + device, browserKey(), "AAAAAAAAAAAAAAAAAAAAAA")));
    }

    static String body(String endpoint, String p256dh, String auth) {
        return "{\"endpoint\":\"" + endpoint + "\",\"expirationTime\":null,\"keys\":{\"p256dh\":\"" + p256dh
                + "\",\"auth\":\"" + auth + "\"}}";
    }

    static String browserKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return WebPushCrypto.encode(WebPushCrypto.uncompressed((ECPublicKey) generator.generateKeyPair().getPublic()));
    }

    List<String> subscriptions(String email) {
        return jdbc.queryForList("SELECT endpoint FROM push_subscriptions s JOIN users u ON u.id = s.user_id "
                + "WHERE u.email = ? ORDER BY s.id", String.class, email);
    }

    MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
