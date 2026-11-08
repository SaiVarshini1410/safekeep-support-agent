package com.safekeep.agent.security;

import com.safekeep.agent.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityTest extends IntegrationTestBase {

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("TC-SEC-01 Without a session, pages redirect to login and the API returns 401")
    void unauthenticated() throws Exception {
        mvc.perform(get("/investigations").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/api/investigations")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {4}")
    @DisplayName("TC-SEC-02 Role rules on endpoints")
    @CsvSource({
            "GET,   /api/health,                                   alice, APP_USER,         403",
            "GET,   /api/health,                                   sam,   SUPPORT_ENGINEER, 200",
            "GET,   /api/metrics,                                  alice, APP_USER,         403",
            "PATCH, /api/applications/backup-server/tools/get_logs, sam,  SUPPORT_ENGINEER, 403",
            "PATCH, /api/applications/backup-server/tools/get_logs, adam, ADMIN,            200",
            "GET,   /applications,                                 sam,   SUPPORT_ENGINEER, 403"
    })
    void roleRules(String method, String url, String name, String role, int expected) throws Exception {
        var request = method.equals("GET") ? get(url)
                : patch(url).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}");
        mvc.perform(request.with(user(name).roles(role)).with(csrf())).andExpect(status().is(expected));
    }

    @Test
    @DisplayName("TC-CFG-03 Admin toggle takes effect and is audited")
    void adminToggleAudited() throws Exception {
        mvc.perform(patch("/api/applications/backup-server/tools/get_logs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}").with(user("adam").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());
        assertThat(registry.isEnabled("backup-server", "get_logs")).isFalse();
        assertThat(auditRepo.findByType("CONFIG_CHANGED")).anySatisfy(a -> assertThat(a.getActor()).isEqualTo("adam"));
    }

    @Test
    @DisplayName("TC-SEC-03 Browser session POST without CSRF token is rejected")
    void csrfRequiredForSessions() throws Exception {
        Long id = newInvestigation("S-1A");
        mvc.perform(post("/api/investigations/" + id + "/proposals/1/approve").with(user("sam").roles("SUPPORT_ENGINEER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Event intake works with an API key and no session or CSRF token")
    void eventIntakeWithApiKey() throws Exception {
        String body = """
                {"eventId":"evt-sec-1","application":"backup-server","eventType":"HIGH_MEMORY",
                 "resource":"backup-server-01","timestamp":"%s","details":{}}""".formatted(clock.instant());
        mvc.perform(post("/api/events").header("X-Api-Key", "bk-test-key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/events").header("X-Api-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
