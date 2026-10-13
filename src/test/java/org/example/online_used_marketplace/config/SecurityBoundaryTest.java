package org.example.online_used_marketplace.config;

import org.example.online_used_marketplace.utils.JwtFilter;
import org.example.online_used_marketplace.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(SecurityBoundaryTest.TestConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "jwt.token-header=Authorization", "jwt.token-prefix=Bearer",
        "jwt.secret=test-only-key-at-least-thirty-two-bytes", "jwt.expiration=60000",
        "app.monitoring.scrape-token=test-only-scrape-secret",
        "app.cors.allowed-origins=http://localhost:5173"
})
class SecurityBoundaryTest {

    @Autowired
    WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousUploadAndMetricsAreRejectedButHealthRemainsPublic() throws Exception {
        mvc.perform(post("/api/files/upload")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics/http.server.requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void internalEndpointsRejectUserButAllowInternalAndAdmin() throws Exception {
        mvc.perform(get("/api/ai/tools/probe").with(user("buyer").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/aiops/probe").with(user("buyer").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/tools/probe").with(user("staff").roles("INTERNAL")))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/files/upload").with(user("buyer").roles("USER")))
                .andExpect(status().isOk());
    }

    @Test
    void scrapeSecretOnlyGrantsGetPrometheusAccess() throws Exception {
        String auth = "Bearer test-only-scrape-secret";
        mvc.perform(get("/actuator/prometheus").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get("/api/ai/tools/probe").header("Authorization", auth))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics/http.server.requests").header("Authorization", auth))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/actuator/prometheus").header("Authorization", auth))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookUsesItsOwnAuthBoundaryAndUnlistedCorsOriginIsRejected() throws Exception {
        mvc.perform(post("/api/ai/aiops/alerts/alertmanager")).andExpect(status().isOk());
        mvc.perform(options("/api/files/upload").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
        mvc.perform(options("/api/files/upload").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isOk());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestConfiguration {
        @Bean JwtUtil jwtUtil() { return mock(JwtUtil.class); }
        @Bean JwtFilter jwtFilter(JwtUtil jwt) { return new JwtFilter(jwt); }
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @RestController
    static class ProbeController {
        @RequestMapping({"/api/files/upload", "/actuator/health", "/actuator/prometheus",
                "/actuator/metrics/http.server.requests", "/api/ai/tools/probe", "/api/ai/aiops/probe",
                "/api/ai/aiops/alerts/alertmanager"})
        String probe() { return "ok"; }
    }
}
