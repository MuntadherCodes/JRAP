package dev.hmcodes.jrap.integrations.http;

/** Outcome of one polite HTTP GET. */
public record FetchResult(int statusCode, String body, String url) {

    public boolean ok() {
        return statusCode >= 200 && statusCode < 300;
    }
}
