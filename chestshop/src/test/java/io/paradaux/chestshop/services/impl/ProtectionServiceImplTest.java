package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.BuildPermission;
import io.paradaux.chestshop.model.ProtectionCheck;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.support.TestConfigs;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shop/chest protection gating. Block/sign geometry is driven through Mockito stubs of the Bukkit
 * objects the {@code BlockUtil} statics read (MockBukkit can't model wall-sign attachment
 * consistently); the ChestShop services are the mocked boundary.
 */
class ProtectionServiceImplTest {

    private AccountService accounts;
    private SignService signService;
    private ShopBlockService shopBlockService;
    private ChestShopConfiguration config;
    private ProtectionServiceImpl protection;
    private World world;
    private Player player;

    @BeforeEach
    void wire() {
        accounts = mock(AccountService.class);
        signService = mock(SignService.class);
        shopBlockService = mock(ShopBlockService.class);
        config = TestConfigs.defaults();
        protection = new ProtectionServiceImpl(accounts, config, signService, shopBlockService);
        world = mock(World.class);
        lenient().when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        player = mock(Player.class);
    }

    /** A mock block whose chunk is loaded, carrying the given block data. */
    private Block block(BlockData data) {
        Block b = mock(Block.class);
        lenient().when(b.getWorld()).thenReturn(world);
        lenient().when(b.getX()).thenReturn(0);
        lenient().when(b.getZ()).thenReturn(0);
        lenient().when(b.getBlockData()).thenReturn(data);
        return b;
    }

    private Block signBlock() {
        return block(mock(WallSign.class)); // instanceof WallSign -> BlockUtil.isSign == true
    }

    private Block plainBlock() {
        return block(mock(BlockData.class)); // neither Sign nor WallSign
    }

    /** Attach a live Sign state (read via getState(block,false)) to a sign block. */
    private Sign stateOf(Block signBlock, boolean valid) {
        Sign sign = mock(Sign.class);
        when(signBlock.getState(false)).thenReturn(sign);
        lenient().when(signService.isValid(sign)).thenReturn(valid);
        return sign;
    }

    // ── hasMemberAccess via canAccess ─────────────────────────────────────────

    @Test
    void access_plainBlock_isAlwaysAllowed() {
        Block b = plainBlock();
        when(shopBlockService.couldBeShopContainer(b)).thenReturn(false);
        assertThat(protection.canAccess(player, b)).isTrue();
    }

    @Test
    void access_invalidSign_isAllowed() {
        Block b = signBlock();
        stateOf(b, false); // sign present but invalid -> access granted
        lenient().when(shopBlockService.couldBeShopContainer(b)).thenReturn(false);
        assertThat(protection.canAccess(player, b)).isTrue();
    }

    @Test
    void access_validSign_nonMember_isDenied() {
        Block b = signBlock();
        Sign sign = stateOf(b, true);
        when(accounts.hasPermission(eq(player), any(), eq(sign))).thenReturn(false);
        lenient().when(shopBlockService.couldBeShopContainer(b)).thenReturn(false);
        assertThat(protection.canAccess(player, b)).isFalse();
    }

    @Test
    void access_validSign_member_notAlsoContainer_isAllowed() {
        Block b = signBlock();
        Sign sign = stateOf(b, true);
        when(accounts.hasPermission(eq(player), any(), eq(sign))).thenReturn(true);
        when(shopBlockService.couldBeShopContainer(b)).thenReturn(false);
        assertThat(protection.canAccess(player, b)).isTrue();
    }

    @Test
    void access_containerWithConnectedSign_nonMember_isDenied() {
        Block b = plainBlock();
        when(shopBlockService.couldBeShopContainer(b)).thenReturn(true);
        Sign connected = mock(Sign.class);
        when(shopBlockService.getConnectedSign(b)).thenReturn(connected);
        when(accounts.hasPermission(eq(player), any(), eq(connected))).thenReturn(false);
        assertThat(protection.canAccess(player, b)).isFalse();
    }

    @Test
    void access_containerWithoutConnectedSign_isAllowed() {
        Block b = plainBlock();
        when(shopBlockService.couldBeShopContainer(b)).thenReturn(true);
        when(shopBlockService.getConnectedSign(b)).thenReturn(null);
        assertThat(protection.canAccess(player, b)).isTrue();
    }

    @Test
    void access_containerWithConnectedSign_member_isAllowed() {
        Block b = plainBlock();
        when(shopBlockService.couldBeShopContainer(b)).thenReturn(true);
        Sign connected = mock(Sign.class);
        when(shopBlockService.getConnectedSign(b)).thenReturn(connected);
        when(accounts.hasPermission(eq(player), any(), eq(connected))).thenReturn(true);
        assertThat(protection.canAccess(player, b)).isTrue();
    }

    @Test
    void access_ignoringBuiltInProtection_skipsVanillaCheck() {
        Block b = signBlock();
        stateOf(b, true);
        // Even a non-member is allowed because the vanilla check is skipped.
        lenient().when(accounts.hasPermission(any(), any(), any())).thenReturn(false);
        assertThat(protection.canAccess(b, player, true)).isTrue();
    }

    // ── worldguard provider hook ──────────────────────────────────────────────

    @Test
    void access_worldGuardProviderCanDeny() {
        Block b = plainBlock();
        when(shopBlockService.couldBeShopContainer(b)).thenReturn(false);
        protection.setWorldGuardProtection(check -> check.setResult(Event.Result.DENY));
        assertThat(protection.canAccess(player, b)).isFalse();
    }

    @Test
    void view_worldGuardProviderNoOp_isAllowed() {
        Block b = plainBlock();
        when(shopBlockService.couldBeShopContainer(b)).thenReturn(false);
        protection.setWorldGuardProtection(check -> { /* no-op provider */ });
        assertThat(protection.canView(b, player, false)).isTrue();
    }

    @Test
    void vanillaCheck_shortCircuitsWhenAlreadyDenied() throws Exception {
        // Defensive guard: if an earlier handler already set DENY, the vanilla check is a no-op.
        ProtectionCheck event = new ProtectionCheck(plainBlock(), player);
        event.setResult(Event.Result.DENY);
        Method m = ProtectionServiceImpl.class.getDeclaredMethod("vanillaCheck", ProtectionCheck.class);
        m.setAccessible(true);
        m.invoke(protection, event);
        assertThat(event.getResult()).isEqualTo(Event.Result.DENY);
    }

    // ── canBuild ──────────────────────────────────────────────────────────────

    private Location loc() {
        return new Location(world, 0, 0, 0);
    }

    @Test
    void build_noProviders_isAllowed() {
        assertThat(protection.canBuild(player, loc(), loc())).isTrue();
    }

    @Test
    void build_worldGuardDenies_skipsGriefPrevention() {
        protection.setWorldGuardBuilding(BuildPermission::disallow);
        Consumer<BuildPermission> gp = mock(Consumer.class); // must not be called once already denied
        protection.setGriefPreventionBuilding(gp);
        assertThat(protection.canBuild(player, loc(), loc())).isFalse();
        org.mockito.Mockito.verifyNoInteractions(gp);
    }

    @Test
    void build_worldGuardAllows_griefPreventionDenies() {
        protection.setWorldGuardBuilding(bp -> { /* leaves it allowed */ });
        protection.setGriefPreventionBuilding(BuildPermission::disallow);
        assertThat(protection.canBuild(player, loc(), loc())).isFalse();
    }

    @Test
    void build_onlyGriefPrevention_allows() {
        protection.setGriefPreventionBuilding(bp -> { /* allowed */ });
        assertThat(protection.canBuild(player, loc(), loc())).isTrue();
    }

    // ── canPlaceSign / canBePlaced / anotherShopFound ─────────────────────────

    /** A placing sign whose attached (base) block is {@code base} and whose own block is {@code own}. */
    private Sign placingSign(Block own, Block base) {
        Sign sign = mock(Sign.class);
        WallSign data = mock(WallSign.class);
        lenient().when(data.getFacing()).thenReturn(BlockFace.NORTH); // opposite SOUTH
        lenient().when(sign.getBlockData()).thenReturn(data);
        lenient().when(sign.getBlock()).thenReturn(own);
        lenient().when(own.getRelative(BlockFace.SOUTH)).thenReturn(base);
        return sign;
    }

    private void noContainersAround(Block signOwn) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.EAST,
                BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH}) {
            Block n = plainBlock();
            lenient().when(signOwn.getRelative(f)).thenReturn(n);
            lenient().when(shopBlockService.couldBeShopContainer(n)).thenReturn(false);
        }
    }

    /** An "other" wall sign at a neighbour, whose validity/attachment/owner are configurable. */
    private void otherSignAt(Block base, BlockFace face, boolean valid, Block attachedTo, boolean owner) {
        Block osb = signBlock();
        when(base.getRelative(face)).thenReturn(osb);
        Sign os = stateOf(osb, valid);
        WallSign data = mock(WallSign.class);
        lenient().when(data.getFacing()).thenReturn(BlockFace.NORTH);
        lenient().when(os.getBlockData()).thenReturn(data);
        lenient().when(os.getBlock()).thenReturn(osb);
        lenient().when(osb.getRelative(BlockFace.SOUTH)).thenReturn(attachedTo);
        lenient().when(accounts.isOwner(player, os)).thenReturn(owner);
    }

    @Test
    void placeSign_anotherOwnersShopOnBase_isRejected() {
        Block own = plainBlock();
        Block base = plainBlock();
        Sign sign = placingSign(own, base);
        // base's UP neighbour is another player's valid, base-attached shop sign -> reject.
        otherSignAt(base, BlockFace.UP, true, base, false);
        // remaining faces are inert
        Block eastNonSign = plainBlock();
        Block northNonSign = plainBlock();
        Block southNonSign = plainBlock();
        when(base.getRelative(BlockFace.EAST)).thenReturn(eastNonSign);
        when(base.getRelative(BlockFace.WEST)).thenReturn(own);   // equals the placing sign block -> skipped
        when(base.getRelative(BlockFace.NORTH)).thenReturn(northNonSign);
        when(base.getRelative(BlockFace.SOUTH)).thenReturn(southNonSign);

        assertThat(protection.canPlaceSign(player, sign)).isFalse();
    }

    @Test
    void placeSign_noConflictingShops_walksEveryContinueBranch_thenChecksPlacement() {
        Block own = plainBlock();
        Block base = plainBlock();
        noContainersAround(own);          // canBePlaced: nothing around 'own' is a container...
        Sign sign = placingSign(own, base); // ...re-stubs own.getRelative(SOUTH) -> base (attachment)

        // UP: valid, base-attached, but owned by the player -> continue
        otherSignAt(base, BlockFace.UP, true, base, true);
        // EAST: valid sign but attached elsewhere -> continue
        otherSignAt(base, BlockFace.EAST, true, plainBlock(), false);
        // WEST: sign present but invalid -> continue
        otherSignAt(base, BlockFace.WEST, false, base, false);
        // NORTH: equals the placing sign's own block -> continue
        when(base.getRelative(BlockFace.NORTH)).thenReturn(own);
        // SOUTH: not a sign -> continue
        Block southNonSign = plainBlock();
        when(base.getRelative(BlockFace.SOUTH)).thenReturn(southNonSign);

        assertThat(protection.canPlaceSign(player, sign)).isTrue();
    }

    @Test
    void placeSign_multipleShopsAllowed_skipsConflictCheck_butPlacementDenied() {
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "allowMultipleShopsAtOneBlock", true);
        ProtectionServiceImpl svc = new ProtectionServiceImpl(accounts, cfg, signService, shopBlockService);

        Block own = plainBlock();
        Block base = plainBlock();
        Sign sign = placingSign(own, base);

        // canBePlaced: one neighbouring container the player cannot access -> placement denied.
        Block container = plainBlock();
        when(own.getRelative(BlockFace.UP)).thenReturn(container);
        when(shopBlockService.couldBeShopContainer(container)).thenReturn(true);
        Sign connected = mock(Sign.class);
        when(shopBlockService.getConnectedSign(container)).thenReturn(connected);
        when(accounts.hasPermission(eq(player), any(), eq(connected))).thenReturn(false);
        for (BlockFace f : new BlockFace[]{BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST,
                BlockFace.NORTH, BlockFace.SOUTH}) {
            Block n = plainBlock();
            when(own.getRelative(f)).thenReturn(n);
            lenient().when(shopBlockService.couldBeShopContainer(n)).thenReturn(false);
        }

        assertThat(svc.canPlaceSign(player, sign)).isFalse();
    }

    @Test
    void placeSign_accessibleContainerNeighbour_isAllowed() {
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "allowMultipleShopsAtOneBlock", true);
        ProtectionServiceImpl svc = new ProtectionServiceImpl(accounts, cfg, signService, shopBlockService);

        Block own = plainBlock();
        Block base = plainBlock();
        Sign sign = placingSign(own, base);

        // A container neighbour the player CAN access (no connected sign) -> loop continues -> allowed.
        Block container = plainBlock();
        when(own.getRelative(BlockFace.UP)).thenReturn(container);
        when(shopBlockService.couldBeShopContainer(container)).thenReturn(true);
        when(shopBlockService.getConnectedSign(container)).thenReturn(null);
        for (BlockFace f : new BlockFace[]{BlockFace.DOWN, BlockFace.EAST, BlockFace.WEST,
                BlockFace.NORTH, BlockFace.SOUTH}) {
            Block n = plainBlock();
            when(own.getRelative(f)).thenReturn(n);
            lenient().when(shopBlockService.couldBeShopContainer(n)).thenReturn(false);
        }

        assertThat(svc.canPlaceSign(player, sign)).isTrue();
    }
}
