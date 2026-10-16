package io.paradaux.chestshop.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The {@link BuildPermission} decision: allowed by default, and the allow/disallow toggles
 * (all overloads). The player/locations are pure pass-through boundaries, so they are mocked.
 */
class BuildPermissionTest {

    private final Player player = mock(Player.class);
    private final Location chest = mock(Location.class);
    private final Location sign = mock(Location.class);

    @Test
    void defaultsToAllowed() {
        assertThat(new BuildPermission(player, chest, sign).isAllowed()).isTrue();
    }

    @Test
    void disallow_thenAllow_flipsState() {
        BuildPermission permission = new BuildPermission(player, chest, sign);

        permission.disallow();
        assertThat(permission.isAllowed()).isFalse();

        permission.allow();
        assertThat(permission.isAllowed()).isTrue();
    }

    @Test
    void allowWithBoolean_setsExplicitState() {
        BuildPermission permission = new BuildPermission(player, chest, sign);

        permission.allow(false);
        assertThat(permission.isAllowed()).isFalse();

        permission.allow(true);
        assertThat(permission.isAllowed()).isTrue();
    }
}
