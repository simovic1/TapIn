package com.tapin.attendance;

import com.tapin.attendance.AttendanceRepository.Attendance;
import com.tapin.attendance.AttendanceRepository.AuditRow;
import com.tapin.auth.AuthenticatedUser;
import com.tapin.members.MembershipDirectory;
import com.tapin.members.MembershipDirectory.MemberRef;
import com.tapin.members.MembershipStatus;
import com.tapin.notifications.EmailEventType;
import com.tapin.notifications.EnqueueResult;
import com.tapin.notifications.NotificationOutbox;
import com.tapin.notifications.OutboxMessage;
import com.tapin.registrations.ParticipationLock;
import com.tapin.registrations.RegistrationService;
import com.tapin.registrations.RegistrationStatus;
import com.tapin.scheduling.TrainingService;
import com.tapin.scheduling.TrainingService.SessionRef;
import com.tapin.scheduling.TrainingService.TrainingView;
import com.tapin.scheduling.TrainingStatus;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only authority for writing attendance and its audit (spec §6-7, §13.2). Each change, its audit row and its
 * outbox e-mail are written in one transaction: all or nothing.
 * <ul>
 *   <li>A trainer may record any club member: registered, cancelled (spec §5.2) or not registered at all (walk-in,
 *       AC-06). Inactive members are not added to a session that has not ended yet; historical entry is allowed.</li>
 *   <li>Idempotent: the same status again changes nothing - no audit, no e-mail (AC-10).</li>
 *   <li>Optimistic locking on {@code expectedVersion} for the first insert (version 0 = no record), updates and the
 *       return to UNMARKED. UNMARKED keeps the row and its growing version, so a stale request never succeeds after
 *       a reset. A mismatch is {@code 409 VERSION_CONFLICT} with the fresh roster row (AC-09).</li>
 *   <li>Concurrency: the {@link ParticipationLock} serialises this with the member's own registration changes and
 *       other attendance writes for the same member; writes are additionally conditional on the version.</li>
 * </ul>
 */
@Service
public class AttendanceService {

    private final AttendanceRepository attendances;
    private final ParticipationLock lock;
    private final TrainingService trainings;
    private final RegistrationService registrations;
    private final MembershipDirectory directory;
    private final NotificationOutbox outbox;
    private final Clock clock;

    AttendanceService(AttendanceRepository attendances, ParticipationLock lock, TrainingService trainings,
                      RegistrationService registrations, MembershipDirectory directory, NotificationOutbox outbox,
                      Clock clock) {
        this.attendances = attendances;
        this.lock = lock;
        this.trainings = trainings;
        this.registrations = registrations;
        this.directory = directory;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ API shapes

    /** {@code RosterEntry}; {@code currentBelt} stays UNKNOWN until the belts module exists. */
    public record RosterEntry(long membershipId, long userId, String firstName, String lastName, String currentBelt,
                              RegistrationStatus registrationStatus, AttendanceStatus attendanceStatus,
                              long attendanceVersion, Instant attendanceUpdatedAt, Long attendanceUpdatedByUserId) {
    }

    public record RosterCounts(int registered, int present, int absent, int unmarked) {
    }

    public record Roster(TrainingView training, RosterCounts counts, List<RosterEntry> entries) {
    }

    public record AttendanceResult(long trainingId, long membershipId, long userId, AttendanceStatus status,
                                   long version, boolean changed, Instant updatedAt, Long updatedByUserId,
                                   String notificationStatus) {
    }

    public record AuditEntry(long auditId, long trainingId, long membershipId, long actorUserId,
                             AttendanceStatus previousStatus, AttendanceStatus newStatus, AttendanceSource source,
                             String note, Instant occurredAt) {
    }

    public record PageMetadata(int page, int size, long totalElements, int totalPages) {
    }

    public record AuditPage(List<AuditEntry> items, PageMetadata page) {
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Roster roster(AuthenticatedUser viewer, long trainingId) {
        TrainingView training = trainings.get(viewer, trainingId);
        Map<Long, RegistrationStatus> registered = registrations.registrationsOf(trainingId);
        Map<Long, Attendance> recorded = new HashMap<>();
        attendances.findBySession(trainingId).forEach(a -> recorded.put(a.membershipId(), a));
        Set<Long> ids = new LinkedHashSet<>(registered.keySet());
        ids.addAll(recorded.keySet());
        Map<Long, MemberRef> members = directory.findAll(ids);
        List<RosterEntry> entries = ids.stream().filter(members::containsKey)
                .map(id -> entry(members.get(id), registered.get(id), recorded.get(id)))
                .sorted(Comparator.comparing(RosterEntry::lastName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RosterEntry::firstName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int registeredCount = 0, present = 0, absent = 0, unmarked = 0;
        for (RosterEntry e : entries) {
            boolean active = e.registrationStatus() == RegistrationStatus.REGISTERED;
            registeredCount += active ? 1 : 0;
            present += e.attendanceStatus() == AttendanceStatus.PRESENT ? 1 : 0;
            absent += e.attendanceStatus() == AttendanceStatus.ABSENT ? 1 : 0;
            unmarked += active && e.attendanceStatus() == AttendanceStatus.UNMARKED ? 1 : 0;
        }
        return new Roster(training, new RosterCounts(registeredCount, present, absent, unmarked), entries);
    }

    @Transactional(readOnly = true)
    public AuditPage audit(AuthenticatedUser viewer, long trainingId, Long membershipId, int page, int size) {
        trainings.get(viewer, trainingId); // 404 for an unknown session
        long total = attendances.auditCount(trainingId, membershipId);
        List<AuditEntry> items = attendances.audit(trainingId, membershipId, page, size).stream()
                .map(AttendanceService::entry).toList();
        return new AuditPage(items, new PageMetadata(page, size, total, (int) ((total + size - 1) / size)));
    }

    // ------------------------------------------------------------------ writing

    /** PRESENT or ABSENT for one member (spec §11.4). */
    @Transactional
    public AttendanceResult set(AuthenticatedUser actor, long trainingId, long membershipId, AttendanceStatus status,
                                long expectedVersion, String note, AttendanceSource source) {
        if (status == AttendanceStatus.UNMARKED) {
            throw ApiException.invalidField("status", "Za povratak na neodlučeno koristite poništavanje evidencije.");
        }
        if (source == AttendanceSource.BULK) {
            throw ApiException.invalidField("source", "Izvor BULK postavlja samo masovno označavanje.");
        }
        return change(actor, trainingId, membershipId, status, expectedVersion, note, source);
    }

    /** Back to UNMARKED (exceptional case, spec §6.1); the member may then cancel the own registration again. */
    @Transactional
    public AttendanceResult reset(AuthenticatedUser actor, long trainingId, long membershipId, long expectedVersion,
                                  String note) {
        return change(actor, trainingId, membershipId, AttendanceStatus.UNMARKED, expectedVersion, note,
                AttendanceSource.CORRECTION);
    }

    private AttendanceResult change(AuthenticatedUser actor, long trainingId, long membershipId, AttendanceStatus target,
                                    long expectedVersion, String note, AttendanceSource source) {
        lock.acquire(trainingId, membershipId);
        SessionRef session = trainings.lockForParticipation(trainingId);
        MemberRef member = directory.findAll(List.of(membershipId)).get(membershipId);
        if (member == null) {
            throw ApiException.notFound("Član ne postoji.");
        }
        Attendance current = attendances.find(trainingId, membershipId).orElse(null);
        AttendanceStatus currentStatus = current != null ? current.status() : AttendanceStatus.UNMARKED;
        if (currentStatus == target) {
            return result(trainingId, member, current, false, EnqueueResult.DUPLICATE); // AC-10
        }
        Instant now = clock.instant();
        if (target != AttendanceStatus.UNMARKED) {
            if (session.status() == TrainingStatus.CANCELLED) {
                throw new ApiException(ErrorCode.TRAINING_CANCELLED, "Trening je otkazan; dolazak se ne evidentira.");
            }
            if (member.status() != MembershipStatus.ACTIVE && !session.hasEnded(now)) {
                throw new ApiException(ErrorCode.MEMBERSHIP_INACTIVE,
                        "Član nije aktivan, pa ga se ne može dodati na novi termin bez ponovne aktivacije.");
            }
        }
        long currentVersion = current != null ? current.version() : 0;
        if (currentVersion != expectedVersion) {
            throw conflict(trainingId, member, current);
        }
        boolean written = current == null
                ? attendances.insert(trainingId, directory.theClub().clubId(), membershipId, target, actor.userId(), now)
                        .isPresent()
                : attendances.update(current.id(), expectedVersion, target, actor.userId(), now);
        if (!written) {
            throw conflict(trainingId, member, attendances.find(trainingId, membershipId).orElse(null));
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        long auditId = attendances.insertAudit(trainingId, membershipId, actor.userId(), currentStatus, target, source,
                cleanNote, now);
        EnqueueResult queued = outbox.enqueue(OutboxMessage.of(EmailEventType.ATTENDANCE_RECORDED, member.userId(),
                member.email(), Map.of("firstName", member.firstName(), "trainingTime", session.displayTime(),
                        "attendanceStatus", label(target)),
                "ATTENDANCE_RECORDED:audit-" + auditId).forSession(trainingId));
        return result(trainingId, member, attendances.find(trainingId, membershipId).orElseThrow(), true, queued);
    }

    private ApiException conflict(long trainingId, MemberRef member, Attendance current) {
        return ApiException.versionConflict(entry(member, registrations.statusOf(trainingId, member.membershipId()),
                current));
    }

    private static String label(AttendanceStatus status) {
        return switch (status) {
            case PRESENT -> "dolazak (prisutni)";
            case ABSENT -> "izostanak (odsutni)";
            case UNMARKED -> "evidencija je poništena, dolazak još nije odlučen";
        };
    }

    private static RosterEntry entry(MemberRef m, RegistrationStatus registration, Attendance a) {
        return new RosterEntry(m.membershipId(), m.userId(), m.firstName(), m.lastName(), "UNKNOWN", registration,
                a != null ? a.status() : AttendanceStatus.UNMARKED, a != null ? a.version() : 0,
                a != null ? a.updatedAt() : null, a != null ? a.updatedByUserId() : null);
    }

    private static AuditEntry entry(AuditRow r) {
        return new AuditEntry(r.auditId(), r.trainingId(), r.membershipId(), r.actorUserId(), r.previousStatus(),
                r.newStatus(), r.source(), r.note(), r.occurredAt());
    }

    private static AttendanceResult result(long trainingId, MemberRef m, Attendance a, boolean changed,
                                           EnqueueResult queued) {
        return new AttendanceResult(trainingId, m.membershipId(), m.userId(),
                a != null ? a.status() : AttendanceStatus.UNMARKED, a != null ? a.version() : 0, changed,
                a != null ? a.updatedAt() : null, a != null ? a.updatedByUserId() : null,
                changed ? queued.notificationStatus() : EnqueueResult.DUPLICATE.notificationStatus());
    }
}
