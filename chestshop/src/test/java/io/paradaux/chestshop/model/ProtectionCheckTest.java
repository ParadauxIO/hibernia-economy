package io.paradaux.chestshop.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

/**
 * The {@link ProtectionCheck} defaults: a check starts undecided, honours built-in protection
 * and checks management rights unless a constructor overrides those flags.
 */
class ProtectionCheckTest {

    private final Block block = mock(Block.class);
    private final Player player = mock(Player.class);

    @Test
    void twoArgConstructor_defaultsToUndecidedWithBuiltInProtectionAndManagementCheck() {
        ProtectionCheck check = new ProtectionCheck(block, player);

        assertThat(check.getResult()).isEqualTo(Event.Result.DEFAULT);
        assertThat(check.isBuiltInProtectionIgnored()).isFalse();
        assertThat(check.checkCanManage()).isTrue();
        assertThat(check.getBlock()).isSameAs(block);
        assertThat(check.getPlayer()).isSameAs(player);
    }

    @Test
    void explicitFlags_overrideOnlyTheDefaultsTheyName() {
        ProtectionCheck ignoreOnly = new ProtectionCheck(block, player, true);
        assertThat(ignoreOnly.isBuiltInProtectionIgnored()).isTrue();
        assertThat(ignoreOnly.checkCanManage()).isTrue();

        ProtectionCheck both = new ProtectionCheck(block, player, true, false);
        assertThat(both.isBuiltInProtectionIgnored()).isTrue();
        assertThat(both.checkCanManage()).isFalse();
    }
}
