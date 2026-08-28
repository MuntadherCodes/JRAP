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
    private String cappedAuditId;
    private String reportId;

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
        cappedAuditId = createAudit();
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
                // The automated pipeline hands over to analysts: completed audits rest at REVIEW.
                .andExpect(jsonPath("$.stage").value("REVIEW"));

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
    @Order(10)
    void analysisProducesGatewayOutcomesAndScores() throws Exception {
        // FR-ANL-1/5: deterministic gateway outcomes and rubric-v1.0 scores on the
        // seeded stub data — walked by hand, asserted exactly.
        JsonNode analysis = getJson("/api/v1/audits/" + auditId + "/analysis");
        assertThat(analysis.get("rubricVersion").asText()).isEqualTo("1.0");
        java.util.Map<String, String> gateway = new java.util.HashMap<>();
        for (JsonNode check : analysis.get("gateway")) {
            gateway.put(check.get("code").asText(), check.get("outcome").asText());
        }
        assertThat(gateway.get("G1")).isEqualTo("FAIL");               // no peer-review page
        assertThat(gateway.get("G2")).isEqualTo("PASS_WITH_CAVEATS");  // thin issues
        assertThat(gateway.get("G3")).isEqualTo("PASS_WITH_CAVEATS");  // portal blocked
        assertThat(gateway.get("G4")).isEqualTo("PASS");
        assertThat(gateway.get("G5")).isEqualTo("PASS");               // ethics page exists
        assertThat(gateway.get("G6")).isEqualTo("PASS");

        java.util.Map<String, Integer> scores = new java.util.HashMap<>();
        for (JsonNode score : analysis.get("scores")) {
            scores.put(score.get("category").asText(), score.get("score").asInt());
        }
        assertThat(scores.get("policy")).isEqualTo(2);       // -2 review policy, -1 solicitation
        assertThat(scores.get("content")).isEqualTo(5);
        assertThat(scores.get("standing")).isEqualTo(1);     // floored by citation collapse
        assertThat(scores.get("regularity")).isEqualTo(3);   // -1 thin issues, -1 volume anomaly
        assertThat(scores.get("availability")).isEqualTo(3); // -1 pdf share, -1 preservation
    }

    @Test
    @Order(11)
    void redFlagDetectorsFireOnSeededAnomalies() throws Exception {
        JsonNode auditFindings = getJson("/api/v1/audits/" + auditId + "/findings");
        java.util.Map<String, String> statusByCode = new java.util.HashMap<>();
        for (JsonNode finding : auditFindings) {
            statusByCode.put(finding.get("code").asText(), finding.get("status").asText());
        }
        assertThat(statusByCode.keySet()).contains(
                "RF-01",  // volume spike/collapse
                "RF-02",  // citation surge-then-collapse
                "RF-03",  // reference-based self-citation
                "RF-04",  // board members author in own journal
                "RF-07",  // UNCLEAR: web search disabled
                "RF-10",  // "Indexed in Scopus" claim
                "RF-11",  // citation solicitation announcement
                "RF-12"); // multidisciplinary scope claim vs ~7 articles/year
        // CON-6: misconduct-class results are indicators requiring verification.
        assertThat(statusByCode.get("RF-10")).isEqualTo("NEEDS_VERIFICATION");
        assertThat(statusByCode.get("RF-11")).isEqualTo("NEEDS_VERIFICATION");
        assertThat(statusByCode.get("RF-12")).isEqualTo("NEEDS_VERIFICATION");
        assertThat(statusByCode.get("RF-02")).isEqualTo("AUTO");
        assertThat(statusByCode.keySet()).doesNotContain("RF-05", "RF-06", "RF-13");
    }

    @Test
    @Order(12)
    void reviewQueueListsFindingsAndSnapshotText() throws Exception {
        // FR-REV-1: the queue carries the audit's findings plus journal-level identity
        // findings a release would inherit; extractions were all confident, so none queue.
        JsonNode queue = getJson("/api/v1/audits/" + auditId + "/review/queue?filter=all&size=200");
        List<String> codes = queue.get("items").findValuesAsText("code");
        assertThat(codes).contains("RF-01", "RF-02", "RF-11", "RF-12");
        assertThat(queue.get("extractionsTotal").asLong()).isZero();
        assertThat(queue.get("findingsTotal").asLong()).isEqualTo(queue.get("total").asLong());

        // FR-REV-2 viewer support: snapshot text is servable for side-by-side checks.
        JsonNode snapshots = getJson("/api/v1/audits/" + auditId + "/snapshots");
        String snapshotId = snapshots.get(0).get("id").asText();
        mockMvc.perform(get("/api/v1/snapshots/{id}/text", snapshotId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());
    }

    @Test
    @Order(13)
    void reviewActionsChangeStateAndAreLogged() throws Exception {
        Map<String, String> idByCode = findingIdsByCode();

        // FR-REV-1: a rejection without a reason is refused.
        mockMvc.perform(post("/api/v1/findings/{id}/reject", idByCode.get("RF-01"))
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/findings/{id}/confirm", idByCode.get("RF-02"))
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Collapse verified against OpenAlex counts\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/findings/{id}/reject", idByCode.get("RF-01"))
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Volume pattern explained by a special issue\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/findings/{id}/severity", idByCode.get("RF-03"))
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"LOW\",\"reason\":\"Self-citation share is marginal\"}"))
                .andExpect(status().isOk());

        JsonNode findings = getJson("/api/v1/audits/" + auditId + "/findings");
        Map<String, JsonNode> byCode = new java.util.HashMap<>();
        for (JsonNode finding : findings) {
            byCode.put(finding.get("code").asText(), finding);
        }
        assertThat(byCode.get("RF-02").get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(byCode.get("RF-01").get("status").asText()).isEqualTo("REJECTED");
        assertThat(byCode.get("RF-03").get("severity").asText()).isEqualTo("LOW");
    }

    @Test
    @Order(14)
    void releaseGateTracksNeedsVerification() throws Exception {
        // FR-REV-4: not releasable while unresolved needs-verification findings remain.
        JsonNode before = getJson("/api/v1/audits/" + auditId + "/review/gate");
        assertThat(before.get("releasable").asBoolean()).isFalse();
        assertThat(before.get("needsVerification").asLong()).isGreaterThan(0);

        Map<String, String> idByCode = findingIdsByCode();
        // Exclude one indicator explicitly (annex-listed), confirm the rest.
        mockMvc.perform(post("/api/v1/findings/{id}/exclude", idByCode.get("RF-10"))
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Indexing claim predates the audit window\"}"))
                .andExpect(status().isOk());
        JsonNode queue = getJson("/api/v1/audits/" + auditId
                + "/review/queue?filter=needs-verification&size=200");
        for (JsonNode item : queue.get("items")) {
            if (!item.get("excluded").asBoolean()) {
                mockMvc.perform(post("/api/v1/findings/{id}/confirm", item.get("id").asText())
                                .header("Authorization", "Bearer " + ownerToken))
                        .andExpect(status().isOk());
            }
        }

        JsonNode after = getJson("/api/v1/audits/" + auditId + "/review/gate");
        assertThat(after.get("needsVerification").asLong()).isZero();
        assertThat(after.get("excluded").asLong()).isGreaterThan(0);
        assertThat(after.get("releasable").asBoolean()).isTrue();
    }

    @Test
    @Order(15)
    void decisionHistoryIsCompleteAndImmutable() throws Exception {
        // FR-INT-7: manual evidence attaches as a first-class, linkable evidence item.
        Map<String, String> idByCode = findingIdsByCode();
        String payload = java.util.Base64.getEncoder()
                .encodeToString("fake-screenshot-bytes".getBytes(StandardCharsets.UTF_8));
        MvcResult attach = mockMvc.perform(post("/api/v1/audits/{id}/evidence", auditId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"ISSN Portal screenshot",
                                 "description":"Registered title matches the site",
                                 "findingId":"%s","contentType":"image/png",
                                 "contentBase64":"%s"}
                                """.formatted(idByCode.get("RF-10"), payload)))
                .andExpect(status().isOk()).andReturn();
        String evidenceId = objectMapper.readTree(attach.getResponse().getContentAsString())
                .get("evidenceItemId").asText();
        mockMvc.perform(get("/api/v1/evidence/{id}/content", evidenceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .isEqualTo("fake-screenshot-bytes".getBytes(StandardCharsets.UTF_8)));

        // FR-REV-1: every action is in the history, attributed and timestamped.
        JsonNode decisions = getJson("/api/v1/audits/" + auditId + "/review/decisions");
        List<String> actions = decisions.findValuesAsText("action");
        assertThat(actions).contains("CONFIRM", "REJECT", "EDIT_SEVERITY", "EXCLUDE", "ATTACH_EVIDENCE");
        for (JsonNode decision : decisions) {
            assertThat(decision.get("decidedByEmail").asText()).isEqualTo("owner@crawl-test.example");
        }

        // The decision log is write-once even for a superuser (DB trigger).
        try (Connection superuser = POSTGRES.getPostgresDatabase().getConnection();
             PreparedStatement update = superuser.prepareStatement(
                     "update review_decision set reason = 'tampered'")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(update::executeUpdate)
                    .hasMessageContaining("immutable");
        }
    }

    @Test
    @Order(16)
    void reviewIsTenantScopedAndViewerReadOnly() throws Exception {
        // Cross-tenant: org B sees nothing of this audit's review surface.
        String tokenB = login("owner@crawl-b.example");
        mockMvc.perform(get("/api/v1/audits/{id}/review/queue", auditId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // VIEWERs read the queue but cannot decide (FR-AUTH role model).
        mockMvc.perform(post("/api/v1/organisations/current/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"viewer@crawl-test.example\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/accept-invitation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"%s","displayName":"Viewer"}
                                """.formatted(emails.lastTokenFor("viewer@crawl-test.example"), PASSWORD)))
                .andExpect(status().isNoContent());
        String viewerToken = login("viewer@crawl-test.example");
        mockMvc.perform(get("/api/v1/audits/{id}/review/queue", auditId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        Map<String, String> idByCode = findingIdsByCode();
        mockMvc.perform(post("/api/v1/findings/{id}/confirm", idByCode.get("RF-11"))
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }

    private Map<String, String> findingIdsByCode() throws Exception {
        JsonNode findings = getJson("/api/v1/audits/" + auditId + "/findings");
        Map<String, String> idByCode = new java.util.HashMap<>();
        for (JsonNode finding : findings) {
            idByCode.put(finding.get("code").asText(), finding.get("id").asText());
        }
        return idByCode;
    }

    @Test
    @Order(17)
    void reportDraftHasFixedStructureRoadmapAndPassesGuard() throws Exception {
        // FR-RPT-1: fixed section structure; CON-5: every factual sentence cited.
        MvcResult generated = mockMvc.perform(post("/api/v1/audits/{id}/reports", auditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.guardPassed").value(true))
                .andExpect(jsonPath("$.verdict").value("NOT_READY")) // G1 FAIL
                .andReturn();
        JsonNode report = objectMapper.readTree(generated.getResponse().getContentAsString());
        reportId = report.get("id").asText();

        List<String> sectionIds = new java.util.ArrayList<>();
        int factual = 0;
        int uncited = 0;
        for (JsonNode section : report.get("sections")) {
            sectionIds.add(section.get("id").asText());
            for (JsonNode sentence : section.get("sentences")) {
                assertThat(sentence.get("guard").asText()).isEqualTo("PASS");
                if ("FACTUAL".equals(sentence.get("kind").asText())) {
                    factual++;
                    if (sentence.get("findingIds").isEmpty() && sentence.get("evidenceItemIds").isEmpty()) {
                        uncited++;
                    }
                }
            }
        }
        assertThat(sectionIds).contains("verdict", "gateway", "csab-policy", "csab-content",
                "csab-standing", "csab-regularity", "csab-availability", "diversity", "findings",
                "methodology", "disclaimer");
        assertThat(sectionIds).doesNotContain("narrative"); // provider disabled (FR-RPT-2)
        assertThat(report.get("narrativePromptVersion").isNull()).isTrue();
        assertThat(factual).isGreaterThan(10);
        assertThat(uncited).isZero(); // CON-5: no unreferenced factual sentence exists

        // FR-RPT-6: roadmap actions from findings, gateway failures and weak scores.
        List<String> actionIds = new java.util.ArrayList<>();
        for (JsonNode action : report.get("roadmap")) {
            actionIds.add(action.get("id").asText());
        }
        assertThat(actionIds).contains("publish-review-policy",      // G1 FAIL
                "citation-integrity-statement",                       // RF-02 confirmed
                "board-internationalisation");                        // standing score 1
        // FR-REV-4: the excluded RF-10 sits in the annex, not the body.
        assertThat(report.get("exclusions").findValuesAsText("code")).contains("RF-10");
        assertThat(objectMapper.writeValueAsString(report.get("sections"))).doesNotContain("RF-10 ");

        // Audit stage tracks the report lifecycle: guard passed => GUARD.
        mockMvc.perform(get("/api/v1/audits/{id}", auditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("GUARD"));
    }

    @Test
    @Order(18)
    void sentenceEditingReleaseImmutabilityAndExports() throws Exception {
        // FR-RPT-4 edit path: change a sentence's text, then remove a structural one.
        mockMvc.perform(post("/api/v1/reports/{id}/sentences", reportId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sentenceId\":\"disclaimer-independence\",\"remove\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guardPassed").value(true));

        // FR-REV-4 + FR-RPT-5: release succeeds (gate clean since Order 14), hash-stamped.
        MvcResult released = mockMvc.perform(post("/api/v1/reports/{id}/release", reportId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andReturn();
        String hash = objectMapper.readTree(released.getResponse().getContentAsString())
                .get("contentHash").asText();
        assertThat(hash).hasSize(64);
        mockMvc.perform(get("/api/v1/audits/{id}", auditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.stage").value("RELEASE"));

        // Released reports are immutable: API-side and DB-side.
        mockMvc.perform(post("/api/v1/reports/{id}/release", reportId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/reports/{id}/sentences", reportId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sentenceId\":\"verdict-stats\",\"text\":\"tampered\"}"))
                .andExpect(status().isConflict());
        try (Connection superuser = POSTGRES.getPostgresDatabase().getConnection();
             PreparedStatement update = superuser.prepareStatement(
                     "update report set verdict = 'READY' where id = ?")) {
            update.setObject(1, UUID.fromString(reportId));
            org.assertj.core.api.Assertions.assertThatThrownBy(update::executeUpdate)
                    .hasMessageContaining("immutable");
        }

        // FR-RPT-5 exports: HTML with superscript citations + hash, DOCX zip, PDF.
        MvcResult html = mockMvc.perform(get("/api/v1/reports/{id}/export?format=html", reportId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        String htmlBody = html.getResponse().getContentAsString();
        assertThat(htmlBody).contains("sup class=\"cite\"").contains(hash)
                .doesNotContain("DRAFT — not released");
        MvcResult docx = mockMvc.perform(get("/api/v1/reports/{id}/export?format=docx", reportId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        byte[] docxBytes = docx.getResponse().getContentAsByteArray();
        assertThat(docxBytes[0]).isEqualTo((byte) 'P');
        assertThat(docxBytes[1]).isEqualTo((byte) 'K');
        MvcResult pdf = mockMvc.perform(get("/api/v1/reports/{id}/export?format=pdf", reportId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 8,
                StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
    }

    @Test
    @Order(19)
    void releaseIsBlockedWhileNeedsVerificationFindingsRemain() throws Exception {
        // The capped audit's own RF-10 indicator was never reviewed (FR-REV-4).
        MvcResult generated = mockMvc.perform(post("/api/v1/audits/{id}/reports", cappedAuditId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String cappedReportId = objectMapper.readTree(generated.getResponse().getContentAsString())
                .get("id").asText();
        mockMvc.perform(post("/api/v1/reports/{id}/release", cappedReportId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("needs-verification-open"));
    }

    @Test
    @Order(20)
    void deltaComparesTwoAuditsOfTheSameJournal() throws Exception {
        // FR-RPT-7: the full audit vs the page-capped audit of the same journal.
        JsonNode delta = getJson("/api/v1/audits/" + auditId + "/delta/" + cappedAuditId);
        assertThat(delta.get("scores").size()).isEqualTo(5);
        assertThat(delta.get("gateway").size()).isEqualTo(6);
        assertThat(delta.get("resolvedCodes").isArray()).isTrue();
        assertThat(delta.get("newCodes").isArray()).isTrue();
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
