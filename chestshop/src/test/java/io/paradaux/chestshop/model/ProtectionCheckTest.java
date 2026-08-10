package io.paradaux.chestshop.model;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Covers the {@link ProtectionCheck} carrier: its three constructors (with their defaulted
 * flags), the DEFAULT→DENY result state, and the block/player accessors. The block and player
 * are pure pass-through boundaries here, so they are mocked.
 */
class ProtectionCheckTest {

    private final Block block = mock(Block.class);
    private final Player player = mock(Player.class);

    @Test
    void twoArgConstructor_defaultsFlags() {
        ProtectionCheck check = new ProtectionCheck(block, player);

        assertThat(check.isBuiltInProtectionIgnored()).isFalse();
        assertThat(check.checkCanManage()).isTrue();
        assertThat(check.getBlock()).isSameAs(block);
        assertThat(check.getPlayer()).isSameAs(player);
        assertThat(check.getResult()).isEqualTo(Event.Result.DEFAULT);
    }

    @Test
    void threeArgConstructor_setsIgnoreFlag() {
        ProtectionCheck check = new ProtectionCheck(block, player, true);

        assertThat(check.isBuiltInProtectionIgnored()).isTrue();
        assertThat(check.checkCanManage()).isTrue();
    }

    @Test
    void fourArgConstructor_setsBothFlags() {
        ProtectionCheck check = new ProtectionCheck(block, player, true, false);

        assertThat(check.isBuiltInProtectionIgnored()).isTrue();
        assertThat(check.checkCanManage()).isFalse();
    }

    @Test
    void setResult_deny_isReadBack() {
        ProtectionCheck check = new ProtectionCheck(block, player);
        check.setResult(Event.Result.DENY);
        assertThat(check.getResult()).isEqualTo(Event.Result.DENY);
    }
}
