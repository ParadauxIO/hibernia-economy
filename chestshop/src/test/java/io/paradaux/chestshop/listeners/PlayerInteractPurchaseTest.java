package io.paradaux.chestshop.listeners;

import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.*;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.hibernia.framework.curator.MenuManager;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerInteractPurchaseTest extends ServerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryBuyProcessesImmediatelyWithEitherButtonMapping(boolean reversed) {
        var config = TestConfigs.defaults();
        TestConfigs.with(config, "reverseButtons", reversed);
        var transactions = mock(TransactionService.class);
        var menus = mock(MenuManager.class);
        var variants = mock(ShopVariantsService.class);
        var signs = mock(SignService.class);
        when(signs.isValid(any(Sign.class))).thenReturn(true);
        var protection = mock(ProtectionService.class);
        when(protection.canAccess(any(org.bukkit.block.Block.class), any(), eq(true)))
                .thenReturn(true);
        var listener = new PlayerInteractListener(
                transactions,
                mock(InfoService.class),
                mock(AccountService.class),
                mock(ItemService.class),
                mock(Message.class),
                protection,
                config,
                signs,
                mock(ShopBlockService.class),
                mock(AdminBypassService.class),
                menus,
                variants);
        var block = server.addSimpleWorld("shops").getBlockAt(0, 64, 0);
        block.getWorld().loadChunk(0, 0);
        block.setType(Material.OAK_SIGN);
        var sign = (Sign) block.getState();
        sign.setLine(3, "STONE");
        sign.update();
        var buyer = player("Buyer");
        var action = reversed ? Action.LEFT_CLICK_BLOCK : Action.RIGHT_CLICK_BLOCK;
        var pending = mock(PendingTransaction.class);
        when(transactions.prepare(any(Sign.class), eq(buyer), eq(action))).thenReturn(pending);
        var event = mock(PlayerInteractEvent.class);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(buyer);
        when(event.getAction()).thenReturn(action);
        listener.onInteract(event);
        var order = inOrder(transactions);
        order.verify(transactions).prepare(any(Sign.class), eq(buyer), eq(action));
        order.verify(transactions).validate(pending);
        order.verify(transactions).process(any(Transaction.class));
        verifyNoInteractions(menus, variants);
    }
}
