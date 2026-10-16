package com.tapin.registrations;

import java.util.Collection;
import java.util.Map;

/**
 * SPI implemented by the attendance module: the recorded attendance of a member ({@code PRESENT}/{@code ABSENT}).
 * No entry (or no implementation) means {@code UNMARKED}. Registrations only reads it; {@code AttendanceService}
 * stays the only writer of attendance (spec §13.2).
 */
public interface AttendanceStatusLookup {

    /** @return {@code PRESENT}, {@code ABSENT} or {@code null} for UNMARKED */
    String statusFor(long trainingId, long membershipId);

    /** Recorded statuses of one member for several sessions; UNMARKED sessions are absent from the map. */
    Map<Long, String> statusesFor(long membershipId, Collection<Long> trainingIds);
}
