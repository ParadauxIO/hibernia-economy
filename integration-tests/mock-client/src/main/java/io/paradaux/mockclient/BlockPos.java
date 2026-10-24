package io.paradaux.mockclient;

import org.cloudburstmc.math.vector.Vector3i;

/**
 * Integer block coordinates. Each axis identifies the south-east-bottom corner
 * of a 1×1×1 block. Use {@link #toVector3i()} when the underlying MCProtocolLib
 * surface is needed directly.
 */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    public Vector3i toVector3i() {
        return Vector3i.from(x, y, z);
    }

    public BlockPos offset(BlockFace face) {
        return new BlockPos(x + face.dx(), y + face.dy(), z + face.dz());
    }
}
