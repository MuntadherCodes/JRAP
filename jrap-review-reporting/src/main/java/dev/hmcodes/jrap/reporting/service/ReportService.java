package dev.hmcodes.jrap.reporting.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.analysis.domain.CsabScore;
import dev.hmcodes.jrap.analysis.domain.GatewayCheck;
import dev.hmcodes.jrap.analysis.repo.CsabScoreRepository;
import dev.hmcodes.jrap.analysis.repo.GatewayCheckRepository;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.reporting.domain.Report;
import dev.hmcodes.jrap.reporting.model.ReportContent;
import dev.hmcodes.jrap.reporting.model.ReportContent.Exclusion;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;
import dev.hmcodes.jrap.reporting.model.ReportContent.Sentence;
import dev.hmcodes.jrap.reporting.repo.ReportRepository;
import dev.hmcodes.jrap.review.service.ReviewService;
import dev.hmcodes.jrap.tenancy.service.SecurityAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Report lifecycle (FR-RPT-1/2/4/5/7): generate a versioned draft (deterministic build
 * + optional LLM narrative + guard), let analysts edit guard-failing sentences, and
 * release — which re-checks the FR-REV-4 gate and re-runs the guard against fresh data,
 * then hash-stamps and freezes the report (DB trigger enforces immutability). The audit's
 * stage tracks the report lifecycle: DRAFT → GUARD (guard passed) → RELEASE.
 */
@Service
public class ReportService {

    private static final TypeReference<List<Section>> SECTIONS = new TypeReference<>() {};

    private final ReportRepository reports;
    private final AuditRepository audits;
    private final JournalRepository journals;
    private final ReportBuilder builder;
    private final DraftingService drafting;
    private final ReviewService reviewService;
    private final EvidenceLinkRepository evidenceLinks;
    private final EvidenceItemRepository evidenceItems;
    private final GatewayCheckRepository gatewayChecks;
    private final CsabScoreRepository scores;
    private final SecurityAuditService securityAudit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReportService(ReportRepository reports, AuditRepository audits, JournalRepository journals,
                         ReportBuilder builder, DraftingService drafting, ReviewService reviewService,
                         EvidenceLinkRepository evidenceLinks, EvidenceItemRepository evidenceItems,
                         GatewayCheckRepository gatewayChecks, CsabScoreRepository scores,
                         SecurityAuditService securityAudit,
                         ObjectMapper objectMapper, Clock clock) {
        this.reports = reports;
        this.audits = audits;
        this.journals = journals;
        this.builder = builder;
        this.drafting = drafting;
        this.reviewService = reviewService;
        this.evidenceLinks = evidenceLinks;
        this.evidenceItems = evidenceItems;
        this.gatewayChecks = gatewayChecks;
        this.scores = scores;
        this.securityAudit = securityAudit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ generation

    @Transactional
    public Report generate(UUID auditId, ReviewService.Actor actor) {
        Audit audit = requireAudit(auditId);
        if (audit.getStatus() != Audit.Status.COMPLETE) {
            throw ApiException.conflict("audit-not-finished",
                    "Reports are generated from completed audits.");
        }
        Journal journal = journals.findById(audit.getJournalId())
                .orElseThrow(() -> ApiException.notFound("journal-not-found", "Journal not found"));

        ReportBuilder.Build build = builder.build(audit, journal);
        List<Section> sections = new ArrayList<>(build.sections());

        // FR-RPT-2: optional LLM narrative from confirmed findings only.
        Map<UUID, List<UUID>> confirmedEvidence = evidenceLinks
                .findByIdFindingIdIn(build.confirmedFindings().stream().map(Finding::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(l -> l.getId().getFindingId(),
                        Collectors.mapping(l -> l.getId().getEvidenceItemId(), Collectors.toList())));
        DraftingService.Draft narrative = drafting.draft(audit, build.confirmedFindings(), confirmedEvidence);
        if (narrative != null) {
            sections.add(1, narrative.section());
        }

        SentenceGuard.Result guard = SentenceGuard.check(sections, build.guardContext());
        sections = markGuard(sections, guard);

        int version = reports.findFirstByAuditIdOrderByVersionDesc(auditId)
                .map(r -> r.getVersion() + 1).orElse(1);
        Report report = new Report(UUID.randomUUID(), audit.getOrganisationId(), auditId,
                journal.getId(), version, build.verdict(), actor.userId(), clock.instant());
        report.setSections(json(sections));
        report.setRoadmap(json(build.roadmap()));
        report.setExclusions(json(build.exclusions()));
        report.setGuardReport(json(guard), guard.passed());
        if (narrative != null) {
            report.setNarrativePromptVersion(narrative.promptVersion());
        }
        reports.save(report);

        audit.setStage(guard.passed() ? Audit.Stage.GUARD : Audit.Stage.DRAFT);
        return report;
    }

    // ------------------------------------------------------------------ sentence editing (FR-RPT-4)

    @Transactional
    public Report editSentence(UUID reportId, String sentenceId, String newText, boolean remove) {
        Report report = requireReport(reportId);
        if (report.getStatus() != Report.Status.DRAFT) {
            throw ApiException.conflict("report-released", "Released reports are immutable (FR-RPT-5).");
        }
        List<Section> sections = sections(report);
        Sentence target = sections.stream()
                .flatMap(s -> s.sentences().stream())
                .filter(s -> s.id().equals(sentenceId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("sentence-not-found", "Sentence not found"));
        Sentence replacement = remove ? null : target.withText(requireText(newText));
        sections = ReportContent.copyWithSentence(sections, sentenceId, replacement);

        Audit audit = requireAudit(report.getAuditId());
        Journal journal = journals.findById(report.getJournalId()).orElseThrow();
        SentenceGuard.Result guard = SentenceGuard.check(sections, freshContext(audit, journal));
        report.setSections(json(markGuard(sections, guard)));
        report.setGuardReport(json(guard), guard.passed());
        if (guard.passed() && audit.getStage() == Audit.Stage.DRAFT) {
            audit.setStage(Audit.Stage.GUARD);
        }
        return report;
    }

    // ------------------------------------------------------------------ release (FR-REV-4, FR-RPT-5)

    @Transactional
    public Report release(UUID reportId, ReviewService.Actor actor) {
        Report report = requireReport(reportId);
        if (report.getStatus() != Report.Status.DRAFT) {
            throw ApiException.conflict("already-released", "This report version is already released.");
        }
        ReviewService.ReleaseGate gate = reviewService.gate(report.getAuditId());
        if (!gate.releasable()) {
            throw ApiException.conflict("needs-verification-open",
                    gate.needsVerification() + " finding(s) remain in needs-verification; confirm,"
                            + " reject, or explicitly exclude them before release (FR-REV-4).");
        }
        // Re-run the guard against CURRENT data so a stale draft cannot slip out.
        Audit audit = requireAudit(report.getAuditId());
        Journal journal = journals.findById(report.getJournalId()).orElseThrow();
        List<Section> sections = sections(report);
        SentenceGuard.Result guard = SentenceGuard.check(sections, freshContext(audit, journal));
        if (!guard.passed()) {
            // No writes here: the exception rolls the transaction back, so persisted marks
            // would be lost anyway. The stored per-sentence marks from generation stand.
            throw ApiException.conflict("guard-failed",
                    guard.failures().size() + " sentence(s) fail the citation guard; edit or remove"
                            + " them before release (FR-RPT-4).");
        }
        String hash = sha256(report.getVerdict().name() + "\n" + report.getSections() + "\n"
                + report.getRoadmap() + "\n" + report.getExclusions());
        report.release(hash, actor.userId(), clock.instant());
        audit.setStage(Audit.Stage.RELEASE);
        // FR-AUTH-5: report release is a security-relevant event.
        securityAudit.record("report.released", report.getOrganisationId(), actor.userId(),
                actor.email(), java.util.Map.of("reportId", report.getId().toString(),
                        "auditId", report.getAuditId().toString(),
                        "contentHash", hash), null);
        return report;
    }

    // ------------------------------------------------------------------ delta (FR-RPT-7)

    public record ScoreDelta(String category, Integer previous, Integer current) {}

    public record GatewayDelta(String code, String previous, String current) {}

    public record Delta(UUID auditId, UUID priorAuditId, List<ScoreDelta> scores,
                        List<GatewayDelta> gateway, List<String> resolvedCodes,
                        List<String> newCodes) {}

    @Transactional(readOnly = true)
    public Delta delta(UUID auditId, UUID priorAuditId) {
        Audit current = requireAudit(auditId);
        Audit prior = requireAudit(priorAuditId);
        if (!current.getJournalId().equals(prior.getJournalId())) {
            throw ApiException.badRequest("different-journals",
                    "Delta reports compare audits of the same journal.");
        }
        Map<String, Integer> prevScores = scores.findByAuditIdOrderByCategory(priorAuditId).stream()
                .collect(Collectors.toMap(CsabScore::getCategory, CsabScore::getScore));
        Map<String, Integer> currScores = scores.findByAuditIdOrderByCategory(auditId).stream()
                .collect(Collectors.toMap(CsabScore::getCategory, CsabScore::getScore));
        List<ScoreDelta> scoreDeltas = new ArrayList<>();
        java.util.TreeSet<String> categories = new java.util.TreeSet<>(prevScores.keySet());
        categories.addAll(currScores.keySet());
        for (String category : categories) {
            scoreDeltas.add(new ScoreDelta(category, prevScores.get(category), currScores.get(category)));
        }

        Map<String, String> prevGateway = gatewayChecks.findByAuditIdOrderByCode(priorAuditId).stream()
                .collect(Collectors.toMap(GatewayCheck::getCode, GatewayCheck::getOutcome));
        Map<String, String> currGateway = gatewayChecks.findByAuditIdOrderByCode(auditId).stream()
                .collect(Collectors.toMap(GatewayCheck::getCode, GatewayCheck::getOutcome));
        List<GatewayDelta> gatewayDeltas = new ArrayList<>();
        java.util.TreeSet<String> codes = new java.util.TreeSet<>(prevGateway.keySet());
        codes.addAll(currGateway.keySet());
        for (String code : codes) {
            gatewayDeltas.add(new GatewayDelta(code, prevGateway.get(code), currGateway.get(code)));
        }

        Set<String> prevCodes = reviewService.reviewableFindings(prior).stream()
                .filter(f -> !f.isExcluded() && f.getStatus() != Finding.Status.REJECTED)
                .map(Finding::getCode).collect(Collectors.toSet());
        Set<String> currCodes = reviewService.reviewableFindings(current).stream()
                .filter(f -> !f.isExcluded() && f.getStatus() != Finding.Status.REJECTED)
                .map(Finding::getCode).collect(Collectors.toSet());
        List<String> resolved = prevCodes.stream().filter(c -> !currCodes.contains(c)).sorted().toList();
        List<String> fresh = currCodes.stream().filter(c -> !prevCodes.contains(c)).sorted().toList();

        return new Delta(auditId, priorAuditId, scoreDeltas, gatewayDeltas, resolved, fresh);
    }

    // ------------------------------------------------------------------ reads + parsing

    @Transactional(readOnly = true)
    public Report requireReport(UUID reportId) {
        return reports.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("report-not-found", "Report not found"));
    }

    @Transactional(readOnly = true)
    public List<Report> forAudit(UUID auditId) {
        requireAudit(auditId);
        return reports.findByAuditIdOrderByVersionDesc(auditId);
    }

    public List<Section> sections(Report report) {
        try {
            return objectMapper.readValue(report.getSections(), SECTIONS);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable report sections for " + report.getId(), e);
        }
    }

    public List<RoadmapAction> roadmap(Report report) {
        try {
            return objectMapper.readValue(report.getRoadmap(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Exclusion> exclusions(Report report) {
        try {
            return objectMapper.readValue(report.getExclusions(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ helpers

    private SentenceGuard.Context freshContext(Audit audit, Journal journal) {
        List<Finding> reviewable = reviewService.reviewableFindings(audit);
        Set<UUID> reportable = reviewable.stream()
                .filter(f -> !f.isExcluded())
                .filter(f -> f.getStatus() == Finding.Status.CONFIRMED
                        || f.getStatus() == Finding.Status.AUTO)
                .map(Finding::getId).collect(Collectors.toSet());
        Set<UUID> confirmed = reviewable.stream()
                .filter(f -> !f.isExcluded() && f.getStatus() == Finding.Status.CONFIRMED)
                .map(Finding::getId).collect(Collectors.toSet());
        Set<UUID> evidence = evidenceItems.findByJournalId(journal.getId()).stream()
                .map(EvidenceItem::getId).collect(Collectors.toSet());
        return new SentenceGuard.Context(reportable, confirmed, evidence);
    }

    private static List<Section> markGuard(List<Section> sections, SentenceGuard.Result guard) {
        Map<String, String> failing = guard.failures().stream()
                .collect(Collectors.toMap(SentenceGuard.Failure::sentenceId,
                        SentenceGuard.Failure::reason, (a, b) -> a));
        List<Section> out = new ArrayList<>();
        for (Section section : sections) {
            out.add(new Section(section.id(), section.title(), section.sentences().stream()
                    .map(s -> s.withGuard(failing.containsKey(s.id()) ? "FAIL" : "PASS"))
                    .toList()));
        }
        return out;
    }

    private Audit requireAudit(UUID auditId) {
        return audits.findById(auditId)
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
    }

    private static String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("text-required", "The edited sentence cannot be empty.");
        }
        return text.trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Report serialisation failed", e);
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
