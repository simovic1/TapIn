package com.tapin.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "invitations")
class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "club_id", nullable = false, updatable = false)
    private Long clubId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private String email;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "inviter_id", updatable = false)
    private Long inviterId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Invitation() {
    }

    Invitation(long clubId, long userId, String email, String tokenHash, Long inviterId, Instant createdAt,
               Instant expiresAt) {
        this.clubId = clubId;
        this.userId = userId;
        this.email = email;
        this.tokenHash = tokenHash;
        this.inviterId = inviterId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    boolean isOpenAt(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    Long getId() { return id; }
    Long getUserId() { return userId; }
    String getEmail() { return email; }
    Instant getExpiresAt() { return expiresAt; }
}
