package dev.hmcodes.jrap.reporting.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The structured report content (FR-RPT-1, CON-5). A report is sections of sentences;
 * every FACTUAL sentence carries its finding and evidence citations, which is what makes
 * the renderer technically unable to emit an unreferenced claim and what the FR-RPT-4
 * guard verifies sentence by sentence. STRUCTURAL sentences are fixed scaffolding
 * (headings, methodology, disclaimer) that assert nothing about the journal.
 */
public final class ReportContent {

    public enum Kind { FACTUAL, STRUCTURAL }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Sentence(String id, Kind kind, String text, List<UUID> findingIds,
                           List<UUID> evidenceItemIds, String guard) {

        public static Sentence structural(String id, String text) {
            return new Sentence(id, Kind.STRUCTURAL, text, List.of(), List.of(), null);
        }

        public static Sentence factual(String id, String text, List<UUID> findingIds,
                                       List<UUID> evidenceItemIds) {
            return new Sentence(id, Kind.FACTUAL, text,
                    findingIds == null ? List.of() : findingIds,
                    evidenceItemIds == null ? List.of() : evidenceItemIds, null);
        }

        public Sentence withGuard(String outcome) {
            return new Sentence(id, kind, text, findingIds, evidenceItemIds, outcome);
        }

        public Sentence withText(String newText) {
            return new Sentence(id, kind, newText, findingIds, evidenceItemIds, guard);
        }
    }

    public record Section(String id, String title, List<Sentence> sentences) {}

    /** One remediation action in the FR-RPT-6 roadmap (§5.4). */
    public record RoadmapAction(String id, String title, String description, String phase,
                                String tag, String completionCriterion, List<UUID> findingIds) {}

    /** FR-REV-4: excluded needs-verification findings, listed in the report annex. */
    public record Exclusion(UUID findingId, String code, String title, String reason) {}

    private ReportContent() {}

    public static List<Section> copyWithSentence(List<Section> sections, String sentenceId,
                                                 Sentence replacement) {
        List<Section> out = new ArrayList<>();
        for (Section section : sections) {
            List<Sentence> sentences = new ArrayList<>();
            boolean changed = false;
            for (Sentence sentence : section.sentences()) {
                if (sentence.id().equals(sentenceId)) {
                    changed = true;
                    if (replacement != null) {
                        sentences.add(replacement);
                    }
                } else {
                    sentences.add(sentence);
                }
            }
            out.add(changed ? new Section(section.id(), section.title(), sentences) : section);
        }
        return out;
    }
}
