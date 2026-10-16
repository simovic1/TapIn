package com.tapin.notifications.email;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * SMTP adapter (any SMTP relay or transactional provider's SMTP endpoint; Mailpit in development).
 * <p>
 * SMTP has no request idempotency. The stable {@code Message-ID} plus the {@code X-TapIn-Idempotency-Key} header
 * let providers/clients that deduplicate by them collapse a re-delivery, but a duplicate after an ambiguous
 * failure is possible.
 */
public class SmtpEmailSender implements EmailSender {

    static final String IDEMPOTENCY_HEADER = "X-TapIn-Idempotency-Key";

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public SendResult send(EmailMessage message) {
        MimeMessage mime;
        try {
            mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.textBody(), false);
            // Spring keeps an explicitly set Message-ID instead of generating a new one per attempt.
            mime.setHeader("Message-ID", message.messageId());
            mime.setHeader(IDEMPOTENCY_HEADER, message.idempotencyKey());
        } catch (MessagingException | IllegalArgumentException e) {
            return new SendResult.PermanentFailure("Invalid message: " + e.getMessage());
        }

        try {
            mailSender.send(mime);
            return new SendResult.Accepted(message.messageId());
        } catch (MailException e) {
            return classify(e);
        }
    }

    @Override
    public String name() {
        return "smtp";
    }

    static SendResult classify(MailException e) {
        if (e instanceof MailParseException || e instanceof MailPreparationException) {
            return new SendResult.PermanentFailure(describe(e));
        }
        if (e instanceof MailAuthenticationException) {
            // Configuration problem: retry so messages go out once credentials are fixed (until attempts run out).
            return new SendResult.TransientFailure("SMTP authentication failed: " + describe(e), false);
        }
        if (e instanceof MailSendException send) {
            for (Exception cause : send.getMessageExceptions()) {
                if (cause instanceof SendFailedException failed && failed.getInvalidAddresses() != null
                        && failed.getInvalidAddresses().length > 0) {
                    return new SendResult.PermanentFailure("Recipient rejected: " + describe(e));
                }
            }
        }
        Throwable root = rootCause(e);
        if (root instanceof ConnectException || root instanceof UnknownHostException) {
            // Nothing was transmitted: safe to retry.
            return new SendResult.TransientFailure("SMTP server unreachable: " + describe(e), false);
        }
        if (root instanceof SocketTimeoutException) {
            // The server may already have accepted the message before the timeout.
            return new SendResult.TransientFailure("SMTP timeout (delivery state unknown): " + describe(e), true);
        }
        return new SendResult.TransientFailure("SMTP error: " + describe(e), true);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == e && e instanceof MailSendException send && send.getMessageExceptions().length > 0) {
            return rootCause(send.getMessageExceptions()[0]);
        }
        return current;
    }

    private static String describe(Throwable e) {
        Throwable root = rootCause(e);
        String rootText = root == e ? "" : " (" + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
        return e.getClass().getSimpleName() + ": " + e.getMessage() + rootText;
    }
}
