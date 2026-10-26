package io.paradaux.chestshop.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.ShopDestroyedEvent;
import com.google.inject.Guice;
import io.paradaux.chestshop.model.*;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.support.ServerTest;
import java.math.BigDecimal;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.event.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class LegacyCompatibilityTest extends ServerTest {
    @AfterEach
    void clearBridge() {
        LegacyCompatibility.clear();
    }

    @Test
    void externalPermissionDenialChangesNativeTradeAndCannotReenableARejectedTrade() {
        LegacyCompatibility.install(Guice.createInjector());
        var plugin = MockBukkit.createMockPlugin();
        server.getPluginManager()
                .registerEvents(
                        new Listener() {
                            @EventHandler
                            public void deny(PreTransactionEvent event) {
                                event.setCancelled(true);
                            }
                        },
                        plugin);
        var player = player("Buyer");
        var trade = new PendingTransaction(
                chest(9),
                player.getInventory(),
                new org.bukkit.inventory.ItemStack[] {item(Material.STONE, 1)},
                BigDecimal.ONE,
                player,
                new Account("Owner", java.util.UUID.randomUUID()),
                mock(Sign.class),
                Transaction.TransactionType.BUY,
                false);
        LegacyCompatibility.beforeTrade(trade);
        assertThat(trade.getTransactionOutcome())
                .isEqualTo(PendingTransaction.TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION);
        new PreTransactionEvent(trade).setCancelled(false);
        assertThat(trade.isCancelled()).isTrue();
    }

    @Test
    void externallyEmittedRemovalEntersNativeLifecycleExactlyOnce() {
        ShopService shops = mock(ShopService.class);
        LegacyCompatibility.install(Guice.createInjector());
        var plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(new LegacyCompatibility(shops), plugin);
        doAnswer(invocation -> {
                    LegacyCompatibility.destroyed(invocation.getArgument(0));
                    return null;
                })
                .when(shops)
                .onDestroyed(any());
        Sign sign = mock(Sign.class);
        server.getPluginManager().callEvent(new ShopDestroyedEvent(null, sign, null));
        verify(shops, times(1)).onDestroyed(argThat(shop -> shop.getSign() == sign));
        reset(shops);
        LegacyCompatibility.destroyed(new DestroyedShop(null, sign, null));
        verifyNoInteractions(shops);
    }

    @Test
    void protectionEventUpdatesTheNativeCarrier() {
        var check = new ProtectionCheck(mock(org.bukkit.block.Block.class), player("Owner"));
        var event = new com.Acrobot.ChestShop.Events.Protection.ProtectionCheckEvent(check);
        event.setResult(Event.Result.DENY);
        assertThat(check.getResult()).isEqualTo(Event.Result.DENY);
    }

    @Test
    void explicitNativeAliasWinsOverLegacyParserResult(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder)
            throws Exception {
        java.nio.file.Files.writeString(folder.resolve("itemAliases.yml"), "Stone: chosen\n");
        LegacyCompatibility.install(Guice.createInjector());
        var plugin = MockBukkit.createMockPlugin();
        server.getPluginManager()
                .registerEvents(
                        new Listener() {
                            @EventHandler
                            public void parse(com.Acrobot.ChestShop.Events.ItemParseEvent event) {
                                event.setItem(item(Material.DIAMOND, 1));
                            }
                        },
                        plugin);
        var codes = mock(io.paradaux.chestshop.services.ItemCodeService.class);
        when(codes.decode("Stone")).thenReturn(item(Material.STONE, 1));
        var items = new io.paradaux.chestshop.services.impl.ItemServiceImpl(
                codes,
                mock(io.paradaux.chestshop.services.MaterialService.class),
                mock(io.paradaux.chestshop.services.InventoryService.class),
                folder.toFile());
        assertThat(items.parse("chosen").getType()).isEqualTo(Material.STONE);
    }
}
