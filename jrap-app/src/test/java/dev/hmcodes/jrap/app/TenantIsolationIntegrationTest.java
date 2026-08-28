package dev.hmcodes.jrap.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.app.support.IntegrationTestBase;
import dev.hmcodes.jrap.app.support.RecordingEmailSender;
import dev.hmcodes.jrap.app.support.TestEmailConfig;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.repo.AppUserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-tenant isolation (FR-AUTH-3, NFR-SEC-2, AC-5): no API call and no repository
 * query may reach another organisation's rows. Enforcement is PostgreSQL row-level
 * security — verified here both through the API and directly at the data layer.
 */
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestEmailConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "Isolation-pass-7";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecordingEmailSender emails;
    @Autowired AppUserRepository users;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID orgAId;
    private UUID orgBId;
    private String ownerAToken;
    private UUID ownerBUserId;

    @BeforeAll
    void setUpTwoOrganisations() throws Exception {
        orgAId = registerAndVerify("Org A", "owner@org-a.example");
        orgBId = registerAndVerify("Org B", "owner@org-b.example");
        ownerAToken = login("owner@org-a.example");
        String ownerBToken = login("owner@org-b.example");
        MvcResult meB = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isOk()).andReturn();
        ownerBUserId = UUID.fromString(
                objectMapper.readTree(meB.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void apiListsOnlyOwnOrganisationsUsers() throws Exception {
        mockMvc.perform(get("/api/v1/organisations/current/users")
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("owner@org-a.example"));
    }

    @Test
    void apiCannotTouchAnotherOrganisationsUser() throws Exception {
        mockMvc.perform(patch("/api/v1/organisations/current/users/{id}/role", ownerBUserId)
                        .header("Authorization", "Bearer " + ownerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
        // And org B's owner is untouched.
        String ownerBToken = login("owner@org-b.example");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void rowLevelSecurityFiltersUnscopedQueries() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // A findAll() with tenant A in scope returns ONLY tenant A rows — the database
        // filters; no WHERE clause from the application is involved.
        TenantContext.setOrganisation(orgAId);
        try {
            List<AppUser> visible = tx.execute(status -> users.findAll());
            assertThat(visible).isNotEmpty();
            assertThat(visible).allMatch(u -> u.getOrganisationId().equals(orgAId));
        } finally {
            TenantContext.clear();
        }

        // With no tenant and no system access, nothing is visible at all.
        List<AppUser> noScope = tx.execute(status -> users.findAll());
        assertThat(noScope).isEmpty();

        // System access (pre-auth flows) sees both organisations.
        List<AppUser> systemScope = TenantContext.runAsSystem(() -> tx.execute(status -> users.findAll()));
        assertThat(systemScope).extracting(AppUser::getOrganisationId)
                .contains(orgAId, orgBId);
    }

    @Test
    void auditLogIsTenantScoped() throws Exception {
        mockMvc.perform(get("/api/v1/organisations/current/audit-log")
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actorEmail == 'owner@org-b.example')]").isEmpty());
    }

    private UUID registerAndVerify(String orgName, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organisationName":"%s","email":"%s",
                                 "password":"%s","displayName":"Owner"}
                                """.formatted(orgName, email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(emails.lastTokenFor(email))))
                .andExpect(status().isNoContent());
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("organisationId").asText());
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
