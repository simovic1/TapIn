package com.tapin.auth;

import com.tapin.TestcontainersConfiguration;
import com.tapin.members.MemberDirectoryService;
import com.tapin.members.Role;
import com.tapin.shared.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
class MembershipManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired MemberDirectoryService directory;
    @Autowired JsonMapper json;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired PasswordResetService resets;
    TestUsers users;
    TestUsers.Created admin;
    TestUsers.Created member;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        users = new TestUsers(jdbc, passwords);
        admin = users.active("admin@members.test", "ADMIN");
        member = users.active("member@members.test", "MEMBER");
    }

    MockHttpSession login(String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    AuthenticatedUser actor(TestUsers.Created user) {
        return new AuthenticatedUser(user.userId(), user.membershipId(), user.email(), Set.of("ADMIN"), null);
    }

    @Test void memberHasOnlyOwnProfileAndCannotPatchPrivilegesOrBelts() throws Exception {
        MockHttpSession session = login(member.email());
        mvc.perform(get("/api/v1/members").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/members/" + admin.membershipId()).session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me").session(session)).andExpect(jsonPath("$.userId").value(member.userId()));
        mvc.perform(patch("/api/v1/me/profile").session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"phone\":\"+385 91 1234567\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("+385 91 1234567"));
        for (String field : List.of("currentBelt", "roles", "membershipStatus", "joinedOn", "userId")) {
            mvc.perform(patch("/api/v1/me/profile").session(session).with(csrf()).contentType("application/json")
                    .content("{\"expectedVersion\":1,\"" + field + "\":\"BLACK\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/v1/members/" + member.membershipId()).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":1,\"firstName\":\"Other\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/me/profile").session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"phone\":null}"))
                .andExpect(status().isConflict());
    }

    @Test void staffCanSearchFilterPageAndReadDetailsButTrainerCannotEdit() throws Exception {
        var trainer = users.active("trainer@members.test", "TRAINER");
        MockHttpSession session = login(trainer.email());
        mvc.perform(get("/api/v1/members").session(session).param("q", "MEMBER@").param("role", "MEMBER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].membershipId").value(member.membershipId()));
        mvc.perform(get("/api/v1/members").session(session).param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.page.totalPages").value(3));
        mvc.perform(get("/api/v1/members/" + member.membershipId()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentBelt").value("UNKNOWN"));
        mvc.perform(patch("/api/v1/members/" + member.membershipId()).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"firstName\":\"Other\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/members/" + member.membershipId() + "/roles").session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isForbidden());
        for (String sort : List.of("email,asc", "lastName;DROP TABLE users,asc")) {
            mvc.perform(get("/api/v1/members").session(session).param("sort", sort)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/members").session(session).param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test void adminUpdatesContactWithValidationAndOptimisticConcurrency() throws Exception {
        MockHttpSession session = login(admin.email());
        String url = "/api/v1/members/" + member.membershipId();
        for (String fields : List.of("\"firstName\":\"  \"", "\"email\":\"invalid\"", "\"joinedOn\":\"2999-01-01\"",
                "\"phone\":\"abc\"", "\"currentBelt\":\"BLACK\"", "\"firstName\":null", "\"joinedOn\":\"bad-date\"")) {
            mvc.perform(patch(url).session(session).with(csrf()).contentType("application/json")
                    .content("{\"expectedVersion\":0," + fields + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(patch(url).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"email\":\"ADMIN@members.test\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DUPLICATE_RESOURCE"));
        mvc.perform(patch(url).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"firstName\":\" Ana \",\"email\":\"ana@members.test\",\"phone\":null,\"joinedOn\":\"2020-01-01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Ana")).andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch(url).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"firstName\":\"Lost update\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.currentState.firstName").value("Ana"));
        assertThat(directory.activeAt(member.membershipId(), Instant.parse("2021-01-01T12:00:00Z"))).isTrue();
    }

    @Test void deactivationAndReactivationPreserveHistoryAndDefineIntervals() throws Exception {
        MockHttpSession session = login(admin.email());
        String url = "/api/v1/members/" + member.membershipId();
        mvc.perform(patch(url).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"membershipStatus\":\"INACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statusHistory.length()").value(2));
        Instant inactive = jdbc.queryForObject("SELECT max(effective_at) FROM membership_status_events WHERE membership_id=?", java.sql.Timestamp.class, member.membershipId()).toInstant();
        assertThat(directory.activeAt(member.membershipId(), inactive.minusSeconds(1))).isTrue();
        assertThat(directory.activeAt(member.membershipId(), inactive)).isFalse();
        mvc.perform(patch(url).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":1,\"membershipStatus\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statusHistory.length()").value(3));
        assertThat(directory.activeAt(member.membershipId(), Instant.now().plusSeconds(1))).isTrue();
        assertThat(directory.activeAt(member.membershipId(), inactive)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM club_memberships WHERE id=?", Integer.class, member.membershipId())).isEqualTo(1);
    }

    @Test void lastAdminCannotBeDeactivatedOrDemotedAndInvitedAdminsDoNotCount() throws Exception {
        users.create("invited-admin@members.test", "INVITED", "ADMIN");
        MockHttpSession session = login(admin.email());
        String url = "/api/v1/members/" + admin.membershipId();
        mvc.perform(patch(url).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"membershipStatus\":\"INACTIVE\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errorCode").value("LAST_ADMIN_REMOVAL"));
        mvc.perform(put(url + "/roles").session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"roles\":[\"MEMBER\"]}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test void concurrentAdminDemotionsLeaveOneActiveAdministrator() throws Exception {
        var second = users.active("second-admin@members.test", "ADMIN");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(admin, second).stream().map(user -> executor.submit(() -> {
                start.await();
                try { directory.replaceRoles(actor(user), user.membershipId(), 0, Set.of(Role.MEMBER)); return "OK"; }
                catch (ApiException ex) { return ex.errorCode().name(); }
            })).toList();
            start.countDown();
            assertThat(List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "LAST_ADMIN_REMOVAL");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_roles WHERE role='ADMIN'", Integer.class)).isEqualTo(1);
    }

    @Test void rolesChangeImmediatelyForExistingSessions() throws Exception {
        MockHttpSession memberSession = login(member.email());
        MockHttpSession adminSession = login(admin.email());
        mvc.perform(put("/api/v1/members/" + member.membershipId() + "/roles").session(adminSession).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"roles\":[\"MEMBER\",\"TRAINER\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/v1/members").session(memberSession)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/members/" + member.membershipId() + "/roles").session(adminSession).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":1,\"roles\":[\"MEMBER\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/members").session(memberSession)).andExpect(status().isForbidden());
    }

    @Test void migrationBackfillsLegacyActivityWithoutRewritingExistingEvents() {
        String schema = "legacy_membership_test";
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target("006").load().migrate();
            long userId = jdbc.queryForObject("INSERT INTO legacy_membership_test.users (email,first_name,last_name,account_status) VALUES ('legacy@test.hr','Legacy','Member','INVITED') RETURNING id", Long.class);
            long id = jdbc.queryForObject("INSERT INTO legacy_membership_test.club_memberships (club_id,user_id,joined_on,status) VALUES ((SELECT id FROM legacy_membership_test.clubs),?,'2018-03-01','ACTIVE') RETURNING id", Long.class, userId);
            jdbc.update("INSERT INTO legacy_membership_test.membership_status_events (membership_id,new_status,effective_at) VALUES (?,'ACTIVE','2020-01-01T00:00:00Z')", id);
            var migrated = org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            migrated.migrate();
            assertThat(jdbc.queryForObject("SELECT min(effective_at) FROM legacy_membership_test.membership_status_events", java.sql.Timestamp.class).toInstant())
                    .isEqualTo(Instant.parse("2018-02-28T23:00:00Z"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM legacy_membership_test.membership_status_events", Integer.class)).isEqualTo(2);
            assertThat(migrated.migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS legacy_membership_test CASCADE");
        }
    }

    @Test void changingEmailRevokesOldResetLinks() throws Exception {
        resets.request(member.email());
        String link = jdbc.queryForObject("SELECT payload->>'resetUrl' FROM email_outbox WHERE recipient_user_id=?", String.class, member.userId());
        String token = link.substring(link.indexOf('#') + 1);
        var session = login(admin.email());
        mvc.perform(patch("/api/v1/members/" + member.membershipId()).session(session).with(csrf()).contentType("application/json")
                .content("{\"expectedVersion\":0,\"email\":\"changed@members.test\"}"))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> resets.confirm(token, "new-password-123"))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).errorCode().name()).isEqualTo("PASSWORD_RESET_TOKEN_INVALID");
    }

    @Test void concurrentDeactivationAndDemotionCannotRemoveBothAdmins() throws Exception {
        var second = users.active("second-admin@members.test", "ADMIN");
        var fields = java.util.Map.of("expectedVersion", json.readTree("0"), "membershipStatus", json.readTree("\"INACTIVE\""));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> {
                start.await();
                try { directory.replaceRoles(actor(admin), admin.membershipId(), 0, Set.of(Role.MEMBER)); return "OK"; }
                catch (ApiException ex) { return ex.errorCode().name(); }
            });
            Future<String> other = executor.submit(() -> {
                start.await();
                try { directory.update(actor(second), second.membershipId(), fields); return "OK"; }
                catch (ApiException ex) { return ex.errorCode().name(); }
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), other.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "LAST_ADMIN_REMOVAL");
        }
    }
}
