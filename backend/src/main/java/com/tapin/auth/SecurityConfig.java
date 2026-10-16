package com.tapin.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.function.Supplier;

/**
 * Session-cookie authentication with CSRF protection (spec §3.2, §14).
 * <ul>
 *   <li>Session cookie {@code TAPIN_SESSION}: HttpOnly, SameSite=Lax, Secure outside local dev (application*.yml).</li>
 *   <li>CSRF: readable {@code XSRF-TOKEN} cookie (SameSite=Lax, Secure on HTTPS requests) echoed back in
 *       {@code X-XSRF-TOKEN}; required for every state-changing request, login and logout included.</li>
 *   <li>Login changes the session id (fixation protection) and rotates the CSRF token; logout invalidates the
 *       session, deletes the cookie and clears the CSRF token.</li>
 *   <li>Authorization: {@link ApiAuthorizationRules} (per endpoint, mirrors {@code x-roles} in the contract) plus
 *       ownership checks in services.</li>
 * </ul>
 * Security errors are delegated to the MVC exception resolver so they become {@code application/problem+json}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties({LoginAttemptProperties.class, InvitationProperties.class})
public class SecurityConfig {

    public static final String SESSION_COOKIE = "TAPIN_SESSION";
    public static final String LOGOUT_URL = "/api/v1/auth/logout";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
            CookieCsrfTokenRepository csrfTokenRepository,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        return http
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // The SPA handles "return to page after login" itself; no session for anonymous 401s.
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(ApiAuthorizationRules::apply)
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_URL)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(SESSION_COOKIE)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> resolver.resolveException(req, res, null, e))
                        .accessDeniedHandler((req, res, e) -> resolver.resolveException(req, res, null, e)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .build();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Secure is derived from the request (HTTPS directly or X-Forwarded-Proto behind the proxy).
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository(TapInUserDetailsService users) {
        return new RefreshingSecurityContextRepository(users);
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CookieCsrfTokenRepository csrfTokenRepository) {
        CsrfAuthenticationStrategy csrfStrategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
        // Generate the new token right away so the login response already carries the new XSRF-TOKEN cookie;
        // otherwise a POST straight after login (e.g. logout) would have no valid token.
        csrfStrategy.setRequestHandler(new CsrfTokenRequestAttributeHandler() {
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response,
                               Supplier<CsrfToken> csrfToken) {
                super.handle(request, response, csrfToken);
                csrfToken.get();
            }
        });
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), csrfStrategy));
    }

    @Bean
    AuthenticationManager authenticationManager(TapInUserDetailsService users, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /** Delegating encoder ({bcrypt} by default) so the algorithm can be upgraded later without a migration. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
