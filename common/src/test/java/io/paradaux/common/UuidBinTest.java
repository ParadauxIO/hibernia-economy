package io.paradaux.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UuidBinTest {

    @Test
    void roundTripsAnyUuid() {
        UUID u = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertEquals(u, UuidBin.fromBytes(UuidBin.toBytes(u)));
        assertEquals(16, UuidBin.toBytes(u).length);
    }

    @Test
    void nullSafe() {
        assertNull(UuidBin.toBytes(null));
        assertNull(UuidBin.fromBytes(null));
    }

    @Test
    void tooShortBufferIsRejected() {
        // ADT-147: 15 bytes previously threw an opaque BufferUnderflowException.
        assertThrows(IllegalArgumentException.class, () -> UuidBin.fromBytes(new byte[15]));
    }

    @Test
    void tooLongBufferIsRejected() {
        // ADT-147: 17 bytes previously silently dropped the trailing byte.
        assertThrows(IllegalArgumentException.class, () -> UuidBin.fromBytes(new byte[17]));
    }
}
