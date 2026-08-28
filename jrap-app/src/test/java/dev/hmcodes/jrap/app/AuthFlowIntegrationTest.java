package dev.hmcodes.jrap.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.app.support.IntegrationTestBase;
import dev.hmcodes.jrap.app.support.RecordingEmailSender;
import dev.hmcodes.jrap.app.support.TestEmailConfig;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end FR-AUTH-1/2: registration, verification, login, invitation, refresh rotation. */
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestEmailConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIntegrationTest extends IntegrationTestBase {

    private static final String OWNER_EMAIL = "owner@flow-test.example";
    private static final String ANALYST_EMAIL = "analyst@flow-test.example";
    private static final String PASSWORD = "Correct-horse-9";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecordingEmailSender emails;

    private String ownerAccessToken;
    private String ownerRefreshToken;

    @Test
    @Order(1)
    void registerVerifyAndLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organisationName":"Flow Test Org","email":"%s",
                                 "password":"%s","displayName":"Flow Owner"}
                                """.formatted(OWNER_EMAIL, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organisationId").isNotEmpty());

        // Login before verification must fail.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(OWNER_EMAIL, PASSWORD)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(emails.lastTokenFor(OWNER_EMAIL))))
                .andExpect(status().isNoContent());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(OWNER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("OWNER"))
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        ownerAccessToken = body.get("accessToken").asText();
        ownerRefreshToken = body.get("refreshToken").asText();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + ownerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(OWNER_EMAIL));
    }

    @Test
    @Order(2)
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"wrong-password-1\"}".formatted(OWNER_EMAIL)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void inviteAnalystAndAccept() throws Exception {
        mockMvc.perform(post("/api/v1/organisations/current/invitations")
                        .header("Authorization", "Bearer " + ownerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"role\":\"ANALYST\"}".formatted(ANALYST_EMAIL)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/accept-invitation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"%s","displayName":"Flow Analyst"}
                                """.formatted(emails.lastTokenFor(ANALYST_EMAIL), PASSWORD)))
                .andExpect(status().isNoContent());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ANALYST_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ANALYST"))
                .andReturn();
        String analystToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();

        // Analysts must not be able to invite (owner-only, FR-AUTH-1).
        mockMvc.perform(post("/api/v1/organisations/current/invitations")
                        .header("Authorization", "Bearer " + analystToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@flow-test.example\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    void refreshRotationDetectsReuse() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(ownerRefreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        String rotated = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(ownerRefreshToken);

        // Presenting the consumed token again is reuse: rejected and the family is revoked.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(ownerRefreshToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(rotated)))
                .andExpect(status().isUnauthorized());
    }
}
