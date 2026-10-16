package com.tapin.reporting;

import com.tapin.belts.BeltService;
import com.tapin.belts.BeltService.BeltHistory;
import com.tapin.members.MembershipDirectory;
import com.tapin.reporting.StatisticsRepository.HeldWhileActive;
import com.tapin.reporting.StatisticsRepository.Member;
import com.tapin.reporting.StatisticsRepository.PresentCounts;
import com.tapin.reporting.StatisticsRepository.RegistrationCounts;
import com.tapin.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A member's own statistics (spec §9.1, BJJ-31, decision D-05). Computed on every read in one read-only
 * transaction, so a corrected attendance, a moved or cancelled session, a historical entry or a corrected promotion
 * is reflected immediately. Ownership is decided by the caller: {@code /me/...} always passes the session's own
 * membershipId, {@code /members/{id}/...} is TRAINER/ADMIN only.
 * <p>
 * <b>D-05 - periods and UNMARKED.</b>
 * <ul>
 *   <li>Periods are club-local calendar dates of the training (Europe/Zagreb), today included: "this month" = from
 *       the 1st of the current month, "last 7 days" = today and the 6 days before, "last 30 days" = today and the
 *       29 days before. The exact dates are returned in {@code windows}.</li>
 *   <li>Only PRESENT on a HELD session is a training. UNMARKED (no decision yet) is neither a training nor an
 *       absence: it is not in {@code registeredButAbsent}, it is excluded from the registration-based rate and shown
 *       separately as {@code registrations.unmarked}. In the main rate a HELD session while active counts in the
 *       denominator whatever its marking, because the basis is "sessions the member could have attended".</li>
 *   <li>Empty denominator: {@code rate = null}, never a division error.</li>
 * </ul>
 */
@Service
public class MemberStatisticsService {

    public record Windows(LocalDate today, LocalDate thisMonthFrom, LocalDate last7DaysFrom, LocalDate last30DaysFrom) {
    }

    public record Registrations(int total, int present, int absent, int unmarked) {
    }

    public record AttendanceRate(int numerator, int denominator, Double rate, String basis) {
        static AttendanceRate of(int numerator, int denominator, String basis) {
            return new AttendanceRate(numerator, denominator,
                    denominator == 0 ? null : (double) numerator / denominator, basis);
        }
    }

    public record BeltPeriodStats(long beltPeriodId, String belt, LocalDate awardedOn, LocalDate effectiveFromDate,
                                  LocalDate effectiveToDate, int trainingsCount) {
    }

    public record MemberStatistics(long membershipId, long userId, Instant asOf, Windows windows, LocalDate memberSince,
                                   int totalPresent, int presentThisMonth, int presentLast7Days, int presentLast30Days,
                                   int registeredButAbsent, Registrations registrations, AttendanceRate attendanceRate,
                                   AttendanceRate registrationAttendanceRate, String currentBelt,
                                   LocalDate currentBeltAwardedOn, int currentBeltTrainings, int unassignedTrainings,
                                   List<BeltPeriodStats> beltPeriods) {
    }

    public record HistoryEntry(long trainingId, LocalDate localDate, Instant startAt, String title,
                               String trainingStatus, String registrationStatus, String attendanceStatus) {
    }

    public record PageMetadata(int page, int size, long totalElements, int totalPages) {
    }

    public record HistoryPage(List<HistoryEntry> items, PageMetadata page) {
    }

    private final StatisticsRepository repository;
    private final BeltService belts;
    private final MembershipDirectory directory;
    private final Clock clock;

    MemberStatisticsService(StatisticsRepository repository, BeltService belts, MembershipDirectory directory,
                            Clock clock) {
        this.repository = repository;
        this.belts = belts;
        this.directory = directory;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MemberStatistics statistics(long membershipId) {
        Member member = requireMember(membershipId);
        Instant now = clock.instant();
        Windows windows = windows(now);
        PresentCounts present = repository.present(membershipId, windows.thisMonthFrom(), windows.last7DaysFrom(),
                windows.last30DaysFrom());
        RegistrationCounts registrations = repository.registrations(membershipId);
        HeldWhileActive held = repository.heldWhileActive(membershipId);
        BeltHistory beltHistory = belts.history(membershipId);
        return new MemberStatistics(membershipId, member.userId(), now, windows, member.joinedOn(),
                present.total(), present.thisMonth(), present.last7Days(), present.last30Days(),
                registrations.absent(),
                new Registrations(registrations.total(), registrations.present(), registrations.absent(),
                        registrations.unmarked()),
                AttendanceRate.of(held.present(), held.heldSessions(), "HELD_SESSIONS_WHILE_ACTIVE"),
                AttendanceRate.of(registrations.present(), registrations.present() + registrations.absent(),
                        "REGISTRATIONS"),
                beltHistory.currentBelt(), beltHistory.currentBeltAwardedOn(), beltHistory.currentBeltTrainings(),
                beltHistory.unassignedTrainings(),
                beltHistory.periods().stream().map(p -> new BeltPeriodStats(p.beltPeriodId(), p.belt().name(),
                        p.awardedOn(), p.effectiveFromDate(), p.effectiveToDate(), p.trainingsCount())).toList());
    }

    @Transactional(readOnly = true)
    public HistoryPage history(long membershipId, LocalDate from, LocalDate to, int page, int size) {
        requireMember(membershipId);
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.invalidField("to", "Mora biti nakon datuma od.");
        }
        Instant now = clock.instant();
        long total = repository.historyCount(membershipId, now, from, to);
        List<HistoryEntry> items = repository.history(membershipId, now, from, to, page, size).stream()
                .map(r -> new HistoryEntry(r.trainingId(), r.localDate(), r.startAt(), r.title(), r.trainingStatus(),
                        r.registrationStatus(), r.attendanceStatus()))
                .toList();
        return new HistoryPage(items, new PageMetadata(page, size, total, (int) ((total + size - 1) / size)));
    }

    /** D-05: club-local calendar dates, today included. */
    Windows windows(Instant now) {
        LocalDate today = now.atZone(directory.theClub().timeZone()).toLocalDate();
        return new Windows(today, today.withDayOfMonth(1), today.minusDays(6), today.minusDays(29));
    }

    private Member requireMember(long membershipId) {
        return repository.member(membershipId).orElseThrow(() -> ApiException.notFound("Član ne postoji."));
    }
}
