package io.paradaux.chestshop.dialogs;

import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.services.ShopFinderService;
import io.paradaux.hibernia.framework.curator.MenuManager;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.DialogFlow;
import io.paradaux.hibernia.framework.usher.Text;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class FindDialogTest extends io.paradaux.chestshop.support.ServerTest {
    @Test
    void damageablePreviewIsNonStackable_withoutChangingSearchItem() {
        var item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_SWORD);
        var meta = item.getItemMeta();
        meta.setMaxStackSize(64);
        item.setItemMeta(meta);
        var state = new FindState(item, "sword", "Sword", "world", 0, 0, 0, true);
        var dialog = new FindDialog(
                mock(ShopFinderService.class), mock(Message.class), mock(MenuManager.class), player -> false, null);
        var view = dialog.main(state);
        var body = (io.paradaux.hibernia.framework.usher.DialogView.Body.Item)
                view.bodies().getFirst();
        org.assertj.core.api.Assertions.assertThat(body.item().getMaxStackSize())
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(state.itemStack().getMaxStackSize())
                .isEqualTo(64);
    }

    @Test
    void successfulSearchOpensChestResultsForJavaToo() {
        var finder = mock(ShopFinderService.class);
        var menus = mock(MenuManager.class);
        var flow = mock(DialogFlow.class);
        var player = mock(Player.class);
        when(flow.player()).thenReturn(player);
        var state = new FindState(null, "STONE", "Stone", "world", 0, 0, 0, true);
        var results = List.of(new FoundShop(
                "STONE", "Stone", "STONE", false, null, "Owner", null, null, 1, 1, 1, "world", 0, 0, 0, 0, null,
                false));
        when(finder.available()).thenReturn(true);
        when(finder.find(state)).thenReturn(CompletableFuture.completedFuture(results));
        doAnswer(call -> {
                    BiConsumer<List<FoundShop>, DialogFlow> callback = call.getArgument(2);
                    callback.accept(results, flow);
                    return null;
                })
                .when(flow)
                .await(any(), any(Text.class), any());
        new FindDialog(finder, mock(Message.class), menus, p -> false, null).submit(false, state, flow);
        var order = inOrder(flow, menus);
        order.verify(flow).close();
        order.verify(menus).open(eq(player), eq(FindResultsMenu.class), any(FindResultsMenu.State.class));
        verify(flow, never()).open("results");
    }

    private static List<FoundShop> stoneResults() {
        return List.of(new FoundShop(
                "STONE", "Stone", "STONE", false, null, "Owner", null, null, 1, 1, 1, "world", 0, 0, 0, 0, null,
                false));
    }

    @Test
    void bedrockSearchReportsInChatInsteadOfWaitDialog() {
        var plugin = org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin();
        var player = server.addPlayer();
        var finder = mock(ShopFinderService.class);
        var menus = mock(MenuManager.class);
        var message = mock(Message.class);
        var flow = mock(DialogFlow.class);
        when(flow.player()).thenReturn(player);
        var state = new FindState(null, "STONE", "Stone", "world", 0, 0, 0, true);
        var pending = new CompletableFuture<List<FoundShop>>();
        when(finder.available()).thenReturn(true);
        when(finder.find(state)).thenReturn(pending);

        new FindDialog(finder, message, menus, p -> p == player, plugin).submit(false, state, flow);

        verify(flow, never()).await(any(), any(Text.class), any());
        var order = inOrder(flow, message);
        order.verify(flow).close();
        order.verify(message).send(player, "find.querying-chat");
        verifyNoInteractions(menus);

        pending.complete(stoneResults());
        server.getScheduler().performOneTick();
        verify(menus).open(eq(player), eq(FindResultsMenu.class), any(FindResultsMenu.State.class));
    }

    @Test
    void bedrockDetectionRunsOnEverySearch() {
        var plugin = org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin();
        var player = server.addPlayer();
        var finder = mock(ShopFinderService.class);
        var flow = mock(DialogFlow.class);
        when(flow.player()).thenReturn(player);
        var state = new FindState(null, "STONE", "Stone", "world", 0, 0, 0, true);
        when(finder.available()).thenReturn(true);
        when(finder.find(state)).thenReturn(new CompletableFuture<>());
        var bedrock = new java.util.concurrent.atomic.AtomicBoolean(false);
        var dialog = new FindDialog(finder, mock(Message.class), mock(MenuManager.class), p -> bedrock.get(), plugin);

        dialog.submit(false, state, flow);
        verify(flow).await(any(), any(Text.class), any());

        bedrock.set(true);
        dialog.submit(false, state, flow);
        verify(flow, times(1)).await(any(), any(Text.class), any());
        verify(flow).close();
    }

    @Test
    void bedrockSearchFailureTellsPlayerSearchIsUnavailable() {
        var plugin = org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin();
        var player = server.addPlayer();
        var finder = mock(ShopFinderService.class);
        var menus = mock(MenuManager.class);
        var message = mock(Message.class);
        var flow = mock(DialogFlow.class);
        when(flow.player()).thenReturn(player);
        var state = new FindState(null, "STONE", "Stone", "world", 0, 0, 0, true);
        when(finder.available()).thenReturn(true);
        when(finder.find(state)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db down")));

        new FindDialog(finder, message, menus, p -> true, plugin).submit(false, state, flow);
        server.getScheduler().performOneTick();

        verify(message).send(player, "find.no-search");
        verifyNoInteractions(menus);
    }
}
