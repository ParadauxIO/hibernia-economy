package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class ShopDisplayServiceTest {
    @Test
    void optionalRegionsAndTemplates() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        World world = mock(World.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld("world")).thenReturn(world);
        ShopDisplayService displays = new ShopDisplayService(plugin, new ChestShopConfiguration());
        assertThat(displays.location("world", -17, 64, 33)).isEqualTo("-17, 64, 33");
        assertThat(displays.previewName("world", 1, 2, 3)).isEmpty();
        assertThat(displays.format("%region-name%", "world", 1, 2, 3)).isEmpty();
        assertThat(displays.format("%region-name%", "missing", 1, 2, 3)).isEmpty();
        displays.setRegionNameProvider(location -> {
            assertThat(location.getWorld()).isSameAs(world);
            assertThat(location.getBlockX()).isEqualTo(-17);
            return "market$1%world%";
        });
        assertThat(displays.format("%region-name% %world% %unknown%", "world", -17, 64, 33))
                .isEqualTo("market$1%world% world %unknown%");
    }
}
