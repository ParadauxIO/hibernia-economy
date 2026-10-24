package io.paradaux.business.chat;

import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.mappers.FirmStaffMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.services.impl.FirmStaffServiceImpl;
import net.draycia.carbon.api.CarbonChat;
import net.draycia.carbon.api.CarbonChatProvider;
import net.draycia.carbon.api.channels.ChannelRegistry;
import net.draycia.carbon.api.channels.ChatChannel;
import net.draycia.carbon.api.users.CarbonPlayer;
import net.draycia.carbon.api.users.UserManager;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FirmChatService} (business/testing/0001): the spy-registry
 * toggles, the active-firm pointer, membership resolution and session cleanup,
 * plus — against a mocked CarbonChat — the channel's permission checks, which is
 * where a stale selection or a revoked spy permission would otherwise leak chat.
 * The clock and the spy-permission lookup are injected so neither needs a server.
 */
@ExtendWith(MockitoExtension.class)
class FirmChatServiceTest {

    @Mock FirmService firms;
    @Mock FirmStaffService staff;
    @Mock ChatChannel defaultChannel;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Set<UUID> spyPermissionRevoked = new HashSet<>();
    private final Map<UUID, CarbonPlayer> carbonPlayers = new HashMap<>();

    private FirmChatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = newService(staff);
    }

    private FirmChatServiceImpl newService(FirmStaffService staffService) {
        return new FirmChatServiceImpl(firms, staffService, Logger.getLogger("test"), now::get,
                uuid -> !spyPermissionRevoked.contains(uuid));
    }

    private static Firm firm(int id, String displayName) {
        Firm f = new Firm();
        f.setFirmId(id);
        f.setDisplayName(displayName);
        return f;
    }

    private static Firm firm(int id, String displayName, UUID proprietor) {
        Firm f = firm(id, displayName);
        f.setProprietorUuid(proprietor.toString());
        return f;
    }

    private static Player player(UUID uuid) {
        Player p = mock(Player.class);
        lenient().when(p.getUniqueId()).thenReturn(uuid);
        return p;
    }

    /** The (mock) Carbon user for a UUID — the same instance Carbon's user manager hands out. */
    private CarbonPlayer carbonPlayer(UUID uuid) {
        return carbonPlayers.computeIfAbsent(uuid, id -> {
            CarbonPlayer cp = mock(CarbonPlayer.class);
            lenient().when(cp.uuid()).thenReturn(id);
            return cp;
        });
    }

    /** Bring the service up against a mocked CarbonChat; returns the channel it registered. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private FirmChatChannel registerWithCarbon(FirmChatServiceImpl svc) {
        CarbonChat carbon = mock(CarbonChat.class);
        ChannelRegistry registry = mock(ChannelRegistry.class);
        UserManager users = mock(UserManager.class);
        lenient().when(carbon.channelRegistry()).thenReturn(registry);
        lenient().doReturn(users).when(carbon).userManager();
        lenient().when(registry.defaultChannel()).thenReturn(defaultChannel);
        lenient().when(users.user(any(UUID.class)))
                .thenAnswer(inv -> CompletableFuture.completedFuture(carbonPlayer(inv.getArgument(0))));
        try (MockedStatic<CarbonChatProvider> provider = mockStatic(CarbonChatProvider.class)) {
            provider.when(CarbonChatProvider::carbonChat).thenReturn(carbon);
            svc.initialise();
        }
        assertThat(svc.available()).isTrue();
        ArgumentCaptor<ChatChannel> registered = ArgumentCaptor.forClass(ChatChannel.class);
        verify(registry).register(registered.capture());
        return (FirmChatChannel) registered.getValue();
    }

    private boolean maySpeak(FirmChatChannel channel, UUID uuid) {
        return channel.permissions().speechPermitted(carbonPlayer(uuid)).permitted();
    }

    private boolean mayHear(FirmChatChannel channel, UUID uuid) {
        return channel.permissions().hearingPermitted(carbonPlayer(uuid)).permitted();
    }

    // ---- membership revalidation ----------------------------------------------

    /**
     * Regression: join firm chat → get fired → send. The cached selection used to be
     * the only thing the channel consulted, so a fired employee kept posting to the
     * firm until a restart. Driven through the real FirmStaffServiceImpl so the
     * fire → chat invalidation wiring is covered, not just the chat side.
     */
    @Test
    void firedEmployee_canNoLongerSpeakInFirmChat() {
        UUID boss = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        Firm acme = firm(7, "Acme", boss);

        FirmStaffMapper staffMapper = mock(FirmStaffMapper.class);
        AtomicBoolean employed = new AtomicBoolean(true);
        when(staffMapper.isEmployedBy(7, employee.toString())).thenAnswer(inv -> employed.get());
        when(staffMapper.endCurrentEmployment(7, employee.toString(), boss.toString())).thenAnswer(inv -> {
            employed.set(false);
            return 1;
        });
        FirmChatServiceImpl[] chat = new FirmChatServiceImpl[1];
        FirmStaffService realStaff = new FirmStaffServiceImpl(firms, mock(FirmRoleMapper.class), staffMapper,
                mock(FirmAccountService.class), () -> chat[0]);
        chat[0] = newService(realStaff);
        FirmChatChannel channel = registerWithCarbon(chat[0]);

        when(firms.listOwnedOrMemberFirms(employee)).thenReturn(List.of(acme));
        when(firms.getFirmByNameOrId("Acme")).thenReturn(acme);
        when(firms.getFirmById(7)).thenReturn(acme);
        when(firms.isProprietor(7, boss)).thenReturn(true);
        when(firms.isProprietor(7, employee)).thenReturn(false);

        // Join firm chat: selected onto the firm channel and allowed to speak.
        assertThat(chat[0].enterChat(player(employee), 7)).containsSame(acme);
        verify(carbonPlayer(employee)).selectedChannel(channel);
        assertThat(maySpeak(channel, employee)).isTrue();

        realStaff.fireEmployee("Acme", employee, boss);

        // Send: denied, reaches nobody, and they're put back on the default channel.
        assertThat(maySpeak(channel, employee)).isFalse();
        assertThat(channel.recipients(carbonPlayer(employee))).isEmpty();
        assertThat(mayHear(channel, employee)).isFalse();
        assertThat(chat[0].activeFirm(employee)).isEmpty();
        verify(carbonPlayer(employee)).selectedChannel(defaultChannel);
    }

    @Test
    void disbandedFirm_dropsEveryoneTunedIn() {
        UUID boss = UUID.randomUUID();
        Firm acme = firm(7, "Acme", boss);
        FirmChatChannel channel = registerWithCarbon(service);
        when(firms.listOwnedOrMemberFirms(boss)).thenReturn(List.of(acme));
        assertThat(service.enterChat(player(boss), 7)).isPresent();
        assertThat(maySpeak(channel, boss)).isTrue();

        // Disband: the firm stops resolving on the active-only lookup.
        when(firms.getFirmById(7)).thenReturn(null);
        service.membershipChanged(7);

        assertThat(maySpeak(channel, boss)).isFalse();
        assertThat(service.activeFirm(boss)).isEmpty();
    }

    @Test
    void membershipChanged_onlyRechecksThatFirm() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Firm one = firm(1, "One", a);
        Firm two = firm(2, "Two", b);
        registerWithCarbon(service);
        when(firms.listOwnedOrMemberFirms(a)).thenReturn(List.of(one));
        when(firms.listOwnedOrMemberFirms(b)).thenReturn(List.of(two));
        service.enterChat(player(a), 1);
        service.enterChat(player(b), 2);
        when(firms.getFirmById(1)).thenReturn(one);

        service.membershipChanged(1);

        assertThat(service.activeFirm(a)).contains(1); // re-checked, still the proprietor
        assertThat(service.activeFirm(b)).contains(2); // untouched: no lookup for firm 2
        verify(firms, never()).getFirmById(2);
    }

    /**
     * Carbon evaluates the channel permissions per message (and per recipient), so
     * the membership check must not hit the database each time: within the TTL the
     * cached verdict is used, and a change made behind our back (another process
     * writing the shared DB) is still picked up once it lapses.
     */
    @Test
    void membershipIsRecheckedAtMostOncePerTtl() {
        UUID boss = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        Firm acme = firm(7, "Acme", boss);
        FirmChatChannel channel = registerWithCarbon(service);
        when(firms.listOwnedOrMemberFirms(employee)).thenReturn(List.of(acme));
        when(firms.getFirmById(7)).thenReturn(acme);
        when(staff.isEmployedBy(7, employee)).thenReturn(true);
        service.enterChat(player(employee), 7);

        for (int i = 0; i < 50; i++) {
            assertThat(maySpeak(channel, employee)).isTrue();
        }
        verify(firms, never()).getFirmById(anyInt());
        verify(staff, never()).isEmployedBy(anyInt(), any());

        // TTL lapses: one re-check, which then covers the next window.
        now.addAndGet(FirmChatServiceImpl.MEMBERSHIP_TTL_MS + 1);
        for (int i = 0; i < 50; i++) {
            assertThat(maySpeak(channel, employee)).isTrue();
        }
        verify(staff, times(1)).isEmployedBy(7, employee);

        // Employment ended out-of-process — no membershipChanged() signal.
        when(staff.isEmployedBy(7, employee)).thenReturn(false);
        assertThat(maySpeak(channel, employee)).isTrue(); // still inside the window
        now.addAndGet(FirmChatServiceImpl.MEMBERSHIP_TTL_MS + 1);
        assertThat(maySpeak(channel, employee)).isFalse();
    }

    @Test
    void membershipRecheckFailure_failsClosedButKeepsTheSelection() {
        UUID boss = UUID.randomUUID();
        Firm acme = firm(7, "Acme", boss);
        FirmChatChannel channel = registerWithCarbon(service);
        when(firms.listOwnedOrMemberFirms(boss)).thenReturn(List.of(acme));
        service.enterChat(player(boss), 7);
        service.membershipChanged(7);

        when(firms.getFirmById(7)).thenThrow(new RuntimeException("db down"));
        assertThat(maySpeak(channel, boss)).isFalse();
        verify(carbonPlayer(boss), never()).selectedChannel(defaultChannel);

        // Once the DB is back the player is still in firm chat, without re-joining.
        doReturn(acme).when(firms).getFirmById(7);
        assertThat(maySpeak(channel, boss)).isTrue();
    }

    @Test
    void leaveChat_clearsSelectionAndReturnsToDefaultChannel() {
        UUID boss = UUID.randomUUID();
        Firm acme = firm(7, "Acme", boss);
        FirmChatChannel channel = registerWithCarbon(service);
        when(firms.listOwnedOrMemberFirms(boss)).thenReturn(List.of(acme));
        Player p = player(boss);
        service.enterChat(p, null);

        service.leaveChat(p);

        assertThat(service.activeFirm(boss)).isEmpty();
        assertThat(maySpeak(channel, boss)).isFalse();
        verify(carbonPlayer(boss)).selectedChannel(defaultChannel);
    }

    // ---- disbanded firms don't count as chat memberships -----------------------

    @Test
    void archivedFirms_areIgnoredWhenResolvingTheChatFirm() {
        UUID u = UUID.randomUUID();
        Firm live = firm(1, "Live", u);
        Firm defunct = firm(2, "Defunct", u);
        defunct.setArchived(true);
        registerWithCarbon(service);
        // listOwnedOrMemberFirms is archived-inclusive.
        when(firms.listOwnedOrMemberFirms(u)).thenReturn(List.of(defunct, live));

        // One live firm: not "multiple", and bare /firm chat resolves to it.
        assertThat(service.inMultipleFirms(u)).isFalse();
        assertThat(service.enterChat(player(u), null)).containsSame(live);
        // The defunct firm can't be entered explicitly either.
        assertThat(service.enterChat(player(u), 2)).isEmpty();
    }

    // ---- CarbonChat absent → feature soft-disabled ----------------------------

    @Test
    void available_isFalseWithoutCarbonChat() {
        assertThat(service.available()).isFalse();
    }

    @Test
    void enterChat_returnsEmptyWhenUnavailable() {
        // No CarbonChat registered, so enterChat short-circuits before touching membership.
        assertThat(service.enterChat(null, 1)).isEmpty();
    }

    // ---- spy permission revalidation -------------------------------------------

    /**
     * Regression: the spy registries were only ever written by the toggle command,
     * so a moderator whose business.admin.chatspy was revoked kept hearing every
     * firm until a restart.
     */
    @Test
    void spyWhoLosesThePermission_stopsHearing() {
        UUID mod = UUID.randomUUID();
        FirmChatChannel channel = registerWithCarbon(service);
        service.toggleGlobalSpy(mod);
        service.toggleFirmSpy(mod, 7);
        assertThat(mayHear(channel, mod)).isTrue();
        assertThat(service.isSpyingOn(mod, 7)).isTrue();

        spyPermissionRevoked.add(mod);

        assertThat(mayHear(channel, mod)).isFalse();
        assertThat(service.isSpyingOn(mod, 7)).isFalse();
        assertThat(service.isAnySpy(mod)).isFalse();

        // Regaining the permission doesn't silently resume spying: they must toggle again.
        spyPermissionRevoked.remove(mod);
        assertThat(service.isAnySpy(mod)).isFalse();
        assertThat(service.toggleGlobalSpy(mod)).isTrue();
        assertThat(mayHear(channel, mod)).isTrue();
    }

    // ---- global spy toggle ----------------------------------------------------

    @Test
    void toggleGlobalSpy_flipsOnThenOff() {
        UUID u = UUID.randomUUID();
        assertThat(service.toggleGlobalSpy(u)).isTrue();
        assertThat(service.isAnySpy(u)).isTrue();
        assertThat(service.isSpyingOn(u, 42)).isTrue(); // global spy sees any firm

        assertThat(service.toggleGlobalSpy(u)).isFalse();
        assertThat(service.isAnySpy(u)).isFalse();
        assertThat(service.isSpyingOn(u, 42)).isFalse();
    }

    // ---- per-firm spy toggle --------------------------------------------------

    @Test
    void toggleFirmSpy_watchesOnlyThatFirm() {
        UUID u = UUID.randomUUID();
        assertThat(service.toggleFirmSpy(u, 7)).isTrue();
        assertThat(service.isSpyingOn(u, 7)).isTrue();
        assertThat(service.isSpyingOn(u, 8)).isFalse(); // a different firm is not watched
        assertThat(service.isAnySpy(u)).isTrue();
    }

    @Test
    void toggleFirmSpy_offForLastFirm_dropsTheSpyEntirely() {
        UUID u = UUID.randomUUID();
        service.toggleFirmSpy(u, 7);
        assertThat(service.toggleFirmSpy(u, 7)).isFalse();
        assertThat(service.isSpyingOn(u, 7)).isFalse();
        // With no firms left watched the player is no longer a spy at all.
        assertThat(service.isAnySpy(u)).isFalse();
    }

    @Test
    void toggleFirmSpy_multipleFirms_areIndependent() {
        UUID u = UUID.randomUUID();
        service.toggleFirmSpy(u, 7);
        service.toggleFirmSpy(u, 9);
        // Turning one off leaves the other watched.
        assertThat(service.toggleFirmSpy(u, 7)).isFalse();
        assertThat(service.isSpyingOn(u, 7)).isFalse();
        assertThat(service.isSpyingOn(u, 9)).isTrue();
        assertThat(service.isAnySpy(u)).isTrue();
    }

    @Test
    void isSpyingOn_nullFirmId_isFalseUnlessGlobal() {
        UUID u = UUID.randomUUID();
        service.toggleFirmSpy(u, 7);
        assertThat(service.isSpyingOn(u, null)).isFalse();

        service.toggleGlobalSpy(u);
        assertThat(service.isSpyingOn(u, null)).isTrue(); // global spy sees everything
    }

    // ---- active-firm pointer + cleanup ---------------------------------------

    @Test
    void activeFirm_absentByDefault() {
        assertThat(service.activeFirm(UUID.randomUUID())).isEmpty();
    }

    @Test
    void activeFirm_survivesWhileStillAMember() {
        UUID boss = UUID.randomUUID();
        Firm acme = firm(7, "Acme", boss);
        registerWithCarbon(service);
        when(firms.listOwnedOrMemberFirms(boss)).thenReturn(List.of(acme));
        when(firms.getFirmById(7)).thenReturn(acme);
        service.enterChat(player(boss), 7);

        service.membershipChanged(7);

        assertThat(service.activeFirm(boss)).contains(7);
    }

    @Test
    void forget_clearsActiveFirmAndAllSpyState() {
        UUID u = UUID.randomUUID();
        service.toggleGlobalSpy(u);
        service.toggleFirmSpy(u, 7);

        service.forget(u);

        assertThat(service.isAnySpy(u)).isFalse();
        assertThat(service.isSpyingOn(u, 7)).isFalse();
        assertThat(service.activeFirm(u)).isEmpty();
    }

    // ---- firmName lookup ------------------------------------------------------

    @Test
    void firmName_nullId_returnsNull() {
        assertThat(service.firmName(null)).isNull();
    }

    @Test
    void firmName_unknownFirm_returnsNull() {
        when(firms.getFirmById(99)).thenReturn(null);
        assertThat(service.firmName(99)).isNull();
    }

    @Test
    void firmName_knownFirm_returnsDisplayName() {
        when(firms.getFirmById(3)).thenReturn(firm(3, "Acme"));
        assertThat(service.firmName(3)).isEqualTo("Acme");
    }

    // ---- multi-firm membership ------------------------------------------------

    @Test
    void inMultipleFirms_trueOnlyWhenMoreThanOne() {
        UUID solo = UUID.randomUUID();
        UUID multi = UUID.randomUUID();
        when(firms.listOwnedOrMemberFirms(solo)).thenReturn(List.of(firm(1, "One")));
        when(firms.listOwnedOrMemberFirms(multi)).thenReturn(List.of(firm(1, "One"), firm(2, "Two")));

        assertThat(service.inMultipleFirms(solo)).isFalse();
        assertThat(service.inMultipleFirms(multi)).isTrue();
    }
}
