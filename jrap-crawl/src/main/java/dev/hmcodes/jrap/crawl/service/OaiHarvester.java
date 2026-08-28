package dev.hmcodes.jrap.crawl.service;

import dev.hmcodes.jrap.crawl.domain.OaiHarvestRecord;
import dev.hmcodes.jrap.crawl.repo.OaiHarvestRepository;
import dev.hmcodes.jrap.integrations.http.FetchException;
import dev.hmcodes.jrap.integrations.http.FetchResult;
import dev.hmcodes.jrap.integrations.http.PoliteHttpFetcher;
import dev.hmcodes.jrap.registry.domain.Audit;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * OAI-PMH harvest used as a cross-check on the HTML crawl (FR-CRWL-2). Best-effort:
 * a site without a working OAI endpoint yields zero records, never a failed audit.
 */
@Service
public class OaiHarvester {

    private static final Logger log = LoggerFactory.getLogger(OaiHarvester.class);
    private static final int MAX_PAGES = 50;

    private final OaiHarvestRepository records;
    private final PoliteHttpFetcher fetcher;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OaiHarvester(OaiHarvestRepository records, PoliteHttpFetcher fetcher,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.records = records;
        this.fetcher = fetcher;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Harvests ListRecords (oai_dc) from {baseUrl}/oai; returns the number of records stored. */
    public long harvest(Audit audit, String baseUrl) {
        String endpoint = baseUrl.replaceAll("/+$", "") + "/oai";
        String url = endpoint + "?verb=ListRecords&metadataPrefix=oai_dc";
        for (int page = 0; page < MAX_PAGES; page++) {
            FetchResult result;
            try {
                result = fetcher.get(url, Map.of());
            } catch (FetchException e) {
                log.info("OAI endpoint unreachable for audit {}: {}", audit.getId(), e.getMessage());
                break;
            }
            if (!result.ok() || result.body() == null) {
                break;
            }
            Document xml = Jsoup.parse(result.body(), "", Parser.xmlParser());
            if (!xml.select("error").isEmpty()) {
                break; // badVerb / noRecordsMatch etc.
            }
            Instant now = clock.instant();
            for (Element record : xml.select("record")) {
                Element header = record.selectFirst("header");
                if (header == null) {
                    continue;
                }
                String identifier = textOf(header, "identifier");
                if (identifier == null || records.existsByAuditIdAndIdentifier(audit.getId(), identifier)) {
                    continue;
                }
                OaiHarvestRecord harvested = new OaiHarvestRecord(UUID.randomUUID(),
                        audit.getOrganisationId(), audit.getId(), identifier,
                        textOf(header, "datestamp"), textOf(record, "dc|title"), now);
                tx.execute(status -> records.save(harvested));
            }
            Element token = xml.selectFirst("resumptionToken");
            if (token == null || token.text().isBlank()) {
                break;
            }
            url = endpoint + "?verb=ListRecords&resumptionToken="
                    + URLEncoder.encode(token.text().trim(), StandardCharsets.UTF_8);
        }
        return records.countByAuditId(audit.getId());
    }

    private static String textOf(Element parent, String selector) {
        Element element = parent.selectFirst(selector);
        return element == null || element.text().isBlank() ? null : element.text().trim();
    }
}
