package com.tapin.auth;

import com.tapin.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cookie flags on a real Tomcat with production cookie settings, reached the way production does: through a TLS
 * terminating reverse proxy ({@code X-Forwarded-Proto: https}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CookieSecurityOverHttpsTest {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void cleanUp() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
    }

    @Test
    void sessionAndCsrfCookiesAreHardenedOverHttps() throws Exception {
        new TestUsers(jdbc, passwordEncoder).active("ana@klub.hr", "MEMBER");

        HttpResponse<String> info = send(HttpRequest.newBuilder(uri("/api/v1/system/info"))
                .header("X-Forwarded-Proto", "https").GET());
        String xsrfCookie = cookie(info, "XSRF-TOKEN");
        assertThat(xsrfCookie).contains("Secure").containsIgnoringCase("SameSite=Lax").doesNotContain("HttpOnly");
        String token = value(xsrfCookie);

        // Anonymous requests (also 401s) must not create server sessions.
        HttpResponse<String> anonymousMe = send(HttpRequest.newBuilder(uri("/api/v1/me"))
                .header("X-Forwarded-Proto", "https").GET());
        assertThat(anonymousMe.statusCode()).isEqualTo(401);
        assertThat(anonymousMe.headers().allValues("Set-Cookie"))
                .noneMatch(c -> c.startsWith(SecurityConfig.SESSION_COOKIE + "="));

        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("X-Forwarded-Proto", "https")
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"ana@klub.hr\",\"password\":\"" + TestUsers.PASSWORD + "\"}")));
        assertThat(login.statusCode()).isEqualTo(200);
        String sessionCookie = cookie(login, SecurityConfig.SESSION_COOKIE);
        assertThat(sessionCookie).contains("Secure").contains("HttpOnly").containsIgnoringCase("SameSite=Lax");
        String newToken = value(cookie(login, "XSRF-TOKEN"));
        assertThat(newToken).isNotBlank().isNotEqualTo(token); // rotated, and usable immediately

        String cookies = SecurityConfig.SESSION_COOKIE + "=" + value(sessionCookie) + "; XSRF-TOKEN=" + newToken;
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v1/me"))
                .header("X-Forwarded-Proto", "https").header("Cookie", cookies).GET());
        assertThat(me.statusCode()).isEqualTo(200);

        HttpResponse<String> logout = send(HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                .header("X-Forwarded-Proto", "https").header("Cookie", cookies).header("X-XSRF-TOKEN", newToken)
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(logout.statusCode()).as(logout.body()).isEqualTo(204);
        // Session cookie deleted (Tomcat expresses it as an expiry in the past).
        assertThat(cookie(logout, SecurityConfig.SESSION_COOKIE))
                .startsWith(SecurityConfig.SESSION_COOKIE + "=;")
                .containsAnyOf("Max-Age=0", "Expires=Thu, 01 Jan 1970");

        // The old session cookie is now worthless.
        HttpResponse<String> afterLogout = send(HttpRequest.newBuilder(uri("/api/v1/me"))
                .header("X-Forwarded-Proto", "https").header("Cookie", cookies).GET());
        assertThat(afterLogout.statusCode()).isEqualTo(401);
    }

    @Test
    void csrfCookieIsNotSecureOnPlainHttpLocalDevelopment() throws Exception {
        HttpResponse<String> info = send(HttpRequest.newBuilder(uri("/api/v1/system/info")).GET());
        assertThat(cookie(info, "XSRF-TOKEN")).doesNotContain("Secure");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String cookie(HttpResponse<?> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        return cookies.stream().filter(c -> c.startsWith(name + "=")).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("No Set-Cookie for " + name + " in " + cookies));
    }

    private static String value(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }
}
