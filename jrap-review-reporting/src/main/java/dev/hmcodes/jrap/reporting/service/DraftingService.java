package dev.hmcodes.jrap.reporting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.aigateway.LlmGateway;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;
import dev.hmcodes.jrap.reporting.model.ReportContent.Sentence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-RPT-2: the narrative section is drafted via the AI gateway from the CONFIRMED
 * findings JSON and nothing else; the prompt forbids new facts, and the FR-RPT-4
 * {@link SentenceGuard} then verifies every drafted sentence's citations machine-side —
 * the drafter is untrusted by design. With the provider disabled (beta default) the
 * narrative section is simply absent and the deterministic sections carry the report.
 */
@Service
public class DraftingService {

    public static final String PROMPT_NAME = "report-drafting";

    private static final Logger log = LoggerFactory.getLogger(DraftingService.class);

    public record Draft(Section section, String promptVersion) {}

    private final LlmGateway llm;
    private final ObjectMapper objectMapper;

    public DraftingService(LlmGateway llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    /** @return the narrative section, or null when the provider is off or nothing is confirmed. */
    public Draft draft(Audit audit, List<Finding> confirmedFindings,
                       Map<UUID, List<UUID>> evidenceByFinding) {
        if (!llm.isEnabled() || confirmedFindings.isEmpty()) {
            return null;
        }
        String findingsJson;
        try {
            List<Map<String, Object>> input = confirmedFindings.stream()
                    .<Map<String, Object>>map(f -> Map.of(
                            "id", f.getId().toString(),
                            "code", f.getCode(),
                            "severity", f.getSeverity().name(),
                            "title", f.getTitle(),
                            "description", f.getDescription()))
                    .toList();
            findingsJson = objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return null;
        }
        LlmGateway.GatewayResult result = llm.complete(PROMPT_NAME,
                Map.of("findings_json", findingsJson), audit.getId(), List.of(), 3000);
        if (!result.ok()) {
            log.info("Narrative drafting unavailable for audit {}: {}", audit.getId(), result.error());
            return null;
        }
        List<Sentence> sentences = parse(result.text());
        if (sentences.isEmpty()) {
            return null;
        }
        return new Draft(new Section(SentenceGuard.NARRATIVE_SECTION_ID, "Narrative summary",
                attachEvidence(sentences, evidenceByFinding)), result.promptVersion());
    }

    /**
     * Lenient parse of the drafter's JSON. Unparseable finding ids are preserved as
     * random UUIDs that cannot match the allowed set — the guard, not the parser, is
     * the enforcement point (FR-RPT-4).
     */
    private List<Sentence> parse(String text) {
        List<Sentence> sentences = new ArrayList<>();
        try {
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return sentences;
            }
            JsonNode array = objectMapper.readTree(text.substring(start, end + 1));
            int index = 0;
            for (JsonNode node : array) {
                String sentenceText = node.path("text").asText("");
                List<UUID> findingIds = new ArrayList<>();
                for (JsonNode idNode : node.path("findingIds")) {
                    try {
                        findingIds.add(UUID.fromString(idNode.asText()));
                    } catch (IllegalArgumentException e) {
                        findingIds.add(UUID.randomUUID()); // guaranteed to fail the guard
                    }
                }
                sentences.add(Sentence.factual("narrative-" + index++, sentenceText, findingIds, List.of()));
            }
        } catch (Exception e) {
            log.info("Unparseable narrative draft dropped: {}", e.getMessage());
        }
        return sentences;
    }

    /** Renderable citations: each sentence inherits the evidence of the findings it cites. */
    private List<Sentence> attachEvidence(List<Sentence> sentences,
                                          Map<UUID, List<UUID>> evidenceByFinding) {
        List<Sentence> out = new ArrayList<>();
        for (Sentence sentence : sentences) {
            List<UUID> evidence = sentence.findingIds().stream()
                    .flatMap(id -> evidenceByFinding.getOrDefault(id, List.of()).stream())
                    .distinct()
                    .toList();
            out.add(Sentence.factual(sentence.id(), sentence.text(), sentence.findingIds(), evidence));
        }
        return out;
    }
}
