package dev.hmcodes.jrap.crawl.store;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dependency-free AWS Signature Version 4 signer, scoped to what the S3 snapshot store
 * needs: header-based signing of GET/PUT requests. Verified in-repo against the worked
 * examples in the AWS "Authenticating Requests (AWS Signature Version 4)" documentation
 * (the examplebucket GET/PUT vectors), so the algorithm — canonical request, string to
 * sign, derived signing key — matches AWS byte for byte.
 */
final class AwsSigV4 {

    private AwsSigV4() {}

    /**
     * Returns the Authorization header value. {@code headers} MUST already contain
     * {@code host}, {@code x-amz-date} (basic ISO-8601, e.g. 20130524T000000Z) and
     * {@code x-amz-content-sha256}; every header passed here is signed.
     */
    static String authorization(String method, URI uri, Map<String, String> headers,
                                String payloadSha256Hex, String accessKey, String secretKey,
                                String region, String service) {
        Map<String, String> canonical = new TreeMap<>();
        headers.forEach((name, value) -> canonical.put(name.toLowerCase(Locale.ROOT), value.trim()));

        StringBuilder canonicalHeaders = new StringBuilder();
        canonical.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String signedHeaders = String.join(";", canonical.keySet());

        String canonicalRequest = method + '\n'
                + canonicalUri(uri) + '\n'
                + canonicalQuery(uri) + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadSha256Hex;

        String amzDate = canonical.get("x-amz-date");
        String date = amzDate.substring(0, 8);
        String scope = date + '/' + region + '/' + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + '\n' + scope + '\n'
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = hmac(hmac(hmac(hmac(
                ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), region), service), "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKey + '/' + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** Each path segment URI-encoded once (RFC 3986 unreserved kept), "/" preserved. */
    private static String canonicalUri(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        // The raw path is already percent-encoded by the caller where needed; S3 requires
        // it forwarded as-is (single encoding, no normalisation).
        return path;
    }

    private static String canonicalQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            pairs.add(name + '=' + value);
        }
        pairs.sort(String::compareTo);
        return String.join("&", pairs);
    }

    /** RFC 3986 encoding of a single S3 key segment (unreserved characters kept). */
    static String encodeSegment(String segment) {
        StringBuilder out = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
