package com.tapin.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Turns an authenticated principal into a session: new session id (fixation protection), rotated CSRF token, and
 * the security context stored in the session. Used by login and by invitation acceptance.
 */
@Component
class SessionAuthenticator {

    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;

    SessionAuthenticator(SessionAuthenticationStrategy sessionStrategy, SecurityContextRepository contextRepository) {
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
    }

    void establish(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    void establish(AuthenticatedUser user, HttpServletRequest request, HttpServletResponse response) {
        user.eraseCredentials();
        establish(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()), request,
                response);
    }
}
