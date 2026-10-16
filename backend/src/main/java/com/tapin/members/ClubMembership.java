package com.tapin.members;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * A user's membership in the club. The user is referenced by id only ({@code users} is owned by the auth module).
 * The belt is intentionally not stored here; it is derived from belt periods.
 */
@Entity
@Table(name = "club_memberships")
class ClubMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "club_id", nullable = false)
    private Club club;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "joined_on", nullable = false)
    private LocalDate joinedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "membership_roles", joinColumns = @JoinColumn(name = "membership_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ClubMembership() {
    }

    static ClubMembership active(Club club, long userId, LocalDate joinedOn, Set<Role> roles) {
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("A membership needs at least one role");
        }
        ClubMembership membership = new ClubMembership();
        membership.club = club;
        membership.userId = userId;
        membership.joinedOn = joinedOn;
        membership.status = MembershipStatus.ACTIVE;
        membership.roles = EnumSet.copyOf(roles);
        return membership;
    }

    void grant(Role role) {
        roles.add(role);
    }

    /** @return the status before activation */
    MembershipStatus activate() {
        MembershipStatus previous = status;
        status = MembershipStatus.ACTIVE;
        return previous;
    }

    Long getId() { return id; }
    Long getUserId() { return userId; }
    MembershipStatus getStatus() { return status; }
    LocalDate getJoinedOn() { return joinedOn; }
    Club getClub() { return club; }
    Set<Role> getRoles() { return Set.copyOf(roles); }
}
