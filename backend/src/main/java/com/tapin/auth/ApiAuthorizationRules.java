package com.tapin.auth;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Endpoint-level authorization for {@code /api/v1}, one rule per contract operation group. It mirrors the
 * {@code x-roles} of {@code api/openapi.yaml} and is verified against it by {@code ApiAuthorizationContractTest}, so
 * every endpoint is protected even before it is implemented. Ownership ("only my own data") is enforced in services
 * on top of this (e.g. {@code /me/...} always uses the session's membershipId; {@code /members/{id}/...} is staff
 * only, so changing an ID in the URL never exposes another member's data).
 * <p>
 * Roles are additive and there is no role hierarchy: ADMIN gets every trainer capability through explicit rules,
 * and self-registration requires MEMBER, so a TRAINER or ADMIN without MEMBER cannot register themselves (§2.1).
 */
final class ApiAuthorizationRules {

    static final String MEMBER = "MEMBER";
    static final String TRAINER = "TRAINER";
    static final String ADMIN = "ADMIN";
    private static final String[] STAFF = {TRAINER, ADMIN};

    private static final String API = "/api/v1";

    private ApiAuthorizationRules() {
    }

    static void apply(AuthorizeHttpRequestsConfigurer<?>.AuthorizationManagerRequestMatcherRegistry auth) {
        auth
                // ---- public
                .requestMatchers(HttpMethod.GET, API + "/system/**").permitAll()
                .requestMatchers(HttpMethod.POST,
                        API + "/auth/login",
                        API + "/auth/invitations/preview",
                        API + "/auth/invitations/accept",
                        API + "/auth/password-reset/request",
                        API + "/auth/password-reset/confirm").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole(ADMIN)
                .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/error").permitAll()

                // ---- MEMBER only: own registration (TRAINER/ADMIN need the MEMBER role too)
                .requestMatchers(HttpMethod.PUT, API + "/trainings/*/my-registration").hasRole(MEMBER)
                .requestMatchers(HttpMethod.DELETE, API + "/trainings/*/my-registration").hasRole(MEMBER)

                // ---- ADMIN only: accounts, roles, membership status, operations (e-mail queue)
                .requestMatchers(HttpMethod.PATCH, API + "/members/*").hasRole(ADMIN)
                .requestMatchers(HttpMethod.PUT, API + "/members/*/roles").hasRole(ADMIN)
                .requestMatchers(API + "/admin/**").hasRole(ADMIN)

                // ---- TRAINER or ADMIN: members, belts, other members' statistics
                .requestMatchers(HttpMethod.GET, API + "/members").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.POST, API + "/members/invitations").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.POST, API + "/members/*/invitations/resend").hasAnyRole(STAFF)
                .requestMatchers(API + "/members/*/belts", API + "/members/*/belts/**").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.GET,
                        API + "/members/*",
                        API + "/members/*/statistics",
                        API + "/members/*/attendance").hasAnyRole(STAFF)

                // ---- TRAINER or ADMIN: schedule management, roster, attendance, reports
                .requestMatchers(HttpMethod.GET, API + "/trainings/coaches").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.POST, API + "/trainings").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.PATCH, API + "/trainings/*").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.POST, API + "/trainings/*/cancel").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.POST, API + "/recurring-schedules").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.PATCH, API + "/recurring-schedules/*").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.GET, API + "/trainings/*/roster").hasAnyRole(STAFF)
                .requestMatchers(API + "/trainings/*/attendance/**").hasAnyRole(STAFF)
                .requestMatchers(API + "/reports/**").hasAnyRole(STAFF)

                // ---- any signed-in user: /me, schedule reading, ...
                .requestMatchers(API + "/**").authenticated()

                // ---- everything else (unknown paths) is closed
                .anyRequest().denyAll();
    }
}
