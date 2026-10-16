package com.tapin.shared.security;

import com.tapin.shared.api.ApiPaths;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Session-cookie authentication with CSRF protection (spec §3.2, §14). The SPA and the API share one origin;
 * the session cookie is {@code HttpOnly} + {@code SameSite=Lax} (see application*.yml), the CSRF token is exposed
 * via the readable {@code XSRF-TOKEN} cookie and must be echoed back in the {@code X-XSRF-TOKEN} header.
 * <p>
 * Security errors are delegated to the MVC {@link HandlerExceptionResolver} so they are rendered by
 * {@code GlobalExceptionHandler} as {@code application/problem+json}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            ApiPaths.V1 + "/system/**",
            ApiPaths.V1 + "/auth/login",
            ApiPaths.V1 + "/auth/invitations/accept",
            ApiPaths.V1 + "/auth/password-reset/**",
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        return http
                .csrf(csrf -> csrf.spa())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> resolver.resolveException(req, res, null, e))
                        .accessDeniedHandler((req, res, e) -> resolver.resolveException(req, res, null, e)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .build();
    }

    /** Delegating encoder (bcrypt by default) so the hashing algorithm can be upgraded later without a migration. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
