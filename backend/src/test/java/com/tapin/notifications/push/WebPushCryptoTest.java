package com.tapin.notifications.push;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPushCryptoTest {

    /** RFC 8291, Appendix A: the complete example message. */
    @Test
    void encryptsTheRfc8291Example() throws Exception {
        byte[] asPrivate = WebPushCrypto.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        byte[] asPublic = WebPushCrypto.decode(
                "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        KeyPair asKeys = new KeyPair(WebPushCrypto.publicKey(asPublic), WebPushCrypto.privateKey(asPrivate));
        byte[] uaPublic = WebPushCrypto.decode(
                "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] authSecret = WebPushCrypto.decode("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] salt = WebPushCrypto.decode("DGv6ra1nlYgDCS1FRnbzlw");

        byte[] body = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
                uaPublic, authSecret, asKeys, salt);

        assertThat(WebPushCrypto.encode(body)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlml"
                + "MoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void browserCanDecryptARandomlySaltedMessage() throws Exception {
        KeyPair browser = WebPushCrypto.generateKeyPair();
        byte[] uaPublic = WebPushCrypto.uncompressed((ECPublicKey) browser.getPublic());
        byte[] authSecret = new byte[16];
        Arrays.fill(authSecret, (byte) 7);
        String json = "{\"title\":\"Podsjetnik: Večernji trening\",\"body\":\"utorak u 18:30\"}";

        byte[] body = WebPushCrypto.encrypt(json.getBytes(StandardCharsets.UTF_8), uaPublic, authSecret);

        assertThat(new String(decrypt(body, (ECPrivateKey) browser.getPrivate(), uaPublic, authSecret),
                StandardCharsets.UTF_8)).isEqualTo(json);
    }

    @Test
    void rejectsMalformedSubscriptionKeysAndOversizedPayloads() throws Exception {
        byte[] uaPublic = WebPushCrypto.uncompressed((ECPublicKey) WebPushCrypto.generateKeyPair().getPublic());
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[1], new byte[65], new byte[16]))
                .hasMessageContaining("65 bytes");
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[1], uaPublic, new byte[8]))
                .hasMessageContaining("16 bytes");
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT + 1], uaPublic, new byte[16]))
                .hasMessageContaining("too large");
    }

    @Test
    void vapidSignatureVerifiesWithThePublicKey() throws Exception {
        KeyPair vapid = WebPushCrypto.generateKeyPair();
        byte[] data = "header.claims".getBytes(StandardCharsets.US_ASCII);
        byte[] signature = WebPushCrypto.signEs256((ECPrivateKey) vapid.getPrivate(), data);

        assertThat(signature).hasSize(64); // JWS r||s, not DER
        assertThat(WebPushCrypto.verifyEs256((ECPublicKey) vapid.getPublic(), data, signature)).isTrue();
    }

    @Test
    void senderRefusesMismatchedVapidKeysAtStartup() throws Exception {
        String publicKey = WebPushCrypto.encode(WebPushCrypto.uncompressed(
                (ECPublicKey) WebPushCrypto.generateKeyPair().getPublic()));
        String otherPrivate = WebPushCrypto.encode(privateScalar((ECPrivateKey) WebPushCrypto.generateKeyPair().getPrivate()));
        PushProperties properties = new PushProperties(publicKey, otherPrivate, "mailto:test@tapin.local",
                List.of("fcm.googleapis.com"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> new WebPushSender(properties, java.time.Clock.systemUTC()))
                .hasMessageContaining("not a key pair");
    }

    @Test
    void onlyHttpsEndpointsOfKnownPushServicesAreAllowed() {
        PushProperties properties = new PushProperties("k", "k", "mailto:x@y", List.of("fcm.googleapis.com", "push.apple.com"),
                Duration.ofSeconds(5));
        assertThat(properties.isAllowedEndpoint("https://fcm.googleapis.com/fcm/send/abc")).isTrue();
        assertThat(properties.isAllowedEndpoint("https://web.push.apple.com/QGx")).isTrue();
        assertThat(properties.isAllowedEndpoint("http://fcm.googleapis.com/fcm/send/abc")).isFalse();
        assertThat(properties.isAllowedEndpoint("https://evilfcm.googleapis.com.attacker.net/x")).isFalse();
        assertThat(properties.isAllowedEndpoint("https://169.254.169.254/latest")).isFalse();
        assertThat(properties.isAllowedEndpoint("https://user@fcm.googleapis.com/x")).isFalse();
        assertThat(properties.isAllowedEndpoint("not a url")).isFalse();
    }

    // ---------------------------------------------------------------- the browser's side (RFC 8291 decryption)

    static byte[] decrypt(byte[] body, ECPrivateKey uaPrivate, byte[] uaPublic, byte[] authSecret) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        assertThat(in.getInt()).isEqualTo(WebPushCrypto.RECORD_SIZE);
        byte[] asPublic = new byte[in.get()];
        in.get(asPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(uaPrivate);
        agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] keyInfo = WebPushCrypto.concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0},
                uaPublic, asPublic);
        byte[] ikm = WebPushCrypto.hkdf(authSecret, agreement.generateSecret(), keyInfo, 32);
        byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        assertThat(padded[padded.length - 1]).as("last-record delimiter").isEqualTo((byte) 2);
        return Arrays.copyOf(padded, padded.length - 1);
    }

    static byte[] privateScalar(ECPrivateKey key) {
        byte[] raw = key.getS().toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, 32 - length, length);
        return out;
    }
}
