package com.tapin.members;

import com.tapin.auth.AuthenticatedUser;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Who may grant which roles (spec §2.1, AC-18): only ADMIN assigns TRAINER/ADMIN; a TRAINER may invite with the
 * MEMBER role only. Used by invitations and role management on top of the endpoint rules.
 */
@Component
public class RoleAssignmentPolicy {

    public void assertCanAssign(AuthenticatedUser actor, Set<Role> requested) {
        if (requested.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Potrebna je barem jedna uloga.");
        }
        if (actor.hasRole(Role.ADMIN.name())) {
            return;
        }
        if (actor.hasRole(Role.TRAINER.name()) && requested.equals(Set.of(Role.MEMBER))) {
            return;
        }
        throw new ApiException(ErrorCode.ROLE_ASSIGNMENT_FORBIDDEN,
                "Samo administrator može dodijeliti ulogu trenera ili administratora.");
    }
}
