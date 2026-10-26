package io.paradaux.treasury.utils;

import java.util.UUID;

/**
 * Thin alias for the shared {@link io.paradaux.common.UuidBin} (ADT-22). The
 * conversion logic now lives in :common; this keeps existing call sites working
 * against a single underlying implementation.
 */
public final class UuidBin {
    private UuidBin() {}

    public static byte[] toBytes(UUID uuid) {
        return io.paradaux.common.UuidBin.toBytes(uuid);
    }

    public static UUID fromBytes(byte[] bytes) {
        return io.paradaux.common.UuidBin.fromBytes(bytes);
    }
}
