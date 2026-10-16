package com.tapin.scheduling;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tapin.auth.AuthenticatedUser;
import com.tapin.members.MembershipDirectory;
import com.tapin.members.MembershipDirectory.MemberRef;
import com.tapin.members.MembershipStatus;
import com.tapin.scheduling.TrainingParticipants.MemberState;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Individual sessions (spec §4, §11.3, AC-11/12): reading, extra/historical sessions, moving, cancelling, coaches
 * and the automatic SCHEDULED → HELD transition.
 * <ul>
 *   <li>No two active (non-cancelled) sessions overlap; the database constraint decides under concurrency.</li>
 *   <li>Cancelling keeps the session, the weekly rule, registrations and the change log; a cancelled session is
 *       never HELD and therefore never counted in statistics.</li>
 *   <li>Assigned coaches are information only: every TRAINER/ADMIN may manage every session (convention §17.1).</li>
 *   <li>HELD is not a lock (§4.3): attendance stays editable; only the session's time and status are frozen.</li>
 * </ul>
 * <b>Decision D-03 – a session that has started or is HELD</b> can no longer be moved or cancelled
 * ({@code 422 BUSINESS_RULE_VIOLATION}); title, note and coaches stay editable.
 */
@Service
public class TrainingService {

    static final Duration DEFAULT_RANGE = Duration.ofDays(14);
    static final Duration MAX_RANGE = Duration.ofDays(92);
    static final Duration MAX_DURATION = Duration.ofHours(12);

    private final TrainingSessionRepository sessions;
    private final SessionEventRepository events;
    private final TrainingNotifier notifier;
    private final MembershipDirectory directory;
    private final List<TrainingParticipants> participants;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    TrainingService(TrainingSessionRepository sessions, SessionEventRepository events, TrainingNotifier notifier,
                    MembershipDirectory directory, List<TrainingParticipants> participants, JdbcTemplate jdbc,
                    Clock clock) {
        this.sessions = sessions;
        this.events = events;
        this.notifier = notifier;
        this.directory = directory;
        this.participants = participants;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ views (API shapes of the contract)

    public record CoachView(long membershipId, long userId, String firstName, String lastName) {
    }

    /**
     * {@code TrainingDetail} of the contract (a superset of {@code TrainingSummary}). {@code registeredCount} is
     * only present for staff: members never learn who or how many others registered (§2.2).
     */
    public record TrainingView(long trainingId, Long scheduleId, LocalDate occurrenceDate, String title,
                               Instant startAt, Instant endAt, LocalDate localDate, TrainingStatus status,
                               List<CoachView> coaches,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer registeredCount,
                               String myRegistrationStatus, String myAttendanceStatus, boolean canRegister,
                               boolean canCancelRegistration, long version, String cancellationReason, String note,
                               Long createdByUserId, Instant createdAt, Instant updatedAt) {
    }

    /** What other modules need to know about a session; {@code displayTime} is formatted for e-mails. */
    public record SessionRef(long trainingId, TrainingStatus status, Instant startAt, Instant endAt,
                             String displayTime) {

        public boolean hasEnded(Instant now) {
            return status == TrainingStatus.HELD || !endAt.isAfter(now);
        }
    }

    public record MutationResult(TrainingView training, boolean changed, String notificationStatus) {
    }

    public record CreateCommand(Instant startAt, Instant endAt, String title, Set<Long> coachMembershipIds,
                                boolean historical, String note) {
    }

    /** {@code null} = unchanged; for title/note {@code Optional.empty()} clears the value. */
    public record UpdateCommand(long expectedVersion, Instant startAt, Instant endAt, Optional<String> title,
                                Set<Long> coachMembershipIds, Optional<String> note) {
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<TrainingView> list(AuthenticatedUser viewer, Instant from, Instant to, Set<TrainingStatus> statuses) {
        Instant start = from != null ? from : clock.instant();
        Instant end = to != null ? to : start.plus(DEFAULT_RANGE);
        if (!end.isAfter(start)) {
            throw ApiException.invalidField("to", "Kraj razdoblja mora biti nakon početka.");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw ApiException.invalidField("to", "Razdoblje može obuhvatiti najviše 92 dana.");
        }
        Set<TrainingStatus> filter = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(TrainingStatus.class) : EnumSet.copyOf(statuses);
        return views(viewer, sessions.findStartingBetween(start, end, filter));
    }

    /** Who can be assigned as a coach (active TRAINER/ADMIN memberships). */
    @Transactional(readOnly = true)
    public List<CoachView> coachCandidates() {
        return directory.findActiveStaff().stream()
                .map(c -> new CoachView(c.membershipId(), c.userId(), c.firstName(), c.lastName())).toList();
    }

    @Transactional(readOnly = true)
    public TrainingView get(AuthenticatedUser viewer, long trainingId) {
        return views(viewer, List.of(find(trainingId))).getFirst();
    }

    /**
     * Reads the session with a shared row lock for the caller's transaction (registrations). A concurrent cancel or
     * move waits until the caller commits, and vice versa, so a registration can never slip in next to a
     * cancellation without being seen by it (the cancel e-mail goes to everyone registered at its commit).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public SessionRef lockForRegistration(long trainingId) {
        TrainingSession s = sessions.findForShare(trainingId)
                .orElseThrow(() -> ApiException.notFound("Trening ne postoji."));
        return new SessionRef(s.getId(), s.getStatus(), s.getStartAt(), s.getEndAt(),
                TrainingNotifier.format(s.getStartAt(), directory.theClub().timeZone()));
    }

    // ------------------------------------------------------------------ changes

    @Transactional
    public MutationResult create(AuthenticatedUser actor, CreateCommand command) {
        Instant now = clock.instant();
        validateTimes(command.startAt(), command.endAt());
        if (command.historical() && command.endAt().isAfter(now)) {
            throw ApiException.invalidField("endAt", "Povijesni trening mora biti završen.");
        }
        if (!command.historical() && !command.startAt().isAfter(now)) {
            throw ApiException.invalidField("startAt",
                    "Početak mora biti u budućnosti. Za prošli trening odaberite povijesni unos.");
        }
        Set<Long> coaches = validCoaches(command.coachMembershipIds());
        assertNoOverlap(command.startAt(), command.endAt(), 0);
        TrainingSession session = TrainingSession.manual(directory.theClub().clubId(), command.startAt(),
                command.endAt(), blankToNull(command.title()), blankToNull(command.note()), coaches,
                command.historical(), actor.userId());
        flush(() -> sessions.saveAndFlush(session));
        Map<String, Object> values = new HashMap<>(snapshot(session));
        values.put("source", command.historical() ? "HISTORICAL" : "MANUAL");
        events.save(new SessionEvent(session.getId(), SessionEvent.Type.CREATED, actor.userId(), null, values, now));
        return new MutationResult(view(actor, session), true, "NOT_REQUIRED");
    }

    @Transactional
    public MutationResult update(AuthenticatedUser actor, long trainingId, UpdateCommand command) {
        TrainingSession session = find(trainingId);
        if (session.getVersion() != command.expectedVersion()) {
            throw ApiException.versionConflict(view(actor, session));
        }
        if (session.getStatus() == TrainingStatus.CANCELLED) {
            throw new ApiException(ErrorCode.TRAINING_CANCELLED, "Otkazani trening se ne može mijenjati.");
        }
        Instant now = clock.instant();
        ZoneId zone = directory.theClub().timeZone();
        Map<String, Object> before = snapshot(session);

        Instant newStart = command.startAt() != null ? command.startAt() : session.getStartAt();
        Instant newEnd = command.endAt() != null ? command.endAt() : session.getEndAt();
        boolean timeChanged = !newStart.equals(session.getStartAt()) || !newEnd.equals(session.getEndAt());
        Instant previousStart = session.getStartAt();
        if (timeChanged) {
            assertNotStarted(session, now, "pomaknuti");
            validateTimes(newStart, newEnd);
            if (!newStart.isAfter(now)) {
                throw ApiException.invalidField("startAt", "Novi početak mora biti u budućnosti.");
            }
            assertNoOverlap(newStart, newEnd, session.getId());
            session.moveTo(newStart, newEnd, true);
        }
        String title = command.title() != null ? blankToNull(command.title().orElse(null)) : session.getTitle();
        String note = command.note() != null ? blankToNull(command.note().orElse(null)) : session.getNote();
        boolean described = !java.util.Objects.equals(title, session.getTitle())
                || !java.util.Objects.equals(note, session.getNote());
        if (described) {
            session.describe(title, note);
        }
        boolean coachesChanged = false;
        if (command.coachMembershipIds() != null
                && !command.coachMembershipIds().equals(session.getCoachMembershipIds())) {
            session.assignCoaches(validCoaches(command.coachMembershipIds()));
            coachesChanged = true;
        }
        if (!timeChanged && !described && !coachesChanged) {
            return new MutationResult(view(actor, session), false, "NOT_REQUIRED");
        }
        flush(sessions::flush);
        SessionEvent event = events.save(new SessionEvent(session.getId(),
                timeChanged ? SessionEvent.Type.RESCHEDULED : SessionEvent.Type.UPDATED, actor.userId(), before,
                snapshot(session), now));
        String notification = timeChanged && !newStart.equals(previousStart)
                ? notifier.rescheduled(session, previousStart, event.getId(), zone) : "NOT_REQUIRED";
        return new MutationResult(view(actor, session), true, notification);
    }

    @Transactional
    public MutationResult cancel(AuthenticatedUser actor, long trainingId, long expectedVersion, String reason) {
        TrainingSession session = find(trainingId);
        if (session.getStatus() == TrainingStatus.CANCELLED) {
            return new MutationResult(view(actor, session), false, "NOT_REQUIRED"); // idempotent
        }
        if (session.getVersion() != expectedVersion) {
            throw ApiException.versionConflict(view(actor, session));
        }
        Instant now = clock.instant();
        assertNotStarted(session, now, "otkazati");
        session.cancel(blankToNull(reason), now, actor.userId());
        flush(sessions::flush);
        Map<String, Object> values = new HashMap<>();
        values.put("status", TrainingStatus.CANCELLED.name());
        values.put("reason", blankToNull(reason));
        SessionEvent event = events.save(new SessionEvent(session.getId(), SessionEvent.Type.CANCELLED,
                actor.userId(), Map.of("status", TrainingStatus.SCHEDULED.name()), values, now));
        String notification = notifier.cancelled(session, event.getId(), directory.theClub().timeZone());
        return new MutationResult(view(actor, session), true, notification);
    }

    /**
     * SCHEDULED sessions whose end has passed become HELD (spec §4.3). Cancelled sessions never do. Idempotent
     * and safe on several instances (a row is updated once; the event is written by the run that updated it).
     *
     * @return number of sessions marked HELD
     */
    @Transactional
    public int markEndedAsHeld() {
        Timestamp now = Timestamp.from(clock.instant());
        List<Long> ids = jdbc.queryForList("""
                UPDATE training_sessions SET status = 'HELD', version = version + 1, updated_at = ?
                WHERE status = 'SCHEDULED' AND end_at <= ?
                RETURNING id
                """, Long.class, now, now);
        for (Long id : ids) {
            jdbc.update("""
                    INSERT INTO training_session_events (session_id, event_type, old_values, new_values, occurred_at)
                    VALUES (?, 'HELD', '{"status":"SCHEDULED"}'::jsonb, '{"status":"HELD"}'::jsonb, ?)
                    """, id, now);
        }
        return ids.size();
    }

    // ------------------------------------------------------------------ rules

    /** Decision D-03, see the class comment. */
    private static void assertNotStarted(TrainingSession session, Instant now, String verb) {
        if (session.getStatus() == TrainingStatus.HELD || session.hasStarted(now)) {
            throw ApiException.businessRule("Trening koji je započeo ili održan ne može se " + verb + ".");
        }
    }

    private static void validateTimes(Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw ApiException.invalidField("endAt", "Završetak mora biti nakon početka.");
        }
        if (Duration.between(startAt, endAt).compareTo(MAX_DURATION) > 0) {
            throw ApiException.invalidField("endAt", "Trening može trajati najviše 12 sati.");
        }
    }

    private void assertNoOverlap(Instant startAt, Instant endAt, long exceptId) {
        if (!sessions.findActiveOverlapping(startAt, endAt, exceptId).isEmpty()) {
            throw SessionOverlap.overlap();
        }
    }

    /** Coaches must be active memberships with an operational role (TRAINER or ADMIN). */
    private Set<Long> validCoaches(Set<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of();
        }
        Map<Long, MemberRef> found = directory.findAll(requested);
        for (Long id : requested) {
            MemberRef member = found.get(id);
            if (member == null || !member.isStaff() || member.status() != MembershipStatus.ACTIVE) {
                throw ApiException.businessRule("Trener termina mora biti aktivan trener ili administrator kluba.");
            }
        }
        return new LinkedHashSet<>(requested);
    }

    private TrainingSession find(long trainingId) {
        return sessions.findById(trainingId).orElseThrow(() -> ApiException.notFound("Trening ne postoji."));
    }

    private static void flush(Runnable action) {
        try {
            action.run();
        } catch (DataIntegrityViolationException e) {
            throw SessionOverlap.translate(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Map<String, Object> snapshot(TrainingSession s) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("startAt", s.getStartAt().toString());
        values.put("endAt", s.getEndAt().toString());
        values.put("title", s.getTitle());
        values.put("note", s.getNote());
        values.put("coachMembershipIds", List.copyOf(new java.util.TreeSet<>(s.getCoachMembershipIds())));
        return values;
    }

    // ------------------------------------------------------------------ mapping

    private TrainingView view(AuthenticatedUser viewer, TrainingSession session) {
        return views(viewer, List.of(session)).getFirst();
    }

    private List<TrainingView> views(AuthenticatedUser viewer, List<TrainingSession> list) {
        ZoneId zone = directory.theClub().timeZone();
        Set<Long> coachIds = new HashSet<>();
        list.forEach(s -> coachIds.addAll(s.getCoachMembershipIds()));
        Map<Long, MemberRef> coaches = directory.findAll(coachIds);
        List<Long> ids = list.stream().map(TrainingSession::getId).toList();
        boolean staff = viewer.hasRole("TRAINER") || viewer.hasRole("ADMIN");
        Map<Long, Integer> counts = staff ? registeredCounts(ids) : Map.of();
        boolean member = viewer.hasRole("MEMBER");
        Map<Long, MemberState> mine = member ? memberStates(viewer.membershipId(), ids) : Map.of();
        MemberRef me = member ? directory.findAll(List.of(viewer.membershipId())).get(viewer.membershipId()) : null;
        boolean activeMember = me != null && me.status() == MembershipStatus.ACTIVE;
        Instant now = clock.instant();
        return list.stream().map(s -> {
            MemberState state = mine.getOrDefault(s.getId(), MemberState.NONE);
            // Spec §5.1: register while the session is not cancelled and has not ended, until attendance is
            // recorded; cancel the own registration any time until PRESENT/ABSENT is recorded.
            boolean canRegister = activeMember && s.getStatus() == TrainingStatus.SCHEDULED
                    && s.getEndAt().isAfter(now) && !state.attendanceRecorded()
                    && !"REGISTERED".equals(state.registrationStatus());
            boolean canCancel = member && "REGISTERED".equals(state.registrationStatus()) && !state.attendanceRecorded();
            List<CoachView> coachViews = s.getCoachMembershipIds().stream().map(coaches::get)
                    .filter(java.util.Objects::nonNull)
                    .sorted(java.util.Comparator.comparing(MemberRef::lastName).thenComparing(MemberRef::firstName))
                    .map(c -> new CoachView(c.membershipId(), c.userId(), c.firstName(), c.lastName())).toList();
            return new TrainingView(s.getId(), s.getScheduleId(), s.getOccurrenceDate(), s.getTitle(), s.getStartAt(),
                    s.getEndAt(), LocalDate.ofInstant(s.getStartAt(), zone), s.getStatus(), coachViews,
                    staff ? counts.getOrDefault(s.getId(), 0) : null, state.registrationStatus(),
                    state.attendanceStatus(), canRegister, canCancel, s.getVersion(), s.getCancellationReason(), s.getNote(),
                    s.getCreatedByUserId(), s.getCreatedAt(), s.getUpdatedAt());
        }).toList();
    }

    private Map<Long, Integer> registeredCounts(Collection<Long> ids) {
        Map<Long, Integer> result = new HashMap<>();
        participants.forEach(p -> result.putAll(p.registeredCounts(ids)));
        return result;
    }

    private Map<Long, MemberState> memberStates(long membershipId, Collection<Long> ids) {
        Map<Long, MemberState> result = new HashMap<>();
        participants.forEach(p -> result.putAll(p.memberStates(membershipId, ids)));
        return result;
    }
}
