package com.tapin.registrations;

import com.tapin.auth.AuthenticatedUser;
import com.tapin.registrations.RegistrationService.MyRegistrationResult;
import com.tapin.shared.api.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Own registration only: the membership always comes from the session, never from the request, so a member cannot
 * act for someone else. MEMBER role is enforced by ApiAuthorizationRules.
 */
@Tag(name = "Registrations")
@RestController
@RequestMapping(ApiPaths.V1 + "/trainings/{trainingId}/my-registration")
class RegistrationController {

    private final RegistrationService service;

    RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @Operation(summary = "Prijava trenutnog člana na trening (idempotentno)")
    @PutMapping
    MyRegistrationResult register(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long trainingId) {
        return service.register(actor, trainingId);
    }

    @Operation(summary = "Otkazivanje vlastite prijave")
    @DeleteMapping
    MyRegistrationResult cancel(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable long trainingId) {
        return service.cancel(actor, trainingId);
    }
}
