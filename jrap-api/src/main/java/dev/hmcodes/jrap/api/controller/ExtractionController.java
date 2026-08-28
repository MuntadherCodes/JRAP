package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.extract.domain.Article;
import dev.hmcodes.jrap.extract.domain.AuthorSlot;
import dev.hmcodes.jrap.extract.domain.BoardMember;
import dev.hmcodes.jrap.extract.repo.ArticleRepository;
import dev.hmcodes.jrap.extract.repo.AuthorSlotRepository;
import dev.hmcodes.jrap.extract.repo.BoardMemberRepository;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Extraction results per audit (FR-EXT-1/2); the review queue UI arrives in Phase 6. */
@RestController
@RequestMapping("/api/v1/audits/{auditId}")
public class ExtractionController {

    private final AuditRepository audits;
    private final BoardMemberRepository boardMembers;
    private final ArticleRepository articles;
    private final AuthorSlotRepository authorSlots;

    public ExtractionController(AuditRepository audits, BoardMemberRepository boardMembers,
                                ArticleRepository articles, AuthorSlotRepository authorSlots) {
        this.audits = audits;
        this.boardMembers = boardMembers;
        this.articles = articles;
        this.authorSlots = authorSlots;
    }

    public record ExtractionSummaryDto(long boardMembers, long boardMembersNeedingReview,
                                       long articles, long articlesNeedingReview) {}

    @GetMapping("/extraction-summary")
    @Transactional(readOnly = true)
    public ExtractionSummaryDto summary(@PathVariable UUID auditId) {
        requireAudit(auditId);
        return new ExtractionSummaryDto(
                boardMembers.countByAuditId(auditId),
                boardMembers.countByAuditIdAndNeedsReviewTrue(auditId),
                articles.countByAuditId(auditId),
                articles.countByAuditIdAndNeedsReviewTrue(auditId));
    }

    public record BoardMemberDto(UUID id, String name, String role, String institution,
                                 String country, String method, BigDecimal confidence,
                                 boolean needsReview) {}

    @GetMapping("/board")
    @Transactional(readOnly = true)
    public List<BoardMemberDto> board(@PathVariable UUID auditId) {
        requireAudit(auditId);
        return boardMembers.findByAuditIdOrderByRoleAscNameAsc(auditId).stream()
                .map(m -> new BoardMemberDto(m.getId(), m.getName(), m.getRole(), m.getInstitution(),
                        m.getCountry(), m.getMethod(), m.getConfidence(), m.isNeedsReview()))
                .toList();
    }

    public record ArticleDto(UUID id, String title, String doi, String datePublished,
                             String dateSubmitted, String dateAccepted, String titleScript,
                             String abstractLanguage, int referencesCount, BigDecimal referencesRomanShare,
                             String method, BigDecimal confidence, boolean needsReview,
                             List<AuthorDto> authors) {}

    public record AuthorDto(int position, String name, String affiliation, String country) {}

    @GetMapping("/articles")
    @Transactional(readOnly = true)
    public List<ArticleDto> articles(@PathVariable UUID auditId) {
        requireAudit(auditId);
        List<Article> list = articles.findByAuditIdOrderByCreatedAt(auditId);
        Map<UUID, List<AuthorSlot>> slotsByArticle = list.isEmpty() ? Map.of()
                : authorSlots.findByArticleIdInOrderByArticleIdAscPositionAsc(
                        list.stream().map(Article::getId).toList())
                .stream().collect(Collectors.groupingBy(AuthorSlot::getArticleId));
        return list.stream().map(a -> new ArticleDto(a.getId(), a.getTitle(), a.getDoi(),
                        a.getDatePublished(), a.getDateSubmitted(), a.getDateAccepted(),
                        a.getTitleScript(), a.getAbstractLanguage(), a.getReferencesCount(),
                        a.getReferencesRomanShare(), a.getMethod(), a.getConfidence(), a.isNeedsReview(),
                        slotsByArticle.getOrDefault(a.getId(), List.of()).stream()
                                .map(s -> new AuthorDto(s.getPosition(), s.getName(),
                                        s.getAffiliation(), s.getCountry()))
                                .toList()))
                .toList();
    }

    private Audit requireAudit(UUID id) {
        return audits.findById(id)
                .filter(a -> a.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
    }
}
