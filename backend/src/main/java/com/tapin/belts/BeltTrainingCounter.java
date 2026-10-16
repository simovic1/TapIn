package com.tapin.belts;

import com.tapin.attendance.AttendanceService;
import com.tapin.belts.BeltPeriodRepository.Period;
import com.tapin.scheduling.TrainingService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Training counts per belt period (spec §9.3), shared by the profile and reports. Computed on every read from the
 * latest data - never stored - so a corrected attendance, session or promotion is reflected immediately (AC-24/26).
 * <ul>
 *   <li>A training counts once per member: PRESENT on a HELD (never cancelled) session.</li>
 *   <li>Its club-local date (Europe/Zagreb) decides the belt: {@code from <= date < next.from}; the current belt has
 *       no upper bound. Dates before the first known period are "unassigned" (AC-33).</li>
 *   <li>{@code sum(periods) + unassigned = total} holds by construction: every date goes to exactly one bucket.</li>
 * </ul>
 */
@Component
public class BeltTrainingCounter {

    private final AttendanceService attendance;
    private final TrainingService trainings;

    BeltTrainingCounter(AttendanceService attendance, TrainingService trainings) {
        this.attendance = attendance;
        this.trainings = trainings;
    }

    /** @param counts per period, same order as the (chronological) periods */
    record Counts(int total, int unassigned, List<Integer> perPeriod) {
    }

    @Transactional(readOnly = true)
    Counts count(long membershipId, List<Period> periods) {
        List<LocalDate> dates = new ArrayList<>(trainings.heldLocalDates(attendance.presentSessionIds(membershipId)).values());
        return assign(dates, periods.stream().map(Period::effectiveFromDate).toList());
    }

    /** Pure assignment of training dates to periods given their (ascending) start dates. */
    static Counts assign(List<LocalDate> dates, List<LocalDate> starts) {
        int[] perPeriod = new int[starts.size()];
        int unassigned = 0;
        for (LocalDate date : dates) {
            int index = -1;
            for (int i = 0; i < starts.size(); i++) {
                if (!date.isBefore(starts.get(i))) {
                    index = i; // the last period that started on or before the date
                }
            }
            if (index < 0) {
                unassigned++;
            } else {
                perPeriod[index]++;
            }
        }
        List<Integer> counts = new ArrayList<>();
        for (int c : perPeriod) {
            counts.add(c);
        }
        return new Counts(dates.size(), unassigned, counts);
    }
}
