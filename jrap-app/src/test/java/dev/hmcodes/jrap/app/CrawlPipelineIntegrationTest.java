package dev.hmcodes.jrap.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.app.support.IntegrationTestBase;
import dev.hmcodes.jrap.app.support.OjsSiteStub;
import dev.hmcodes.jrap.app.support.RecordingEmailSender;
import dev.hmcodes.jrap.app.support.TestEmailConfig;
import dev.hmcodes.jrap.crawl.pipeline.AuditRunner;
import dev.hmcodes.jrap.crawl.store.SnapshotStore;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 end-to-end: crawl of a stub OJS site — discovery and classification
 * (FR-CRWL-1/2), immutable snapshots with PDF text (FR-CRWL-3/7), robots compliance
 * with recorded skips (FR-CRWL-4, AC-4 seed), page-cap enforcement, OAI cross-check
 * finding, resume without refetching (NFR-AVL-1), and tenant isolation of audits.
 */
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestEmailConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrawlPipelineIntegrationTest extends IntegrationTestBase {

    private static final OjsSiteStub STUB = new OjsSiteStub();
    private static final String PASSWORD = "Crawl-pass-2026";

    @DynamicPropertySource
    static void crawlProperties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("jrap.integrations.openalex-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.crossref-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.doaj-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.issn-portal-base-url", STUB::baseUrl);
        registry.add("jrap.integrations.per-host-min-interval-ms", () -> "0");
        String snapshotDir = Files.createTempDirectory("jrap-snapshots-test").toString();
        registry.add("jrap.snapshots.root-dir", () -> snapshotDir);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecordingEmailSender emails;
    @Autowired AuditRunner runner;
    @Autowired SnapshotStore snapshotStore;

    private String ownerToken;
    private String journalId;
    private String auditId;

    @BeforeAll
    void setUp() throws Exception {
        register("Crawl Org", "owner@crawl-test.example");
        ownerToken = login("owner@crawl-test.example");
        MvcResult journal = mockMvc.perform(post("/api/v1/journals")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issn\":\"%s\"}".formatted(OjsSiteStub.ISSN)))
                .andExpect(status().isCreated()).andReturn();
        journalId = objectMapper.readTree(journal.getResponse().getContentAsString())
                .get("id").asText();
    }

    @AfterAll
    void tearDown() {
        STUB.stop();
    }

    @Test
    @Order(1)
    void crawlCompletesWithClassifiedSnapshots() throws Exception {
        auditId = createAudit();
        runUntilTerminal(auditId);

        mockMvc.perform(get("/api/v1/audits/{id}", auditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));

        JsonNode inventory = getJson("/api/v1/audits/" + auditId + "/snapshots");
        Map<String, Long> byType = new java.util.HashMap<>();
        for (JsonNode snapshot : inventory) {
            byType.merge(snapshot.get("pageType").asText(), 1L, Long::sum);
        }
        assertThat(byType.get("home")).isEqualTo(1);
        assertThat(byType.get("archive")).isEqualTo(1);
        assertThat(byType.get("issue")).isEqualTo(2);
        assertThat(byType.get("article-landing")).isEqualTo(3);
        assertThat(byType.get("article-pdf")).isEqualTo(1);
        assertThat(byType.get("editorial-team")).isEqualTo(1);
        assertThat(byType.get("ethics")).isEqualTo(1);
        assertThat(byType).doesNotContainKey(null);
    }

    @Test
    @Order(2)
    void robotsDisallowedUrlIsSkippedWithReasonAndNeverFetched() throws Exception {
        JsonNode skipped = getJson("/api/v1/audits/" + auditId + "/skipped");
        List<String> reasons = new java.util.ArrayList<>();
        boolean privateSkipped = false;
        for (JsonNode entry : skipped) {
            reasons.add(entry.get("reason").asText());
            if (entry.get("url").asText().endsWith("/private/secret")) {
                privateSkipped = true;
                assertThat(entry.get("reason").asText()).isEqualTo("robots-disallowed");
            }
        }
        assertThat(privateSkipped).as("robots-disallowed page must appear in the skip log").isTrue();
        // AC-4: the disallowed page was never requested from the site.
        assertThat(STUB.hitsFor("/private/secret")).isZero();
    }

    @Test
    @Order(3)
    void pdfTextIsExtractedIntoTheSnapshotStore() throws Exception {
        JsonNode inventory = getJson("/api/v1/audits/" + auditId + "/snapshots");
        String pdfUrl = null;
        for (JsonNode snapshot : inventory) {
            if ("article-pdf".equals(snapshot.get("pageType").asText())) {
                pdfUrl = snapshot.get("url").asText();
            }
        }
        assertThat(pdfUrl).isNotNull();
        // Read the stored text payload directly through the snapshot store.
        try (Connection superuser = POSTGRES.getPostgresDatabase().getConnection();
             PreparedStatement select = superuser.prepareStatement(
                     "select text_storage_key from snapshot where url = ?")) {
            select.setString(1, pdfUrl);
            try (var rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String textKey = rs.getString(1);
                assertThat(textKey).isNotNull();
                String text = new String(snapshotStore.get(textKey), StandardCharsets.UTF_8);
                assertThat(text).contains(OjsSiteStub.PDF_SENTENCE);
            }
        }
    }

    @Test
    @Order(4)
    void oaiCrossCheckRecordsMismatchFinding() throws Exception {
        JsonNode findings = getJson("/api/v1/journals/" + journalId + "/findings");
        List<String> codes = findings.findValuesAsText("code");
        assertThat(codes).contains("CRAWL_OAI_HTML_MISMATCH");
    }

    @Test
    @Order(5)
    void resumeDoesNotRefetchCompletedWork() throws Exception {
        int archiveHitsBefore = STUB.hitsFor("/issue/archive");
        // Simulate a crashed instance: force the completed audit back to RUNNING.
        try (Connection superuser = POSTGRES.getPostgresDatabase().getConnection();
             PreparedStatement update = superuser.prepareStatement(
                     "update audit set status = 'RUNNING', finished_at = null where id = ?")) {
            update.setObject(1, UUID.fromString(auditId));
            update.executeUpdate();
        }
        runUntilTerminal(auditId);
        mockMvc.perform(get("/api/v1/audits/{id}", auditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));
        // NFR-AVL-1: the resumed run found its frontier fully checkpointed — no refetch.
        assertThat(STUB.hitsFor("/issue/archive")).isEqualTo(archiveHitsBefore);
    }

    @Test
    @Order(6)
    void pageCapStopsTheCrawlAndRecordsSkips() throws Exception {
        String cappedAuditId = createAudit();
        try (Connection superuser = POSTGRES.getPostgresDatabase().getConnection();
             PreparedStatement update = superuser.prepareStatement(
                     "update audit set page_cap = 2 where id = ?")) {
            update.setObject(1, UUID.fromString(cappedAuditId));
            update.executeUpdate();
        }
        runUntilTerminal(cappedAuditId);
        MvcResult result = mockMvc.perform(get("/api/v1/audits/{id}", cappedAuditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andReturn();
        JsonNode audit = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(audit.get("pagesFetched").asInt()).isEqualTo(2);
        JsonNode skipped = getJson("/api/v1/audits/" + cappedAuditId + "/skipped");
        List<String> reasons = new java.util.ArrayList<>();
        skipped.forEach(entry -> reasons.add(entry.get("reason").asText()));
        assertThat(reasons).contains("page-cap-reached");
    }

    @Test
    @Order(8)
    void extractionProducesBoardAndArticlesWithProvenance() throws Exception {
        // FR-EXT-1/2/3/4: deterministic extraction with confidence + provenance.
        mockMvc.perform(get("/api/v1/audits/{id}/extraction-summary", auditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardMembers").value(5))
                .andExpect(jsonPath("$.boardMembersNeedingReview").value(0))
                .andExpect(jsonPath("$.articles").value(3))
                .andExpect(jsonPath("$.articlesNeedingReview").value(0));

        mockMvc.perform(get("/api/v1/audits/{id}", auditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articlesExtracted").value(3))
                .andExpect(jsonPath("$.boardMembersExtracted").value(5))
                .andExpect(jsonPath("$.stage").value("ENRICH"));

        JsonNode board = getJson("/api/v1/audits/" + auditId + "/board");
        assertThat(board.findValuesAsText("name")).contains("Prof. Ali Hassan", "Dr. John Smith");
        boolean aliOk = false;
        for (JsonNode member : board) {
            assertThat(member.get("needsReview").asBoolean()).isFalse();
            assertThat(member.get("method").asText()).isEqualTo("PARSER");
            if (member.get("name").asText().equals("Prof. Ali Hassan")) {
                assertThat(member.get("institution").asText()).isEqualTo("University of Baghdad");
                assertThat(member.get("country").asText()).isEqualTo("Iraq");
                aliOk = true;
            }
        }
        assertThat(aliOk).isTrue();

        JsonNode articles = getJson("/api/v1/audits/" + auditId + "/articles");
        assertThat(articles.size()).isEqualTo(3);
        boolean article101Ok = false;
        for (JsonNode article : articles) {
            assertThat(article.get("authors").size()).isEqualTo(2);
            assertThat(article.get("needsReview").asBoolean()).isFalse();
            if ("10.99999/stub.101".equals(article.path("doi").asText())) {
                assertThat(article.get("title").asText())
                        .isEqualTo("Machine learning for stub diagnostics");
                assertThat(article.get("datePublished").asText()).isEqualTo("2026/03/01");
                assertThat(article.get("dateSubmitted").asText()).isEqualTo("2026-01-05");
                assertThat(article.get("dateAccepted").asText()).isEqualTo("2026-02-10");
                assertThat(article.get("titleScript").asText()).isEqualTo("ROMAN");
                assertThat(article.get("abstractLanguage").asText()).isEqualTo("en");
                assertThat(article.get("referencesCount").asInt()).isEqualTo(3);
                assertThat(article.get("authors").get(0).get("country").asText()).isEqualTo("Iraq");
                article101Ok = true;
            }
        }
        assertThat(article101Ok).isTrue();
    }

    @Test
    @Order(9)
    void reconciliationFlagsTheSeededCrossrefMismatchOnly() throws Exception {
        // FR-EXT-5: stub.102's Crossref record deliberately disagrees on the title.
        JsonNode findings = getJson("/api/v1/journals/" + journalId + "/findings");
        List<String> codes = findings.findValuesAsText("code");
        assertThat(codes).contains("EXT_TITLE_MISMATCH_CROSSREF");
        assertThat(codes).doesNotContain("EXT_TITLE_MISMATCH_OPENALEX",
                "EXT_AUTHOR_COUNT_MISMATCH_CROSSREF", "EXT_DOI_NOT_IN_CROSSREF",
                "EXT_DATE_MISMATCH_CROSSREF");

        // NFR-AI-1: provider disabled -> deterministic parsers only, zero LLM calls.
        try (Connection superuser = POSTGRES.getPostgresDatabase().getConnection();
             PreparedStatement count = superuser.prepareStatement("select count(*) from llm_call")) {
            try (var rs = count.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    @Test
    @Order(7)
    void auditsAreInvisibleAcrossTenants() throws Exception {
        register("Crawl Org B", "owner@crawl-b.example");
        String tokenB = login("owner@crawl-b.example");
        mockMvc.perform(get("/api/v1/audits/{id}", auditId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    private String createAudit() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/journals/{id}/audits", journalId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
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
