package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.services.SignBreakService;
import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Pins the hot-path guard on {@link PhysicsBreakListener}. {@link BlockPhysicsEvent} fires for
 * essentially every block update in a loaded chunk, so the handler must short-circuit on a cheap
 * {@link org.bukkit.Tag#SIGNS} membership test before delegating to the expensive sign-resolution
 * work in {@link SignBreakService}.
 */
class PhysicsBreakListenerTest extends ServerTest {

    private WorldMock world;
    private SignBreakService signBreak;
    private PhysicsBreakListener listener;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("physicsworld");
        signBreak = mock(SignBreakService.class);
        listener = new PhysicsBreakListener(signBreak);
    }

    private BlockPhysicsEvent physicsEventAt(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return new BlockPhysicsEvent(block, block.getBlockData());
    }

    @Test
    void nonSignBlock_shortCircuitsWithoutDelegating() {
        listener.onSign(physicsEventAt(10, Material.STONE));

        verify(signBreak, never()).handlePhysicsBreak(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void standingSign_delegatesToSignBreak() {
        BlockPhysicsEvent event = physicsEventAt(20, Material.OAK_SIGN);

        listener.onSign(event);

        verify(signBreak, times(1)).handlePhysicsBreak(event.getBlock());
    }

    @Test
    void wallSign_delegatesToSignBreak() {
        BlockPhysicsEvent event = physicsEventAt(30, Material.OAK_WALL_SIGN);

        listener.onSign(event);

        verify(signBreak, times(1)).handlePhysicsBreak(event.getBlock());
    }
}
