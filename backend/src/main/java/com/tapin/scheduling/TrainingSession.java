package com.tapin.scheduling;

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
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A concrete training session: materialised from a rule ({@code scheduleId + occurrenceDate}, unique) or created
 * manually (both null). Sessions are never deleted; cancelling keeps the row, so a rule occurrence is never
 * materialised again (AC-12).
 */
@Entity
@Table(name = "training_sessions")
class TrainingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "club_id", nullable = false)
    private Long clubId;

    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "occurrence_date")
    private LocalDate occurrenceDate;

    private String title;

    private String note;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrainingStatus status;

    @Column(name = "time_overridden", nullable = false)
    private boolean timeOverridden;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_user_id")
    private Long cancelledByUserId;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_coaches", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "membership_id", nullable = false)
    private Set<Long> coachMembershipIds = new LinkedHashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected TrainingSession() {
    }

    static TrainingSession manual(long clubId, Instant startAt, Instant endAt, String title, String note,
                                  Set<Long> coachMembershipIds, boolean historical, long createdByUserId) {
        TrainingSession session = new TrainingSession();
        session.clubId = clubId;
        session.startAt = startAt;
        session.endAt = endAt;
        session.title = title;
        session.note = note;
        session.coachMembershipIds = new LinkedHashSet<>(coachMembershipIds);
        session.status = historical ? TrainingStatus.HELD : TrainingStatus.SCHEDULED;
        session.createdByUserId = createdByUserId;
        return session;
    }

    /** Moves the session; an occurrence of a rule is then detached from later rule time changes. */
    void moveTo(Instant startAt, Instant endAt, boolean individualOverride) {
        this.startAt = startAt;
        this.endAt = endAt;
        if (individualOverride && scheduleId != null) {
            this.timeOverridden = true;
        }
    }

    void describe(String title, String note) {
        this.title = title;
        this.note = note;
    }

    void assignCoaches(Set<Long> membershipIds) {
        coachMembershipIds.clear();
        coachMembershipIds.addAll(membershipIds);
    }

    void cancel(String reason, Instant at, Long byUserId) {
        status = TrainingStatus.CANCELLED;
        cancellationReason = reason;
        cancelledAt = at;
        cancelledByUserId = byUserId;
    }

    boolean hasStarted(Instant now) {
        return !startAt.isAfter(now);
    }

    Long getId() { return id; }
    long getClubId() { return clubId; }
    Long getScheduleId() { return scheduleId; }
    LocalDate getOccurrenceDate() { return occurrenceDate; }
    String getTitle() { return title; }
    String getNote() { return note; }
    Instant getStartAt() { return startAt; }
    Instant getEndAt() { return endAt; }
    TrainingStatus getStatus() { return status; }
    boolean isTimeOverridden() { return timeOverridden; }
    String getCancellationReason() { return cancellationReason; }
    Long getCreatedByUserId() { return createdByUserId; }
    Set<Long> getCoachMembershipIds() { return Set.copyOf(coachMembershipIds); }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
    long getVersion() { return version; }
}
