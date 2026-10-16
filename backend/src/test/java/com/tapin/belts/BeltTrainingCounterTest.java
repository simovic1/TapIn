package com.tapin.belts;

import com.tapin.belts.BeltTrainingCounter.Counts;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §9.3: inclusive start, exclusive start of the next period, unassigned before the first one. */
class BeltTrainingCounterTest {

    static LocalDate d(String date) {
        return LocalDate.parse(date);
    }

    @Test
    void periodBoundariesAreInclusiveStartAndExclusiveNextStart() {
        List<LocalDate> starts = List.of(d("2026-01-01"), d("2026-10-16"));
        Counts counts = BeltTrainingCounter.assign(
                List.of(d("2025-12-31"), d("2026-01-01"), d("2026-10-15"), d("2026-10-16"), d("2026-12-01")), starts);

        assertThat(counts.unassigned()).isEqualTo(1);              // 31 Dec, before the first period
        assertThat(counts.perPeriod()).containsExactly(2, 2);      // 1 Jan + 15 Oct | 16 Oct + 1 Dec
        assertThat(counts.total()).isEqualTo(5);
        assertThat(counts.perPeriod().stream().mapToInt(Integer::intValue).sum() + counts.unassigned())
                .isEqualTo(counts.total());
    }

    @Test
    void withoutPeriodsEverythingIsUnassigned() {
        Counts counts = BeltTrainingCounter.assign(List.of(d("2026-03-03"), d("2026-03-05")), List.of());
        assertThat(counts.unassigned()).isEqualTo(2);
        assertThat(counts.perPeriod()).isEmpty();
    }

    @Test
    void promotionDayGoesToTheOldBeltAfterTrainingAndToTheNewBeltBeforeTraining() {
        LocalDate awarded = d("2026-10-15");
        List<LocalDate> training = List.of(awarded);

        assertThat(BeltTrainingCounter.assign(training,
                List.of(d("2026-01-01"), PromotionTiming.AFTER_TRAINING.effectiveFrom(awarded))).perPeriod())
                .containsExactly(1, 0); // AC-22
        assertThat(BeltTrainingCounter.assign(training,
                List.of(d("2026-01-01"), PromotionTiming.BEFORE_TRAINING.effectiveFrom(awarded))).perPeriod())
                .containsExactly(0, 1); // AC-23
    }
}
