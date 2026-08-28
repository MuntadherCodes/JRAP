package dev.hmcodes.jrap.registry.pipeline;

import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;

/**
 * One pipeline stage (SRS §4: RESOLVE → CRAWL → EXTRACT → ENRICH → ANALYSE → ...).
 * Modules contribute handlers for the stages they own; the runner executes them in
 * enum order and checkpoints the audit's stage between them, so an interrupted audit
 * resumes at its current stage (NFR-AVL-1) and modules stay decoupled (NFR-MNT-1).
 * Handlers MUST be idempotent — a resumed stage re-runs from its own checkpoints.
 */
public interface AuditStageHandler {

    Audit.Stage stage();

    void run(Audit audit, Journal journal);
}
