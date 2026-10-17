package com.tapin.notifications.email;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

/**
 * Gmail HTTPS delivery with refresh-token authorization. Credentials and response bodies never enter logs.
 * Gmail has no send idempotency key: an ambiguous failure can produce a duplicate on retry.
 * Two bounded HTTP calls stay below the outbox lease; the stable MIME Message-ID survives retries.
 */
final class GmailEmailSender implements EmailSender {
    private final String from, clientId, clientSecret, refreshToken;
    private final URI tokenUri, sendUri;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    GmailEmailSender(String from, String clientId, String clientSecret, String refreshToken) {
        this(from, clientId, clientSecret, refreshToken,
            URI.create("https://oauth2.googleapis.com/token"),
            URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"));
    }
    GmailEmailSender(String from, String clientId, String clientSecret, String refreshToken, URI tokenUri, URI sendUri) {
        for (String value : new String[]{from, clientId, clientSecret, refreshToken}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Gmail requires MAIL_FROM and all GMAIL OAuth settings");
        }
        this.from = from; this.clientId = clientId; this.clientSecret = clientSecret;
        this.refreshToken = refreshToken; this.tokenUri = tokenUri; this.sendUri = sendUri;
    }
    @Override public String name() { return "gmail"; }

    @Override public SendResult send(EmailMessage message) {
        String raw;
        try {
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
            MimeMessageHelper helper = new MimeMessageHelper(mime, StandardCharsets.UTF_8.name());
            helper.setFrom(from); helper.setTo(message.to()); helper.setSubject(message.subject());
            helper.setText(message.textBody(), false);
            mime.saveChanges();
            mime.setHeader("Message-ID", message.messageId());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            mime.writeTo(bytes);
            raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            return new SendResult.PermanentFailure("Invalid Gmail message");
        }
        boolean sending = false;
        try {
            String form = "grant_type=refresh_token&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret) + "&refresh_token=" + encode(refreshToken);
            var tokenResponse = post(tokenUri, "application/x-www-form-urlencoded", form, null);
            if (tokenResponse.statusCode() != 200)
                return new SendResult.TransientFailure("Gmail authorization failed (HTTP " + tokenResponse.statusCode() + "); check OAuth configuration", false);
            String token = json.readTree(tokenResponse.body()).path("access_token").asText("");
            if (token.isBlank()) return new SendResult.TransientFailure("Gmail authorization returned no access token", false);
            sending = true;
            var response = post(sendUri, "application/json", json.writeValueAsString(Map.of("raw", raw)), token);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String id = json.readTree(response.body()).path("id").asText("");
                if (id.isBlank()) return new SendResult.TransientFailure("Gmail returned no message ID", true);
                return new SendResult.Accepted(id);
            }
            int status = response.statusCode();
            // 403 includes quota/permission errors; retry after configuration or quota recovery.
            if (status == 401 || status == 403 || status == 408 || status == 429 || status >= 500)
                return new SendResult.TransientFailure("Gmail delivery HTTP " + status, status == 408 || status >= 500);
            return new SendResult.PermanentFailure("Gmail rejected message (HTTP " + status + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult.TransientFailure("Gmail request interrupted", sending);
        } catch (Exception e) {
            return new SendResult.TransientFailure("Gmail request failed", sending);
        }
    }
    private HttpResponse<String> post(URI uri, String type, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
