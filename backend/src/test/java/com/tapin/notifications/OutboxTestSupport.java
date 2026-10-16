package com.tapin.notifications;

import org.springframework.context.ApplicationContext;

import java.util.Map;

/** Lets tests in other packages render outbox payloads with the real templates. */
public final class OutboxTestSupport {

    private OutboxTestSupport() {
    }

    public record RenderedEmail(String subject, String body) {
    }

    public static RenderedEmail render(ApplicationContext context, String eventType, Map<String, Object> payload) {
        EmailTemplateRenderer.Rendered rendered = context.getBean(EmailTemplateRenderer.class)
                .render(EmailEventType.valueOf(eventType), payload);
        return new RenderedEmail(rendered.subject(), rendered.body());
    }
}
