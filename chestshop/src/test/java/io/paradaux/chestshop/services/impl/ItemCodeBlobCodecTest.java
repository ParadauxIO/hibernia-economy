package io.paradaux.chestshop.services.impl;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codec coverage for the PAR-290 / ADT-136 item-code blob migration: new blobs are
 * plain Base64 of UTF-8 bytes, and {@link ItemCodeServiceImpl#decodeBlob} must read both
 * that and the legacy Java-serialized-String blobs (a serialized {@code String} in plain
 * Base64) so the one-time migration can convert old rows without losing any. The live
 * re-write over {@code items.db} still needs a server smoke-test; this pins the decoder
 * logic the migration relies on.
 */
class ItemCodeBlobCodecTest {

    private static final String YAML =
            "==: org.bukkit.inventory.ItemStack\nv: 4189\ntype: STONE\n";

    /**
     * Reproduce the exact byte layout the old vendored {@code Base64.encodeObject(String)}
     * wrote — an {@code ObjectOutputStream}-serialized {@code String}, Base64 of those bytes,
     * no line breaks. Verified byte-identical to the vendored encoder before its removal.
     */
    private static String encodeObject(String value) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(value);
        }
        return java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    @Test
    void plainEncoding_roundTrips() throws Exception {
        String blob = ItemCodeServiceImpl.encodeBlob(YAML);
        assertThat(ItemCodeServiceImpl.decodeBlob(blob)).isEqualTo(YAML);
    }

    @Test
    void decode_readsLegacyJavaSerializedBlob() throws Exception {
        // What the old code wrote: a Java-serialized String via the vendored Base64.
        String legacyBlob = encodeObject(YAML);
        assertThat(ItemCodeServiceImpl.decodeBlob(legacyBlob)).isEqualTo(YAML);
    }

    @Test
    void plainAndLegacyEncodingsDiffer_soMigrationActuallyRewrites() throws Exception {
        String plain = ItemCodeServiceImpl.encodeBlob(YAML);
        String legacy = encodeObject(YAML);
        assertThat(plain).isNotEqualTo(legacy);
        // And both decode back to the same YAML — the migration is loss-free.
        assertThat(ItemCodeServiceImpl.decodeBlob(plain)).isEqualTo(ItemCodeServiceImpl.decodeBlob(legacy));
    }

    @Test
    void decode_emptyBlob_yieldsEmptyString() throws Exception {
        // An empty blob decodes to zero bytes (length < 2) -> plain empty string, no legacy path.
        assertThat(ItemCodeServiceImpl.decodeBlob("")).isEmpty();
    }

    @Test
    void decode_readsLineBrokenLegacyBlob() throws Exception {
        // Old wrapped legacy output: a legacy blob with newlines inserted (76-col wrapping). The
        // strict java.util.Base64 decoder rejects the newlines, so decodeBlob uses the vendored one.
        String legacy = encodeObject(YAML);
        StringBuilder wrapped = new StringBuilder();
        for (int i = 0; i < legacy.length(); i++) {
            wrapped.append(legacy.charAt(i));
            if ((i + 1) % 76 == 0) {
                wrapped.append('\n');
            }
        }
        wrapped.append('\n');
        assertThat(ItemCodeServiceImpl.decodeBlob(wrapped.toString())).isEqualTo(YAML);
    }

    @Test
    void decode_rejectsLegacyNonStringPayload() throws Exception {
        // A legacy blob serialising a non-String object must be rejected by the String-only filter.
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject(new java.util.ArrayList<>());
        }
        String blob = java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
        assertThat(java.util.Arrays.equals(new byte[]{(byte) 0xAC, (byte) 0xED},
                java.util.Arrays.copyOf(bos.toByteArray(), 2))).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ItemCodeServiceImpl.decodeBlob(blob))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void plainEncoding_isNotNativeDeserialization() {
        // A plain blob's bytes are the UTF-8 YAML itself — no ObjectOutputStream header.
        byte[] raw = java.util.Base64.getDecoder().decode(ItemCodeServiceImpl.encodeBlob(YAML));
        boolean javaSerialHeader = raw.length >= 2 && (raw[0] & 0xFF) == 0xAC && (raw[1] & 0xFF) == 0xED;
        assertThat(javaSerialHeader).isFalse();
    }
}
