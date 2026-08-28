package dev.hmcodes.jrap.reporting.service;

import dev.hmcodes.jrap.reporting.model.ReportContent;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;
import dev.hmcodes.jrap.reporting.model.ReportContent.Sentence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The FR-RPT-4 post-generation guard (NFR-AI-2, AC-3): machine-side verification that
 * every factual sentence in a draft maps to known finding/evidence ids. Sentences that
 * fail are marked and block release until edited. The narrative section — the only
 * LLM-written part (FR-RPT-2) — is held to the strictest rule: factual sentences only,
 * each citing at least one CONFIRMED finding, and nothing else.
 *
 * <p>Pure logic, no Spring: directly unit-testable against the injection suite.</p>
 */
public final class SentenceGuard {

    public static final String NARRATIVE_SECTION_ID = "narrative";

    public record Failure(String sentenceId, String reason) {}

    public record Result(boolean passed, int checked, List<Failure> failures) {}

    /** The id sets a draft is allowed to cite. */
    public record Context(Set<UUID> reportableFindingIds, Set<UUID> confirmedFindingIds,
                          Set<UUID> journalEvidenceIds) {}

    private SentenceGuard() {}

    public static Result check(List<Section> sections, Context context) {
        List<Failure> failures = new ArrayList<>();
        int checked = 0;
        for (Section section : sections) {
            boolean narrative = NARRATIVE_SECTION_ID.equals(section.id());
            for (Sentence sentence : section.sentences()) {
                checked++;
                String reason = narrative
                        ? checkNarrative(sentence, context)
                        : checkDeterministic(sentence, context);
                if (reason != null) {
                    failures.add(new Failure(sentence.id(), reason));
                }
            }
        }
        return new Result(failures.isEmpty(), checked, failures);
    }

    /** FR-RPT-2: LLM sentences must be factual and cite only confirmed findings. */
    private static String checkNarrative(Sentence sentence, Context context) {
        if (sentence.kind() != ReportContent.Kind.FACTUAL) {
            return "narrative sentences must be FACTUAL — structural scaffolding is not accepted from the drafter";
        }
        if (sentence.text() == null || sentence.text().isBlank()) {
            return "empty sentence";
        }
        if (sentence.findingIds() == null || sentence.findingIds().isEmpty()) {
            return "no finding citation — every narrative sentence must map to a finding (FR-RPT-4)";
        }
        for (UUID findingId : sentence.findingIds()) {
            if (findingId == null || !context.confirmedFindingIds().contains(findingId)) {
                return "cites a finding that is not a confirmed finding of this audit: " + findingId;
            }
        }
        for (UUID evidenceId : nullSafe(sentence.evidenceItemIds())) {
            if (evidenceId == null || !context.journalEvidenceIds().contains(evidenceId)) {
                return "cites unknown evidence: " + evidenceId;
            }
        }
        return null;
    }

    /** Deterministic sections: factual sentences carry at least one valid citation. */
    private static String checkDeterministic(Sentence sentence, Context context) {
        if (sentence.text() == null || sentence.text().isBlank()) {
            return "empty sentence";
        }
        if (sentence.kind() == ReportContent.Kind.STRUCTURAL) {
            return null; // fixed scaffolding — asserts nothing about the journal
        }
        boolean cited = false;
        for (UUID findingId : nullSafe(sentence.findingIds())) {
            if (findingId == null || !context.reportableFindingIds().contains(findingId)) {
                return "cites a finding outside this report's set: " + findingId;
            }
            cited = true;
        }
        for (UUID evidenceId : nullSafe(sentence.evidenceItemIds())) {
            if (evidenceId == null || !context.journalEvidenceIds().contains(evidenceId)) {
                return "cites unknown evidence: " + evidenceId;
            }
            cited = true;
        }
        if (!cited) {
            return "factual sentence with no citation (CON-5)";
        }
        return null;
    }

    private static List<UUID> nullSafe(List<UUID> list) {
        return list == null ? List.of() : list;
    }
}
