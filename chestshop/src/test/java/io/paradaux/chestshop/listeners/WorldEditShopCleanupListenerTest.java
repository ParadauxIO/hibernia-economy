package io.paradaux.chestshop.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

class WorldEditShopCleanupListenerTest {
    @Test
    void actorlessEditsAreDeferredUntilTheirChunkLoads() throws Exception {
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        var server = mock(org.bukkit.Server.class);
        var plugins = mock(org.bukkit.plugin.PluginManager.class);
        var market = mock(io.paradaux.chestshop.services.MarketService.class);
        var sync = mock(io.paradaux.chestshop.services.MarketSyncService.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        when(market.enabled()).thenReturn(true);
        var world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("world");
        when(server.getWorld("world")).thenReturn(world);
        var editWorld = mock(com.sk89q.worldedit.world.World.class);
        when(editWorld.getName()).thenReturn("world");
        var event = mock(com.sk89q.worldedit.event.extent.EditSessionEvent.class);
        when(event.getWorld()).thenReturn(editWorld);
        when(event.getStage()).thenReturn(com.sk89q.worldedit.EditSession.Stage.BEFORE_CHANGE);
        Extent extent = mock(Extent.class);
        when(event.getExtent()).thenReturn(extent);
        var listener = new WorldEditShopCleanupListener(plugin, market, sync);
        listener.onEditSession(event);
        var captured = org.mockito.ArgumentCaptor.forClass(Extent.class);
        verify(event).setExtent(captured.capture());
        BlockState sign = block("minecraft:oak_sign");
        BlockVector3 pos = BlockVector3.at(-17, 64, 33);
        when(extent.setBlock(pos, sign)).thenReturn(true);
        captured.getValue().setBlock(pos, sign);
        Operations.complete(captured.getValue().commit());
        listener.drain();
        verifyNoInteractions(sync);
        var chunk = mock(org.bukkit.Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(-2);
        when(chunk.getZ()).thenReturn(2);
        when(world.isChunkLoaded(-2, 2)).thenReturn(true);
        listener.onChunkLoad(new org.bukkit.event.world.ChunkLoadEvent(chunk, false));
        listener.drain();
        verify(sync).onBlockReplaced("world", -17, 64, 33);
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    private BlockState block(String id) {
        BlockState state = mock(BlockState.class);
        BlockType type = mock(BlockType.class);
        when(state.getBlockType()).thenReturn(type);
        when(type.id()).thenReturn(id);
        return state;
    }

    @Test
    void publishesAcceptedSignChangesOnlyAfterDelegateCommit() throws Exception {
        Queue<WorldEditShopCleanupListener.Pos> queue = new ConcurrentLinkedQueue<>();
        Extent extent = mock(Extent.class);
        BlockVector3 pos = BlockVector3.at(-17, 64, 33);
        BlockState air = block("minecraft:air");
        BlockState existing = block("minecraft:oak_wall_hanging_sign");
        when(extent.getBlock(pos)).thenReturn(existing);
        when(extent.setBlock(pos, air)).thenReturn(true);
        Operation commit = mock(Operation.class);
        when(extent.commit()).thenReturn(commit);
        when(commit.resume(any())).thenAnswer(invocation -> {
            assertThat(queue).isEmpty();
            return null;
        });
        var logger = new WorldEditShopCleanupListener.SignChangeLogger(queue, extent, "world");
        assertThat(logger.setBlock(pos, air)).isTrue();
        assertThat(queue).isEmpty();
        Operations.complete(logger.commit());
        assertThat(queue).containsExactly(new WorldEditShopCleanupListener.Pos("world", -17, 64, 33));
    }

    @Test
    void rejectedEditsAndOrdinaryBlocksDoNotEnterQueue() throws Exception {
        Queue<WorldEditShopCleanupListener.Pos> queue = new ConcurrentLinkedQueue<>();
        Extent extent = mock(Extent.class);
        BlockVector3 pos = BlockVector3.at(1, 2, 3);
        BlockState air = block("minecraft:air");
        BlockState existing = block("minecraft:oak_sign");
        when(extent.getBlock(pos)).thenReturn(existing);
        var logger = new WorldEditShopCleanupListener.SignChangeLogger(queue, extent, "world");
        assertThat(logger.setBlock(pos, air)).isFalse();
        when(extent.getBlock(pos)).thenReturn(air);
        when(extent.setBlock(pos, air)).thenReturn(true);
        logger.setBlock(pos, air);
        Operations.complete(logger.commit());
        assertThat(queue).isEmpty();
    }

    @Test
    void undoOrPasteQueuesRestoredSignAndDeduplicatesWithinSession() throws Exception {
        Queue<WorldEditShopCleanupListener.Pos> queue = new ConcurrentLinkedQueue<>();
        Extent extent = mock(Extent.class);
        BlockVector3 pos = BlockVector3.at(1, 2, 3);
        BlockState sign = block("minecraft:oak_wall_sign");
        when(extent.setBlock(pos, sign)).thenReturn(true);
        var logger = new WorldEditShopCleanupListener.SignChangeLogger(queue, extent, "world");
        logger.setBlock(pos, sign);
        logger.setBlock(pos, sign);
        Operations.complete(logger.commit());
        Operations.complete(logger.commit());
        assertThat(queue).containsExactly(new WorldEditShopCleanupListener.Pos("world", 1, 2, 3));
    }
}
