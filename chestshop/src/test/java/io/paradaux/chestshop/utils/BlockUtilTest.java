package io.paradaux.chestshop.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockUtilTest {

    @Mock
    private Block block;

    @Mock
    private org.bukkit.World world;

    /**
     * Helper: stub the chunk-loaded check so isLoaded(block) returns true
     * (otherwise everything in BlockUtil short-circuits to false).
     */
    private void stubLoaded(boolean loaded) {
        lenient().when(block.getWorld()).thenReturn(world);
        lenient().when(block.getX()).thenReturn(0);
        lenient().when(block.getZ()).thenReturn(0);
        lenient().when(world.isChunkLoaded(0, 0)).thenReturn(loaded);
    }

    // ── isSign ────────────────────────────────────────────────────────────────

    @Test
    void isSign_returnsTrueForStandingSign() {
        stubLoaded(true);
        when(block.getBlockData()).thenReturn(mock(Sign.class));

        assertThat(BlockUtil.isSign(block)).isTrue();
    }

    @Test
    void isSign_returnsTrueForWallSign() {
        stubLoaded(true);
        when(block.getBlockData()).thenReturn(mock(WallSign.class));

        assertThat(BlockUtil.isSign(block)).isTrue();
    }

    @Test
    void isSign_returnsFalseForNonSign() {
        stubLoaded(true);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));

        assertThat(BlockUtil.isSign(block)).isFalse();
    }

    @Test
    void isSign_returnsFalseWhenChunkUnloaded() {
        stubLoaded(false);
        // No need to stub getBlockData — short-circuits before that call.

        assertThat(BlockUtil.isSign(block)).isFalse();
    }

    // ── isChest (Block overload) ──────────────────────────────────────────────

    @Test
    void isChest_block_returnsTrueForChestBlockData() {
        stubLoaded(true);
        when(block.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.Chest.class));

        assertThat(BlockUtil.isChest(block)).isTrue();
    }

    @Test
    void isChest_block_returnsFalseForNonChest() {
        stubLoaded(true);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));

        assertThat(BlockUtil.isChest(block)).isFalse();
    }

    // ── isChest (InventoryHolder overload) ────────────────────────────────────

    @Test
    void isChest_holder_returnsTrueForChestAndDoubleChest() {
        InventoryHolder chest = mock(org.bukkit.block.Chest.class);
        InventoryHolder doubleChest = mock(DoubleChest.class);

        assertThat(BlockUtil.isChest(chest)).isTrue();
        assertThat(BlockUtil.isChest(doubleChest)).isTrue();
    }

    @Test
    void isChest_holder_returnsFalseForOtherHolders() {
        InventoryHolder generic = mock(InventoryHolder.class);
        assertThat(BlockUtil.isChest(generic)).isFalse();
    }

    // ── getMajorDirection ─────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(
            value = BlockFace.class,
            names = {"NORTH_WEST", "NORTH_NORTH_WEST", "NORTH_NORTH_EAST"})
    void getMajorDirection_collapsesNorthDiagonalsToNorth(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(BlockFace.NORTH);
    }

    @ParameterizedTest
    @EnumSource(
            value = BlockFace.class,
            names = {"NORTH_EAST", "EAST_NORTH_EAST", "EAST_SOUTH_EAST"})
    void getMajorDirection_collapsesEastDiagonalsToEast(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(BlockFace.EAST);
    }

    @ParameterizedTest
    @EnumSource(
            value = BlockFace.class,
            names = {"SOUTH_EAST", "SOUTH_SOUTH_EAST", "SOUTH_SOUTH_WEST"})
    void getMajorDirection_collapsesSouthDiagonalsToSouth(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(BlockFace.SOUTH);
    }

    @ParameterizedTest
    @EnumSource(
            value = BlockFace.class,
            names = {"SOUTH_WEST", "WEST_NORTH_WEST", "WEST_SOUTH_WEST"})
    void getMajorDirection_collapsesWestDiagonalsToWest(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(BlockFace.WEST);
    }

    @ParameterizedTest
    @EnumSource(
            value = BlockFace.class,
            names = {"NORTH", "EAST", "SOUTH", "WEST", "UP", "DOWN", "SELF"})
    void getMajorDirection_passesCardinalDirectionsThrough(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(face);
    }

    // ── getAttachedBlock ──────────────────────────────────────────────────────

    @Test
    void getAttachedBlock_wallSignUsesOppositeOfFacing() {
        // WallSign extends Directional. The sign attaches to the block opposite its facing.
        org.bukkit.block.Sign sign = mock(org.bukkit.block.Sign.class);
        WallSign wallSign =
                mock(WallSign.class, org.mockito.Mockito.withSettings().extraInterfaces(Directional.class));
        when(sign.getBlockData()).thenReturn(wallSign);
        when(((Directional) wallSign).getFacing()).thenReturn(BlockFace.NORTH);

        Block signBlock = mock(Block.class);
        Block expected = mock(Block.class);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.SOUTH)).thenReturn(expected);

        assertThat(BlockUtil.getAttachedBlock(sign)).isSameAs(expected);
    }

    @Test
    void getAttachedBlock_standingSignUsesDown() {
        org.bukkit.block.Sign sign = mock(org.bukkit.block.Sign.class);
        Sign signData = mock(Sign.class);
        when(sign.getBlockData()).thenReturn(signData);

        Block signBlock = mock(Block.class);
        Block expected = mock(Block.class);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.DOWN)).thenReturn(expected);

        assertThat(BlockUtil.getAttachedBlock(sign)).isSameAs(expected);
    }

    @Test
    void getAttachedBlock_nonSignDataThrows() {
        org.bukkit.block.Sign sign = mock(org.bukkit.block.Sign.class);
        when(sign.getBlockData()).thenReturn(mock(BlockData.class));

        assertThatThrownBy(() -> BlockUtil.getAttachedBlock(sign)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── isLoaded ──────────────────────────────────────────────────────────────

    @Test
    void isLoaded_checksTheChunkContainingTheBlock() {
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(32); // chunk 2
        when(block.getZ()).thenReturn(-17); // chunk -2 (arithmetic shift floors negatives)
        when(world.isChunkLoaded(2, -2)).thenReturn(true);

        assertThat(BlockUtil.isLoaded(block)).isTrue();
    }

    @Test
    void isChest_block_returnsFalseWhenChunkUnloaded() {
        stubLoaded(false);

        assertThat(BlockUtil.isChest(block)).isFalse();
    }

    // ── openBlockGUI ──────────────────────────────────────────────────────────

    @Test
    void openBlockGUI_opensTheHoldersInventoryForThePlayer() {
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory inv = mock(Inventory.class);
        Player player = mock(Player.class);
        when(holder.getInventory()).thenReturn(inv);

        assertThat(BlockUtil.openBlockGUI(holder, player)).isTrue();
        verify(player).openInventory(inv);
    }

    // ── findNeighbor ──────────────────────────────────────────────────────────

    private static Block loadedBlockAt(int chunkX, int chunkZ) {
        Block b = mock(Block.class);
        org.bukkit.World w = mock(org.bukkit.World.class);
        lenient().when(b.getWorld()).thenReturn(w);
        lenient().when(b.getX()).thenReturn(chunkX << 4);
        lenient().when(b.getZ()).thenReturn(chunkZ << 4);
        lenient().when(w.isChunkLoaded(chunkX, chunkZ)).thenReturn(true);
        return b;
    }

    /** Make {@link #block} a chest half of the given type, facing the given way. */
    private void stubChestData(Chest.Type type, BlockFace facing) {
        Chest data = mock(Chest.class);
        lenient().when(data.getType()).thenReturn(type);
        lenient().when(data.getFacing()).thenReturn(facing);
        when(block.getBlockData()).thenReturn(data);
    }

    @Test
    void findNeighbor_nullWhenUnloaded() {
        stubLoaded(false);

        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @Test
    void findNeighbor_nullWhenNotAChest() {
        stubLoaded(true);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));

        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @Test
    void findNeighbor_nullForASingleChest() {
        stubLoaded(true);
        stubChestData(Chest.Type.SINGLE, BlockFace.NORTH);

        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        // half, chest facing, face the other half sits on
        "LEFT,  WEST,  NORTH",
        "LEFT,  NORTH, EAST",
        "LEFT,  EAST,  SOUTH",
        "LEFT,  SOUTH, WEST",
        "RIGHT, WEST,  SOUTH",
        "RIGHT, NORTH, WEST",
        "RIGHT, EAST,  NORTH",
        "RIGHT, SOUTH, EAST",
    })
    void findNeighbor_returnsTheOtherHalfOnTheRotatedFace(Chest.Type half, BlockFace facing, BlockFace neighbourFace) {
        stubLoaded(true);
        stubChestData(half, facing);
        when(block.getType()).thenReturn(Material.CHEST);
        Block neighbour = loadedBlockAt(0, 0);
        when(neighbour.getType()).thenReturn(Material.CHEST);
        when(block.getRelative(neighbourFace)).thenReturn(neighbour);

        assertThat(BlockUtil.findNeighbor(block)).isSameAs(neighbour);
    }

    @Test
    void findNeighbor_nullWhenTheNeighbourIsADifferentContainerType() {
        stubLoaded(true);
        stubChestData(Chest.Type.LEFT, BlockFace.WEST);
        when(block.getType()).thenReturn(Material.CHEST);
        Block neighbour = loadedBlockAt(0, 0);
        when(neighbour.getType()).thenReturn(Material.TRAPPED_CHEST);
        when(block.getRelative(BlockFace.NORTH)).thenReturn(neighbour);

        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }

    @Test
    void findNeighbor_nullWhenTheNeighboursChunkIsUnloaded() {
        stubLoaded(true);
        stubChestData(Chest.Type.LEFT, BlockFace.EAST);
        Block neighbour = mock(Block.class);
        org.bukkit.World neighbourWorld = mock(org.bukkit.World.class);
        when(neighbour.getWorld()).thenReturn(neighbourWorld);
        when(neighbour.getX()).thenReturn(16);
        when(neighbour.getZ()).thenReturn(0);
        when(neighbourWorld.isChunkLoaded(1, 0)).thenReturn(false);
        when(block.getRelative(BlockFace.SOUTH)).thenReturn(neighbour);

        assertThat(BlockUtil.findNeighbor(block)).isNull();
    }
}
