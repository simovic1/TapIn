package com.tapin.scheduling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Weekly rule in club-local wall-clock time (spec §4.1). Changes apply to the future only. */
@Entity
@Table(name = "recurring_schedules")
class RecurringSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "club_id", nullable = false)
    private Long clubId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DayOfWeek weekday;

    // Bound as java.time.LocalTime directly: hibernate.jdbc.time_zone=UTC would otherwise shift TIME values by the
    // JVM's 1970 offset. Wall-clock time must stay exactly 18:30.
    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected RecurringSchedule() {
    }

    RecurringSchedule(long clubId, DayOfWeek weekday, LocalTime startTime, LocalTime endTime, LocalDate validFrom,
                      LocalDate validTo, boolean enabled, Long createdByUserId) {
        this.clubId = clubId;
        this.weekday = weekday;
        this.startTime = startTime;
        this.endTime = endTime;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.enabled = enabled;
        this.createdByUserId = createdByUserId;
    }

    /** True if the rule produces an occurrence on {@code date}. */
    boolean occursOn(LocalDate date) {
        return enabled && date.getDayOfWeek() == weekday && !date.isBefore(validFrom)
                && (validTo == null || !date.isAfter(validTo));
    }

    void change(LocalTime startTime, LocalTime endTime, LocalDate validTo, boolean enabled) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.validTo = validTo;
        this.enabled = enabled;
    }

    Long getId() { return id; }
    long getClubId() { return clubId; }
    DayOfWeek getWeekday() { return weekday; }
    LocalTime getStartTime() { return startTime; }
    LocalTime getEndTime() { return endTime; }
    LocalDate getValidFrom() { return validFrom; }
    LocalDate getValidTo() { return validTo; }
    boolean isEnabled() { return enabled; }
    long getVersion() { return version; }
}
