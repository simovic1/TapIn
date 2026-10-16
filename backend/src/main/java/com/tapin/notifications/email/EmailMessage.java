package com.tapin.notifications.email;

/**
 * A rendered e-mail ready for a provider.
 *
 * @param messageId      RFC 5322 Message-ID, stable for all attempts of one outbox row, so recipients/clients
 *                       that deduplicate by Message-ID can collapse a duplicate delivery
 * @param idempotencyKey stable key for providers that support request idempotency (HTTP APIs); equals the outbox
 *                       deduplication key
 */
public record EmailMessage(String to, String subject, String textBody, String messageId, String idempotencyKey) {
}
