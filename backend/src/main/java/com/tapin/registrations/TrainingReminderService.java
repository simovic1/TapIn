package com.tapin.registrations;

import com.tapin.auth.UserAccountService;
import com.tapin.members.MembershipDirectory;
import com.tapin.members.MembershipDirectory.MemberRef;
import com.tapin.notifications.EmailEventType;
import com.tapin.notifications.EnqueueResult;
import com.tapin.notifications.NotificationOutbox;
import com.tapin.notifications.OutboxMessage;
import com.tapin.scheduling.TrainingService;
import com.tapin.scheduling.TrainingService.SessionRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reminder three hours before a training (spec §8.2, AC-13/14, decision D-04).
 * <ul>
 *   <li>Due at {@code start_at - 3h} as an instant, so 18:30 Zagreb is always 15:30 Zagreb, in summer and winter.</li>
 *   <li>Recipients: members whose registration is REGISTERED and was made at or before the due time - a later
 *       registration gets its confirmation but no retroactive reminder - and who did not switch reminders off.
 *       Cancelled trainings get none.</li>
 *   <li>D-04: the job runs every minute; a missed due time (restart, outage) is caught up until the training starts.
 *       A reminder still undelivered when the training starts expires in the outbox (FAILED, not sent).</li>
 *   <li>One reminder per member and session: the deduplication key does not contain the time, so restarts, several
 *       workers and moving the session never produce a second one.</li>
 *   <li>Reminders are stored in the inbox and sent by push; the profile preference controls both.</li>
 * </ul>
 */
@Service
public class TrainingReminderService {

    private static final Logger log = LoggerFactory.getLogger(TrainingReminderService.class);

    private final ReminderProperties properties;
    private final TrainingService trainings;
    private final RegistrationRepository registrations;
    private final MembershipDirectory directory;
    private final UserAccountService users;
    private final NotificationOutbox outbox;
    private final Clock clock;

    TrainingReminderService(ReminderProperties properties, TrainingService trainings,
                            RegistrationRepository registrations, MembershipDirectory directory,
                            UserAccountService users, NotificationOutbox outbox,
                            Clock clock) {
        this.properties = properties;
        this.trainings = trainings;
        this.registrations = registrations;
        this.directory = directory;
        this.users = users;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** @return number of reminders written to the outbox by this run */
    @Transactional
    public int enqueueDue() {
        if (!properties.enabled()) {
            return 0;
        }
        Instant now = clock.instant();
        int queued = 0;
        for (SessionRef session : trainings.scheduledStartingBefore(now, now.plus(properties.lead()))) {
            Instant due = session.startAt().minus(properties.lead());
            List<Long> registered = registrations.registeredAtOrBefore(session.trainingId(), due);
            if (registered.isEmpty()) {
                continue;
            }
            Map<Long, MemberRef> members = directory.findAll(registered);
            List<Long> userIds = members.values().stream().map(MemberRef::userId).toList();
            Set<Long> wantingReminders = users.withTrainingReminders(userIds);
            for (MemberRef member : members.values()) {
                if (!wantingReminders.contains(member.userId())) {
                    continue;
                }
                Map<String, Object> payload = new HashMap<>(session.emailValues());
                payload.put("firstName", member.firstName());
                String key = "TRAINING_REMINDER:session-" + session.trainingId() + ":membership-" + member.membershipId();
                OutboxMessage reminder = OutboxMessage.of(EmailEventType.TRAINING_REMINDER, member.userId(),
                                member.email(), payload, key)
                        .forSession(session.trainingId())
                        .expiresAt(session.startAt());
                if (outbox.enqueue(reminder) == EnqueueResult.QUEUED) {
                    queued++;
                }
            }
        }
        if (queued > 0) {
            log.info("Queued {} training reminder(s)", queued);
        }
        return queued;
    }
}
