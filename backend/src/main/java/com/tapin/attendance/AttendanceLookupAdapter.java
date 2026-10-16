package com.tapin.attendance;

import com.tapin.registrations.AttendanceStatusLookup;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * attendance's implementation of the registrations SPI: registrations reads the recorded status (to refuse own
 * changes after PRESENT/ABSENT) without depending on this module. Runs in the caller's transaction, after the
 * caller took the participation lock, so it always sees the latest committed attendance.
 */
@Component
class AttendanceLookupAdapter implements AttendanceStatusLookup {

    private final AttendanceRepository attendances;

    AttendanceLookupAdapter(AttendanceRepository attendances) {
        this.attendances = attendances;
    }

    @Override
    public String statusFor(long trainingId, long membershipId) {
        return attendances.find(trainingId, membershipId)
                .filter(a -> a.status() != AttendanceStatus.UNMARKED)
                .map(a -> a.status().name())
                .orElse(null);
    }

    @Override
    public Map<Long, String> statusesFor(long membershipId, Collection<Long> trainingIds) {
        Map<Long, String> result = new HashMap<>();
        attendances.recordedOf(membershipId, trainingIds).forEach((id, status) -> result.put(id, status.name()));
        return result;
    }
}
