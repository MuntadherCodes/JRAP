package dev.hmcodes.jrap.integrations.source;

import dev.hmcodes.jrap.common.util.Issn;
import dev.hmcodes.jrap.integrations.cache.ApiRecordService;
import dev.hmcodes.jrap.integrations.cache.RecordedResponse;
import dev.hmcodes.jrap.integrations.dto.SourceResult;
import dev.hmcodes.jrap.integrations.http.RobotsLite;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-page homepage probe used at registration time (FR-JRN-1): finds ISSNs stated
 * on the page and detects the platform (OJS version) from the generator meta tag.
 * Robots-gated (CON-2); the full site crawl is Phase 3. A robots-disallowed or
 * unreachable homepage degrades to UNAVAILABLE — never an error.
 */
@Component
public class SiteProbe {

    public static final String SOURCE = "SITE";

    public record SiteIdentity(String url, String pageTitle, List<String> issns, String platform) {}

    private static final Pattern ISSN_PATTERN = Pattern.compile("\\b(\\d{4})-(\\d{3}[\\dXx])\\b");
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern GENERATOR_PATTERN = Pattern.compile(
            "<meta[^>]+name=[\"']generator[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERATOR_PATTERN_REVERSED = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']generator[\"']", Pattern.CASE_INSENSITIVE);

    private final ApiRecordService apiRecords;
    private final RobotsLite robots;

    public SiteProbe(ApiRecordService apiRecords, RobotsLite robots) {
        this.apiRecords = apiRecords;
        this.robots = robots;
    }

    public SourceResult<SiteIdentity> probe(String url) {
        if (!robots.isAllowed(url)) {
            return SourceResult.unavailable(null, null);
        }
        return apiRecords.getOrFetch(SOURCE, "homepage:" + url, url, Map.of())
                .map(response -> toResult(response, url))
                .orElseGet(() -> SourceResult.unavailable(null, null));
    }

    private SourceResult<SiteIdentity> toResult(RecordedResponse response, String url) {
        if (response.statusCode() == 404) {
            return SourceResult.notFound(response.apiRecordId(), response.retrievedAt(), response.fromCache());
        }
        if (response.statusCode() != 200 || response.body() == null) {
            return SourceResult.unavailable(response.apiRecordId(), response.retrievedAt());
        }
        String html = response.body();
        Set<String> issns = new LinkedHashSet<>();
        Matcher issnMatcher = ISSN_PATTERN.matcher(html);
        while (issnMatcher.find()) {
            // Checksum-validate so year ranges like "2024-2025" are never mistaken for ISSNs.
            String candidate = Issn.normalise(issnMatcher.group(1) + "-" + issnMatcher.group(2));
            if (candidate != null) {
                issns.add(candidate);
            }
        }
        String pageTitle = null;
        Matcher titleMatcher = TITLE_PATTERN.matcher(html);
        if (titleMatcher.find()) {
            pageTitle = titleMatcher.group(1).trim().replaceAll("\\s+", " ");
        }
        String platform = null;
        Matcher generatorMatcher = GENERATOR_PATTERN.matcher(html);
        if (generatorMatcher.find()) {
            platform = generatorMatcher.group(1).trim();
        } else {
            Matcher reversed = GENERATOR_PATTERN_REVERSED.matcher(html);
            if (reversed.find()) {
                platform = reversed.group(1).trim();
            }
        }
        SiteIdentity identity = new SiteIdentity(url, pageTitle, List.copyOf(issns), platform);
        return SourceResult.ok(identity, response.apiRecordId(), response.retrievedAt(), response.fromCache());
    }
}
