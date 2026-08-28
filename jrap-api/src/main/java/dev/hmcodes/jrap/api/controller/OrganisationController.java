package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.domain.Organisation;
import dev.hmcodes.jrap.tenancy.repo.OrganisationRepository;
import dev.hmcodes.jrap.tenancy.repo.SecurityAuditLogRepository;
import dev.hmcodes.jrap.tenancy.service.InvitationService;
import dev.hmcodes.jrap.tenancy.service.UserAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tenant-scoped organisation management (FR-AUTH-1, FR-AUTH-5). */
@RestController
@RequestMapping("/api/v1/organisations/current")
public class OrganisationController {

    private final OrganisationRepository organisations;
    private final UserAdminService userAdminService;
    private final InvitationService invitationService;
    private final SecurityAuditLogRepository auditLog;

    public OrganisationController(OrganisationRepository organisations, UserAdminService userAdminService,
                                  InvitationService invitationService, SecurityAuditLogRepository auditLog) {
        this.organisations = organisations;
        this.userAdminService = userAdminService;
        this.invitationService = invitationService;
        this.auditLog = auditLog;
    }

    public record OrganisationDto(UUID id, String name, Organisation.Status status, Instant createdAt) {}

    @GetMapping
    @Transactional(readOnly = true)
    public OrganisationDto current() {
        Organisation org = organisations.findById(TenantContext.requireOrganisationId())
                .orElseThrow(() -> ApiException.notFound("organisation-not-found", "Organisation not found"));
        return new OrganisationDto(org.getId(), org.getName(), org.getStatus(), org.getCreatedAt());
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('OWNER', 'ANALYST')")
    public List<AuthController.UserDto> users() {
        return userAdminService.listUsers().stream().map(AuthController.UserDto::from).toList();
    }

    public record InviteRequest(@NotBlank @Email String email, @NotNull AppUser.Role role) {}

    @PostMapping("/invitations")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> invite(@AuthenticationPrincipal AuthPrincipal principal,
                                    @RequestBody @Valid InviteRequest request) {
        UUID userId = invitationService.invite(request.email(), request.role(),
                principal.userId(), principal.email());
        return Map.of("userId", userId);
    }

    public record ChangeRoleRequest(@NotNull AppUser.Role role) {}

    @PatchMapping("/users/{userId}/role")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@AuthenticationPrincipal AuthPrincipal principal,
                           @PathVariable UUID userId,
                           @RequestBody @Valid ChangeRoleRequest request) {
        userAdminService.changeRole(userId, request.role(), principal.userId(), principal.email());
    }

    @DeleteMapping("/users/{userId}")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableUser(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID userId) {
        userAdminService.disableUser(userId, principal.userId(), principal.email());
    }

    public record AuditLogEntryDto(Long id, Instant occurredAt, UUID actorUserId, String actorEmail,
                                   String eventType, String details, String sourceIp) {}

    @GetMapping("/audit-log")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional(readOnly = true)
    public List<AuditLogEntryDto> auditLog() {
        return auditLog.findTop100ByOrganisationIdOrderByOccurredAtDesc(TenantContext.requireOrganisationId())
                .stream()
                .map(e -> new AuditLogEntryDto(e.getId(), e.getOccurredAt(), e.getActorUserId(),
                        e.getActorEmail(), e.getEventType(), e.getDetails(), e.getSourceIp()))
                .toList();
    }
}
