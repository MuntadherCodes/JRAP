package dev.hmcodes.jrap.extract.pipeline;

import dev.hmcodes.jrap.extract.service.ExtractService;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.pipeline.AuditStageHandler;
import org.springframework.stereotype.Component;

/** The EXTRACT stage (FR-EXT-1..4, 6). */
@Component
public class ExtractStageHandler implements AuditStageHandler {

    private final ExtractService extractService;

    public ExtractStageHandler(ExtractService extractService) {
        this.extractService = extractService;
    }

    @Override
    public Audit.Stage stage() {
        return Audit.Stage.EXTRACT;
    }

    @Override
    public void run(Audit audit, Journal journal) {
        extractService.run(audit, journal);
    }
}
