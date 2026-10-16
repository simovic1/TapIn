package com.tapin.auth;

import com.tapin.auth.MembershipAuthorityLookup.MembershipAuthority;
import com.tapin.shared.api.ApiPaths;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import com.tapin.shared.error.RateLimitedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/** Public invitation endpoints (no session needed; CSRF still required). */
@Tag(name = "Auth")
@RestController
@RequestMapping(ApiPaths.V1 + "/auth/invitations")
class InvitationController {

    private final InvitationService invitations;
    private final MembershipAuthorityLookup memberships;
    private final SessionAuthenticator sessions;
    private final CurrentUserAssembler currentUsers;
    private final FailureCounter invalidTokens;
    private final InvitationProperties properties;
    private final Clock clock;

    InvitationController(InvitationService invitations, MembershipAuthorityLookup memberships,
                         SessionAuthenticator sessions, CurrentUserAssembler currentUsers,
                         InvitationProperties properties, Clock clock) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.sessions = sessions;
        this.currentUsers = currentUsers;
        this.properties = properties;
        this.clock = clock;
        this.invalidTokens = new FailureCounter(properties.window());
    }

    record TokenRequest(@NotBlank @Size(min = 32, max = 256) String token) {
    }

    record PreviewResponse(String email, String firstName, String lastName, String clubName, Instant expiresAt) {
    }

    record AcceptRequest(
            @NotBlank @Size(min = 32, max = 256) String token,
            @NotBlank String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 32) String phone) {
    }

    @Operation(summary = "Podaci iz pozivnice prije aktivacije")
    @PostMapping("/preview")
    PreviewResponse preview(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        InvitationService.Preview preview = guarded(http, () -> invitations.preview(request.token()));
        String clubName = memberships.findForUser(preview.userId()).map(MembershipAuthority::clubName).orElse("");
        return new PreviewResponse(preview.email(), preview.firstName(), preview.lastName(), clubName,
                preview.expiresAt());
    }

    @Operation(summary = "Aktivacija pozivnice i postavljanje lozinke; uspostavlja sesiju")
    @PostMapping("/accept")
    CurrentUserResponse accept(@Valid @RequestBody AcceptRequest request, HttpServletRequest http,
                               HttpServletResponse response) {
        AuthenticatedUser user = guarded(http, () -> invitations.accept(new InvitationService.Activation(
                request.token(), request.password(), request.firstName(), request.lastName(), request.phone())));
        sessions.establish(user, http, response);
        return currentUsers.assemble(user);
    }

    /** Counts invalid tokens per IP and answers 429 after too many (token guessing protection). */
    private <T> T guarded(HttpServletRequest http, Supplier<T> action) {
        String key = "ip:" + http.getRemoteAddr();
        Instant now = clock.instant();
        long retryAfter = invalidTokens.retryAfterSeconds(key, properties.maxInvalidTokensPerIp(), now);
        if (retryAfter > 0) {
            throw new RateLimitedException("Previše neuspjelih pokušaja. Pokušajte ponovno kasnije.", retryAfter);
        }
        try {
            return action.get();
        } catch (ApiException e) {
            if (e.errorCode() == ErrorCode.INVITATION_INVALID_OR_EXPIRED) {
                invalidTokens.record(key, now);
            }
            throw e;
        }
    }
}
