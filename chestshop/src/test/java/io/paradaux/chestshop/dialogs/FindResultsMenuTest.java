package io.paradaux.chestshop.dialogs;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.services.FindPresentation;
import io.paradaux.chestshop.services.ShopDisplayService;
import io.paradaux.chestshop.support.ServerTest;
import java.math.BigDecimal;
import java.util.List;
import org.bukkit.event.inventory.*;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class FindResultsMenuTest extends ServerTest {
    @Test
    void resultClickCancelsItemMovementAndDefersGpsUntilNextTick() {
        var plugin = MockBukkit.createMockPlugin();
        var presentation = mock(FindPresentation.class);
        when(presentation.title(any(), any())).thenReturn(net.kyori.adventure.text.Component.text("Owner"));
        when(presentation.lore(any(), any())).thenReturn(List.of());
        when(presentation.command(any(), anyBoolean())).thenReturn("compass");
        var menu = manager(plugin, presentation);
        var player = server.addPlayer();
        var state = state();
        menu.open(player, FindResultsMenu.class, new FindResultsMenu.State(state));
        var view = player.getOpenInventory();
        var event = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, 0, ClickType.RIGHT, InventoryAction.PICKUP_ALL);
        menu.onClick(event);
        assertThat(event.isCancelled()).isTrue();
        verify(presentation, never()).navigate(any(), any(), anyBoolean());
        server.getScheduler().performOneTick();
        verify(presentation).navigate(player, state.results().getFirst(), false);
        assertThat(player.getOpenInventory().getTopInventory()).isNotSameAs(view.getTopInventory());
    }

    @Test
    void delayedActionCannotOperateAfterPlayerHasClosedTheMenu() {
        var plugin = MockBukkit.createMockPlugin();
        var presentation = mock(FindPresentation.class);
        when(presentation.title(any(), any())).thenReturn(net.kyori.adventure.text.Component.text("Owner"));
        when(presentation.lore(any(), any())).thenReturn(List.of());
        when(presentation.command(any(), anyBoolean())).thenReturn("compass");
        var menu = manager(plugin, presentation);
        var player = server.addPlayer();
        menu.open(player, FindResultsMenu.class, new FindResultsMenu.State(state()));
        menu.onClick(new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
        player.closeInventory();
        server.getScheduler().performOneTick();
        verify(presentation, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void rightClickWithoutGpsShowsTheStoredItemAndProtectsTheInspectionInventory() {
        var plugin = MockBukkit.createMockPlugin();
        var emptyActions = mock(FindPresentation.class);
        when(emptyActions.title(any(), any())).thenReturn(net.kyori.adventure.text.Component.text("Owner"));
        when(emptyActions.lore(any(), any())).thenReturn(List.of());
        when(emptyActions.command(any(), anyBoolean())).thenReturn("");
        var menu = manager(plugin, emptyActions);
        var player = server.addPlayer();
        var item = item(org.bukkit.Material.DIAMOND_SWORD, 1);
        var meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Named sword"));
        item.setItemMeta(meta);
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("item", item);
        var state = state();
        state.setResults(List.of(new FoundShop(
                "SWORD",
                "Sword",
                "DIAMOND_SWORD",
                true,
                yaml.saveToString(),
                "Owner",
                BigDecimal.ONE,
                null,
                1,
                1,
                1,
                "world",
                0,
                64,
                0,
                0)));
        menu.open(player, FindResultsMenu.class, new FindResultsMenu.State(state));
        menu.onClick(new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.RIGHT,
                InventoryAction.PICKUP_ALL));
        server.getScheduler().performOneTick();
        var inspection = player.getOpenInventory();
        assertThat(inspection.getTopInventory().getItem(13)).isEqualTo(item);
        var take = new InventoryClickEvent(
                inspection, InventoryType.SlotType.CONTAINER, 13, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        menu.onClick(take);
        assertThat(take.isCancelled()).isTrue();
        verify(emptyActions, never()).navigate(any(), any(), anyBoolean());
        menu.onClick(new InventoryClickEvent(
                inspection, InventoryType.SlotType.CONTAINER, 18, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        server.getScheduler().performOneTick();
        assertThat(player.getOpenInventory().getTopInventory().getSize()).isEqualTo(54);
        assertThat(player.getInventory().contains(org.bukkit.Material.DIAMOND_SWORD))
                .isFalse();
    }

    private io.paradaux.hibernia.framework.curator.MenuManager manager(
            org.bukkit.plugin.java.JavaPlugin plugin, FindPresentation presentation) {
        var message = mock(io.paradaux.hibernia.framework.i18n.Message.class);
        when(message.component(anyString(), any(Object[].class)))
                .thenReturn(net.kyori.adventure.text.Component.text("Find"));
        var handler = new FindResultsMenu(presentation, mock(ShopDisplayService.class), message);
        var manager = new io.paradaux.hibernia.framework.curator.MenuManager(
                plugin, java.util.Set.of(handler), mock(com.google.inject.Injector.class));
        manager.registerAll();
        return manager;
    }

    @Test
    void fortySixResultsUseOriginalFooterAndPreviewReturnsToSecondPage() {
        var presentation = mock(FindPresentation.class);
        when(presentation.title(any(), any()))
                .thenAnswer(
                        call -> net.kyori.adventure.text.Component.text(((FoundShop) call.getArgument(0)).itemKey()));
        when(presentation.lore(any(), any())).thenReturn(List.of());
        when(presentation.command(any(), anyBoolean())).thenReturn("");
        var menu = manager(MockBukkit.createMockPlugin(), presentation);
        var state = state();
        state.setFuzzy(true);
        state.setResults(java.util.stream.IntStream.range(0, 46)
                .mapToObj(i -> new FoundShop(
                        "STONE" + i,
                        "Stone",
                        "STONE",
                        false,
                        null,
                        "Owner",
                        BigDecimal.ONE,
                        null,
                        1,
                        64,
                        10,
                        "world",
                        i,
                        64,
                        1,
                        10))
                .toList());
        var player = server.addPlayer();
        menu.open(player, FindResultsMenu.class, new FindResultsMenu.State(state));
        var first = player.getOpenInventory().getTopInventory();
        assertThat(first.getSize()).isEqualTo(54);
        assertThat(first.getItem(44).getItemMeta().displayName())
                .isEqualTo(net.kyori.adventure.text.Component.text("STONE44")
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        assertThat(first.getItem(0).getItemMeta().getEnchantmentGlintOverride()).isTrue();
        assertThat(first.getItem(45).getType()).isEqualTo(org.bukkit.Material.ARROW);
        assertThat(first.getItem(48).getType()).isEqualTo(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        click(menu, player, 50, ClickType.LEFT);
        var second = player.getOpenInventory().getTopInventory();
        assertThat(second.getItem(1)).isNull();
        assertThat(second.getItem(48).getType()).isEqualTo(org.bukkit.Material.PAPER);
        assertThat(second.getItem(50).getType()).isEqualTo(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        var secondPageItem = second.getItem(0).clone();
        click(menu, player, 0, ClickType.RIGHT);
        assertThat(snapshot(second)).containsOnlyNulls();
        assertThat(player.getOpenInventory().getTopInventory().getItem(13).getType())
                .isEqualTo(org.bukkit.Material.STONE);
        player.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        server.getScheduler().performOneTick();
        assertThat(player.getOpenInventory().getTopInventory().getItem(0)).isEqualTo(secondPageItem);
        assertThat(player.getOpenInventory().getTopInventory().getItem(1)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = ClickType.class,
            names = {"RIGHT", "SHIFT_RIGHT", "LEFT", "SHIFT_LEFT"})
    void configuredCommandsPreserveLeftAndRightSemantics(ClickType click) {
        var presentation = mock(FindPresentation.class);
        when(presentation.title(any(), any())).thenReturn(net.kyori.adventure.text.Component.text("Owner"));
        when(presentation.lore(any(), any())).thenReturn(List.of());
        when(presentation.command(any(), anyBoolean())).thenReturn("compass");
        var menu = manager(MockBukkit.createMockPlugin(), presentation);
        var player = server.addPlayer();
        var state = state();
        menu.open(player, FindResultsMenu.class, new FindResultsMenu.State(state));
        click(menu, player, 0, click);
        verify(presentation).navigate(player, state.results().getFirst(), click.isLeftClick());
    }

    private void click(
            io.paradaux.hibernia.framework.curator.MenuManager menu,
            org.bukkit.entity.Player player,
            int slot,
            ClickType click) {
        var event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot, click, InventoryAction.PICKUP_ALL);
        menu.onClick(event);
        assertThat(event.isCancelled()).isTrue();
        server.getScheduler().performOneTick();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> transferAttempts() {
        return java.util.stream.Stream.of(false, true)
                .flatMap(preview -> java.util.stream.Stream.of(0, 13, 20, 49, 54, -999)
                        .flatMap(slot -> java.util.Arrays.stream(ClickType.values())
                                .map(click -> org.junit.jupiter.params.provider.Arguments.of(preview, slot, click))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("transferAttempts")
    void everyClickIsDeniedThroughRegisteredListeners(boolean preview, int slot, ClickType click) {
        var presentation = protectedPresentation();
        var manager = manager(MockBukkit.createMockPlugin(), presentation);
        var player = server.addPlayer();
        player.getInventory().setItem(0, item(org.bukkit.Material.DIAMOND, 7));
        player.getInventory().setItemInOffHand(item(org.bukkit.Material.EMERALD, 3));
        manager.open(player, FindResultsMenu.class, new FindResultsMenu.State(state()));
        if (preview) click(manager, player, 0, ClickType.RIGHT);
        player.setItemOnCursor(item(org.bukkit.Material.GOLD_INGOT, 5));
        var inventoryBefore = snapshot(player.getInventory());
        var cursorBefore = player.getItemOnCursor().clone();
        var top = player.getOpenInventory().getTopInventory();
        var topBefore = snapshot(top);
        InventoryAction action = switch (click) {
            case SHIFT_LEFT, SHIFT_RIGHT -> InventoryAction.MOVE_TO_OTHER_INVENTORY;
            case NUMBER_KEY, SWAP_OFFHAND -> InventoryAction.HOTBAR_SWAP;
            case DOUBLE_CLICK -> InventoryAction.COLLECT_TO_CURSOR;
            case DROP -> InventoryAction.DROP_ONE_SLOT;
            case CONTROL_DROP -> InventoryAction.DROP_ALL_SLOT;
            case MIDDLE, CREATIVE -> InventoryAction.CLONE_STACK;
            case RIGHT -> InventoryAction.PICKUP_HALF;
            default -> InventoryAction.PICKUP_ALL;
        };
        var event = new InventoryClickEvent(
                player.getOpenInventory(),
                slot < 0 ? InventoryType.SlotType.OUTSIDE : InventoryType.SlotType.CONTAINER,
                slot,
                click,
                action,
                click == ClickType.NUMBER_KEY ? 0 : -1);
        server.getPluginManager().callEvent(event);
        assertThat(event.isCancelled()).isTrue();
        assertThat(event.getResult()).isEqualTo(org.bukkit.event.Event.Result.DENY);
        assertThat(player.getItemOnCursor()).isEqualTo(cursorBefore);
        server.getScheduler().performOneTick();
        assertThat(snapshot(player.getInventory())).containsExactly(inventoryBefore);
        // MockBukkit clears the cursor when an allowed action opens a new view.
        // In-place interactions must preserve it; no callback may award a UI item.
        if (player.getOpenInventory().getTopInventory() == top) {
            assertThat(player.getItemOnCursor()).isEqualTo(cursorBefore);
            assertThat(snapshot(top)).containsExactly(topBefore);
        } else {
            assertThat(player.getItemOnCursor().getType())
                    .isIn(org.bukkit.Material.AIR, org.bukkit.Material.GOLD_INGOT);
            // Curator clears copied display items when the old view is retired.
            assertThat(snapshot(top)).containsOnlyNulls();
        }
        verify(presentation, never()).navigate(any(), any(), anyBoolean());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void dragAndCreativeEventsCannotMoveOrCloneItems(boolean preview) {
        var manager = manager(MockBukkit.createMockPlugin(), protectedPresentation());
        var player = server.addPlayer();
        player.setGameMode(org.bukkit.GameMode.CREATIVE);
        manager.open(player, FindResultsMenu.class, new FindResultsMenu.State(state()));
        if (preview) click(manager, player, 0, ClickType.RIGHT);
        var top = player.getOpenInventory().getTopInventory();
        var topBefore = snapshot(top);
        var before = snapshot(player.getInventory());
        player.setItemOnCursor(item(org.bukkit.Material.DIAMOND, 32));
        var cursor = player.getItemOnCursor().clone();
        for (var slots : List.of(
                java.util.Map.of(0, item(org.bukkit.Material.DIAMOND, 1)),
                java.util.Map.of(top.getSize(), item(org.bukkit.Material.DIAMOND, 1)),
                java.util.Map.of(
                        0,
                        item(org.bukkit.Material.DIAMOND, 1),
                        top.getSize(),
                        item(org.bukkit.Material.DIAMOND, 1)))) {
            var drag = new InventoryDragEvent(
                    player.getOpenInventory(), item(org.bukkit.Material.DIAMOND, 30), cursor.clone(), false, slots);
            server.getPluginManager().callEvent(drag);
            assertThat(drag.isCancelled()).isTrue();
        }
        var creative = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                preview ? 13 : 0,
                item(org.bukkit.Material.NETHERITE_BLOCK, 64));
        server.getPluginManager().callEvent(creative);
        assertThat(creative.isCancelled()).isTrue();
        server.getScheduler().performOneTick();
        assertThat(snapshot(top)).containsExactly(topBefore);
        assertThat(snapshot(player.getInventory())).containsExactly(before);
        assertThat(player.getItemOnCursor()).isEqualTo(cursor);
    }

    @Test
    void queuedClickCannotCloseOrModifyAnUnrelatedInventory() {
        var presentation = protectedPresentation();
        when(presentation.command(any(), anyBoolean())).thenReturn("compass");
        var manager = manager(MockBukkit.createMockPlugin(), presentation);
        var player = server.addPlayer();
        manager.open(player, FindResultsMenu.class, new FindResultsMenu.State(state()));
        var event = new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        assertThat(event.isCancelled()).isTrue();
        var other = chestWith(27, item(org.bukkit.Material.DIAMOND, 7));
        player.openInventory(other);
        server.getScheduler().performOneTick();
        assertThat(player.getOpenInventory().getTopInventory()).isSameAs(other);
        assertThat(other.getItem(0)).isEqualTo(item(org.bukkit.Material.DIAMOND, 7));
        verify(presentation, never()).navigate(any(), any(), anyBoolean());
    }

    private FindPresentation protectedPresentation() {
        var presentation = mock(FindPresentation.class);
        when(presentation.title(any(), any())).thenReturn(net.kyori.adventure.text.Component.text("Owner"));
        when(presentation.lore(any(), any())).thenReturn(List.of());
        when(presentation.command(any(), anyBoolean())).thenReturn("");
        return presentation;
    }

    private org.bukkit.inventory.ItemStack[] snapshot(org.bukkit.inventory.Inventory inventory) {
        return java.util.Arrays.stream(inventory.getContents())
                .map(item -> item == null ? null : item.clone())
                .toArray(org.bukkit.inventory.ItemStack[]::new);
    }

    private FindState state() {
        var state = new FindState(null, "STONE", "Stone", "world", 0, 0, 0, true);
        state.setResults(List.of(new FoundShop(
                "STONE",
                "Stone",
                "STONE",
                false,
                null,
                "Owner",
                BigDecimal.ONE,
                null,
                1,
                64,
                10,
                "world",
                1,
                64,
                1,
                10)));
        return state;
    }
}
