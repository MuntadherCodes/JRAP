package dev.hmcodes.jrap.integrations.cache;

import java.time.Instant;
import java.util.UUID;

/** A source response together with its stored evidence record. */
public record RecordedResponse(UUID apiRecordId, int statusCode, String body,
                               Instant retrievedAt, boolean fromCache) {
}
