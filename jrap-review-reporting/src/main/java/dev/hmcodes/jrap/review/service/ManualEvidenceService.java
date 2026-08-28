package dev.hmcodes.jrap.review.service;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.crawl.store.SnapshotStore;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.EvidenceLink;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.review.domain.ReviewDecision;
import dev.hmcodes.jrap.review.repo.ReviewDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * FR-INT-7 manual-evidence channel: analysts attach externally gathered artefacts
 * (ISSN Portal screenshots per FR-INT-4, Google Scholar profiles, Scopus exports) as
 * first-class evidence items — storable, linkable to findings, and usable by the
 * scorer and report generator exactly like crawled or API evidence.
 */
@Service
public class ManualEvidenceService {

    /** Beta cap for uploaded payloads (decoded bytes). */
    static final int MAX_PAYLOAD_BYTES = 5 * 1024 * 1024;

    public record ManualEvidenceRequest(String source, String description, UUID findingId,
                                        String contentType, String contentBase64) {}

    private final AuditRepository audits;
    private final FindingRepository findings;
    private final EvidenceItemRepository evidenceItems;
    private final EvidenceLinkRepository evidenceLinks;
    private final ReviewDecisionRepository decisions;
    private final SnapshotStore snapshotStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ManualEvidenceService(AuditRepository audits, FindingRepository findings,
                                 EvidenceItemRepository evidenceItems, EvidenceLinkRepository evidenceLinks,
                                 ReviewDecisionRepository decisions, SnapshotStore snapshotStore,
                                 ObjectMapper objectMapper, Clock clock) {
        this.audits = audits;
        this.findings = findings;
        this.evidenceItems = evidenceItems;
        this.evidenceLinks = evidenceLinks;
        this.decisions = decisions;
        this.snapshotStore = snapshotStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public UUID attach(UUID auditId, ManualEvidenceRequest request, ReviewService.Actor actor) {
        if (request.source() == null || request.source().isBlank()) {
            throw ApiException.badRequest("source-required",
                    "Manual evidence must name its source (e.g. 'ISSN Portal screenshot').");
        }
        if (request.description() == null || request.description().isBlank()) {
            throw ApiException.badRequest("description-required",
                    "Manual evidence must describe what it shows.");
        }
        Audit audit = audits.findById(auditId)
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));

        EvidenceItem item = new EvidenceItem(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getJournalId(), EvidenceItem.Type.MANUAL, null,
                request.source().trim(), request.description().trim(), clock.instant(), clock.instant());
        item.setAuditId(auditId);
        item.setUploadedBy(actor.userId());

        if (request.contentBase64() != null && !request.contentBase64().isBlank()) {
            byte[] bytes = decode(request.contentBase64());
            String hash = sha256(bytes);
            item.setStorageKey(snapshotStore.put(auditId.toString(), "manual", hash, bytes));
            item.setContentType(request.contentType() == null || request.contentType().isBlank()
                    ? "application/octet-stream" : request.contentType().trim());
        }
        evidenceItems.save(item);

        if (request.findingId() != null) {
            Finding finding = findings.findById(request.findingId())
                    .orElseThrow(() -> ApiException.notFound("finding-not-found", "Finding not found"));
            evidenceLinks.save(new EvidenceLink(finding.getId(), item.getId(), audit.getOrganisationId()));
        }

        String newValue;
        try {
            newValue = objectMapper.writeValueAsString(java.util.Map.of(
                    "source", request.source().trim(),
                    "finding", request.findingId() == null ? "" : request.findingId().toString()));
        } catch (Exception e) {
            newValue = "{}";
        }
        decisions.save(new ReviewDecision(UUID.randomUUID(), audit.getOrganisationId(), auditId,
                ReviewDecision.TargetType.EVIDENCE, item.getId(), ReviewDecision.Action.ATTACH_EVIDENCE,
                null, null, newValue, actor.userId(), actor.email(), clock.instant()));
        return item.getId();
    }

    private static byte[] decode(String base64) {
        byte[] bytes;
        try {
            // MIME decoder: tolerant of the line-wrapped base64 browsers produce.
            bytes = Base64.getMimeDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad-content", "contentBase64 is not valid base64.");
        }
        if (bytes.length == 0) {
            throw ApiException.badRequest("bad-content", "The uploaded payload is empty.");
        }
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw ApiException.badRequest("payload-too-large",
                    "Manual evidence payloads are capped at " + (MAX_PAYLOAD_BYTES / (1024 * 1024)) + " MB.");
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            // SHA-256 is a mandatory JDK algorithm; fall back to a stable name regardless.
            return HexFormat.of().formatHex(String.valueOf(bytes.length).getBytes(StandardCharsets.UTF_8));
        }
    }
}
