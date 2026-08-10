package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.GoodsTransfer;

import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.model.Transaction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the goods-side atomicity guarantees of {@link TransactionServiceImpl} (ADT-4):
 * a fully-achievable move proceeds, an incomplete move reverts both inventories
 * without committing, and the money-leg compensation reverses goods in the correct
 * direction for both buy and sell. The underlying inventory mechanics live in
 * {@link InventoryUtil#transfer}, mocked here so the test exercises the rollback
 * wiring rather than Bukkit inventory internals.
 *
 * <p>That this logic — formerly split across the {@code ItemManager}/{@code
 * EconomicModule} listeners — is now exercisable as plain method calls on a service
 * is the point of the service migration.
 */
class TransactionServiceTest {

    private InventoryService inventoryService;
    private GoodsTransfer service;

    @BeforeEach
    void setUp() {
        // STACK_TO_64 only affects InventoryUtil#getMaxStackSize, which is mocked
        // away here — the transfer overloads under test are stubbed directly.
        inventoryService = mock(InventoryService.class);
        ChestShopConfiguration config = mock(ChestShopConfiguration.class);
        service = new GoodsTransferImpl(inventoryService, config);
    }

    @Test
    void transferItems_movesEverything_returnsTrueAndDoesNotRevert() {
        Inventory source = mock(Inventory.class);
        Inventory target = mock(Inventory.class);
        when(source.getContents()).thenReturn(new ItemStack[]{null, null});
        when(target.getContents()).thenReturn(new ItemStack[]{null, null});
        ItemStack[] items = {mock(ItemStack.class)};

        when(inventoryService.transfer(any(ItemStack.class), eq(source), eq(target))).thenReturn(0);

        boolean moved = service.transfer(source, target, items);

        assertThat(moved).isTrue();

        // A complete move must not touch the snapshots.
        verify(source, never()).setContents(any());
        verify(target, never()).setContents(any());
    }

    @Test
    void transferItems_onShortfall_revertsBothInventories_returnsFalse() {
        Inventory source = mock(Inventory.class);
        Inventory target = mock(Inventory.class);
        ItemStack[] sourceSnapshot = {null};
        ItemStack[] targetSnapshot = {null};
        when(source.getContents()).thenReturn(sourceSnapshot);
        when(target.getContents()).thenReturn(targetSnapshot);
        ItemStack[] items = {mock(ItemStack.class)};

        // Report leftovers — the target could not hold everything.
        when(inventoryService.transfer(any(ItemStack.class), eq(source), eq(target))).thenReturn(3);

        boolean moved = service.transfer(source, target, items);

        assertThat(moved).isFalse();

        // Both inventories must be restored from their snapshots, and the
        // holders must NOT be refreshed (the move did not happen).
        verify(source).setContents(any(ItemStack[].class));
        verify(target).setContents(any(ItemStack[].class));
        verify(source, never()).getHolder();
        verify(target, never()).getHolder();
    }

    @Test
    void reverseTransfer_forBuy_movesGoodsFromClientBackToOwner() {
        Transaction event = mock(Transaction.class);
        Inventory ownerInv = mock(Inventory.class);
        Inventory clientInv = mock(Inventory.class);
        when(event.getTransactionType()).thenReturn(BUY);
        when(event.getOwnerInventory()).thenReturn(ownerInv);
        when(event.getClientInventory()).thenReturn(clientInv);
        when(event.getStock()).thenReturn(new ItemStack[]{mock(ItemStack.class)});
        when(clientInv.getContents()).thenReturn(new ItemStack[]{null});
        when(ownerInv.getContents()).thenReturn(new ItemStack[]{null});

        when(inventoryService.transfer(any(ItemStack.class), eq(clientInv), eq(ownerInv))).thenReturn(0);

        service.reverse(event);

        // A buy moved owner -> client, so the reversal must move client -> owner.
        verify(inventoryService).transfer(any(ItemStack.class), eq(clientInv), eq(ownerInv));
    }

    @Test
    void reverseTransfer_forSell_movesGoodsFromOwnerBackToClient() {
        Transaction event = mock(Transaction.class);
        Inventory ownerInv = mock(Inventory.class);
        Inventory clientInv = mock(Inventory.class);
        when(event.getTransactionType()).thenReturn(SELL);
        when(event.getOwnerInventory()).thenReturn(ownerInv);
        when(event.getClientInventory()).thenReturn(clientInv);
        when(event.getStock()).thenReturn(new ItemStack[]{mock(ItemStack.class)});
        when(clientInv.getContents()).thenReturn(new ItemStack[]{null});
        when(ownerInv.getContents()).thenReturn(new ItemStack[]{null});

        when(inventoryService.transfer(any(ItemStack.class), eq(ownerInv), eq(clientInv))).thenReturn(0);

        service.reverse(event);

        // A sell moved client -> owner, so the reversal must move owner -> client.
        verify(inventoryService).transfer(any(ItemStack.class), eq(ownerInv), eq(clientInv));
    }
}
