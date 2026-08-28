package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.crawl.domain.Snapshot;
import dev.hmcodes.jrap.crawl.repo.SnapshotRepository;
import dev.hmcodes.jrap.crawl.store.SnapshotStore;
import dev.hmcodes.jrap.extract.repo.ArticleRepository;
import dev.hmcodes.jrap.extract.repo.BoardMemberRepository;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.review.domain.ReviewDecision;
import dev.hmcodes.jrap.review.service.ExtractionReviewService;
import dev.hmcodes.jrap.review.service.ManualEvidenceService;
import dev.hmcodes.jrap.review.service.ReviewService;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The review workflow API (FR-REV-1..4, §3.2.2 findings confirm/reject): the queue an
 * analyst works through, the actions they take, the immutable decision history, the
 * FR-REV-4 release gate, snapshot text for the side-by-side viewer (FR-REV-2), and the
 * FR-INT-7 manual-evidence channel. VIEWERs read; OWNERs and ANALYSTs decide.
 */
@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService reviewService;
    private final ExtractionReviewService extractionReview;
    private final ManualEvidenceService manualEvidence;
    private final AuditRepository audits;
    private final BoardMemberRepository boardMembers;
    private final ArticleRepository articles;
    private final SnapshotRepository snapshots;
    private final SnapshotStore snapshotStore;
    private final EvidenceItemRepository evidenceItems;
    private final EvidenceLinkRepository evidenceLinks;

    public ReviewController(ReviewService reviewService, ExtractionReviewService extractionReview,
                            ManualEvidenceService manualEvidence, AuditRepository audits,
                            BoardMemberRepository boardMembers, ArticleRepository articles,
                            SnapshotRepository snapshots, SnapshotStore snapshotStore,
                            EvidenceItemRepository evidenceItems, EvidenceLinkRepository evidenceLinks) {
        this.reviewService = reviewService;
        this.extractionReview = extractionReview;
        this.manualEvidence = manualEvidence;
        this.audits = audits;
        this.boardMembers = boardMembers;
        this.articles = articles;
        this.snapshots = snapshots;
        this.snapshotStore = snapshotStore;
        this.evidenceItems = evidenceItems;
        this.evidenceLinks = evidenceLinks;
    }

    // ------------------------------------------------------------------ queue (FR-REV-1/2)

    public record QueueItemDto(UUID id, String kind, String severity, String status,
                               boolean excluded, String code, String title, String description,
                               String reviewNote, BigDecimal confidence, UUID snapshotId,
                               String excerpt, Map<String, String> fields, List<UUID> evidenceItemIds,
                               Instant createdAt) {}

    public record QueuePageDto(List<QueueItemDto> items, int page, int size, long total,
                               long findingsTotal, long extractionsTotal) {}

    @GetMapping("/audits/{auditId}/review/queue")
    @Transactional(readOnly = true)
    public QueuePageDto queue(@PathVariable UUID auditId,
                              @RequestParam(defaultValue = "all") String filter,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size) {
        Audit audit = requireAudit(auditId);
        if (size < 1 || size > 200) {
            size = 50;
        }

        List<QueueItemDto> findingItems = findingItems(audit);
        List<QueueItemDto> extractionItems = extractionItems(auditId);

        List<QueueItemDto> selected = switch (filter) {
            case "findings" -> findingItems;
            case "needs-verification" -> findingItems.stream()
                    .filter(i -> "NEEDS_VERIFICATION".equals(i.status())).toList();
            case "open" -> findingItems.stream()
                    .filter(i -> "AUTO".equals(i.status()) || "NEEDS_VERIFICATION".equals(i.status()))
                    .toList();
            case "extractions" -> extractionItems;
            default -> {
                List<QueueItemDto> all = new ArrayList<>(findingItems);
                all.addAll(extractionItems);
                yield all;
            }
        };

        int from = Math.max(0, page * size);
        List<QueueItemDto> slice = from >= selected.size() ? List.of()
                : selected.subList(from, Math.min(selected.size(), from + size));
        return new QueuePageDto(slice, page, size, selected.size(),
                findingItems.size(), extractionItems.size());
    }

    private List<QueueItemDto> findingItems(Audit audit) {
        List<Finding> all = reviewService.reviewableFindings(audit);
        Map<UUID, List<UUID>> evidenceByFinding = evidenceLinks
                .findByIdFindingIdIn(all.stream().map(Finding::getId).toList()).stream()
                .collect(Collectors.groupingBy(l -> l.getId().getFindingId(),
                        Collectors.mapping(l -> l.getId().getEvidenceItemId(), Collectors.toList())));
        return all.stream()
                .sorted(Comparator.comparing(Finding::getSeverity).thenComparing(Finding::getCreatedAt))
                .map(f -> new QueueItemDto(f.getId(), "FINDING", f.getSeverity().name(),
                        f.getStatus().name(), f.isExcluded(), f.getCode(), f.getTitle(),
                        f.getDescription(), f.getReviewNote(), null, null, null, null,
                        evidenceByFinding.getOrDefault(f.getId(), List.of()), f.getCreatedAt()))
                .toList();
    }

    private List<QueueItemDto> extractionItems(UUID auditId) {
        List<QueueItemDto> items = new ArrayList<>();
        boardMembers.findByAuditIdOrderByRoleAscNameAsc(auditId).stream()
                .filter(m -> m.isNeedsReview())
                .forEach(m -> items.add(new QueueItemDto(m.getId(), "BOARD_MEMBER", null,
                        "NEEDS_REVIEW", false, null, m.getName(),
                        "Low-confidence board extraction — check against the snapshot.",
                        null, m.getConfidence(), m.getSnapshotId(), m.getExcerpt(),
                        boardFields(m.getRole(), m.getInstitution(), m.getCountry()),
                        List.of(), m.getCreatedAt())));
        articles.findByAuditIdOrderByCreatedAt(auditId).stream()
                .filter(a -> a.isNeedsReview())
                .forEach(a -> items.add(new QueueItemDto(a.getId(), "ARTICLE", null,
                        "NEEDS_REVIEW", false, null,
                        a.getTitle() == null ? "(untitled article)" : a.getTitle(),
                        "Low-confidence article extraction — check against the snapshot.",
                        null, a.getConfidence(), a.getSnapshotId(), null,
                        articleFields(a.getDoi(), a.getDateSubmitted(), a.getDateAccepted(),
                                a.getDatePublished(), a.getAbstractLanguage()),
                        List.of(), a.getCreatedAt())));
        return items;
    }

    private static Map<String, String> boardFields(String role, String institution, String country) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("role", role);
        map.put("institution", institution);
        map.put("country", country);
        return map;
    }

    private static Map<String, String> articleFields(String doi, String submitted, String accepted,
                                                     String published, String abstractLanguage) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("doi", doi);
        map.put("dateSubmitted", submitted);
        map.put("dateAccepted", accepted);
        map.put("datePublished", published);
        map.put("abstractLanguage", abstractLanguage);
        return map;
    }

    // ------------------------------------------------------------------ finding actions (FR-REV-1)

    public record NoteRequest(String note) {}

    public record ReasonRequest(@NotBlank String reason) {}

    public record SeverityRequest(@NotNull Finding.Severity severity, @NotBlank String reason) {}

    @PostMapping("/findings/{id}/confirm")
    public void confirm(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                        @RequestParam(required = false) UUID auditId,
                        @RequestBody(required = false) NoteRequest request) {
        reviewService.confirm(id, request == null ? null : request.note(), actor(principal), auditId);
    }

    @PostMapping("/findings/{id}/reject")
    public void reject(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                       @RequestParam(required = false) UUID auditId,
                       @Valid @RequestBody ReasonRequest request) {
        reviewService.reject(id, request.reason(), actor(principal), auditId);
    }

    @PostMapping("/findings/{id}/severity")
    public void severity(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                         @RequestParam(required = false) UUID auditId,
                         @Valid @RequestBody SeverityRequest request) {
        reviewService.editSeverity(id, request.severity(), request.reason(), actor(principal), auditId);
    }

    @PostMapping("/findings/{id}/annotate")
    public void annotate(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                         @RequestParam(required = false) UUID auditId,
                         @RequestBody NoteRequest request) {
        reviewService.annotate(id, request.note(), actor(principal), auditId);
    }

    @PostMapping("/findings/{id}/exclude")
    public void exclude(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                        @RequestParam(required = false) UUID auditId,
                        @Valid @RequestBody ReasonRequest request) {
        reviewService.exclude(id, request.reason(), actor(principal), auditId);
    }

    @PostMapping("/findings/{id}/include")
    public void include(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                        @RequestParam(required = false) UUID auditId) {
        reviewService.include(id, actor(principal), auditId);
    }

    // ------------------------------------------------------------------ extraction corrections (FR-REV-2)

    public record BoardCorrectionRequest(String name, String role, String institution,
                                         String country, String note) {}

    public record ArticleCorrectionRequest(String title, String doi, String dateSubmitted,
                                           String dateAccepted, String datePublished,
                                           String abstractLanguage, String note) {}

    @PostMapping("/board-members/{id}/correct")
    public void correctBoardMember(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID id,
                                   @RequestBody BoardCorrectionRequest request) {
        extractionReview.correctBoardMember(id, new ExtractionReviewService.BoardMemberCorrection(
                request.name(), request.role(), request.institution(), request.country(),
                request.note()), actor(principal));
    }

    @PostMapping("/board-members/{id}/confirm")
    public void confirmBoardMember(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID id) {
        extractionReview.confirmBoardMember(id, actor(principal));
    }

    @PostMapping("/articles/{id}/correct")
    public void correctArticle(@AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID id,
                               @RequestBody ArticleCorrectionRequest request) {
        extractionReview.correctArticle(id, new ExtractionReviewService.ArticleCorrection(
                request.title(), request.doi(), request.dateSubmitted(), request.dateAccepted(),
                request.datePublished(), request.abstractLanguage(), request.note()), actor(principal));
    }

    @PostMapping("/articles/{id}/confirm")
    public void confirmArticle(@AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable UUID id) {
        extractionReview.confirmArticle(id, actor(principal));
    }

    // ------------------------------------------------------------------ history + gate (FR-REV-1/4)

    public record DecisionDto(UUID id, String targetType, UUID targetId, String action,
                              String reason, String oldValue, String newValue,
                              String decidedByEmail, Instant createdAt) {}

    @GetMapping("/audits/{auditId}/review/decisions")
    @Transactional(readOnly = true)
    public List<DecisionDto> decisions(@PathVariable UUID auditId) {
        requireAudit(auditId);
        return reviewService.decisions(auditId).stream()
                .map(d -> new DecisionDto(d.getId(), d.getTargetType().name(), d.getTargetId(),
                        d.getAction().name(), d.getReason(), d.getOldValue(), d.getNewValue(),
                        d.getDecidedByEmail(), d.getCreatedAt()))
                .toList();
    }

    public record GateDto(long open, long needsVerification, long excluded, boolean releasable) {}

    @GetMapping("/audits/{auditId}/review/gate")
    @Transactional(readOnly = true)
    public GateDto gate(@PathVariable UUID auditId) {
        requireAudit(auditId);
        ReviewService.ReleaseGate gate = reviewService.gate(auditId);
        return new GateDto(gate.open(), gate.needsVerification(), gate.excluded(), gate.releasable());
    }

    // ------------------------------------------------------------------ snapshot text (FR-REV-2)

    public record SnapshotTextDto(UUID id, String url, String pageType, Instant fetchedAt, String text) {}

    @GetMapping("/snapshots/{id}/text")
    @Transactional(readOnly = true)
    public SnapshotTextDto snapshotText(@PathVariable UUID id) {
        Snapshot snapshot = snapshots.findById(id)
                .filter(s -> s.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("snapshot-not-found", "Snapshot not found"));
        String text = null;
        if (snapshot.getTextStorageKey() != null) {
            try {
                text = new String(snapshotStore.get(snapshot.getTextStorageKey()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                text = null;
            }
        }
        return new SnapshotTextDto(snapshot.getId(), snapshot.getUrl(), snapshot.getPageType(),
                snapshot.getFetchedAt(), text);
    }

    // ------------------------------------------------------------------ manual evidence (FR-INT-7)

    public record ManualEvidenceRequest(@NotBlank String source, @NotBlank String description,
                                        UUID findingId, String contentType, String contentBase64) {}

    @PostMapping("/audits/{auditId}/evidence")
    public Map<String, UUID> attachEvidence(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable UUID auditId,
                                            @Valid @RequestBody ManualEvidenceRequest request) {
        requireAudit(auditId);
        requireAnalyst(principal);
        UUID id = manualEvidence.attach(auditId, new ManualEvidenceService.ManualEvidenceRequest(
                request.source(), request.description(), request.findingId(), request.contentType(),
                request.contentBase64()), actor(principal));
        return Map.of("evidenceItemId", id);
    }

    @GetMapping("/evidence/{id}/content")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> evidenceContent(@PathVariable UUID id) {
        EvidenceItem item = evidenceItems.findById(id)
                .filter(e -> e.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("evidence-not-found", "Evidence not found"));
        if (item.getStorageKey() == null) {
            throw ApiException.notFound("no-content", "This evidence item has no stored payload.");
        }
        byte[] bytes;
        try {
            bytes = snapshotStore.get(item.getStorageKey());
        } catch (Exception e) {
            throw ApiException.notFound("no-content", "The stored payload could not be read.");
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(item.getContentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        // Analyst uploads are served download-only: never rendered same-origin.
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"evidence-" + item.getId() + "\"")
                .contentType(mediaType)
                .body(bytes);
    }

    // ------------------------------------------------------------------ helpers

    private ReviewService.Actor actor(AuthPrincipal principal) {
        requireAnalyst(principal);
        return new ReviewService.Actor(principal.userId(), principal.email());
    }

    /** VIEWERs are read-only in the review workflow (FR-AUTH role model). */
    private static void requireAnalyst(AuthPrincipal principal) {
        if (principal.role() == AppUser.Role.VIEWER) {
            throw ApiException.forbidden("viewer-read-only",
                    "Viewers cannot take review actions; ask an analyst or owner.");
        }
    }

    private Audit requireAudit(UUID id) {
        return audits.findById(id)
                .filter(a -> a.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
    }
}
