package dev.hmcodes.jrap.platform;

import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.crawl.pipeline.AuditService;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.platform.AuditSchedule;
import dev.hmcodes.jrap.registry.platform.AuditScheduleRepository;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.reporting.service.ReportService;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.repo.AppUserRepository;
import dev.hmcodes.jrap.tenancy.service.EmailSender;
import dev.hmcodes.jrap.tenancy.service.TenantTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-DASH-3: fires due re-audit schedules and emails organisation owners when a
 * scheduled audit completes, summarising material changes (score deltas, new
 * critical/high findings) against the previous completed audit.
 */
@Component
public class ScheduledAuditService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledAuditService.class);

    private final AuditScheduleRepository schedules;
    private final AuditRepository audits;
    private final JournalRepository journals;
    private final AppUserRepository users;
    private final AuditService auditService;
    private final ReportService reportService;
    private final EmailSender emailSender;
    private final TenantTx tenantTx;
    private final Clock clock;

    public ScheduledAuditService(AuditScheduleRepository schedules, AuditRepository audits,
                                 JournalRepository journals, AppUserRepository users,
                                 AuditService auditService, ReportService reportService,
                                 EmailSender emailSender, TenantTx tenantTx, Clock clock) {
        this.schedules = schedules;
        this.audits = audits;
        this.journals = journals;
        this.users = users;
        this.auditService = auditService;
        this.reportService = reportService;
        this.emailSender = emailSender;
        this.tenantTx = tenantTx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${jrap.platform.schedule-poll-ms:60000}")
    public void poll() {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Schedule iteration failed", e);
        }
    }

    /** Fires due schedules and sends pending completion notifications. Returns audits started. */
    public synchronized int runOnce() {
        Instant now = clock.instant();
        int started = 0;
        List<AuditSchedule> due = tenantTx.asSystem(() ->
                schedules.findByActiveTrueAndNextRunAtBefore(now));
        for (AuditSchedule schedule : due) {
            TenantContext.setOrganisation(schedule.getOrganisationId());
            try {
                Audit audit = auditService.create(schedule.getJournalId(), schedule.getCreatedBy(),
                        "scheduler");
                started++;
                tenantTx.asSystem(() -> {
                    schedules.findById(schedule.getId()).ifPresent(s -> s.fired(audit.getId(), now));
                    return null;
                });
            } catch (Exception e) {
                // e.g. an audit already in flight: retry at the next poll, don't advance.
                log.info("Schedule {} not fired: {}", schedule.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        notifyCompleted();
        return started;
    }

    private void notifyCompleted() {
        List<AuditSchedule> candidates = tenantTx.asSystem(schedules::findByActiveTrueAndLastAuditIdIsNotNull);
        for (AuditSchedule schedule : candidates) {
            if (!schedule.isNotifyEmail()
                    || schedule.getLastAuditId().equals(schedule.getLastNotifiedAuditId())) {
                continue;
            }
            TenantContext.setOrganisation(schedule.getOrganisationId());
            try {
                Audit audit = audits.findById(schedule.getLastAuditId()).orElse(null);
                if (audit == null || audit.getStatus() != Audit.Status.COMPLETE) {
                    continue; // still running — check again next poll
                }
                String body = buildEmail(schedule, audit);
                for (AppUser user : users.findByOrganisationIdOrderByCreatedAt(
                        schedule.getOrganisationId())) {
                    if (user.getRole() == AppUser.Role.OWNER) {
                        emailSender.send(user.getEmail(), "JRAP scheduled audit completed", body);
                    }
                }
                tenantTx.asSystem(() -> {
                    schedules.findById(schedule.getId())
                            .ifPresent(s -> s.notified(audit.getId()));
                    return null;
                });
            } catch (Exception e) {
                log.warn("Notification for schedule {} failed: {}", schedule.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** Completion summary with material changes vs the previous completed audit (FR-DASH-3). */
    private String buildEmail(AuditSchedule schedule, Audit audit) {
        String title = journals.findById(schedule.getJournalId())
                .map(Journal::getTitle).orElse("your journal");
        StringBuilder body = new StringBuilder("The scheduled audit of ").append(title)
                .append(" completed at ").append(audit.getFinishedAt()).append(".\n");
        UUID previous = previousCompletedAudit(schedule.getJournalId(), audit.getId());
        if (previous != null) {
            try {
                ReportService.Delta delta = reportService.delta(audit.getId(), previous);
                boolean material = false;
                for (ReportService.ScoreDelta score : delta.scores()) {
                    if (score.previous() != null && score.current() != null
                            && !score.previous().equals(score.current())) {
                        body.append("Score change — ").append(score.category()).append(": ")
                                .append(score.previous()).append(" -> ").append(score.current())
                                .append("\n");
                        material = true;
                    }
                }
                if (!delta.newCodes().isEmpty()) {
                    body.append("New findings: ").append(String.join(", ", delta.newCodes()))
                            .append("\n");
                    material = true;
                }
                if (!material) {
                    body.append("No material changes against the previous audit.\n");
                }
            } catch (Exception e) {
                body.append("Delta against the previous audit was not computable.\n");
            }
        }
        return body.toString();
    }

    private UUID previousCompletedAudit(UUID journalId, UUID excludeAuditId) {
        return audits.findByJournalIdOrderByCreatedAtDesc(journalId).stream()
                .filter(a -> a.getStatus() == Audit.Status.COMPLETE)
                .filter(a -> !a.getId().equals(excludeAuditId))
                .map(Audit::getId)
                .findFirst()
                .orElse(null);
    }
}
