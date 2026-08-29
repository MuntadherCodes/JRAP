package dev.hmcodes.jrap.integrations.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single outbound HTTP path for scholarly APIs and site probes.
 *
 * <p>Politeness per CON-2/CON-3: identifying User-Agent with a contact address, a
 * per-host minimum request interval (default 1 s, configurable down only in tests),
 * bounded retries with exponential backoff on transient failures. Never follows
 * redirects into authentication and never sends credentials.</p>
 */
@Component
public class PoliteHttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(PoliteHttpFetcher.class);
    private static final int MAX_ATTEMPTS = 3;
    /** Longest single retry wait, even when a server's Retry-After asks for more. */
    private static final long MAX_RETRY_WAIT_MS = 60_000;

    private final HttpClient client;
    private final String userAgent;
    private final long perHostMinIntervalMs;
    private final Map<String, Long> lastRequestAtMs = new ConcurrentHashMap<>();
    private final Map<String, Long> hostIntervalOverrideMs = new ConcurrentHashMap<>();

    public PoliteHttpFetcher(@Value("${jrap.contact-email}") String contactEmail,
                             @Value("${jrap.integrations.per-host-min-interval-ms:1000}") long perHostMinIntervalMs) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.userAgent = "JRAP/0.1 (Journal Readiness Audit Platform; mailto:" + contactEmail + ")";
        this.perHostMinIntervalMs = perHostMinIntervalMs;
    }

    /** Fetches a resource as raw bytes with selected response headers — used by the crawler. */
    public FetchedResource getResource(String url, Map<String, String> headers) {
        URI uri = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .GET();
        headers.forEach(builder::setHeader); // caller-supplied headers REPLACE defaults (per-source UA override)
        HttpRequest request = builder.build();

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            awaitPolitenessSlot(uri.getHost());
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if ((response.statusCode() >= 500 || response.statusCode() == 429) && attempt < MAX_ATTEMPTS) {
                    backoff(attempt, response);
                    continue;
                }
                Map<String, String> kept = new java.util.LinkedHashMap<>();
                for (String name : List.of("content-type", "etag", "last-modified", "content-language")) {
                    response.headers().firstValue(name).ifPresent(value -> kept.put(name, value));
                }
                return new FetchedResource(response.statusCode(), response.body(), kept,
                        response.uri().toString());
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted while fetching " + url, e);
            }
        }
        log.warn("Fetch failed after {} attempts: {}", MAX_ATTEMPTS, url);
        throw new FetchException("Failed to fetch " + url, lastFailure);
    }

    public FetchResult get(String url, Map<String, String> headers) {
        URI uri = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, text/html;q=0.8, */*;q=0.5")
                .GET();
        headers.forEach(builder::setHeader); // caller-supplied headers REPLACE defaults (per-source UA override)
        HttpRequest request = builder.build();

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            awaitPolitenessSlot(uri.getHost()); // every attempt claims a fresh slot
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if ((response.statusCode() >= 500 || response.statusCode() == 429) && attempt < MAX_ATTEMPTS) {
                    backoff(attempt, response);
                    continue;
                }
                return new FetchResult(response.statusCode(), response.body(), url);
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted while fetching " + url, e);
            }
        }
        log.warn("Fetch failed after {} attempts: {}", MAX_ATTEMPTS, url);
        throw new FetchException("Failed to fetch " + url, lastFailure);
    }

    /**
     * Honors a robots.txt Crawl-delay for a host (caller caps the value). A delay at or
     * below the configured default interval clears any override — refreshed robots.txt
     * files that drop the directive fall back to the baseline.
     */
    public void respectCrawlDelay(String host, long millis) {
        if (host == null) {
            return;
        }
        if (millis > perHostMinIntervalMs) {
            hostIntervalOverrideMs.put(host, millis);
        } else {
            hostIntervalOverrideMs.remove(host);
        }
    }

    private void awaitPolitenessSlot(String host) {
        if (host == null) {
            return;
        }
        long interval = hostIntervalOverrideMs.getOrDefault(host, perHostMinIntervalMs);
        if (interval <= 0) {
            return;
        }
        while (true) {
            long now = System.currentTimeMillis();
            Long previous = lastRequestAtMs.get(host);
            if (previous == null || now - previous >= interval) {
                Long witnessed = previous;
                boolean claimed = (witnessed == null)
                        ? lastRequestAtMs.putIfAbsent(host, now) == null
                        : lastRequestAtMs.replace(host, witnessed, now);
                if (claimed) {
                    return;
                }
            } else {
                try {
                    Thread.sleep(Math.max(10, interval - (now - previous)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FetchException("Interrupted while rate-limiting for host " + host, e);
                }
            }
        }
    }

    /**
     * Waits before a retry: exponential backoff, or the server's Retry-After when it
     * asks for longer (RFC 9110 §10.2.3 — sent with 429 and 503). Whichever wins is
     * capped at {@link #MAX_RETRY_WAIT_MS}.
     */
    private void backoff(int attempt, HttpResponse<?> response) {
        long waitMs = 500L * (1L << (attempt - 1));
        if (response != null) {
            waitMs = Math.max(waitMs, retryAfterMillis(response));
        }
        try {
            Thread.sleep(Math.min(waitMs, MAX_RETRY_WAIT_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted during retry backoff", e);
        }
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after")
                .map(PoliteHttpFetcher::parseRetryAfter)
                .orElse(0L);
    }

    /** Parses both Retry-After forms: delay-seconds ("120") and HTTP-date (RFC 1123). */
    static long parseRetryAfter(String value) {
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return Math.max(0, seconds) * 1000L;
        } catch (NumberFormatException notSeconds) {
            try {
                Instant when = ZonedDateTime
                        .parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, Duration.between(Instant.now(), when).toMillis());
            } catch (DateTimeParseException notDate) {
                return 0;
            }
        }
    }
}
