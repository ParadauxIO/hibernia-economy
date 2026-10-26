package io.paradaux.chestshop.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.services.*;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.hibernia.framework.i18n.Message;
import java.math.BigDecimal;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real preparation, partial-fill validation, inventory movement and confirmation; mock ledger boundary. */
class WildcardPurchaseTest extends ServerTest {
    private Sign sign;
    private Player buyer;
    private Inventory stock;
    private ItemStack can;
    private ItemStack bottle;
    private EconomyService economy;
    private PurchasePreviewService previews;
    private ShopVariantsService variants;
    private TransactionService transactions;
    private ItemService items;

    @BeforeEach
    void wire() {
        var config = TestConfigs.defaults();
        var world = server.addSimpleWorld("wildcards");
        var block = world.getBlockAt(0, 64, 0);
        block.setType(Material.OAK_SIGN);
        sign = (Sign) block.getState();
        sign.setLine(0, "Seller");
        sign.setLine(1, "2");
        sign.setLine(2, "B 10:S 6");
        sign.setLine(3, "carlton_dry*");
        sign.update();
        buyer = server.addPlayer("Buyer");
        buyer.teleport(sign.getLocation().clone());
        can = item(Material.PAPER, 5);
        can.editMeta(meta -> meta.displayName(Component.text("Can")));
        bottle = item(Material.PAPER, 4);
        bottle.editMeta(meta -> meta.displayName(Component.text("Bottle")));
        stock = chestWith(27, bottle, can, item(Material.DIAMOND, 20));
        var container = mock(Container.class);
        when(container.getInventory()).thenReturn(stock);
        var shops = mock(ShopBlockService.class);
        when(shops.findConnectedContainer(any(Sign.class))).thenReturn(container);
        var accounts = mock(AccountService.class);
        var owner = new Account("Seller", "Seller", UUID.randomUUID());
        when(accounts.resolveAccount("Seller")).thenReturn(owner);
        economy = mock(EconomyService.class);
        when(economy.hasAccount(any())).thenReturn(true);
        when(economy.hasFunds(any(), any())).thenReturn(true);
        when(economy.getBalance(any())).thenReturn(new BigDecimal("1000"));
        when(economy.settle(any(), any(), any(), any())).thenReturn(true);
        var message = mock(Message.class);
        items = mock(ItemService.class);
        when(items.wildcardName(any())).thenAnswer(call -> {
            ItemStack item = call.getArgument(0);
            return item.isSimilar(can) ? "carlton_dry_can" : item.isSimilar(bottle) ? "carlton_dry_bottle" : "other";
        });
        var signs = new SignService(config);
        var materials = new MaterialServiceImpl(config);
        var inventory = new InventoryServiceImpl(config, materials);
        var admin = mock(AdminBypassService.class);
        when(admin.has(any(), anyString())).thenReturn(true);
        var factory = new TradeContextFactoryImpl(economy, accounts, message, items, config, signs, shops, inventory);
        var validator = new TradeValidatorImpl(
                economy,
                accounts,
                mock(SignBreakService.class),
                message,
                items,
                config,
                signs,
                inventory,
                admin,
                mock(RestrictedSignService.class),
                new PartialFillCalculatorImpl(economy, inventory, materials, config));
        var goods = new GoodsTransferImpl(inventory, config, mock(TradeRecoveryService.class));
        transactions = new TransactionServiceImpl(
                factory,
                validator,
                new TradeSettlementImpl(economy, goods),
                mock(PostTradeReactions.class),
                economy,
                mock(StockCounterService.class),
                mock(MarketSyncService.class),
                mock(MetricsService.class),
                mock(SettledPurchasePublisher.class));
        var protection = mock(ProtectionService.class);
        when(protection.canAccess(any(), any(), anyBoolean())).thenReturn(true);
        previews = new PurchasePreviewService(transactions, accounts, protection, signs, config, message, materials);
        variants = new ShopVariantsService(new WildcardItems(items), shops, config, previews);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,5", "3,15", "4,20"})
    void customQuantityIsPricedAndConfirmedWithoutReusingOldOffer(int amount, String price) {
        var original = previews.preview(sign, buyer, Action.RIGHT_CLICK_BLOCK, can);
        var resized = previews.resize(original, buyer, amount);
        assertThat(resized.stock()[0].getAmount()).isEqualTo(amount);
        assertThat(resized.price()).isEqualByComparingTo(price);
        assertThat(resized.bundleQuantity()).isEqualTo(2);
        assertThat(previews.confirm(original, buyer)).isFalse();
        verify(economy, never()).settle(any(), any(), any(), any());
        assertThat(previews.confirm(resized, buyer)).isTrue();
        assertThat(previews.confirm(resized, buyer)).isFalse();
        assertThat(count(buyer.getInventory(), can)).isEqualTo(amount);
        assertThat(count(stock, can)).isEqualTo(5 - amount);
        assertThat(count(stock, bottle)).isEqualTo(4);
    }

    @Test
    void quantityAboveStockPreviewsPartialFillAndRejectsLaterStockChanges() {
        var original = previews.preview(sign, buyer, Action.RIGHT_CLICK_BLOCK, can);
        var resized = previews.resize(original, buyer, 8);
        assertThat(resized.stock()[0].getAmount()).isEqualTo(5);
        assertThat(resized.price()).isEqualByComparingTo("25");
        var reduced = can.clone();
        reduced.setAmount(4);
        stock.setItem(1, reduced);
        assertThat(previews.confirm(resized, buyer)).isFalse();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @Test
    void quantityChangeCannotAcceptChangedSignOrAnotherPlayer() {
        var original = previews.preview(sign, buyer, Action.RIGHT_CLICK_BLOCK, can);
        assertThat(previews.resize(original, server.addPlayer("Other"), 3)).isNull();
        sign.setLine(2, "B 100");
        sign.update();
        assertThat(previews.resize(original, buyer, 3)).isNull();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    void invalidQuantitiesCannotPrepareOrReplaceOffer(int amount) {
        var original = previews.preview(sign, buyer, Action.RIGHT_CLICK_BLOCK, can);
        assertThat(previews.resize(original, buyer, amount)).isNull();
        assertThat(transactions.prepare(sign, buyer, Action.RIGHT_CLICK_BLOCK, can, amount))
                .isNull();
        verify(economy, never()).settle(any(), any(), any(), any());
        assertThat(previews.confirm(original, buyer)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,OtherSeller", "1,3", "1,invalid", "3,other*"})
    void quantityChangeRejectsReconfiguredShop(int line, String replacement) {
        var original = previews.preview(sign, buyer, Action.RIGHT_CLICK_BLOCK, can);
        sign.setLine(line, replacement);
        sign.update();
        assertThat(previews.resize(original, buyer, 3)).isNull();
        assertThat(previews.confirm(original, buyer)).isFalse();
        assertThat(count(stock, can)).isEqualTo(5);
        assertThat(count(buyer.getInventory(), can)).isZero();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @Test
    void quantityChangeRejectsRemovedShop() {
        var original = previews.preview(sign, buyer, Action.RIGHT_CLICK_BLOCK, can);
        sign.getBlock().setType(Material.AIR);
        assertThat(previews.resize(original, buyer, 3)).isNull();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @Test
    void onlyTheLatestQuantityOfferCanBeUsed() {
        var original = previews.preview(sign, buyer, Action.RIGHT_CLICK_BLOCK, can);
        var replacement = previews.resize(original, buyer, 3);
        assertThat(previews.resize(original, buyer, 4)).isNull();
        assertThat(previews.confirm(replacement, buyer)).isTrue();
        assertThat(previews.resize(replacement, buyer, 4)).isNull();
        assertThat(count(buyer.getInventory(), can)).isEqualTo(3);
        verify(economy, times(1)).settle(any(), any(), any(), any());
    }

    @Test
    void explicitQuantityCannotEnableADisabledTradeDirection() {
        sign.setLine(2, "S 6");
        sign.update();
        var trade = transactions.prepare(sign, buyer, Action.RIGHT_CLICK_BLOCK, can, 3);
        assertThat(trade.getExactPrice()).isEqualByComparingTo(io.paradaux.chestshop.utils.PriceUtil.NO_PRICE);
        transactions.validate(trade);
        assertThat(trade.isCancelled()).isTrue();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @Test
    void explicitQuantityRequiresASelectedVariation() {
        assertThat(transactions.prepare(sign, buyer, Action.RIGHT_CLICK_BLOCK, null, 3))
                .isNull();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @Test
    void customSellUsesSellUnitPrice() {
        buyer.getInventory().addItem(can.clone());
        var original = previews.preview(sign, buyer, Action.LEFT_CLICK_BLOCK, can);
        var resized = previews.resize(original, buyer, 3);
        assertThat(resized.price()).isEqualByComparingTo("9");
        assertThat(previews.confirm(resized, buyer)).isTrue();
        assertThat(count(buyer.getInventory(), can)).isEqualTo(2);
        assertThat(count(stock, can)).isEqualTo(8);
    }

    @Test
    void customPriceRoundsOnceFromSignRatio() {
        sign.setLine(1, "3");
        sign.setLine(2, "B 10");
        sign.update();
        var trade = transactions.prepare(sign, buyer, Action.RIGHT_CLICK_BLOCK, can, 2);
        assertThat(trade.getExactPrice()).isEqualByComparingTo("6.67");
        assertThat(trade.getStock()[0].getAmount()).isEqualTo(2);
    }

    @Test
    void selectionPreviewAndConfirmationTransferOnlyChosenVariation() {
        var selection = variants.choices(sign, buyer, Action.RIGHT_CLICK_BLOCK);
        assertThat(selection.variants()).hasSize(2);
        var chosen = selection.variants().get(1);
        var offer = variants.preview(selection, buyer, chosen);
        assertThat(offer).isNotNull();
        assertThat(offer.stock())
                .allSatisfy(item -> assertThat(item.isSimilar(can)).isTrue());
        assertThat(offer.price()).isEqualByComparingTo("10");
        verify(economy, never()).settle(any(), any(), any(), any());
        assertThat(buyer.getInventory().contains(Material.PAPER)).isFalse();
        assertThat(previews.confirm(offer, buyer)).isTrue();
        assertThat(count(buyer.getInventory(), can)).isEqualTo(2);
        assertThat(count(buyer.getInventory(), bottle)).isZero();
        assertThat(count(stock, can)).isEqualTo(3);
        assertThat(count(stock, bottle)).isEqualTo(4);
        verify(economy).settle(eq(new BigDecimal("10")), eq(buyer), any(), any());
        assertThat(previews.confirm(offer, buyer)).isFalse();
    }

    @Test
    void soldOutSelectedVariationDoesNotSubstituteAnotherDrink() {
        var selection = variants.choices(sign, buyer, Action.RIGHT_CLICK_BLOCK);
        var offer = variants.preview(selection, buyer, selection.variants().get(1));
        stock.setItem(1, null);
        assertThat(previews.confirm(offer, buyer)).isFalse();
        assertThat(count(stock, bottle)).isEqualTo(4);
        assertThat(buyer.getInventory().contains(Material.PAPER)).isFalse();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @Test
    void sellingSelectsFromPlayerAndUsesSellPrice() {
        buyer.getInventory().addItem(can.clone());
        var selection = variants.choices(sign, buyer, Action.LEFT_CLICK_BLOCK);
        assertThat(selection.variants()).hasSize(1);
        var offer = variants.preview(selection, buyer, selection.variants().getFirst());
        assertThat(offer.buying()).isFalse();
        assertThat(offer.price()).isEqualByComparingTo("6");
        assertThat(previews.confirm(offer, buyer)).isTrue();
        assertThat(count(buyer.getInventory(), can)).isEqualTo(3);
        assertThat(count(stock, can)).isEqualTo(7);
    }

    @Test
    void rejectsUnselectedOrUnrelatedItems() {
        assertThat(transactions.prepare(sign, buyer, Action.RIGHT_CLICK_BLOCK)).isNull();
        assertThat(transactions.prepare(sign, buyer, Action.RIGHT_CLICK_BLOCK, item(Material.DIAMOND, 1)))
                .isNull();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @Test
    void failedLedgerSettlementRestoresBothInventories() {
        var selection = variants.choices(sign, buyer, Action.RIGHT_CLICK_BLOCK);
        var offer = variants.preview(selection, buyer, selection.variants().get(1));
        when(economy.settle(any(), any(), any(), any())).thenReturn(false);
        assertThat(previews.confirm(offer, buyer)).isFalse();
        assertThat(count(stock, can)).isEqualTo(5);
        assertThat(count(stock, bottle)).isEqualTo(4);
        assertThat(count(buyer.getInventory(), can)).isZero();
    }

    @Test
    void stockCounterCountsEveryMatchingVariationButNotUnrelatedItems() {
        var config = TestConfigs.defaults();
        var materials = new MaterialServiceImpl(config);
        var counter = new StockCounterServiceImpl(
                items,
                config,
                new SignService(config),
                mock(ShopBlockService.class),
                new InventoryServiceImpl(config, materials),
                materials);
        counter.updateCounterOnQuantityLine(sign, stock);
        assertThat(sign.getLine(1)).isEqualTo("Q 2 : C 9");
        stock.setItem(1, null);
        counter.updateCounterOnQuantityLine(sign, stock);
        assertThat(sign.getLine(1)).isEqualTo("Q 2 : C 4");
    }

    @Test
    void partialPurchaseShowsReducedQuantityAndPriceBeforeConfirmation() {
        var remaining = can.clone();
        remaining.setAmount(1);
        stock.setItem(1, remaining);
        var selection = variants.choices(sign, buyer, Action.RIGHT_CLICK_BLOCK);
        var offer = variants.preview(selection, buyer, selection.variants().get(1));
        assertThat(offer.stock()).hasSize(1);
        assertThat(offer.stock()[0].getAmount()).isEqualTo(1);
        assertThat(offer.price()).isEqualByComparingTo("5");
        assertThat(previews.confirm(offer, buyer)).isTrue();
        assertThat(count(buyer.getInventory(), can)).isEqualTo(1);
        assertThat(count(stock, bottle)).isEqualTo(4);
    }

    @Test
    void staleOrForgedSelectionCannotBecomeAnOffer() {
        var selection = variants.choices(sign, buyer, Action.RIGHT_CLICK_BLOCK);
        assertThat(variants.preview(selection, buyer, item(Material.DIAMOND, 1)))
                .isNull();
        sign.setLine(3, "other*");
        sign.update();
        assertThat(variants.preview(selection, buyer, selection.variants().getFirst()))
                .isNull();
        sign.getBlock().setType(Material.AIR);
        assertThat(variants.preview(selection, buyer, selection.variants().getFirst()))
                .isNull();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    @Test
    void exactShopsRejectASelectedWildcardItem() {
        sign.setLine(3, "PAPER");
        sign.update();
        assertThat(variants.choices(sign, buyer, Action.RIGHT_CLICK_BLOCK)).isNull();
        assertThat(transactions.prepare(sign, buyer, Action.RIGHT_CLICK_BLOCK, can))
                .isNull();
        buyer.teleport(sign.getLocation().clone().add(30, 0, 0));
        assertThat(variants.choices(sign, buyer, Action.RIGHT_CLICK_BLOCK)).isNull();
    }

    @Test
    void selectedItemSpecificDenyIsEnforced() {
        when(items.getName(any())).thenReturn("Paper#123");
        buyer.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(),
                io.paradaux.chestshop.utils.Permissions.BUY_ID + "Paper#123",
                false);
        var selection = variants.choices(sign, buyer, Action.RIGHT_CLICK_BLOCK);
        assertThat(variants.preview(selection, buyer, selection.variants().getFirst()))
                .isNull();
        verify(economy, never()).settle(any(), any(), any(), any());
    }

    private static int count(Inventory inventory, ItemStack sample) {
        int count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.isSimilar(sample)) count += item.getAmount();
        }
        return count;
    }
}
