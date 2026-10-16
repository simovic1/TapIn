package com.tapin.scheduling;

/** Session status (spec §4.3). HELD is not a lock: attendance can still be corrected afterwards. */
public enum TrainingStatus {
    SCHEDULED,
    CANCELLED,
    HELD
}
