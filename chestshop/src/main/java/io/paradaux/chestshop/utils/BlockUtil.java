package io.paradaux.chestshop.utils;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Pure, stateless block helpers: sign/chest tests, attached-block and double-chest neighbour
 * geometry, direction math, block-state reads and GUI opening. The stateful, config/sign-backed
 * resolution (getConnectedSign, findConnectedContainer, isShopBlock, …) lives on
 * {@link io.paradaux.chestshop.services.ShopBlockService}.
 *
 * @author Acrobot
 */
public final class BlockUtil {

    /** The faces off a chest that a second chest may extend it into (double-chest search). */
    public static final BlockFace[] CHEST_EXTENSION_FACES = {BlockFace.EAST, BlockFace.NORTH, BlockFace.WEST, BlockFace.SOUTH};

    /** The faces around a shop container that its sign/neighbours may sit on. */
    public static final BlockFace[] SHOP_FACES = {BlockFace.SELF, BlockFace.DOWN, BlockFace.UP, BlockFace.EAST, BlockFace.NORTH, BlockFace.WEST, BlockFace.SOUTH};

    private BlockUtil() {
    }

    /**
     * Checks if the block is a sign
     *
     * @param block Block to check
     * @return Is this block a sign?
     */
    public static boolean isSign(Block block) {
        if (!isLoaded(block)) {
            return false;
        }

        BlockData data = block.getBlockData();
        return data instanceof Sign || data instanceof WallSign;
    }

    /**
     * Checks if the block is a chest
     *
     * @param block Block to check
     * @return Is this block a chest?
     */
    public static boolean isChest(Block block) {
        return BlockUtil.isLoaded(block) && block.getBlockData() instanceof org.bukkit.block.data.type.Chest;
    }

    /**
     * Checks if the InventoryHolder is a chest
     *
     * @param holder Inventory holder to check
     * @return Is this holder a chest?
     */
    public static boolean isChest(InventoryHolder holder) {
        return holder instanceof org.bukkit.block.Chest || holder instanceof DoubleChest;
    }

    /**
     * Gets the block to which the sign is attached
     *
     * @param sign Sign which is attached
     * @return Block to which the sign is attached
     */
    public static Block getAttachedBlock(org.bukkit.block.Sign sign) {
        BlockFace direction;
        BlockData blockData = sign.getBlockData();
        if (blockData instanceof WallSign) {
            direction = ((Directional) blockData).getFacing().getOppositeFace();
        } else if (blockData instanceof Sign) {
            direction = BlockFace.DOWN;
        } else {
            throw new IllegalArgumentException("Cannot get direction of " + blockData.getClass().getSimpleName());
        }
        return sign.getBlock().getRelative(direction);
    }

    /**
     * Convert a blockface to a major direction
     *
     * @param face The face to get the major direction from
     * @return The major direction. For middle directions it will return the next clockwise direction
     */
    public static BlockFace getMajorDirection(BlockFace face) {
        switch (face) {
            case NORTH_WEST:
            case NORTH_NORTH_WEST:
            case NORTH_NORTH_EAST:
                return BlockFace.NORTH;
            case NORTH_EAST:
            case EAST_NORTH_EAST:
            case EAST_SOUTH_EAST:
                return BlockFace.EAST;
            case SOUTH_EAST:
            case SOUTH_SOUTH_EAST:
            case SOUTH_SOUTH_WEST:
                return BlockFace.SOUTH;
            case SOUTH_WEST:
            case WEST_NORTH_WEST:
            case WEST_SOUTH_WEST:
                return BlockFace.WEST;
            default:
                return face;
        }
    }

    /**
     * Opens the holder's inventory GUI
     *
     * @param holder Inventory holder
     * @param player Player on whose screen the GUI is going to be shown
     * @return Was the opening successful?
     */
    public static boolean openBlockGUI(InventoryHolder holder, Player player) {
        Inventory inventory = holder.getInventory();
        player.openInventory(inventory);

        return true;
    }

    /**
     * Check if the chunk a block is in is loaded
     *
     * @param block The block to check
     * @return Whether or not the chunk is loaded
     */
    public static boolean isLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    /**
     * The other half of a double chest, or {@code null} if {@code block} is a single chest
     * (or the neighbour isn't loaded / isn't the same type).
     */
    public static Block findNeighbor(Block block) {
        if (!isLoaded(block)) {
            return null;
        }

        BlockData blockData = block.getBlockData();
        if (!(blockData instanceof Chest)) {
            return null;
        }

        Chest chestData = (Chest) blockData;
        if (chestData.getType() == Chest.Type.SINGLE) {
            return null;
        }

        BlockFace chestFace = chestData.getFacing();
        // we have to rotate is to get the adjacent chest
        // west, right -> south
        // west, left -> north
        if (chestFace == BlockFace.WEST) {
            chestFace = BlockFace.NORTH;
        } else if (chestFace == BlockFace.NORTH) {
            chestFace = BlockFace.EAST;
        } else if (chestFace == BlockFace.EAST) {
            chestFace = BlockFace.SOUTH;
        } else if (chestFace == BlockFace.SOUTH) {
            chestFace = BlockFace.WEST;
        }
        if (chestData.getType() == Chest.Type.RIGHT) {
            chestFace = chestFace.getOppositeFace();
        }

        Block neighborBlock = block.getRelative(chestFace);
        if (!isLoaded(neighborBlock)) {
            return null;
        }

        if (neighborBlock.getType() == block.getType()) {
            return neighborBlock;
        }

        return null;
    }

    /**
     * Read a block's state, snapshot or live.
     *
     * @param block       the block to read
     * @param useSnapshot {@code false} to read the live block state (not a snapshot copy)
     * @return the block's state
     */
    public static BlockState getState(Block block, boolean useSnapshot) {
        return block.getState(useSnapshot);
    }
}
