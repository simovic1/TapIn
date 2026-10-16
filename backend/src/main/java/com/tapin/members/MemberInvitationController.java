package com.tapin.members;

import com.tapin.auth.AuthenticatedUser;
import com.tapin.members.MemberInvitationService.InvitationResult;
import com.tapin.members.MemberInvitationService.InviteCommand;
import com.tapin.shared.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.Set;

/** Endpoint access (TRAINER/ADMIN) is enforced by ApiAuthorizationRules; role limits by RoleAssignmentPolicy. */
@Tag(name = "Members")
@RestController
@RequestMapping(ApiPaths.V1 + "/members")
class MemberInvitationController {

    private final MemberInvitationService service;

    MemberInvitationController(MemberInvitationService service) {
        this.service = service;
    }

    record InviteMemberRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotEmpty Set<Role> roles,
            LocalDate joinedOn) {
    }

    @Operation(summary = "Pozivanje novog člana")
    @PostMapping("/invitations")
    ResponseEntity<InvitationResult> invite(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @Valid @RequestBody InviteMemberRequest request) {
        InvitationResult result = service.invite(actor, new InviteCommand(request.email(), request.firstName(),
                request.lastName(), request.roles(), request.joinedOn()));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/members/" + result.membershipId())).body(result);
    }

    @Operation(summary = "Ponovno slanje pozivnice")
    @PostMapping("/{membershipId}/invitations/resend")
    ResponseEntity<InvitationResult> resend(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable long membershipId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.resend(actor, membershipId));
    }
}
