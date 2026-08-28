package dev.hmcodes.jrap.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.extract.domain.Article;
import dev.hmcodes.jrap.extract.domain.BoardMember;
import dev.hmcodes.jrap.extract.repo.ArticleRepository;
import dev.hmcodes.jrap.extract.repo.BoardMemberRepository;
import dev.hmcodes.jrap.extract.util.NameNormalizer;
import dev.hmcodes.jrap.review.domain.ReviewDecision;
import dev.hmcodes.jrap.review.repo.ReviewDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FR-REV-2: low-confidence extraction rows (FR-EXT-4) are corrected — or confirmed as-is —
 * by an analyst against the source snapshot. Corrections keep full provenance: the row's
 * previous values land in the immutable decision log, and the corrected row leaves the
 * review queue (needs_review = false).
 */
@Service
public class ExtractionReviewService {

    public record BoardMemberCorrection(String name, String role, String institution,
                                        String country, String note) {}

    public record ArticleCorrection(String title, String doi, String dateSubmitted,
                                    String dateAccepted, String datePublished,
                                    String abstractLanguage, String note) {}

    private final BoardMemberRepository boardMembers;
    private final ArticleRepository articles;
    private final ReviewDecisionRepository decisions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExtractionReviewService(BoardMemberRepository boardMembers, ArticleRepository articles,
                                   ReviewDecisionRepository decisions, ObjectMapper objectMapper,
                                   Clock clock) {
        this.boardMembers = boardMembers;
        this.articles = articles;
        this.decisions = decisions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void correctBoardMember(UUID id, BoardMemberCorrection correction, ReviewService.Actor actor) {
        BoardMember member = boardMembers.findById(id)
                .orElseThrow(() -> ApiException.notFound("board-member-not-found", "Board member not found"));
        Map<String, Object> before = boardMemberJson(member);
        if (notBlank(correction.name())) {
            member.setName(correction.name().trim());
            member.setNormalizedName(NameNormalizer.normalize(correction.name()));
        }
        if (correction.role() != null) {
            member.setRole(blankToNull(correction.role()));
        }
        if (correction.institution() != null) {
            member.setInstitution(blankToNull(correction.institution()));
        }
        if (correction.country() != null) {
            member.setCountry(blankToNull(correction.country()));
        }
        member.setNeedsReview(false);
        log(member.getOrganisationId(), member.getAuditId(), ReviewDecision.TargetType.BOARD_MEMBER,
                id, ReviewDecision.Action.CORRECT, correction.note(), before, boardMemberJson(member), actor);
    }

    @Transactional
    public void correctArticle(UUID id, ArticleCorrection correction, ReviewService.Actor actor) {
        Article article = articles.findById(id)
                .orElseThrow(() -> ApiException.notFound("article-not-found", "Article not found"));
        Map<String, Object> before = articleJson(article);
        if (correction.title() != null) {
            article.setTitle(blankToNull(correction.title()));
        }
        if (correction.doi() != null) {
            article.setDoi(blankToNull(correction.doi()));
        }
        if (correction.dateSubmitted() != null) {
            article.setDateSubmitted(blankToNull(correction.dateSubmitted()));
        }
        if (correction.dateAccepted() != null) {
            article.setDateAccepted(blankToNull(correction.dateAccepted()));
        }
        if (correction.datePublished() != null) {
            article.setDatePublished(blankToNull(correction.datePublished()));
        }
        if (correction.abstractLanguage() != null) {
            article.setAbstractLanguage(blankToNull(correction.abstractLanguage()));
        }
        article.setNeedsReview(false);
        log(article.getOrganisationId(), article.getAuditId(), ReviewDecision.TargetType.ARTICLE,
                id, ReviewDecision.Action.CORRECT, correction.note(), before, articleJson(article), actor);
    }

    /** The extraction was checked against the snapshot and is right as-is. */
    @Transactional
    public void confirmBoardMember(UUID id, ReviewService.Actor actor) {
        BoardMember member = boardMembers.findById(id)
                .orElseThrow(() -> ApiException.notFound("board-member-not-found", "Board member not found"));
        member.setNeedsReview(false);
        log(member.getOrganisationId(), member.getAuditId(), ReviewDecision.TargetType.BOARD_MEMBER,
                id, ReviewDecision.Action.CONFIRM, null, null, boardMemberJson(member), actor);
    }

    @Transactional
    public void confirmArticle(UUID id, ReviewService.Actor actor) {
        Article article = articles.findById(id)
                .orElseThrow(() -> ApiException.notFound("article-not-found", "Article not found"));
        article.setNeedsReview(false);
        log(article.getOrganisationId(), article.getAuditId(), ReviewDecision.TargetType.ARTICLE,
                id, ReviewDecision.Action.CONFIRM, null, null, articleJson(article), actor);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> boardMemberJson(BoardMember member) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", member.getName());
        map.put("role", member.getRole());
        map.put("institution", member.getInstitution());
        map.put("country", member.getCountry());
        return map;
    }

    private static Map<String, Object> articleJson(Article article) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", article.getTitle());
        map.put("doi", article.getDoi());
        map.put("dateSubmitted", article.getDateSubmitted());
        map.put("dateAccepted", article.getDateAccepted());
        map.put("datePublished", article.getDatePublished());
        map.put("abstractLanguage", article.getAbstractLanguage());
        return map;
    }

    private void log(UUID orgId, UUID auditId, ReviewDecision.TargetType targetType, UUID targetId,
                     ReviewDecision.Action action, String reason, Map<String, Object> oldValue,
                     Map<String, Object> newValue, ReviewService.Actor actor) {
        decisions.save(new ReviewDecision(UUID.randomUUID(), orgId, auditId, targetType, targetId,
                action, reason, json(oldValue), json(newValue), actor.userId(), actor.email(),
                clock.instant()));
    }

    private String json(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
