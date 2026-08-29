package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.platform.AdminService;
import dev.hmcodes.jrap.registry.platform.SettingsService;
import dev.hmcodes.jrap.tenancy.domain.Organisation;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-ADM-1/2: the platform-operator console. Every endpoint requires the platform-admin
 * claim (configured via jrap.admin.emails); operations cross tenant boundaries by design
 * and are recorded in the immutable security audit log.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;
    private final SettingsService settings;

    public AdminController(AdminService adminService, SettingsService settings) {
        this.adminService = adminService;
        this.settings = settings;
    }

    @GetMapping("/organisations")
    public List<AdminService.OrgRow> organisations(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return adminService.listOrganisations();
    }

    public record QuotaRequest(int maxJournals) {}

    @PatchMapping("/organisations/{id}/quota")
    public void quota(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                      @RequestBody QuotaRequest request) {
        requireAdmin(principal);
        adminService.updateQuota(id, request.maxJournals(), principal.userId(), principal.email());
    }

    public record StatusRequest(Organisation.Status status) {}

    @PatchMapping("/organisations/{id}/status")
    public void status(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                       @RequestBody StatusRequest request) {
        requireAdmin(principal);
        if (request.status() == null) {
            throw ApiException.badRequest("status-required", "status: ACTIVE or ARCHIVED.");
        }
        adminService.setOrganisationStatus(id, request.status(), principal.userId(),
                principal.email());
    }

    public record TransferRequest(UUID targetOrganisationId) {}

    @PostMapping("/journals/{id}/transfer")
    public void transfer(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                         @RequestBody TransferRequest request) {
        requireAdmin(principal);
        if (request.targetOrganisationId() == null) {
            throw ApiException.badRequest("target-required", "targetOrganisationId is required.");
        }
        adminService.transferJournal(id, request.targetOrganisationId(), principal.userId(),
                principal.email());
    }

    @GetMapping("/settings")
    public Map<String, String> settings(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return settings.all();
    }

    public record SettingRequest(@NotBlank String key, @NotBlank String value) {}

    @PutMapping("/settings")
    public void putSetting(@AuthenticationPrincipal AuthPrincipal principal,
                           @jakarta.validation.Valid @RequestBody SettingRequest request) {
        requireAdmin(principal);
        try {
            settings.put(request.key(), request.value(), principal.userId());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad-value", e.getMessage());
        }
    }

    /** FR-ADM-2: internal status page data — per-source health + AI gateway state. */
    @GetMapping("/status")
    public Map<String, Object> sourceStatus(@AuthenticationPrincipal AuthPrincipal principal) {
        requireAdmin(principal);
        return adminService.sourceStatus();
    }

    private static void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.platformAdmin()) {
            throw ApiException.forbidden("admin-only",
                    "This endpoint is restricted to platform administrators.");
        }
    }
}
