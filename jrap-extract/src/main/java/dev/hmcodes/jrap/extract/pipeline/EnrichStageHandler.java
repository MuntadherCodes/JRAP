package dev.hmcodes.jrap.extract.pipeline;

import dev.hmcodes.jrap.extract.service.ReconciliationService;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.pipeline.AuditStageHandler;
import org.springframework.stereotype.Component;

/** The ENRICH stage: cross-source reconciliation per DOI (FR-EXT-5). */
@Component
public class EnrichStageHandler implements AuditStageHandler {

    private final ReconciliationService reconciliationService;

    public EnrichStageHandler(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Override
    public Audit.Stage stage() {
        return Audit.Stage.ENRICH;
    }

    @Override
    public void run(Audit audit, Journal journal) {
        reconciliationService.run(audit);
    }
}
