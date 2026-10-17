package io.paradaux.common;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the cross-trust-boundary invariant (ADT-22/23): mint and verify derive
 * the signing key through this one method, so it must be deterministic and stable.
 */
class JwtKeysTest {

    @Test
    void deriveHmacKey_isDeterministic_andProducesA256BitHmacKey() {
        SecretKey a = JwtKeys.deriveHmacKey("a-long-enough-shared-secret-value-123456");
        SecretKey b = JwtKeys.deriveHmacKey("a-long-enough-shared-secret-value-123456");

        assertEquals("HmacSHA256", a.getAlgorithm());
        assertEquals(32, a.getEncoded().length, "SHA-256 → 256-bit key");
        assertArrayEquals(a.getEncoded(), b.getEncoded(), "same secret must derive the same key");
    }

    @Test
    void differentSecrets_deriveDifferentKeys() {
        assertFalse(Arrays.equals(
                JwtKeys.deriveHmacKey("secret-one").getEncoded(),
                JwtKeys.deriveHmacKey("secret-two").getEncoded()));
    }

    @Test
    void base64Secret_usesTheDecodedBytesDirectlyAsTheKey() {
        byte[] random = new byte[48];
        for (int i = 0; i < random.length; i++) random[i] = (byte) (i * 7 + 1);
        String secret = "base64:" + Base64.getEncoder().encodeToString(random);

        SecretKey key = JwtKeys.deriveHmacKey(secret);
        assertEquals("HmacSHA256", key.getAlgorithm());
        assertArrayEquals(random, key.getEncoded(), "key material must be used verbatim, not re-hashed");
    }

    @Test
    void base64Secret_belowMinimumKeyLength_isRejected() {
        String tooShort = "base64:" + Base64.getEncoder().encodeToString(new byte[16]); // 128-bit
        assertThrows(IllegalStateException.class, () -> JwtKeys.deriveHmacKey(tooShort));
    }

    @Test
    void nullSecret_isRejected() {
        assertThrows(IllegalStateException.class, () -> JwtKeys.deriveHmacKey(null));
    }

    @Test
    void emptySecret_isRejected() {
        assertThrows(IllegalStateException.class, () -> JwtKeys.deriveHmacKey(""));
    }

    @Test
    void blankSecret_isRejected() {
        assertThrows(IllegalStateException.class, () -> JwtKeys.deriveHmacKey("   "));
    }

    @Test
    void legacyPassphrase_derivesKeyByPlainSha256OfUtf8Bytes() throws NoSuchAlgorithmException {
        String passphrase = "legacy-passphrase-at-least-32-chars-long";
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest(passphrase.getBytes(StandardCharsets.UTF_8));

        SecretKey key = JwtKeys.deriveHmacKey(passphrase);
        assertEquals("HmacSHA256", key.getAlgorithm());
        assertArrayEquals(expected, key.getEncoded(),
                "legacy path must be exactly SHA-256(secret UTF-8) — pinned so mint/verify can't drift");
    }

    @Test
    void rotatingFromPassphraseToKeyMaterial_changesTheKey_soOldTokensStopVerifying() {
        byte[] random = new byte[32];
        Arrays.fill(random, (byte) 0x5A);
        String keyMaterial = "base64:" + Base64.getEncoder().encodeToString(random);

        assertFalse(Arrays.equals(
                JwtKeys.deriveHmacKey("legacy-passphrase-at-least-32-chars-long").getEncoded(),
                JwtKeys.deriveHmacKey(keyMaterial).getEncoded()),
                "rotation must change the signing key (invalidating old tokens)");
    }
}
