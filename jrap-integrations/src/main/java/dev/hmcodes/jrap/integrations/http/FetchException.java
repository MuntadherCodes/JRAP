package dev.hmcodes.jrap.integrations.http;

/** Network-level failure after retries — callers degrade gracefully (FR-INT-6). */
public class FetchException extends RuntimeException {

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
