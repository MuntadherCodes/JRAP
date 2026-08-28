package dev.hmcodes.jrap.integrations.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of asking one source about one journal. {@code apiRecordId} points at the
 * stored evidence record backing this result (null only when the source was
 * network-unreachable and no response could be recorded).
 */
public record SourceResult<T>(SourceAvailability availability, T data, UUID apiRecordId,
                              Instant retrievedAt, boolean fromCache) {

    public static <T> SourceResult<T> ok(T data, UUID apiRecordId, Instant retrievedAt, boolean fromCache) {
        return new SourceResult<>(SourceAvailability.OK, data, apiRecordId, retrievedAt, fromCache);
    }

    public static <T> SourceResult<T> notFound(UUID apiRecordId, Instant retrievedAt, boolean fromCache) {
        return new SourceResult<>(SourceAvailability.NOT_FOUND, null, apiRecordId, retrievedAt, fromCache);
    }

    public static <T> SourceResult<T> unavailable(UUID apiRecordId, Instant retrievedAt) {
        return new SourceResult<>(SourceAvailability.UNAVAILABLE, null, apiRecordId, retrievedAt, false);
    }
}
