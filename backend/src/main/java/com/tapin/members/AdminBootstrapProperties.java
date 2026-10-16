package com.tapin.members;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Initial administrator, created on startup only while the club has no active administrator.
 * Values come from the environment ({@code ADMIN_EMAIL}, {@code ADMIN_PASSWORD}, ...); there is no default password.
 * After the first successful start the variables can (and in production should) be removed.
 */
@ConfigurationProperties(prefix = "tapin.bootstrap.admin")
public record AdminBootstrapProperties(
        @DefaultValue("") String email,
        @DefaultValue("") String password,
        @DefaultValue("Administrator") String firstName,
        @DefaultValue("TapIn") String lastName) {

    public static final int MIN_PASSWORD_LENGTH = 12;

    boolean isConfigured() {
        return !email.isBlank();
    }

    /** Never print the password, also not via the record's generated toString(). */
    @Override
    public String toString() {
        return "AdminBootstrapProperties[email=" + email + ", password=****, firstName=" + firstName
                + ", lastName=" + lastName + "]";
    }
}
