package com.tapin.notifications;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Croatian plain-text templates. Placeholders {@code {name}} are filled from the outbox payload; producers put
 * display-ready values there (dates already formatted in Europe/Zagreb). A missing placeholder is a programming
 * error and fails the message permanently instead of sending a broken e-mail.
 */
@Component
class EmailTemplateRenderer {

    record Rendered(String subject, String body) {
    }

    private record Template(String subject, String body) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final String FOOTER = "\n\n--\nOva poruka je automatski poslana iz aplikacije TapIn. Ne odgovarajte na nju.";

    private static final Map<EmailEventType, Template> TEMPLATES = new EnumMap<>(Map.of(
            EmailEventType.INVITATION, new Template(
                    "Pozivnica u {clubName}",
                    "Bok {firstName},\n\npozvani ste u aplikaciju TapIn za {clubName}.\n"
                            + "Račun aktivirate na poveznici:\n{invitationUrl}\n\nPoveznica vrijedi do {expiresAt}."),
            EmailEventType.PASSWORD_RESET, new Template(
                    "Promjena lozinke",
                    "Bok {firstName},\n\nzatražena je promjena lozinke. Novu lozinku postavite na poveznici:\n"
                            + "{resetUrl}\n\nPoveznica vrijedi do {expiresAt}. Ako niste vi zatražili promjenu, zanemarite ovu poruku."),
            EmailEventType.REGISTRATION_CONFIRMED, new Template(
                    "Prijavljeni ste na trening {trainingTime}",
                    "Bok {firstName},\n\nprijava na trening {trainingTime} je zaprimljena."),
            EmailEventType.REGISTRATION_CANCELLED, new Template(
                    "Prijava na trening {trainingTime} je otkazana",
                    "Bok {firstName},\n\nvaša prijava na trening {trainingTime} je otkazana."),
            EmailEventType.TRAINING_REMINDER, new Template(
                    "Podsjetnik: trening {trainingTime}",
                    "Bok {firstName},\n\npodsjećamo vas da ste prijavljeni na trening {trainingTime}."),
            EmailEventType.TRAINING_CANCELLED, new Template(
                    "Otkazan trening {trainingTime}",
                    "Bok {firstName},\n\ntrening {trainingTime} je otkazan."),
            EmailEventType.TRAINING_RESCHEDULED, new Template(
                    "Promijenjen termin treninga",
                    "Bok {firstName},\n\ntrening zakazan za {previousTrainingTime} premješten je na {trainingTime}."),
            EmailEventType.ATTENDANCE_RECORDED, new Template(
                    "Evidencija dolaska: trening {trainingTime}",
                    "Bok {firstName},\n\nza trening {trainingTime} evidentirano je: {attendanceStatus}.")));

    Rendered render(EmailEventType type, Map<String, Object> payload) {
        Template template = TEMPLATES.get(type);
        if (template == null) {
            throw new IllegalArgumentException("No e-mail template for " + type);
        }
        return new Rendered(fill(template.subject(), payload, type), fill(template.body(), payload, type) + FOOTER);
    }

    private static String fill(String text, Map<String, Object> payload, EmailEventType type) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = payload.get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException("Missing payload value '" + matcher.group(1) + "' for " + type);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
