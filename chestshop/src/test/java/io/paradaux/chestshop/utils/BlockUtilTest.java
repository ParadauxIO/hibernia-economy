package io.paradaux.chestshop.utils;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockUtilTest {

    @Mock private Block block;
    @Mock private org.bukkit.World world;

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
    @EnumSource(value = BlockFace.class, names = {"NORTH_WEST", "NORTH_NORTH_WEST", "NORTH_NORTH_EAST"})
    void getMajorDirection_collapsesNorthDiagonalsToNorth(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(BlockFace.NORTH);
    }

    @ParameterizedTest
    @EnumSource(value = BlockFace.class, names = {"NORTH_EAST", "EAST_NORTH_EAST", "EAST_SOUTH_EAST"})
    void getMajorDirection_collapsesEastDiagonalsToEast(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(BlockFace.EAST);
    }

    @ParameterizedTest
    @EnumSource(value = BlockFace.class, names = {"SOUTH_EAST", "SOUTH_SOUTH_EAST", "SOUTH_SOUTH_WEST"})
    void getMajorDirection_collapsesSouthDiagonalsToSouth(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(BlockFace.SOUTH);
    }

    @ParameterizedTest
    @EnumSource(value = BlockFace.class, names = {"SOUTH_WEST", "WEST_NORTH_WEST", "WEST_SOUTH_WEST"})
    void getMajorDirection_collapsesWestDiagonalsToWest(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(BlockFace.WEST);
    }

    @ParameterizedTest
    @EnumSource(value = BlockFace.class, names = {"NORTH", "EAST", "SOUTH", "WEST", "UP", "DOWN", "SELF"})
    void getMajorDirection_passesCardinalDirectionsThrough(BlockFace face) {
        assertThat(BlockUtil.getMajorDirection(face)).isEqualTo(face);
    }

    // ── getAttachedBlock ──────────────────────────────────────────────────────

    @Test
    void getAttachedBlock_wallSignUsesOppositeOfFacing() {
        // WallSign extends Directional. The sign attaches to the block opposite its facing.
        org.bukkit.block.Sign sign = mock(org.bukkit.block.Sign.class);
        WallSign wallSign = mock(WallSign.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(Directional.class));
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
}
