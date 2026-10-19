package io.paradaux.chestshop.dialogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.services.ShopDisplayService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.hibernia.framework.usher.DialogView;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class FindDialogTest extends ServerTest {
    @Test
    void results_renderEachRowsItem_evenWhenNotFlaggedCustom_andKeepInvalidRowsSelectable() {
        ItemStack stored = new ItemStack(Material.DIAMOND_SWORD);
        var meta = stored.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Keepsake"));
        stored.setItemMeta(meta);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", stored);
        FindState state = new FindState(null, "STONE", "Stone", "world", 0, 0, 0, true);
        state.setResults(List.of(
                row("STONE", null),
                row("DIAMOND_SWORD", yaml.saveToString()),
                row("DIRT", "other: 1"),
                row("INVALID", null)));
        ShopDisplayService displays = mock(ShopDisplayService.class);
        when(displays.location(anyString(), anyInt(), anyInt(), anyInt())).thenReturn("world: 0, 0, 0");
        DialogView view = new FindDialog(null, null, displays).results(state);
        assertThat(view.bodies().stream()
                        .filter(DialogView.Body.Item.class::isInstance)
                        .map(DialogView.Body.Item.class::cast)
                        .map(DialogView.Body.Item::item)
                        .toList())
                .containsExactly(new ItemStack(Material.STONE), stored, new ItemStack(Material.DIRT));
        assertThat(view.buttons()).hasSize(12);
    }

    @Test
    void inspectionUsesActualItemMetadataEvenWhenResultIconsAreConfigured() {
        ItemStack stored = new ItemStack(Material.DIAMOND_SWORD);
        var meta = stored.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Keepsake"));
        stored.setItemMeta(meta);
        var yaml = new YamlConfiguration();
        yaml.set("item", stored);
        var state = new FindState(null, "SWORD", "Sword", "world", 0, 0, 0, true);
        state.setResults(List.of(row("DIAMOND_SWORD", yaml.saveToString())));
        state.inspect(0);
        var dialog = new FindDialog(null, null, mock(ShopDisplayService.class));
        var inspection = dialog.inspection(state);
        assertThat(inspection.bodies().stream()
                        .filter(DialogView.Body.Item.class::isInstance)
                        .map(DialogView.Body.Item.class::cast)
                        .map(DialogView.Body.Item::item)
                        .toList())
                .containsExactly(stored);
        var flow = mock(io.paradaux.hibernia.framework.usher.DialogFlow.class);
        dialog.inspect0(state, flow);
        org.mockito.Mockito.verify(flow).open("inspect");
        dialog.inspectionBack(flow);
        org.mockito.Mockito.verify(flow).back();
    }

    private FoundShop row(String material, String data) {
        return new FoundShop(
                material, material, material, false, data, "Owner", null, null, 1, 1, 1, "world", 0, 0, 0, 0);
    }
}
