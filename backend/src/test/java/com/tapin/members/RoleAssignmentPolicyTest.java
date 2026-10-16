package com.tapin.members;

import com.tapin.auth.AuthenticatedUserTestFactory;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC-18: a trainer may only invite members; only an admin assigns TRAINER/ADMIN. */
class RoleAssignmentPolicyTest {

    private final RoleAssignmentPolicy policy = new RoleAssignmentPolicy();

    @Test
    void trainerMayAssignMemberOnly() {
        var trainer = AuthenticatedUserTestFactory.withRoles("TRAINER", "MEMBER");

        assertThatCode(() -> policy.assertCanAssign(trainer, Set.of(Role.MEMBER))).doesNotThrowAnyException();
        for (Set<Role> forbidden : java.util.List.of(Set.of(Role.ADMIN), Set.of(Role.TRAINER),
                Set.of(Role.MEMBER, Role.TRAINER))) {
            assertThatThrownBy(() -> policy.assertCanAssign(trainer, forbidden))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.errorCode())
                                    .isEqualTo(ErrorCode.ROLE_ASSIGNMENT_FORBIDDEN));
        }
    }

    @Test
    void adminMayAssignAnyRole() {
        var admin = AuthenticatedUserTestFactory.withRoles("ADMIN");
        assertThatCode(() -> policy.assertCanAssign(admin, Set.of(Role.ADMIN, Role.TRAINER, Role.MEMBER)))
                .doesNotThrowAnyException();
    }

    @Test
    void memberMayAssignNothing() {
        var member = AuthenticatedUserTestFactory.withRoles("MEMBER");
        assertThatThrownBy(() -> policy.assertCanAssign(member, Set.of(Role.MEMBER))).isInstanceOf(ApiException.class);
    }
}
