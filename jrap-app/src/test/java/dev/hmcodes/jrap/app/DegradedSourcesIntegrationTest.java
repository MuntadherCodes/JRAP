package dev.hmcodes.jrap.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.app.support.IntegrationTestBase;
import dev.hmcodes.jrap.app.support.OjsSiteStub;
import dev.hmcodes.jrap.app.support.RecordingEmailSender;
import dev.hmcodes.jrap.app.support.TestEmailConfig;
import dev.hmcodes.jrap.crawl.pipeline.AuditRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-7 (graceful degradation): with OpenAlex completely unreachable — a dead port, not
 * an error response — a URL-registered journal still audits end to end. The audit
 * COMPLETEs, crawl/extract/gateway results are intact, and the standing category
 * degrades to the explicit no-citation-data UNCLEAR criterion instead of guessing.
 *
 * <p>Runs in its own class with its OWN ISSN: the api_record cache is global across
 * test classes in the shared database, so reusing the main stub's ISSN would serve
 * cached OpenAlex responses recorded by {@link CrawlPipelineIntegrationTest} and
 * silently defeat the dead-source setup.</p>
 */
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestEmailConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DegradedSourcesIntegrationTest extends IntegrationTestBase {

    /** Distinct valid ISSN (checksum 9) — never used by any other test class. */
    private static final String ISSN = "1234-5679";
    private static final OjsSiteStub STUB = new OjsSiteStub(ISSN, "Stub Journal of Degraded Sources");
    private static final String PASSWORD = "Degrade-pass-2026";

    @DynamicPropertySource
    static void degradedProperties(DynamicPropertyRegistry registry) throws Exception {
        // AC-7 network-dead source: nothing listens on the discard port, so every
        // OpenAlex call fails at connect time after the fetcher's bounded retries.
        registry.add("jrap.integrations.openalex-base-url", () -> "http://127.0.0.1:9");
        registry.add("jrap.integrations.crossref-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.doaj-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.issn-portal-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.per-host-min-interval-ms", () -> "0");
        String snapshotDir = Files.createTempDirectory("jrap-snapshots-degraded").toString();
        registry.add("jrap.snapshots.root-dir", () -> snapshotDir);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecordingEmailSender emails;
    @Autowired AuditRunner runner;

    private String ownerToken;

    @AfterAll
    void tearDown() {
        STUB.stop();
    }

    @Test
    void auditCompletesWithStandingUnclearWhenOpenAlexIsDead() throws Exception {
        // Registration by URL (FR-JRN-1): the site states the ISSN; Crossref resolves it,
        // OpenAlex being dead must not block registration.
        register("Degraded Org", "owner@degraded-test.example");
        ownerToken = login("owner@degraded-test.example");
        MvcResult journal = mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"%s/\"}".formatted(STUB.baseUrl())))
                .andExpect(status().isCreated()).andReturn();
        String journalId = objectMapper.readTree(journal.getResponse().getContentAsString())
                .get("id").asText();

        MvcResult created = mockMvc.perform(post("/api/v1/journals/{id}/audits", journalId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated()).andReturn();
        String auditId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();
        runUntilTerminal(auditId);

        // The audit COMPLETEs; the pipeline reached its resting stage (REVIEW).
        JsonNode audit = getJson("/api/v1/audits/" + auditId);
        assertThat(audit.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(audit.get("stage").asText()).isEqualTo("REVIEW");

        // Crawl and extraction are intact: snapshots inventoried, articles with metadata.
        assertThat(getJson("/api/v1/audits/" + auditId + "/snapshots").size())
                .isGreaterThanOrEqualTo(8);
        JsonNode articles = getJson("/api/v1/audits/" + auditId + "/articles");
        assertThat(articles.size()).isEqualTo(3);
        assertThat(articles.get(0).get("title").asText()).isNotBlank();

        // Analysis ran: the gateway table is present and populated.
        JsonNode analysis = getJson("/api/v1/audits/" + auditId + "/analysis");
        assertThat(analysis.get("gateway").size()).isGreaterThanOrEqualTo(5);

        // Standing degrades to the explicit UNCLEAR criterion; the other categories
        // still score from the material that IS available.
        JsonNode standing = null;
        java.util.List<String> categories = new java.util.ArrayList<>();
        for (JsonNode score : analysis.get("scores")) {
            categories.add(score.get("category").asText());
            if ("standing".equals(score.get("category").asText())) {
                standing = score;
            }
        }
        assertThat(categories).contains("policy", "content", "standing", "regularity", "availability");
        assertThat(standing).isNotNull();
        assertThat(standing.get("criteria").asText())
                .contains("standing.noCitationData")
                .contains("UNCLEAR");

        // The citation-trend metric declares the gap instead of inventing a trend.
        boolean trendSeen = false;
        for (JsonNode metric : analysis.get("metrics")) {
            if ("citation_trend".equals(metric.get("name").asText())) {
                trendSeen = true;
                assertThat(metric.get("detail").asText()).contains("UNKNOWN");
            }
        }
        assertThat(trendSeen).as("citation_trend metric is still recorded").isTrue();
    }

    private void runUntilTerminal(String id) throws Exception {
        for (int i = 0; i < 120; i++) {
            runner.runOnce();
            MvcResult result = mockMvc.perform(get("/api/v1/audits/{aid}", id)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk()).andReturn();
            String auditStatus = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("status").asText();
            if (!auditStatus.equals("PENDING") && !auditStatus.equals("RUNNING")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Audit " + id + " did not reach a terminal state");
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void register(String orgName, String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organisationName":"%s","email":"%s",
                                 "password":"%s","displayName":"Owner"}
                                """.formatted(orgName, email, PASSWORD)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(emails.lastTokenFor(email))))
                .andExpect(status().isNoContent());
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
