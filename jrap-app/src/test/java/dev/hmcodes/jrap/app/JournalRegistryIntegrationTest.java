package dev.hmcodes.jrap.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.app.support.IntegrationTestBase;
import dev.hmcodes.jrap.app.support.RecordingEmailSender;
import dev.hmcodes.jrap.app.support.ScholarSourceStub;
import dev.hmcodes.jrap.app.support.TestEmailConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 end-to-end: registration by ISSN and URL against stubbed scholarly sources
 * (FR-JRN-1), identity-inconsistency findings with evidence (FR-JRN-2), graceful
 * degradation when a source is blocked (FR-INT-6), the ApiRecord cache (CON-3),
 * quotas and archiving (FR-JRN-3), and cross-tenant invisibility of journals.
 */
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestEmailConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JournalRegistryIntegrationTest extends IntegrationTestBase {

    private static final ScholarSourceStub STUB = new ScholarSourceStub();
    private static final String PASSWORD = "Registry-pass-77";

    @DynamicPropertySource
    static void sourceProperties(DynamicPropertyRegistry registry) {
        registry.add("jrap.integrations.openalex-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.crossref-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.doaj-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.issn-portal-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.per-host-min-interval-ms", () -> "0");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecordingEmailSender emails;

    private String ownerAToken;
    private String ownerBToken;
    private UUID orgBId;
    private String journalAId;

    @BeforeAll
    void setUpOrganisations() throws Exception {
        registerAndVerify("Registry Org A", "owner@registry-a.example");
        orgBId = registerAndVerify("Registry Org B", "owner@registry-b.example");
        ownerAToken = login("owner@registry-a.example");
        ownerBToken = login("owner@registry-b.example");
    }

    @AfterAll
    void stopStub() {
        STUB.stop();
    }

    @Test
    @Order(1)
    void invalidIssnIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issn\":\"1234-5678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("invalid-issn"));
    }

    @Test
    @Order(2)
    void registerByIssnResolvesIdentityAndFindings() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issn\":\"%s\"}".formatted(ScholarSourceStub.ISSN_PRINT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(ScholarSourceStub.TITLE))
                .andExpect(jsonPath("$.publisher").value(ScholarSourceStub.PUBLISHER))
                .andExpect(jsonPath("$.issnL").value(ScholarSourceStub.ISSN_PRINT))
                .andExpect(jsonPath("$.issnPrint").value(ScholarSourceStub.ISSN_PRINT))
                .andExpect(jsonPath("$.issnOnline").value(ScholarSourceStub.ISSN_ONLINE))
                .andExpect(jsonPath("$.platform").value(ScholarSourceStub.OJS_GENERATOR))
                .andExpect(jsonPath("$.inCrossref").value(true))
                .andExpect(jsonPath("$.inDoaj").value(true))
                .andReturn();
        journalAId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();

        // Identity per source: 4 scholarly sources + the homepage, ISSN Portal blocked → UNAVAILABLE.
        mockMvc.perform(get("/api/v1/journals/{id}", journalAId)
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.length()").value(5))
                .andExpect(jsonPath(
                        "$.identity[?(@.source == 'ISSN_PORTAL' && @.availability == 'UNAVAILABLE')]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                        "$.identity[?(@.source == 'CROSSREF' && @.availability == 'OK')]")
                        .isNotEmpty());

        // FR-JRN-2: the seeded swapped ISSNs and publisher mismatch become findings,
        // and the blocked ISSN Portal is an UNCLEAR/info finding (FR-INT-6).
        MvcResult findingsResult = mockMvc.perform(get("/api/v1/journals/{id}/findings", journalAId)
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode findings = objectMapper.readTree(findingsResult.getResponse().getContentAsString());
        assertThat(findings.findValuesAsText("code"))
                .contains("IDENTITY_SWAPPED_ISSNS", "IDENTITY_PUBLISHER_MISMATCH",
                        "IDENTITY_SOURCE_UNAVAILABLE_ISSN_PORTAL");
        JsonNode swapped = null;
        for (JsonNode finding : findings) {
            if ("IDENTITY_SWAPPED_ISSNS".equals(finding.get("code").asText())) {
                swapped = finding;
            }
        }
        assertThat(swapped).isNotNull();
        assertThat(swapped.get("severity").asText()).isEqualTo("HIGH");
        assertThat(swapped.get("evidenceItemIds").size()).isEqualTo(2);
        assertThat(findings.findValuesAsText("code")).doesNotContain("IDENTITY_TITLE_MISMATCH");
    }

    @Test
    @Order(3)
    void duplicateRegistrationConflicts() throws Exception {
        mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issn\":\"%s\"}".formatted(ScholarSourceStub.ISSN_PRINT)))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(4)
    void journalsAreInvisibleAcrossTenantsAndCacheServesSecondOrganisation() throws Exception {
        // Org B cannot see org A's journal.
        mockMvc.perform(get("/api/v1/journals/{id}", journalAId)
                        .header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isNotFound());

        // Org B registers the same ISSN: allowed (public data), and served from the
        // ApiRecord cache — the stub must see NO new OpenAlex request.
        int openAlexHitsBefore = STUB.hitsFor("/sources/issn:");
        mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issn\":\"%s\"}".formatted(ScholarSourceStub.ISSN_PRINT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(ScholarSourceStub.TITLE));
        assertThat(STUB.hitsFor("/sources/issn:")).isEqualTo(openAlexHitsBefore);
    }

    @Test
    @Order(5)
    void quotaBlocksAndArchivingFrees() throws Exception {
        // Platform admin (beta: direct DB) caps org B at 1 journal.
        try (Connection superuser = POSTGRES.getPostgresDatabase().getConnection();
             PreparedStatement insert = superuser.prepareStatement(
                     "insert into org_quota (org_id, max_journals, updated_at) values (?, 1, now())")) {
            insert.setObject(1, orgBId);
            insert.executeUpdate();
        }

        // Org B is at its quota → graceful refusal (FR-BILL-2 mechanism).
        mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issn\":\"%s\"}".formatted(ScholarSourceStub.ISSN_ONLINE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("quota-reached"));

        // Archive the journal → the active count drops → registration works again.
        String journalBId = objectMapper.readTree(mockMvc.perform(get("/api/v1/journals")
                                .header("Authorization", "Bearer " + ownerBToken))
                        .andExpect(status().isOk()).andReturn()
                        .getResponse().getContentAsString())
                .get(0).get("id").asText();
        mockMvc.perform(post("/api/v1/journals/{id}/archive", journalBId)
                        .header("Authorization", "Bearer " + ownerBToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issn\":\"%s\"}".formatted(ScholarSourceStub.ISSN_PRINT)))
                .andExpect(status().isCreated());
    }

    @Test
    @Order(6)
    void registerByHomepageUrl() throws Exception {
        registerAndVerify("Registry Org C", "owner@registry-c.example");
        String ownerCToken = login("owner@registry-c.example");
        mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerCToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"%s/journal-home\"}".formatted(STUB.baseUrl())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(ScholarSourceStub.TITLE))
                .andExpect(jsonPath("$.platform").value(ScholarSourceStub.OJS_GENERATOR))
                .andExpect(jsonPath("$.issnL").value(ScholarSourceStub.ISSN_PRINT));
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("organisationId").asText());
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
