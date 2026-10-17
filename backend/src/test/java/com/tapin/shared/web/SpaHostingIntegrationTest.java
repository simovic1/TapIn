package com.tapin.shared.web;

import com.tapin.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "tapin.frontend.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SpaHostingIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void publicShellSupportsDirectNavigationToNestedPages() throws Exception {
        for (String path : new String[]{"/", "/prijava", "/treninzi/12/uredi", "/clanovi/42/pojasevi", "/profil"}) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void servingTheShellDoesNotExposeApiDataOrUnknownFiles() throws Exception {
        for (String path : new String[]{"/api/v1/me", "/api/v1/me/notifications", "/api/v1/admin/outbox", "/actuator/metrics", "/.env"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CSRF_TOKEN_INVALID"));
    }
}
