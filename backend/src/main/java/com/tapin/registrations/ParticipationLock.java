package com.tapin.registrations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single serialisation point for a member's participation in one session: own registration changes (this
 * module) and attendance writes (attendance module, BJJ-19). Both must call {@link #acquire} first in their
 * transaction; it is a PostgreSQL transaction-scoped advisory lock on (session, membership), held until commit.
 * <p>
 * Effect (AC-05, spec §6.4): "member cancels" and "trainer marks PRESENT/ABSENT" for the same member never
 * interleave. Whichever commits first wins and the other sees its result: a cancel after attendance is refused
 * ({@code 409 ATTENDANCE_ALREADY_RECORDED}); attendance after a cancel is recorded on a CANCELLED registration, which
 * the spec allows ("otkazao, ali ipak došao"). It also works when no registration row exists yet (walk-in).
 */
@Component
public class ParticipationLock {

    private final JdbcTemplate jdbc;

    ParticipationLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(long trainingId, long membershipId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class,
                "participation:" + trainingId + ":" + membershipId);
    }
}
