package io.paradaux.chestshop.listeners;

import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** Loaded only with FAWE installed; its queue bypasses WorldEdit's per-block extent callbacks. */
final class FaweShopCleanup implements IBatchProcessor {
    private final String world;
    private final Queue<WorldEditShopCleanupListener.Pos> queue;

    FaweShopCleanup(String world, Queue<WorldEditShopCleanupListener.Pos> queue) {
        this.world = world;
        this.queue = queue;
    }

    static void install(EditSessionEvent event, Queue<WorldEditShopCleanupListener.Pos> queue) {
        // WorldEdit's Extent has no addPostProcessor method. Resolve the FAWE extension
        // only here, retaining binary compatibility with ordinary WorldEdit.
        try {
            Extent extent = (Extent) Extent.class.getMethod("addPostProcessor", IBatchProcessor.class)
                    .invoke(event.getExtent(), new FaweShopCleanup(event.getWorld().getName(), queue));
            event.setExtent(extent);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot register FAWE shop cleanup", e);
        }
    }

    @Override
    public void postProcess(IChunk chunk, IChunkGet before, IChunkSet after) {
        if (before == null || after == null) return; // a cancelled/empty chunk batch
        // Both snapshots matter: removed tile entities disappear from after, while
        // paste/undo adds signs absent from before. Tile coordinates are chunk-local.
        Set<BlockVector3> tiles = new HashSet<>(before.tiles().keySet());
        tiles.addAll(after.tiles().keySet());
        for (BlockVector3 pos : tiles) {
            queue.add(new WorldEditShopCleanupListener.Pos(world,
                    (chunk.getX() << 4) + pos.x(), pos.y(), (chunk.getZ() << 4) + pos.z()));
        }
    }

    @Override public Future<?> postProcessSet(IChunk chunk, IChunkGet before, IChunkSet after) {
        postProcess(chunk, before, after);
        return CompletableFuture.completedFuture(null);
    }

    @Override public ProcessorScope getScope() { return ProcessorScope.READING_BLOCKS; }
    @Override public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) { return set; }
    @Override public Extent construct(Extent child) { return child; }
}
