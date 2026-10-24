package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class WildcardItemsTest extends ServerTest {
    @Test
    void chooserGroupsUnstackableCustomItemsButRetainsTheExactFirstInstance() {
        var items = mock(ItemService.class);
        var matcher = new WildcardItems(items);
        var first = item(Material.LEATHER, 1);
        first.editMeta(meta -> {
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer()
                    .set(
                            new org.bukkit.NamespacedKey("nexo", "backpack"),
                            org.bukkit.persistence.PersistentDataType.STRING,
                            "first-wallet");
        });
        var second = first.clone();
        second.editMeta(meta -> meta.getPersistentDataContainer()
                .set(
                        new org.bukkit.NamespacedKey("nexo", "backpack"),
                        org.bukkit.persistence.PersistentDataType.STRING,
                        "second-wallet"));
        when(items.wildcardName(any())).thenReturn("leather_wallet");
        when(items.customItemId(any())).thenReturn("leather_wallet");
        var inventory = chestWith(9, first, second);
        assertThat(matcher.variants("leather*", inventory)).hasSize(2);
        var choices = matcher.choices("leather*", inventory);
        assertThat(choices).hasSize(1);
        assertThat(choices.getFirst().isSimilar(first)).isTrue();
        assertThat(inventory.getItem(1).isSimilar(second)).isTrue();
        inventory.setItem(0, null);
        assertThat(matcher.choices("leather*", inventory).getFirst().isSimilar(second))
                .isTrue();
    }

    @Test
    void chooserKeepsDifferentCustomIdsAndVanillaMetadataVariants() {
        var items = mock(ItemService.class);
        var matcher = new WildcardItems(items);
        var wallet = item(Material.LEATHER, 1);
        wallet.editMeta(meta -> meta.setMaxStackSize(1));
        var bag = wallet.clone();
        bag.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Bag")));
        when(items.wildcardName(any())).thenReturn("leather_item");
        when(items.customItemId(wallet)).thenReturn("leather_wallet");
        when(items.customItemId(bag)).thenReturn("leather_bag");
        var inventory = chestWith(9, wallet, bag);
        assertThat(matcher.choices("leather*", inventory)).hasSize(2);
        when(items.customItemId(any())).thenReturn(null);
        assertThat(matcher.choices("leather*", inventory)).hasSize(2);
    }

    @Test
    void matchesDrinkIdsLiterallyAndKeepsDistinctMetadata() {
        var items = mock(ItemService.class);
        var matcher = new WildcardItems(items);
        var bottle = new ItemStack(Material.POTION, 3);
        var can = new ItemStack(Material.POTION, 2);
        can.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Can")));
        var unrelated = new ItemStack(Material.DIAMOND);
        when(items.wildcardName(bottle)).thenReturn("carlton_dry_bottle");
        when(items.wildcardName(can)).thenReturn("carlton_dry_can");
        when(items.wildcardName(unrelated)).thenReturn("other_drink");
        assertThat(matcher.matches("CARLTON_DRY*", bottle)).isTrue();
        assertThat(matcher.matches("carlton_dry*", can)).isTrue();
        assertThat(matcher.matches("carlton_dry*", unrelated)).isFalse();
        assertThat(matcher.matches("carlton.dry*", bottle)).isFalse();
        var inventory = server.createInventory(null, 9);
        inventory.setItem(0, bottle);
        inventory.setItem(1, can);
        inventory.setItem(2, bottle);
        inventory.setItem(3, unrelated);
        var variants = matcher.variants("carlton_dry*", inventory);
        assertThat(variants).hasSize(2);
        assertThat(variants.get(0).isSimilar(bottle)).isTrue();
        assertThat(variants.get(1).isSimilar(can)).isTrue();
        assertThat(variants).allSatisfy(item -> assertThat(item.getAmount()).isEqualTo(1));
        assertThat(inventory.getItem(0).getAmount()).isEqualTo(3);
    }

    @Test
    void absentOrEmptyStockNeverProducesAVariation() {
        var items = mock(ItemService.class);
        var matcher = new WildcardItems(items);
        assertThat(WildcardItems.isPattern(null)).isFalse();
        assertThat(matcher.variants("carlton_dry*", null)).isEmpty();
        assertThat(matcher.count("carlton_dry*", null)).isZero();
        assertThat(matcher.matches("carlton_dry*", null)).isFalse();
        assertThat(matcher.matches("carlton_dry*", item(Material.AIR, 1))).isFalse();
        assertThat(matcher.matches("*", item(Material.PAPER, 1))).isFalse();
        assertThat(matcher.matches("carlton_dry*", item(Material.PAPER, 1))).isFalse();
        var empty = item(Material.PAPER, 1);
        empty.setAmount(0);
        assertThat(matcher.matches("carlton_dry*", empty)).isFalse();
    }

    @Test
    void rejectsUnboundedAndMalformedPatterns() {
        for (String pattern : new String[] {"*", "foo**", "f*oo", "foo", "foo?*", "foo[bar]*", ""}) {
            assertThat(WildcardItems.isPattern(pattern)).as(pattern).isFalse();
        }
        assertThat(WildcardItems.isPattern("carlton_dry*")).isTrue();
    }
}
