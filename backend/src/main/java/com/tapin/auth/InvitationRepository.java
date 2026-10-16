package com.tapin.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    /** Revokes every open invitation of the user (called before issuing a new one). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Invitation i set i.revokedAt = :now
            where i.userId = :userId and i.acceptedAt is null and i.revokedAt is null
            """)
    int revokeOpen(long userId, Instant now);

    /**
     * Atomically consumes an open invitation. Returns 1 for exactly one caller even under concurrent accepts;
     * 0 if it is already accepted, revoked or expired.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Invitation i set i.acceptedAt = :now
            where i.id = :id and i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now
            """)
    int consume(long id, Instant now);
}
