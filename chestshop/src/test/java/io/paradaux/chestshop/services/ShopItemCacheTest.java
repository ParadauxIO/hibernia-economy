package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.ShopItem;
import io.paradaux.chestshop.model.ShopItemCache;
import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class ShopItemCacheTest extends ServerTest {
    @Test
    void repeatedContentDecodesOnceAndReturnedStacksCannotCorruptTheCache() {
        var cache = new ShopItemCache();
        try (var decoder = mockStatic(ShopItem.class)) {
            decoder.when(() -> ShopItem.decode("DIAMOND", "data")).thenReturn(new ItemStack(Material.DIAMOND, 2));
            cache.decode("DIAMOND", "data").setAmount(7);
            ItemStack next = cache.decode("DIAMOND", "data");
            assertThat(next.getAmount()).isEqualTo(2);
            next.setType(Material.STONE);
            assertThat(cache.decode("DIAMOND", "data").getType()).isEqualTo(Material.DIAMOND);
            decoder.verify(() -> ShopItem.decode("DIAMOND", "data"), times(1));
            cache.clear();
            cache.decode("DIAMOND", "data");
            decoder.verify(() -> ShopItem.decode("DIAMOND", "data"), times(2));
        }
    }

    @Test
    void changedMetadataAndMaterialGetIndependentEntries() {
        var cache = new ShopItemCache();
        try (var decoder = mockStatic(ShopItem.class)) {
            decoder.when(() -> ShopItem.decode("DIAMOND", "old")).thenReturn(new ItemStack(Material.DIAMOND, 2));
            decoder.when(() -> ShopItem.decode("DIAMOND", "new")).thenReturn(new ItemStack(Material.DIAMOND, 3));
            decoder.when(() -> ShopItem.decode("STONE", "new")).thenReturn(new ItemStack(Material.STONE, 4));
            assertThat(cache.decode("DIAMOND", "old").getAmount()).isEqualTo(2);
            assertThat(cache.decode("DIAMOND", "new").getAmount()).isEqualTo(3);
            assertThat(cache.decode("STONE", "new").getAmount()).isEqualTo(4);
        }
    }

    @Test
    void leastRecentlyUsedEntryIsEvictedAndOversizedOrMissingItemsAreNotCached() {
        var cache = new ShopItemCache();
        try (var decoder = mockStatic(ShopItem.class)) {
            decoder.when(() -> ShopItem.decode(eq("DIAMOND"), anyString()))
                    .thenAnswer(call -> new ItemStack(Material.DIAMOND));
            for (int i = 0; i < 256; i++) cache.decode("DIAMOND", "data" + i);
            cache.decode("DIAMOND", "data0"); // keep the first; data1 is now oldest
            cache.decode("DIAMOND", "data256");
            cache.decode("DIAMOND", "data0");
            cache.decode("DIAMOND", "data1");
            decoder.verify(() -> ShopItem.decode("DIAMOND", "data0"), times(1));
            decoder.verify(() -> ShopItem.decode("DIAMOND", "data1"), times(2));
            String large = "a".repeat(65537);
            cache.decode("DIAMOND", large);
            cache.decode("DIAMOND", large);
            decoder.verify(() -> ShopItem.decode("DIAMOND", large), times(2));
            assertThat(cache.decode("UNKNOWN", "broken")).isNull();
            assertThat(cache.decode("UNKNOWN", "broken")).isNull();
            decoder.verify(() -> ShopItem.decode("UNKNOWN", "broken"), times(2));
        }
    }

    @Test
    void cacheEvictsLargeItemsBeforeReachingTheEntryCountLimit() {
        var cache = new ShopItemCache();
        String first = "a".repeat(60 * 1024);
        try (var decoder = mockStatic(ShopItem.class)) {
            decoder.when(() -> ShopItem.decode(eq("DIAMOND"), anyString()))
                    .thenAnswer(call -> new ItemStack(Material.DIAMOND));
            cache.decode("DIAMOND", first);
            for (int i = 0; i < 35; i++) cache.decode("DIAMOND", first + i);
            cache.decode("DIAMOND", first);
            decoder.verify(() -> ShopItem.decode("DIAMOND", first), times(2));
        }
    }
}
