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
 * Minimal robots.txt gate (CON-2): Disallow prefixes plus Crawl-delay. A missing
 * robots.txt (404) allows fetching; an unreachable or erroring robots.txt fails
 * CLOSED — when in doubt, JRAP does not fetch. A declared Crawl-delay is honored by
 * registering a per-host interval with the polite fetcher, capped at
 * {@link #MAX_CRAWL_DELAY_MS}: a hostile robots.txt must not be able to stall an
 * audit for a day (10 s x a 3000-page cap is already ~8 h of wall clock on a
 * single-threaded runner), and 10 s is far above CON-2's 1 req/s baseline.
 */
@Component
public class RobotsLite {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RobotsLite.class);

    record ParsedRules(List<String> disallows, long crawlDelayMillis) {}

    private record CachedRules(List<String> disallows, Instant fetchedAt) {}

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    static final long MAX_CRAWL_DELAY_MS = 10_000;

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
            rules = fetchRules(hostKey, uri.getHost()); // outside any map lock; a rare duplicate fetch is fine
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

    private CachedRules fetchRules(String hostKey, String host) {
        try {
            FetchResult result = fetcher.get(hostKey + "/robots.txt", Map.of());
            if (result.statusCode() == 404) {
                fetcher.respectCrawlDelay(host, 0); // no robots.txt: allowed, default politeness
                return new CachedRules(List.of(), Instant.now());
            }
            if (!result.ok()) {
                return null; // ambiguous: fail closed
            }
            ParsedRules parsed = parse(result.body());
            long delay = Math.min(parsed.crawlDelayMillis(), MAX_CRAWL_DELAY_MS);
            if (delay > 0) {
                log.info("robots.txt Crawl-delay {} ms honored for {} (declared {} ms) — crawls of this host slow down accordingly",
                        delay, host, parsed.crawlDelayMillis());
            }
            fetcher.respectCrawlDelay(host, delay);
            return new CachedRules(parsed.disallows(), Instant.now());
        } catch (FetchException e) {
            return null; // unreachable: fail closed
        }
    }

    static ParsedRules parse(String robotsTxt) {
        List<String> disallows = new ArrayList<>();
        long crawlDelayMillis = 0;
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
            } else if (applies && lower.startsWith("crawl-delay:")) {
                String value = line.substring("crawl-delay:".length()).trim();
                try {
                    double seconds = Double.parseDouble(value); // fractional values occur in the wild
                    if (seconds > 0) {
                        crawlDelayMillis = Math.max(crawlDelayMillis, (long) (seconds * 1000));
                    }
                } catch (NumberFormatException ignored) {
                    // malformed delay: ignore the directive, keep the disallows
                }
            }
        }
        return new ParsedRules(disallows, crawlDelayMillis);
    }
}
