package io.paradaux.chestshop.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.support.ServerTest;
import java.io.File;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Optional acceptance probe using the unmodified installed Utilities bytecode. */
class InstalledAddonCompatibilityTest extends ServerTest {
    @Test
    void realDatabaseListenerResolvesEveryEventParameterIncludingReload() throws Exception {
        File folder = new File(System.getProperty("legacyAddonDir"));
        File jar = Arrays.stream(folder.listFiles())
                .filter(file -> file.getName().startsWith("chestshop-database-")
                        && file.getName().endsWith(".jar"))
                .findFirst()
                .orElseThrow();
        try (var loader = new URLClassLoader(
                new java.net.URL[] {jar.toURI().toURL()}, getClass().getClassLoader())) {
            Class<?> listener = loader.loadClass("io.github.md5sha256.chestshopdatabase.listener.ChestShopListener");
            var handlers = Arrays.stream(listener.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(org.bukkit.event.EventHandler.class))
                    .toList();
            assertThat(handlers).hasSize(5);
            assertThat(handlers.stream().map(method -> method.getParameterTypes()[0]))
                    .allMatch(org.bukkit.event.Event.class::isAssignableFrom)
                    .contains(com.Acrobot.ChestShop.Events.ChestShopReloadEvent.class);
        }
    }

    @Test
    void realUtilitiesListenerEnforcesEveryConfiguredItemInBothTradeDirections() throws Exception {
        File folder = new File(System.getProperty("legacyAddonDir"));
        var urls = Arrays.stream(folder.listFiles(file -> file.getName().endsWith(".jar")))
                .map(file -> {
                    try {
                        return file.toURI().toURL();
                    } catch (Exception e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .toArray(java.net.URL[]::new);
        try (var loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> itemsType = loader.loadClass("com.utilities.managers.ItemManager");
            Class<?> permissionsType = loader.loadClass("com.utilities.managers.ShopPermissionManager");
            Class<?> listenerType = loader.loadClass("com.utilities.managers.TransactionManager");
            Object items = mock(itemsType);
            Object permissions = mock(permissionsType);
            var plugin = MockBukkit.createMockPlugin();
            Class<?> messages = loader.loadClass("com.utilities.managers.MessageManager");
            var field = messages.getDeclaredField("messagesConfig");
            field.setAccessible(true);
            var config = new YamlConfiguration();
            config.set("shop.permission_denied", "Permission denied");
            field.set(null, config);
            Object listener =
                    listenerType.getConstructor(JavaPlugin.class, itemsType).newInstance(plugin, items);
            listenerType.getMethod("setShopPermissionManager", permissionsType).invoke(listener, permissions);
            when((Boolean) permissionsType.getMethod("isEnabled").invoke(permissions))
                    .thenReturn(true);
            var client = player("Buyer");
            var world = server.addSimpleWorld("shops");
            var chest = world.getBlockAt(0, 64, 0);
            chest.setType(Material.CHEST);
            var block = world.getBlockAt(0, 64, 1);
            block.setType(Material.OAK_WALL_SIGN);
            var data = (org.bukkit.block.data.type.WallSign) block.getBlockData();
            data.setFacing(org.bukkit.block.BlockFace.SOUTH);
            block.setBlockData(data);
            Sign sign = (Sign) block.getState();
            var stack = item(Material.PAPER, 1);
            var ids = YamlConfiguration.loadConfiguration(new File(folder, "Utilities/config.yml"))
                    .getStringList("shopPermissions.items");
            assertThat(ids).isNotEmpty();
            for (String id : ids) {
                when((String) itemsType
                                .getMethod("getNexoIdFromItem", org.bukkit.inventory.ItemStack.class)
                                .invoke(items, stack))
                        .thenReturn(id);
                for (var type : Transaction.TransactionType.values()) {
                    String permission = "shop." + type.name().toLowerCase(java.util.Locale.ROOT) + "." + id;
                    when((String) permissionsType
                                    .getMethod(
                                            type == Transaction.TransactionType.BUY
                                                    ? "getBuyPermission"
                                                    : "getSellPermission",
                                            String.class)
                                    .invoke(permissions, id))
                            .thenReturn(permission);
                    var trade = new PendingTransaction(
                            chest(9),
                            client.getInventory(),
                            new org.bukkit.inventory.ItemStack[] {stack},
                            BigDecimal.ONE,
                            client,
                            new Account("Owner", UUID.randomUUID()),
                            sign,
                            type,
                            false);
                    listenerType
                            .getMethod("onPreTransaction", PreTransactionEvent.class)
                            .invoke(listener, new PreTransactionEvent(trade));
                    assertThat(trade.isCancelled()).as(permission).isTrue();
                    var attachment = client.addAttachment(plugin, permission, true);
                    var permitted = new PendingTransaction(
                            chest(9),
                            client.getInventory(),
                            new org.bukkit.inventory.ItemStack[] {stack},
                            BigDecimal.ONE,
                            client,
                            new Account("Owner", UUID.randomUUID()),
                            sign,
                            type,
                            false);
                    listenerType
                            .getMethod("onPreTransaction", PreTransactionEvent.class)
                            .invoke(listener, new PreTransactionEvent(permitted));
                    assertThat(permitted.isCancelled())
                            .as(permission + " allowed")
                            .isFalse();
                    client.removeAttachment(attachment);
                }
            }
        }
    }
}
