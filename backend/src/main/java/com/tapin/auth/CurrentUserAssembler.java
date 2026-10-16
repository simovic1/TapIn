package com.tapin.auth;

import com.tapin.auth.MembershipAuthorityLookup.MembershipAuthority;
import com.tapin.auth.UserAccountService.UserProfile;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/** Builds the contract's {@code CurrentUser} for /me, login and invitation acceptance. */
@Component
class CurrentUserAssembler {

    private final UserAccountService userAccounts;
    private final MembershipAuthorityLookup memberships;

    CurrentUserAssembler(UserAccountService userAccounts, MembershipAuthorityLookup memberships) {
        this.userAccounts = userAccounts;
        this.memberships = memberships;
    }

    CurrentUserResponse assemble(AuthenticatedUser user) {
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
                membership.roles().stream().sorted(Comparator.comparingInt(CurrentUserAssembler::roleOrder)).toList(),
                membership.joinedOn(),
                new CurrentUserResponse.Club(membership.clubName(), membership.clubTimeZone()),
                profile.version(),
                profile.trainingRemindersEnabled());
    }

    private static int roleOrder(String role) {
        return switch (role) {
            case "MEMBER" -> 0;
            case "TRAINER" -> 1;
            default -> 2;
        };
    }
}
