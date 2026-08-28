package dev.hmcodes.jrap.integrations.dto;

import java.util.List;
import java.util.Map;

/**
 * A journal's identity as one source states it (input to FR-JRN-1 resolution and
 * FR-JRN-2 consistency checking). Null fields mean "this source does not state it".
 */
public record JournalSourceIdentity(
        String source,
        String sourceId,
        String title,
        String publisher,
        String country,
        String issnPrint,
        String issnOnline,
        String issnL,
        List<String> issns,
        String homepageUrl,
        Map<String, Object> extra) {
}
