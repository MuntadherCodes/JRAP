package dev.hmcodes.jrap.extract.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.aigateway.LlmGateway;
import dev.hmcodes.jrap.crawl.domain.Snapshot;
import dev.hmcodes.jrap.crawl.repo.SnapshotRepository;
import dev.hmcodes.jrap.crawl.store.SnapshotStore;
import dev.hmcodes.jrap.extract.domain.Article;
import dev.hmcodes.jrap.extract.domain.AuthorSlot;
import dev.hmcodes.jrap.extract.domain.BoardMember;
import dev.hmcodes.jrap.extract.parse.ArticleParser;
import dev.hmcodes.jrap.extract.parse.BoardParser;
import dev.hmcodes.jrap.extract.parse.ParsedArticle;
import dev.hmcodes.jrap.extract.parse.ParsedMember;
import dev.hmcodes.jrap.extract.repo.ArticleRepository;
import dev.hmcodes.jrap.extract.repo.AuthorSlotRepository;
import dev.hmcodes.jrap.extract.repo.BoardMemberRepository;
import dev.hmcodes.jrap.extract.util.Countries;
import dev.hmcodes.jrap.extract.util.LanguageDetector;
import dev.hmcodes.jrap.extract.util.NameNormalizer;
import dev.hmcodes.jrap.extract.util.ScriptDetector;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The EXTRACT stage (FR-EXT-1..4, 6): deterministic parsers first, LLM fallback via the
 * gateway only when a page defeats them, provenance and confidence on every row, and
 * needs_review flags feeding the Phase-6 human-confirmation queue. Idempotent per
 * snapshot — a resumed audit never re-extracts what is already stored.
 */
@Service
public class ExtractService {

    private static final Logger log = LoggerFactory.getLogger(ExtractService.class);

    private final SnapshotRepository snapshots;
    private final SnapshotStore store;
    private final BoardParser boardParser;
    private final ArticleParser articleParser;
    private final BoardMemberRepository boardMembers;
    private final ArticleRepository articles;
    private final AuthorSlotRepository authorSlots;
    private final AuditRepository audits;
    private final LlmGateway llm;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final double confidenceThreshold;

    public ExtractService(SnapshotRepository snapshots, SnapshotStore store, BoardParser boardParser,
                          ArticleParser articleParser, BoardMemberRepository boardMembers,
                          ArticleRepository articles, AuthorSlotRepository authorSlots,
                          AuditRepository audits, LlmGateway llm, ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager, Clock clock,
                          @Value("${jrap.extract.confidence-threshold:0.8}") double confidenceThreshold) {
        this.snapshots = snapshots;
        this.store = store;
        this.boardParser = boardParser;
        this.articleParser = articleParser;
        this.boardMembers = boardMembers;
        this.articles = articles;
        this.authorSlots = authorSlots;
        this.audits = audits;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.confidenceThreshold = confidenceThreshold;
    }

    public void run(Audit audit, Journal journal) {
        List<Snapshot> all = snapshots.findByAuditIdOrderByFetchedAt(audit.getId());
        for (Snapshot snapshot : all) {
            Audit current = audits.findById(audit.getId()).orElseThrow();
            if (current.getStatus() == Audit.Status.CANCELLED) {
                return;
            }
            if (snapshot.getHttpStatus() != 200) {
                continue;
            }
            switch (snapshot.getPageType()) {
                case "editorial-team" -> extractBoard(audit, journal, snapshot);
                case "article-landing" -> extractArticle(audit, journal, snapshot);
                default -> { }
            }
        }
        long articleCount = articles.countByAuditId(audit.getId());
        long memberCount = boardMembers.countByAuditId(audit.getId());
        tx.execute(status -> {
            audits.findById(audit.getId()).ifPresent(a -> {
                a.setArticlesExtracted((int) articleCount);
                a.setBoardMembersExtracted((int) memberCount);
            });
            return null;
        });
    }

    private void extractBoard(Audit audit, Journal journal, Snapshot snapshot) {
        if (boardMembers.existsByAuditIdAndSnapshotId(audit.getId(), snapshot.getId())) {
            return; // resume: already extracted
        }
        Document document = load(snapshot);
        if (document == null) {
            return;
        }
        List<ParsedMember> parsed = boardParser.parse(document);
        String method = "PARSER";
        String promptVersion = null;
        if (parsed.isEmpty() && llm.isEnabled()) {
            LlmGateway.GatewayResult result = llm.complete("board-extraction",
                    Map.of("page_text", clip(document.text(), 12000)),
                    audit.getId(), List.of(snapshot.getId()), 4000);
            if (result.ok()) {
                parsed = parseLlmMembers(result.text());
                method = "LLM";
                promptVersion = result.promptVersion();
            }
        }
        Instant now = clock.instant();
        for (ParsedMember member : parsed) {
            double confidence = method.equals("LLM") ? 0.7 : member.confidence();
            BoardMember row = new BoardMember(UUID.randomUUID(), audit.getOrganisationId(),
                    journal.getId(), audit.getId(), snapshot.getId(), member.name(),
                    NameNormalizer.normalize(member.name()), member.role(), member.institution(),
                    member.country(), toJson(member.profileLinks()), method, promptVersion,
                    decimal(confidence), member.excerpt(), confidence < confidenceThreshold, now);
            try {
                tx.execute(status -> boardMembers.save(row));
            } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
                // duplicate (audit, snapshot, name, role) — first write wins
            }
        }
    }

    private void extractArticle(Audit audit, Journal journal, Snapshot snapshot) {
        if (articles.existsByAuditIdAndSnapshotId(audit.getId(), snapshot.getId())) {
            return; // resume: already extracted
        }
        Document document = load(snapshot);
        if (document == null) {
            return;
        }
        ParsedArticle parsed = articleParser.parse(document);
        String promptVersion = null;
        if (parsed.title() == null && llm.isEnabled()) {
            LlmGateway.GatewayResult result = llm.complete("article-extraction",
                    Map.of("page_text", clip(document.text(), 12000)),
                    audit.getId(), List.of(snapshot.getId()), 4000);
            if (result.ok()) {
                ParsedArticle fromLlm = parseLlmArticle(result.text());
                if (fromLlm != null) {
                    parsed = fromLlm;
                    promptVersion = result.promptVersion();
                }
            }
        }

        Instant now = clock.instant();
        Article article = new Article(UUID.randomUUID(), audit.getOrganisationId(), journal.getId(),
                audit.getId(), snapshot.getId(), now);
        article.setTitle(parsed.title());
        article.setTitleScript(parsed.title() == null ? null : ScriptDetector.classify(parsed.title()));
        article.setDoi(parsed.doi());
        article.setPages(parsed.pages());
        article.setAbstractText(clip(parsed.abstractText(), 8000));
        article.setAbstractLanguage(LanguageDetector.detect(parsed.abstractText()));
        article.setDateSubmitted(parsed.dateSubmitted());
        article.setDateAccepted(parsed.dateAccepted());
        article.setDatePublished(parsed.datePublished());
        article.setKeywords(toJson(parsed.keywords()));
        article.setReferencesJson(toJson(clipList(parsed.references(), 300)));
        article.setReferencesCount(parsed.references().size());
        if (!parsed.references().isEmpty()) {
            article.setReferencesRomanShare(BigDecimal.valueOf(
                            ScriptDetector.romanShare(String.join(" ", parsed.references())))
                    .setScale(3, RoundingMode.HALF_UP));
        }
        article.setMethod(promptVersion != null ? "LLM" : parsed.method());
        article.setPromptVersion(promptVersion);
        double confidence = promptVersion != null ? 0.7 : parsed.confidence();
        article.setConfidence(decimal(confidence));
        article.setNeedsReview(confidence < confidenceThreshold);

        List<AuthorSlot> slots = new ArrayList<>();
        int position = 1;
        for (ParsedArticle.ParsedAuthor author : parsed.authors()) {
            slots.add(new AuthorSlot(UUID.randomUUID(), audit.getOrganisationId(), article.getId(),
                    position++, author.name(), NameNormalizer.normalize(author.name()),
                    author.affiliation(), Countries.find(author.affiliation()).orElse(null), now));
        }
        try {
            tx.execute(status -> {
                articles.save(article);
                authorSlots.saveAll(slots);
                return null;
            });
        } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
            // duplicate (audit, snapshot) — first write wins
        }
    }

    private Document load(Snapshot snapshot) {
        try {
            byte[] raw = store.get(snapshot.getRawStorageKey());
            return Jsoup.parse(new String(raw, StandardCharsets.UTF_8), snapshot.getUrl());
        } catch (Exception e) {
            log.warn("Failed to load snapshot {} for extraction: {}", snapshot.getId(), e.getMessage());
            return null;
        }
    }

    private List<ParsedMember> parseLlmMembers(String json) {
        try {
            JsonNode root = objectMapper.readTree(stripFences(json));
            List<ParsedMember> members = new ArrayList<>();
            for (JsonNode node : root) {
                String name = text(node, "name");
                if (name != null && BoardParser.looksLikeName(name)) {
                    members.add(new ParsedMember(name, text(node, "role"), text(node, "institution"),
                            text(node, "country"), List.of(), 0.7, "LLM extraction"));
                }
            }
            return members;
        } catch (Exception e) {
            log.warn("LLM board response unparseable: {}", e.getMessage());
            return List.of();
        }
    }

    private ParsedArticle parseLlmArticle(String json) {
        try {
            JsonNode root = objectMapper.readTree(stripFences(json));
            if (!root.hasNonNull("title")) {
                return null;
            }
            List<ParsedArticle.ParsedAuthor> authors = new ArrayList<>();
            for (JsonNode author : root.path("authors")) {
                String name = text(author, "name");
                if (name != null) {
                    authors.add(new ParsedArticle.ParsedAuthor(name, text(author, "affiliation")));
                }
            }
            List<String> keywords = new ArrayList<>();
            root.path("keywords").forEach(k -> keywords.add(k.asText()));
            return new ParsedArticle(text(root, "title"), text(root, "doi"), text(root, "pages"),
                    text(root, "abstract"), text(root, "date_submitted"), text(root, "date_accepted"),
                    text(root, "date_published"), keywords, List.of(), authors, "LLM", 0.7);
        } catch (Exception e) {
            log.warn("LLM article response unparseable: {}", e.getMessage());
            return null;
        }
    }

    private static String stripFences(String text) {
        return text == null ? "" : text.replaceAll("(?s)```(?:json)?", "").trim();
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.get(field).asText().isBlank()
                ? node.get(field).asText().trim() : null;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String clip(String text, int max) {
        return text == null ? null : text.length() > max ? text.substring(0, max) : text;
    }

    private static List<String> clipList(List<String> list, int max) {
        return list.size() > max ? list.subList(0, max) : list;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
