package com.tapin.members;

import com.tapin.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/members")
class MemberDirectoryController {
    private final MemberDirectoryService service;
    MemberDirectoryController(MemberDirectoryService service) { this.service = service; }

    @GetMapping
    MemberDirectoryService.MemberPage list(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) MembershipStatus membershipStatus, @RequestParam(required = false) Role role,
            @RequestParam(required = false) String belt, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "lastName,asc") String sort) {
        return service.list(q, membershipStatus, role, belt, page, size, sort);
    }

    @GetMapping("/{membershipId}")
    MemberDirectoryService.MemberDetail detail(@PathVariable long membershipId) { return service.detail(membershipId); }

    @PatchMapping("/{membershipId}")
    MemberDirectoryService.MemberDetail update(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long membershipId,
                                               @RequestBody Map<String, JsonNode> fields) {
        return service.update(actor, membershipId, fields);
    }

    record ReplaceRoles(@NotNull @PositiveOrZero Long expectedVersion, @NotEmpty Set<@NotNull Role> roles) {}

    @PutMapping("/{membershipId}/roles")
    MemberDirectoryService.MemberDetail roles(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long membershipId,
                                              @Valid @RequestBody ReplaceRoles request) {
        return service.replaceRoles(actor, membershipId, request.expectedVersion(), request.roles());
    }
}
