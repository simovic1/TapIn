package com.tapin.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

interface TrainingSessionRepository extends JpaRepository<TrainingSession, Long> {

    /** Sessions starting in {@code [from, to)}, optionally filtered by status, ordered by start. */
    @Query("select s from TrainingSession s where s.startAt >= :from and s.startAt < :to "
            + "and s.status in :statuses order by s.startAt, s.id")
    List<TrainingSession> findStartingBetween(@Param("from") Instant from, @Param("to") Instant to,
                                              @Param("statuses") Collection<TrainingStatus> statuses);

    /** Active (not cancelled) sessions overlapping {@code [startAt, endAt)}, excluding {@code exceptId}. */
    @Query("select s from TrainingSession s where s.status <> com.tapin.scheduling.TrainingStatus.CANCELLED "
            + "and s.startAt < :endAt and s.endAt > :startAt and s.id <> :exceptId order by s.startAt")
    List<TrainingSession> findActiveOverlapping(@Param("startAt") Instant startAt, @Param("endAt") Instant endAt,
                                                @Param("exceptId") long exceptId);

    /** Future, still scheduled occurrences of a rule whose time was not changed individually (decision D-02). */
    @Query("select s from TrainingSession s where s.scheduleId = :scheduleId "
            + "and s.status = com.tapin.scheduling.TrainingStatus.SCHEDULED and s.timeOverridden = false "
            + "and s.startAt > :now order by s.startAt")
    List<TrainingSession> findFollowingRule(@Param("scheduleId") long scheduleId, @Param("now") Instant now);
}
