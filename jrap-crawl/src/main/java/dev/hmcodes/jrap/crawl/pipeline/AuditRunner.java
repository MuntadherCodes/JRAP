package dev.hmcodes.jrap.crawl.pipeline;

import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.pipeline.AuditStageHandler;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.tenancy.service.TenantTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single-threaded pipeline worker for the beta deployment: claims the oldest PENDING
 * audit (or resumes a RUNNING one after a restart — NFR-AVL-1) and drives the
 * registered {@link AuditStageHandler}s in stage order, checkpointing the audit's
 * stage between them. Multi-instance claiming (SKIP LOCKED / a broker) is a Phase-9
 * scalability concern behind this same entry point (NFR-SCAL-1).
 */
@Component
public class AuditRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditRunner.class);

    private record Claim(UUID auditId, UUID orgId, UUID journalId) {}

    private final AuditRepository audits;
    private final JournalRepository journals;
    private final Map<Audit.Stage, AuditStageHandler> handlers = new EnumMap<>(Audit.Stage.class);
    private final TenantTx tenantTx;
    private final Clock clock;

    public AuditRunner(AuditRepository audits, JournalRepository journals,
                       List<AuditStageHandler> stageHandlers, TenantTx tenantTx, Clock clock) {
        this.audits = audits;
        this.journals = journals;
        for (AuditStageHandler handler : stageHandlers) {
            this.handlers.put(handler.stage(), handler);
        }
        this.tenantTx = tenantTx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${jrap.crawl.poll-interval-ms:5000}")
    public void poll() {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Audit runner iteration failed", e);
        }
    }

    /** Processes at most one audit to completion. Returns true if an audit was processed. */
    public synchronized boolean runOnce() {
        Claim claim = tenantTx.asSystem(() ->
                audits.findFirstByStatusOrderByCreatedAt(Audit.Status.PENDING)
                        .or(() -> audits.findFirstByStatusOrderByCreatedAt(Audit.Status.RUNNING))
                        .map(audit -> {
                            audit.markRunning(clock.instant());
                            return new Claim(audit.getId(), audit.getOrganisationId(), audit.getJournalId());
                        })
                        .orElse(null));
        if (claim == null) {
            return false;
        }
        TenantContext.setOrganisation(claim.orgId());
        try {
            Journal journal = journals.findById(claim.journalId()).orElseThrow();
            runStages(claim.auditId(), journal);
            return true;
        } catch (Exception e) {
            log.error("Audit {} failed", claim.auditId(), e);
            tenantTx.asSystem(() -> audits.findById(claim.auditId()).ifPresent(a ->
                    a.markFailed(truncate(e.getMessage()), clock.instant())));
            return true;
        } finally {
            TenantContext.clear();
        }
    }

    private void runStages(UUID auditId, Journal journal) {
        while (true) {
            Audit audit = audits.findById(auditId).orElseThrow();
            if (audit.getStatus() != Audit.Status.RUNNING) {
                return; // cancelled or already terminal
            }
            AuditStageHandler handler = handlers.get(audit.getStage());
            if (handler == null) {
                complete(auditId);
                return;
            }
            handler.run(audit, journal);

            Audit after = audits.findById(auditId).orElseThrow();
            if (after.getStatus() != Audit.Status.RUNNING) {
                return; // handler observed a cancellation
            }
            Audit.Stage next = nextHandledStage(after.getStage());
            if (next == null) {
                complete(auditId);
                return;
            }
            tenantTx.asSystem(() -> audits.findById(auditId).ifPresent(a -> a.setStage(next)));
        }
    }

    private Audit.Stage nextHandledStage(Audit.Stage current) {
        Audit.Stage[] stages = Audit.Stage.values();
        for (int i = current.ordinal() + 1; i < stages.length; i++) {
            if (handlers.containsKey(stages[i])) {
                return stages[i];
            }
        }
        return null;
    }

    private void complete(UUID auditId) {
        tenantTx.asSystem(() -> audits.findById(auditId).ifPresent(a -> {
            if (a.getStatus() == Audit.Status.RUNNING) {
                a.markComplete(clock.instant());
            }
        }));
        log.info("Audit {} complete", auditId);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
