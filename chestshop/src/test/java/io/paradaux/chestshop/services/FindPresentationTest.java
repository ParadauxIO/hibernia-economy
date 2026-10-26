package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.support.ServerTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindPresentationTest extends ServerTest {
    @TempDir
    Path root;

    @Test
    void importsLegacyPresentationOnceAndHonorsSubsequentNativeChanges() throws Exception {
        Files.createDirectories(root.resolve("ChestShopDatabase"));
        Files.createDirectories(root.resolve("ChestShop"));
        Files.writeString(
                root.resolve("ChestShopDatabase/settings.yml"),
                "shop-preview-scale: 0.85\nshop-preview-default-visibility: false\nshop-icon-left-click-command: 'compass transit <x> <y> <z>'\nshop-template-both:\n  material: ENDER_CHEST\n  display-name: '%owner%'\n  lore: ['Quantity %quantity% Capacity %capacity%']\n");
        var presentation = new FindPresentation(root.resolve("ChestShop").toFile());
        assertThat(presentation.previewScale()).isEqualTo(0.85f);
        assertThat(presentation.defaultVisible()).isFalse();
        assertThat(presentation.command(shop(), true)).isEqualTo("compass transit -3 64 8");
        assertThat(presentation.icon(shop()).getType()).isEqualTo(org.bukkit.Material.ENDER_CHEST);
        var displays = mock(ShopDisplayService.class);
        var text = PlainTextComponentSerializer.plainText().serialize(presentation.details(shop(), displays));
        assertThat(text).contains("Quantity 16 Capacity 27").contains("<red>Owner");
        Files.writeString(root.resolve("ChestShop/find.yml"), "shop-preview-scale: 0.7\n");
        presentation.reload();
        assertThat(presentation.previewScale()).isEqualTo(0.7f);
    }

    @Test
    void navigationRunsAsPlayerAndRefusesCrossWorldDestinations() {
        var presentation = new FindPresentation();
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        presentation.navigate(player, shop(), false);
        verify(player).performCommand("compass -3 64 8");
        when(world.getName()).thenReturn("other");
        presentation.navigate(player, shop(), true);
        verify(player, times(1)).performCommand(anyString());
    }

    @Test
    void invalidScaleFallsBackToSaneDefault() throws Exception {
        Files.createDirectories(root.resolve("ChestShop"));
        Files.writeString(root.resolve("ChestShop/find.yml"), "shop-preview-scale: -1\n");
        assertThat(new FindPresentation(root.resolve("ChestShop").toFile()).previewScale())
                .isEqualTo(0.5f);
    }

    static FoundShop shop() {
        return new FoundShop(
                "STONE",
                "Stone",
                "STONE",
                false,
                null,
                "<red>Owner",
                BigDecimal.valueOf(32),
                BigDecimal.valueOf(16),
                16,
                64,
                27,
                "world",
                -3,
                64,
                8,
                100);
    }
}
