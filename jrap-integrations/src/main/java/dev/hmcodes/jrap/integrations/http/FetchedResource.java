package dev.hmcodes.jrap.integrations.http;

import java.util.Map;

/** Raw-bytes fetch outcome with the response headers worth keeping as evidence. */
public record FetchedResource(int statusCode, byte[] body, Map<String, String> headers,
                              String finalUrl) {

    public boolean ok() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String contentType() {
        return headers.get("content-type");
    }
}
