package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.tenancy.service.UserAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated user's own profile. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserAdminService userAdminService;

    public MeController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public AuthController.UserDto me(@org.springframework.security.core.annotation.AuthenticationPrincipal
                                     AuthPrincipal principal) {
        return AuthController.UserDto.from(userAdminService.getUser(principal.userId()));
    }
}
