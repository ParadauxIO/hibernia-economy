package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.utils.Permissions;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Full unit coverage of {@link SignBreakServiceImpl}: the physics-driven removal
 * ({@code handlePhysicsBreak}), the multi-sign protection decision ({@code canBlockBeBroken} and its
 * private {@code getAttachedSigns}/{@code canDestroyShop} helpers) and the shop-removal reaction
 * ({@code sendShopDestroyed}). Static collaborators — {@link BlockUtil}, {@link SignService#getOwner}
 * and {@link ChestShop#getPlugin()} — are stubbed via {@code mockStatic}; every mutable block/sign
 * is a Mockito mock so no live server is required.
 */
class SignBreakServiceImplTest {

    private AccountService accounts;
    private ShopService shops;
    private ChestShopConfiguration config;
    private SignService signService;
    private ShopBlockService shopBlockService;
    private SignBreakServiceImpl service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountService.class);
        shops = mock(ShopService.class);
        config = mock(ChestShopConfiguration.class);
        signService = mock(SignService.class);
        shopBlockService = mock(ShopBlockService.class);
        service = new SignBreakServiceImpl(accounts, shops, config, signService, shopBlockService);
    }

    // ---------------------------------------------------------------------- handlePhysicsBreak

    @Test
    void handlePhysicsBreak_notASign_returnsImmediately() {
        Block block = mock(Block.class);
        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(false);

            service.handlePhysicsBreak(block);

            bu.verify(() -> BlockUtil.getAttachedBlock(any()), never());
        }
        verifyNoInteractions(shops, shopBlockService, signService);
    }

    @Test
    void handlePhysicsBreak_signStillAttached_noOp() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        Block attached = mock(Block.class);
        when(attached.getType()).thenReturn(Material.STONE); // not AIR

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(block, false)).thenReturn(sign);
            bu.when(() -> BlockUtil.getAttachedBlock(sign)).thenReturn(attached);

            service.handlePhysicsBreak(block);
        }
        verifyNoInteractions(shops, shopBlockService);
        verify(signService, never()).isValid(any(Sign.class));
    }

    @Test
    void handlePhysicsBreak_attachedAir_butInvalidSign_noOp() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        Block attached = mock(Block.class);
        when(attached.getType()).thenReturn(Material.AIR);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(block, false)).thenReturn(sign);
            bu.when(() -> BlockUtil.getAttachedBlock(sign)).thenReturn(attached);
            when(signService.isValid(sign)).thenReturn(false);

            service.handlePhysicsBreak(block);
        }
        verifyNoInteractions(shops, shopBlockService);
    }

    @Test
    void handlePhysicsBreak_attachedAirValidSign_withMetadata_extractsPlayer() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        Sign liveState = mock(Sign.class);
        Block attached = mock(Block.class);
        Block signBlock = mock(Block.class);
        Container container = mock(Container.class);
        Player player = mock(Player.class);
        MetadataValue mv = mock(MetadataValue.class);
        when(attached.getType()).thenReturn(Material.AIR);
        when(block.getState()).thenReturn(liveState);
        when(block.hasMetadata(SignBreakServiceImpl.METADATA_NAME)).thenReturn(true);
        when(block.getMetadata(SignBreakServiceImpl.METADATA_NAME)).thenReturn(List.of(mv));
        when(mv.value()).thenReturn(player);
        when(liveState.getBlock()).thenReturn(signBlock);
        when(shopBlockService.findConnectedContainer(signBlock)).thenReturn(container);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(block, false)).thenReturn(sign);
            bu.when(() -> BlockUtil.getAttachedBlock(sign)).thenReturn(attached);
            when(signService.isValid(sign)).thenReturn(true);

            service.handlePhysicsBreak(block);
        }

        ArgumentCaptor<DestroyedShop> captor = ArgumentCaptor.forClass(DestroyedShop.class);
        verify(shops).onDestroyed(captor.capture());
        assertThat(captor.getValue().getDestroyer()).isSameAs(player);
        assertThat(captor.getValue().getSign()).isSameAs(liveState);
        assertThat(captor.getValue().getContainer()).isSameAs(container);
    }

    @Test
    void handlePhysicsBreak_attachedAirValidSign_withoutMetadata_nullPlayer() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        Sign liveState = mock(Sign.class);
        Block attached = mock(Block.class);
        Block signBlock = mock(Block.class);
        when(attached.getType()).thenReturn(Material.AIR);
        when(block.getState()).thenReturn(liveState);
        when(block.hasMetadata(SignBreakServiceImpl.METADATA_NAME)).thenReturn(false);
        when(liveState.getBlock()).thenReturn(signBlock);
        when(shopBlockService.findConnectedContainer(signBlock)).thenReturn(null);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(block, false)).thenReturn(sign);
            bu.when(() -> BlockUtil.getAttachedBlock(sign)).thenReturn(attached);
            when(signService.isValid(sign)).thenReturn(true);

            service.handlePhysicsBreak(block);
        }

        ArgumentCaptor<DestroyedShop> captor = ArgumentCaptor.forClass(DestroyedShop.class);
        verify(shops).onDestroyed(captor.capture());
        assertThat(captor.getValue().getDestroyer()).isNull();
        assertThat(captor.getValue().getContainer()).isNull();
        verify(block, never()).getMetadata(any());
    }

    // ---------------------------------------------------------------------- sendShopDestroyed

    @Test
    void sendShopDestroyed_resolvesContainerAndFiresReaction() {
        Sign sign = mock(Sign.class);
        Block signBlock = mock(Block.class);
        Container container = mock(Container.class);
        Player player = mock(Player.class);
        when(sign.getBlock()).thenReturn(signBlock);
        when(shopBlockService.findConnectedContainer(signBlock)).thenReturn(container);

        service.sendShopDestroyed(sign, player);

        ArgumentCaptor<DestroyedShop> captor = ArgumentCaptor.forClass(DestroyedShop.class);
        verify(shops).onDestroyed(captor.capture());
        assertThat(captor.getValue().getDestroyer()).isSameAs(player);
        assertThat(captor.getValue().getSign()).isSameAs(sign);
        assertThat(captor.getValue().getContainer()).isSameAs(container);
    }

    // ---------------------------------------------------------------------- canBlockBeBroken

    @Test
    void canBlockBeBroken_blockIsSign_ownerCanDestroy_tagsAndReturnsTrue() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        Player breaker = mock(Player.class);
        ChestShop plugin = mock(ChestShop.class);
        when(block.getState()).thenReturn(sign);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class);
             MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            ss.when(() -> SignService.getOwner(sign)).thenReturn("Alice");
            cs.when(ChestShop::getPlugin).thenReturn(plugin);
            when(signService.isValid(sign)).thenReturn(true);
            when(config.isTurnOffSignProtection()).thenReturn(false);
            when(accounts.canUseName(breaker, Permissions.OTHER_NAME_DESTROY, "Alice")).thenReturn(true);

            boolean result = service.canBlockBeBroken(block, breaker);

            assertThat(result).isTrue();
            verify(sign).setMetadata(eq(SignBreakServiceImpl.METADATA_NAME), any());
        }
    }

    @Test
    void canBlockBeBroken_signProtectionDisabled_tagsAndReturnsTrue() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        Player breaker = mock(Player.class);
        ChestShop plugin = mock(ChestShop.class);
        when(block.getState()).thenReturn(sign);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class);
             MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            ss.when(() -> SignService.getOwner(sign)).thenReturn("Bob");
            cs.when(ChestShop::getPlugin).thenReturn(plugin);
            when(signService.isValid(sign)).thenReturn(true);
            when(config.isTurnOffSignProtection()).thenReturn(true); // short-circuits canDestroyShop

            boolean result = service.canBlockBeBroken(block, breaker);

            assertThat(result).isTrue();
            verify(sign).setMetadata(eq(SignBreakServiceImpl.METADATA_NAME), any());
            // canDestroyShop never consulted because protection is off
            verify(accounts, never()).canUseName(any(), any(), any());
        }
    }

    @Test
    void canBlockBeBroken_protectedSign_returnsFalseAndTagsNothing() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        Player breaker = mock(Player.class);
        when(block.getState()).thenReturn(sign);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class);
             MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            ss.when(() -> SignService.getOwner(sign)).thenReturn("Carol");
            when(signService.isValid(sign)).thenReturn(true);
            when(config.isTurnOffSignProtection()).thenReturn(false);
            when(accounts.canUseName(breaker, Permissions.OTHER_NAME_DESTROY, "Carol")).thenReturn(false);

            boolean result = service.canBlockBeBroken(block, breaker);

            assertThat(result).isFalse();
            verify(sign, never()).setMetadata(any(), any());
        }
    }

    @Test
    void canBlockBeBroken_invalidSign_isSkipped_thenReturnsTrue() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        Player breaker = mock(Player.class);
        when(block.getState()).thenReturn(sign);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class);
             MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            when(signService.isValid(sign)).thenReturn(false); // skipped via continue

            boolean result = service.canBlockBeBroken(block, breaker);

            assertThat(result).isTrue(); // nothing protected, nothing broken
            verify(sign, never()).setMetadata(any(), any());
            ss.verify(() -> SignService.getOwner(any(Sign.class)), never());
        }
    }

    @Test
    void canBlockBeBroken_canDestroyShop_nullBreaker_isFalse_protects() {
        Block block = mock(Block.class);
        Sign sign = mock(Sign.class);
        when(block.getState()).thenReturn(sign);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class);
             MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            ss.when(() -> SignService.getOwner(sign)).thenReturn("Dave");
            when(signService.isValid(sign)).thenReturn(true);
            when(config.isTurnOffSignProtection()).thenReturn(false);

            boolean result = service.canBlockBeBroken(block, null); // player == null -> canDestroyShop false

            assertThat(result).isFalse();
            verify(accounts, never()).canUseName(any(), any(), any());
            verify(sign, never()).setMetadata(any(), any());
        }
    }

    @Test
    void canBlockBeBroken_walksConnectionFaces_collectsAttachedSigns() {
        // block is NOT a sign: walk SOUTH,NORTH,EAST,WEST,UP.
        Block block = mock(Block.class);
        Player breaker = mock(Player.class);
        ChestShop plugin = mock(ChestShop.class);

        // SOUTH: a sign attached to `block` (kept)
        Block southRel = mock(Block.class);
        Sign southSign = mock(Sign.class);
        when(southRel.getState()).thenReturn(southSign);
        when(block.getRelative(BlockFace.SOUTH)).thenReturn(southRel);

        // NORTH: not a sign (continue)
        Block northRel = mock(Block.class);
        when(block.getRelative(BlockFace.NORTH)).thenReturn(northRel);

        // EAST: a sign, but attached to some OTHER block (not `block`) -> not collected
        Block eastRel = mock(Block.class);
        Sign eastSign = mock(Sign.class);
        Block otherBlock = mock(Block.class);
        when(eastRel.getState()).thenReturn(eastSign);
        when(block.getRelative(BlockFace.EAST)).thenReturn(eastRel);

        // WEST + UP: not signs
        Block westRel = mock(Block.class);
        Block upRel = mock(Block.class);
        when(block.getRelative(BlockFace.WEST)).thenReturn(westRel);
        when(block.getRelative(BlockFace.UP)).thenReturn(upRel);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class);
             MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(false);
            bu.when(() -> BlockUtil.isSign(southRel)).thenReturn(true);
            bu.when(() -> BlockUtil.isSign(northRel)).thenReturn(false);
            bu.when(() -> BlockUtil.isSign(eastRel)).thenReturn(true);
            bu.when(() -> BlockUtil.isSign(westRel)).thenReturn(false);
            bu.when(() -> BlockUtil.isSign(upRel)).thenReturn(false);
            bu.when(() -> BlockUtil.getAttachedBlock(southSign)).thenReturn(block);   // equals block -> collected
            bu.when(() -> BlockUtil.getAttachedBlock(eastSign)).thenReturn(otherBlock); // not equal -> dropped
            ss.when(() -> SignService.getOwner(southSign)).thenReturn("Eve");
            cs.when(ChestShop::getPlugin).thenReturn(plugin);
            when(signService.isValid(southSign)).thenReturn(true);
            when(config.isTurnOffSignProtection()).thenReturn(true);

            boolean result = service.canBlockBeBroken(block, breaker);

            assertThat(result).isTrue();
            verify(southSign).setMetadata(eq(SignBreakServiceImpl.METADATA_NAME), any());
            verify(eastSign, never()).setMetadata(any(), any());
        }
    }

    @Test
    void canBlockBeBroken_afterProtection_remainingSignsSkippedByCanBeBrokenFlag() {
        // Two attached signs: first protected (flips canBeBroken=false), second then hit the
        // `!canBeBroken` branch of the continue.
        Block block = mock(Block.class);
        Player breaker = mock(Player.class);

        Block southRel = mock(Block.class);
        Sign firstSign = mock(Sign.class);
        when(southRel.getState()).thenReturn(firstSign);
        when(block.getRelative(BlockFace.SOUTH)).thenReturn(southRel);

        Block northRel = mock(Block.class);
        Sign secondSign = mock(Sign.class);
        when(northRel.getState()).thenReturn(secondSign);
        when(block.getRelative(BlockFace.NORTH)).thenReturn(northRel);

        Block eastRel = mock(Block.class);
        Block westRel = mock(Block.class);
        Block upRel = mock(Block.class);
        when(block.getRelative(BlockFace.EAST)).thenReturn(eastRel);
        when(block.getRelative(BlockFace.WEST)).thenReturn(westRel);
        when(block.getRelative(BlockFace.UP)).thenReturn(upRel);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class);
             MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(false);
            bu.when(() -> BlockUtil.isSign(southRel)).thenReturn(true);
            bu.when(() -> BlockUtil.isSign(northRel)).thenReturn(true);
            bu.when(() -> BlockUtil.isSign(eastRel)).thenReturn(false);
            bu.when(() -> BlockUtil.isSign(westRel)).thenReturn(false);
            bu.when(() -> BlockUtil.isSign(upRel)).thenReturn(false);
            bu.when(() -> BlockUtil.getAttachedBlock(firstSign)).thenReturn(block);
            bu.when(() -> BlockUtil.getAttachedBlock(secondSign)).thenReturn(block);
            ss.when(() -> SignService.getOwner(firstSign)).thenReturn("Frank");
            when(signService.isValid(firstSign)).thenReturn(true);
            when(signService.isValid(secondSign)).thenReturn(true);
            when(config.isTurnOffSignProtection()).thenReturn(false);
            when(accounts.canUseName(breaker, Permissions.OTHER_NAME_DESTROY, "Frank")).thenReturn(false);

            boolean result = service.canBlockBeBroken(block, breaker);

            assertThat(result).isFalse();
            // second sign short-circuited by !canBeBroken: never validated for protection decision
            ss.verify(() -> SignService.getOwner(secondSign), never());
            verify(firstSign, never()).setMetadata(any(), any());
            verify(secondSign, never()).setMetadata(any(), any());
        }
    }

    /**
     * The {@code block == null} guard in the private {@code getAttachedSigns} is unreachable through
     * the public API ({@code canBlockBeBroken} never forwards a null block from its callers), so it
     * is exercised directly via reflection to close the branch.
     */
    @Test
    @SuppressWarnings("unchecked")
    void getAttachedSigns_nullBlock_returnsEmptyList() throws Exception {
        Method m = SignBreakServiceImpl.class.getDeclaredMethod("getAttachedSigns", org.bukkit.block.Block.class);
        m.setAccessible(true);
        List<Sign> result = (List<Sign>) m.invoke(null, new Object[]{null});
        assertThat(result).isEmpty();
    }
}
