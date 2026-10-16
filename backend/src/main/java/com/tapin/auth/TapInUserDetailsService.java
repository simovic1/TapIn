package com.tapin.auth;

import com.tapin.auth.MembershipAuthorityLookup.MembershipAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Loads sign-in capable users: account ACTIVE and a club membership with at least one role.
 * INVITED/DISABLED accounts and users without membership cannot sign in; the login endpoint reports all failures
 * identically so it does not reveal which accounts exist.
 */
@Service
class TapInUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;
    private final MembershipAuthorityLookup memberships;

    TapInUserDetailsService(UserAccountRepository users, MembershipAuthorityLookup memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedUser loadUserByUsername(String email) {
        return users.findByEmailIgnoreCase(email.strip())
                .flatMap(this::toPrincipal)
                .orElseThrow(() -> new UsernameNotFoundException("No sign-in capable user"));
    }

    /** Fresh principal for an already authenticated user, or empty if they may no longer use the app. */
    @Transactional(readOnly = true)
    Optional<AuthenticatedUser> reload(long userId) {
        return users.findById(userId).flatMap(this::toPrincipal);
    }

    private Optional<AuthenticatedUser> toPrincipal(UserAccount user) {
        if (user.getAccountStatus() != AccountStatus.ACTIVE || user.getPasswordHash() == null) {
            return Optional.empty();
        }
        Optional<MembershipAuthority> membership = memberships.findForUser(user.getId());
        if (membership.isEmpty() || membership.get().roles().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedUser(user.getId(), membership.get().membershipId(), user.getEmail(),
                membership.get().roles(), user.getPasswordHash()).credentialsVersion(user.getCredentialsVersion()));
    }
}
