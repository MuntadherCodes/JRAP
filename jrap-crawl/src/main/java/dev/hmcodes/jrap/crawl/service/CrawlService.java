package dev.hmcodes.jrap.crawl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.crawl.domain.CrawlTask;
import dev.hmcodes.jrap.crawl.domain.Snapshot;
import dev.hmcodes.jrap.crawl.repo.CrawlTaskRepository;
import dev.hmcodes.jrap.crawl.repo.SnapshotRepository;
import dev.hmcodes.jrap.crawl.store.SnapshotStore;
import dev.hmcodes.jrap.integrations.http.FetchException;
import dev.hmcodes.jrap.integrations.http.FetchedResource;
import dev.hmcodes.jrap.integrations.http.PoliteHttpFetcher;
import dev.hmcodes.jrap.integrations.http.RobotsLite;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The CRAWL stage (FR-CRWL-1..4, 7): processes an audit's persisted frontier one URL at
 * a time — robots-gated, rate-limited by the polite fetcher, page-capped, every skip
 * recorded with its reason. Each task commits in its own short transaction, so the
 * frontier itself is the checkpoint: an interrupted crawl resumes at the next QUEUED
 * task without refetching anything (NFR-AVL-1).
 */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);

    public record CrawlOutcome(boolean completed, int fetched, int skipped) {}

    private final CrawlTaskRepository tasks;
    private final SnapshotRepository snapshots;
    private final AuditRepository audits;
    private final SnapshotStore store;
    private final PoliteHttpFetcher fetcher;
    private final RobotsLite robots;
    private final PageClassifier classifier;
    private final ObjectMapper objectMapper;
    private final dev.hmcodes.jrap.registry.platform.SettingsService settings;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int maxDepth;

    public CrawlService(CrawlTaskRepository tasks, SnapshotRepository snapshots, AuditRepository audits,
                        SnapshotStore store, PoliteHttpFetcher fetcher, RobotsLite robots,
                        PageClassifier classifier, ObjectMapper objectMapper,
                        dev.hmcodes.jrap.registry.platform.SettingsService settings,
                        PlatformTransactionManager transactionManager, Clock clock,
                        @Value("${jrap.crawl.max-depth:10}") int maxDepth) {
        this.tasks = tasks;
        this.snapshots = snapshots;
        this.audits = audits;
        this.store = store;
        this.fetcher = fetcher;
        this.robots = robots;
        this.classifier = classifier;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.maxDepth = maxDepth;
    }

    /** Runs the crawl for one audit until the frontier drains, the cap is hit, or it is cancelled. */
    public CrawlOutcome run(Audit audit, Journal journal) {
        String baseUrl = baseUrlOf(journal);
        seedFrontier(audit, journal, baseUrl);

        while (true) {
            Audit current = audits.findById(audit.getId()).orElseThrow();
            if (current.getStatus() == Audit.Status.CANCELLED) {
                return new CrawlOutcome(false, current.getPagesFetched(), current.getPagesSkipped());
            }
            if (current.getPagesFetched() >= current.getPageCap()) {
                skipRemaining(audit.getId(), "page-cap-reached");
                Audit after = audits.findById(audit.getId()).orElseThrow();
                return new CrawlOutcome(true, after.getPagesFetched(), after.getPagesSkipped());
            }
            var next = tasks.findFirstByAuditIdAndStatusOrderByCreatedAt(audit.getId(), CrawlTask.Status.QUEUED);
            if (next.isEmpty()) {
                Audit after = audits.findById(audit.getId()).orElseThrow();
                return new CrawlOutcome(true, after.getPagesFetched(), after.getPagesSkipped());
            }
            processTask(next.get(), current, journal, baseUrl);
        }
    }

    private void processTask(CrawlTask task, Audit audit, Journal journal, String baseUrl) {
        Instant now = clock.instant();

        if (snapshots.findByAuditIdAndUrl(audit.getId(), task.getUrl()).isPresent()) {
            // Resume: already snapshotted in an earlier run of this audit.
            tx.execute(status -> {
                tasks.findById(task.getId()).ifPresent(t -> t.markDone(now));
                return null;
            });
            return;
        }
        if (isAdminBlocked(task.getUrl())) {
            // FR-ADM-1: platform-level crawl blocklist (abuse control), recorded like any skip.
            finishTask(task, audit, "admin-blocklist", true);
            return;
        }
        if (!robots.isAllowed(task.getUrl())) {
            finishTask(task, audit, "robots-disallowed", true);
            return;
        }

        FetchedResource resource;
        try {
            resource = fetcher.getResource(task.getUrl(), Map.of());
        } catch (FetchException e) {
            finishTask(task, audit, "network-error: " + e.getMessage(), false);
            return;
        }

        String contentType = resource.contentType();
        boolean isHtml = contentType != null && contentType.contains("html");
        boolean isPdf = contentType != null && contentType.contains("pdf");
        String pageTitle = null;
        String text = null;
        Document document = null;

        // Base URI for link resolution follows redirects (FetchedResource.finalUrl).
        String baseForLinks = resource.finalUrl() != null ? resource.finalUrl() : task.getUrl();
        if (isHtml && resource.body() != null) {
            document = Jsoup.parse(new String(resource.body(), StandardCharsets.UTF_8), baseForLinks);
            pageTitle = document.title();
            text = document.text();
        } else if (isPdf && resource.body() != null && resource.ok()) {
            text = extractPdfText(resource.body());
        }

        String pageType = classifier.classify(task.getUrl(), contentType, pageTitle, journal.getHomepageUrl());
        String contentHash = sha256(resource.body());
        String rawKey = store.put(audit.getId().toString(), "raw", contentHash, orEmpty(resource.body()));
        String textKey = text == null ? null
                : store.put(audit.getId().toString(), "text", sha256(text.getBytes(StandardCharsets.UTF_8)),
                        text.getBytes(StandardCharsets.UTF_8));

        Map<String, String> headerMap = new LinkedHashMap<>(resource.headers());
        boolean noFollow = false;
        if (document != null) {
            Element metaRobots = document.selectFirst("meta[name=robots]");
            if (metaRobots != null) {
                String directives = metaRobots.attr("content").toLowerCase(Locale.ROOT);
                headerMap.put("meta-robots", directives);
                noFollow = directives.contains("nofollow");
            }
        }

        Snapshot snapshot = new Snapshot(UUID.randomUUID(), audit.getOrganisationId(), audit.getId(),
                journal.getId(), task.getUrl(), resource.statusCode(), contentType, contentHash,
                rawKey, textKey, pageType, toJson(headerMap), now, now);

        Document finalDocument = noFollow ? null : document;
        CrawlTask finalTask = task;
        tx.execute(status -> {
            snapshots.save(snapshot);
            tasks.findById(finalTask.getId()).ifPresent(t -> t.markDone(now));
            audits.findById(audit.getId()).ifPresent(a -> a.setPagesFetched(a.getPagesFetched() + 1));
            return null;
        });

        if (finalDocument != null && task.getDepth() < maxDepth && resource.ok()) {
            discoverLinks(finalDocument, task, audit, baseUrl);
        }
    }

    private void discoverLinks(Document document, CrawlTask from, Audit audit, String baseUrl) {
        String baseHost = hostOf(baseUrl);
        long frontierBound = (long) audit.getPageCap() * 3;
        Instant now = clock.instant();
        for (Element anchor : document.select("a[href]")) {
            if ("nofollow".equalsIgnoreCase(anchor.attr("rel"))) {
                continue;
            }
            String absolute = anchor.absUrl("href");
            String normalised = normaliseUrl(absolute);
            if (normalised == null || !baseHost.equalsIgnoreCase(hostOf(normalised))) {
                continue;
            }
            if (looksLikeAsset(normalised) || looksLikeNoise(normalised)) {
                continue;
            }
            if (tasks.existsByAuditIdAndUrl(audit.getId(), normalised)) {
                continue;
            }
            if (tasks.countByAuditIdAndStatus(audit.getId(), CrawlTask.Status.QUEUED) >= frontierBound) {
                return;
            }
            CrawlTask discovered = new CrawlTask(UUID.randomUUID(), audit.getOrganisationId(),
                    audit.getId(), normalised, from.getDepth() + 1, from.getUrl(), now);
            try {
                tx.execute(status -> tasks.save(discovered));
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // concurrent duplicate — the unique (audit_id, url) index is the arbiter
            }
        }
    }

    private void seedFrontier(Audit audit, Journal journal, String baseUrl) {
        Instant now = clock.instant();
        enqueueIfAbsent(audit, baseUrl, 0, "seed", now);
        // The OJS profile enumerates the archive explicitly (FR-CRWL-2). This is also
        // harmless on non-OJS sites: a 404 becomes a recorded snapshot, not a failure.
        enqueueIfAbsent(audit, baseUrl.replaceAll("/+$", "") + "/issue/archive", 1, "ojs-profile", now);
    }

    private void enqueueIfAbsent(Audit audit, String url, int depth, String from, Instant now) {
        String normalised = normaliseUrl(url);
        if (normalised == null || tasks.existsByAuditIdAndUrl(audit.getId(), normalised)) {
            return;
        }
        CrawlTask task = new CrawlTask(UUID.randomUUID(), audit.getOrganisationId(), audit.getId(),
                normalised, depth, from, now);
        try {
            tx.execute(status -> tasks.save(task));
        } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
            // already enqueued
        }
    }

    private boolean isAdminBlocked(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            for (String blocked : settings.crawlBlocklist()) {
                if (lower.equals(blocked) || lower.endsWith("." + blocked)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void finishTask(CrawlTask task, Audit audit, String reason, boolean skipped) {
        Instant now = clock.instant();
        tx.execute(status -> {
            tasks.findById(task.getId()).ifPresent(t -> {
                if (skipped) {
                    t.markSkipped(reason, now);
                } else {
                    t.markFailed(reason, now);
                }
            });
            audits.findById(audit.getId()).ifPresent(a -> a.setPagesSkipped(a.getPagesSkipped() + 1));
            return null;
        });
    }

    private void skipRemaining(UUID auditId, String reason) {
        Instant now = clock.instant();
        tx.execute(status -> {
            var queued = tasks.findByAuditIdAndStatusIn(auditId, java.util.List.of(CrawlTask.Status.QUEUED));
            queued.forEach(t -> t.markSkipped(reason, now));
            audits.findById(auditId).ifPresent(a -> a.setPagesSkipped(a.getPagesSkipped() + queued.size()));
            return null;
        });
    }

    private String extractPdfText(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        } catch (Exception e) {
            log.warn("PDF text extraction failed: {}", e.getMessage());
            return null; // OCR fallback for scanned PDFs arrives with the extract module (Phase 4)
        }
    }

    public static String normaliseUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null
                    || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
                return null;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            path = path.replaceAll(";jsessionid=[^/?]*", "");
            String query = uri.getRawQuery();
            return uri.getScheme() + "://" + uri.getRawAuthority() + path
                    + (query == null || query.isBlank() ? "" : "?" + query);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean looksLikeAsset(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg",
                ".ico", ".woff", ".woff2", ".ttf", ".zip", ".mp4"}) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeNoise(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("/login") || lower.contains("/user/register")
                || lower.contains("/search?") || lower.contains("logout")
                || lower.contains("mailto:");
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String baseUrlOf(Journal journal) {
        String base = journal.getHomepageUrl() != null ? journal.getHomepageUrl()
                : journal.getRegisteredInput();
        if (base == null || normaliseUrl(base) == null) {
            throw new IllegalStateException("Journal has no crawlable homepage URL");
        }
        return normaliseUrl(base);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] orEmpty(byte[] bytes) {
        return bytes == null ? new byte[0] : bytes;
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
