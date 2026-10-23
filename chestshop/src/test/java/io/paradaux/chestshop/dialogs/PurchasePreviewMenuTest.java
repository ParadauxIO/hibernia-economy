package io.paradaux.chestshop.dialogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.PurchasePreviewService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.hibernia.framework.curator.MenuManager;
import io.paradaux.hibernia.framework.i18n.Message;
import java.math.BigDecimal;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PurchasePreviewMenuTest extends ServerTest {
    @Test
    void inspectingCannotTakeItemsAndClosingCancelsPurchase() {
        var service = mock(PurchasePreviewService.class);
        var offer = offer();
        var menu = menu(service);
        var player = server.addPlayer();
        menu.open(player, PurchasePreviewMenu.class, offer);
        var top = player.getOpenInventory().getTopInventory();
        assertThat(top.getItem(0)).isEqualTo(offer.stock()[0]);
        var click = new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.SHIFT_LEFT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY);
        menu.onClick(click);
        assertThat(click.isCancelled()).isTrue();
        server.getScheduler().performOneTick();
        player.closeInventory();
        assertThat(player.getInventory().contains(Material.POTION)).isFalse();
        verify(service, never()).confirm(any(), any());
    }

    @Test
    void confirmationRunsOnceAfterClosingMenu() {
        var service = mock(PurchasePreviewService.class);
        var offer = offer();
        var menu = menu(service);
        var player = server.addPlayer();
        menu.open(player, PurchasePreviewMenu.class, offer);
        var original = player.getOpenInventory().getTopInventory();
        doAnswer(call -> {
                    assertThat(player.getOpenInventory().getTopInventory()).isNotSameAs(original);
                    return true;
                })
                .when(service)
                .confirm(offer, player);
        for (int i = 0; i < 2; i++) {
            menu.onClick(new InventoryClickEvent(
                    player.getOpenInventory(),
                    InventoryType.SlotType.CONTAINER,
                    49,
                    ClickType.LEFT,
                    InventoryAction.PICKUP_ALL));
        }
        server.getScheduler().performOneTick();
        verify(service).confirm(offer, player);
    }

    @Test
    void closingBeforeQueuedConfirmationPreventsPurchase() {
        var service = mock(PurchasePreviewService.class);
        var menu = menu(service);
        var player = server.addPlayer();
        menu.open(player, PurchasePreviewMenu.class, offer());
        menu.onClick(new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                49,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
        player.closeInventory();
        server.getScheduler().performOneTick();
        verify(service, never()).confirm(any(), any());
    }

    private PurchasePreviewService.Offer offer() {
        var offer = mock(PurchasePreviewService.Offer.class);
        var drink = item(Material.POTION, 2);
        drink.editMeta(meta -> {
            meta.displayName(Component.text("Carlton Dry"));
            meta.lore(java.util.List.of(Component.text("A specific variation")));
        });
        when(offer.stock()).thenReturn(new org.bukkit.inventory.ItemStack[] {drink});
        when(offer.price()).thenReturn(new BigDecimal("10"));
        return offer;
    }

    private MenuManager menu(PurchasePreviewService service) {
        var message = mock(Message.class);
        when(message.component(anyString(), any(Object[].class))).thenReturn(Component.text("Preview"));
        var economy = mock(EconomyService.class);
        when(economy.format(any())).thenReturn("$10");
        var manager = new MenuManager(
                MockBukkit.createMockPlugin(),
                Set.of(new PurchasePreviewMenu(service, economy, message)),
                mock(com.google.inject.Injector.class));
        manager.registerAll();
        return manager;
    }
}
