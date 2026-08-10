package io.paradaux.common;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * UUID ↔ {@code BINARY(16)} conversion — the single source of truth (ADT-22).
 * Previously copy-pasted in treasury and treasury-api-plugin (and mirrored in
 * the rest-api type handler and the TS DAL). Big-endian most-significant-bits
 * first, matching the {@code uuid_to_bin(...)} / column layout used in SQL.
 */
public final class UuidBin {

    private UuidBin() {}

    public static byte[] toBytes(UUID uuid) {
        if (uuid == null) return null;
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null) return null;
        // ADT-147: a non-16-byte buffer previously either threw an opaque
        // BufferUnderflowException (too short) or silently dropped trailing bytes
        // (too long), yielding a wrong-but-plausible UUID. Fail loudly instead.
        if (bytes.length != 16) {
            throw new IllegalArgumentException(
                    "UUID BINARY value must be exactly 16 bytes, got " + bytes.length);
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
