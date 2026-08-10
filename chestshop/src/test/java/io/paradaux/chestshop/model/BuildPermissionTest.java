package io.paradaux.chestshop.model;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Covers the {@link BuildPermission} carrier: the nullable chest location, the allow/disallow
 * toggles (all overloads) and the default allowed state. The player/locations are pure
 * pass-through boundaries here, so they are mocked.
 */
class BuildPermissionTest {

    private final Player player = mock(Player.class);
    private final Location chest = mock(Location.class);
    private final Location sign = mock(Location.class);

    @Test
    void defaultsToAllowed_andExposesInputs() {
        BuildPermission permission = new BuildPermission(player, chest, sign);

        assertThat(permission.isAllowed()).isTrue();
        assertThat(permission.getPlayer()).isSameAs(player);
        assertThat(permission.getChest()).isSameAs(chest);
        assertThat(permission.getSign()).isSameAs(sign);
    }

    @Test
    void chestLocation_mayBeNull() {
        BuildPermission permission = new BuildPermission(player, null, sign);
        assertThat(permission.getChest()).isNull();
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
