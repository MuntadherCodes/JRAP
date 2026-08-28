package dev.hmcodes.jrap.reporting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-RPT-6 (§5.4): maps every open finding, failed gateway check and weak score to
 * remediation actions from the versioned action catalogue, phased 0-3 / 3-6 / 6-12
 * months and tagged must-fix vs strengthens, each with a measurable completion
 * criterion. Deterministic: same inputs, same roadmap.
 */
@Service
public class RoadmapGenerator {

    public static final String CATALOGUE_VERSION = "1.0";

    private final JsonNode catalogue;

    public RoadmapGenerator(ObjectMapper objectMapper) {
        try (InputStream in = RoadmapGenerator.class.getResourceAsStream(
                "/roadmap/catalogue-v" + CATALOGUE_VERSION + ".json")) {
            if (in == null) {
                throw new IllegalStateException("Missing roadmap catalogue v" + CATALOGUE_VERSION);
            }
            this.catalogue = objectMapper.readTree(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unreadable roadmap catalogue", e);
        }
    }

    /**
     * @param openFindings  findings entering the report (confirmed or automated; not
     *                      rejected, not excluded)
     * @param gatewayOutcomes code -> outcome (PASS | PASS_WITH_CAVEATS | FAIL | UNCLEAR)
     * @param scores          category -> 0..5
     */
    public List<RoadmapAction> generate(List<Finding> openFindings,
                                        Map<String, String> gatewayOutcomes,
                                        Map<String, Integer> scores) {
        // action id -> accumulated action (dedupe across triggers, merge finding ids)
        Map<String, RoadmapAction> actions = new LinkedHashMap<>();

        for (Finding finding : openFindings) {
            for (JsonNode node : actionsForCode(finding.getCode())) {
                merge(actions, node, finding.getId());
            }
        }
        JsonNode gateway = catalogue.path("gateway");
        for (Map.Entry<String, String> entry : gatewayOutcomes.entrySet()) {
            if (("FAIL".equals(entry.getValue()) || "PASS_WITH_CAVEATS".equals(entry.getValue()))
                    && gateway.has(entry.getKey())) {
                merge(actions, gateway.get(entry.getKey()), null);
            }
        }
        JsonNode scoreActions = catalogue.path("scores");
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() <= 2 && scoreActions.has(entry.getKey())) {
                for (JsonNode node : scoreActions.get(entry.getKey())) {
                    merge(actions, node, null);
                }
            }
        }

        List<RoadmapAction> result = new ArrayList<>(actions.values());
        // Stable order: phase, then must-fix first, then id.
        result.sort(java.util.Comparator
                .comparingInt((RoadmapAction a) -> phaseRank(a.phase()))
                .thenComparing(a -> "MUST_FIX".equals(a.tag()) ? 0 : 1)
                .thenComparing(RoadmapAction::id));
        return result;
    }

    private List<JsonNode> actionsForCode(String code) {
        JsonNode byCode = catalogue.path("byCode");
        if (byCode.has(code)) {
            List<JsonNode> out = new ArrayList<>();
            byCode.get(code).forEach(out::add);
            return out;
        }
        JsonNode byPrefix = catalogue.path("byCodePrefix");
        List<JsonNode> out = new ArrayList<>();
        byPrefix.fields().forEachRemaining(entry -> {
            if (code.startsWith(entry.getKey())) {
                entry.getValue().forEach(out::add);
            }
        });
        return out;
    }

    private static void merge(Map<String, RoadmapAction> actions, JsonNode node, UUID findingId) {
        String id = node.get("id").asText();
        RoadmapAction existing = actions.get(id);
        List<UUID> findingIds = new ArrayList<>(existing == null ? List.of() : existing.findingIds());
        if (findingId != null && !findingIds.contains(findingId)) {
            findingIds.add(findingId);
        }
        actions.put(id, new RoadmapAction(id,
                node.get("title").asText(),
                node.get("description").asText(),
                node.get("phase").asText(),
                node.get("tag").asText(),
                node.get("completion").asText(),
                findingIds));
    }

    private static int phaseRank(String phase) {
        return switch (phase) {
            case "P0_3" -> 0;
            case "P3_6" -> 1;
            default -> 2;
        };
    }
}
