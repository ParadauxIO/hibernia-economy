package io.paradaux.treasuryapi.services.impl;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.mappers.ExplorerUiMapper;
import io.paradaux.treasuryapi.services.ExplorerUiService;
import io.paradaux.treasuryapi.services.KeycloakAdminClient;
import io.paradaux.treasuryapi.services.PlayerGroupSync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the explorer-UI orchestration lifted out of {@code UiAccessHandler}
 * (structure/0001, plugin-architecture/0001): role validation, the atomic
 * single-use link-code claim (the {@code claimLinkCode} affected-row count is the
 * authoritative guard), the Keycloak-pending fallback, and the self-sync
 * LuckPerms guard resolved through an optional {@code PlayerGroupSync} (not the
 * Injector, plugin-architecture/0002).
 */
class ExplorerUiServiceImplTest {

    private ExplorerUiMapper mapper;
    private KeycloakAdminClient keycloak;
    private TreasuryAPI plugin;
    private PlayerGroupSync task;
    private ExplorerUiServiceImpl service;

    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mapper = mock(ExplorerUiMapper.class);
        keycloak = mock(KeycloakAdminClient.class);
        plugin = mock(TreasuryAPI.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        task = mock(PlayerGroupSync.class);
        service = new ExplorerUiServiceImpl(mapper, keycloak, plugin, Optional.of(task));
    }

    // ---- Role grants ----

    @Test
    void grantRole_validRole_lowerCases_writesWithGranter_returnsNormalised() {
        UUID target = UUID.randomUUID();
        UUID by = UUID.randomUUID();

        String granted = service.grantRole(target, "ADMIN", by);

        assertEquals("admin", granted);
        verify(mapper).addRole(target, "admin", by);
    }

    @Test
    void grantRole_invalidRole_throwsBadCommand_neverWrites() {
        BadCommandException ex = assertThrows(BadCommandException.class,
                () -> service.grantRole(UUID.randomUUID(), "wizard", null));
        assertEquals("treasuryapi.ui.user.invalid-role", ex.getMessage());
        verify(mapper, never()).addRole(any(), anyString(), any());
    }

    @Test
    void revokeRole_returnsTrueWhenRowRemoved() {
        UUID target = UUID.randomUUID();
        when(mapper.removeRole(target, "admin")).thenReturn(1);
        org.junit.jupiter.api.Assertions.assertTrue(service.revokeRole(target, "ADMIN"));
        verify(mapper).removeRole(target, "admin");
    }

    @Test
    void revokeRole_returnsFalseWhenNoRow() {
        UUID target = UUID.randomUUID();
        when(mapper.removeRole(target, "admin")).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertFalse(service.revokeRole(target, "admin"));
    }

    // ---- Link-code redemption (single-use guard) ----

    @Test
    void redeemLinkCode_winner_claimsRowThenUpsertsIdentity_returnsLinked() {
        when(mapper.findValidLinkSub("ABC123")).thenReturn("sub-1");
        when(mapper.claimLinkCode("ABC123")).thenReturn(1);
        when(keycloak.isEnabled()).thenReturn(false);

        ExplorerUiService.LinkOutcome outcome = service.redeemLinkCode("abc123", playerId, "Alice");

        assertEquals(ExplorerUiService.LinkOutcome.LINKED, outcome);
        verify(mapper).claimLinkCode("ABC123");
        verify(mapper).upsertIdentity(org.mockito.ArgumentMatchers.eq("sub-1"),
                org.mockito.ArgumentMatchers.eq(playerId),
                org.mockito.ArgumentMatchers.eq("Alice"), anyString());
    }

    @Test
    void redeemLinkCode_loser_claimReturnsZero_throwsNotFound_neverUpserts() {
        // Passed the SELECT (a concurrent winner had not yet deleted it), but this
        // caller's conditional DELETE claimed zero rows — it lost the race.
        when(mapper.findValidLinkSub("ABC123")).thenReturn("sub-1");
        when(mapper.claimLinkCode("ABC123")).thenReturn(0);

        assertThrows(NotFoundException.class, () -> service.redeemLinkCode("abc123", playerId, "Alice"));

        verify(mapper).claimLinkCode("ABC123");
        verify(mapper, never()).upsertIdentity(anyString(), any(), anyString(), anyString());
        verify(keycloak, never()).isEnabled();
    }

    @Test
    void redeemLinkCode_unknownCode_selectReturnsNull_throwsNotFound_neverClaims() {
        when(mapper.findValidLinkSub("NOPE")).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.redeemLinkCode("nope", playerId, "Alice"));

        verify(mapper, never()).claimLinkCode(anyString());
        verify(mapper, never()).upsertIdentity(anyString(), any(), anyString(), anyString());
    }

    @Test
    void redeemLinkCode_keycloakFails_returnsPendingAfterLocalLink() throws Exception {
        when(mapper.findValidLinkSub("ABC123")).thenReturn("sub-1");
        when(mapper.claimLinkCode("ABC123")).thenReturn(1);
        when(keycloak.isEnabled()).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(keycloak).setMinecraftAttributes(anyString(), any(), anyString());

        ExplorerUiService.LinkOutcome outcome = service.redeemLinkCode("abc123", playerId, "Alice");

        assertEquals(ExplorerUiService.LinkOutcome.LINKED_KEYCLOAK_PENDING, outcome);
        verify(mapper).upsertIdentity(anyString(), any(), anyString(), anyString());
    }

    // ---- Self-sync (optional PlayerGroupSync, absent without LuckPerms) ----

    @Test
    void selfSync_luckPermsAbsent_throwsConflictUnavailable() {
        ExplorerUiServiceImpl withoutLuckPerms =
                new ExplorerUiServiceImpl(mapper, keycloak, plugin, Optional.empty());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> withoutLuckPerms.selfSync(playerId));
        assertEquals("treasuryapi.ui.sync.unavailable", ex.getMessage());
    }

    @Test
    void selfSync_luckPermsPresent_reconcilesPlayer() {
        service.selfSync(playerId);
        verify(task).reconcilePlayer(playerId);
    }

    @Test
    void selfSync_reconcileThrows_wrappedAsConflictFailed() {
        org.mockito.Mockito.doThrow(new RuntimeException("kaboom")).when(task).reconcilePlayer(playerId);

        ConflictException ex = assertThrows(ConflictException.class, () -> service.selfSync(playerId));
        assertEquals("treasuryapi.ui.sync.failed", ex.getMessage());
    }
    @Test
    void resetPassword_disabled_neverCallsKeycloak() throws Exception {
        assertThrows(ConflictException.class, () -> service.resetPassword(playerId));
        verify(keycloak, never()).resetPassword(anyString(), anyString());
    }

    @Test
    void resetPassword_missingOrAmbiguousLink_neverCallsKeycloak() throws Exception {
        when(keycloak.isEnabled()).thenReturn(true);
        when(mapper.findLinkedSubjects(playerId)).thenReturn(java.util.List.of());
        assertThrows(NotFoundException.class, () -> service.resetPassword(playerId));
        when(mapper.findLinkedSubjects(playerId)).thenReturn(java.util.List.of("one", "two"));
        assertThrows(ConflictException.class, () -> service.resetPassword(playerId));
        verify(keycloak, never()).resetPassword(anyString(), anyString());
    }

    @Test
    void resetPassword_usesLinkedSubject_returnsSecret_andLimitsRepeatedAttempts() throws Exception {
        when(keycloak.isEnabled()).thenReturn(true);
        when(mapper.findLinkedSubjects(playerId)).thenReturn(java.util.List.of("own-sub"));
        String password = service.resetPassword(playerId);
        org.junit.jupiter.api.Assertions.assertTrue(password.matches("Aa1![A-Za-z0-9_-]{32}"));
        verify(keycloak).resetPassword("own-sub", password);
        assertEquals("treasuryapi.ui.reset.cooldown",
                assertThrows(ConflictException.class, () -> service.resetPassword(playerId)).getMessage());
        UUID other = UUID.randomUUID();
        when(mapper.findLinkedSubjects(other)).thenReturn(java.util.List.of("other-sub"));
        org.junit.jupiter.api.Assertions.assertNotEquals(password, service.resetPassword(other));
    }

    @Test
    void resetPassword_upstreamFailure_isSanitized_andStillRateLimited() throws Exception {
        when(keycloak.isEnabled()).thenReturn(true);
        when(mapper.findLinkedSubjects(playerId)).thenReturn(java.util.List.of("own-sub"));
        org.mockito.Mockito.doThrow(new IllegalStateException("sensitive upstream response"))
                .when(keycloak).resetPassword(anyString(), anyString());
        ConflictException error = assertThrows(ConflictException.class, () -> service.resetPassword(playerId));
        assertEquals("treasuryapi.ui.reset.failed", error.getMessage());
        org.junit.jupiter.api.Assertions.assertNull(error.getCause());
        assertEquals("treasuryapi.ui.reset.cooldown",
                assertThrows(ConflictException.class, () -> service.resetPassword(playerId)).getMessage());
    }

}
