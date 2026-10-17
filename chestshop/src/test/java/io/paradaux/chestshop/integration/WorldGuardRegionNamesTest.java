package io.paradaux.chestshop.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldGuardRegionNamesTest {
    private ProtectedRegion region(String name, int priority) {
        ProtectedRegion region = mock(ProtectedRegion.class);
        when(region.getId()).thenReturn(name);
        when(region.getPriority()).thenReturn(priority);
        return region;
    }

    @Test
    void deterministicPriorityAndNameOrderExcludesGlobal() {
        assertThat(WorldGuardIntegration.RegionNames.select(Set.of())).isEmpty();
        assertThat(WorldGuardIntegration.RegionNames.select(Set.of(
                        region("__global__", 100), region("city", 0),
                        region("market-z", 10), region("market-a", 10))))
                .isEqualTo("market-a");
    }
}
