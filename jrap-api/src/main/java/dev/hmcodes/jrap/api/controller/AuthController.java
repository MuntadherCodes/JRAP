package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.api.security.JwtService;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.service.AuthenticationService;
import dev.hmcodes.jrap.tenancy.service.InvitationService;
import dev.hmcodes.jrap.tenancy.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Authentication endpoints (FR-AUTH-1/2). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthenticationService authenticationService;
    private final InvitationService invitationService;
    private final JwtService jwtService;

    public AuthController(RegistrationService registrationService, AuthenticationService authenticationService,
                          InvitationService invitationService, JwtService jwtService) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.invitationService = invitationService;
        this.jwtService = jwtService;
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 200) String organisationName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 10, max = 200) String password,
            @NotBlank @Size(max = 200) String displayName) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> register(@org.springframework.web.bind.annotation.RequestBody
                                      @jakarta.validation.Valid RegisterRequest request) {
        UUID orgId = registrationService.registerOrganisation(
                request.organisationName(), request.email(), request.password(), request.displayName());
        return Map.of("organisationId", orgId);
    }

    public record VerifyEmailRequest(@NotBlank String token) {}

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@RequestBody @jakarta.validation.Valid VerifyEmailRequest request) {
        registrationService.verifyEmail(request.token());
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password, String totpCode) {}

    public record TokenResponse(String accessToken, String refreshToken, UserDto user) {}

    @PostMapping("/login")
    public TokenResponse login(@RequestBody @jakarta.validation.Valid LoginRequest request,
                               HttpServletRequest http) {
        var result = authenticationService.login(
                request.email(), request.password(), request.totpCode(), http.getRemoteAddr());
        return toTokenResponse(result);
    }

    public record RefreshRequest(@NotBlank String refreshToken) {}

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestBody @jakarta.validation.Valid RefreshRequest request,
                                 HttpServletRequest http) {
        var result = authenticationService.refresh(request.refreshToken(), http.getRemoteAddr());
        return toTokenResponse(result);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody @jakarta.validation.Valid RefreshRequest request) {
        authenticationService.logout(request.refreshToken());
    }

    public record AcceptInvitationRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 200) String password,
            @NotBlank @Size(max = 200) String displayName) {}

    @PostMapping("/accept-invitation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptInvitation(@RequestBody @jakarta.validation.Valid AcceptInvitationRequest request) {
        invitationService.acceptInvitation(request.token(), request.password(), request.displayName());
    }

    @PostMapping("/totp/setup")
    public AuthenticationService.TotpSetup setupTotp(@AuthenticationPrincipal AuthPrincipal principal) {
        return authenticationService.setupTotp(principal.userId());
    }

    public record EnableTotpRequest(@NotBlank String code) {}

    @PostMapping("/totp/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enableTotp(@AuthenticationPrincipal AuthPrincipal principal,
                           @RequestBody @jakarta.validation.Valid EnableTotpRequest request) {
        authenticationService.enableTotp(principal.userId(), request.code());
    }

    private TokenResponse toTokenResponse(AuthenticationService.AuthenticatedUser result) {
        return new TokenResponse(jwtService.issueAccessToken(result.user()),
                result.refreshToken(), UserDto.from(result.user()));
    }

    public record UserDto(UUID id, UUID organisationId, String email, String displayName,
                          AppUser.Role role, AppUser.Status status, boolean totpEnabled) {
        public static UserDto from(AppUser u) {
            return new UserDto(u.getId(), u.getOrganisationId(), u.getEmail(), u.getDisplayName(),
                    u.getRole(), u.getStatus(), u.isTotpEnabled());
        }
    }
}
