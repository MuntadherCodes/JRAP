package dev.hmcodes.jrap.integrations.cache;

import dev.hmcodes.jrap.integrations.http.FetchException;
import dev.hmcodes.jrap.integrations.http.FetchResult;
import dev.hmcodes.jrap.integrations.http.PoliteHttpFetcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cache-through access to scholarly sources (CON-3, FR-INT-1..4).
 *
 * <p>Every upstream response — cacheable or not — is stored as an immutable
 * {@link ApiRecord} with its retrieval timestamp, so downstream findings can cite it as
 * evidence. Fresh 200/404 records satisfy later lookups within the TTL; error responses
 * are stored for evidence but never served from cache. Network failure returns empty —
 * callers degrade to "source unavailable" (FR-INT-6), never abort.</p>
 */
@Service
public class ApiRecordService {

    private final ApiRecordRepository repository;
    private final PoliteHttpFetcher fetcher;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration notFoundTtl;

    public ApiRecordService(ApiRecordRepository repository, PoliteHttpFetcher fetcher, Clock clock,
                            @Value("${jrap.integrations.cache-ttl:P7D}") Duration cacheTtl,
                            @Value("${jrap.integrations.not-found-ttl:PT6H}") Duration notFoundTtl) {
        this.repository = repository;
        this.fetcher = fetcher;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
        this.notFoundTtl = notFoundTtl;
    }

    /** Returns the response for (source, key), from cache when fresh; empty on network failure. */
    public Optional<RecordedResponse> getOrFetch(String source, String requestKey, String url,
                                                 Map<String, String> headers) {
        Instant now = clock.instant();
        Optional<ApiRecord> cached = repository.findFresh(source, requestKey, now).stream().findFirst();
        if (cached.isPresent()) {
            ApiRecord record = cached.get();
            return Optional.of(new RecordedResponse(record.getId(), record.getStatusCode(),
                    record.getResponseBody(), record.getRetrievedAt(), true));
        }
        FetchResult result;
        try {
            result = fetcher.get(url, headers);
        } catch (FetchException e) {
            return Optional.empty();
        }
        Instant expires = switch (result.statusCode()) {
            case 200 -> now.plus(cacheTtl);
            case 404 -> now.plus(notFoundTtl);
            default -> now; // stored as evidence, never served from cache
        };
        ApiRecord record = new ApiRecord(UUID.randomUUID(), source, requestKey, url,
                result.statusCode(), result.body(), hash(result.body()), now, expires);
        repository.save(record);
        return Optional.of(new RecordedResponse(record.getId(), record.getStatusCode(),
                record.getResponseBody(), record.getRetrievedAt(), false));
    }

    private static String hash(String body) {
        if (body == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
