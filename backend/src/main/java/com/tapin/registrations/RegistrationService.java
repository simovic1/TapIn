package com.tapin.registrations;

import com.tapin.auth.AuthenticatedUser;
import com.tapin.members.MembershipDirectory;
import com.tapin.members.MembershipDirectory.MemberRef;
import com.tapin.members.MembershipStatus;
import com.tapin.notifications.EmailEventType;
import com.tapin.notifications.EnqueueResult;
import com.tapin.notifications.NotificationOutbox;
import com.tapin.notifications.OutboxMessage;
import com.tapin.registrations.RegistrationRepository.Registration;
import com.tapin.scheduling.TrainingService;
import com.tapin.scheduling.TrainingService.SessionRef;
import com.tapin.scheduling.TrainingStatus;
import com.tapin.shared.error.ApiException;
import com.tapin.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A member's own registration (spec §5.1, AC-03/04/05).
 * <ul>
 *   <li>At most one registration per member and session (unique key); re-registering reuses the row.</li>
 *   <li>Idempotent: repeating the same action changes nothing, writes no event and no e-mail
 *       ({@code changed=false}, {@code notificationStatus=NOT_REQUIRED}).</li>
 *   <li>Registering needs an ACTIVE membership and a session that is not cancelled and has not ended; while it is in
 *       progress registering is still allowed. Cancelling the own registration is allowed any time, also after the
 *       start, until PRESENT/ABSENT is recorded; after that only a trainer changes it.</li>
 *   <li>Concurrency: every change holds the {@link ParticipationLock} of (session, member) - shared with attendance -
 *       and a shared lock on the session row, so a parallel attendance write or session cancellation is either fully
 *       before or fully after it (no check is bypassed).</li>
 * </ul>
 */
@Service
public class RegistrationService {

    private final RegistrationRepository registrations;
    private final ParticipationLock lock;
    private final TrainingService trainings;
    private final MembershipDirectory directory;
    private final NotificationOutbox outbox;
    private final List<AttendanceStatusLookup> attendance;
    private final Clock clock;

    RegistrationService(RegistrationRepository registrations, ParticipationLock lock, TrainingService trainings,
                        MembershipDirectory directory, NotificationOutbox outbox,
                        List<AttendanceStatusLookup> attendance, Clock clock) {
        this.registrations = registrations;
        this.lock = lock;
        this.trainings = trainings;
        this.directory = directory;
        this.outbox = outbox;
        this.attendance = attendance;
        this.clock = clock;
    }

    /** {@code MyRegistrationResult} of the contract. */
    public record MyRegistrationResult(long trainingId, long membershipId, RegistrationStatus registrationStatus,
                                       String attendanceStatus, Instant registeredAt, Instant cancelledAt,
                                       boolean changed, String notificationStatus) {
    }

    @Transactional
    public MyRegistrationResult register(AuthenticatedUser actor, long trainingId) {
        long membershipId = actor.membershipId();
        lock.acquire(trainingId, membershipId);
        SessionRef session = trainings.lockForRegistration(trainingId);
        Registration existing = registrations.find(trainingId, membershipId).orElse(null);
        String attendanceStatus = attendanceOf(trainingId, membershipId);
        if (existing != null && existing.status() == RegistrationStatus.REGISTERED) {
            return result(existing, attendanceStatus, false, EnqueueResult.DUPLICATE);
        }

        MemberRef member = me(membershipId);
        if (member.status() != MembershipStatus.ACTIVE) {
            throw new ApiException(ErrorCode.MEMBERSHIP_INACTIVE,
                    "Članstvo nije aktivno, pa prijava na trening nije moguća. Obratite se treneru.");
        }
        if (session.status() == TrainingStatus.CANCELLED) {
            throw new ApiException(ErrorCode.TRAINING_CANCELLED, "Trening je otkazan.");
        }
        Instant now = clock.instant();
        if (session.hasEnded(now)) {
            throw ApiException.businessRule("Trening je završio. Dolazak na održani trening evidentira trener.");
        }
        if (!"UNMARKED".equals(attendanceStatus)) {
            throw new ApiException(ErrorCode.ATTENDANCE_ALREADY_RECORDED,
                    "Dolazak je već evidentiran; prijavu može promijeniti samo trener.");
        }

        long registrationId;
        String eventType;
        if (existing == null) {
            // The participation lock serialises this member; the unique key stays the last line of defence.
            registrationId = registrations.insertRegistered(trainingId, directory.theClub().clubId(), membershipId, now)
                    .orElseThrow(() -> ApiException.conflict("Prijava je upravo izmijenjena. Osvježite prikaz."));
            eventType = "REGISTERED";
        } else {
            registrationId = existing.id();
            registrations.reRegister(registrationId, now);
            eventType = "RE_REGISTERED";
        }
        long eventId = registrations.insertEvent(registrationId, eventType, actor.userId(), "SELF", now);
        EnqueueResult queued = notify(EmailEventType.REGISTRATION_CONFIRMED, member, session, eventId);
        return result(registrations.find(trainingId, membershipId).orElseThrow(), attendanceStatus, true, queued);
    }

    @Transactional
    public MyRegistrationResult cancel(AuthenticatedUser actor, long trainingId) {
        long membershipId = actor.membershipId();
        lock.acquire(trainingId, membershipId);
        SessionRef session = trainings.lockForRegistration(trainingId);
        Registration existing = registrations.find(trainingId, membershipId)
                .orElseThrow(() -> ApiException.notFound("Niste prijavljeni na ovaj trening."));
        String attendanceStatus = attendanceOf(trainingId, membershipId);
        if (existing.status() == RegistrationStatus.CANCELLED) {
            return result(existing, attendanceStatus, false, EnqueueResult.DUPLICATE);
        }
        if (!"UNMARKED".equals(attendanceStatus)) {
            throw new ApiException(ErrorCode.ATTENDANCE_ALREADY_RECORDED,
                    "Dolazak je već evidentiran, pa prijavu više ne možete otkazati. Za ispravak se obratite treneru.");
        }
        Instant now = clock.instant();
        registrations.cancel(existing.id(), now);
        long eventId = registrations.insertEvent(existing.id(), "CANCELLED", actor.userId(), "SELF", now);
        EnqueueResult queued = notify(EmailEventType.REGISTRATION_CANCELLED, me(membershipId), session, eventId);
        return result(registrations.find(trainingId, membershipId).orElseThrow(), attendanceStatus, true, queued);
    }

    /** Current registration status of a member, for the attendance module (roster, bulk "absent"). */
    @Transactional(readOnly = true)
    public RegistrationStatus statusOf(long trainingId, long membershipId) {
        return registrations.find(trainingId, membershipId).map(Registration::status).orElse(null);
    }

    private EnqueueResult notify(EmailEventType type, MemberRef member, SessionRef session, long eventId) {
        return outbox.enqueue(OutboxMessage.of(type, member.userId(), member.email(),
                Map.of("firstName", member.firstName(), "trainingTime", session.displayTime()),
                type.name() + ":registration-event-" + eventId).forSession(session.trainingId()));
    }

    private MemberRef me(long membershipId) {
        MemberRef member = directory.findAll(List.of(membershipId)).get(membershipId);
        if (member == null) {
            throw new IllegalStateException("Signed-in user without membership " + membershipId);
        }
        return member;
    }

    private String attendanceOf(long trainingId, long membershipId) {
        for (AttendanceStatusLookup lookup : attendance) {
            String status = lookup.statusFor(trainingId, membershipId);
            if (status != null) {
                return status;
            }
        }
        return "UNMARKED";
    }

    private static MyRegistrationResult result(Registration r, String attendanceStatus, boolean changed,
                                               EnqueueResult queued) {
        return new MyRegistrationResult(r.trainingId(), r.membershipId(), r.status(), attendanceStatus,
                r.registeredAt(), r.cancelledAt(), changed,
                changed ? queued.notificationStatus() : EnqueueResult.DUPLICATE.notificationStatus());
    }
}
