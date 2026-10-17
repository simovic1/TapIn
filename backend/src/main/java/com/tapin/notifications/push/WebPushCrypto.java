package com.tapin.notifications.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Web Push cryptography with the JDK only (no extra provider):
 * <ul>
 *   <li>message encryption, {@code aes128gcm} content coding (RFC 8188) with the Web Push key derivation (RFC 8291);</li>
 *   <li>VAPID ES256 signatures (RFC 8292) in JWS form (r||s, 64 bytes).</li>
 * </ul>
 */
public final class WebPushCrypto {

    /** Record size announced in the aes128gcm header; the whole message is a single record. */
    static final int RECORD_SIZE = 4096;
    /** Largest plaintext that fits one record: rs - 16 (GCM tag) - 1 (delimiter). */
    public static final int MAX_PLAINTEXT = RECORD_SIZE - 17;

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {
    }

    // ------------------------------------------------------------------ keys and encoding

    public static byte[] decode(String base64Url) {
        // Browsers and tools emit base64url with or without padding; accept standard base64 characters as well.
        String normalized = base64Url.trim().replace('+', '-').replace('/', '_').replace("=", "");
        return B64URL_DECODER.decode(normalized);
    }

    public static String encode(byte[] bytes) {
        return B64URL.encodeToString(bytes);
    }

    /** P-256 public key from its uncompressed encoding {@code 0x04 || X || Y}. */
    public static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new GeneralSecurityException("P-256 public key must be 65 bytes, uncompressed (0x04...)");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        // KeyFactory rejects points that are not on the curve.
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    /** P-256 private key from the raw 32-byte scalar d. */
    public static ECPrivateKey privateKey(byte[] d) throws GeneralSecurityException {
        if (d.length != 32) {
            throw new GeneralSecurityException("P-256 private key must be 32 bytes");
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
    }

    /** Uncompressed encoding {@code 0x04 || X || Y} (65 bytes). */
    public static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        copyFixed(key.getW().getAffineX(), out, 1);
        copyFixed(key.getW().getAffineY(), out, 33);
        return out;
    }

    static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    // ------------------------------------------------------------------ VAPID (RFC 8292)

    /** ES256 signature of {@code data} in JWS form (r||s). */
    public static byte[] signEs256(ECPrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(key);
        signature.update(data);
        return signature.sign();
    }

    static boolean verifyEs256(ECPublicKey key, byte[] data, byte[] jwsSignature) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initVerify(key);
        signature.update(data);
        return signature.verify(jwsSignature);
    }

    // ------------------------------------------------------------------ encryption (RFC 8291 / RFC 8188)

    /**
     * Encrypts {@code plaintext} for one subscription.
     *
     * @param uaPublic   the subscription's {@code p256dh} key (65 bytes)
     * @param authSecret the subscription's {@code auth} secret (16 bytes)
     * @return the request body: header {@code salt(16) || rs(4) || idlen(1) || as_public(65)} and one record
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generateKeyPair(), salt);
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt)
            throws GeneralSecurityException {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new GeneralSecurityException("Push payload too large: " + plaintext.length + " bytes");
        }
        if (authSecret.length != 16) {
            throw new GeneralSecurityException("Push auth secret must be 16 bytes");
        }
        ECPublicKey uaKey = publicKey(uaPublic);
        byte[] asPublic = uncompressed((ECPublicKey) asKeys.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(asKeys.getPrivate());
        agreement.doPhase(uaKey, true);
        byte[] ecdhSecret = agreement.generateSecret();

        // RFC 8291 §3.4: IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" || 0x00 || ua_public || as_public, 32)
        byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic);
        byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
        // RFC 8188 §2.2/2.3: content encryption key and nonce from the random salt.
        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        // Single (last) record: plaintext followed by the 0x02 delimiter, no padding.
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{2}));

        ByteBuffer body = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ciphertext.length);
        body.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(ciphertext);
        return body.array();
    }

    /** HKDF-SHA256 (RFC 5869) for output lengths up to 32 bytes (one expand block). */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt, ikm);
        byte[] okm = hmac(prk, concat(info, new byte[]{1}));
        return Arrays.copyOf(okm, length);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static void copyFixed(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray(); // may carry a leading sign byte or be shorter than 32 bytes
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, target, offset + 32 - length, length);
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available in this JDK", e);
        }
    }
}
