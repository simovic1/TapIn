package com.tapin.attendance;

/** Origin of an attendance change for the audit (spec §7). BULK is set only by the bulk "absent" action. */
public enum AttendanceSource {
    SINGLE,
    BULK,
    HISTORICAL_IMPORT,
    CORRECTION
}
