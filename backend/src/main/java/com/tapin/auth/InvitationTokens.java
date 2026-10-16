package com.tapin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One-time tokens: 256 random bits, URL-safe base64 (43 chars). Only the SHA-256 hash is stored, so a database leak
 * does not reveal usable links. SHA-256 (not bcrypt) is appropriate because the token itself has full entropy.
 */
final class InvitationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private InvitationTokens() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
