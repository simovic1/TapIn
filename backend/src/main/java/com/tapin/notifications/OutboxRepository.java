package com.tapin.notifications;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plain SQL access to {@code email_outbox}. All worker updates are conditional on the worker still holding the
 * lease ({@code locked_by}), so a worker whose lease expired can never overwrite the result of another worker.
 */
@Repository
class OutboxRepository {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final RowMapper<OutboxEntry> rowMapper = this::mapRow;

    OutboxRepository(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /** @return true if inserted, false if a row with the same deduplication key already exists */
    boolean insertIfAbsent(OutboxMessage message, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO email_outbox (event_type, recipient_user_id, recipient_email, session_id, payload,
                                          redact_payload_after_send, deduplication_key, next_attempt_at,
                                          created_at, updated_at, expires_at)
                VALUES (:eventType, :recipientUserId, :recipientEmail, :sessionId, CAST(:payload AS jsonb),
                        :redact, :deduplicationKey, :now, :now, :now, :expiresAt)
                ON CONFLICT (deduplication_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("eventType", message.eventType().name())
                .addValue("recipientUserId", message.recipientUserId())
                .addValue("recipientEmail", message.recipientEmail())
                .addValue("sessionId", message.sessionId())
                .addValue("payload", jsonMapper.writeValueAsString(message.payload()))
                .addValue("redact", message.containsSecret())
                .addValue("deduplicationKey", message.deduplicationKey())
                .addValue("expiresAt", message.expiresAt() == null ? null : ts(message.expiresAt()))
                .addValue("now", ts(now)));
        return inserted == 1;
    }

    /**
     * Atomically claims up to {@code limit} due rows for {@code workerId}. {@code SKIP LOCKED} lets concurrent
     * workers pass over rows another worker is claiming, and the lease keeps them away while it is sending.
     * The attempt is counted at claim time, so a crash during sending still consumes an attempt.
     */
    List<OutboxEntry> claimDue(String workerId, Instant now, Instant leaseUntil, int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM email_outbox
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= :now
                      AND (locked_until IS NULL OR locked_until < :now)
                    ORDER BY next_attempt_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE email_outbox o
                SET locked_by = :workerId, locked_until = :leaseUntil, attempt_count = o.attempt_count + 1,
                    last_attempt_at = :now, updated_at = :now
                FROM due
                WHERE o.id = due.id
                RETURNING o.*
                """, new MapSqlParameterSource()
                .addValue("workerId", workerId)
                .addValue("now", ts(now))
                .addValue("leaseUntil", ts(leaseUntil))
                .addValue("limit", limit), rowMapper);
    }

    boolean markSent(long id, String workerId, Instant now, String providerMessageId) {
        return jdbc.update("""
                UPDATE email_outbox
                SET status = 'SENT', sent_at = :now, provider_message_id = :providerMessageId, last_error = NULL,
                    locked_by = NULL, locked_until = NULL, updated_at = :now,
                    payload = CASE WHEN redact_payload_after_send THEN '{}'::jsonb ELSE payload END
                WHERE id = :id AND locked_by = :workerId AND status = 'PENDING'
                """, params(id, workerId, now).addValue("providerMessageId", providerMessageId)) == 1;
    }

    boolean reschedule(long id, String workerId, Instant now, Instant nextAttemptAt, String error) {
        return jdbc.update("""
                UPDATE email_outbox
                SET next_attempt_at = :nextAttemptAt, last_error = :error,
                    locked_by = NULL, locked_until = NULL, updated_at = :now
                WHERE id = :id AND locked_by = :workerId AND status = 'PENDING'
                """, params(id, workerId, now)
                .addValue("nextAttemptAt", ts(nextAttemptAt))
                .addValue("error", OutboxErrors.sanitize(error))) == 1;
    }

    boolean markFailed(long id, String workerId, Instant now, String error) {
        return jdbc.update("""
                UPDATE email_outbox
                SET status = 'FAILED', last_error = :error, locked_by = NULL, locked_until = NULL, updated_at = :now,
                    payload = CASE WHEN redact_payload_after_send THEN '{}'::jsonb ELSE payload END
                WHERE id = :id AND locked_by = :workerId AND status = 'PENDING'
                """, params(id, workerId, now).addValue("error", OutboxErrors.sanitize(error))) == 1;
    }

    /**
     * Manual re-delivery of a FAILED row with a fresh attempt budget. Redacted rows (secret links) and rows past their
     * {@code expires_at} (a reminder after the training started) cannot be re-sent.
     */
    boolean requeueFailed(long id, Instant now) {
        return jdbc.update("""
                UPDATE email_outbox
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = :now, updated_at = :now
                WHERE id = :id AND status = 'FAILED' AND NOT redact_payload_after_send
                  AND (expires_at IS NULL OR expires_at > :now)
                """, new MapSqlParameterSource().addValue("id", id).addValue("now", ts(now))) == 1;
    }

    Optional<OutboxEntry> findById(long id) {
        return jdbc.query("SELECT * FROM email_outbox WHERE id = :id", Map.of("id", id), rowMapper).stream().findFirst();
    }

    Map<OutboxStatus, Long> countByStatus() {
        Map<OutboxStatus, Long> counts = new java.util.EnumMap<>(OutboxStatus.class);
        for (OutboxStatus status : OutboxStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.query("SELECT status, count(*) AS n FROM email_outbox GROUP BY status", rs -> {
            counts.put(OutboxStatus.valueOf(rs.getString("status")), rs.getLong("n"));
        });
        return counts;
    }

    Optional<Instant> oldestPendingCreatedAt() {
        return jdbc.query("SELECT min(created_at) AS t FROM email_outbox WHERE status = 'PENDING'",
                (rs, n) -> instant(rs, "t")).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private OutboxEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEntry(
                rs.getLong("id"),
                EmailEventType.valueOf(rs.getString("event_type")),
                rs.getLong("recipient_user_id"),
                rs.getString("recipient_email"),
                rs.getObject("session_id", Long.class),
                jsonMapper.readValue(rs.getString("payload"), PAYLOAD_TYPE),
                rs.getBoolean("redact_payload_after_send"),
                rs.getString("deduplication_key"),
                OutboxStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                instant(rs, "next_attempt_at"),
                rs.getString("last_error"),
                rs.getString("locked_by"),
                instant(rs, "locked_until"),
                instant(rs, "sent_at"),
                instant(rs, "expires_at"));
    }

    private static MapSqlParameterSource params(long id, String workerId, Instant now) {
        return new MapSqlParameterSource().addValue("id", id).addValue("workerId", workerId).addValue("now", ts(now));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** PENDING, not currently being sent: FAILED with {@code reason} (e.g. reminders of a cancelled training). */
    int discardPending(long sessionId, EmailEventType type, String reason, Instant now) {
        return jdbc.update("""
                UPDATE email_outbox SET status = 'FAILED', last_error = :reason, updated_at = :now
                WHERE session_id = :session AND event_type = :type AND status = 'PENDING'
                  AND (locked_until IS NULL OR locked_until < :now)
                """, new MapSqlParameterSource("session", sessionId).addValue("type", type.name())
                .addValue("reason", OutboxErrors.sanitize(reason)).addValue("now", ts(now)));
    }

    // ------------------------------------------------------------------ operator view (BJJ-30)

    /** A row as an operator may see it: no payload (it can hold invitation/reset links), sanitized error. */
    record OperatorRow(long id, EmailEventType eventType, OutboxStatus status, long recipientUserId,
                       String recipientEmail, Long sessionId, String deduplicationKey, int attemptCount,
                       Instant createdAt, Instant lastAttemptAt, Instant nextAttemptAt, String lastError,
                       Instant sentAt, String providerMessageId, Instant expiresAt, boolean retryable) {
    }

    List<OperatorRow> findForOperator(Optional<OutboxStatus> status, int page, int size, Instant now) {
        return jdbc.query("""
                SELECT id, event_type, status, recipient_user_id, recipient_email, session_id, deduplication_key,
                       attempt_count, created_at, last_attempt_at, next_attempt_at, last_error, sent_at,
                       provider_message_id, expires_at,
                       (status = 'FAILED' AND NOT redact_payload_after_send
                        AND (expires_at IS NULL OR expires_at > :now)) AS retryable
                FROM email_outbox
                WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                ORDER BY CASE status WHEN 'FAILED' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END, updated_at DESC, id DESC
                LIMIT :size OFFSET :offset
                """, new MapSqlParameterSource("status", status.map(Enum::name).orElse(null))
                .addValue("size", size).addValue("offset", (long) page * size).addValue("now", ts(now)), (rs, n) -> {
            OutboxStatus rowStatus = OutboxStatus.valueOf(rs.getString("status"));
            return new OperatorRow(rs.getLong("id"), EmailEventType.valueOf(rs.getString("event_type")), rowStatus,
                    rs.getLong("recipient_user_id"), rs.getString("recipient_email"),
                    rs.getObject("session_id", Long.class), rs.getString("deduplication_key"),
                    rs.getInt("attempt_count"), instant(rs, "created_at"), instant(rs, "last_attempt_at"),
                    rowStatus == OutboxStatus.PENDING ? instant(rs, "next_attempt_at") : null,
                    OutboxErrors.sanitize(rs.getString("last_error")), instant(rs, "sent_at"),
                    rs.getString("provider_message_id"), instant(rs, "expires_at"),
                    rs.getBoolean("retryable"));
        });
    }

    long countForOperator(Optional<OutboxStatus> status) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM email_outbox WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                """, new MapSqlParameterSource("status", status.map(Enum::name).orElse(null)), Long.class);
        return count == null ? 0 : count;
    }

    /** Queue state for the alert: PENDING rows older than {@code stuckBefore} and the newest error among them. */
    record Backlog(long stuckPending, Instant oldestStuckCreatedAt, String latestError) {
    }

    Backlog backlog(Instant stuckBefore) {
        return jdbc.queryForObject("""
                SELECT count(*) AS n, min(created_at) AS oldest,
                       (SELECT last_error FROM email_outbox
                        WHERE status = 'PENDING' AND created_at < :before AND last_error IS NOT NULL
                        ORDER BY last_attempt_at DESC NULLS LAST, id DESC LIMIT 1) AS latest_error
                FROM email_outbox
                WHERE status = 'PENDING' AND created_at < :before
                """, new MapSqlParameterSource("before", ts(stuckBefore)),
                (rs, n) -> new Backlog(rs.getLong("n"), instant(rs, "oldest"),
                        OutboxErrors.sanitize(rs.getString("latest_error"))));
    }

    /** FAILED rows that changed at or after {@code since} (exhausted retries, permanent errors, stale messages). */
    long failedSince(Instant since) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE status = 'FAILED' AND updated_at >= :since",
                new MapSqlParameterSource("since", ts(since)), Long.class);
        return count == null ? 0 : count;
    }
}
