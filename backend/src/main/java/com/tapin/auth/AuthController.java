package com.tapin.auth;

import com.tapin.shared.api.ApiPaths;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/login} and {@code GET /me}. Logout ({@code POST /auth/logout}) is handled by Spring Security's
 * logout filter (see {@link SecurityConfig}).
 */
@Tag(name = "Auth")
@RestController
@RequestMapping(ApiPaths.V1)
class AuthController {

    private static final String INVALID_CREDENTIALS = "Neispravan e-mail ili lozinka.";

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticator sessions;
    private final LoginAttemptLimiter limiter;
    private final CurrentUserAssembler currentUsers;

    AuthController(AuthenticationManager authenticationManager, SessionAuthenticator sessions,
                   LoginAttemptLimiter limiter, CurrentUserAssembler currentUsers) {
        this.authenticationManager = authenticationManager;
        this.sessions = sessions;
        this.limiter = limiter;
        this.currentUsers = currentUsers;
    }

    record LoginRequest(@NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(max = 128) String password) {
    }

    @Operation(summary = "Prijava e-mailom i lozinkom; uspostavlja sesiju")
    @PostMapping("/auth/login")
    CurrentUserResponse login(@Valid @RequestBody LoginRequest login, HttpServletRequest request,
                              HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        limiter.checkAllowed(login.email(), ip);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login.email().strip(), login.password()));
        } catch (AuthenticationException e) {
            // Same answer for unknown e-mail, wrong password, invited or disabled account (no account enumeration).
            limiter.recordFailure(login.email(), ip);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS);
        }
        limiter.recordSuccess(login.email());

        sessions.establish(authentication, request, response);
        return currentUsers.assemble((AuthenticatedUser) authentication.getPrincipal());
    }

    @Operation(summary = "Trenutni korisnik, uloge i članstvo")
    @GetMapping("/me")
    CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return currentUsers.assemble(user);
    }
}
