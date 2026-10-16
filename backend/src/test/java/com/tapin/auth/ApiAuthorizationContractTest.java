package com.tapin.auth;

import com.tapin.TestcontainersConfiguration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every operation in {@code api/openapi.yaml} is called as anonymous and as each role combination; the result must
 * match its {@code x-roles}: 401 for anonymous on protected endpoints, 403 when no role matches, and "passes the
 * security layer" (anything but 401/403) otherwise. Endpoints that are not implemented yet answer 404 after passing
 * security, so the matrix holds from day one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ApiAuthorizationContractTest {

    private static final List<Set<String>> PRINCIPALS = List.of(
            Set.of(), // anonymous
            Set.of("MEMBER"),
            Set.of("TRAINER"),
            Set.of("ADMIN"),
            Set.of("TRAINER", "MEMBER"),
            Set.of("ADMIN", "MEMBER"));

    @Autowired
    MockMvc mvc;

    record Operation(String method, String path, Set<String> roles) {
        boolean isPublic() {
            return roles.contains("PUBLIC");
        }

        boolean anyAuthenticated() {
            return roles.contains("ANY");
        }

        String concretePath() {
            return "/api/v1" + path.replaceAll("\\{[^}]+}", "1");
        }
    }

    @TestFactory
    Stream<DynamicTest> everyContractOperationEnforcesItsRoles() throws Exception {
        List<Operation> operations = contractOperations();
        assertThat(operations).hasSizeGreaterThan(40);

        return operations.stream().flatMap(op -> PRINCIPALS.stream().map(principal -> DynamicTest.dynamicTest(
                op.method() + " " + op.path() + " as " + (principal.isEmpty() ? "anonymous" : principal),
                () -> assertAccess(op, principal))));
    }

    private void assertAccess(Operation op, Set<String> principal) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(op.method()), op.concretePath())
                .with(csrf())
                .contentType("application/json")
                .content("{}");
        if (!principal.isEmpty()) {
            request.with(user("user").roles(principal.toArray(String[]::new)));
        }
        int status = mvc.perform(request).andReturn().getResponse().getStatus();

        boolean allowed = op.isPublic()
                || (!principal.isEmpty() && (op.anyAuthenticated() || principal.stream().anyMatch(op.roles()::contains)));
        if (allowed) {
            assertThat(status).as("expected access").isNotIn(401, 403);
        } else if (principal.isEmpty()) {
            assertThat(status).as("anonymous must be rejected").isEqualTo(401);
        } else {
            assertThat(status).as("role must be rejected").isEqualTo(403);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Operation> contractOperations() throws Exception {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(Path.of("..", "api", "openapi.yaml"))) {
            root = new Yaml().load(in);
        }
        List<Operation> operations = new ArrayList<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) root.get("paths");
        paths.forEach((path, item) -> item.forEach((method, value) -> {
            if (value instanceof Map<?, ?> op && op.containsKey("operationId")) {
                List<String> roles = (List<String>) op.get("x-roles");
                assertThat(roles).as("x-roles of " + method + " " + path).isNotEmpty();
                operations.add(new Operation(method.toUpperCase(), path, Set.copyOf(roles)));
            }
        }));
        return operations;
    }
}
