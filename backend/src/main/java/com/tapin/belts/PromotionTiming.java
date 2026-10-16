package com.tapin.belts;

import java.time.LocalDate;

/**
 * When the belt was awarded relative to that day's training (spec §3.3): the award date stays the real date, only
 * the start of counting differs.
 */
public enum PromotionTiming {
    /** Default: the training on the award day still counts for the previous belt; counting starts the next day. */
    AFTER_TRAINING,
    /** The training on the award day already counts for the new belt. */
    BEFORE_TRAINING;

    LocalDate effectiveFrom(LocalDate awardedOn) {
        return this == AFTER_TRAINING ? awardedOn.plusDays(1) : awardedOn;
    }

    static PromotionTiming of(LocalDate awardedOn, LocalDate effectiveFrom) {
        return awardedOn != null && awardedOn.equals(effectiveFrom) ? BEFORE_TRAINING : AFTER_TRAINING;
    }
}
