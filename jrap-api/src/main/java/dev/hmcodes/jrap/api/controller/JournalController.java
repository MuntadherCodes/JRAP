package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.integrations.dto.SourceAvailability;
import dev.hmcodes.jrap.registry.domain.EvidenceLink;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.domain.JournalIdentityRecord;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import dev.hmcodes.jrap.registry.repo.JournalIdentityRecordRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.registry.service.JournalRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Journal registry endpoints (FR-JRN-1..3). */
@RestController
@RequestMapping("/api/v1/journals")
public class JournalController {

    private final JournalRegistrationService registrationService;
    private final JournalRepository journals;
    private final JournalIdentityRecordRepository identityRecords;
    private final FindingRepository findings;
    private final EvidenceLinkRepository evidenceLinks;
    private final Clock clock;

    public JournalController(JournalRegistrationService registrationService, JournalRepository journals,
                             JournalIdentityRecordRepository identityRecords, FindingRepository findings,
                             EvidenceLinkRepository evidenceLinks, Clock clock) {
        this.registrationService = registrationService;
        this.journals = journals;
        this.identityRecords = identityRecords;
        this.findings = findings;
        this.evidenceLinks = evidenceLinks;
        this.clock = clock;
    }

    public record RegisterJournalRequest(String issn, String url) {}

    public record JournalDto(UUID id, String status, String title, String publisher, String country,
                             String issnL, String issnPrint, String issnOnline, String platform,
                             String homepageUrl, String openalexId, String doajId,
                             boolean inCrossref, boolean inDoaj, Instant createdAt) {
        static JournalDto from(Journal j) {
            return new JournalDto(j.getId(), j.getStatus().name(), j.getTitle(), j.getPublisher(),
                    j.getCountry(), j.getIssnL(), j.getIssnPrint(), j.getIssnOnline(), j.getPlatform(),
                    j.getHomepageUrl(), j.getOpenalexId(), j.getDoajId(), j.isInCrossref(), j.isInDoaj(),
                    j.getCreatedAt());
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ANALYST')")
    @ResponseStatus(HttpStatus.CREATED)
    public JournalDto register(@AuthenticationPrincipal AuthPrincipal principal,
                               @RequestBody @Valid RegisterJournalRequest request) {
        Journal journal = registrationService.register(
                blankToNull(request.issn()), blankToNull(request.url()),
                principal.userId(), principal.email());
        return JournalDto.from(journal);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<JournalDto> list() {
        return journals.findByOrganisationIdOrderByCreatedAtDesc(TenantContext.requireOrganisationId())
                .stream().map(JournalDto::from).toList();
    }

    public record IdentityRecordDto(String source, SourceAvailability availability, String title,
                                    String publisher, String country, String issnPrint,
                                    String issnOnline, String issnL, UUID apiRecordId,
                                    Instant retrievedAt) {}

    public record JournalDetailDto(JournalDto journal, List<IdentityRecordDto> identity) {}

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public JournalDetailDto detail(@PathVariable UUID id) {
        Journal journal = requireJournal(id);
        List<IdentityRecordDto> identity = identityRecords.findByJournalIdOrderBySource(id).stream()
                .map(r -> new IdentityRecordDto(r.getSource(), r.getAvailability(), r.getTitle(),
                        r.getPublisher(), r.getCountry(), r.getIssnPrint(), r.getIssnOnline(),
                        r.getIssnL(), r.getApiRecordId(), r.getRetrievedAt()))
                .toList();
        return new JournalDetailDto(JournalDto.from(journal), identity);
    }

    public record FindingDto(UUID id, String category, String code, Finding.Severity severity,
                             Finding.Status status, String title, String description,
                             String detectorVersion, Instant createdAt, List<UUID> evidenceItemIds) {}

    @GetMapping("/{id}/findings")
    @Transactional(readOnly = true)
    public List<FindingDto> findings(@PathVariable UUID id) {
        requireJournal(id);
        // Sort by semantic severity (enum declaration order), not alphabetically.
        List<Finding> journalFindings = findings.findByJournalId(id).stream()
                .sorted(java.util.Comparator.comparing(Finding::getSeverity)
                        .thenComparing(Finding::getCreatedAt))
                .toList();
        Map<UUID, List<UUID>> evidenceByFinding = evidenceLinks
                .findByIdFindingIdIn(journalFindings.stream().map(Finding::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(l -> l.getId().getFindingId(),
                        Collectors.mapping(l -> l.getId().getEvidenceItemId(), Collectors.toList())));
        return journalFindings.stream()
                .map(f -> new FindingDto(f.getId(), f.getCategory(), f.getCode(), f.getSeverity(),
                        f.getStatus(), f.getTitle(), f.getDescription(), f.getDetectorVersion(),
                        f.getCreatedAt(), evidenceByFinding.getOrDefault(f.getId(), List.of())))
                .toList();
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void archive(@PathVariable UUID id) {
        requireJournal(id).archive(clock.instant());
    }

    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unarchive(@PathVariable UUID id) {
        requireJournal(id).unarchive();
    }

    private Journal requireJournal(UUID id) {
        // Row-level security scopes this lookup to the caller's organisation.
        return journals.findById(id)
                .filter(j -> j.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("journal-not-found", "Journal not found"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
