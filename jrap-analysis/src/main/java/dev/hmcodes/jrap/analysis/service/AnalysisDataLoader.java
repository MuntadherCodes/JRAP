package dev.hmcodes.jrap.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.crawl.repo.SnapshotRepository;
import dev.hmcodes.jrap.extract.domain.Article;
import dev.hmcodes.jrap.extract.domain.AuthorSlot;
import dev.hmcodes.jrap.extract.repo.ArticleRepository;
import dev.hmcodes.jrap.extract.repo.AuthorSlotRepository;
import dev.hmcodes.jrap.extract.repo.BoardMemberRepository;
import dev.hmcodes.jrap.integrations.cache.ApiRecord;
import dev.hmcodes.jrap.integrations.cache.ApiRecordRepository;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.domain.JournalIdentityRecord;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import dev.hmcodes.jrap.registry.repo.JournalIdentityRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Assembles the frozen evidence set the engine analyses (never the live web). */
@Service
public class AnalysisDataLoader {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDataLoader.class);

    private final ArticleRepository articles;
    private final AuthorSlotRepository authorSlots;
    private final BoardMemberRepository boardMembers;
    private final SnapshotRepository snapshots;
    private final FindingRepository findings;
    private final JournalIdentityRecordRepository identityRecords;
    private final ApiRecordRepository apiRecords;
    private final ObjectMapper objectMapper;

    public AnalysisDataLoader(ArticleRepository articles, AuthorSlotRepository authorSlots,
                              BoardMemberRepository boardMembers, SnapshotRepository snapshots,
                              FindingRepository findings, JournalIdentityRecordRepository identityRecords,
                              ApiRecordRepository apiRecords, ObjectMapper objectMapper) {
        this.articles = articles;
        this.authorSlots = authorSlots;
        this.boardMembers = boardMembers;
        this.snapshots = snapshots;
        this.findings = findings;
        this.identityRecords = identityRecords;
        this.apiRecords = apiRecords;
        this.objectMapper = objectMapper;
    }

    public AnalysisData load(Audit audit, Journal journal) {
        List<Article> articleList = articles.findByAuditIdOrderByCreatedAt(audit.getId());
        Map<UUID, List<AuthorSlot>> slots = articleList.isEmpty() ? Map.of()
                : authorSlots.findByArticleIdInOrderByArticleIdAscPositionAsc(
                        articleList.stream().map(Article::getId).toList())
                .stream().collect(Collectors.groupingBy(AuthorSlot::getArticleId));

        SortedMap<Integer, Long> worksByYear = new TreeMap<>();
        SortedMap<Integer, Long> citedByYear = new TreeMap<>();
        Double citedness = null;
        boolean openAlexAvailable = false;
        Boolean preservation = null;

        for (JournalIdentityRecord record : identityRecords.findByJournalIdOrderBySource(journal.getId())) {
            if ("OPENALEX".equals(record.getSource()) && record.getApiRecordId() != null) {
                ApiRecord api = apiRecords.findById(record.getApiRecordId()).orElse(null);
                if (api != null && api.getStatusCode() == 200) {
                    openAlexAvailable = true;
                    citedness = parseOpenAlexCounts(api.getResponseBody(), worksByYear, citedByYear);
                }
            }
            if ("DOAJ".equals(record.getSource())) {
                try {
                    JsonNode extra = objectMapper.readTree(record.getExtra());
                    if (extra.has("hasPreservation")) {
                        preservation = extra.get("hasPreservation").asBoolean();
                    }
                } catch (Exception e) {
                    log.debug("Unparseable DOAJ extra for journal {}", journal.getId());
                }
            }
        }

        return new AnalysisData(journal, articleList, slots,
                boardMembers.findByAuditIdOrderByRoleAscNameAsc(audit.getId()),
                snapshots.findByAuditIdOrderByFetchedAt(audit.getId()),
                findings.findByJournalId(journal.getId()),
                worksByYear, citedByYear, citedness, openAlexAvailable, preservation);
    }

    /** Parses counts_by_year and summary_stats from the STORED OpenAlex source record. */
    private Double parseOpenAlexCounts(String body, SortedMap<Integer, Long> worksByYear,
                                       SortedMap<Integer, Long> citedByYear) {
        try {
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode yearNode : root.path("counts_by_year")) {
                int year = yearNode.path("year").asInt();
                worksByYear.put(year, yearNode.path("works_count").asLong());
                citedByYear.put(year, yearNode.path("cited_by_count").asLong());
            }
            JsonNode stats = root.path("summary_stats");
            return stats.hasNonNull("2yr_mean_citedness")
                    ? stats.get("2yr_mean_citedness").asDouble() : null;
        } catch (Exception e) {
            log.warn("Unparseable stored OpenAlex record: {}", e.getMessage());
            return null;
        }
    }
}
