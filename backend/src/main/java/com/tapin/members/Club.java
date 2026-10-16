package com.tapin.members;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.ZoneId;

/** The club. The MVP has exactly one row, seeded by V003 (guarded by {@code uk_clubs_single_club}). */
@Entity
@Table(name = "clubs")
class Club {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Club() {
    }

    Long getId() { return id; }
    String getName() { return name; }
    ZoneId getTimeZone() { return ZoneId.of(timeZone); }
}
