package com.tapin.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.function.Supplier;

/**
 * Session-backed context repository that re-validates the session principal against the database on every
 * request: a deactivated account or removed membership loses access immediately (next request = 401), and changed
 * roles apply without a new login. Authorization is therefore never based on stale session data.
 */
class RefreshingSecurityContextRepository implements SecurityContextRepository {

    private final HttpSessionSecurityContextRepository delegate = new HttpSessionSecurityContextRepository();
    private final TapInUserDetailsService users;

    RefreshingSecurityContextRepository(TapInUserDetailsService users) {
        this.users = users;
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        DeferredSecurityContext stored = delegate.loadDeferredContext(request);
        return new DeferredSecurityContext() {
            private SecurityContext refreshed;

            @Override
            public SecurityContext get() {
                if (refreshed == null) {
                    refreshed = refresh(stored);
                }
                return refreshed;
            }

            @Override
            public boolean isGenerated() {
                return stored.isGenerated();
            }
        };
    }

    private SecurityContext refresh(Supplier<SecurityContext> stored) {
        SecurityContext context = stored.get();
        Authentication authentication = context.getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            return context; // anonymous, or a test-provided authentication
        }
        return users.reload(principal.userId())
                .filter(fresh -> fresh.credentialsVersion() == principal.credentialsVersion())
                .map(fresh -> {
                    SecurityContext updated = SecurityContextHolder.createEmptyContext();
                    updated.setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()));
                    fresh.eraseCredentials();
                    return updated;
                })
                .orElseGet(SecurityContextHolder::createEmptyContext);
    }

    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return loadDeferredContext(requestResponseHolder.getRequest()).get();
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        delegate.saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return delegate.containsContext(request);
    }
}
