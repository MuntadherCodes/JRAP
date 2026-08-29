package dev.hmcodes.jrap.crawl.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S3/MinIO-backed snapshot store (SRS §2.4 object storage; ops hardening). Talks the S3
 * REST API directly over java.net.http with an in-repo SigV4 signer — no AWS SDK, per
 * the project's no-new-dependency stance. Path-style addressing so MinIO works without
 * wildcard DNS. Selected with {@code jrap.snapshots.store=s3}; the filesystem store
 * remains the default. PUTs are idempotent because keys are content-addressed.
 */
@Component
@ConditionalOnProperty(name = "jrap.snapshots.store", havingValue = "s3")
public class S3SnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(S3SnapshotStore.class);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient client;
    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;

    public S3SnapshotStore(@Value("${jrap.snapshots.s3.endpoint}") String endpoint,
                           @Value("${jrap.snapshots.s3.bucket}") String bucket,
                           @Value("${jrap.snapshots.s3.region:us-east-1}") String region,
                           @Value("${jrap.snapshots.s3.access-key}") String accessKey,
                           @Value("${jrap.snapshots.s3.secret-key}") String secretKey) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.endpoint = URI.create(endpoint.replaceAll("/+$", ""));
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        log.info("Snapshot store: s3 endpoint={} bucket={}", this.endpoint, bucket);
    }

    @Override
    public String put(String auditId, String category, String contentHash, byte[] bytes) {
        String key = auditId + "/" + category + "/" + contentHash;
        HttpResponse<byte[]> response = send("PUT", key, bytes);
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(new IOException(
                    "S3 PUT " + key + " returned " + response.statusCode()));
        }
        return key;
    }

    @Override
    public byte[] get(String storageKey) {
        HttpResponse<byte[]> response = send("GET", storageKey, null);
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(new IOException(
                    "S3 GET " + storageKey + " returned " + response.statusCode()));
        }
        return response.body();
    }

    private HttpResponse<byte[]> send(String method, String key, byte[] body) {
        StringBuilder encodedKey = new StringBuilder();
        for (String segment : key.split("/")) {
            if (!encodedKey.isEmpty()) {
                encodedKey.append('/');
            }
            encodedKey.append(AwsSigV4.encodeSegment(segment));
        }
        URI uri = URI.create(endpoint + "/" + AwsSigV4.encodeSegment(bucket) + "/" + encodedKey);

        String payloadHash = AwsSigV4.sha256Hex(body == null ? new byte[0] : body);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
            Map<String, String> signedHeaders = new LinkedHashMap<>();
            signedHeaders.put("host", hostHeader(uri));
            signedHeaders.put("x-amz-content-sha256", payloadHash);
            signedHeaders.put("x-amz-date", amzDate);
            String authorization = AwsSigV4.authorization(method, uri, signedHeaders,
                    payloadHash, accessKey, secretKey, region, "s3");

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("x-amz-content-sha256", payloadHash)
                    .header("x-amz-date", amzDate)
                    .header("Authorization", authorization)
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 500 && attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UncheckedIOException(new IOException("Interrupted talking to S3", e));
            }
        }
        throw new UncheckedIOException(new IOException(
                "S3 " + method + " " + key + " failed after " + MAX_ATTEMPTS + " attempts", lastFailure));
    }

    /** Host header per SigV4: include the port only when it is non-default. */
    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Interrupted during S3 retry backoff", e));
        }
    }
}
