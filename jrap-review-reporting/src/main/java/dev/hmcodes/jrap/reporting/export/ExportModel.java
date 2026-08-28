package dev.hmcodes.jrap.reporting.export;

import dev.hmcodes.jrap.reporting.model.ReportContent.Exclusion;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything an export renderer needs, resolved once (FR-RPT-3/5): the structured
 * sections, per-sentence citation numbers into the evidence annex, the roadmap, the
 * FR-REV-4 exclusions annex, and the release stamp. Renderers are pure functions of
 * this model — they cannot reach the database, so they cannot cite anything that is
 * not already in the annex (CON-5).
 */
public record ExportModel(
        String journalTitle,
        String organisationName,
        String verdict,
        int version,
        boolean released,
        String contentHash,
        Instant generatedAt,
        Instant releasedAt,
        String rubricVersion,
        List<Section> sections,
        List<RoadmapAction> roadmap,
        List<Exclusion> exclusions,
        /** sentence id -> annex numbers of its citations, in citation order */
        Map<String, List<Integer>> citationNumbers,
        /** the evidence annex, numbered 1..n */
        List<CitedEvidence> evidence) {

    public record CitedEvidence(int number, UUID id, String type, String source,
                                String excerpt, Instant retrievedAt) {}

    public String watermark() {
        return released ? null : "DRAFT — not released; not for distribution";
    }

    public String stamp() {
        return released
                ? "Released " + releasedAt + " · SHA-256 " + contentHash
                : "Draft v" + version + " generated " + generatedAt;
    }
}
