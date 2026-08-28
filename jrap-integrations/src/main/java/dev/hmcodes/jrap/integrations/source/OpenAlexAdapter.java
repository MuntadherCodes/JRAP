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

/**
 * OpenAlex source adapter (FR-INT-1). Uses the polite pool via the mailto in the
 * User-Agent and a mailto query parameter (CON-3).
 */
@Component
public class OpenAlexAdapter {

    public static final String SOURCE = "OPENALEX";

    private final ApiRecordService apiRecords;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String contactEmail;

    public OpenAlexAdapter(ApiRecordService apiRecords, ObjectMapper objectMapper,
                           @Value("${jrap.integrations.openalex-base-url:https://api.openalex.org}") String baseUrl,
                           @Value("${jrap.contact-email}") String contactEmail) {
        this.apiRecords = apiRecords;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.contactEmail = contactEmail;
    }

    public SourceResult<JournalSourceIdentity> resolveJournalByIssn(String issn) {
        String key = "sources:issn:" + issn;
        String url = baseUrl + "/sources/issn:" + issn + "?mailto=" + contactEmail;
        return apiRecords.getOrFetch(SOURCE, key, url, Map.of())
                .map(this::toResult)
                .orElseGet(() -> SourceResult.unavailable(null, null));
    }

    /** Canonical author candidate for misattribution screening (FR-ANL-8). */
    public record AuthorRecord(String displayName, String institution, long worksCount) {}

    public SourceResult<AuthorRecord> searchAuthor(String name) {
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        String key = "authors:search:" + name.toLowerCase(java.util.Locale.ROOT);
        String url = baseUrl + "/authors?search=" + encoded + "&per-page=1&mailto=" + contactEmail;
        return apiRecords.getOrFetch(SOURCE, key, url, Map.of())
                .map(this::toAuthorResult)
                .orElseGet(() -> SourceResult.unavailable(null, null));
    }

    private SourceResult<AuthorRecord> toAuthorResult(RecordedResponse response) {
        if (response.statusCode() != 200) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
        try {
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (!results.isArray() || results.isEmpty()) {
                return SourceResult.notFound(response.apiRecordId(), response.retrievedAt(),
                        response.fromCache());
            }
            JsonNode hit = results.get(0);
            String institution = null;
            JsonNode institutions = hit.path("last_known_institutions");
            if (institutions.isArray() && !institutions.isEmpty()) {
                institution = textOrNull(institutions.get(0), "display_name");
            }
            return SourceResult.ok(new AuthorRecord(textOrNull(hit, "display_name"), institution,
                            hit.path("works_count").asLong()),
                    response.apiRecordId(), response.retrievedAt(), response.fromCache());
        } catch (Exception e) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
    }

    /** Minimal per-DOI work record for reconciliation (FR-EXT-5). */
    public record WorkRecord(String title, int authorCount, Integer publishedYear) {}

    public SourceResult<WorkRecord> workByDoi(String doi) {
        String key = "works:doi:" + doi;
        String url = baseUrl + "/works/https://doi.org/" + doi + "?mailto=" + contactEmail;
        return apiRecords.getOrFetch(SOURCE, key, url, Map.of())
                .map(this::toWorkResult)
                .orElseGet(() -> SourceResult.unavailable(null, null));
    }

    private SourceResult<WorkRecord> toWorkResult(RecordedResponse response) {
        if (response.statusCode() == 404) {
            return SourceResult.notFound(response.apiRecordId(), response.retrievedAt(), response.fromCache());
        }
        if (response.statusCode() != 200) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode authorships = root.path("authorships");
            return SourceResult.ok(new WorkRecord(
                            textOrNull(root, "display_name"),
                            authorships.isArray() ? authorships.size() : 0,
                            root.hasNonNull("publication_year") ? root.get("publication_year").asInt() : null),
                    response.apiRecordId(), response.retrievedAt(), response.fromCache());
        } catch (Exception e) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
    }

    private SourceResult<JournalSourceIdentity> toResult(RecordedResponse response) {
        if (response.statusCode() == 404) {
            return SourceResult.notFound(response.apiRecordId(), response.retrievedAt(), response.fromCache());
        }
        if (response.statusCode() != 200) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            List<String> issns = new ArrayList<>();
            root.path("issn").forEach(node -> issns.add(node.asText()));
            Map<String, Object> extra = new LinkedHashMap<>();
            if (root.hasNonNull("works_count")) {
                extra.put("worksCount", root.get("works_count").asLong());
            }
            if (root.hasNonNull("cited_by_count")) {
                extra.put("citedByCount", root.get("cited_by_count").asLong());
            }
            JsonNode stats = root.path("summary_stats");
            if (stats.hasNonNull("2yr_mean_citedness")) {
                extra.put("twoYearMeanCitedness", stats.get("2yr_mean_citedness").asDouble());
            }
            if (stats.hasNonNull("h_index")) {
                extra.put("hIndex", stats.get("h_index").asInt());
            }
            JournalSourceIdentity identity = new JournalSourceIdentity(
                    SOURCE,
                    textOrNull(root, "id"),
                    textOrNull(root, "display_name"),
                    textOrNull(root, "host_organization_name"),
                    textOrNull(root, "country_code"),
                    null,
                    null,
                    textOrNull(root, "issn_l"),
                    issns,
                    textOrNull(root, "homepage_url"),
                    extra);
            return SourceResult.ok(identity, response.apiRecordId(), response.retrievedAt(), response.fromCache());
        } catch (Exception e) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
    }

    static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
