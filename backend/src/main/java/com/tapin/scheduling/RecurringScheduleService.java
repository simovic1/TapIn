package com.tapin.scheduling;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tapin.auth.AuthenticatedUser;
import com.tapin.members.MembershipDirectory;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Weekly rules (spec §4.1). Edits apply to the future only: past sessions keep their own instants, and
 * {@code validFrom} cannot be changed.
 * <p>
 * <b>Decision D-02 – already materialised future sessions.</b> A rule change is also applied to the rule's
 * sessions that are still {@code SCHEDULED}, have not started and whose time was not changed individually: a new
 * time moves them (registered members get "Promijenjen termin"), and an occurrence no longer covered by the rule
 * ({@code validTo} shortened, rule disabled) is <em>cancelled</em>, never deleted (registered members get
 * "Otkazan trening"). Individually moved or cancelled sessions and sessions that have started stay as they are.
 */
@Service
public class RecurringScheduleService {

    static final String REMOVED_FROM_SCHEDULE = "Termin je uklonjen iz tjednog rasporeda.";

    private final RecurringScheduleRepository schedules;
    private final TrainingSessionRepository sessions;
    private final SessionEventRepository events;
    private final SessionMaterializer materializer;
    private final TrainingNotifier notifier;
    private final MembershipDirectory directory;
    private final Clock clock;

    RecurringScheduleService(RecurringScheduleRepository schedules, TrainingSessionRepository sessions,
                             SessionEventRepository events, SessionMaterializer materializer,
                             TrainingNotifier notifier, MembershipDirectory directory, Clock clock) {
        this.schedules = schedules;
        this.sessions = sessions;
        this.events = events;
        this.materializer = materializer;
        this.notifier = notifier;
        this.directory = directory;
        this.clock = clock;
    }

    /** API shape of a rule ({@code RecurringSchedule} in the contract); times as {@code HH:mm}. */
    public record ScheduleView(long scheduleId, DayOfWeek weekday,
                               @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                               @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                               LocalDate validFrom, LocalDate validTo, boolean enabled, long version) {
    }

    public record CreateCommand(DayOfWeek weekday, LocalTime startTime, LocalTime endTime, LocalDate validFrom,
                                LocalDate validTo, boolean enabled) {
    }

    /** {@code null} = unchanged; {@code validTo = Optional.empty()} = remove the end date. */
    public record UpdateCommand(long expectedVersion, LocalTime startTime, LocalTime endTime,
                                Optional<LocalDate> validTo, Boolean enabled) {
    }

    @Transactional(readOnly = true)
    public List<ScheduleView> list() {
        return schedules.findAll().stream()
                .sorted(Comparator.comparing(RecurringSchedule::getWeekday)
                        .thenComparing(RecurringSchedule::getStartTime).thenComparing(RecurringSchedule::getId))
                .map(RecurringScheduleService::view).toList();
    }

    @Transactional
    public ScheduleView create(AuthenticatedUser actor, CreateCommand command) {
        validate(command.startTime(), command.endTime(), command.validFrom(), command.validTo());
        RecurringSchedule rule = new RecurringSchedule(directory.theClub().clubId(), command.weekday(),
                command.startTime(), command.endTime(), command.validFrom(), command.validTo(), command.enabled(),
                actor.userId());
        assertNoOverlappingRule(rule);
        schedules.saveAndFlush(rule);
        materializer.materialize();
        return view(rule);
    }

    @Transactional
    public ScheduleView update(AuthenticatedUser actor, long scheduleId, UpdateCommand command) {
        RecurringSchedule rule = schedules.findById(scheduleId)
                .orElseThrow(() -> ApiException.notFound("Pravilo rasporeda ne postoji."));
        if (rule.getVersion() != command.expectedVersion()) {
            throw ApiException.versionConflict(view(rule));
        }
        LocalTime start = command.startTime() != null ? command.startTime() : rule.getStartTime();
        LocalTime end = command.endTime() != null ? command.endTime() : rule.getEndTime();
        LocalDate validTo = command.validTo() != null ? command.validTo().orElse(null) : rule.getValidTo();
        boolean enabled = command.enabled() != null ? command.enabled() : rule.isEnabled();
        validate(start, end, rule.getValidFrom(), validTo);
        rule.change(start, end, validTo, enabled);
        assertNoOverlappingRule(rule);
        applyToMaterializedSessions(rule, actor.userId());
        try {
            schedules.saveAndFlush(rule);
            sessions.flush();
        } catch (DataIntegrityViolationException e) {
            throw SessionOverlap.translate(e);
        }
        materializer.materialize();
        return view(rule);
    }

    /** Decision D-02, see the class comment. */
    private void applyToMaterializedSessions(RecurringSchedule rule, long actorUserId) {
        ZoneId zone = directory.theClub().timeZone();
        Instant now = clock.instant();
        for (TrainingSession session : sessions.findFollowingRule(rule.getId(), now)) {
            LocalDate date = session.getOccurrenceDate();
            if (!rule.occursOn(date)) {
                session.cancel(REMOVED_FROM_SCHEDULE, now, actorUserId);
                SessionEvent event = events.save(new SessionEvent(session.getId(), SessionEvent.Type.CANCELLED,
                        actorUserId, null, Map.of("reason", REMOVED_FROM_SCHEDULE, "source", "SCHEDULE"), now));
                notifier.cancelled(session, event.getId(), zone);
                continue;
            }
            Instant newStart = SessionMaterializer.toInstant(date, rule.getStartTime(), zone);
            Instant newEnd = SessionMaterializer.toInstant(date, rule.getEndTime(), zone);
            if (newStart.equals(session.getStartAt()) && newEnd.equals(session.getEndAt())) {
                continue;
            }
            Instant previousStart = session.getStartAt();
            Instant previousEnd = session.getEndAt();
            session.moveTo(newStart, newEnd, false);
            SessionEvent event = events.save(new SessionEvent(session.getId(), SessionEvent.Type.RESCHEDULED,
                    actorUserId, Map.of("startAt", previousStart.toString(), "endAt", previousEnd.toString()),
                    Map.of("startAt", newStart.toString(), "endAt", newEnd.toString(), "source", "SCHEDULE"), now));
            notifier.rescheduled(session, previousStart, event.getId(), zone);
        }
    }

    private void assertNoOverlappingRule(RecurringSchedule rule) {
        if (!rule.isEnabled()) {
            return;
        }
        boolean overlaps = schedules.findByEnabledTrue().stream()
                .filter(other -> !other.getId().equals(rule.getId()))
                .filter(other -> other.getWeekday() == rule.getWeekday())
                .filter(other -> other.getStartTime().isBefore(rule.getEndTime())
                        && rule.getStartTime().isBefore(other.getEndTime()))
                .anyMatch(other -> periodsOverlap(other.getValidFrom(), other.getValidTo(), rule.getValidFrom(),
                        rule.getValidTo()));
        if (overlaps) {
            throw new ApiException(ErrorCode.TRAINING_OVERLAP,
                    "U to vrijeme već postoji drugo pravilo rasporeda za isti dan u tjednu.");
        }
    }

    private static boolean periodsOverlap(LocalDate fromA, LocalDate toA, LocalDate fromB, LocalDate toB) {
        return (toB == null || !fromA.isAfter(toB)) && (toA == null || !fromB.isAfter(toA));
    }

    private static void validate(LocalTime start, LocalTime end, LocalDate validFrom, LocalDate validTo) {
        if (!end.isAfter(start)) {
            throw ApiException.invalidField("endTime", "Završetak mora biti nakon početka.");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw ApiException.invalidField("validTo", "Datum završetka ne može biti prije datuma početka.");
        }
    }

    static ScheduleView view(RecurringSchedule rule) {
        return new ScheduleView(rule.getId(), rule.getWeekday(), rule.getStartTime(), rule.getEndTime(),
                rule.getValidFrom(), rule.getValidTo(), rule.isEnabled(), rule.getVersion());
    }
}
