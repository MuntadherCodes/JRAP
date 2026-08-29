package dev.hmcodes.jrap.platform;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.registry.platform.ActionItem;
import dev.hmcodes.jrap.registry.platform.ActionItemRepository;
import dev.hmcodes.jrap.reporting.domain.Report;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import dev.hmcodes.jrap.reporting.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * FR-DASH-2: turns a report's roadmap into tracked, assignable action items with due
 * dates and completion evidence; the journal dashboard (FR-DASH-1) lists the open ones.
 */
@Service
public class ActionItemService {

    private final ActionItemRepository actions;
    private final ReportService reportService;
    private final Clock clock;

    public ActionItemService(ActionItemRepository actions, ReportService reportService, Clock clock) {
        this.actions = actions;
        this.reportService = reportService;
        this.clock = clock;
    }

    /** Adopts every roadmap action of the report not already tracked for the journal. */
    @Transactional
    public int adoptRoadmap(UUID reportId) {
        Report report = reportService.requireReport(reportId);
        int created = 0;
        for (RoadmapAction action : reportService.roadmap(report)) {
            if (actions.existsByJournalIdAndCatalogueActionId(report.getJournalId(), action.id())) {
                continue;
            }
            actions.save(new ActionItem(UUID.randomUUID(), report.getOrganisationId(),
                    report.getJournalId(), report.getId(), action.id(), action.title(),
                    action.description(), action.phase(), action.tag(),
                    action.completionCriterion(), clock.instant()));
            created++;
        }
        return created;
    }

    @Transactional
    public void assign(UUID actionId, UUID assigneeUserId, LocalDate dueDate) {
        require(actionId).assign(assigneeUserId, dueDate, clock.instant());
    }

    @Transactional
    public void setStatus(UUID actionId, ActionItem.Status status, String note, UUID evidenceId) {
        if (status == ActionItem.Status.DONE && (note == null || note.isBlank())) {
            throw ApiException.badRequest("note-required",
                    "Completing an action needs a completion note (FR-DASH-2).");
        }
        require(actionId).setStatus(status, note, evidenceId, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<ActionItem> forJournal(UUID journalId) {
        return actions.findByJournalIdOrderByCreatedAt(journalId);
    }

    private ActionItem require(UUID actionId) {
        return actions.findById(actionId)
                .orElseThrow(() -> ApiException.notFound("action-not-found", "Action not found"));
    }
}
