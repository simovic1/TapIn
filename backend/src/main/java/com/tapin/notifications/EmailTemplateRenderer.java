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

    /** Values for messages queued before a placeholder existed (older outbox rows stay renderable). */
    private static final Map<String, Object> DEFAULTS = Map.of("trainingName", "Trening");

    /** {@code {name}} is required; {@code {name?}} is optional (empty when missing, e.g. in older outbox rows). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)(\\?)?}");
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
                    "Prijava zaprimljena: {trainingName}, {trainingTime}",
                    "Bok {firstName},\n\nprijavljeni ste na trening „{trainingName}” – {trainingTime}.\n"
                            + "Prijava nije potvrda dolaska: dolazak evidentira trener na treningu.\n\n"
                            + "Ako ne možete doći, otkažite prijavu: {trainingUrl?}"),
            EmailEventType.REGISTRATION_CANCELLED, new Template(
                    "Otkazali ste prijavu: {trainingName}, {trainingTime}",
                    "Bok {firstName},\n\nvaša prijava na trening „{trainingName}” – {trainingTime} je otkazana.\n"
                            + "Trening se i dalje održava prema rasporedu; ponovno se možete prijaviti: {trainingUrl?}"),
            EmailEventType.TRAINING_REMINDER, new Template(
                    "Podsjetnik: {trainingName}, {trainingTime}",
                    "Bok {firstName},\n\npodsjećamo vas da ste prijavljeni na trening „{trainingName}” – {trainingTime}.\n\n"
                            + "Ako ne možete doći, otkažite prijavu: {trainingUrl?}\n"
                            + "Podsjetnike možete isključiti u postavkama profila."),
            EmailEventType.TRAINING_CANCELLED, new Template(
                    "Trening je otkazan: {trainingName}, {trainingTime}",
                    "Bok {firstName},\n\ntrener je otkazao trening „{trainingName}” – {trainingTime}.{reasonLine?}\n"
                            + "Na ovaj termin ne trebate doći. Raspored: {trainingUrl?}"),
            EmailEventType.TRAINING_RESCHEDULED, new Template(
                    "Promijenjen termin: {trainingName}, sada {trainingTime}",
                    "Bok {firstName},\n\ntrening „{trainingName}” premješten je s {previousTrainingTime} "
                            + "na {trainingTime}.\nVaša prijava vrijedi za novi termin. Detalji: {trainingUrl?}"),
            EmailEventType.ATTENDANCE_RECORDED, new Template(
                    "Evidencija dolaska: {trainingName}, {trainingTime}",
                    "Bok {firstName},\n\nza trening „{trainingName}” – {trainingTime} evidentirano je: "
                            + "{attendanceStatus}.\nAko evidencija nije točna, javite se treneru. Trening: {trainingUrl?}")));

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
            Object value = payload.getOrDefault(matcher.group(1), DEFAULTS.get(matcher.group(1)));
            if (value == null && matcher.group(2) != null) {
                value = "";
            }
            if (value == null) {
                throw new IllegalArgumentException("Missing payload value '" + matcher.group(1) + "' for " + type);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
