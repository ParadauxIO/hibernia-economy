package io.paradaux.chestshop.utils;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LocationUtilTest {

    @Mock private World world;
    @Mock private Location location;

    @Test
    void locationToString_formatsAsBracketedWorldNameAndIntCoords() {
        lenient().when(world.getName()).thenReturn("world");
        lenient().when(location.getWorld()).thenReturn(world);
        lenient().when(location.getBlockX()).thenReturn(10);
        lenient().when(location.getBlockY()).thenReturn(64);
        lenient().when(location.getBlockZ()).thenReturn(-25);

        assertThat(LocationUtil.locationToString(location)).isEqualTo("[world] 10, 64, -25");
    }

    @Test
    void locationToString_includesNegativeZeroCoordinates() {
        lenient().when(world.getName()).thenReturn("nether");
        lenient().when(location.getWorld()).thenReturn(world);
        lenient().when(location.getBlockX()).thenReturn(0);
        lenient().when(location.getBlockY()).thenReturn(0);
        lenient().when(location.getBlockZ()).thenReturn(0);

        assertThat(LocationUtil.locationToString(location)).isEqualTo("[nether] 0, 0, 0");
    }

    @Test
    void worldName_returnsWorldName_whenLoaded() {
        lenient().when(world.getName()).thenReturn("world");
        lenient().when(location.getWorld()).thenReturn(world);

        assertThat(LocationUtil.worldName(location)).isEqualTo("world");
    }

    @Test
    void worldName_returnsPlaceholder_whenWorldUnloaded() {
        lenient().when(location.getWorld()).thenReturn(null); // ADT-140

        assertThat(LocationUtil.worldName(location)).isEqualTo("?");
    }
}
