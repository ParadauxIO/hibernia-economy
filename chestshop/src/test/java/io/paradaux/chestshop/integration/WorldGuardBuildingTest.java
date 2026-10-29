package io.paradaux.chestshop.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import io.paradaux.chestshop.model.BuildPermission;
import io.paradaux.chestshop.support.TestConfigs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorldGuardBuildingTest {
    @ParameterizedTest
    @CsvSource({
        // only-in-region, use-flag, region-count (-1: no manager), flag, allowed
        "true, false, -1, unset, false",
        "true, false, 0, unset, false",
        "true, false, 1, unset, true",
        "false, false, -1, unset, true",
        "false, false, 0, unset, true",
        "false, false, 1, ALLOW, true",
        "false, false, 1, DENY, false",
        "true, true, 1, ALLOW, true",
        "true, true, 1, DENY, false",
        "true, true, 1, unset, false",
        "false, true, 1, ALLOW, true",
        "false, true, 1, DENY, false",
        "false, true, -1, unset, false"
    })
    void regionAndFlagPolicy(boolean onlyRegion, boolean useFlag, int count, String state, boolean expected) {
        var config = TestConfigs.defaults();
        assertThat(config.isWorldguardOnlyInRegion()).isTrue();
        TestConfigs.with(config, "worldguardOnlyInRegion", onlyRegion);
        TestConfigs.with(config, "worldguardUseFlag", useFlag);
        var regions = count < 0 ? null : mock(ApplicableRegionSet.class);
        if (regions != null) {
            when(regions.size()).thenReturn(count);
            when(regions.queryState(any(), any(StateFlag.class)))
                    .thenReturn(state.equals("unset") ? null : StateFlag.State.valueOf(state));
        }
        var event = new BuildPermission(null, null, null);
        var flag = new StateFlag("allow-shop", false);
        WorldGuardIntegration.Building.checkRegions(event, regions, mock(LocalPlayer.class), config, flag);
        assertThat(event.isAllowed()).isEqualTo(expected);
        event.disallow();
        WorldGuardIntegration.Building.checkRegions(event, regions, mock(LocalPlayer.class), config, flag);
        assertThat(event.isAllowed()).isFalse();
    }
}
