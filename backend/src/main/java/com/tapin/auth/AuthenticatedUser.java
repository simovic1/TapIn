package com.tapin.auth;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Principal stored in the HTTP session. Carries both identifiers: {@code userId} (account) and
 * {@code membershipId} (club membership, used for all member actions and ownership checks).
 * Roles are re-read from the database on every request ({@link RefreshingSecurityContextRepository}), so a role or
 * status change by an administrator takes effect immediately.
 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long userId;
    private final long membershipId;
    private final String email;
    private final Set<String> roles;
    private String passwordHash;

    AuthenticatedUser(long userId, long membershipId, String email, Set<String> roles, String passwordHash) {
        this.userId = userId;
        this.membershipId = membershipId;
        this.email = email;
        this.roles = Set.copyOf(roles);
        this.passwordHash = passwordHash;
    }

    public long userId() {
        return userId;
    }

    public long membershipId() {
        return membershipId;
    }

    public Set<String> roles() {
        return roles;
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** True when {@code membershipId} is the signed-in user's own membership (ownership check). */
    public boolean ownsMembership(long membershipId) {
        return this.membershipId == membershipId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** Called by Spring Security after authentication: the hash never lives in the session. */
    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AuthenticatedUser that && that.userId == userId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(userId);
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[userId=" + userId + ", membershipId=" + membershipId + ", roles=" + List.copyOf(roles) + "]";
    }
}
