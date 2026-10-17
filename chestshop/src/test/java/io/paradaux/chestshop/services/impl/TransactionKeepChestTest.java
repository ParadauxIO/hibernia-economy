package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.PostTradeReactions;

import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The keep-chest branch of {@code deleteEmptyShop} (config REMOVE_EMPTY_CHESTS=false): when an empty
 * shop is removed, its sign is dropped back into the container as an item. Needs a real MockBukkit
 * sign/inventory + real {@code Material.isItem()} — the previous (server-less) Transaction test
 * couldn't reach it.
 */
class TransactionKeepChestTest extends ServerTest {

    private ChestShopConfiguration config;
    private SignService signService;
    private ShopBlockService shopBlockService;
    private PostTradeReactions service;

    @BeforeEach
    void wire() {
        config = TestConfigs.with(TestConfigs.with(TestConfigs.defaults(),
                "removeEmptyShops", true), "removeEmptyChests", false);
        signService = mock(SignService.class);
        shopBlockService = mock(ShopBlockService.class);
        service = new PostTradeReactionsImpl(
                mock(EconomyService.class), mock(ShopService.class), mock(AccountService.class),
                mock(Message.class), mock(ItemService.class), config, signService, shopBlockService,
                mock(InventoryService.class));
    }

    private void deleteEmptyShop(Transaction event) {
        service.deleteEmptyShop(event);
    }

    @Test
    void removedShop_dropsTheSignBackIntoTheContainerAsAnItem() throws Exception {
        World world = server.addSimpleWorld("keepchest");
        Block block = world.getBlockAt(new Location(world, 0, 64, 0));
        block.setType(Material.OAK_WALL_SIGN);
        Sign sign = (Sign) block.getState();
        Inventory owner = chest(27); // empty — the sold-out shop

        when(signService.isAdminShop(sign)).thenReturn(false);
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(null);

        Transaction event = mock(Transaction.class);
        when(event.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);
        when(event.getSign()).thenReturn(sign);
        when(event.getOwnerInventory()).thenReturn(owner);
        when(event.getStock()).thenReturn(new ItemStack[]{item(Material.DIAMOND, 1)});

        deleteEmptyShop(event);

        // OAK_WALL_SIGN isn't an item; the code resolves it to OAK_SIGN and adds one to the chest.
        assertThat(owner.contains(Material.OAK_SIGN)).isTrue();
        assertThat(block.getType()).isEqualTo(Material.AIR); // the sign block was cleared
    }

    @Test
    void removedShop_logsWhenTheSignTypeHasNoItemForm() throws Exception {
        // A (contrived) sign whose material has no item form: exercises the warn branch.
        World world = server.addSimpleWorld("keepchest2");
        Block block = world.getBlockAt(new Location(world, 0, 64, 0));
        block.setType(Material.OAK_WALL_SIGN);

        Sign sign = mock(Sign.class);
        when(sign.getType()).thenReturn(Material.WATER); // not an item, no WALL_ prefix
        when(sign.getBlock()).thenReturn(block);
        when(sign.getWorld()).thenReturn(world);
        Inventory owner = chest(27);

        when(signService.isAdminShop(sign)).thenReturn(false);
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(null);

        Transaction event = mock(Transaction.class);
        when(event.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);
        when(event.getSign()).thenReturn(sign);
        when(event.getOwnerInventory()).thenReturn(owner);
        when(event.getStock()).thenReturn(new ItemStack[]{item(Material.DIAMOND, 1)});

        deleteEmptyShop(event); // WATER has no item form -> logs, adds nothing
        assertThat(owner.isEmpty()).isTrue();
    }

    @Test
    void removeEmptyChestsEnabled_butContainerNotEmpty_keepsChestAndReturnsSign() throws Exception {
        TestConfigs.with(config, "removeEmptyChests", true);
        World world = server.addSimpleWorld("keepchest3");
        Block block = world.getBlockAt(new Location(world, 0, 64, 0));
        block.setType(Material.OAK_WALL_SIGN);
        Sign sign = (Sign) block.getState();
        Inventory owner = chestWith(27, item(Material.COBBLESTONE, 5)); // not empty (unrelated item)

        when(signService.isAdminShop(sign)).thenReturn(false);
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(null);
        Transaction event = mock(Transaction.class);
        when(event.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);
        when(event.getSign()).thenReturn(sign);
        when(event.getOwnerInventory()).thenReturn(owner);
        when(event.getStock()).thenReturn(new ItemStack[]{item(Material.DIAMOND, 1)});

        deleteEmptyShop(event); // removeEmptyChests && isEmpty(false) -> else branch (keep chest)
        assertThat(owner.contains(Material.OAK_SIGN)).isTrue();
    }

    @Test
    void standingSign_isAlreadyAnItem_returnedDirectly() throws Exception {
        World world = server.addSimpleWorld("keepchest4");
        Block block = world.getBlockAt(new Location(world, 0, 64, 0));
        block.setType(Material.OAK_SIGN); // standing sign — Material.OAK_SIGN.isItem() == true
        Sign sign = (Sign) block.getState();
        Inventory owner = chest(27);

        when(signService.isAdminShop(sign)).thenReturn(false);
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(null);
        Transaction event = mock(Transaction.class);
        when(event.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);
        when(event.getSign()).thenReturn(sign);
        when(event.getOwnerInventory()).thenReturn(owner);
        when(event.getStock()).thenReturn(new ItemStack[]{item(Material.DIAMOND, 1)});

        deleteEmptyShop(event); // signType.isItem() true -> skips the WALL_ resolution
        assertThat(owner.contains(Material.OAK_SIGN)).isTrue();
    }
}
