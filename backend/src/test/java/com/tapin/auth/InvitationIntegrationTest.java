package com.tapin.auth;

import com.tapin.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Invitation and activation end to end over HTTP (spec §3.2, AC-01, AC-18). Commits for real (no test tx). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class InvitationIntegrationTest {

    private static final String NEW_PASSWORD = "nova-lozinka-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JsonMapper json;
    @Autowired InvitationService invitationService;
    @Autowired ApplicationContext context;

    TestUsers users;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        users = new TestUsers(jdbc, passwordEncoder);
    }

    // ---------------------------------------------------------------- AC-01: invite -> e-mail -> activate

    @Test
    void trainerInvitesMemberWhoActivatesTheAccount() throws Exception {
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        MockHttpSession trainer = login("trener@klub.hr", TestUsers.PASSWORD);

        JsonNode created = body(invite(trainer, "Nova.Clanica@Klub.hr", "MEMBER")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/v1/members/\\d+")))
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED")));
        long userId = created.get("userId").asLong();

        // Account INVITED (no password) vs. membership ACTIVE: two different things.
        assertThat(jdbc.queryForMap("SELECT account_status, password_hash FROM users WHERE id = ?", userId))
                .containsEntry("account_status", "INVITED").containsEntry("password_hash", null);
        assertThat(jdbc.queryForObject("SELECT status FROM club_memberships WHERE user_id = ?", String.class, userId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForList("SELECT r.role FROM membership_roles r JOIN club_memberships m "
                + "ON m.id = r.membership_id WHERE m.user_id = ?", String.class, userId)).containsExactly("MEMBER");

        // The e-mail contains the activation link; only the token's hash is stored.
        String token = tokenFromOutbox(userId);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM invitations WHERE user_id = ?", String.class, userId))
                .isEqualTo(InvitationTokens.hash(token)).isNotEqualTo(token);
        Map<String, Object> payload = invitationPayload(userId);
        OutboxTestSupport.RenderedEmail email = OutboxTestSupport.render(context, "INVITATION", payload);
        assertThat(email.body()).contains("https://tapin.test/pozivnica#" + token).contains("Bok Nova");
        assertThat(email.subject()).isEqualTo("Pozivnica u BJJ klub");

        // Preview for the activation page (anonymous).
        mvc.perform(post("/api/v1/auth/invitations/preview").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("Nova.Clanica@Klub.hr"))
                .andExpect(jsonPath("$.clubName").value("BJJ klub"));
        Instant expiresAt = Instant.parse(created.get("expiresAt").asString());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));

        // Cannot sign in before activation.
        mvc.perform(loginRequest("nova.clanica@klub.hr", NEW_PASSWORD)).andExpect(status().isUnauthorized());

        // Activation signs the user in.
        MvcResult accepted = accept(token, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"))
                .andReturn();
        MockHttpSession member = (MockHttpSession) accepted.getRequest().getSession(false);
        mvc.perform(get("/api/v1/me").session(member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Nova"));

        // One-time: the same link no longer works.
        accept(token, "druga-lozinka-123").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INVITATION_INVALID_OR_EXPIRED"));
        preview(token).andExpect(status().isUnprocessableContent());

        // The chosen password works for normal login.
        mvc.perform(loginRequest("nova.clanica@klub.hr", NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void expiredInvitationCannotBeActivated() throws Exception {
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        long userId = body(invite(login("trener@klub.hr", TestUsers.PASSWORD), "kasno@klub.hr", "MEMBER")
                .andExpect(status().isCreated())).get("userId").asLong();
        String token = tokenFromOutbox(userId);
        jdbc.update("UPDATE invitations SET created_at = now() - interval '8 days', expires_at = now() - interval '1 day'");

        accept(token, NEW_PASSWORD).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INVITATION_INVALID_OR_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("INVITED");
    }

    @Test
    void weakPasswordIsRejectedAndTheInvitationStaysUsable() throws Exception {
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        long userId = body(invite(login("trener@klub.hr", TestUsers.PASSWORD), "slaba@klub.hr", "MEMBER")
                .andExpect(status().isCreated())).get("userId").asLong();
        String token = tokenFromOutbox(userId);

        accept(token, "kratka").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        accept(token, NEW_PASSWORD).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- AC-18: roles

    @Test
    void trainerCannotInviteTrainersOrAdminsAndNothingIsCreated() throws Exception {
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        MockHttpSession trainer = login("trener@klub.hr", TestUsers.PASSWORD);
        long usersBefore = count("users");

        for (String roles : List.of("TRAINER", "ADMIN", "MEMBER\",\"TRAINER")) {
            invite(trainer, "netko@klub.hr", roles).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("ROLE_ASSIGNMENT_FORBIDDEN"));
        }
        assertThat(count("users")).isEqualTo(usersBefore);
        assertThat(count("invitations")).isZero();
        assertThat(count("email_outbox")).isZero();
    }

    @Test
    void adminCanInviteATrainer() throws Exception {
        users.active("admin@klub.hr", "ADMIN");
        long userId = body(invite(login("admin@klub.hr", TestUsers.PASSWORD), "novi.trener@klub.hr", "TRAINER\",\"MEMBER")
                .andExpect(status().isCreated())).get("userId").asLong();

        assertThat(jdbc.queryForList("SELECT r.role FROM membership_roles r JOIN club_memberships m "
                + "ON m.id = r.membership_id WHERE m.user_id = ? ORDER BY r.role", String.class, userId))
                .containsExactly("MEMBER", "TRAINER");
    }

    @Test
    void memberCannotInviteAnyone() throws Exception {
        users.active("clan@klub.hr", "MEMBER");
        invite(login("clan@klub.hr", TestUsers.PASSWORD), "netko@klub.hr", "MEMBER").andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    void duplicateEmailCreatesNoAdditionalUserOrMembership() throws Exception {
        users.active("admin@klub.hr", "ADMIN");
        MockHttpSession admin = login("admin@klub.hr", TestUsers.PASSWORD);
        invite(admin, "isti@klub.hr", "MEMBER").andExpect(status().isCreated());
        long[] before = {count("users"), count("club_memberships"), count("invitations"), count("email_outbox")};

        for (String email : List.of("isti@klub.hr", "ISTI@Klub.HR", "ADMIN@klub.hr")) {
            invite(admin, email, "MEMBER").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("DUPLICATE_RESOURCE"));
        }
        assertThat(new long[]{count("users"), count("club_memberships"), count("invitations"), count("email_outbox")})
                .containsExactly(before);
    }

    // ---------------------------------------------------------------- resend

    @Test
    void resendRevokesThePreviousLinkAndSendsANewOne() throws Exception {
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        MockHttpSession trainer = login("trener@klub.hr", TestUsers.PASSWORD);
        JsonNode created = body(invite(trainer, "ponovno@klub.hr", "MEMBER").andExpect(status().isCreated()));
        long userId = created.get("userId").asLong();
        String oldToken = tokenFromOutbox(userId);

        JsonNode resent = body(mvc.perform(post("/api/v1/members/" + created.get("membershipId").asLong()
                        + "/invitations/resend").session(trainer).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationStatus").value("QUEUED")));
        String newToken = tokenFromOutbox(userId);

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(resent.get("invitationId").asLong()).isNotEqualTo(created.get("invitationId").asLong());
        assertThat(jdbc.queryForList("SELECT deduplication_key FROM email_outbox ORDER BY id", String.class))
                .containsExactly("INVITATION:invitation-" + created.get("invitationId").asLong(),
                        "INVITATION:invitation-" + resent.get("invitationId").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invitations WHERE user_id = ? AND revoked_at IS NOT NULL",
                Long.class, userId)).isEqualTo(1);

        preview(oldToken).andExpect(status().isUnprocessableContent());
        accept(oldToken, NEW_PASSWORD).andExpect(status().isUnprocessableContent());
        accept(newToken, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void resendIsRefusedOnceTheAccountIsActive() throws Exception {
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        TestUsers.Created active = users.active("aktivan@klub.hr", "MEMBER");
        MockHttpSession trainer = login("trener@klub.hr", TestUsers.PASSWORD);

        mvc.perform(post("/api/v1/members/" + active.membershipId() + "/invitations/resend").session(trainer).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
        mvc.perform(post("/api/v1/members/999999/invitations/resend").session(trainer).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void theSameLinkCanBeUsedOnlyOnceEvenConcurrently() throws Exception {
        users.active("trener@klub.hr", "MEMBER", "TRAINER");
        long userId = body(invite(login("trener@klub.hr", TestUsers.PASSWORD), "brzo@klub.hr", "MEMBER")
                .andExpect(status().isCreated())).get("userId").asLong();
        String token = tokenFromOutbox(userId);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                invitationService.accept(new InvitationService.Activation(token, NEW_PASSWORD, "Brzi", "Član", null));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        List<Boolean> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(attempt));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
        }
        assertThat(results).containsOnlyOnce(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invitations WHERE accepted_at IS NOT NULL", Long.class))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions invite(MockHttpSession session, String email, String roles) throws Exception {
        return mvc.perform(post("/api/v1/members/invitations").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"firstName\":\"Nova\",\"lastName\":\"Članica\","
                        + "\"roles\":[\"" + roles + "\"]}"));
    }

    private ResultActions accept(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/invitations/accept").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"" + password
                        + "\",\"firstName\":\"Nova\",\"lastName\":\"Članica\"}"));
    }

    private ResultActions preview(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/invitations/preview").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"));
    }

    private MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mvc.perform(loginRequest(email, password)).andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private static org.springframework.test.web.servlet.RequestBuilder loginRequest(String email, String password) {
        return post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invitationPayload(long userId) {
        String payload = jdbc.queryForObject("""
                SELECT payload::text FROM email_outbox
                WHERE recipient_user_id = ? AND event_type = 'INVITATION' ORDER BY id DESC LIMIT 1""",
                String.class, userId);
        return json.readValue(payload, Map.class);
    }

    private String tokenFromOutbox(long userId) {
        String url = (String) invitationPayload(userId).get("invitationUrl");
        assertThat(url).startsWith("https://tapin.test/pozivnica#");
        return url.substring(url.indexOf('#') + 1);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @SuppressWarnings("unused")
    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }
}
