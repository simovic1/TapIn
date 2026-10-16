package com.tapin.auth;

import com.tapin.shared.web.ProfilePatch;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/me/profile")
class OwnProfileController {
    private final UserAccountService users;
    private final CurrentUserAssembler current;
    OwnProfileController(UserAccountService users, CurrentUserAssembler current) { this.users = users; this.current = current; }

    @PatchMapping
    CurrentUserResponse update(@AuthenticationPrincipal AuthenticatedUser actor, @RequestBody Map<String, JsonNode> fields) {
        var patch = new ProfilePatch(fields, Set.of("expectedVersion", "phone"));
        users.updateOwnPhone(actor.userId(), patch.version(), patch.text("phone", null));
        return current.assemble(actor);
    }
}
