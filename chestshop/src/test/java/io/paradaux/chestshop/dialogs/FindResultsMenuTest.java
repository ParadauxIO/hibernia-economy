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
        var menu = new FindResultsMenu(plugin, presentation, mock(ShopDisplayService.class));
        var player = server.addPlayer();
        var state = state();
        menu.open(player, state);
        var view = player.getOpenInventory();
        var event = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, 10, ClickType.RIGHT, InventoryAction.PICKUP_ALL);
        menu.click(event);
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
        var menu = new FindResultsMenu(plugin, presentation, mock(ShopDisplayService.class));
        var player = server.addPlayer();
        menu.open(player, state());
        menu.click(new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                10,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
        player.closeInventory();
        server.getScheduler().performOneTick();
        verify(presentation, never()).navigate(any(), any(), anyBoolean());
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
