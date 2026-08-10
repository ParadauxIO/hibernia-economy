package io.paradaux.chestshop.utils;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Coverage for the geometry/GUI helpers on {@link BlockUtil} the primary test doesn't reach. */
@ExtendWith(MockitoExtension.class)
class BlockUtilExtraTest {

    private Block loadedBlock() {
        Block block = mock(Block.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        lenient().when(block.getWorld()).thenReturn(world);
        lenient().when(block.getX()).thenReturn(0);
        lenient().when(block.getZ()).thenReturn(0);
        lenient().when(world.isChunkLoaded(0, 0)).thenReturn(true);
        return block;
    }

    @Test
    void isChestBlock_trueForChestData_falseOtherwise() {
        Block chest = loadedBlock();
        when(chest.getBlockData()).thenReturn(mock(Chest.class));
        assertThat(BlockUtil.isChest(chest)).isTrue();

        Block plain = loadedBlock();
        when(plain.getBlockData()).thenReturn(mock(BlockData.class));
        assertThat(BlockUtil.isChest(plain)).isFalse();
    }

    @Test
    void isChestBlock_falseWhenChunkUnloaded() {
        Block block = mock(Block.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(0);
        when(block.getZ()).thenReturn(0);
        when(world.isChunkLoaded(0, 0)).thenReturn(false); // isLoaded false -> short-circuit
        assertThat(BlockUtil.isChest(block)).isFalse();
    }

    @Test
    void findNeighbor_leavesFaceUnchanged_forAFacingOutsideTheCardinalSet() {
        Block block = loadedBlock();
        Chest data = mock(Chest.class);
        when(data.getType()).thenReturn(Chest.Type.LEFT);
        when(data.getFacing()).thenReturn(BlockFace.UP); // matches none of W/N/E/S -> face unchanged
        when(block.getBlockData()).thenReturn(data);
        when(block.getType()).thenReturn(org.bukkit.Material.CHEST);
        Block neighbor = loadedBlockAt(0, 0);
        when(neighbor.getType()).thenReturn(org.bukkit.Material.CHEST);
        when(block.getRelative(BlockFace.UP)).thenReturn(neighbor);
        assertThat(BlockUtil.findNeighbor(block)).isSameAs(neighbor);
    }

    @Test
    void isChestHolder_trueForChestAndDoubleChest_falseOtherwise() {
        assertThat(BlockUtil.isChest(mock(org.bukkit.block.Chest.class))).isTrue();
        assertThat(BlockUtil.isChest(mock(DoubleChest.class))).isTrue();
        assertThat(BlockUtil.isChest(mock(InventoryHolder.class))).isFalse();
    }

    @Test
    void getAttachedBlock_standingSign_attachesToBlockBelow() {
        org.bukkit.block.Sign sign = mock(org.bukkit.block.Sign.class);
        org.bukkit.block.data.type.Sign standing = mock(org.bukkit.block.data.type.Sign.class);
        when(sign.getBlockData()).thenReturn(standing);
        Block signBlock = mock(Block.class);
        Block below = mock(Block.class);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.DOWN)).thenReturn(below);
        assertThat(BlockUtil.getAttachedBlock(sign)).isSameAs(below);
    }

    @Test
    void getAttachedBlock_wallSign_attachesOppositeItsFacing() {
        org.bukkit.block.Sign sign = mock(org.bukkit.block.Sign.class);
        WallSign wall = mock(WallSign.class, org.mockito.Mockito.withSettings().extraInterfaces(Directional.class));
        when(sign.getBlockData()).thenReturn(wall);
        when(((Directional) wall).getFacing()).thenReturn(BlockFace.EAST);
        Block signBlock = mock(Block.class);
        Block behind = mock(Block.class);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.WEST)).thenReturn(behind); // opposite of EAST
        assertThat(BlockUtil.getAttachedBlock(sign)).isSameAs(behind);
    }

    @Test
    void getAttachedBlock_nonSignData_throws() {
        org.bukkit.block.Sign sign = mock(org.bukkit.block.Sign.class);
        when(sign.getBlockData()).thenReturn(mock(BlockData.class));
        assertThatThrownBy(() -> BlockUtil.getAttachedBlock(sign))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getMajorDirection_snapsIntermediateFacesToTheirMajorAxis() {
        assertThat(BlockUtil.getMajorDirection(BlockFace.NORTH_NORTH_EAST)).isEqualTo(BlockFace.NORTH);
        assertThat(BlockUtil.getMajorDirection(BlockFace.EAST_SOUTH_EAST)).isEqualTo(BlockFace.EAST);
        assertThat(BlockUtil.getMajorDirection(BlockFace.SOUTH_SOUTH_WEST)).isEqualTo(BlockFace.SOUTH);
        assertThat(BlockUtil.getMajorDirection(BlockFace.WEST_NORTH_WEST)).isEqualTo(BlockFace.WEST);
        assertThat(BlockUtil.getMajorDirection(BlockFace.UP)).isEqualTo(BlockFace.UP); // default: unchanged
    }

    @Test
    void openBlockGUI_opensTheHoldersInventory() {
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory inv = mock(Inventory.class);
        Player player = mock(Player.class);
        when(holder.getInventory()).thenReturn(inv);
        assertThat(BlockUtil.openBlockGUI(holder, player)).isTrue();
        verify(player).openInventory(inv);
    }

    @Test
    void isLoaded_falseForAnUnloadedChunk() {
        Block block = mock(Block.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(32);
        when(block.getZ()).thenReturn(48);
        when(world.isChunkLoaded(2, 3)).thenReturn(false);
        assertThat(BlockUtil.isLoaded(block)).isFalse();
    }

    @Test
    void getState_delegatesToTheBlock() {
        Block block = mock(Block.class);
        BlockState state = mock(BlockState.class);
        when(block.getState(false)).thenReturn(state);
        assertThat(BlockUtil.getState(block, false)).isSameAs(state);
    }

    @Test
    void findNeighbor_nullWhenUnloaded() {
        Block block = mock(Block.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(0);
        when(block.getZ()).thenReturn(0);
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @Test
    void findNeighbor_nullWhenNotAChest() {
        Block block = loadedBlock();
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @Test
    void findNeighbor_nullForASingleChest() {
        Block block = loadedBlock();
        Chest single = mock(Chest.class);
        when(single.getType()).thenReturn(Chest.Type.SINGLE);
        when(block.getBlockData()).thenReturn(single);
        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @Test
    void findNeighbor_returnsTheMatchingDoubleChestHalf_leftFacingWest() {
        Block block = loadedBlock();
        Chest left = mock(Chest.class);
        when(left.getType()).thenReturn(Chest.Type.LEFT);
        when(left.getFacing()).thenReturn(BlockFace.WEST); // WEST -> NORTH
        when(block.getBlockData()).thenReturn(left);
        when(block.getType()).thenReturn(org.bukkit.Material.CHEST);

        Block neighbor = loadedBlockAt(0, 0);
        when(neighbor.getType()).thenReturn(org.bukkit.Material.CHEST);
        when(block.getRelative(BlockFace.NORTH)).thenReturn(neighbor);

        assertThat(BlockUtil.findNeighbor(block)).isSameAs(neighbor);
    }

    @Test
    void findNeighbor_rightHalfUsesTheOppositeFace_andRejectsAMismatchedNeighbour() {
        Block block = loadedBlock();
        Chest right = mock(Chest.class);
        when(right.getType()).thenReturn(Chest.Type.RIGHT);
        when(right.getFacing()).thenReturn(BlockFace.NORTH); // NORTH -> EAST, RIGHT -> opposite -> WEST
        when(block.getBlockData()).thenReturn(right);
        when(block.getType()).thenReturn(org.bukkit.Material.CHEST);

        Block neighbor = loadedBlockAt(0, 0);
        when(neighbor.getType()).thenReturn(org.bukkit.Material.TRAPPED_CHEST); // mismatched -> null
        when(block.getRelative(BlockFace.WEST)).thenReturn(neighbor);

        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @Test
    void findNeighbor_nullWhenNeighbourUnloaded_eastAndSouthRotations() {
        Block block = loadedBlock();
        Chest left = mock(Chest.class);
        when(left.getType()).thenReturn(Chest.Type.LEFT);
        when(left.getFacing()).thenReturn(BlockFace.EAST); // EAST -> SOUTH
        when(block.getBlockData()).thenReturn(left);

        Block neighbor = mock(Block.class);
        org.bukkit.World nWorld = mock(org.bukkit.World.class);
        when(neighbor.getWorld()).thenReturn(nWorld);
        when(neighbor.getX()).thenReturn(0);
        when(neighbor.getZ()).thenReturn(0);
        when(nWorld.isChunkLoaded(0, 0)).thenReturn(false); // neighbour unloaded -> null
        when(block.getRelative(BlockFace.SOUTH)).thenReturn(neighbor);

        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @Test
    void findNeighbor_southRotation() {
        Block block = loadedBlock();
        Chest left = mock(Chest.class);
        when(left.getType()).thenReturn(Chest.Type.LEFT);
        when(left.getFacing()).thenReturn(BlockFace.SOUTH); // SOUTH -> WEST
        when(block.getBlockData()).thenReturn(left);
        when(block.getType()).thenReturn(org.bukkit.Material.CHEST);

        Block neighbor = loadedBlockAt(0, 0);
        when(neighbor.getType()).thenReturn(org.bukkit.Material.CHEST);
        when(block.getRelative(BlockFace.WEST)).thenReturn(neighbor);

        assertThat(BlockUtil.findNeighbor(block)).isSameAs(neighbor);
    }

    private Block loadedBlockAt(int chunkX, int chunkZ) {
        Block block = mock(Block.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        lenient().when(block.getWorld()).thenReturn(world);
        lenient().when(block.getX()).thenReturn(chunkX << 4);
        lenient().when(block.getZ()).thenReturn(chunkZ << 4);
        lenient().when(world.isChunkLoaded(chunkX, chunkZ)).thenReturn(true);
        return block;
    }
}
