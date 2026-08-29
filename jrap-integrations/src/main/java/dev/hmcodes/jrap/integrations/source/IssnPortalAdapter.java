package dev.hmcodes.jrap.integrations.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.integrations.cache.ApiRecordService;
import dev.hmcodes.jrap.integrations.cache.RecordedResponse;
import dev.hmcodes.jrap.integrations.dto.JournalSourceIdentity;
import dev.hmcodes.jrap.integrations.dto.SourceResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ISSN Portal adapter (FR-INT-4). The portal frequently blocks automated access; a
 * blocked or unparseable response degrades to UNAVAILABLE, and the analyst-entered
 * manual-evidence fallback (FR-INT-7) covers the gap. An optional per-source
 * User-Agent override ({@code jrap.integrations.issn-portal-user-agent}) lets an
 * operator try a browser profile against the portal's bot filter — a config decision,
 * left empty by default so JRAP identifies itself honestly everywhere else.
 */
@Component
public class IssnPortalAdapter {

    public static final String SOURCE = "ISSN_PORTAL";

    private final ApiRecordService apiRecords;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Map<String, String> requestHeaders;

    public IssnPortalAdapter(ApiRecordService apiRecords, ObjectMapper objectMapper,
                             @Value("${jrap.integrations.issn-portal-base-url:https://portal.issn.org}") String baseUrl,
                             @Value("${jrap.integrations.issn-portal-user-agent:}") String userAgentOverride) {
        this.apiRecords = apiRecords;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.requestHeaders = userAgentOverride == null || userAgentOverride.isBlank()
                ? Map.of()
                : Map.of("User-Agent", userAgentOverride.trim());
    }

    public SourceResult<JournalSourceIdentity> resolveJournalByIssn(String issn) {
        String key = "resource:" + issn;
        String url = baseUrl + "/resource/ISSN/" + issn + "?format=json";
        return apiRecords.getOrFetch(SOURCE, key, url, requestHeaders)
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
            String title = null;
            String issnL = null;
            for (JsonNode node : root.path("@graph")) {
                if (node.hasNonNull("mainTitle") && title == null) {
                    title = node.get("mainTitle").asText();
                }
                if (node.hasNonNull("name") && title == null && node.path("@id").asText("").contains("KeyTitle")) {
                    title = node.get("name").asText();
                }
                String id = node.path("@id").asText("");
                if (id.contains("resource/ISSN-L/")) {
                    issnL = id.substring(id.lastIndexOf('/') + 1);
                }
            }
            JournalSourceIdentity identity = new JournalSourceIdentity(
                    SOURCE, issn, title, null, null, null, null, issnL,
                    List.of(issn), null, Map.of());
            return SourceResult.ok(identity, response.apiRecordId(), response.retrievedAt(), response.fromCache());
        } catch (Exception e) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
    }
}
