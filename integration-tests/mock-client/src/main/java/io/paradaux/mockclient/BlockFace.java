package io.paradaux.mockclient;

import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/**
 * The six cardinal block faces. Maps onto MCProtocolLib's
 * {@link Direction} enum at the wire level.
 */
public enum BlockFace {
    DOWN  ( 0, -1,  0, Direction.DOWN ),
    UP    ( 0,  1,  0, Direction.UP   ),
    NORTH ( 0,  0, -1, Direction.NORTH),
    SOUTH ( 0,  0,  1, Direction.SOUTH),
    WEST  (-1,  0,  0, Direction.WEST ),
    EAST  ( 1,  0,  0, Direction.EAST );

    private final int dx;
    private final int dy;
    private final int dz;
    private final Direction direction;

    BlockFace(int dx, int dy, int dz, Direction direction) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.direction = direction;
    }

    public int dx() { return dx; }
    public int dy() { return dy; }
    public int dz() { return dz; }
    public Direction direction() { return direction; }

    public BlockFace opposite() {
        return switch (this) {
            case DOWN  -> UP;
            case UP    -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST  -> EAST;
            case EAST  -> WEST;
        };
    }
}
