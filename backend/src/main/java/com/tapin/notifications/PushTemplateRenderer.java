package com.tapin.notifications;



import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;



import java.net.URI;

import java.util.EnumMap;

import java.util.LinkedHashMap;

import java.util.Map;



/**

 * Croatian push texts, filled from the same outbox payload as the e-mail. The result is the JSON the service worker

 * shows ({@code frontend/src/sw.ts}): {@code title}, {@code body}, {@code url} (same-origin path to open on click)

 * and {@code tag} (a newer notification about the same training replaces the older one).

 */

@Component

class PushTemplateRenderer {



    private record Template(String title, String body) {

    }



    /** Only the training reminder is offered as push for now (spec §8.2); other types stay e-mail only. */

    private static final Map<EmailEventType, Template> TEMPLATES = new EnumMap<>(Map.of(

            EmailEventType.REGISTRATION_CONFIRMED, new Template("Prijava zaprimljena: {trainingName}", "{trainingTime}. Prijava nije potvrda dolaska."),

            EmailEventType.REGISTRATION_CANCELLED, new Template("Otkazali ste prijavu: {trainingName}", "{trainingTime}. Trening se i dalje održava."),

            EmailEventType.TRAINING_CANCELLED, new Template("Trening je otkazan: {trainingName}", "{trainingTime}.{reasonLine?}"),

            EmailEventType.TRAINING_RESCHEDULED, new Template("Promijenjen termin: {trainingName}", "S {previousTrainingTime} na {trainingTime}. Vaša prijava vrijedi za novi termin."),

            EmailEventType.ATTENDANCE_RECORDED, new Template("Evidencija dolaska: {trainingName}", "{trainingTime}: {attendanceStatus}. Ako evidencija nije točna, javite se treneru."),

            EmailEventType.TRAINING_REMINDER, new Template(

                    "Podsjetnik: {trainingName}",

                    "{trainingTime}. Ako ne možete doći, otkažite prijavu.")));



    private final JsonMapper jsonMapper;



    PushTemplateRenderer(JsonMapper jsonMapper) {

        this.jsonMapper = jsonMapper;

    }



    /** @throws IllegalArgumentException no push template for the type or a required value is missing */

    String render(EmailEventType type, Long sessionId, Map<String, Object> payload) {

        Template template = TEMPLATES.get(type);

        if (template == null) {

            throw new IllegalArgumentException("No push template for " + type);

        }

        Map<String, Object> message = new LinkedHashMap<>();

        message.put("title", EmailTemplateRenderer.fill(template.title(), payload, type));

        message.put("body", EmailTemplateRenderer.fill(template.body(), payload, type));

        message.put("url", path(payload.get("trainingUrl")));

        message.put("tag", sessionId == null ? type.name() : "training-" + sessionId);

        return jsonMapper.writeValueAsString(message);

    }



    /** Only the path of the absolute e-mail link: the notification opens the app on its own origin. */

    private static String path(Object url) {

        if (url == null) {

            return "/";

        }

        try {

            URI uri = URI.create(String.valueOf(url));

            String path = uri.getRawPath();

            return path == null || path.isEmpty() || !path.startsWith("/") || path.startsWith("//") ? "/" : path;

        } catch (IllegalArgumentException e) {

            return "/";

        }

    }

}

