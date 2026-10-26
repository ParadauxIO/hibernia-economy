package io.paradaux.chestshop.dialogs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.services.PurchasePreviewService;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.hibernia.framework.curator.MenuManager;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.usher.DialogFlow;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PurchaseQuantityDialogTest {
    private final PurchasePreviewService previews = mock(PurchasePreviewService.class);
    private final MenuManager menus = mock(MenuManager.class);
    private final PurchaseQuantityDialog dialog = new PurchaseQuantityDialog(previews, menus, TestConfigs.defaults());
    private final PurchasePreviewService.Offer offer = mock(PurchasePreviewService.Offer.class);
    private final DialogFlow flow = mock(DialogFlow.class);

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "1.5", "abc", "2147483648"})
    void invalidInputKeepsFormOpenAndCannotTrade(String input) {
        assertThatThrownBy(() -> dialog.custom(input, offer, flow)).isInstanceOf(BadCommandException.class);
        verifyNoInteractions(previews, menus, flow);
    }

    @Test
    void customCountOpensFreshPreviewWithoutConfirming() {
        var player = mock(Player.class);
        var updated = mock(PurchasePreviewService.Offer.class);
        when(flow.player()).thenReturn(player);
        when(previews.resize(offer, player, 17)).thenReturn(updated);
        dialog.custom(" 17 ", offer, flow);
        verify(flow).close();
        verify(menus).open(player, PurchasePreviewMenu.class, updated);
        verify(previews, never()).confirm(any(), any());
    }

    @Test
    void shortcutMultipliesSignQuantity() {
        var player = mock(Player.class);
        when(flow.player()).thenReturn(player);
        when(offer.bundleQuantity()).thenReturn(3);
        dialog.eight(offer, flow);
        verify(previews).resize(offer, player, 24);
        verifyNoInteractions(menus);
    }
}
