package com.tapin.belts;

import com.tapin.auth.AuthenticatedUser;
import com.tapin.auth.UserAccountService;
import com.tapin.belts.BeltPeriodRepository.AuditRow;
import com.tapin.belts.BeltPeriodRepository.Period;
import com.tapin.belts.BeltTrainingCounter.Counts;
import com.tapin.members.MembershipDirectory;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Belt history of a member (spec §3.3, §11.6): initial/historical entry, promotion and controlled correction, each
 * audited. There is no DELETE: a wrong entry is corrected, never removed without a trace.
 * <ul>
 *   <li>{@code awardedOn} is the real award date (may be unknown, never invented); {@code effectiveFromDate} is when
 *       counting starts: the next day for AFTER_TRAINING, the same day for BEFORE_TRAINING.</li>
 *   <li>Chronology: ordered by start date, every period is a strictly higher belt than the one before, and known award
 *       dates follow the same order. Equal start dates are a {@code 409 BELT_DUPLICATE_EFFECTIVE_DATE}, any other
 *       violation a {@code 422 BELT_CHRONOLOGY_INVALID}.</li>
 *   <li>Concurrency: all changes of one member's belts run under one advisory lock and re-validate the whole
 *       history inside it; corrections additionally use {@code expectedVersion} (409 with the fresh period).</li>
 * </ul>
 */
@Service
public class BeltService {

    private final BeltPeriodRepository periods;
    private final BeltTrainingCounter counter;
    private final MembershipDirectory directory;
    private final UserAccountService users;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    BeltService(BeltPeriodRepository periods, BeltTrainingCounter counter, MembershipDirectory directory,
                UserAccountService users, JdbcTemplate jdbc, Clock clock) {
        this.periods = periods;
        this.counter = counter;
        this.directory = directory;
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ API shapes

    public record BeltPeriodView(long beltPeriodId, Belt belt, LocalDate awardedOn, LocalDate effectiveFromDate,
                                 LocalDate effectiveToDate, int trainingsCount, Long awardedByUserId, String note,
                                 long createdByUserId, Instant createdAt, Instant updatedAt, long version) {
    }

    public record BeltHistory(long membershipId, String currentBelt, LocalDate currentBeltAwardedOn,
                              int currentBeltTrainings, int totalTrainings, int unassignedTrainings,
                              List<BeltPeriodView> periods) {
    }

    public record MutationResult(BeltPeriodView beltPeriod, BeltHistory history) {
    }

    public record AuditEntry(long auditId, long beltPeriodId, long membershipId, long actorUserId, String action,
                             Map<String, Object> oldValues, Map<String, Object> newValues, String reason,
                             Instant occurredAt) {
    }

    public record PageMetadata(int page, int size, long totalElements, int totalPages) {
    }

    public record AuditPage(List<AuditEntry> items, PageMetadata page) {
    }

    public record CreateCommand(Belt belt, LocalDate awardedOn, PromotionTiming promotionTiming,
                                LocalDate effectiveFromDate, Long awardedByUserId, String note) {
    }

    /** {@code null} = unchanged; {@code Optional.empty()} clears a nullable field. */
    public record CorrectCommand(long expectedVersion, Belt belt, Optional<LocalDate> awardedOn,
                                 PromotionTiming promotionTiming, LocalDate effectiveFromDate,
                                 Optional<Long> awardedByUserId, Optional<String> note, String reason) {
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public BeltHistory history(long membershipId) {
        requireMember(membershipId);
        return historyOf(membershipId);
    }

    @Transactional(readOnly = true)
    public AuditPage audit(long membershipId, int page, int size) {
        requireMember(membershipId);
        long total = periods.auditCount(membershipId);
        List<AuditEntry> items = periods.audit(membershipId, page, size).stream().map(BeltService::entry).toList();
        return new AuditPage(items, new PageMetadata(page, size, total, (int) ((total + size - 1) / size)));
    }

    // ------------------------------------------------------------------ changes

    @Transactional
    public MutationResult create(AuthenticatedUser actor, long membershipId, CreateCommand command) {
        requireMember(membershipId);
        lock(membershipId);
        LocalDate effective = effectiveFrom(command.awardedOn(), command.promotionTiming(), command.effectiveFromDate());
        validateDates(command.awardedOn(), effective);
        requireUser(command.awardedByUserId());
        String note = clean(command.note());

        List<Period> existing = periods.findByMembership(membershipId);
        List<Candidate> after = new ArrayList<>(existing.stream().map(Candidate::of).toList());
        after.add(new Candidate(null, command.belt(), command.awardedOn(), effective));
        validateChronology(after);

        Instant now = clock.instant();
        long id;
        try {
            id = periods.insert(membershipId, command.belt(), command.awardedOn(), effective, command.awardedByUserId(),
                    note, actor.userId(), now);
        } catch (DuplicateKeyException e) {
            throw duplicate(effective);
        }
        Period created = periods.find(membershipId, id).orElseThrow();
        periods.insertAudit(id, membershipId, actor.userId(), "CREATED", null, snapshot(created), null, now);
        return result(membershipId, id);
    }

    @Transactional
    public MutationResult correct(AuthenticatedUser actor, long membershipId, long periodId, CorrectCommand command) {
        requireMember(membershipId);
        lock(membershipId);
        Period current = periods.find(membershipId, periodId)
                .orElseThrow(() -> ApiException.notFound("Zapis o pojasu ne postoji."));
        if (current.version() != command.expectedVersion()) {
            throw ApiException.versionConflict(viewOf(membershipId, periodId));
        }
        Belt belt = command.belt() != null ? command.belt() : current.belt();
        LocalDate awardedOn = command.awardedOn() != null ? command.awardedOn().orElse(null) : current.awardedOn();
        LocalDate effective;
        if (awardedOn != null) {
            PromotionTiming timing = command.promotionTiming() != null ? command.promotionTiming()
                    : PromotionTiming.of(current.awardedOn(), current.effectiveFromDate());
            effective = timing.effectiveFrom(awardedOn);
        } else {
            effective = command.effectiveFromDate() != null ? command.effectiveFromDate() : current.effectiveFromDate();
        }
        Long awardedBy = command.awardedByUserId() != null ? command.awardedByUserId().orElse(null) : current.awardedByUserId();
        String note = command.note() != null ? clean(command.note().orElse(null)) : current.note();
        validateDates(awardedOn, effective);
        requireUser(awardedBy);

        if (belt == current.belt() && Objects.equals(awardedOn, current.awardedOn())
                && effective.equals(current.effectiveFromDate()) && Objects.equals(awardedBy, current.awardedByUserId())
                && Objects.equals(note, current.note())) {
            return result(membershipId, periodId); // nothing changed: no audit entry
        }
        List<Candidate> after = new ArrayList<>(periods.findByMembership(membershipId).stream()
                .map(p -> p.id() == periodId ? new Candidate(p.id(), belt, awardedOn, effective) : Candidate.of(p))
                .toList());
        validateChronology(after);

        Instant now = clock.instant();
        boolean updated;
        try {
            updated = periods.update(periodId, current.version(), belt, awardedOn, effective, awardedBy, note, now);
        } catch (DuplicateKeyException e) {
            throw duplicate(effective);
        }
        if (!updated) {
            throw ApiException.versionConflict(viewOf(membershipId, periodId));
        }
        periods.insertAudit(periodId, membershipId, actor.userId(), "CORRECTED", snapshot(current),
                snapshot(periods.find(membershipId, periodId).orElseThrow()), command.reason().strip(), now);
        return result(membershipId, periodId);
    }

    // ------------------------------------------------------------------ rules

    private record Candidate(Long id, Belt belt, LocalDate awardedOn, LocalDate effectiveFrom) {
        static Candidate of(Period p) {
            return new Candidate(p.id(), p.belt(), p.awardedOn(), p.effectiveFromDate());
        }
    }

    private static LocalDate effectiveFrom(LocalDate awardedOn, PromotionTiming timing, LocalDate explicit) {
        if (awardedOn != null) {
            return (timing != null ? timing : PromotionTiming.AFTER_TRAINING).effectiveFrom(awardedOn);
        }
        if (explicit == null) {
            throw ApiException.invalidField("effectiveFromDate",
                    "Ako datum dodjele nije poznat, unesite od kada se treninzi broje u taj pojas.");
        }
        return explicit;
    }

    private void validateDates(LocalDate awardedOn, LocalDate effective) {
        LocalDate today = LocalDate.now(clock.withZone(directory.theClub().timeZone()));
        if (awardedOn != null && awardedOn.isAfter(today)) {
            throw ApiException.invalidField("awardedOn", "Datum dodjele ne može biti u budućnosti.");
        }
        if (awardedOn == null && effective.isAfter(today)) {
            throw ApiException.invalidField("effectiveFromDate", "Početak brojanja ne može biti u budućnosti.");
        }
    }

    /** Strictly increasing belts by start date; known award dates in the same order; no equal starts. */
    private static void validateChronology(List<Candidate> list) {
        List<Candidate> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(Candidate::effectiveFrom));
        for (int i = 1; i < sorted.size(); i++) {
            Candidate previous = sorted.get(i - 1);
            Candidate next = sorted.get(i);
            if (previous.effectiveFrom().equals(next.effectiveFrom())) {
                throw duplicate(next.effectiveFrom());
            }
            if (!next.belt().isHigherThan(previous.belt())) {
                throw new ApiException(ErrorCode.BELT_CHRONOLOGY_INVALID, "Pojas " + label(next.belt())
                        + " od " + next.effectiveFrom() + " mora biti viši od prethodnog pojasa (" + label(previous.belt())
                        + " od " + previous.effectiveFrom() + ").");
            }
            if (previous.awardedOn() != null && next.awardedOn() != null && next.awardedOn().isBefore(previous.awardedOn())) {
                throw new ApiException(ErrorCode.BELT_CHRONOLOGY_INVALID,
                        "Datum dodjele višeg pojasa ne može biti prije datuma dodjele nižeg pojasa.");
            }
        }
    }

    private static ApiException duplicate(LocalDate effective) {
        return new ApiException(ErrorCode.BELT_DUPLICATE_EFFECTIVE_DATE,
                "Za ovog člana već postoji pojas koji se broji od " + effective + ".");
    }

    private static String label(Belt belt) {
        return switch (belt) {
            case WHITE -> "bijeli";
            case BLUE -> "plavi";
            case PURPLE -> "ljubičasti";
            case BROWN -> "smeđi";
            case BLACK -> "crni";
        };
    }

    private void lock(long membershipId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class,
                "belts:" + membershipId);
    }

    private void requireMember(long membershipId) {
        if (directory.findAll(List.of(membershipId)).isEmpty()) {
            throw ApiException.notFound("Član ne postoji.");
        }
    }

    private void requireUser(Long userId) {
        if (userId != null && users.findById(userId).isEmpty()) {
            throw ApiException.invalidField("awardedByUserId", "Korisnik koji je dodijelio pojas ne postoji.");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ------------------------------------------------------------------ mapping

    private MutationResult result(long membershipId, long periodId) {
        BeltHistory history = historyOf(membershipId);
        BeltPeriodView view = history.periods().stream().filter(p -> p.beltPeriodId() == periodId).findFirst().orElseThrow();
        return new MutationResult(view, history);
    }

    private BeltPeriodView viewOf(long membershipId, long periodId) {
        return historyOf(membershipId).periods().stream().filter(p -> p.beltPeriodId() == periodId).findFirst().orElseThrow();
    }

    BeltHistory historyOf(long membershipId) {
        List<Period> list = periods.findByMembership(membershipId);
        Counts counts = counter.count(membershipId, list);
        List<BeltPeriodView> views = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Period p = list.get(i);
            LocalDate to = i + 1 < list.size() ? list.get(i + 1).effectiveFromDate() : null;
            views.add(new BeltPeriodView(p.id(), p.belt(), p.awardedOn(), p.effectiveFromDate(), to,
                    counts.perPeriod().get(i), p.awardedByUserId(), p.note(), p.createdByUserId(), p.createdAt(),
                    p.updatedAt(), p.version()));
        }
        BeltPeriodView current = views.isEmpty() ? null : views.getLast();
        return new BeltHistory(membershipId, current != null ? current.belt().name() : "UNKNOWN",
                current != null ? current.awardedOn() : null, current != null ? current.trainingsCount() : 0,
                counts.total(), counts.unassigned(), views);
    }

    private static Map<String, Object> snapshot(Period p) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("belt", p.belt().name());
        values.put("awardedOn", p.awardedOn() == null ? null : p.awardedOn().toString());
        values.put("effectiveFromDate", p.effectiveFromDate().toString());
        values.put("awardedByUserId", p.awardedByUserId());
        values.put("note", p.note());
        return values;
    }

    private static AuditEntry entry(AuditRow r) {
        return new AuditEntry(r.auditId(), r.beltPeriodId(), r.membershipId(), r.actorUserId(), r.action(),
                r.oldValues(), r.newValues(), r.reason(), r.occurredAt());
    }
}
