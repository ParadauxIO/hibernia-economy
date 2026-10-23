package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.hibernia.framework.i18n.Message;
import java.math.BigDecimal;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchasePreviewServiceTest extends ServerTest {
    private TransactionService transactions;
    private AccountService accounts;
    private PurchasePreviewService service;
    private Sign sign;
    private Player player;
    private PendingTransaction trade;
    private ProtectionService protection;
    private io.paradaux.chestshop.model.config.ChestShopConfiguration config;
    private MaterialService materials;

    @BeforeEach
    void setUpPreview() {
        var world = server.addSimpleWorld("preview");
        var block = world.getBlockAt(0, 64, 0);
        block.setType(Material.OAK_SIGN);
        sign = (Sign) block.getState();
        sign.setLine(0, "Seller");
        sign.setLine(1, "2");
        sign.setLine(2, "B 10");
        sign.setLine(3, "DIAMOND");
        sign.update();
        player = server.addPlayer("Buyer");
        player.teleport(sign.getLocation().clone());
        transactions = mock(TransactionService.class);
        accounts = mock(AccountService.class);
        config = TestConfigs.defaults();
        protection = mock(ProtectionService.class);
        materials = new io.paradaux.chestshop.services.impl.MaterialServiceImpl(config);
        when(protection.canAccess(any(), any(), anyBoolean())).thenReturn(true);
        service = new PurchasePreviewService(
                transactions, accounts, protection, new SignService(config), config, mock(Message.class), materials);
        trade = new PendingTransaction(
                null,
                player.getInventory(),
                new ItemStack[] {new ItemStack(Material.DIAMOND, 2)},
                new BigDecimal("10"),
                player,
                mock(Account.class),
                sign,
                Transaction.TransactionType.BUY,
                true);
        when(transactions.prepare(any(), eq(player), eq(Action.RIGHT_CLICK_BLOCK)))
                .thenAnswer(call -> trade);
    }

    private PurchasePreviewService.Offer offer() {
        var result = service.preview(sign, player, Action.RIGHT_CLICK_BLOCK);
        assertThat(result).isNotNull();
        return result;
    }

    @Test
    void anotherPlayerCannotConsumeTheOffer() {
        var offer = offer();
        assertThat(service.confirm(offer, server.addPlayer("Other"))).isFalse();
        assertThat(service.confirm(offer, player)).isTrue();
        verify(transactions, times(1)).process(any());
    }

    @Test
    void remoteOrDifferentWorldConfirmationIsRejected() {
        var offer = offer();
        player.teleport(sign.getLocation().clone().add(20, 0, 0));
        assertThat(service.confirm(offer, player)).isFalse();
        player.teleport(server.addSimpleWorld("other").getSpawnLocation());
        assertThat(service.preview(sign, player, Action.RIGHT_CLICK_BLOCK)).isNull();
        verify(transactions, never()).process(any());
    }

    @Test
    void rejectsInvalidSignAndUnavailableTrade() {
        when(transactions.prepare(any(), any(), any())).thenReturn(null);
        assertThat(service.preview(sign, player, Action.RIGHT_CLICK_BLOCK)).isNull();
        sign.setLine(1, "bad");
        assertThat(service.canUse(sign, player)).isFalse();
        verify(transactions, never()).process(any());
    }

    @Test
    void revokedTerrainAccessAndStaffAccessAreRechecked() {
        TestConfigs.with(config, "checkAccessForShopUse", true);
        var offer = offer();
        when(protection.canAccess(any(), any(), anyBoolean())).thenReturn(false);
        assertThat(service.confirm(offer, player)).isFalse();
        when(protection.canAccess(any(), any(), anyBoolean())).thenReturn(true);
        TestConfigs.with(config, "ignoreAccessPerms", true);
        when(accounts.canAccess(eq(player), any(Sign.class))).thenReturn(true);
        assertThat(service.preview(sign, player, Action.RIGHT_CLICK_BLOCK)).isNull();
        verify(transactions, never()).process(any());
    }

    @Test
    void ignoredMetadataCannotSilentlySubstituteThePreview() {
        TestConfigs.with(config, "excludedItemAttributesRaw", java.util.List.of("display-name"));
        var altered = new ItemStack(Material.DIAMOND, 2);
        altered.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Different")));
        player.getInventory().addItem(altered);
        assertThat(materials.equals(altered, new ItemStack(Material.DIAMOND, 2)))
                .isTrue();
        assertThat(service.preview(sign, player, Action.RIGHT_CLICK_BLOCK)).isNull();
        verify(transactions, never()).process(any());
    }

    @Test
    void previewDoesNotTradeAndDisplayCopiesCannotChangeTheOffer() {
        var offer = offer();
        offer.stock()[0].setAmount(64);
        assertThat(offer.stock()[0].getAmount()).isEqualTo(2);
        verify(transactions, never()).process(any());
        assertThat(player.getInventory().contains(Material.DIAMOND)).isFalse();
    }

    @Test
    void aReloadCannotTurnAPurchaseIntoASaleAtTheSamePrice() {
        var offer = offer();
        trade = new PendingTransaction(
                null,
                player.getInventory(),
                trade.getStock(),
                trade.getExactPrice(),
                player,
                trade.getOwnerAccount(),
                sign,
                Transaction.TransactionType.SELL,
                true);
        assertThat(service.confirm(offer, player)).isFalse();
        verify(transactions, never()).process(any());
    }

    @Test
    void confirmsExactOfferOnlyOnce() {
        var offer = offer();
        assertThat(service.confirm(offer, player)).isTrue();
        assertThat(service.confirm(offer, player)).isFalse();
        var captured = org.mockito.ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).process(captured.capture());
        assertThat(captured.getValue().getStock()).containsExactly(new ItemStack(Material.DIAMOND, 2));
        assertThat(captured.getValue().getExactPrice()).isEqualByComparingTo("10");
    }

    @Test
    void rejectsAChangedPriceOnTheSign() {
        var offer = offer();
        sign.setLine(2, "B 100");
        sign.update();
        assertThat(service.confirm(offer, player)).isFalse();
        verify(transactions, never()).process(any());
    }

    @Test
    void rejectsPartialFillChangesAfterPreview() {
        var offer = offer();
        trade.setStock(new ItemStack(Material.DIAMOND, 1));
        trade.setExactPrice(new BigDecimal("5"));
        assertThat(service.confirm(offer, player)).isFalse();
        verify(transactions, never()).process(any());
    }

    @Test
    void rejectsDifferentMetadataEvenAtSamePriceAndQuantity() {
        var offer = offer();
        var altered = new ItemStack(Material.DIAMOND, 2);
        altered.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Different item")));
        trade.setStock(altered);
        assertThat(service.confirm(offer, player)).isFalse();
        verify(transactions, never()).process(any());
    }

    @Test
    void rejectsRemovedShopAndRemoteConfirmation() {
        var offer = offer();
        sign.getBlock().setType(Material.AIR);
        assertThat(service.confirm(offer, player)).isFalse();
        verify(transactions, never()).process(any());
    }

    @Test
    void rechecksAccessBeforeConfirming() {
        var offer = offer();
        when(accounts.isOwner(eq(player), any())).thenReturn(true);
        assertThat(service.confirm(offer, player)).isFalse();
        verify(transactions, never()).process(any());
    }

    @Test
    void rejectsInvalidatedTradeWithoutSettlement() {
        var offer = offer();
        doAnswer(call -> {
                    ((PendingTransaction) call.getArgument(0))
                            .setCancelled(PendingTransaction.TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST);
                    return null;
                })
                .when(transactions)
                .validate(any());
        assertThat(service.confirm(offer, player)).isFalse();
        verify(transactions, never()).process(any());
    }
}
