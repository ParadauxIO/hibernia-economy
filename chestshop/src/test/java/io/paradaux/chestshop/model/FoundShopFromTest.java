package io.paradaux.chestshop.model;

import io.paradaux.treasury.api.market.ShopResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Coverage for {@link FoundShop#from} — the registry-row → FoundShop factory + distance maths. */
class FoundShopFromTest {

    private ShopResult row(String world, int x, int y, int z, int batch) {
        ShopResult r = mock(ShopResult.class);
        lenient().when(r.world()).thenReturn(world);
        lenient().when(r.signX()).thenReturn(x);
        lenient().when(r.signY()).thenReturn(y);
        lenient().when(r.signZ()).thenReturn(z);
        lenient().when(r.itemKey()).thenReturn("DIAMOND");
        lenient().when(r.itemName()).thenReturn("Diamond");
        lenient().when(r.material()).thenReturn("DIAMOND");
        lenient().when(r.itemCustom()).thenReturn(false);
        lenient().when(r.itemData()).thenReturn(null);
        lenient().when(r.ownerName()).thenReturn("Steve");
        lenient().when(r.buyPrice()).thenReturn(new BigDecimal("10"));
        lenient().when(r.sellPrice()).thenReturn(new BigDecimal("5"));
        lenient().when(r.batchQty()).thenReturn(batch);
        lenient().when(r.currentStock()).thenReturn(64);
        lenient().when(r.estimatedCapacity()).thenReturn(128);
        return r;
    }

    @Test
    void from_computesSquaredDistance_sameWorldWithPosition() {
        FoundShop shop = FoundShop.from(row("world", 3, 4, 0, 1), "world", 0, 0, 0, true);
        assertThat(shop.distanceSquared()).isEqualTo(3L * 3 + 4L * 4); // 25
        assertThat(shop.world()).isEqualTo("world");
        assertThat(shop.itemKey()).isEqualTo("DIAMOND");
    }

    @Test
    void from_infiniteDistance_whenPlayerHasNoPosition() {
        FoundShop shop = FoundShop.from(row("world", 3, 4, 0, 1), "world", 0, 0, 0, false);
        assertThat(shop.distanceSquared()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void from_infiniteDistance_whenQueryWorldIsNull() {
        FoundShop shop = FoundShop.from(row("world", 3, 4, 0, 1), null, 0, 0, 0, true);
        assertThat(shop.distanceSquared()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void from_infiniteDistance_forACrossWorldShop() {
        FoundShop shop = FoundShop.from(row("nether", 3, 4, 0, 1), "world", 0, 0, 0, true);
        assertThat(shop.distanceSquared()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void from_clampsBatchQtyToAtLeastOne() {
        assertThat(FoundShop.from(row("world", 0, 0, 0, 0), "world", 0, 0, 0, true).batchQty()).isEqualTo(1);
        assertThat(FoundShop.from(row("world", 0, 0, 0, 16), "world", 0, 0, 0, true).batchQty()).isEqualTo(16);
    }
}
