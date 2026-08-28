package dev.hmcodes.jrap.reporting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-RPT-6 / §5.4: catalogue mapping, phases, tags, dedupe, ordering. */
class RoadmapGeneratorTest {

    private final RoadmapGenerator generator = new RoadmapGenerator(new ObjectMapper());

    private static Finding finding(String code) {
        return new Finding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "red-flag",
                code, Finding.Severity.MEDIUM, Finding.Status.CONFIRMED, code + " title",
                code + " description", "red-flags/1.1.0", Instant.EPOCH);
    }

    @Test
    void mapsFindingsGatewayAndScoresToPhasedActions() {
        Finding rf02 = finding("RF-02");
        Finding rf06 = finding("RF-06");
        Finding identity = finding("IDENTITY_ISSN_SWAP");
        List<RoadmapAction> roadmap = generator.generate(
                List.of(rf02, rf06, identity),
                Map.of("G1", "FAIL", "G2", "PASS_WITH_CAVEATS", "G4", "PASS", "G5", "UNCLEAR"),
                Map.of("standing", 1, "availability", 2, "content", 5));

        List<String> ids = roadmap.stream().map(RoadmapAction::id).toList();
        assertThat(ids).contains(
                "citation-integrity-statement",   // RF-02
                "integrity-panel",                // RF-06 (§5.4: integrity panel, COPE workflow)
                "identity-record-correction",     // IDENTITY_ prefix (§5.4: RF-09 -> record corrections)
                "publish-review-policy",          // G1 FAIL
                "regularise-schedule",            // G2 caveats
                "reference-deposit",              // standing <= 2 (§5.4)
                "board-internationalisation",
                "thematic-issues",
                "preservation-enrolment");        // availability <= 2 (§5.4: PKP PN/CLOCKSS)
        assertThat(ids).doesNotContain("english-metadata", "affiliation-completeness");

        // Ordering: 0-3 month must-fix actions first; strengthens later.
        assertThat(roadmap.get(0).phase()).isEqualTo("P0_3");
        assertThat(roadmap.get(0).tag()).isEqualTo("MUST_FIX");
        int p03Last = lastIndexOfPhase(roadmap, "P0_3");
        int p36First = firstIndexOfPhase(roadmap, "P3_6");
        assertThat(p03Last).isLessThan(p36First);

        // Traceability: the RF-02 action carries the triggering finding id.
        RoadmapAction citation = roadmap.stream()
                .filter(a -> a.id().equals("citation-integrity-statement")).findFirst().orElseThrow();
        assertThat(citation.findingIds()).containsExactly(rf02.getId());
        assertThat(citation.completionCriterion()).isNotBlank();
    }

    @Test
    void dedupesActionsAcrossMultipleTriggeringFindings() {
        Finding a = finding("IDENTITY_ISSN_SWAP");
        Finding b = finding("IDENTITY_PUBLISHER_MISMATCH");
        List<RoadmapAction> roadmap = generator.generate(List.of(a, b), Map.of(), Map.of());
        List<RoadmapAction> corrections = roadmap.stream()
                .filter(x -> x.id().equals("identity-record-correction")).toList();
        assertThat(corrections).hasSize(1);
        assertThat(corrections.get(0).findingIds())
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void unknownCodesProduceNoActionsAndNoFailure() {
        List<RoadmapAction> roadmap = generator.generate(
                List.of(finding("RF-99")), Map.of("G1", "PASS"), Map.of("standing", 5));
        assertThat(roadmap).isEmpty();
    }

    private static int firstIndexOfPhase(List<RoadmapAction> roadmap, String phase) {
        for (int i = 0; i < roadmap.size(); i++) {
            if (roadmap.get(i).phase().equals(phase)) {
                return i;
            }
        }
        return roadmap.size();
    }

    private static int lastIndexOfPhase(List<RoadmapAction> roadmap, String phase) {
        int last = -1;
        for (int i = 0; i < roadmap.size(); i++) {
            if (roadmap.get(i).phase().equals(phase)) {
                last = i;
            }
        }
        return last;
    }
}
