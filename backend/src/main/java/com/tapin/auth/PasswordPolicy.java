package com.tapin.auth;

import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;

import java.nio.charset.StandardCharsets;

/**
 * Minimum 10 characters; at most 72 bytes in UTF-8 because bcrypt ignores (Spring rejects) anything longer.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 10;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    static void validate(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Lozinka mora imati najmanje " + MIN_LENGTH + " znakova.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Lozinka je predugačka (najviše 72 bajta).");
        }
    }
}
