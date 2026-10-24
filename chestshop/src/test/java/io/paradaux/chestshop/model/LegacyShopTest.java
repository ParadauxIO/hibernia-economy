package io.paradaux.chestshop.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class LegacyShopTest {
    private static final String ROW = """
            {"world_uuid":"01234567-89ab-cdef-0123-456789abcdef",
             "pos_x":-12,"pos_y":64,"pos_z":3,"owner_name":"B:AB",
             "item_bytes":"AQID\\nBA==","buy_price":12.34,"sell_price":null,
             "quantity":16,"stock":0,"estimated_capacity":-1,
             "visible":0,"hologram":1,"last_updated":1735787045}
            """;

    @Test void readsExportWithoutLosingDecimalsFlagsOrSourceTime() {
        var row = LegacyShop.parse(ROW);
        assertEquals(new BigDecimal("12.34"), row.buyPrice());
        assertNull(row.sellPrice());
        assertEquals(-12, row.x());
        assertEquals(-1, row.capacity());
        assertFalse(row.visible());
        assertTrue(row.hologram());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, row.itemBytes());
        assertEquals(Instant.parse("2025-01-02T03:04:05Z"), row.observedAt());
    }

    @Test void validatesLegacyConstraintsAndAllowsSellOnlyShops() {
        String sellOnly = ROW.replace("\"buy_price\":12.34", "\"buy_price\":null")
                .replace("\"sell_price\":null", "\"sell_price\":1.25");
        assertEquals(new BigDecimal("1.25"), LegacyShop.parse(sellOnly).sellPrice());
        assertNull(LegacyShop.parse(ROW.replace("1735787045", "null")).observedAt());
        for (String invalid : new String[]{
                ROW.replace("\"stock\":0", "\"stock\":-1"),
                ROW.replace("\"estimated_capacity\":-1", "\"estimated_capacity\":-2"),
                ROW.replace("B:AB", " "),
                ROW.replace("AQID\\nBA==", ""),
                ROW.replace("12.34", "null"),
                ROW.replace("\"sell_price\":null", "\"sell_price\":-1")}) {
            assertThrows(IllegalArgumentException.class, () -> LegacyShop.parse(invalid));
        }
    }

    @Test void rejectsIncompleteOrCorruptRows() {
        assertThrows(RuntimeException.class, () -> LegacyShop.parse("{}"));
        assertThrows(RuntimeException.class, () -> LegacyShop.parse(ROW.replace("12.34", "-1")));
        assertThrows(RuntimeException.class, () -> LegacyShop.parse(ROW.replace("\"quantity\":16", "\"quantity\":0")));
        assertThrows(RuntimeException.class, () -> LegacyShop.parse(ROW.replace("\"visible\":0", "\"visible\":2")));
        assertThrows(RuntimeException.class, () -> LegacyShop.parse(ROW.replace("\"AQID\\nBA==\"", "null")));
    }
}
