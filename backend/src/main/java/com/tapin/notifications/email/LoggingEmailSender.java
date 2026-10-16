package com.tapin.notifications.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Substitute provider ({@code tapin.mail.provider=log}): accepts every message and only logs recipient and subject.
 * The body is never logged because it may contain one-time links.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public SendResult send(EmailMessage message) {
        log.info("[mail:log] to={} subject=\"{}\" messageId={}", message.to(), message.subject(), message.messageId());
        return new SendResult.Accepted(message.messageId());
    }

    @Override
    public String name() {
        return "log";
    }
}
