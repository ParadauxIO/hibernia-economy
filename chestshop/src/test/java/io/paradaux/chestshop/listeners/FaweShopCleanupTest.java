package io.paradaux.chestshop.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.math.BlockVector3;
import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

class FaweShopCleanupTest {
    @Test
    void completedChunksIncludeRemovedAndPastedTilesWithWorldCoordinates() {
        IChunk chunk = mock(IChunk.class);
        when(chunk.getX()).thenReturn(-2);
        when(chunk.getZ()).thenReturn(3);
        IChunkGet before = mock(IChunkGet.class);
        IChunkSet after = mock(IChunkSet.class);
        var oldTiles = new HashMap<BlockVector3, com.fastasyncworldedit.core.nbt.FaweCompoundTag>();
        oldTiles.put(BlockVector3.at(15, 64, 1), null);
        var newTiles = new HashMap<BlockVector3, com.fastasyncworldedit.core.nbt.FaweCompoundTag>();
        newTiles.put(BlockVector3.at(15, 64, 1), null);
        newTiles.put(BlockVector3.at(0, 70, 2), null);
        when(before.tiles()).thenReturn(oldTiles);
        when(after.tiles()).thenReturn(newTiles);
        Queue<WorldEditShopCleanupListener.Pos> queue = new ConcurrentLinkedQueue<>();
        var cleanup = new FaweShopCleanup("world", queue);
        assertThat(cleanup.processSet(chunk, before, after)).isSameAs(after);
        assertThat(queue).isEmpty();
        var other = mock(com.fastasyncworldedit.core.queue.IBatchProcessor.class);
        com.fastasyncworldedit.core.extent.processor.MultiBatchProcessor.of(cleanup, other)
                .postProcess(chunk, before, after);
        assertThat(queue)
                .containsExactlyInAnyOrder(
                        new WorldEditShopCleanupListener.Pos("world", -17, 64, 49),
                        new WorldEditShopCleanupListener.Pos("world", -32, 70, 50));
    }
}
