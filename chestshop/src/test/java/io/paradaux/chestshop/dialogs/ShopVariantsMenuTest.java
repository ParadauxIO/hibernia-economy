package io.paradaux.chestshop.dialogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.services.*;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.hibernia.framework.curator.MenuManager;
import io.paradaux.hibernia.framework.i18n.Message;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

class ShopVariantsMenuTest extends ServerTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void emptyBuyAndSellCanCancelWithoutTradingOrMovingItems(boolean buying, boolean reversed) {
        var config = TestConfigs.defaults();
        TestConfigs.with(config, "reverseButtons", reversed);
        var previews = mock(PurchasePreviewService.class);
        var shops = mock(ShopBlockService.class);
        var items = mock(ItemService.class);
        when(items.wildcardName(any())).thenReturn("unrelated");
        var variants = new ShopVariantsService(new WildcardItems(items), shops, config, previews);
        var block = server.addSimpleWorld("shops").getBlockAt(0, 64, 0);
        block.setType(Material.OAK_SIGN);
        var sign = (Sign) block.getState();
        sign.setLine(3, "drink*");
        sign.update();
        var player = server.addPlayer();
        player.getInventory().setItem(0, item(Material.DIAMOND, 7));
        var stock = chestWith(54, item(Material.EMERALD, 3));
        var container = mock(org.bukkit.block.Container.class);
        when(container.getInventory()).thenReturn(stock);
        when(shops.findConnectedContainer(any(Sign.class))).thenReturn(container);
        when(previews.canUse(sign, player)).thenReturn(true);
        var buy = reversed ? Action.LEFT_CLICK_BLOCK : Action.RIGHT_CLICK_BLOCK;
        var sell = reversed ? Action.RIGHT_CLICK_BLOCK : Action.LEFT_CLICK_BLOCK;
        var selection = variants.choices(sign, player, buying ? buy : sell);
        assertThat(selection.variants()).isEmpty();
        verify(items).wildcardName(item(buying ? Material.EMERALD : Material.DIAMOND, buying ? 3 : 7));
        var playerBefore = snapshot(player.getInventory());
        var stockBefore = snapshot(stock);
        var manager = manager(variants, previews);
        manager.open(player, ShopVariantsMenu.class, selection);
        var top = player.getOpenInventory().getTopInventory();
        assertThat(top.getSize()).isEqualTo(54);
        assertThat(top.getItem(22).getType()).isEqualTo(Material.BARRIER);
        assertThat(top.getItem(22).getItemMeta().displayName()).isEqualTo(Component.text("shop.variants.empty"));
        assertThat(top.getItem(45).getItemMeta().displayName()).isEqualTo(Component.text("shop.purchase.cancel"));
        assertThat(top.getItem(48)).isNull();
        assertThat(top.getItem(50)).isNull();
        click(manager, player, 22);
        assertThat(player.getOpenInventory().getTopInventory()).isSameAs(top);
        click(manager, player, 45);
        assertThat(player.getOpenInventory().getTopInventory()).isNotSameAs(top);
        assertThat(snapshot(player.getInventory())).containsExactly(playerBefore);
        assertThat(snapshot(stock)).containsExactly(stockBefore);
        verify(previews).canUse(sign, player);
        verifyNoMoreInteractions(previews);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 45, 46, 91})
    void populatedMenusPageAndSelectionOpensConfirmationWithoutTrading(int count) {
        var variants = mock(ShopVariantsService.class);
        var previews = mock(PurchasePreviewService.class);
        List<ItemStack> choices = IntStream.range(0, count)
                .mapToObj(index -> {
                    var item = item(Material.POTION, 1);
                    item.editMeta(meta -> meta.displayName(Component.text("Variant " + index)));
                    return item;
                })
                .toList();
        var selection = new ShopVariantsService.Selection(null, "drink*", Action.RIGHT_CLICK_BLOCK, choices);
        var player = server.addPlayer();
        var offer = mock(PurchasePreviewService.Offer.class);
        when(offer.stock()).thenReturn(new ItemStack[] {choices.getLast()});
        when(offer.price()).thenReturn(BigDecimal.TEN);
        when(variants.preview(eq(selection), eq(player), eq(choices.getLast()))).thenReturn(offer);
        var manager = manager(variants, previews);
        manager.open(player, ShopVariantsMenu.class, selection);
        for (int page = 0; page <= (count - 1) / 45; page++) {
            var top = player.getOpenInventory().getTopInventory();
            int remaining = Math.min(45, count - page * 45);
            for (int slot = 0; slot < remaining; slot++) {
                assertThat(top.getItem(slot)).isEqualTo(choices.get(page * 45 + slot));
            }
            for (int slot = remaining; slot < 45; slot++)
                assertThat(top.getItem(slot)).isNull();
            if (page == 0) assertThat(top.getItem(48)).isNull();
            else assertThat(top.getItem(48).getType()).isEqualTo(Material.ARROW);
            if ((page + 1) * 45 < count) {
                assertThat(top.getItem(50).getType()).isEqualTo(Material.ARROW);
                click(manager, player, 50);
            } else assertThat(top.getItem(50)).isNull();
        }
        if (count > 45) {
            click(manager, player, 48);
            assertThat(player.getOpenInventory().getTopInventory().getItem(0))
                    .isEqualTo(choices.get(((count - 1) / 45 - 1) * 45));
            click(manager, player, 50);
        }
        click(manager, player, (count - 1) % 45);
        verify(variants).preview(selection, player, choices.getLast());
        assertThat(player.getOpenInventory().getTopInventory().getItem(0)).isEqualTo(choices.getLast());
        assertThat(player.getOpenInventory().getTopInventory().getItem(49)).isNotNull();
        verifyNoInteractions(previews);
        assertThat(player.getInventory().contains(Material.POTION)).isFalse();
    }

    private MenuManager manager(ShopVariantsService variants, PurchasePreviewService previews) {
        var message = mock(Message.class);
        when(message.component(anyString(), any(Object[].class)))
                .thenAnswer(call -> Component.text((String) call.getArgument(0)));
        var economy = mock(EconomyService.class);
        when(economy.format(any())).thenReturn("$10");
        var reference = new java.util.concurrent.atomic.AtomicReference<MenuManager>();
        var manager = new MenuManager(
                MockBukkit.createMockPlugin(),
                Set.of(
                        new ShopVariantsMenu(variants, reference::get, message),
                        new PurchasePreviewMenu(
                                previews,
                                economy,
                                message,
                                () -> mock(io.paradaux.hibernia.framework.usher.DialogManager.class))),
                mock(com.google.inject.Injector.class));
        reference.set(manager);
        manager.registerAll();
        return manager;
    }

    private void click(MenuManager manager, Player player, int slot) {
        var event = new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                slot,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        manager.onClick(event);
        assertThat(event.isCancelled()).isTrue();
        server.getScheduler().performOneTick();
    }

    private ItemStack[] snapshot(org.bukkit.inventory.Inventory inventory) {
        return java.util.Arrays.stream(inventory.getContents())
                .map(item -> item == null ? null : item.clone())
                .toArray(ItemStack[]::new);
    }
}
