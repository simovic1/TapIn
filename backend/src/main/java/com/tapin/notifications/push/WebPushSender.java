package com.tapin.notifications.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web Push over HTTP (RFC 8030) with VAPID authentication (RFC 8292) and {@code aes128gcm} payload encryption
 * (RFC 8291). Uses the JDK HTTP client and crypto only.
 */
public class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    /** VAPID tokens are cached per push service origin and renewed well before they expire (max 24 h). */
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    private static final Duration TOKEN_RENEW_BEFORE = Duration.ofHours(1);
    private static final Duration MAX_TTL = Duration.ofDays(28);

    private record Token(String jwt, long expiresAtEpochSecond) {
    }

    private final PushProperties properties;
    private final ECPrivateKey privateKey;
    private final String publicKey;
    private final HttpClient http;
    private final Clock clock;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    public WebPushSender(PushProperties properties, Clock clock) {
        this(properties, clock, HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WebPushSender(PushProperties properties, Clock clock, HttpClient http) {
        this.properties = properties;
        this.clock = clock;
        this.http = http;
        try {
            byte[] publicBytes = WebPushCrypto.decode(properties.vapidPublicKey());
            ECPublicKey vapidPublic = WebPushCrypto.publicKey(publicBytes);
            this.privateKey = WebPushCrypto.privateKey(WebPushCrypto.decode(properties.vapidPrivateKey()));
            // Fail at startup, not at the first reminder, when the two keys do not belong together.
            byte[] probe = "tapin-vapid-check".getBytes(StandardCharsets.US_ASCII);
            if (!WebPushCrypto.verifyEs256(vapidPublic, probe, WebPushCrypto.signEs256(privateKey, probe))) {
                throw new GeneralSecurityException("public and private key are not a key pair");
            }
            this.publicKey = WebPushCrypto.encode(publicBytes);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid VAPID keys (VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY): "
                    + e.getMessage(), e);
        }
    }

    @Override
    public PushResult send(Target target, String payload, Duration ttl) {
        if (!properties.isAllowedEndpoint(target.endpoint())) {
            return new PushResult.Gone("Endpoint host is not an allowed push service");
        }
        URI endpoint = URI.create(target.endpoint());
        byte[] body;
        try {
            body = WebPushCrypto.encrypt(payload.getBytes(StandardCharsets.UTF_8),
                    WebPushCrypto.decode(target.p256dh()), WebPushCrypto.decode(target.auth()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Broken subscription keys: the browser has to subscribe again.
            return new PushResult.Gone("Cannot encrypt for subscription: " + e.getMessage());
        }
        long ttlSeconds = Math.max(0, Math.min(ttl.toSeconds(), MAX_TTL.toSeconds()));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.timeout())
                .header("Authorization", "vapid t=" + token(origin(endpoint)) + ", k=" + publicKey)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", Long.toString(ttlSeconds))
                .header("Urgency", "high")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return new PushResult.TransientFailure("[webpush] " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PushResult.TransientFailure("[webpush] interrupted");
        }
        int status = response.statusCode();
        String detail = "[webpush] HTTP " + status + " " + abbreviate(response.body());
        if (status >= 200 && status < 300) {
            return new PushResult.Accepted(response.headers().firstValue("Location").orElse(null));
        }
        if (status == 404 || status == 410) {
            return new PushResult.Gone(detail);
        }
        if (status == 400 || status == 413) {
            return new PushResult.PermanentFailure(detail);
        }
        if (status == 401 || status == 403) {
            log.warn("Push service rejected the VAPID credentials ({}); check VAPID_* keys and VAPID_SUBJECT", detail);
        }
        return new PushResult.TransientFailure(detail);
    }

    private String token(String audience) {
        long now = clock.instant().getEpochSecond();
        Token cached = tokens.get(audience);
        if (cached != null && cached.expiresAtEpochSecond() - TOKEN_RENEW_BEFORE.toSeconds() > now) {
            return cached.jwt();
        }
        long exp = now + TOKEN_LIFETIME.toSeconds();
        String header = WebPushCrypto.encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = WebPushCrypto.encode(("{\"aud\":\"" + audience + "\",\"exp\":" + exp + ",\"sub\":\""
                + properties.vapidSubject() + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        try {
            String jwt = signingInput + "." + WebPushCrypto.encode(
                    WebPushCrypto.signEs256(privateKey, signingInput.getBytes(StandardCharsets.US_ASCII)));
            tokens.put(audience, new Token(jwt, exp));
            return jwt;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign VAPID token", e);
        }
    }

    /** RFC 8292 audience: the origin of the push resource. */
    static String origin(URI endpoint) {
        return endpoint.getScheme().toLowerCase(Locale.ROOT) + "://" + endpoint.getHost().toLowerCase(Locale.ROOT)
                + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String single = body.replaceAll("\\s+", " ").trim();
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }
}
