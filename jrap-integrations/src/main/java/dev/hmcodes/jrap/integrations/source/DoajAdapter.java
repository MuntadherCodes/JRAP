package dev.hmcodes.jrap.integrations.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.integrations.cache.ApiRecordService;
import dev.hmcodes.jrap.integrations.cache.RecordedResponse;
import dev.hmcodes.jrap.integrations.dto.JournalSourceIdentity;
import dev.hmcodes.jrap.integrations.dto.SourceResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.hmcodes.jrap.integrations.source.OpenAlexAdapter.textOrNull;

/**
 * DOAJ source adapter (FR-INT-3). Field-by-field comparison against the journal's own
 * pages happens in the consistency checker; this adapter reports what DOAJ states.
 */
@Component
public class DoajAdapter {

    public static final String SOURCE = "DOAJ";

    private final ApiRecordService apiRecords;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public DoajAdapter(ApiRecordService apiRecords, ObjectMapper objectMapper,
                       @Value("${jrap.integrations.doaj-base-url:https://doaj.org}") String baseUrl) {
        this.apiRecords = apiRecords;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public SourceResult<JournalSourceIdentity> resolveJournalByIssn(String issn) {
        String key = "search:journals:issn:" + issn;
        String url = baseUrl + "/api/search/journals/issn%3A" + issn;
        return apiRecords.getOrFetch(SOURCE, key, url, Map.of())
                .map(response -> toResult(response, issn))
                .orElseGet(() -> SourceResult.unavailable(null, null));
    }

    private SourceResult<JournalSourceIdentity> toResult(RecordedResponse response, String issn) {
        if (response.statusCode() == 404) {
            return SourceResult.notFound(response.apiRecordId(), response.retrievedAt(), response.fromCache());
        }
        if (response.statusCode() != 200) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                // DOAJ's search endpoint answers 200 with zero results for unknown ISSNs.
                return SourceResult.notFound(response.apiRecordId(), response.retrievedAt(), response.fromCache());
            }
            JsonNode hit = results.get(0);
            JsonNode bibjson = hit.path("bibjson");
            String pissn = textOrNull(bibjson, "pissn");
            String eissn = textOrNull(bibjson, "eissn");
            List<String> issns = new ArrayList<>();
            if (pissn != null) {
                issns.add(pissn);
            }
            if (eissn != null) {
                issns.add(eissn);
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            JsonNode apc = bibjson.path("apc");
            if (apc.has("has_apc")) {
                extra.put("hasApc", apc.get("has_apc").asBoolean());
            }
            JsonNode preservation = bibjson.path("preservation");
            if (preservation.has("has_preservation")) {
                extra.put("hasPreservation", preservation.get("has_preservation").asBoolean());
            }
            JournalSourceIdentity identity = new JournalSourceIdentity(
                    SOURCE,
                    textOrNull(hit, "id"),
                    textOrNull(bibjson, "title"),
                    textOrNull(bibjson.path("publisher"), "name"),
                    textOrNull(bibjson.path("publisher"), "country"),
                    pissn,
                    eissn,
                    null,
                    issns,
                    textOrNull(bibjson.path("ref"), "journal"),
                    extra);
            return SourceResult.ok(identity, response.apiRecordId(), response.retrievedAt(), response.fromCache());
        } catch (Exception e) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
    }
}
