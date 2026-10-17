package com.tapin.notifications.email;

import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;

class GmailEmailSenderTest {
    private HttpServer server;
    private GmailEmailSender sender;
    private int tokenStatus = 200, sendStatus = 200, sends;
    private String tokenBody = "{\"access_token\":\"test-access\"}";
    private String sendBody = "{\"id\":\"gmail-123\"}";
    private String tokenRequest, raw, authorization;
    private final EmailMessage message = new EmailMessage("member@example.com", "Pozivnica čćž", "Poveznica za člana", "<stable@tapin>", "invite-1");

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/send", exchange -> {
            sends++;
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            raw = JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes()).path("raw").asText();
            byte[] body = sendBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(sendStatus, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        sender = new GmailEmailSender("club@gmail.com", "client", "secret+&", "refresh", URI.create(base + "/token"), URI.create(base + "/send"));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void deliversUtf8MimeWithStableIdAndEncodedCredentials() throws Exception {
        assertThat(sender.send(message)).isEqualTo(new SendResult.Accepted("gmail-123"));
        assertThat(tokenRequest).contains("grant_type=refresh_token", "client_secret=secret%2B%26", "refresh_token=refresh");
        assertThat(authorization).isEqualTo("Bearer test-access");
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
        assertThat(mime.getSubject()).isEqualTo(message.subject());
        assertThat(mime.getContent().toString()).contains(message.textBody());
        assertThat(mime.getMessageID()).isEqualTo(message.messageId());
        assertThat(mime.getFrom()[0].toString()).isEqualTo("club@gmail.com");
        assertThat(mime.getAllRecipients()[0].toString()).isEqualTo(message.to());
    }
    @Test void authorizationFailureDoesNotSendOrLeakProviderBody() {
        tokenStatus = 400; tokenBody = "secret refresh token invalid_grant";
        assertThat(sender.send(message)).isEqualTo(new SendResult.TransientFailure("Gmail authorization failed (HTTP 400); check OAuth configuration", false));
        assertThat(sends).isZero();
    }
    @Test void quotaFailureRetriesWithoutExposingBody() {
        sendStatus = 429; sendBody = "private recipient and body";
        assertThat(sender.send(message)).isEqualTo(new SendResult.TransientFailure("Gmail delivery HTTP 429", false));
    }
    @Test void serverFailureIsAmbiguous() {
        sendStatus = 503;
        assertThat(sender.send(message)).isEqualTo(new SendResult.TransientFailure("Gmail delivery HTTP 503", true));
    }
    @Test void invalidMessageIsPermanent() {
        sendStatus = 400;
        assertThat(sender.send(message)).isEqualTo(new SendResult.PermanentFailure("Gmail rejected message (HTTP 400)"));
    }
    @Test void malformedSuccessIsAmbiguousAndNotMarkedSent() {
        sendBody = "{}";
        assertThat(sender.send(message)).isEqualTo(new SendResult.TransientFailure("Gmail returned no message ID", true));
    }
    @Test void missingAccessTokenDoesNotSend() {
        tokenBody = "{}";
        assertThat(sender.send(message)).isInstanceOf(SendResult.TransientFailure.class);
        assertThat(sends).isZero();
    }
}
