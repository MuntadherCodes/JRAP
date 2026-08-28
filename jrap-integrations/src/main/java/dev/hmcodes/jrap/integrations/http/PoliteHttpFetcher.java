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

    private final HttpClient client;
    private final String userAgent;
    private final long perHostMinIntervalMs;
    private final Map<String, Long> lastRequestAtMs = new ConcurrentHashMap<>();

    public PoliteHttpFetcher(@Value("${jrap.contact-email}") String contactEmail,
                             @Value("${jrap.integrations.per-host-min-interval-ms:1000}") long perHostMinIntervalMs) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.userAgent = "JRAP/0.1 (Journal Readiness Audit Platform; mailto:" + contactEmail + ")";
        this.perHostMinIntervalMs = perHostMinIntervalMs;
    }

    public FetchResult get(String url, Map<String, String> headers) {
        URI uri = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, text/html;q=0.8, */*;q=0.5")
                .GET();
        headers.forEach(builder::header);
        HttpRequest request = builder.build();

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            awaitPolitenessSlot(uri.getHost()); // every attempt claims a fresh slot
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if ((response.statusCode() >= 500 || response.statusCode() == 429) && attempt < MAX_ATTEMPTS) {
                    backoff(attempt);
                    continue;
                }
                return new FetchResult(response.statusCode(), response.body(), url);
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted while fetching " + url, e);
            }
        }
        log.warn("Fetch failed after {} attempts: {}", MAX_ATTEMPTS, url);
        throw new FetchException("Failed to fetch " + url, lastFailure);
    }

    private void awaitPolitenessSlot(String host) {
        if (perHostMinIntervalMs <= 0 || host == null) {
            return;
        }
        while (true) {
            long now = System.currentTimeMillis();
            Long previous = lastRequestAtMs.get(host);
            if (previous == null || now - previous >= perHostMinIntervalMs) {
                Long witnessed = previous;
                boolean claimed = (witnessed == null)
                        ? lastRequestAtMs.putIfAbsent(host, now) == null
                        : lastRequestAtMs.replace(host, witnessed, now);
                if (claimed) {
                    return;
                }
            } else {
                try {
                    Thread.sleep(Math.max(10, perHostMinIntervalMs - (now - previous)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FetchException("Interrupted while rate-limiting for host " + host, e);
                }
            }
        }
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(500L * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted during retry backoff", e);
        }
    }
}
