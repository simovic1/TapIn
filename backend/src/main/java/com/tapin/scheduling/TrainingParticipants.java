package com.tapin.scheduling;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * SPI through which scheduling learns about registrations without depending on the registrations module (which
 * depends on scheduling). Implemented by registrations (BJJ-17/18); until then no implementation exists, so nobody
 * is notified, counts are 0 and every member's state is "not registered".
 */
public interface TrainingParticipants {

    /** Members with an active ({@code REGISTERED}) registration: recipients of cancel/move e-mails (spec §8.1). */
    List<Participant> activeRegistrations(long trainingId);

    /** Number of active registrations per session (staff only, spec §2.2). Missing ids count as 0. */
    default Map<Long, Integer> registeredCounts(Collection<Long> trainingIds) {
        return Map.of();
    }

    /** The signed-in member's own registration/attendance per session. Missing ids = not registered. */
    default Map<Long, MemberState> memberStates(long membershipId, Collection<Long> trainingIds) {
        return Map.of();
    }

    record Participant(long membershipId, long userId, String firstName, String email) {
    }

    /**
     * @param registrationStatus {@code REGISTERED}, {@code CANCELLED} or {@code NONE}
     * @param attendanceStatus   {@code PRESENT}, {@code ABSENT} or {@code UNMARKED}
     */
    record MemberState(String registrationStatus, String attendanceStatus, boolean canRegister,
                       boolean canCancelRegistration) {

        static final MemberState NONE = new MemberState("NONE", "UNMARKED", false, false);
    }
}
