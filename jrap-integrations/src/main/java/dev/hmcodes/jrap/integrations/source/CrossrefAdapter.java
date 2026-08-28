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

/** Crossref source adapter (FR-INT-2) with etiquette headers (CON-3). */
@Component
public class CrossrefAdapter {

    public static final String SOURCE = "CROSSREF";

    private final ApiRecordService apiRecords;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String contactEmail;

    public CrossrefAdapter(ApiRecordService apiRecords, ObjectMapper objectMapper,
                           @Value("${jrap.integrations.crossref-base-url:https://api.crossref.org}") String baseUrl,
                           @Value("${jrap.contact-email}") String contactEmail) {
        this.apiRecords = apiRecords;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.contactEmail = contactEmail;
    }

    public SourceResult<JournalSourceIdentity> resolveJournalByIssn(String issn) {
        String key = "journals:" + issn;
        String url = baseUrl + "/journals/" + issn + "?mailto=" + contactEmail;
        return apiRecords.getOrFetch(SOURCE, key, url, Map.of())
                .map(response -> toResult(response, issn))
                .orElseGet(() -> SourceResult.unavailable(null, null));
    }

    /** Minimal per-DOI work record for reconciliation (FR-EXT-5). */
    public record WorkRecord(String title, int authorCount, String firstAuthorFamily, Integer publishedYear) {}

    public SourceResult<WorkRecord> workByDoi(String doi) {
        String key = "works:" + doi;
        String url = baseUrl + "/works/" + doi + "?mailto=" + contactEmail;
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
            JsonNode message = objectMapper.readTree(response.body()).path("message");
            String title = null;
            JsonNode titles = message.path("title");
            if (titles.isArray() && !titles.isEmpty()) {
                title = titles.get(0).asText();
            }
            JsonNode authorsNode = message.path("author");
            int authorCount = authorsNode.isArray() ? authorsNode.size() : 0;
            String firstFamily = null;
            if (authorsNode.isArray() && !authorsNode.isEmpty()) {
                firstFamily = textOrNull(authorsNode.get(0), "family");
            }
            Integer year = null;
            JsonNode dateParts = message.path("issued").path("date-parts");
            if (dateParts.isArray() && !dateParts.isEmpty() && dateParts.get(0).isArray()
                    && !dateParts.get(0).isEmpty()) {
                year = dateParts.get(0).get(0).asInt();
            }
            return SourceResult.ok(new WorkRecord(title, authorCount, firstFamily, year),
                    response.apiRecordId(), response.retrievedAt(), response.fromCache());
        } catch (Exception e) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
    }

    private SourceResult<JournalSourceIdentity> toResult(RecordedResponse response, String issn) {
        if (response.statusCode() == 404) {
            return SourceResult.notFound(response.apiRecordId(), response.retrievedAt(), response.fromCache());
        }
        if (response.statusCode() != 200) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
        try {
            JsonNode message = objectMapper.readTree(response.body()).path("message");
            List<String> issns = new ArrayList<>();
            message.path("ISSN").forEach(node -> issns.add(node.asText()));
            String issnPrint = null;
            String issnOnline = null;
            for (JsonNode typed : message.path("issn-type")) {
                String type = typed.path("type").asText();
                String value = typed.path("value").asText(null);
                if ("print".equals(type)) {
                    issnPrint = value;
                } else if ("electronic".equals(type)) {
                    issnOnline = value;
                }
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            JsonNode counts = message.path("counts");
            if (counts.hasNonNull("total-dois")) {
                extra.put("totalDois", counts.get("total-dois").asLong());
            }
            JsonNode byYear = message.path("breakdowns").path("dois-by-issued-year");
            if (byYear.isArray() && !byYear.isEmpty()) {
                Map<String, Long> doisByYear = new LinkedHashMap<>();
                byYear.forEach(pair -> {
                    if (pair.isArray() && pair.size() == 2) {
                        doisByYear.put(pair.get(0).asText(), pair.get(1).asLong());
                    }
                });
                extra.put("doisByYear", doisByYear);
            }
            JournalSourceIdentity identity = new JournalSourceIdentity(
                    SOURCE,
                    issn,
                    textOrNull(message, "title"),
                    textOrNull(message, "publisher"),
                    null,
                    issnPrint,
                    issnOnline,
                    null,
                    issns,
                    null,
                    extra);
            return SourceResult.ok(identity, response.apiRecordId(), response.retrievedAt(), response.fromCache());
        } catch (Exception e) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
    }
}
