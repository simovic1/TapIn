package com.tapin.scheduling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Append-only change log entry of a session ({@code training_session_events}). */
@Entity
@Table(name = "training_session_events")
class SessionEvent {

    enum Type { CREATED, RESCHEDULED, UPDATED, CANCELLED, HELD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    @Column(name = "event_type", nullable = false, updatable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Type eventType;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_values", updatable = false)
    private Map<String, Object> oldValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_values", updatable = false)
    private Map<String, Object> newValues;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected SessionEvent() {
    }

    SessionEvent(long sessionId, Type eventType, Long actorUserId, Map<String, Object> oldValues,
                 Map<String, Object> newValues, Instant occurredAt) {
        this.sessionId = sessionId;
        this.eventType = eventType;
        this.actorUserId = actorUserId;
        this.oldValues = oldValues;
        this.newValues = newValues;
        this.occurredAt = occurredAt;
    }

    Long getId() { return id; }
}
