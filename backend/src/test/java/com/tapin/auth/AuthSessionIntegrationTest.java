package com.tapin.auth;

import com.tapin.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real login/logout with sessions, CSRF, live role/status re-validation and ownership-by-role rules. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AuthSessionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @PersistenceContext EntityManager entityManager;

    TestUsers users;

    @BeforeEach
    void setUp() {
        users = new TestUsers(jdbc, passwordEncoder);
    }

    // ---------------------------------------------------------------- login / logout

    @Test
    void loginRequiresCsrfToken() throws Exception {
        users.active("ana@klub.hr", "MEMBER");

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("ana@klub.hr")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void wrongPasswordUnknownEmailAndInvitedAccountAllLookTheSame() throws Exception {
        users.active("ana@klub.hr", "MEMBER");
        users.create("pozvan@klub.hr", "INVITED", "MEMBER");

        for (String body : new String[]{
                "{\"email\":\"ana@klub.hr\",\"password\":\"kriva-lozinka\"}",
                credentials("nepostoji@klub.hr"),
                credentials("pozvan@klub.hr")}) {
            mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("Neispravan e-mail ili lozinka."));
        }
    }

    @Test
    void loginEstablishesSessionWithNewIdAndRotatedCsrfToken() throws Exception {
        TestUsers.Created ana = users.active("Ana@Klub.hr", "MEMBER", "TRAINER");
        MockHttpSession anonymousSession = new MockHttpSession();
        String idBefore = anonymousSession.getId();

        MvcResult login = mvc.perform(post("/api/v1/auth/login").session(anonymousSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials("ana@KLUB.hr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ana.userId()))
                .andExpect(jsonPath("$.membershipId").value(ana.membershipId()))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"))
                .andExpect(jsonPath("$.roles[1]").value("TRAINER"))
                .andExpect(jsonPath("$.club.timeZone").value("Europe/Zagreb"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(idBefore); // session fixation protection
        // CSRF rotation and cookie flags are verified on a real server in CookieSecurityOverHttpsTest
        // (spring-security-test's csrf() replaces the token repository inside MockMvc).

        mvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("Ana@Klub.hr"));
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        users.active("ana@klub.hr", "MEMBER");
        MockHttpSession session = login("ana@klub.hr");

        MockHttpServletResponse logout = mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertThat(session.isInvalid()).isTrue();
        Cookie cleared = logout.getCookie(SecurityConfig.SESSION_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        mvc.perform(get("/api/v1/me").session(new MockHttpSession())).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutCsrfIsRejected() throws Exception {
        users.active("ana@klub.hr", "MEMBER");
        MockHttpSession session = login("ana@klub.hr");

        mvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void anonymousUserGetsNoProtectedData() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/members/1/statistics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/trainings")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- live re-validation

    @Test
    void deactivatedAccountLosesAccessOnTheNextRequest() throws Exception {
        TestUsers.Created ana = users.active("ana@klub.hr", "MEMBER");
        MockHttpSession session = login("ana@klub.hr");

        jdbc.update("UPDATE users SET account_status = 'DISABLED' WHERE id = ?", ana.userId());
        simulateNextRequestTransaction();

        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangesApplyWithoutNewLogin() throws Exception {
        TestUsers.Created ana = users.active("ana@klub.hr", "MEMBER");
        MockHttpSession session = login("ana@klub.hr");
        mvc.perform(get("/api/v1/members").session(session)).andExpect(status().isForbidden());

        users.setRoles(ana.membershipId(), "MEMBER", "TRAINER");
        simulateNextRequestTransaction();

        mvc.perform(get("/api/v1/members").session(session)).andExpect(statusIsNot401or403());
    }

    // ---------------------------------------------------------------- role rules (AC-02, 17, 28, 29)

    @Test
    void memberCannotReadOtherMembersDataByChangingTheId() throws Exception {
        users.active("ana@klub.hr", "MEMBER");
        TestUsers.Created ivo = users.active("ivo@klub.hr", "MEMBER");
        MockHttpSession ana = login("ana@klub.hr");
        long other = ivo.membershipId();

        mvc.perform(get("/api/v1/members/" + other + "/statistics").session(ana)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/members/" + other + "/belts").session(ana)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/members/" + other + "/attendance").session(ana)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/members/" + other).session(ana)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/members/" + other + "/belts/1").session(ana).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        // Own data only through /me/... (resolved from the session, no ID in the URL).
        mvc.perform(get("/api/v1/me/statistics").session(ana)).andExpect(statusIsNot401or403());
        mvc.perform(get("/api/v1/me/belts").session(ana)).andExpect(statusIsNot401or403());
    }

    @Test
    void trainerWithoutMemberRoleCannotRegisterThemselves() throws Exception {
        users.active("trener@klub.hr", "TRAINER");
        users.active("trener-clan@klub.hr", "TRAINER", "MEMBER");
        MockHttpSession trainerOnly = login("trener@klub.hr");
        MockHttpSession trainerMember = login("trener-clan@klub.hr");

        mvc.perform(put("/api/v1/trainings/1/my-registration").session(trainerOnly).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/trainings/1/my-registration").session(trainerOnly).with(csrf()))
                .andExpect(status().isForbidden());
        // AC-02: trainer who is also a member can register and record attendance of others.
        mvc.perform(put("/api/v1/trainings/1/my-registration").session(trainerMember).with(csrf()))
                .andExpect(statusIsNot401or403());
        mvc.perform(get("/api/v1/trainings/1/roster").session(trainerMember)).andExpect(statusIsNot401or403());
    }

    @Test
    void adminHasTrainerOperationalPowers() throws Exception {
        users.active("admin@klub.hr", "ADMIN");
        MockHttpSession admin = login("admin@klub.hr");

        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{
                get("/api/v1/trainings/1/roster"),
                put("/api/v1/trainings/1/attendance/2").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/v1/trainings/1/cancel").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/api/v1/members/2/belts"),
                get("/api/v1/reports/attendance"),
                post("/api/v1/members/invitations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")}) {
            mvc.perform(request.session(admin)).andExpect(statusIsNot401or403());
        }
        // ...but, like a trainer, not self-registration without the MEMBER role.
        mvc.perform(put("/api/v1/trainings/1/my-registration").session(admin).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- rate limiting

    @Test
    void repeatedFailuresAreRateLimitedEvenForTheRightPassword() throws Exception {
        users.active("meta@klub.hr", "MEMBER");
        for (int i = 0; i < 5; i++) {
            mvc.perform(loginRequest("{\"email\":\"meta@klub.hr\",\"password\":\"krivo-" + i + "\"}", "10.9.9.9"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(loginRequest(credentials("meta@klub.hr"), "10.9.9.9"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * MockMvc requests run inside this test's transaction and share its persistence context; production requests
     * each get a fresh one. Clearing it makes the next request read the database like production does.
     */
    private void simulateNextRequestTransaction() {
        entityManager.flush();
        entityManager.clear();
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(email)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder loginRequest(String body, String ip) {
        return post("/api/v1/auth/login").with(csrf()).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String credentials(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}";
    }

    private static org.springframework.test.web.servlet.ResultMatcher statusIsNot401or403() {
        return result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }
}
