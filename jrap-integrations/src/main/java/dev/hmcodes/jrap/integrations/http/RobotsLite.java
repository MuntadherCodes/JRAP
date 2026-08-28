package dev.hmcodes.jrap.integrations.http;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal robots.txt gate for the Phase-2 homepage probe (CON-2). The full politeness
 * engine (crawl-delay, sitemaps, meta-robots) arrives with the crawler in Phase 3.
 * A missing robots.txt (404) allows fetching; an unreachable or erroring robots.txt
 * fails CLOSED — when in doubt, JRAP does not fetch.
 */
@Component
public class RobotsLite {

    private record CachedRules(List<String> disallows, Instant fetchedAt) {}

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final PoliteHttpFetcher fetcher;
    private final Map<String, CachedRules> cache = new ConcurrentHashMap<>();

    public RobotsLite(PoliteHttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public boolean isAllowed(String url) {
        URI uri = URI.create(url);
        String hostKey = uri.getScheme() + "://" + uri.getAuthority();
        CachedRules rules = cache.get(hostKey);
        if (rules == null || rules.fetchedAt().isBefore(Instant.now().minus(CACHE_TTL))) {
            rules = fetchRules(hostKey); // outside any map lock; a rare duplicate fetch is fine
            if (rules != null) {
                cache.put(hostKey, rules);
            } else {
                cache.remove(hostKey);
            }
        }
        if (rules == null) {
            return false; // fail closed
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return rules.disallows().stream().noneMatch(path::startsWith);
    }

    private CachedRules fetchRules(String hostKey) {
        try {
            FetchResult result = fetcher.get(hostKey + "/robots.txt", Map.of());
            if (result.statusCode() == 404) {
                return new CachedRules(List.of(), Instant.now()); // no robots.txt: allowed
            }
            if (!result.ok()) {
                return null; // ambiguous: fail closed
            }
            return new CachedRules(parseDisallows(result.body()), Instant.now());
        } catch (FetchException e) {
            return null; // unreachable: fail closed
        }
    }

    static List<String> parseDisallows(String robotsTxt) {
        List<String> disallows = new ArrayList<>();
        boolean applies = false;
        for (String rawLine : robotsTxt.split("\n")) {
            String line = rawLine.split("#", 2)[0].trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user-agent:")) {
                String agent = line.substring("user-agent:".length()).trim();
                applies = agent.equals("*") || agent.toLowerCase(Locale.ROOT).contains("jrap");
            } else if (applies && lower.startsWith("disallow:")) {
                String path = line.substring("disallow:".length()).trim();
                if (!path.isEmpty()) {
                    disallows.add(path);
                }
            }
        }
        return disallows;
    }
}
