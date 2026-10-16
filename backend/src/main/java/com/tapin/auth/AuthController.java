package com.tapin.auth;

import com.tapin.auth.MembershipAuthorityLookup.MembershipAuthority;
import com.tapin.auth.UserAccountService.UserProfile;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;

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
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final LoginAttemptLimiter limiter;
    private final UserAccountService userAccounts;
    private final MembershipAuthorityLookup memberships;

    AuthController(AuthenticationManager authenticationManager, SessionAuthenticationStrategy sessionStrategy,
                   SecurityContextRepository contextRepository, LoginAttemptLimiter limiter,
                   UserAccountService userAccounts, MembershipAuthorityLookup memberships) {
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.limiter = limiter;
        this.userAccounts = userAccounts;
        this.memberships = memberships;
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

        // New session id (fixation protection) + new CSRF token, then persist the context in the session.
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        return currentUser((AuthenticatedUser) authentication.getPrincipal());
    }

    @Operation(summary = "Trenutni korisnik, uloge i članstvo")
    @GetMapping("/me")
    CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return currentUser(user);
    }

    private CurrentUserResponse currentUser(AuthenticatedUser user) {
        UserProfile profile = userAccounts.findProfile(user.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Potrebna je prijava."));
        MembershipAuthority membership = memberships.findForUser(user.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Potrebna je prijava."));
        return new CurrentUserResponse(
                profile.userId(),
                membership.membershipId(),
                profile.email(),
                profile.firstName(),
                profile.lastName(),
                profile.phone(),
                profile.accountStatus(),
                membership.membershipStatus(),
                membership.roles().stream().sorted(Comparator.comparingInt(AuthController::roleOrder)).toList(),
                membership.joinedOn(),
                new CurrentUserResponse.Club(membership.clubName(), membership.clubTimeZone()),
                profile.version());
    }

    private static int roleOrder(String role) {
        return switch (role) {
            case "MEMBER" -> 0;
            case "TRAINER" -> 1;
            default -> 2;
        };
    }
}
