package com.tapin.scheduling;

import com.tapin.notifications.EmailEventType;
import com.tapin.notifications.EnqueueResult;
import com.tapin.notifications.NotificationOutbox;
import com.tapin.notifications.OutboxMessage;
import com.tapin.scheduling.TrainingParticipants.Participant;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes "training cancelled" / "training moved" e-mails for registered members into the outbox, in the caller's
 * transaction (spec §8.1, AC-11). The deduplication key contains the id of the persisted session event, so a
 * retried request never produces a second e-mail for the same change.
 */
@Component
class TrainingNotifier {

    private static final String[] WEEKDAYS = {"ponedjeljak", "utorak", "srijeda", "četvrtak", "petak", "subota", "nedjelja"};
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d. M. yyyy. 'u' HH:mm");

    private final NotificationOutbox outbox;
    private final List<TrainingParticipants> participants;

    TrainingNotifier(NotificationOutbox outbox, List<TrainingParticipants> participants) {
        this.outbox = outbox;
        this.participants = participants;
    }

    /** @return {@code QUEUED} if at least one e-mail was written, otherwise {@code NOT_REQUIRED} */
    String cancelled(TrainingSession session, long eventId, ZoneId zone) {
        return notifyAll(session, EmailEventType.TRAINING_CANCELLED, eventId,
                Map.of("trainingTime", format(session.getStartAt(), zone)));
    }

    String rescheduled(TrainingSession session, Instant previousStart, long eventId, ZoneId zone) {
        return notifyAll(session, EmailEventType.TRAINING_RESCHEDULED, eventId, Map.of(
                "trainingTime", format(session.getStartAt(), zone),
                "previousTrainingTime", format(previousStart, zone)));
    }

    private String notifyAll(TrainingSession session, EmailEventType type, long eventId, Map<String, Object> values) {
        boolean queued = false;
        for (TrainingParticipants source : participants) {
            for (Participant p : source.activeRegistrations(session.getId())) {
                Map<String, Object> payload = new HashMap<>(values);
                payload.put("firstName", p.firstName());
                String key = type.name() + ":session-" + session.getId() + ":event-" + eventId
                        + ":membership-" + p.membershipId();
                EnqueueResult result = outbox.enqueue(
                        OutboxMessage.of(type, p.userId(), p.email(), payload, key).forSession(session.getId()));
                queued |= result == EnqueueResult.QUEUED;
            }
        }
        return queued ? EnqueueResult.QUEUED.notificationStatus() : EnqueueResult.DUPLICATE.notificationStatus();
    }

    /** "utorak 20. 10. 2026. u 18:30" in the club time zone. */
    static String format(Instant instant, ZoneId zone) {
        ZonedDateTime local = instant.atZone(zone);
        return WEEKDAYS[local.getDayOfWeek().getValue() - 1] + " " + DATE_TIME.format(local);
    }
}
