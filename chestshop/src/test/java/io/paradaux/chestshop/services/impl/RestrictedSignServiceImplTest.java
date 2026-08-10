package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome;
import io.paradaux.chestshop.model.Transaction.TransactionType;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.utils.Permissions;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full line + branch coverage for {@link RestrictedSignServiceImpl}: the pre-transaction gate,
 * restricted-sign resolution across connected faces, the static sign-format predicates, and the
 * access/destroy/group-permission checks. The {@code BlockUtil} statics (incl. the statically
 * imported {@code getState}) are driven with {@link org.mockito.Mockito#mockStatic}; the two
 * service collaborators are plain Mockito mocks.
 */
class RestrictedSignServiceImplTest {

    private AccountService accounts;
    private AdminBypassService adminBypass;
    private RestrictedSignServiceImpl service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountService.class);
        adminBypass = mock(AdminBypassService.class);
        service = new RestrictedSignServiceImpl(accounts, adminBypass);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Sign sign(String[] lines) {
        Sign s = mock(Sign.class);
        lenient().when(s.getLines()).thenReturn(lines);
        return s;
    }

    private PendingTransaction pending(Sign sign, Player client) {
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        return new PendingTransaction(mock(Inventory.class), mock(Inventory.class),
                new org.bukkit.inventory.ItemStack[0], BigDecimal.ONE, client, owner, sign,
                TransactionType.BUY, false);
    }

    private static final String[] RESTRICTED = {"[restricted]", "", "", ""};
    private static final String[] PLAIN = {"Alice", "1", "B 5", "STONE"};

    // ═══════════════════════════ onPreTransaction ═══════════════════════════

    @Test
    void onPreTransaction_cancelled_returnsEarly() {
        PendingTransaction event = pending(sign(PLAIN), mock(Player.class));
        event.setCancelled(TransactionOutcome.SPAM_CLICKING_PROTECTION);

        service.onPreTransaction(event); // must not overwrite the outcome

        assertThat(event.getTransactionOutcome()).isEqualTo(TransactionOutcome.SPAM_CLICKING_PROTECTION);
    }

    @Test
    void onPreTransaction_restrictedAndNoAccess_cancelsAsRestricted() {
        Player client = mock(Player.class);
        Block signBlock = mock(Block.class);
        Block blockUp = mock(Block.class);
        Sign shopSign = sign(PLAIN);
        Sign restrictedSign = sign(RESTRICTED);
        when(shopSign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.UP)).thenReturn(blockUp);
        PendingTransaction event = pending(shopSign, client);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(blockUp)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(blockUp, false)).thenReturn(restrictedSign);
            when(adminBypass.has(client, Permissions.ADMIN)).thenReturn(false); // no admin bypass, no group line

            service.onPreTransaction(event);
        }

        assertThat(event.getTransactionOutcome()).isEqualTo(TransactionOutcome.SHOP_IS_RESTRICTED);
    }

    @Test
    void onPreTransaction_restrictedButAccessible_passes() {
        Player client = mock(Player.class);
        Block signBlock = mock(Block.class);
        Block blockUp = mock(Block.class);
        Sign shopSign = sign(PLAIN);
        Sign restrictedSign = sign(new String[]{"[restricted]", "vip", "", ""});
        when(shopSign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.UP)).thenReturn(blockUp);
        PendingTransaction event = pending(shopSign, client);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(blockUp)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(blockUp, false)).thenReturn(restrictedSign);
            // restricted shop, but the client can access it → the && short-circuits, no cancel
            when(adminBypass.has(client, Permissions.GROUP + "vip")).thenReturn(true);

            service.onPreTransaction(event);
        }

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    void onPreTransaction_notRestricted_passes() {
        Player client = mock(Player.class);
        Block signBlock = mock(Block.class);
        Block blockUp = mock(Block.class);
        Sign shopSign = sign(PLAIN);
        when(shopSign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.UP)).thenReturn(blockUp);
        PendingTransaction event = pending(shopSign, client);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(blockUp)).thenReturn(false); // not a restricted shop

            service.onPreTransaction(event);
        }

        assertThat(event.isCancelled()).isFalse();
    }

    // ═══════════════════════════ getRestrictedSign ═══════════════════════════

    @Test
    void getRestrictedSign_blockIsRestrictedSign_returnsIt() {
        Location location = mock(Location.class);
        Block block = mock(Block.class);
        when(location.getBlock()).thenReturn(block);
        Sign restrictedSign = sign(RESTRICTED);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(block, false)).thenReturn(restrictedSign);

            assertThat(service.getRestrictedSign(location)).isSameAs(restrictedSign);
        }
    }

    @Test
    void getRestrictedSign_blockIsNonRestrictedSign_returnsNull() {
        Location location = mock(Location.class);
        Block block = mock(Block.class);
        when(location.getBlock()).thenReturn(block);
        Sign plainSign = sign(PLAIN);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(block)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(block, false)).thenReturn(plainSign);

            assertThat(service.getRestrictedSign(location)).isNull();
        }
    }

    @Test
    void getRestrictedSign_walksFaces_findsAttachedRestrictedSign() {
        Location location = mock(Location.class);
        Block currentBlock = mock(Block.class);
        when(location.getBlock()).thenReturn(currentBlock);

        // SELF: not a sign → continue. UP: a sign but attached elsewhere → continue.
        // EAST: a restricted sign attached to currentBlock → returned.
        Block selfRel = mock(Block.class);
        Block upRel = mock(Block.class);
        Block eastRel = mock(Block.class);
        when(currentBlock.getRelative(BlockFace.SELF)).thenReturn(selfRel);
        when(currentBlock.getRelative(BlockFace.UP)).thenReturn(upRel);
        when(currentBlock.getRelative(BlockFace.EAST)).thenReturn(eastRel);

        Sign upSign = sign(PLAIN);           // attached to a different block → skipped
        Sign eastSign = sign(RESTRICTED);    // attached to currentBlock, restricted → returned
        Block otherBlock = mock(Block.class);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(selfRel)).thenReturn(false); // relative not-sign skip
            bu.when(() -> BlockUtil.isSign(upRel)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(upRel, false)).thenReturn(upSign);
            bu.when(() -> BlockUtil.getAttachedBlock(upSign)).thenReturn(otherBlock); // mismatch skip
            bu.when(() -> BlockUtil.isSign(eastRel)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(eastRel, false)).thenReturn(eastSign);
            bu.when(() -> BlockUtil.getAttachedBlock(eastSign)).thenReturn(currentBlock); // match

            assertThat(service.getRestrictedSign(location)).isSameAs(eastSign);
        }
    }

    @Test
    void getRestrictedSign_attachedButNotRestricted_exhaustsAndReturnsNull() {
        Location location = mock(Location.class);
        Block currentBlock = mock(Block.class);
        when(location.getBlock()).thenReturn(currentBlock);

        Block[] relatives = new Block[RestrictedSignServiceImplFaces.FACES.length];
        for (int i = 0; i < relatives.length; i++) {
            relatives[i] = mock(Block.class);
            when(currentBlock.getRelative(RestrictedSignServiceImplFaces.FACES[i])).thenReturn(relatives[i]);
        }

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            // currentBlock itself is not a sign so the top-of-method branch falls through to the loop.
            bu.when(() -> BlockUtil.isSign(currentBlock)).thenReturn(false);
            // Every face is a sign attached to currentBlock, but none is restricted → loop finishes → null.
            for (Block rel : relatives) {
                Sign plain = sign(PLAIN);
                bu.when(() -> BlockUtil.isSign(rel)).thenReturn(true);
                bu.when(() -> BlockUtil.getState(rel, false)).thenReturn(plain);
                bu.when(() -> BlockUtil.getAttachedBlock(plain)).thenReturn(currentBlock);
            }

            assertThat(service.getRestrictedSign(location)).isNull();
        }
    }

    /** Mirror of the private {@code SIGN_CONNECTION_FACES} so the exhaustion test stubs every face. */
    private static final class RestrictedSignServiceImplFaces {
        static final BlockFace[] FACES = {BlockFace.SELF, BlockFace.UP, BlockFace.EAST,
                BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH};
    }

    // ═══════════════════════════ isRestrictedShop (static) ═══════════════════════════

    @Test
    void isRestrictedShop_blockUpIsRestrictedSign_true() {
        Block signBlock = mock(Block.class);
        Block blockUp = mock(Block.class);
        Sign sign = sign(PLAIN);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.UP)).thenReturn(blockUp);
        Sign upSign = sign(RESTRICTED);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(blockUp)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(blockUp, false)).thenReturn(upSign);

            assertThat(RestrictedSignServiceImpl.isRestrictedShop(sign)).isTrue();
        }
    }

    @Test
    void isRestrictedShop_blockUpIsSignButNotRestricted_false() {
        Block signBlock = mock(Block.class);
        Block blockUp = mock(Block.class);
        Sign sign = sign(PLAIN);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.UP)).thenReturn(blockUp);
        Sign upSign = sign(PLAIN); // a sign, but not a [restricted] one → second half of && is false

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(blockUp)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(blockUp, false)).thenReturn(upSign);

            assertThat(RestrictedSignServiceImpl.isRestrictedShop(sign)).isFalse();
        }
    }

    @Test
    void isRestrictedShop_blockUpNotSign_false() {
        Block signBlock = mock(Block.class);
        Block blockUp = mock(Block.class);
        Sign sign = sign(PLAIN);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.UP)).thenReturn(blockUp);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(blockUp)).thenReturn(false);

            assertThat(RestrictedSignServiceImpl.isRestrictedShop(sign)).isFalse();
        }
    }

    // ═══════════════════════════ isRestricted (static) ═══════════════════════════

    @Test
    void isRestricted_lines_matchesCaseInsensitively() {
        assertThat(RestrictedSignServiceImpl.isRestricted(new String[]{"[RESTRICTED]", "", "", ""})).isTrue();
        assertThat(RestrictedSignServiceImpl.isRestricted(PLAIN)).isFalse();
    }

    @Test
    void isRestricted_sign_delegatesToLines() {
        assertThat(RestrictedSignServiceImpl.isRestricted(sign(RESTRICTED))).isTrue();
        assertThat(RestrictedSignServiceImpl.isRestricted(sign(PLAIN))).isFalse();
    }

    // ═══════════════════════════ canAccess ═══════════════════════════

    @Test
    void canAccess_blockUpNotSign_true() {
        Player player = mock(Player.class);
        Block signBlock = mock(Block.class);
        Block blockUp = mock(Block.class);
        Sign sign = sign(PLAIN);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.UP)).thenReturn(blockUp);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(blockUp)).thenReturn(false);

            assertThat(service.canAccess(sign, player)).isTrue();
        }
    }

    @Test
    void canAccess_blockUpIsSign_delegatesToHasPermission() {
        Player player = mock(Player.class);
        Block signBlock = mock(Block.class);
        Block blockUp = mock(Block.class);
        Sign sign = sign(PLAIN);
        when(sign.getBlock()).thenReturn(signBlock);
        when(signBlock.getRelative(BlockFace.UP)).thenReturn(blockUp);
        Sign upSign = sign(new String[]{"[restricted]", "vip", "", ""});

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(blockUp)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(blockUp, false)).thenReturn(upSign);
            when(adminBypass.has(player, Permissions.ADMIN)).thenReturn(false);
            when(adminBypass.has(player, Permissions.GROUP + "vip")).thenReturn(true);

            assertThat(service.canAccess(sign, player)).isTrue();
        }
    }

    // ═══════════════════════════ canDestroy ═══════════════════════════

    @Test
    void canDestroy_delegatesToAccountsWithAssociatedSign() {
        Player player = mock(Player.class);
        Block restrictedBlock = mock(Block.class);
        Block down = mock(Block.class);
        Sign restricted = sign(RESTRICTED);
        when(restricted.getBlock()).thenReturn(restrictedBlock);
        when(restrictedBlock.getRelative(BlockFace.DOWN)).thenReturn(down);
        Sign shopSign = sign(PLAIN);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(down)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(down, false)).thenReturn(shopSign);
            when(accounts.hasPermission(player, Permissions.OTHER_NAME_DESTROY, shopSign)).thenReturn(true);

            assertThat(service.canDestroy(player, restricted)).isTrue();
            verify(accounts).hasPermission(player, Permissions.OTHER_NAME_DESTROY, shopSign);
        }
    }

    // ═══════════════════════════ getAssociatedSign (static) ═══════════════════════════

    @Test
    void getAssociatedSign_downIsSign_returnsIt() {
        Block restrictedBlock = mock(Block.class);
        Block down = mock(Block.class);
        Sign restricted = sign(RESTRICTED);
        when(restricted.getBlock()).thenReturn(restrictedBlock);
        when(restrictedBlock.getRelative(BlockFace.DOWN)).thenReturn(down);
        Sign shopSign = sign(PLAIN);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(down)).thenReturn(true);
            bu.when(() -> BlockUtil.getState(down, false)).thenReturn(shopSign);

            assertThat(RestrictedSignServiceImpl.getAssociatedSign(restricted)).isSameAs(shopSign);
        }
    }

    @Test
    void getAssociatedSign_downNotSign_returnsNull() {
        Block restrictedBlock = mock(Block.class);
        Block down = mock(Block.class);
        Sign restricted = sign(RESTRICTED);
        when(restricted.getBlock()).thenReturn(restrictedBlock);
        when(restrictedBlock.getRelative(BlockFace.DOWN)).thenReturn(down);

        try (MockedStatic<BlockUtil> bu = mockStatic(BlockUtil.class)) {
            bu.when(() -> BlockUtil.isSign(down)).thenReturn(false);

            assertThat(RestrictedSignServiceImpl.getAssociatedSign(restricted)).isNull();
        }
    }

    // ═══════════════════════════ hasPermission ═══════════════════════════

    @Test
    void hasPermission_adminBypass_true() {
        Player player = mock(Player.class);
        when(adminBypass.has(player, Permissions.ADMIN)).thenReturn(true);

        assertThat(service.hasPermission(player, new String[]{"[restricted]", "vip", "", ""})).isTrue();
        verify(adminBypass, never()).has(eq(player), eq(Permissions.GROUP + "vip"));
    }

    @Test
    void hasPermission_groupLineMatch_true() {
        Player player = mock(Player.class);
        when(adminBypass.has(player, Permissions.ADMIN)).thenReturn(false);
        when(adminBypass.has(eq(player), any(String.class))).thenReturn(false);
        when(adminBypass.has(player, Permissions.GROUP + "vip")).thenReturn(true);

        assertThat(service.hasPermission(player, new String[]{"admins", "vip", "", ""})).isTrue();
    }

    @Test
    void hasPermission_noMatch_false() {
        Player player = mock(Player.class);
        when(adminBypass.has(eq(player), any(String.class))).thenReturn(false);

        assertThat(service.hasPermission(player, new String[]{"a", "b", "c", "d"})).isFalse();
    }
}
