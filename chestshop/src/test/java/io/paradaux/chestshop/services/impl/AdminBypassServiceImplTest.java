package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.utils.Permissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Full branch coverage for {@link AdminBypassServiceImpl}: the opt-out set, the
 * bypass-aware {@link AdminBypassServiceImpl#has} demotion (staff vs. gated nodes), and
 * the {@code otherName} name-permission resolver. {@link Permissions} is exercised for
 * real against a stubbed {@link Player}/{@link CommandSender} — it is a pure delegate over
 * {@code hasPermission}/{@code isPermissionSet}, so the node logic is real, not mocked.
 */
class AdminBypassServiceImplTest {

    private AdminBypassServiceImpl service;
    private Player player;
    private UUID uuid;

    @BeforeEach
    void setUp() {
        service = new AdminBypassServiceImpl();
        player = mock(Player.class);
        uuid = UUID.randomUUID();
        lenient().when(player.getUniqueId()).thenReturn(uuid);
    }

    // ── isDisabled / toggle / forget ──────────────────────────────────────────

    @Test
    void isDisabled_falseForNullPlayer() {
        assertThat(service.isDisabled(null)).isFalse();
    }

    @Test
    void isDisabled_falseWhenNotOptedOut() {
        assertThat(service.isDisabled(player)).isFalse();
    }

    @Test
    void toggle_optsOutThenBackIn() {
        assertThat(service.toggle(player)).isTrue();   // first toggle opts out
        assertThat(service.isDisabled(player)).isTrue();
        assertThat(service.toggle(player)).isFalse();  // second toggle opts back in
        assertThat(service.isDisabled(player)).isFalse();
    }

    @Test
    void forget_dropsTheOptOut() {
        service.toggle(player);
        assertThat(service.isDisabled(player)).isTrue();
        service.forget(uuid);
        assertThat(service.isDisabled(player)).isFalse();
    }

    // ── has: the bypass-aware demotion ────────────────────────────────────────

    @Test
    void has_nonPlayerSender_delegatesToNode() {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(Permissions.ADMIN)).thenReturn(true);
        assertThat(service.has(console, Permissions.ADMIN)).isTrue();
    }

    @Test
    void has_player_notDisabled_gatedNode_delegatesToNode() {
        when(player.hasPermission(Permissions.ADMIN)).thenReturn(true);
        assertThat(service.has(player, Permissions.ADMIN)).isTrue();
    }

    @Test
    void has_player_disabled_gatedNode_isDemotedToFalse() {
        service.toggle(player); // opt out
        // ADMIN is a gated staff node → demoted regardless of the underlying permission.
        lenient().when(player.hasPermission(Permissions.ADMIN)).thenReturn(true);
        assertThat(service.has(player, Permissions.ADMIN)).isFalse();
    }

    @Test
    void has_player_disabled_nonGatedNode_stillDelegates() {
        service.toggle(player); // opt out
        // BUY is a basic player node — never gated — so opting out doesn't demote it.
        when(player.hasPermission(Permissions.BUY)).thenReturn(true);
        assertThat(service.has(player, Permissions.BUY)).isTrue();
    }

    // ── otherName ─────────────────────────────────────────────────────────────

    @Test
    void otherName_singleArg_delegatesToBaseOtherName() {
        // base == OTHER_NAME path: hasBase is false (base equals OTHER_NAME), then falls to
        // has(OTHER_NAME + ".*"); grant it so we take the set-false branch and return true.
        when(player.hasPermission(Permissions.OTHER_NAME + ".*")).thenReturn(true);
        // no set-false overrides → both hasPermissionSetFalse checks are false → true
        assertThat(service.otherName(player, "Notch")).isTrue();
    }

    @Test
    void otherName_wildcardGranted_butNameExplicitlyDenied_returnsFalse() {
        when(player.hasPermission(Permissions.OTHER_NAME + ".*")).thenReturn(true);
        // Explicit set-false for the exact name → the &&-chain short-circuits to false.
        when(player.isPermissionSet(Permissions.OTHER_NAME + ".Notch")).thenReturn(true);
        when(player.hasPermission(Permissions.OTHER_NAME + ".Notch")).thenReturn(false);
        assertThat(service.otherName(player, "Notch")).isFalse();
    }

    @Test
    void otherName_wildcardGranted_lowercaseNameDenied_returnsFalse() {
        when(player.hasPermission(Permissions.OTHER_NAME + ".*")).thenReturn(true);
        // First set-false (exact "Notch") passes; the lowercase variant is denied.
        when(player.isPermissionSet(Permissions.OTHER_NAME + ".notch")).thenReturn(true);
        when(player.hasPermission(Permissions.OTHER_NAME + ".notch")).thenReturn(false);
        assertThat(service.otherName(player, "Notch")).isFalse();
    }

    @Test
    void otherName_withBase_hasBaseTrue_takesSetFalseBranch() {
        String base = Permissions.OTHER_NAME_ACCESS;
        // base != OTHER_NAME, so hasBase = otherName(player, OTHER_NAME, name); grant the
        // OTHER_NAME wildcard so that inner call returns true → hasBase true.
        when(player.hasPermission(Permissions.OTHER_NAME + ".*")).thenReturn(true);
        assertThat(service.otherName(player, base, "Notch")).isTrue();
    }

    @Test
    void otherName_withBase_elseBranch_grantsViaSpecificNode() {
        String base = Permissions.OTHER_NAME_ACCESS;
        // No OTHER_NAME wildcard and no base.* → hasBase false, has(base+".*") false → else branch.
        when(player.hasPermission(base + ".Notch")).thenReturn(true);
        assertThat(service.otherName(player, base, "Notch")).isTrue();
    }

    @Test
    void otherName_withBase_elseBranch_grantsViaLowercaseNode() {
        String base = Permissions.OTHER_NAME_ACCESS;
        // Exact-case node absent; lowercase node granted → second half of the || fires.
        when(player.hasPermission(base + ".Notch")).thenReturn(false);
        when(player.hasPermission((base + ".Notch").toLowerCase(Locale.ROOT))).thenReturn(true);
        assertThat(service.otherName(player, base, "Notch")).isTrue();
    }

    @Test
    void otherName_withBase_elseBranch_deniedEverywhere_returnsFalse() {
        String base = Permissions.OTHER_NAME_ACCESS;
        assertThat(service.otherName(player, base, "Notch")).isFalse();
    }

    @Test
    void otherName_withBase_hasBaseFalse_baseWildcardGranted_takesSetFalseBranch() {
        String base = Permissions.OTHER_NAME_ACCESS;
        // hasBase false (no OTHER_NAME wildcard), but has(base + ".*") true → then-branch.
        when(player.hasPermission(base + ".*")).thenReturn(true);
        assertThat(service.otherName(player, base, "Notch")).isTrue();
    }
}
