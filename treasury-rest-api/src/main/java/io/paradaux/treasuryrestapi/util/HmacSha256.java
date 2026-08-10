package io.paradaux.treasuryrestapi.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 helper for signing webhook deliveries. Mirrors the scheme used by
 * the tesks GitHub webhook receiver: the signature header is
 * {@code sha256=<lowercase-hex>} over the raw request body, so consumers verify
 * with a constant-time compare.
 */
public final class HmacSha256 {

    private HmacSha256() {}

    /** Returns {@code "sha256=" + hex(hmac(secret, body))} for the signature header. */
    public static String sign(String secret, byte[] body) {
        return "sha256=" + hex(secret, body);
    }

    public static String hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hex.encode(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time string compare, for consumers/tests verifying a signature. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
