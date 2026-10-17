package io.paradaux.treasuryrestapi.util;

/**
 * Lowercase-hex encoding of byte arrays. Shared by the webhook secret generator
 * and the HMAC signature helper so the {@code byte[] → hex} loop lives in one place.
 */
public final class Hex {

    private Hex() {}

    /** Returns the lowercase-hex representation of {@code bytes}. */
    public static String encode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
