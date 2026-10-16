package com.tapin.notifications;

import java.util.regex.Pattern;

/**
 * Makes provider error texts safe to store in {@code last_error}, log and show to an operator (BJJ-30): secrets that
 * an adapter or a mail server might echo back (credentials, tokens, links with a token) are masked. Applied when an
 * error is written and again when it is read, so rows written before this rule existed are covered too.
 */
final class OutboxErrors {

    static final int MAX_ERROR_LENGTH = 2000;

    /** {@code password=...}, {@code token: ...}, {@code Authorization: Bearer ...} and similar key/value pairs. */
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|authorization|auth)(\\s*[=:]\\s*)"
                    + "(bearer\\s+|basic\\s+)?[^\\s,;&\"']+");
    /** Links keep scheme and host only: path, query and fragment may carry an invitation or reset token. */
    private static final Pattern URL = Pattern.compile("(?i)\\b(https?://[^/\\s?#\"']+)[/?#][^\\s\"']*");
    /** Long opaque strings (raw tokens, keys) that are not part of a known pair. */
    private static final Pattern OPAQUE = Pattern.compile("\\b[A-Za-z0-9_\\-]{32,}\\b");

    private OutboxErrors() {
    }

    static String sanitize(String error) {
        if (error == null) {
            return null;
        }
        String safe = SECRET_PAIR.matcher(error).replaceAll("$1$2***");
        safe = URL.matcher(safe).replaceAll("$1/***");
        safe = OPAQUE.matcher(safe).replaceAll("***");
        return safe.length() <= MAX_ERROR_LENGTH ? safe : safe.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }
}
