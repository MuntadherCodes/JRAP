package dev.hmcodes.jrap.reporting.service;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.reporting.domain.Report;
import dev.hmcodes.jrap.reporting.export.DocxExporter;
import dev.hmcodes.jrap.reporting.export.ExportModel;
import dev.hmcodes.jrap.reporting.export.HtmlExporter;
import dev.hmcodes.jrap.reporting.export.PdfExporter;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;
import dev.hmcodes.jrap.reporting.model.ReportContent.Sentence;
import dev.hmcodes.jrap.tenancy.repo.OrganisationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a report into the renderer-ready {@link ExportModel} (citation numbering in
 * section order, evidence annex) and dispatches to the FR-RPT-5 format renderers.
 */
@Service
public class ReportExportService {

    public record Export(byte[] bytes, String contentType, String filename) {}

    private final ReportService reportService;
    private final AuditRepository audits;
    private final JournalRepository journals;
    private final OrganisationRepository organisations;
    private final EvidenceItemRepository evidenceItems;

    public ReportExportService(ReportService reportService, AuditRepository audits,
                               JournalRepository journals, OrganisationRepository organisations,
                               EvidenceItemRepository evidenceItems) {
        this.reportService = reportService;
        this.audits = audits;
        this.journals = journals;
        this.organisations = organisations;
        this.evidenceItems = evidenceItems;
    }

    @Transactional(readOnly = true)
    public Export export(UUID reportId, String format) {
        Report report = reportService.requireReport(reportId);
        ExportModel model = model(report);
        String base = "jrap-report-v" + report.getVersion();
        return switch (format) {
            case "html" -> new Export(HtmlExporter.render(model).getBytes(StandardCharsets.UTF_8),
                    "text/html; charset=utf-8", base + ".html");
            case "docx" -> new Export(DocxExporter.render(model),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    base + ".docx");
            case "pdf" -> new Export(PdfExporter.render(model), "application/pdf", base + ".pdf");
            default -> throw ApiException.badRequest("bad-format",
                    "Supported export formats: html, docx, pdf.");
        };
    }

    @Transactional(readOnly = true)
    public ExportModel model(Report report) {
        Audit audit = audits.findById(report.getAuditId())
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
        Journal journal = journals.findById(report.getJournalId())
                .orElseThrow(() -> ApiException.notFound("journal-not-found", "Journal not found"));
        String organisationName = organisations.findById(report.getOrganisationId())
                .map(org -> org.getName()).orElse("—");

        List<Section> sections = reportService.sections(report);

        // Citation numbering: first appearance in section order (FR-RPT-3).
        Map<UUID, Integer> numberByEvidence = new LinkedHashMap<>();
        Map<String, List<Integer>> citationNumbers = new LinkedHashMap<>();
        for (Section section : sections) {
            for (Sentence sentence : section.sentences()) {
                List<Integer> numbers = new ArrayList<>();
                for (UUID evidenceId : sentence.evidenceItemIds() == null ? List.<UUID>of()
                        : sentence.evidenceItemIds()) {
                    numbers.add(numberByEvidence.computeIfAbsent(evidenceId,
                            id -> numberByEvidence.size() + 1));
                }
                if (!numbers.isEmpty()) {
                    citationNumbers.put(sentence.id(), numbers);
                }
            }
        }

        Map<UUID, EvidenceItem> resolved = new LinkedHashMap<>();
        evidenceItems.findAllById(numberByEvidence.keySet())
                .forEach(item -> resolved.put(item.getId(), item));
        List<ExportModel.CitedEvidence> annex = numberByEvidence.entrySet().stream()
                .map(entry -> {
                    EvidenceItem item = resolved.get(entry.getKey());
                    if (item == null) {
                        return new ExportModel.CitedEvidence(entry.getValue(), entry.getKey(),
                                "MISSING", "unresolved evidence", null, null);
                    }
                    return new ExportModel.CitedEvidence(entry.getValue(), item.getId(),
                            item.getType().name(), item.getSource(), item.getExcerpt(),
                            item.getRetrievedAt());
                })
                .sorted(java.util.Comparator.comparingInt(ExportModel.CitedEvidence::number))
                .collect(Collectors.toList());

        return new ExportModel(
                journal.getTitle() == null ? "Untitled journal" : journal.getTitle(),
                organisationName,
                report.getVerdict().name(),
                report.getVersion(),
                report.getStatus() == Report.Status.RELEASED,
                report.getContentHash(),
                report.getCreatedAt(),
                report.getReleasedAt(),
                audit.getRubricVersion() == null ? "—" : audit.getRubricVersion(),
                sections,
                reportService.roadmap(report),
                reportService.exclusions(report),
                citationNumbers,
                annex);
    }
}
