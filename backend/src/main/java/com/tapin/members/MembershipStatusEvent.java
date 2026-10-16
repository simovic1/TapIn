package com.tapin.members;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** Append-only history of membership status changes. */
@Entity
@Table(name = "membership_status_events")
class MembershipStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "membership_id", nullable = false, updatable = false)
    private Long membershipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", updatable = false)
    private MembershipStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false)
    private MembershipStatus newStatus;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MembershipStatusEvent() {
    }

    MembershipStatusEvent(long membershipId, MembershipStatus oldStatus, MembershipStatus newStatus,
                          Instant effectiveAt, Long actorUserId) {
        this.membershipId = membershipId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.effectiveAt = effectiveAt;
        this.actorUserId = actorUserId;
    }
}
