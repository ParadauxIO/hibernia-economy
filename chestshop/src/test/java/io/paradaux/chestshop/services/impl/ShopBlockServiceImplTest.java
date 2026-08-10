package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real block↔sign geometry on MockBukkit: {@link ShopBlockServiceImpl} resolving the sign a
 * container is attached to (and vice-versa) and the shop-container predicates, over a real
 * {@link SignService} and {@code SHOP_CONTAINERS} config with placed chests and wall signs.
 */
class ShopBlockServiceImplTest extends ServerTest {

    private ShopBlockServiceImpl service;
    private World world;
    private int base = 0;

    @BeforeEach
    void wire() {
        ChestShopConfiguration config = TestConfigs.with(TestConfigs.defaults(),
                "shopContainersRaw", List.of("CHEST"));
        service = new ShopBlockServiceImpl(config, new SignService(config));
        world = server.addSimpleWorld("blockworld");
        for (int cx = -2; cx <= 40; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                world.loadChunk(cx, cz);
            }
        }
    }

    /** A chest with a valid wall-shop-sign hung on the block to its EAST. Returns [chest, sign]. */
    private Block[] shopWithWallSign() {
        int x = base += 6;
        Block chest = world.getBlockAt(x, 64, 0);
        chest.setType(Material.CHEST);

        Block signBlock = world.getBlockAt(x + 1, 64, 0);
        signBlock.setType(Material.OAK_WALL_SIGN);
        WallSign data = (WallSign) signBlock.getBlockData();
        data.setFacing(BlockFace.EAST); // opposite (WEST) points back at the chest
        signBlock.setBlockData(data);

        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, "Notch");
        sign.setLine(SignService.QUANTITY_LINE, "64");
        sign.setLine(SignService.PRICE_LINE, "B 5");
        sign.setLine(SignService.ITEM_LINE, "Diamond");
        sign.update();

        return new Block[]{chest, signBlock};
    }

    // ---- couldBeShopContainer ---------------------------------------------------

    @Test
    void couldBeShopContainer_block() {
        Block chest = world.getBlockAt(200, 64, 0);
        chest.setType(Material.CHEST);
        assertThat(service.couldBeShopContainer(chest)).isTrue();

        Block stone = world.getBlockAt(202, 64, 0);
        stone.setType(Material.STONE);
        assertThat(service.couldBeShopContainer(stone)).isFalse();

        assertThat(service.couldBeShopContainer((Block) null)).isFalse();
    }

    @Test
    void couldBeShopContainer_holder_containerAndDoubleChest() {
        Block chest = world.getBlockAt(204, 64, 0);
        chest.setType(Material.CHEST);
        Container container = (Container) chest.getState();
        assertThat(service.couldBeShopContainer((InventoryHolder) container)).isTrue();

        DoubleChest dc = mock(DoubleChest.class);
        when(dc.getLeftSide(false)).thenReturn((InventoryHolder) container);
        assertThat(service.couldBeShopContainer(dc)).isTrue();

        assertThat(service.couldBeShopContainer(mock(InventoryHolder.class))).isFalse();
    }

    // ---- findConnectedContainer -------------------------------------------------

    @Test
    void findConnectedContainer_fromWallSign() {
        Block[] shop = shopWithWallSign();
        Sign sign = (Sign) shop[1].getState();
        Container container = service.findConnectedContainer(sign);
        assertThat(container).isNotNull();
        assertThat(container.getBlock()).isEqualTo(shop[0]);
    }

    @Test
    void findConnectedContainer_fromBlock() {
        Block[] shop = shopWithWallSign();
        Container container = service.findConnectedContainer(shop[1]);
        assertThat(container).isNotNull();
        assertThat(container.getBlock()).isEqualTo(shop[0]);
    }

    @Test
    void findConnectedContainer_fromStandingSign_searchesAllFaces() {
        // A standing sign (no WallSign facing) above a chest: found via the SHOP_FACES loop.
        int x = base += 6;
        Block chest = world.getBlockAt(x, 64, 0);
        chest.setType(Material.CHEST);
        Block signBlock = world.getBlockAt(x, 65, 0);
        signBlock.setType(Material.OAK_SIGN); // standing sign -> signFace null
        Sign sign = (Sign) signBlock.getState();

        Container container = service.findConnectedContainer(sign);
        assertThat(container).isNotNull();
        assertThat(container.getBlock()).isEqualTo(chest);
    }

    @Test
    void findConnectedContainer_nullWhenNoContainerNearby() {
        int x = base += 6;
        Block signBlock = world.getBlockAt(x, 64, 0);
        signBlock.setType(Material.OAK_SIGN);
        Sign sign = (Sign) signBlock.getState();
        assertThat(service.findConnectedContainer(sign)).isNull();
    }

    @Test
    void findConnectedContainer_nullWhenSignBlockNotLoaded() {
        Sign sign = mock(Sign.class);
        Block block = mock(Block.class);
        World unloaded = mock(World.class);
        when(sign.getBlock()).thenReturn(block);
        when(block.getWorld()).thenReturn(unloaded);
        when(block.getX()).thenReturn(0);
        when(block.getZ()).thenReturn(0);
        when(unloaded.isChunkLoaded(0, 0)).thenReturn(false);
        assertThat(service.findConnectedContainer(sign)).isNull();
    }

    // ---- getConnectedSign / findAnyNearbyShopSign -------------------------------

    @Test
    void getConnectedSign_findsAttachedShopSign() {
        Block[] shop = shopWithWallSign();
        Sign sign = service.getConnectedSign(shop[0]);
        assertThat(sign).isNotNull();
        assertThat(SignService.getOwner(sign)).isEqualTo("Notch");
    }

    @Test
    void getConnectedSign_nullWhenNothingAttached() {
        Block chest = world.getBlockAt(300, 64, 0);
        chest.setType(Material.CHEST);
        assertThat(service.getConnectedSign(chest)).isNull();
    }

    @Test
    void findAnyNearbyShopSign_skipsInvalidSign() {
        int x = base += 6;
        Block chest = world.getBlockAt(x, 64, 0);
        chest.setType(Material.CHEST);
        Block signBlock = world.getBlockAt(x + 1, 64, 0);
        signBlock.setType(Material.OAK_WALL_SIGN);
        WallSign data = (WallSign) signBlock.getBlockData();
        data.setFacing(BlockFace.EAST);
        signBlock.setBlockData(data);
        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, ""); // invalid: no owner
        sign.update();

        assertThat(service.findAnyNearbyShopSign(chest)).isNull();
    }

    // ---- findConnectedShopSigns -------------------------------------------------

    @Test
    void findConnectedShopSigns_fromBlock() {
        Block[] shop = shopWithWallSign();
        List<Sign> found = service.findConnectedShopSigns(shop[0]);
        assertThat(found).hasSize(1);
        assertThat(SignService.getOwner(found.get(0))).isEqualTo("Notch");
    }

    @Test
    void findConnectedShopSigns_fromBlockStateHolder() {
        Block[] shop = shopWithWallSign();
        BlockState chestState = shop[0].getState();
        List<Sign> found = service.findConnectedShopSigns((InventoryHolder) chestState);
        assertThat(found).hasSize(1);
    }

    @Test
    void findConnectedShopSigns_fromDoubleChest() {
        Block[] shop = shopWithWallSign();
        BlockState chestState = shop[0].getState();
        DoubleChest dc = mock(DoubleChest.class);
        when(dc.getLeftSide(false)).thenReturn((InventoryHolder) chestState);
        when(dc.getRightSide(false)).thenReturn((InventoryHolder) chestState);
        List<Sign> found = service.findConnectedShopSigns(dc);
        assertThat(found).isNotEmpty();
    }

    @Test
    void findConnectedShopSigns_doubleChest_emptyWhenSidesNotBlockStates() {
        DoubleChest dc = mock(DoubleChest.class);
        when(dc.getLeftSide(false)).thenReturn(mock(InventoryHolder.class));
        when(dc.getRightSide(false)).thenReturn(mock(InventoryHolder.class));
        assertThat(service.findConnectedShopSigns(dc)).isEmpty();
    }

    @Test
    void findConnectedShopSigns_otherHolder_empty() {
        assertThat(service.findConnectedShopSigns(mock(InventoryHolder.class))).isEmpty();
    }

    // ---- isShopBlock ------------------------------------------------------------

    @Test
    void isShopBlock_block_trueWhenSignAttached_falseOtherwise() {
        Block[] shop = shopWithWallSign();
        assertThat(service.isShopBlock(shop[0])).isTrue();

        Block loneChest = world.getBlockAt(400, 64, 0);
        loneChest.setType(Material.CHEST);
        assertThat(service.isShopBlock(loneChest)).isFalse();

        Block stone = world.getBlockAt(402, 64, 0);
        stone.setType(Material.STONE);
        assertThat(service.isShopBlock(stone)).isFalse();
    }

    @Test
    void findConnectedContainer_fromNonSignBlock_searchesFaces() {
        // A non-WallSign block (the chest itself) -> signFace null -> SHOP_FACES loop (SELF hits it).
        Block chest = world.getBlockAt(base += 6, 64, 0);
        chest.setType(Material.CHEST);
        Container container = service.findConnectedContainer(chest);
        assertThat(container).isNotNull();
        assertThat(container.getBlock()).isEqualTo(chest);
    }

    @Test
    void findConnectedContainer_block_nullWhenNotLoaded() {
        Block block = mock(Block.class);
        World unloaded = mock(World.class);
        when(block.getWorld()).thenReturn(unloaded);
        when(block.getX()).thenReturn(0);
        when(block.getZ()).thenReturn(0);
        when(unloaded.isChunkLoaded(0, 0)).thenReturn(false);
        assertThat(service.findConnectedContainer(block)).isNull();
    }

    @Test
    void findConnectedShopSigns_holder_emptyForNonShopChest() {
        Block loneChest = world.getBlockAt(base += 6, 64, 0);
        loneChest.setType(Material.CHEST);
        assertThat(service.findConnectedShopSigns((InventoryHolder) loneChest.getState())).isEmpty();
    }

    @Test
    void findAnyNearbyShopSign_continuesPastUnloadedFace() {
        // Chest at a chunk boundary: its EAST neighbour is in an unloaded chunk (isLoaded==false,
        // continue), while a valid wall sign sits on a loaded face.
        int x = 40 * 16 + 15; // chunk 40 (loaded); EAST -> chunk 41 (unloaded)
        Block chest = world.getBlockAt(x, 64, 0);
        chest.setType(Material.CHEST);
        Block signBlock = world.getBlockAt(x - 1, 64, 0); // WEST, loaded
        signBlock.setType(Material.OAK_WALL_SIGN);
        WallSign data = (WallSign) signBlock.getBlockData();
        data.setFacing(BlockFace.WEST); // opposite (EAST) points at the chest
        signBlock.setBlockData(data);
        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, "Notch");
        sign.setLine(SignService.QUANTITY_LINE, "64");
        sign.setLine(SignService.PRICE_LINE, "B 5");
        sign.setLine(SignService.ITEM_LINE, "Diamond");
        sign.update();

        assertThat(service.findAnyNearbyShopSign(chest)).isNotNull();
    }

    @Test
    void findConnectedShopSigns_skipsSignWhoseContainerIsAnotherBlock() {
        // A valid wall sign that resolves to a DIFFERENT chest than the one we search from.
        int x = base += 6;
        Block chestA = world.getBlockAt(x, 64, 0);
        chestA.setType(Material.CHEST);
        Block chestB = world.getBlockAt(x + 2, 64, 0);
        chestB.setType(Material.CHEST);
        // Sign between them, attached to chestB (facing EAST -> opposite WEST is x+1... arrange so it
        // resolves to chestB, not chestA). Place sign at x+3 facing EAST so opposite (WEST=x+2)=chestB.
        Block signBlock = world.getBlockAt(x + 3, 64, 0);
        signBlock.setType(Material.OAK_WALL_SIGN);
        WallSign data = (WallSign) signBlock.getBlockData();
        data.setFacing(BlockFace.EAST);
        signBlock.setBlockData(data);
        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, "Notch");
        sign.setLine(SignService.QUANTITY_LINE, "64");
        sign.setLine(SignService.PRICE_LINE, "B 5");
        sign.setLine(SignService.ITEM_LINE, "Diamond");
        sign.update();

        // Searching from chestA: the only nearby sign resolves to chestB, so it's skipped.
        assertThat(service.findConnectedShopSigns(chestA)).isEmpty();
    }

    @Test
    void findConnectedShopSigns_skipsInvalidSignOnChest() {
        int x = base += 6;
        Block chest = world.getBlockAt(x, 64, 0);
        chest.setType(Material.CHEST);
        Block signBlock = world.getBlockAt(x + 1, 64, 0);
        signBlock.setType(Material.OAK_WALL_SIGN);
        WallSign data = (WallSign) signBlock.getBlockData();
        data.setFacing(BlockFace.EAST);
        signBlock.setBlockData(data);
        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, "Notch");
        sign.setLine(SignService.QUANTITY_LINE, "64");
        sign.setLine(SignService.PRICE_LINE, "nope"); // invalid price -> isValid false
        sign.setLine(SignService.ITEM_LINE, "Diamond");
        sign.update();

        assertThat(service.findConnectedShopSigns(chest)).isEmpty();
    }

    @Test
    void isShopBlock_holder_blockStateAndDoubleChestAndOther() {
        Block[] shop = shopWithWallSign();
        BlockState chestState = shop[0].getState();
        assertThat(service.isShopBlock((InventoryHolder) chestState)).isTrue();

        DoubleChest dc = mock(DoubleChest.class);
        when(dc.getLeftSide(false)).thenReturn((InventoryHolder) chestState);
        when(dc.getRightSide(false)).thenReturn((InventoryHolder) chestState);
        assertThat(service.isShopBlock(dc)).isTrue();

        assertThat(service.isShopBlock(mock(InventoryHolder.class))).isFalse();
    }

    // ---- targeted geometry: mocked WallSign facings + double-chest neighbour -----
    // MockBukkit's SignState/WallSign block-data reports a default NORTH facing regardless of the
    // real facing, so the signFace!=null container path and the wall-sign facing filter can't be
    // reached with real objects. These drive those branches with mocked block-data (the pattern
    // used by utils/BlockUtilExtraTest).

    private Block unloadedBlock() {
        Block b = mock(Block.class);
        World w = mock(World.class);
        lenient().when(b.getWorld()).thenReturn(w);
        lenient().when(b.getX()).thenReturn(0);
        lenient().when(b.getZ()).thenReturn(0);
        lenient().when(w.isChunkLoaded(0, 0)).thenReturn(false);
        return b;
    }

    private Block loadedMock() {
        Block b = mock(Block.class);
        World w = mock(World.class);
        lenient().when(b.getWorld()).thenReturn(w);
        lenient().when(b.getX()).thenReturn(0);
        lenient().when(b.getZ()).thenReturn(0);
        lenient().when(w.isChunkLoaded(0, 0)).thenReturn(true);
        return b;
    }

    /** A {@link Sign} at a real world block whose block-data is a mocked wall sign with the given facing. */
    private Sign mockWallSign(Block signBlock, BlockFace facing) {
        Sign s = mock(Sign.class);
        WallSign wd = mock(WallSign.class);
        lenient().when(wd.getFacing()).thenReturn(facing);
        lenient().when(s.getBlockData()).thenReturn(wd);
        lenient().when(s.getBlock()).thenReturn(signBlock);
        lenient().when(s.getLocation()).thenReturn(signBlock.getLocation());
        return s;
    }

    @Test
    void findConnectedContainer_returnsDirectlyViaSignFace() {
        // signFace != null and the block it points at is a shop container -> returned immediately.
        int x = base += 6;
        Block chest = world.getBlockAt(x, 64, 0);
        chest.setType(Material.CHEST);
        Block signBlock = world.getBlockAt(x + 1, 64, 0); // EAST of the chest, opposite (WEST) -> chest
        Sign sign = mockWallSign(signBlock, BlockFace.EAST);
        Container c = service.findConnectedContainer(sign);
        assertThat(c).isNotNull();
        assertThat(c.getBlock()).isEqualTo(chest);
    }

    @Test
    void findConnectedContainer_signFaceEmpty_fallsToShopFacesLoop() {
        // signFace points at an empty block (98 false); the loop then skips the signFace direction
        // (104 false) and finds the container on another face (104 true).
        int x = base += 6;
        Block chest = world.getBlockAt(x, 64, 0);
        chest.setType(Material.CHEST);
        Block signBlock = world.getBlockAt(x + 1, 64, 0);
        Sign sign = mockWallSign(signBlock, BlockFace.WEST); // opposite (EAST = x+2) is empty
        Container c = service.findConnectedContainer(sign);
        assertThat(c).isNotNull();
        assertThat(c.getBlock()).isEqualTo(chest); // found at WEST via the loop
    }

    @Test
    void getConnectedSign_viaDoubleChestNeighbour() {
        // getConnectedSign: the origin block has no directly-attached sign, but its double-chest
        // neighbour does (59/60). The neighbour is a real chest+sign; the origin is a chest mock
        // whose block-data is a LEFT half (MockBukkit doesn't form double chests from placement).
        int bx = base += 6;
        Block neighbour = world.getBlockAt(bx, 64, 0);
        neighbour.setType(Material.CHEST);
        Block signBlock = world.getBlockAt(bx + 1, 64, 0);
        signBlock.setType(Material.OAK_WALL_SIGN);
        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, "Notch");
        sign.setLine(SignService.QUANTITY_LINE, "64");
        sign.setLine(SignService.PRICE_LINE, "B 5");
        sign.setLine(SignService.ITEM_LINE, "Diamond");
        sign.update();

        Block origin = mock(Block.class);
        World w = mock(World.class);
        lenient().when(origin.getWorld()).thenReturn(w);
        lenient().when(origin.getX()).thenReturn(0);
        lenient().when(origin.getZ()).thenReturn(0);
        lenient().when(w.isChunkLoaded(0, 0)).thenReturn(true);
        when(origin.getType()).thenReturn(Material.CHEST);
        Chest cd = mock(Chest.class);
        lenient().when(cd.getType()).thenReturn(Chest.Type.LEFT);
        lenient().when(cd.getFacing()).thenReturn(BlockFace.WEST); // LEFT + WEST -> neighbour NORTH
        when(origin.getBlockData()).thenReturn(cd);
        Block air1 = unloadedBlock();
        when(origin.getRelative(any(BlockFace.class))).thenReturn(air1);
        when(origin.getRelative(BlockFace.NORTH)).thenReturn(neighbour);

        Sign found = service.getConnectedSign(origin);
        assertThat(found).isNotNull();
        assertThat(SignService.getOwner(found)).isEqualTo("Notch");
    }

    @Test
    void findAnyNearbyShopSign_skipsWallSignBelongingToAnotherContainer() {
        // A wall sign whose facing != the search face and whose backing block is a shop container
        // is skipped (185/186/187) — it belongs to a different chest.
        Block origin = mock(Block.class);
        Block wallFace = loadedMock();
        WallSign wd = mock(WallSign.class);
        when(wd.getFacing()).thenReturn(BlockFace.EAST); // != NORTH (the search face)
        when(wallFace.getBlockData()).thenReturn(wd);
        Block behind = loadedMock();
        when(behind.getType()).thenReturn(Material.CHEST); // couldBeShopContainer -> true
        when(wallFace.getRelative(BlockFace.WEST)).thenReturn(behind); // EAST.getOppositeFace()

        Block air2 = unloadedBlock();
        when(origin.getRelative(any(BlockFace.class))).thenReturn(air2);
        when(origin.getRelative(BlockFace.NORTH)).thenReturn(wallFace);

        assertThat(service.findAnyNearbyShopSign(origin)).isNull();
    }

    @Test
    void findAnyNearbyShopSign_wallSignFacingSearchDirection_isValidated() {
        // A wall sign whose facing == the search face is attached to this block: the skip guard's
        // getFacing()!=bf is false, so it is validated and returned (185 false arc).
        Block origin = mock(Block.class);
        Block wallFace = loadedMock();
        WallSign wd = mock(WallSign.class);
        when(wd.getFacing()).thenReturn(BlockFace.NORTH); // == the search face below
        when(wallFace.getBlockData()).thenReturn(wd);
        Sign sign = mock(Sign.class);
        when(sign.getLines()).thenReturn(new String[]{"Notch", "64", "B 5", "Diamond"});
        when(wallFace.getState(false)).thenReturn(sign);
        Block air = unloadedBlock();
        when(origin.getRelative(any(BlockFace.class))).thenReturn(air);
        when(origin.getRelative(BlockFace.NORTH)).thenReturn(wallFace);
        assertThat(service.findAnyNearbyShopSign(origin)).isSameAs(sign);
    }

    @Test
    void findAnyNearbyShopSign_returnsStandingSignOnFace() {
        // A standing sign (data is a Sign but not a WallSign) on a face -> 189 false -> validated.
        int x = base += 6;
        Block chest = world.getBlockAt(x, 64, 0);
        chest.setType(Material.CHEST);
        Block signBlock = world.getBlockAt(x, 65, 0); // UP
        signBlock.setType(Material.OAK_SIGN);
        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, "Notch");
        sign.setLine(SignService.QUANTITY_LINE, "64");
        sign.setLine(SignService.PRICE_LINE, "B 5");
        sign.setLine(SignService.ITEM_LINE, "Diamond");
        sign.update();

        assertThat(service.findAnyNearbyShopSign(chest)).isNotNull();
    }

    @Test
    void couldBeShopContainer_falseForUnloadedBlock() {
        assertThat(service.couldBeShopContainer(unloadedBlock())).isFalse();
    }

    @Test
    void couldBeShopContainer_holder_falseBranches() {
        Block stone = world.getBlockAt(base += 6, 64, 0);
        stone.setType(Material.STONE);
        Container stoneContainer = mock(Container.class);
        when(stoneContainer.getBlock()).thenReturn(stone);
        // Container holder whose block isn't a shop container -> 209 second operand false.
        assertThat(service.couldBeShopContainer((InventoryHolder) stoneContainer)).isFalse();

        DoubleChest dc = mock(DoubleChest.class);
        when(dc.getLeftSide(false)).thenReturn((InventoryHolder) stoneContainer);
        // DoubleChest whose left side isn't a shop container -> 210 false.
        assertThat(service.couldBeShopContainer(dc)).isFalse();
    }

    @Test
    void findConnectedShopSigns_doubleChest_onlyRightSideNotBlockState() {
        Block[] shop = shopWithWallSign();
        BlockState chestState = shop[0].getState();
        DoubleChest dc = mock(DoubleChest.class);
        when(dc.getLeftSide(false)).thenReturn((InventoryHolder) chestState); // BlockState
        when(dc.getRightSide(false)).thenReturn(mock(InventoryHolder.class)); // not a BlockState
        assertThat(service.findConnectedShopSigns(dc)).isEmpty(); // 122 right operand true
    }

    @Test
    void findConnectedShopSigns_doubleChest_bothSidesNotShopBlocks() {
        Block c1 = world.getBlockAt(base += 6, 64, 0);
        c1.setType(Material.CHEST);
        Block c2 = world.getBlockAt(base += 6, 64, 0);
        c2.setType(Material.CHEST);
        DoubleChest dc = mock(DoubleChest.class);
        when(dc.getLeftSide(false)).thenReturn((InventoryHolder) c1.getState());
        when(dc.getRightSide(false)).thenReturn((InventoryHolder) c2.getState());
        // Both sides are BlockStates but neither is a shop block -> 129 false, 133 false.
        assertThat(service.findConnectedShopSigns(dc)).isEmpty();
    }

    @Test
    void findConnectedShopSigns_skipsSignWithNoResolvableContainer() {
        // A sign on a non-container block with nothing else nearby -> findConnectedContainer null
        // -> 163 (signContainer == null) true -> continue (164).
        int x = base += 6;
        Block stone = world.getBlockAt(x, 64, 0);
        stone.setType(Material.STONE);
        Block signBlock = world.getBlockAt(x + 1, 64, 0);
        signBlock.setType(Material.OAK_WALL_SIGN);
        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, "Notch");
        sign.setLine(SignService.QUANTITY_LINE, "64");
        sign.setLine(SignService.PRICE_LINE, "B 5");
        sign.setLine(SignService.ITEM_LINE, "Diamond");
        sign.update();
        assertThat(service.findConnectedShopSigns(stone)).isEmpty();
    }

    @Test
    void findConnectedShopSigns_skipsSignResolvingToDifferentContainer() {
        // A sign on a non-container block that resolves to a chest that isn't the search block
        // -> 163 second operand (!chestBlock.equals(signContainer.getBlock())) true -> continue.
        int x = base += 6;
        Block stone = world.getBlockAt(x, 64, 0);
        stone.setType(Material.STONE);
        Block signBlock = world.getBlockAt(x + 1, 64, 0);
        signBlock.setType(Material.OAK_WALL_SIGN);
        Sign sign = (Sign) signBlock.getState();
        sign.setLine(SignService.NAME_LINE, "Notch");
        sign.setLine(SignService.QUANTITY_LINE, "64");
        sign.setLine(SignService.PRICE_LINE, "B 5");
        sign.setLine(SignService.ITEM_LINE, "Diamond");
        sign.update();
        Block chest = world.getBlockAt(x + 2, 64, 0); // the sign resolves to this, not the stone
        chest.setType(Material.CHEST);
        assertThat(service.findConnectedShopSigns(stone)).isEmpty();
    }

    @Test
    void isShopBlock_doubleChest_leftFalseRightTrue_andBothFalse() {
        Block[] shop = shopWithWallSign();
        BlockState shopState = shop[0].getState();
        Block lone = world.getBlockAt(base += 6, 64, 0);
        lone.setType(Material.CHEST);
        BlockState loneState = lone.getState();

        // left not a shop block -> 225 false -> 226 right evaluated -> true.
        DoubleChest dc1 = mock(DoubleChest.class);
        when(dc1.getLeftSide(false)).thenReturn((InventoryHolder) loneState);
        when(dc1.getRightSide(false)).thenReturn((InventoryHolder) shopState);
        assertThat(service.isShopBlock(dc1)).isTrue();

        // neither side a shop block -> 225 false, 226 false.
        DoubleChest dc2 = mock(DoubleChest.class);
        when(dc2.getLeftSide(false)).thenReturn((InventoryHolder) loneState);
        when(dc2.getRightSide(false)).thenReturn((InventoryHolder) loneState);
        assertThat(service.isShopBlock(dc2)).isFalse();
    }
}
