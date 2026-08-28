package dev.hmcodes.jrap.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import dev.hmcodes.jrap.review.domain.ReviewDecision;
import dev.hmcodes.jrap.review.repo.ReviewDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Analyst actions on findings (FR-REV-1): confirm, reject with reason, edit severity,
 * annotate, and the FR-REV-4 release-gate exclusions. State lands on the finding row;
 * every action also appends an immutable {@link ReviewDecision}. Misconduct-class
 * findings enter as NEEDS_VERIFICATION and only a human confirmation moves them to
 * CONFIRMED (FR-REV-3, CON-6) — there is deliberately no bulk-confirm.
 */
@Service
public class ReviewService {

    /** The acting analyst, resolved by the API layer from the authenticated principal. */
    public record Actor(UUID userId, String email) {}

    public record ReleaseGate(long open, long needsVerification, long excluded, boolean releasable) {}

    private final FindingRepository findings;
    private final AuditRepository audits;
    private final ReviewDecisionRepository decisions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReviewService(FindingRepository findings, AuditRepository audits,
                         ReviewDecisionRepository decisions, ObjectMapper objectMapper, Clock clock) {
        this.findings = findings;
        this.audits = audits;
        this.decisions = decisions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void confirm(UUID findingId, String note, Actor actor, UUID queueAuditId) {
        Finding finding = require(findingId);
        String before = statusJson(finding);
        finding.setStatus(Finding.Status.CONFIRMED);
        applyNote(finding, note, actor);
        log(finding, ReviewDecision.Action.CONFIRM, note, before, statusJson(finding), actor, queueAuditId);
    }

    @Transactional
    public void reject(UUID findingId, String reason, Actor actor, UUID queueAuditId) {
        requireReason(reason, "A rejection must state its reason (FR-REV-1).");
        Finding finding = require(findingId);
        String before = statusJson(finding);
        finding.setStatus(Finding.Status.REJECTED);
        applyNote(finding, reason, actor);
        log(finding, ReviewDecision.Action.REJECT, reason, before, statusJson(finding), actor, queueAuditId);
    }

    @Transactional
    public void editSeverity(UUID findingId, Finding.Severity severity, String reason, Actor actor,
                             UUID queueAuditId) {
        requireReason(reason, "A severity change must state its reason.");
        Finding finding = require(findingId);
        if (finding.getSeverity() == severity) {
            throw ApiException.badRequest("severity-unchanged", "The finding already has that severity.");
        }
        String before = json(Map.of("severity", finding.getSeverity().name()));
        finding.setSeverity(severity);
        finding.markReviewed(actor.userId(), clock.instant());
        log(finding, ReviewDecision.Action.EDIT_SEVERITY, reason, before,
                json(Map.of("severity", severity.name())), actor, queueAuditId);
    }

    @Transactional
    public void annotate(UUID findingId, String note, Actor actor, UUID queueAuditId) {
        requireReason(note, "An annotation cannot be empty.");
        Finding finding = require(findingId);
        String before = finding.getReviewNote() == null ? null
                : json(Map.of("note", finding.getReviewNote()));
        applyNote(finding, note, actor);
        log(finding, ReviewDecision.Action.ANNOTATE, null, before, json(Map.of("note", note)), actor, queueAuditId);
    }

    /** FR-REV-4: exclude a needs-verification finding from release; listed in the report annex. */
    @Transactional
    public void exclude(UUID findingId, String reason, Actor actor, UUID queueAuditId) {
        requireReason(reason, "An exclusion must state its reason — it is listed in the report annex (FR-REV-4).");
        Finding finding = require(findingId);
        if (finding.getStatus() != Finding.Status.NEEDS_VERIFICATION) {
            throw ApiException.badRequest("not-excludable",
                    "Only needs-verification findings can be excluded; confirm or reject the others.");
        }
        finding.exclude(reason);
        finding.markReviewed(actor.userId(), clock.instant());
        log(finding, ReviewDecision.Action.EXCLUDE, reason, json(Map.of("excluded", false)),
                json(Map.of("excluded", true)), actor, queueAuditId);
    }

    @Transactional
    public void include(UUID findingId, Actor actor, UUID queueAuditId) {
        Finding finding = require(findingId);
        if (!finding.isExcluded()) {
            return;
        }
        finding.include();
        finding.markReviewed(actor.userId(), clock.instant());
        log(finding, ReviewDecision.Action.INCLUDE, null, json(Map.of("excluded", true)),
                json(Map.of("excluded", false)), actor, queueAuditId);
    }

    /**
     * Everything reviewable under one audit: the audit's own findings plus journal-level
     * findings (e.g. registration-time identity findings) that a release would inherit.
     */
    @Transactional(readOnly = true)
    public List<Finding> reviewableFindings(Audit audit) {
        List<Finding> result = new ArrayList<>(findings.findByAuditId(audit.getId()));
        findings.findByJournalId(audit.getJournalId()).stream()
                .filter(f -> f.getAuditId() == null)
                .forEach(result::add);
        return result;
    }

    /** FR-REV-4 release gate: zero needs-verification findings, unless explicitly excluded. */
    @Transactional(readOnly = true)
    public ReleaseGate gate(UUID auditId) {
        Audit audit = audits.findById(auditId)
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
        List<Finding> all = reviewableFindings(audit);
        long open = all.stream().filter(f -> f.getStatus() == Finding.Status.AUTO).count();
        long needsVerification = all.stream()
                .filter(f -> f.getStatus() == Finding.Status.NEEDS_VERIFICATION && !f.isExcluded())
                .count();
        long excluded = all.stream().filter(Finding::isExcluded).count();
        return new ReleaseGate(open, needsVerification, excluded, needsVerification == 0);
    }

    @Transactional(readOnly = true)
    public List<ReviewDecision> decisions(UUID auditId) {
        return decisions.findByAuditIdOrderByCreatedAtDesc(auditId);
    }

    // ------------------------------------------------------------------ helpers

    private Finding require(UUID findingId) {
        return findings.findById(findingId)
                .orElseThrow(() -> ApiException.notFound("finding-not-found", "Finding not found"));
    }

    private void applyNote(Finding finding, String note, Actor actor) {
        if (note != null && !note.isBlank()) {
            finding.setReviewNote(note.trim());
        }
        finding.markReviewed(actor.userId(), clock.instant());
    }

    private static void requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason-required", message);
        }
    }

    private String statusJson(Finding finding) {
        return json(Map.of("status", finding.getStatus().name()));
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * The decision's audit linkage: audit-linked findings carry their own audit id;
     * journal-level findings (audit_id null) are logged under the audit whose queue the
     * analyst reviewed them in (the API layer passes it), falling back to the journal's
     * latest audit.
     */
    private void log(Finding finding, ReviewDecision.Action action, String reason,
                     String oldValue, String newValue, Actor actor, UUID queueAuditId) {
        UUID auditId = finding.getAuditId() != null ? finding.getAuditId()
                : queueAuditId != null ? queueAuditId
                : latestAuditId(finding.getJournalId());
        decisions.save(new ReviewDecision(UUID.randomUUID(), finding.getOrganisationId(), auditId,
                ReviewDecision.TargetType.FINDING, finding.getId(), action, reason, oldValue,
                newValue, actor.userId(), actor.email(), clock.instant()));
    }

    private UUID latestAuditId(UUID journalId) {
        return audits.findFirstByJournalIdOrderByCreatedAtDesc(journalId)
                .map(Audit::getId)
                .orElseThrow(() -> ApiException.conflict("no-audit",
                        "Journal-level findings can only be reviewed once an audit exists."));
    }
}
