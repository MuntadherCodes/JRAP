package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.platform.ActionItemService;
import dev.hmcodes.jrap.platform.DashboardService;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.platform.ActionItem;
import dev.hmcodes.jrap.registry.platform.AuditSchedule;
import dev.hmcodes.jrap.registry.platform.AuditScheduleRepository;
import dev.hmcodes.jrap.registry.platform.Webhook;
import dev.hmcodes.jrap.registry.platform.WebhookDeliveryRepository;
import dev.hmcodes.jrap.registry.platform.WebhookRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.reporting.domain.Report;
import dev.hmcodes.jrap.reporting.service.ReportService;
import dev.hmcodes.jrap.tenancy.domain.ApiKey;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.repo.ApiKeyRepository;
import dev.hmcodes.jrap.tenancy.service.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Phase-8 platform surface: API keys (FR-AUTH-4), webhooks (§3.2.2), dashboards
 * (FR-DASH-1/4), action tracking (FR-DASH-2), and re-audit schedules (FR-DASH-3).
 * OWNER manages keys/webhooks; VIEWERs read; API keys map onto the same role model.
 */
@RestController
@RequestMapping("/api/v1")
public class PlatformController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeys;
    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository webhookDeliveries;
    private final DashboardService dashboards;
    private final ActionItemService actionItems;
    private final AuditScheduleRepository schedules;
    private final JournalRepository journals;
    private final ReportService reportService;
    private final Clock clock;

    public PlatformController(ApiKeyService apiKeyService, ApiKeyRepository apiKeys,
                              WebhookRepository webhooks, WebhookDeliveryRepository webhookDeliveries,
                              DashboardService dashboards, ActionItemService actionItems,
                              AuditScheduleRepository schedules, JournalRepository journals,
                              ReportService reportService, Clock clock) {
        this.apiKeyService = apiKeyService;
        this.apiKeys = apiKeys;
        this.webhooks = webhooks;
        this.webhookDeliveries = webhookDeliveries;
        this.dashboards = dashboards;
        this.actionItems = actionItems;
        this.schedules = schedules;
        this.journals = journals;
        this.reportService = reportService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ API keys (FR-AUTH-4)

    public record CreateKeyRequest(@NotBlank String name, List<String> scopes,
                                   Integer rateLimitPerMinute) {}

    public record CreatedKeyDto(UUID id, String name, String secret, String prefix,
                                String scopes, int rateLimitPerMinute) {}

    public record KeyDto(UUID id, String name, String prefix, String scopes,
                         int rateLimitPerMinute, Instant createdAt, Instant lastUsedAt,
                         Instant revokedAt) {}

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedKeyDto createKey(@AuthenticationPrincipal AuthPrincipal principal,
                                   @Valid @RequestBody CreateKeyRequest request) {
        requireOwner(principal);
        ApiKeyService.CreatedKey created = apiKeyService.create(principal.organisationId(),
                request.name(), request.scopes(),
                request.rateLimitPerMinute() == null ? 0 : request.rateLimitPerMinute(),
                principal.userId(), principal.email());
        return new CreatedKeyDto(created.key().getId(), created.key().getName(), created.secret(),
                created.key().getPrefix(), created.key().getScopes(),
                created.key().getRateLimitPerMinute());
    }

    @GetMapping("/api-keys")
    @Transactional(readOnly = true)
    public List<KeyDto> listKeys(@AuthenticationPrincipal AuthPrincipal principal) {
        return apiKeys.findByOrganisationIdOrderByCreatedAtDesc(principal.organisationId()).stream()
                .map(k -> new KeyDto(k.getId(), k.getName(), k.getPrefix(), k.getScopes(),
                        k.getRateLimitPerMinute(), k.getCreatedAt(), k.getLastUsedAt(),
                        k.getRevokedAt()))
                .toList();
    }

    @PostMapping("/api-keys/{id}/revoke")
    public void revokeKey(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        requireOwner(principal);
        apiKeyService.revoke(id, principal.organisationId(), principal.userId(), principal.email());
    }

    // ------------------------------------------------------------------ webhooks (§3.2.2)

    public record CreateWebhookRequest(@NotBlank String url, List<String> events) {}

    public record WebhookDto(UUID id, String url, String secret, String events, boolean active,
                             Integer lastStatus, Instant lastDeliveryAt) {}

    @PostMapping("/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public WebhookDto createWebhook(@AuthenticationPrincipal AuthPrincipal principal,
                                    @Valid @RequestBody CreateWebhookRequest request) {
        requireOwner(principal);
        if (!request.url().startsWith("http://") && !request.url().startsWith("https://")) {
            throw ApiException.badRequest("bad-url", "Webhook URLs must be http(s).");
        }
        List<String> events = (request.events() == null || request.events().isEmpty())
                ? List.of("audit.completed")
                : request.events().stream()
                        .filter(e -> e.equals("audit.completed") || e.equals("finding.critical"))
                        .distinct().toList();
        if (events.isEmpty()) {
            throw ApiException.badRequest("bad-events",
                    "Valid events: audit.completed, finding.critical.");
        }
        byte[] secretBytes = new byte[24];
        RANDOM.nextBytes(secretBytes);
        Webhook webhook = new Webhook(UUID.randomUUID(), principal.organisationId(),
                request.url().trim(), HexFormat.of().formatHex(secretBytes),
                "[" + events.stream().map(e -> "\"" + e + "\"")
                        .reduce((a, b) -> a + "," + b).orElse("") + "]",
                clock.instant());
        webhooks.save(webhook);
        return webhookDto(webhook, true);
    }

    @GetMapping("/webhooks")
    @Transactional(readOnly = true)
    public List<WebhookDto> listWebhooks(@AuthenticationPrincipal AuthPrincipal principal) {
        return webhooks.findByOrganisationIdOrderByCreatedAt(principal.organisationId()).stream()
                .map(w -> webhookDto(w, false))
                .toList();
    }

    @PostMapping("/webhooks/{id}/deactivate")
    @Transactional
    public void deactivateWebhook(@AuthenticationPrincipal AuthPrincipal principal,
                                  @PathVariable UUID id) {
        requireOwner(principal);
        webhooks.findById(id)
                .filter(w -> w.getOrganisationId().equals(principal.organisationId()))
                .orElseThrow(() -> ApiException.notFound("webhook-not-found", "Webhook not found"))
                .setActive(false);
    }

    public record DeliveryDto(UUID id, String event, Integer statusCode, boolean ok,
                              Instant attemptedAt) {}

    @GetMapping("/webhooks/{id}/deliveries")
    @Transactional(readOnly = true)
    public List<DeliveryDto> deliveries(@AuthenticationPrincipal AuthPrincipal principal,
                                        @PathVariable UUID id) {
        webhooks.findById(id)
                .filter(w -> w.getOrganisationId().equals(principal.organisationId()))
                .orElseThrow(() -> ApiException.notFound("webhook-not-found", "Webhook not found"));
        return webhookDeliveries.findTop50ByWebhookIdOrderByAttemptedAtDesc(id).stream()
                .map(d -> new DeliveryDto(d.getId(), d.getEvent(), d.getStatusCode(), d.isOk(),
                        d.getAttemptedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ dashboards (FR-DASH-1/4)

    @GetMapping("/journals/{id}/dashboard")
    @Transactional(readOnly = true)
    public DashboardService.JournalDashboard journalDashboard(@PathVariable UUID id) {
        return dashboards.journalDashboard(requireJournal(id));
    }

    @GetMapping("/organisations/current/dashboard")
    @Transactional(readOnly = true)
    public List<DashboardService.PortfolioRow> portfolio() {
        return dashboards.portfolio();
    }

    // ------------------------------------------------------------------ actions (FR-DASH-2)

    @PostMapping("/reports/{id}/adopt-roadmap")
    public java.util.Map<String, Integer> adoptRoadmap(@AuthenticationPrincipal AuthPrincipal principal,
                                                       @PathVariable UUID id) {
        requireAnalyst(principal);
        Report report = reportService.requireReport(id);
        if (!report.getOrganisationId().equals(TenantContext.requireOrganisationId())) {
            throw ApiException.notFound("report-not-found", "Report not found");
        }
        return java.util.Map.of("created", actionItems.adoptRoadmap(id));
    }

    public record AssignRequest(UUID assigneeUserId, LocalDate dueDate) {}

    @PostMapping("/actions/{id}/assign")
    public void assign(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                       @RequestBody AssignRequest request) {
        requireAnalyst(principal);
        actionItems.assign(id, request.assigneeUserId(), request.dueDate());
    }

    public record StatusRequest(ActionItem.Status status, String note, UUID evidenceId) {}

    @PostMapping("/actions/{id}/status")
    public void actionStatus(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                             @RequestBody StatusRequest request) {
        requireAnalyst(principal);
        if (request.status() == null) {
            throw ApiException.badRequest("status-required", "status is required.");
        }
        actionItems.setStatus(id, request.status(), request.note(), request.evidenceId());
    }

    @GetMapping("/journals/{id}/actions")
    @Transactional(readOnly = true)
    public List<ActionItem> actions(@PathVariable UUID id) {
        requireJournal(id);
        return actionItems.forJournal(id);
    }

    // ------------------------------------------------------------------ schedules (FR-DASH-3)

    public record ScheduleRequest(AuditSchedule.Cadence cadence, Instant firstRunAt,
                                  Boolean notifyEmail) {}

    public record ScheduleDto(UUID id, String cadence, Instant nextRunAt, boolean notifyEmail,
                              boolean active, UUID lastAuditId) {}

    @PutMapping("/journals/{id}/schedule")
    @Transactional
    public ScheduleDto upsertSchedule(@AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID id,
                                      @RequestBody ScheduleRequest request) {
        requireAnalyst(principal);
        requireJournal(id);
        if (request.cadence() == null) {
            throw ApiException.badRequest("cadence-required",
                    "cadence: MONTHLY, QUARTERLY, SEMIANNUAL or ANNUAL.");
        }
        Instant firstRun = request.firstRunAt() == null
                ? clock.instant().plusSeconds(60) : request.firstRunAt();
        AuditSchedule schedule = schedules.findByJournalId(id).orElse(null);
        if (schedule == null) {
            schedule = new AuditSchedule(UUID.randomUUID(), principal.organisationId(), id,
                    request.cadence(), firstRun,
                    request.notifyEmail() == null || request.notifyEmail(),
                    principal.userId(), clock.instant());
            schedules.save(schedule);
        } else {
            schedule.setActive(true);
        }
        return scheduleDto(schedule);
    }

    @PostMapping("/journals/{id}/schedule/deactivate")
    @Transactional
    public void deactivateSchedule(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID id) {
        requireAnalyst(principal);
        schedules.findByJournalId(id).ifPresent(s -> s.setActive(false));
    }

    // ------------------------------------------------------------------ helpers

    private static ScheduleDto scheduleDto(AuditSchedule s) {
        return new ScheduleDto(s.getId(), s.getCadence().name(), s.getNextRunAt(),
                s.isNotifyEmail(), s.isActive(), s.getLastAuditId());
    }

    private static WebhookDto webhookDto(Webhook w, boolean includeSecret) {
        return new WebhookDto(w.getId(), w.getUrl(), includeSecret ? w.getSecret() : null,
                w.getEvents(), w.isActive(), w.getLastStatus(), w.getLastDeliveryAt());
    }

    private Journal requireJournal(UUID id) {
        return journals.findById(id)
                .filter(j -> j.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("journal-not-found", "Journal not found"));
    }

    private static void requireOwner(AuthPrincipal principal) {
        if (principal.role() != AppUser.Role.OWNER) {
            throw ApiException.forbidden("owner-only", "Only organisation owners can do this.");
        }
    }

    private static void requireAnalyst(AuthPrincipal principal) {
        if (principal.role() == AppUser.Role.VIEWER) {
            throw ApiException.forbidden("viewer-read-only", "Viewers cannot take this action.");
        }
    }
}
