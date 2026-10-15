package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.LegacyShop;
import io.paradaux.chestshop.services.*;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LegacyShopImportServiceImplTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final MarketService market = mock(MarketService.class);
    private final MarketApi api = mock(MarketApi.class);
    private final AccountService accounts = mock(AccountService.class);
    private final SignService signs = mock(SignService.class);
    private final UUID worldId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();
    private final LegacyShopImportServiceImpl importer = new LegacyShopImportServiceImpl(
            plugin, new File("unused"), market, accounts, signs);

    @BeforeEach void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld(worldId)).thenReturn(world);
        when(market.market()).thenReturn(api);
    }

    private LegacyShop row() {
        return new LegacyShop(worldId, -1, 64, 2, "Owner", new byte[]{1},
                BigDecimal.TEN, null, 16, 100, -1, false, false, Instant.EPOCH);
    }

    @Test void resolvesOwnerWorldAndItemWithoutLoadingChunks() {
        var owner = new MarketService.Owner(1, "PERSONAL", null, playerId, false);
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner", "Owner", playerId));
        when(market.ownerFromUuid(playerId, false)).thenReturn(owner);
        var item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        var record = mock(ChestShopShopRecord.class);
        when(market.shop(any(Location.class), eq(item), eq(owner), eq(BigDecimal.TEN), isNull(),
                eq(16), eq(100), isNull())).thenReturn(record);
        try (var stacks = mockStatic(ItemStack.class)) {
            stacks.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenReturn(item);
            importer.importRow(row());
        }
        verify(api).importShop(record, false, false, Instant.EPOCH);
        var location = org.mockito.ArgumentCaptor.forClass(Location.class);
        verify(market).shop(location.capture(), eq(item), eq(owner), eq(BigDecimal.TEN), isNull(),
                eq(16), eq(100), isNull());
        assertSame(world, location.getValue().getWorld());
        assertEquals(-1, location.getValue().getBlockX());
        verifyNoInteractions(world);
    }

    @Test void adminImportUsesAdminClassificationAndPropagatesWriteFailures() {
        when(signs.isAdminShop("Owner")).thenReturn(true);
        var owner = new MarketService.Owner(null, null, null, null, true);
        when(market.ownerFromUuid(null, true)).thenReturn(owner);
        var item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        var record = mock(ChestShopShopRecord.class);
        when(market.shop(any(Location.class), eq(item), eq(owner), eq(BigDecimal.TEN), isNull(),
                eq(16), eq(100), isNull())).thenReturn(record);
        doThrow(new IllegalStateException("DB unavailable")).when(api)
                .importShop(record, false, false, Instant.EPOCH);
        try (var stacks = mockStatic(ItemStack.class)) {
            stacks.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenReturn(item);
            assertThrows(IllegalStateException.class, () -> importer.importRow(row()));
        }
        verifyNoInteractions(accounts);
    }

    @Test void rejectsEveryIncompleteOwnerAndAirItem() {
        when(accounts.resolveAccount("Owner")).thenReturn(new Account());
        assertThrows(IllegalArgumentException.class, () -> importer.importRow(row()));
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner", "Owner", playerId));
        for (var owner : new MarketService.Owner[]{
                new MarketService.Owner(null, null, null, null, true),
                new MarketService.Owner(null, "PERSONAL", null, playerId, false),
                new MarketService.Owner(1, null, null, playerId, false),
                new MarketService.Owner(1, "PERSONAL", null, null, false)}) {
            when(market.ownerFromUuid(playerId, false)).thenReturn(owner);
            assertThrows(IllegalArgumentException.class, () -> importer.importRow(row()));
        }
        when(market.ownerFromUuid(playerId, false))
                .thenReturn(new MarketService.Owner(5, "BUSINESS", 2, null, false));
        var air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        try (var stacks = mockStatic(ItemStack.class)) {
            stacks.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenReturn(air);
            assertThrows(IllegalArgumentException.class, () -> importer.importRow(row()));
        }
        verifyNoInteractions(api);
    }

    @Test void businessImportKeepsMeasuredCapacity() {
        var owner = new MarketService.Owner(5, "BUSINESS", 2, null, false);
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner", "Owner", playerId));
        when(market.ownerFromUuid(playerId, false)).thenReturn(owner);
        var item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        var record = mock(ChestShopShopRecord.class);
        when(market.shop(any(Location.class), eq(item), eq(owner), eq(BigDecimal.TEN), isNull(),
                eq(16), eq(100), eq(27))).thenReturn(record);
        var source = row();
        var withCapacity = new LegacyShop(source.worldUuid(), source.x(), source.y(), source.z(),
                source.ownerName(), source.itemBytes(), source.buyPrice(), source.sellPrice(),
                source.quantity(), source.stock(), 27, source.visible(), source.hologram(), source.observedAt());
        try (var stacks = mockStatic(ItemStack.class)) {
            stacks.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenReturn(item);
            importer.importRow(withCapacity);
        }
        verify(api).importShop(record, false, false, Instant.EPOCH);
    }

    @Test void unresolvedWorldOrOwnerCannotCreatePartialRows() {
        when(server.getWorld(worldId)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> importer.importRow(row()));
        when(server.getWorld(worldId)).thenReturn(world);
        assertThrows(IllegalArgumentException.class, () -> importer.importRow(row()));
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner", "Owner", playerId));
        when(market.ownerFromUuid(playerId, false))
                .thenReturn(new MarketService.Owner(5, "BUSINESS", null, null, false));
        assertThrows(IllegalArgumentException.class, () -> importer.importRow(row()));
        verifyNoInteractions(api);
    }
}
