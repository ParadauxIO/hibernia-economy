package io.paradaux.treasury.api.impl;

import io.paradaux.treasury.api.market.ShopResult;
import io.paradaux.treasury.api.market.ShopSearchQuery;
import io.paradaux.treasury.mappers.ShopQueryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShopQueryApiImpl}'s logic — argument validation, the
 * result cap, chunk-bound maths and the preview default — with a mocked mapper
 * (no DB). The SQL itself is exercised by {@code ShopQueryApiImplIT}.
 */
@ExtendWith(MockitoExtension.class)
class ShopQueryApiImplTest {

    @Mock
    ShopQueryMapper mapper;

    private static ShopResult row() {
        return new ShopResult("world", null, 0, 0, 0, false, null, null, null, "Steve",
                "DIAMOND", "DIAMOND", "Diamond", false, null,
                new BigDecimal("5.00"), null, 1, 10, 10, true, true);
    }

    @Test
    void searchShops_blankItemKey_throws() {
        ShopQueryApiImpl api = new ShopQueryApiImpl(mapper);
        assertThrows(IllegalArgumentException.class,
                () -> api.searchShops(ShopSearchQuery.builder().itemKey("  ").build()));
        assertThrows(IllegalArgumentException.class,
                () -> api.searchShops(ShopSearchQuery.builder().build()));
    }

    @Test
    void searchShops_delegatesWithQueryFields() {
        ShopQueryApiImpl api = new ShopQueryApiImpl(mapper);
        List<ShopResult> expected = List.of(row());
        when(mapper.searchShops(eq("DIAMOND"), eq(true), eq("world"), eq(50))).thenReturn(expected);

        List<ShopResult> result = api.searchShops(ShopSearchQuery.builder()
                .itemKey("DIAMOND").fuzzy(true).world("world").limit(50).build());

        assertSame(expected, result);
    }

    @Test
    void searchShops_atCap_stillReturns() {
        ShopQueryApiImpl api = new ShopQueryApiImpl(mapper);
        // Return exactly `limit` rows → the cap-hit warning branch.
        List<ShopResult> full = IntStream.range(0, 3).mapToObj(i -> row()).toList();
        when(mapper.searchShops(any(), any(Boolean.class), any(), eq(3))).thenReturn(full);

        List<ShopResult> result = api.searchShops(ShopSearchQuery.builder()
                .itemKey("DIAMOND").limit(3).build());

        assertEquals(3, result.size());
    }

    @Test
    void shopsInChunk_translatesChunkToBlockBounds() {
        ShopQueryApiImpl api = new ShopQueryApiImpl(mapper);
        api.shopsInChunk("world", 2, -1);
        // chunk 2 → x 32..47 ; chunk -1 → z -16..-1
        verify(mapper).shopsInChunk("world", 32, 47, -16, -1);
    }

    @Test
    void matchingItemKeys_nullSubstring_becomesEmpty() {
        ShopQueryApiImpl api = new ShopQueryApiImpl(mapper);
        api.matchingItemKeys(null, 0);
        // null → "" and a non-positive limit is floored to 1
        verify(mapper).matchingItemKeys("", 1);
    }

    @Test
    void previewVisible_defaultsWhenNull_elseStored() {
        ShopQueryApiImpl api = new ShopQueryApiImpl(mapper);
        UUID player = UUID.randomUUID();

        when(mapper.previewPreference(player)).thenReturn(null);
        assertTrue(api.previewVisible(player, true));
        assertFalse(api.previewVisible(player, false));

        when(mapper.previewPreference(player)).thenReturn(false);
        assertFalse(api.previewVisible(player, true));
    }
}
