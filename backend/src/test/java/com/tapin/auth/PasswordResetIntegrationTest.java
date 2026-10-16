package com.tapin.auth;

import com.tapin.TestcontainersConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class PasswordResetIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired PasswordResetService resets;
    String email;
    long userId;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        email = UUID.randomUUID() + "@test.hr";
        userId = new TestUsers(jdbc, passwords).active(email, "MEMBER").userId();
    }

    String token() {
        String url = jdbc.queryForObject("SELECT payload->>'resetUrl' FROM email_outbox WHERE recipient_user_id = ? ORDER BY id DESC LIMIT 1", String.class, userId);
        return url.substring(url.indexOf('#') + 1);
    }

    @Test void resetIsPrivateOneTimeAndRevokesAllOldSessions(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var login = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        for (String address : new String[]{email, "unknown-" + email}) {
            mvc.perform(post("/api/v1/auth/password-reset/request").with(csrf()).contentType("application/json")
                    .content("{\"email\":\"" + address + "\"}"))
                    .andExpect(status().isAccepted()).andExpect(content().string(""));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Integer.class)).isEqualTo(1);
        String token = token();
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM expires_at - created_at)::int FROM password_reset_tokens", Integer.class)).isEqualTo(1800);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens", String.class)).isEqualTo(InvitationTokens.hash(token));
        mvc.perform(post("/api/v1/auth/password-reset/confirm").with(csrf()).contentType("application/json")
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"new-password-123\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(passwords.matches("new-password-123", jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId))).isTrue();
        assertThatThrownBy(() -> resets.confirm(token, "another-password-123")).isInstanceOf(com.tapin.shared.error.ApiException.class);
        assertThat(output.getAll()).doesNotContain(token, "new-password-123", TestUsers.PASSWORD);
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"new-password-123\"}"))
                .andExpect(status().isOk());
    }

    @Test void invitedAndDisabledAccountsDoNotReceiveResetTokens() throws Exception {
        var users = new TestUsers(jdbc, passwords);
        for (String state : new String[]{"INVITED", "DISABLED"}) {
            var account = users.create(state.toLowerCase() + "-" + email, state, "MEMBER");
            mvc.perform(post("/api/v1/auth/password-reset/request").with(csrf()).contentType("application/json")
                    .content("{\"email\":\"" + account.email() + "\"}"))
                    .andExpect(status().isAccepted()).andExpect(content().string(""));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Integer.class)).isZero();
    }

    @Test void resendExpiryAndWeakPasswordCannotChangeCredentials() {
        resets.request(email);
        String old = token();
        resets.request(email);
        String current = token();
        assertThatThrownBy(() -> resets.confirm(old, "new-password-123")).isInstanceOf(com.tapin.shared.error.ApiException.class);
        assertThatThrownBy(() -> resets.confirm(current, "short")).isInstanceOf(com.tapin.shared.error.ApiException.class);
        jdbc.update("UPDATE password_reset_tokens SET created_at = now() - interval '1 hour', expires_at = now() - interval '1 minute'");
        assertThatThrownBy(() -> resets.confirm(current, "new-password-123")).isInstanceOf(com.tapin.shared.error.ApiException.class);
        assertThat(passwords.matches(TestUsers.PASSWORD, jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId))).isTrue();
    }

    @Test void concurrentConfirmationHasOnlyOneWinner() throws Exception {
        resets.request(email);
        String token = token();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> action = () -> {
                start.await();
                try { resets.confirm(token, "new-password-123"); return true; }
                catch (com.tapin.shared.error.ApiException ex) { return false; }
            };
            var first = executor.submit(action); var second = executor.submit(action); start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void resetRequiresCsrfAndLimitsUnknownAddressesToo() throws Exception {
        String address = "unknown-" + email;
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType("application/json")
                .content("{\"email\":\"" + address + "\"}")).andExpect(status().isForbidden());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/password-reset/request").with(csrf()).contentType("application/json")
                    .content("{\"email\":\"" + address + "\"}")).andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/v1/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + address + "\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    @Test void invitationIssueAndResendShareTheOperatorLimit() throws Exception {
        var staff = new TestUsers(jdbc, passwords).active("staff-" + email, "TRAINER");
        var login = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + staff.email() + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/members/invitations").session(session).with(csrf()).contentType("application/json")
                    .content("{\"email\":\"" + email + "\",\"firstName\":\"Test\",\"lastName\":\"Test\",\"roles\":[\"MEMBER\"]}"))
                    .andExpect(status().isConflict());
        }
        mvc.perform(post("/api/v1/members/" + userId + "/invitations/resend").session(session).with(csrf()))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
}
