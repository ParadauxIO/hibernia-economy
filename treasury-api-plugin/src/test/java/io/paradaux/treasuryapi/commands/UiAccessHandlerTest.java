package io.paradaux.treasuryapi.commands;

import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasuryapi.services.ExplorerUiService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the (now thin) {@link UiAccessHandler}: it resolves player
 * names to UUIDs — guarding never-seen accounts (treasury-api-plugin/behaviour/0002)
 * — delegates orchestration to {@link ExplorerUiService}, and renders the success
 * message. The service is mocked; these pin the handler's name-resolution guard,
 * delegation, and success/partial rendering. Persistence + Keycloak orchestration
 * is covered separately in {@code ExplorerUiServiceImplTest}.
 */
class UiAccessHandlerTest {

    private ExplorerUiService service;
    private Message message;
    private UiAccessHandler handler;
    private Player sender;

    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ExplorerUiService.class);
        message = mock(Message.class);
        handler = new UiAccessHandler(service, message);

        sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(playerId);
        when(sender.getName()).thenReturn("Alice");
    }

    // ---- Link ----

    @Test
    void doLink_fullyLinked_rendersSuccess() {
        when(service.redeemLinkCode("abc123", playerId, "Alice"))
                .thenReturn(ExplorerUiService.LinkOutcome.LINKED);

        handler.doLink(sender, "abc123");

        verify(service).redeemLinkCode("abc123", playerId, "Alice");
        verify(message).send(sender, "treasuryapi.ui.link.success");
    }

    @Test
    void doLink_keycloakPending_rendersPartial() {
        when(service.redeemLinkCode("abc123", playerId, "Alice"))
                .thenReturn(ExplorerUiService.LinkOutcome.LINKED_KEYCLOAK_PENDING);

        handler.doLink(sender, "abc123");

        verify(message).send(sender, "treasuryapi.ui.link.partial");
    }

    @Test
    void doLink_invalidCode_serviceThrows_propagatesToDispatcher() {
        // The service throws NotFound("treasuryapi.ui.link.invalid"); the framework
        // dispatcher renders it. The handler must NOT swallow it or send success.
        when(service.redeemLinkCode(anyString(), any(), anyString()))
                .thenThrow(new NotFoundException("treasuryapi.ui.link.invalid"));

        assertThrows(NotFoundException.class, () -> handler.doLink(sender, "nope"));
        verify(message, never()).send(sender, "treasuryapi.ui.link.success");
    }

    // ---- Sync ----

    @Test
    void doSelfSync_delegatesThenRendersDone() {
        handler.doSelfSync(sender);
        verify(service).selfSync(playerId);
        verify(message).send(sender, "treasuryapi.ui.sync.done");
    }

    // ---- Role grants: name resolution + guard (behaviour/0002) ----

    @Test
    void doUserAdd_realPlayer_resolvesUuid_delegates_rendersAdded() {
        UUID targetUuid = UUID.randomUUID();
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(service.grantRole(targetUuid, "ADMIN", playerId)).thenReturn("admin");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(target);
            handler.doUserAdd(sender, "ADMIN", "Bob");
        }

        verify(service).grantRole(targetUuid, "ADMIN", playerId);
        verify(message).send(sender, "treasuryapi.ui.user.added", "role", "admin", "player", "Bob");
    }

    @Test
    void doUserAdd_fromConsole_grantedByIsNull() {
        UUID targetUuid = UUID.randomUUID();
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(service.grantRole(eq(targetUuid), eq("government"), isNull())).thenReturn("government");
        CommandSender console = mock(CommandSender.class); // not a Player

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(target);
            handler.doUserAdd(console, "government", "Bob");
        }

        verify(service).grantRole(eq(targetUuid), eq("government"), isNull());
    }

    @Test
    void doUserAdd_unknownPlayer_isRefused_neverDelegates() {
        // ADT-38 / behaviour/0002: getOfflinePlayer fabricates a deterministic UUID for
        // any string, so a never-seen name must be refused before any service call.
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(false);
        when(target.isOnline()).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Ghost")).thenReturn(target);
            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> handler.doUserAdd(sender, "admin", "Ghost"));
            org.junit.jupiter.api.Assertions.assertEquals("treasuryapi.ui.user.unknown-player", ex.getMessage());
        }

        verify(service, never()).grantRole(any(), anyString(), any());
    }

    @Test
    void doUserRemove_removedRow_reportsRemoved() {
        UUID targetUuid = UUID.randomUUID();
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(service.revokeRole(targetUuid, "ADMIN")).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(target);
            handler.doUserRemove(sender, "ADMIN", "Bob");
        }

        verify(service).revokeRole(targetUuid, "ADMIN");
        verify(message).send(sender, "treasuryapi.ui.user.removed", "role", "admin", "player", "Bob");
    }

    @Test
    void doUserRemove_noRow_reportsNotGranted() {
        UUID targetUuid = UUID.randomUUID();
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(service.revokeRole(targetUuid, "admin")).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(target);
            handler.doUserRemove(sender, "admin", "Bob");
        }

        verify(message).send(sender, "treasuryapi.ui.user.not-granted", "role", "admin", "player", "Bob");
    }

    @Test
    void doUserRemove_unknownPlayer_isRefused_neverDelegates() {
        // behaviour/0002: the guard now applies to remove too (previously it did not).
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(false);
        when(target.isOnline()).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Ghost")).thenReturn(target);
            assertThrows(NotFoundException.class, () -> handler.doUserRemove(sender, "admin", "Ghost"));
        }

        verify(service, never()).revokeRole(any(), anyString());
    }

    @Test
    void doUserList_withRoles_joinsThem() {
        UUID targetUuid = UUID.randomUUID();
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(service.listRoles(targetUuid)).thenReturn(List.of("admin", "government"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(target);
            handler.doUserList(sender, "Bob");
        }

        verify(message).send(sender, "treasuryapi.ui.user.list", "player", "Bob", "roles", "admin, government");
    }

    @Test
    void doUserList_noRoles_showsImplicitPlayer() {
        UUID targetUuid = UUID.randomUUID();
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(service.listRoles(targetUuid)).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(target);
            handler.doUserList(sender, "Bob");
        }

        verify(message).send(sender, "treasuryapi.ui.user.no-roles", "player", "Bob");
    }

    @Test
    void doUserList_unknownPlayer_isRefused_neverDelegates() {
        // behaviour/0002: list also guards never-seen names now.
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(false);
        when(target.isOnline()).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Ghost")).thenReturn(target);
            assertThrows(NotFoundException.class, () -> handler.doUserList(sender, "Ghost"));
        }

        verify(service, never()).listRoles(any());
    }
}
