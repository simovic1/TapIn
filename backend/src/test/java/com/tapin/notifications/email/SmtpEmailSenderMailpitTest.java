package com.tapin.notifications.email;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** SMTP adapter against a real Mailpit test mailbox (same image as docker-compose). */
@Testcontainers(disabledWithoutDocker = true)
class SmtpEmailSenderMailpitTest {

    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.31.6")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));

    @Test
    void deliversWithStableMessageIdAndIdempotencyHeader() throws Exception {
        SmtpEmailSender sender = new SmtpEmailSender(mailSender(MAILPIT.getMappedPort(1025)), "no-reply@tapin.local");

        SendResult result = sender.send(new EmailMessage("ana@example.com", "Prijavljeni ste na trening",
                "Bok Ana,\n\nčćžšđ – UTF-8 radi.", "<tapin-outbox-7@tapin.local>", "REGISTRATION_CONFIRMED:reg-7"));

        assertThat(result).isEqualTo(new SendResult.Accepted("<tapin-outbox-7@tapin.local>"));
        String messages = get("/api/v1/messages");
        assertThat(messages).contains("Prijavljeni ste na trening").contains("tapin-outbox-7@tapin.local");
        String id = messages.replaceAll("(?s).*\"ID\":\"([^\"]+)\".*", "$1");
        String headers = get("/api/v1/message/" + id + "/headers");
        assertThat(headers).contains("X-Tapin-Idempotency-Key").contains("REGISTRATION_CONFIRMED:reg-7");
    }

    @Test
    void unreachableServerIsATransientNonAmbiguousFailure() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SmtpEmailSender sender = new SmtpEmailSender(mailSender(closedPort), "no-reply@tapin.local");

        SendResult result = sender.send(new EmailMessage("ana@example.com", "S", "B", "<m@tapin.local>", "k"));

        assertThat(result).isInstanceOf(SendResult.TransientFailure.class);
        assertThat(((SendResult.TransientFailure) result).ambiguous()).isFalse();
    }

    private static JavaMailSenderImpl mailSender(int port) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(MAILPIT.getHost());
        mailSender.setPort(port);
        mailSender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
        mailSender.getJavaMailProperties().put("mail.smtp.timeout", "2000");
        return mailSender;
    }

    private static String get(String path) throws Exception {
        URI uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
        }
    }
}
