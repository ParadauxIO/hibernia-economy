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

class FindDialogTest {
    @Test
    void successfulSearchOpensChestResultsForJavaToo() {
        var finder = mock(ShopFinderService.class);
        var menus = mock(MenuManager.class);
        var flow = mock(DialogFlow.class);
        var player = mock(Player.class);
        when(flow.player()).thenReturn(player);
        var state = new FindState(null, "STONE", "Stone", "world", 0, 0, 0, true);
        var results = List.of(new FoundShop(
                "STONE", "Stone", "STONE", false, null, "Owner", null, null, 1, 1, 1, "world", 0, 0, 0, 0));
        when(finder.available()).thenReturn(true);
        when(finder.find(state)).thenReturn(CompletableFuture.completedFuture(results));
        doAnswer(call -> {
                    BiConsumer<List<FoundShop>, DialogFlow> callback = call.getArgument(2);
                    callback.accept(results, flow);
                    return null;
                })
                .when(flow)
                .await(any(), any(Text.class), any());
        new FindDialog(finder, mock(Message.class), menus).submit(false, state, flow);
        var order = inOrder(flow, menus);
        order.verify(flow).close();
        order.verify(menus).open(eq(player), eq(FindResultsMenu.class), any(FindResultsMenu.State.class));
        verify(flow, never()).open("results");
    }
}
