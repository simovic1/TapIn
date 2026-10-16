package com.tapin.auth;

import java.util.Set;

/** Builds session principals for unit tests in other packages. */
public final class AuthenticatedUserTestFactory {

    private AuthenticatedUserTestFactory() {
    }

    public static AuthenticatedUser withRoles(String... roles) {
        return new AuthenticatedUser(1L, 10L, "test@klub.hr", Set.of(roles), null);
    }
}
