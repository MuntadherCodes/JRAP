package dev.hmcodes.jrap.review.pipeline;

import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.pipeline.AuditStageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * REVIEW is a human stage (SRS §4): the automated pipeline hands over to analysts here.
 * Registering this handler makes the runner advance the audit's checkpoint to REVIEW
 * after ANALYSE, so a completed audit rests at stage REVIEW — "evidence and findings
 * ready, awaiting human review". Confirmations, rejections and corrections then happen
 * through the review API at the analyst's pace; the DRAFT → GUARD → RELEASE stages
 * (Phase 7) consume the reviewed state.
 */
@Component
public class ReviewStageHandler implements AuditStageHandler {

    private static final Logger log = LoggerFactory.getLogger(ReviewStageHandler.class);

    @Override
    public Audit.Stage stage() {
        return Audit.Stage.REVIEW;
    }

    @Override
    public void run(Audit audit, Journal journal) {
        log.info("Audit {} entered REVIEW: findings await human review (FR-REV-1)", audit.getId());
    }
}
