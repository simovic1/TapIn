package com.tapin.attendance;

/** Attendance (spec §6.1). UNMARKED = not decided yet; only PRESENT counts as a training in statistics. */
public enum AttendanceStatus {
    PRESENT,
    ABSENT,
    UNMARKED
}
