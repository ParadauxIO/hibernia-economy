package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.mappers.AccountMapper;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.Permissions;
import org.apache.ibatis.exceptions.PersistenceException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Comprehensive coverage for {@link AccountServiceImpl}: the get-or-create resolution,
 * the {@link io.paradaux.chestshop.utils.SimpleCache} hit/miss/exception fall-throughs, the
 * name-access predicates ({@code canUseName}/{@code canAccess}/{@code isOwner}), and the
 * {@code load()} bootstrap. The {@link AccountMapper} is mocked (the SQLite store); the
 * business-account collaborator is a {@link FakeBusinessAccounts} (its {@code bind}
 * signature references Treasury/Business API types absent from the test runtime, so it can't be
 * a Mockito mock). {@link Bukkit}/{@link ChestShop} statics are stubbed per test.
 */
class AccountServiceImplTest {

    private AccountMapper mapper;
    private FakeBusinessAccounts business;
    private SignService signService;
    private io.paradaux.chestshop.services.AdminBypassService adminBypass;
    private ChestShopConfiguration config;
    private AccountServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AccountMapper.class);
        business = new FakeBusinessAccounts();
        signService = mock(SignService.class);
        adminBypass = mock(io.paradaux.chestshop.services.AdminBypassService.class);
        config = mock(ChestShopConfiguration.class);
        when(config.getCacheSize()).thenReturn(128);
        lenient().when(config.isEnsureCorrectPlayerid()).thenReturn(false);
        lenient().when(config.getAdminShopName()).thenReturn("Server");
        lenient().when(config.getServerEconomyAccount()).thenReturn("");
        lenient().when(config.getServerEconomyAccountUuid()).thenReturn(new UUID(0, 0));
        service = new AccountServiceImpl(mapper, business, config, signService, adminBypass);
    }

    private Player player(String name, UUID id) {
        Player p = mock(Player.class);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.getUniqueId()).thenReturn(id);
        return p;
    }

    private Sign signOwned(String owner) {
        Sign s = mock(Sign.class);
        lenient().when(s.getLines()).thenReturn(new String[]{owner, "5", "B 10", "STONE"});
        return s;
    }

    // ═══════════════════ getAccountCount ═══════════════════

    @Test
    void getAccountCount_excludesAdminRow() {
        when(mapper.count()).thenReturn(5L);
        assertThat(service.getAccountCount()).isEqualTo(4);
    }

    @Test
    void getAccountCount_returnsZeroOnPersistenceError() {
        when(mapper.count()).thenThrow(new PersistenceException("boom"));
        assertThat(service.getAccountCount()).isEqualTo(0);
    }

    // ═══════════════════ getAccount(UUID) — cache & loader ═══════════════════

    @Test
    void getAccountByUuid_loadsFromMapper_thenCaches() {
        UUID id = UUID.randomUUID();
        Account acc = new Account("Notch", "Notch", null);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);

        Account first = service.getAccount(id);
        assertThat(first).isSameAs(acc);
        assertThat(acc.getUuid()).isEqualTo(id);

        // Second call is a cache hit — no further mapper calls needed.
        Account second = service.getAccount(id);
        assertThat(second).isSameAs(acc);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(1)).findLatestByUuid(id);
    }

    @Test
    void getAccountByUuid_returnsNull_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(mapper.findLatestByUuid(id)).thenReturn(null);
        assertThat(service.getAccount(id)).isNull();
    }

    @Test
    void getAccountByUuid_returnsNull_onPersistenceError() {
        UUID id = UUID.randomUUID();
        when(mapper.findLatestByUuid(id)).thenThrow(new PersistenceException("db down"));
        assertThat(service.getAccount(id)).isNull();
    }

    // ═══════════════════ getAccount(String) ═══════════════════

    @Test
    void getAccountByName_nullAndEmptyRejected() {
        assertThatThrownBy(() -> service.getAccount((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.getAccount("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getAccountByName_loadsAndCaches() {
        Account acc = new Account("Notch", "Notch", UUID.randomUUID());
        when(mapper.findLatestByName("Notch")).thenReturn(acc);
        assertThat(service.getAccount("Notch")).isSameAs(acc);
    }

    @Test
    void getAccountByName_returnsNull_whenNotFound() {
        when(mapper.findLatestByName("Ghost")).thenReturn(null);
        assertThat(service.getAccount("Ghost")).isNull();
    }

    @Test
    void getAccountByName_returnsNull_onPersistenceError() {
        when(mapper.findLatestByName("Notch")).thenThrow(new PersistenceException("db"));
        assertThat(service.getAccount("Notch")).isNull();
    }

    // ═══════════════════ getAccountFromShortName ═══════════════════

    @Test
    void getAccountFromShortName_nullAndEmptyRejected() {
        assertThatThrownBy(() -> service.getAccountFromShortName(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.getAccountFromShortName("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getAccountFromShortName_loads() {
        Account acc = new Account("Notch", "Notch", UUID.randomUUID());
        when(mapper.findByShortName("Notch")).thenReturn(acc);
        assertThat(service.getAccountFromShortName("Notch")).isSameAs(acc);
    }

    @Test
    void getAccountFromShortName_returnsNull_whenNotFound() {
        when(mapper.findByShortName("Ghost")).thenReturn(null);
        assertThat(service.getAccountFromShortName("Ghost")).isNull();
    }

    @Test
    void getAccountFromShortName_returnsNull_onPersistenceError() {
        when(mapper.findByShortName("Notch")).thenThrow(new PersistenceException("db"));
        assertThat(service.getAccountFromShortName("Notch")).isNull();
    }

    // ═══════════════════ getOrCreateAccount ═══════════════════

    @Test
    void getOrCreateAccount_returnsExisting() {
        UUID id = UUID.randomUUID();
        Account acc = new Account("Notch", "Notch", null);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.getOrCreateAccount(id, "Notch")).isSameAs(acc);
    }

    @Test
    void getOrCreateAccount_createsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(mapper.findLatestByUuid(id)).thenReturn(null);
        when(mapper.findByUuidAndName(id, "Notch")).thenReturn(null);
        when(mapper.findByShortName(anyString())).thenReturn(null);
        Account created = service.getOrCreateAccount(id, "Notch");
        assertThat(created).isNotNull();
        assertThat(created.getName()).isEqualTo("Notch");
        assertThat(created.getLastSeen()).isNotNull();
    }

    @Test
    void getOrCreateAccount_nullArgsRejected() {
        assertThatThrownBy(() -> service.getOrCreateAccount(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.getOrCreateAccount(UUID.randomUUID(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void getOrCreateAccount_fromOfflinePlayer_resolvesByUuidAndName() {
        UUID id = UUID.randomUUID();
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Notch");
        when(op.getUniqueId()).thenReturn(id);
        Account acc = new Account("Notch", "Notch", null);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.getOrCreateAccount(op)).isSameAs(acc);
    }

    @Test
    void getOrCreateAccount_fromOfflinePlayer_rejectsWrongUuidVersion() {
        service.setUuidVersion(4);
        when(config.isEnsureCorrectPlayerid()).thenReturn(true);
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Notch");
        when(op.getUniqueId()).thenReturn(new UUID(0, 0)); // version 0, != 4
        assertThatThrownBy(() -> service.getOrCreateAccount(op)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getOrCreateAccount_fromOfflinePlayer_nullNameRejected() {
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn(null);
        when(op.getUniqueId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.getOrCreateAccount(op)).isInstanceOf(NullPointerException.class);
    }

    // ═══════════════════ storeUsername ═══════════════════

    @Test
    void storeUsername_reusesExistingRow() {
        UUID id = UUID.randomUUID();
        Account existing = new Account("Notch", "Notch", id);
        when(mapper.findByUuidAndName(id, "Notch")).thenReturn(existing);
        Account result = service.storeUsername(new io.paradaux.chestshop.model.PlayerSnapshot(id, "Notch"));
        assertThat(result).isSameAs(existing);
        assertThat(result.getLastSeen()).isNotNull();
    }

    @Test
    void storeUsername_createsWhenLookupThrows() {
        UUID id = UUID.randomUUID();
        when(mapper.findByUuidAndName(id, "Notch")).thenThrow(new PersistenceException("db"));
        when(mapper.findByShortName(anyString())).thenReturn(null);
        Account result = service.storeUsername(new io.paradaux.chestshop.model.PlayerSnapshot(id, "Notch"));
        assertThat(result).isNotNull();
        assertThat(result.getUuid()).isEqualTo(id);
    }

    @Test
    void storeUsername_returnsNull_whenSaveThrows() {
        UUID id = UUID.randomUUID();
        when(mapper.findByUuidAndName(id, "Notch")).thenReturn(null);
        when(mapper.findByShortName(anyString())).thenReturn(null);
        org.mockito.Mockito.doThrow(new PersistenceException("save fail")).when(mapper).save(any());
        Account result = service.storeUsername(new io.paradaux.chestshop.model.PlayerSnapshot(id, "Notch"));
        assertThat(result).isNull();
    }

    // ═══════════════════ resolveAccount ═══════════════════

    @Test
    void resolveAccount_prefersBusinessAccount() {
        Account biz = new Account("B:5", "B:5", new UUID(1, 5));
        business.resolve = name -> biz;
        assertThat(service.resolveAccount("B:5")).isSameAs(biz);
    }

    @Test
    void resolveAccount_fallsBackToShortName() {
        Account acc = new Account("Notch", "Notch", UUID.randomUUID());
        when(mapper.findByShortName("Notch")).thenReturn(acc);
        when(mapper.findLatestByUuid(acc.getUuid())).thenReturn(acc);
        assertThat(service.resolveAccount("Notch")).isSameAs(acc);
    }

    @Test
    void resolveAccount_fallsBackToFullName_whenNoShortName() {
        UUID id = UUID.randomUUID();
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findByShortName("Notch")).thenReturn(null);
        when(mapper.findLatestByName("Notch")).thenReturn(acc);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.resolveAccount("Notch")).isSameAs(acc);
    }

    @Test
    void resolveAccount_returnsNull_whenNothingResolves() {
        when(mapper.findByShortName("Ghost")).thenReturn(null);
        when(mapper.findLatestByName("Ghost")).thenReturn(null);
        assertThat(service.resolveAccount("Ghost")).isNull();
    }

    // ═══════════════════ canUseName / canAccess ═══════════════════

    @Test
    void canUseName_adminShop_allowedWithPermission() {
        Player p = player("Notch", UUID.randomUUID());
        when(signService.isAdminShop("Server")).thenReturn(true);
        when(adminBypass.has(p, Permissions.ADMIN_SHOP)).thenReturn(true);
        assertThat(service.canUseName(p, Permissions.OTHER_NAME, "Server")).isTrue();
    }

    @Test
    void canUseName_adminShop_deniedWithoutPermission() {
        try (MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            Player p = player("Notch", UUID.randomUUID());
            when(signService.isAdminShop("Server")).thenReturn(true);
            when(adminBypass.has(p, Permissions.ADMIN_SHOP)).thenReturn(false);
            assertThat(service.canUseName(p, Permissions.OTHER_NAME, "Server")).isFalse();
        }
    }

    @Test
    void canUseName_businessAccount_allowedForChestShopAdmin() {
        Player p = player("Notch", UUID.randomUUID());
        when(signService.isAdminShop("B:5")).thenReturn(false);
        when(adminBypass.has(p, Permissions.ADMIN)).thenReturn(true);
        assertThat(service.canUseName(p, Permissions.OTHER_NAME, "B:5")).isTrue();
    }

    @Test
    void canUseName_businessAccount_nonAdmin_delegatesToAccessCheck() {
        UUID id = UUID.randomUUID();
        Player p = player("Notch", id);
        when(signService.isAdminShop("B:5")).thenReturn(false);
        when(adminBypass.has(p, Permissions.ADMIN)).thenReturn(false);
        Account biz = new Account("B:5", "B:5", new UUID(1, 5));
        business.resolve = name -> biz;
        business.canAccess = (pl, ac) -> true; // firm membership grants access
        assertThat(service.canUseName(p, Permissions.OTHER_NAME, "B:5")).isTrue();
    }

    @Test
    void canUseName_personalName_grantedByOtherNamePermission() {
        Player p = player("Notch", UUID.randomUUID());
        when(signService.isAdminShop("Alice")).thenReturn(false);
        when(adminBypass.otherName(p, Permissions.OTHER_NAME, "Alice")).thenReturn(true);
        assertThat(service.canUseName(p, Permissions.OTHER_NAME, "Alice")).isTrue();
    }

    @Test
    void canUseName_ownName_allowedWhenNoAccountExists() {
        Player p = player("Notch", UUID.randomUUID());
        when(signService.isAdminShop("Notch")).thenReturn(false);
        when(adminBypass.otherName(p, Permissions.OTHER_NAME, "Notch")).thenReturn(false);
        when(mapper.findByShortName("Notch")).thenReturn(null);
        when(mapper.findLatestByName("Notch")).thenReturn(null);
        assertThat(service.canUseName(p, Permissions.OTHER_NAME, "Notch")).isTrue();
    }

    @Test
    void canUseName_unknownOtherName_denied() {
        try (MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            Player p = player("Notch", UUID.randomUUID());
            when(signService.isAdminShop("Alice")).thenReturn(false);
            when(adminBypass.otherName(p, Permissions.OTHER_NAME, "Alice")).thenReturn(false);
            when(mapper.findByShortName("Alice")).thenReturn(null);
            when(mapper.findLatestByName("Alice")).thenReturn(null);
            assertThat(service.canUseName(p, Permissions.OTHER_NAME, "Alice")).isFalse();
        }
    }

    @Test
    void canUseName_resolvedNameDiffersFromRequested_grantsViaOtherName() {
        // Sign name "Bob" resolves (via short-name) to an account whose canonical name is "Alice".
        // account.getName() ("Alice") differs from requested "Bob", and the player holds the
        // othername permission for "Alice" → granted through that late otherName check.
        UUID id = UUID.randomUUID();
        UUID accId = UUID.randomUUID();
        Player p = player("Notch", id);
        Account acc = new Account("Alice", "Bob", accId);
        when(signService.isAdminShop("Bob")).thenReturn(false);
        when(adminBypass.otherName(p, Permissions.OTHER_NAME, "Bob")).thenReturn(false);
        when(mapper.findByShortName("Bob")).thenReturn(acc);
        when(mapper.findLatestByUuid(accId)).thenReturn(acc);
        when(adminBypass.otherName(p, Permissions.OTHER_NAME, "Alice")).thenReturn(true);
        assertThat(service.canUseName(p, Permissions.OTHER_NAME, "Bob")).isTrue();
    }

    @Test
    void canUseName_ownAccount_grantedByUuidMatch() {
        UUID id = UUID.randomUUID();
        Player p = player("Notch", id);
        Account acc = new Account("Notch", "Notch", id);
        when(signService.isAdminShop("Notch")).thenReturn(false);
        when(adminBypass.otherName(p, Permissions.OTHER_NAME, "Notch")).thenReturn(false);
        when(mapper.findByShortName("Notch")).thenReturn(acc);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.canUseName(p, Permissions.OTHER_NAME, "Notch")).isTrue();
    }

    @Test
    void canAccess_deniedWhenUuidMismatchAndNotFirmMember() {
        try (MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            Player p = player("Notch", UUID.randomUUID());
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            business.canAccess = (pl, ac) -> false;
            assertThat(service.canAccess(p, acc)).isFalse();
        }
    }

    @Test
    void canAccess_grantedByFirmMembership() {
        Player p = player("Notch", UUID.randomUUID());
        Account acc = new Account("B:5", "B:5", new UUID(1, 5));
        business.canAccess = (pl, ac) -> true;
        assertThat(service.canAccess(p, acc)).isTrue();
    }

    // ═══════════════════ hasPermission / isOwner / canAccess(sign) ═══════════════════

    @Test
    void hasPermission_nullPlayer_false() {
        assertThat(service.hasPermission(null, Permissions.OTHER_NAME, signOwned("Notch"))).isFalse();
    }

    @Test
    void hasPermission_nullSign_true() {
        assertThat(service.hasPermission(player("Notch", UUID.randomUUID()), Permissions.OTHER_NAME, null)).isTrue();
    }

    @Test
    void hasPermission_emptyOwner_true() {
        Player p = player("Notch", UUID.randomUUID());
        assertThat(service.hasPermission(p, Permissions.OTHER_NAME, signOwned(""))).isTrue();
    }

    @Test
    void hasPermission_delegatesToCanUseName() {
        Player p = player("Notch", UUID.randomUUID());
        Account acc = new Account("Notch", "Notch", p.getUniqueId());
        when(signService.isAdminShop("Notch")).thenReturn(false);
        when(adminBypass.otherName(eq(p), anyString(), eq("Notch"))).thenReturn(false);
        when(mapper.findByShortName("Notch")).thenReturn(acc);
        when(mapper.findLatestByUuid(p.getUniqueId())).thenReturn(acc);
        assertThat(service.canAccess(p, signOwned("Notch"))).isTrue();
    }

    @Test
    void isOwner_nullArgs_false() {
        assertThat(service.isOwner(null, signOwned("Notch"))).isFalse();
        assertThat(service.isOwner(player("Notch", UUID.randomUUID()), null)).isFalse();
    }

    @Test
    void isOwner_emptyOwnerLine_false() {
        assertThat(service.isOwner(player("Notch", UUID.randomUUID()), signOwned(""))).isFalse();
    }

    @Test
    void isOwner_noAccount_matchesByName() {
        Player p = player("Notch", UUID.randomUUID());
        when(mapper.findByShortName("Notch")).thenReturn(null);
        when(mapper.findLatestByName("Notch")).thenReturn(null);
        assertThat(service.isOwner(p, signOwned("Notch"))).isTrue();
    }

    @Test
    void isOwner_businessAccount_delegatesToAccess() {
        Player p = player("Notch", UUID.randomUUID());
        Account biz = new Account("B:5", "B:5", new UUID(1, 5));
        business.resolve = name -> biz;
        business.canAccess = (pl, ac) -> true;
        assertThat(service.isOwner(p, signOwned("B:5"))).isTrue();
    }

    @Test
    void isOwner_personalAccount_matchesByUuid() {
        UUID id = UUID.randomUUID();
        Player p = player("Notch", id);
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findByShortName("Notch")).thenReturn(acc);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.isOwner(p, signOwned("Notch"))).isTrue();
    }

    // ═══════════════════ isIgnoring / isAdminShop / isServerEconomyAccount ═══════════════════

    @Test
    void isIgnoring_offlinePlayer_nullIsFalse() {
        assertThat(service.isIgnoring((OfflinePlayer) null)).isFalse();
    }

    @Test
    void isIgnoring_offlinePlayer_readsAccountFlag() {
        UUID id = UUID.randomUUID();
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Notch");
        when(op.getUniqueId()).thenReturn(id);
        Account acc = new Account("Notch", "Notch", id);
        acc.setIgnoreMessages(true);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.isIgnoring(op)).isTrue();
    }

    @Test
    void isIgnoring_uuid_falseWhenNoAccount() {
        UUID id = UUID.randomUUID();
        when(mapper.findLatestByUuid(id)).thenReturn(null);
        assertThat(service.isIgnoring(id)).isFalse();
    }

    @Test
    void isIgnoring_uuid_readsAccountFlag() {
        UUID id = UUID.randomUUID();
        Account acc = new Account("Notch", "Notch", id);
        acc.setIgnoreMessages(true);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.isIgnoring(id)).isTrue();
    }

    @Test
    void isAdminShop_falseWhenUnset_trueWhenMatches() {
        UUID id = UUID.randomUUID();
        assertThat(service.isAdminShop(id)).isFalse(); // adminAccount not loaded
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(id);
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);
            service.load();
        }
        assertThat(service.isAdminShop(id)).isTrue();
        assertThat(service.isAdminShop(UUID.randomUUID())).isFalse();
    }

    @Test
    void isServerEconomyAccount_falseWhenUnset() {
        assertThat(service.isServerEconomyAccount(UUID.randomUUID())).isFalse();
    }

    // ═══════════════════ load() ═══════════════════

    @Test
    void load_setsUuidVersionFromOnlineMode_andAdminAccount() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);

            service.load();

            assertThat(service.getUuidVersion()).isEqualTo(4);
            assertThat(service.getServerEconomyAccount()).isNull();
        }
    }

    @Test
    void load_derivesUuidVersionFromOnlinePlayer_whenOffline() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(false);
            Player online = player("Someone", UUID.randomUUID()); // random UUID is version 4
            bk.when(Bukkit::getOnlinePlayers).thenAnswer(i -> Collections.singletonList(online));
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);

            service.load();

            assertThat(service.getUuidVersion()).isEqualTo(4);
        }
    }

    @Test
    void load_leavesUuidVersionUnset_whenOfflineWithNoPlayers() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(false);
            bk.when(Bukkit::getOnlinePlayers).thenAnswer(i -> Collections.emptyList());
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);

            service.load();

            assertThat(service.getUuidVersion()).isEqualTo(-1);
        }
    }

    @Test
    void load_fallsBackToOfflineUuid_whenMojangRatelimited() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class);
             MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            // getOfflinePlayer returns null → NPE on getUniqueId() → fallback path.
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(null);
            when(mapper.findByShortName(anyString())).thenReturn(null);

            service.load();

            // Admin account still established via the deterministic offline UUID.
            UUID expected = UUID.nameUUIDFromBytes("OfflinePlayer:Server".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(service.isAdminShop(expected)).isTrue();
        }
    }

    @Test
    void load_resolvesServerEconomyAccount_byName() {
        when(config.getServerEconomyAccount()).thenReturn("Bank");
        UUID bankId = UUID.randomUUID();
        Account bank = new Account("Bank", "Bank", bankId);
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);
            when(mapper.findLatestByName("Bank")).thenReturn(bank);
            when(mapper.findLatestByUuid(bankId)).thenReturn(bank);

            service.load();

            assertThat(service.getServerEconomyAccount()).isSameAs(bank);
            assertThat(service.isServerEconomyAccount(bankId)).isTrue();
        }
    }

    @Test
    void load_createsServerEconomyAccount_fromConfiguredUuid() {
        UUID bankId = UUID.randomUUID();
        when(config.getServerEconomyAccount()).thenReturn("Bank");
        when(config.getServerEconomyAccountUuid()).thenReturn(bankId);
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);
            when(mapper.findLatestByName("Bank")).thenReturn(null); // not found by name
            when(mapper.findByUuidAndName(bankId, "Bank")).thenReturn(null);

            service.load();

            assertThat(service.getServerEconomyAccount()).isNotNull();
            assertThat(service.getServerEconomyAccount().getUuid()).isEqualTo(bankId);
        }
    }

    @Test
    void load_warnsAndLeavesNull_whenServerEconomyAccountUnresolvable() {
        when(config.getServerEconomyAccount()).thenReturn("Bank");
        when(config.getServerEconomyAccountUuid()).thenReturn(new UUID(0, 0)); // no uuid to create from
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);
            when(mapper.findLatestByName("Bank")).thenReturn(null);

            service.load();

            assertThat(service.getServerEconomyAccount()).isNull();
        }
    }

    @Test
    void load_logsError_onPersistenceFailure() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            // storeAccount(adminAccount) → mapper.save throws → outer catch logs, no throw.
            org.mockito.Mockito.doThrow(new PersistenceException("db")).when(mapper).save(any());

            service.load(); // must not throw

            assertThat(service.getServerEconomyAccount()).isNull();
        }
    }

    // ═══════════════════ setUuidVersion / getUuidVersion ═══════════════════

    @Test
    void uuidVersion_roundTrips() {
        service.setUuidVersion(3);
        assertThat(service.getUuidVersion()).isEqualTo(3);
    }

    // ═══════════════════ getOrCreateAccount(OfflinePlayer) argument-check arcs ═══════════════════

    @Test
    void getOrCreateAccount_playerInstance_shortCircuitsVersionCheck() {
        // A live Player instance always passes (the first || operand is true) even with
        // ensure-correct-playerid on and a mismatched version.
        UUID id = new UUID(0, 0); // version 0
        Player p = player("Notch", id);
        service.setUuidVersion(4);
        when(config.isEnsureCorrectPlayerid()).thenReturn(true);
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.getOrCreateAccount(p)).isSameAs(acc);
    }

    @Test
    void getOrCreateAccount_offlinePlayer_passesWhenUuidVersionUnset() {
        // uuidVersion < 0 (default -1) → the version rule is not enforced.
        when(config.isEnsureCorrectPlayerid()).thenReturn(true);
        UUID id = new UUID(0, 0);
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Notch");
        when(op.getUniqueId()).thenReturn(id);
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.getOrCreateAccount(op)).isSameAs(acc);
    }

    @Test
    void getOrCreateAccount_offlinePlayer_passesWhenVersionMatches() {
        when(config.isEnsureCorrectPlayerid()).thenReturn(true);
        service.setUuidVersion(4);
        UUID id = UUID.randomUUID(); // version 4
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Notch");
        when(op.getUniqueId()).thenReturn(id);
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.getOrCreateAccount(op)).isSameAs(acc);
    }

    // ═══════════════════ remaining single-arc branches ═══════════════════

    @Test
    void canUseName_resolvedNameDiffers_otherNameDenied_fallsToAccessCheck() {
        UUID accId = UUID.randomUUID();
        Player p = player("Notch", UUID.randomUUID());
        Account acc = new Account("Alice", "Bob", accId);
        when(signService.isAdminShop("Bob")).thenReturn(false);
        when(adminBypass.otherName(p, Permissions.OTHER_NAME, "Bob")).thenReturn(false);
        when(mapper.findByShortName("Bob")).thenReturn(acc);
        when(mapper.findLatestByUuid(accId)).thenReturn(acc);
        when(adminBypass.otherName(p, Permissions.OTHER_NAME, "Alice")).thenReturn(false); // late otherName denied
        business.canAccess = (pl, ac) -> true; // but firm membership grants access
        assertThat(service.canUseName(p, Permissions.OTHER_NAME, "Bob")).isTrue();
    }

    @Test
    void hasPermission_nullOwnerLine_true() {
        Sign s = mock(Sign.class);
        when(s.getLines()).thenReturn(new String[]{null, "", "", ""}); // getOwner → null
        assertThat(service.hasPermission(player("Notch", UUID.randomUUID()), Permissions.OTHER_NAME, s)).isTrue();
    }

    @Test
    void isOwner_nullOwnerLine_false() {
        Sign s = mock(Sign.class);
        when(s.getLines()).thenReturn(new String[]{null, "", "", ""}); // getOwner → null
        assertThat(service.isOwner(player("Notch", UUID.randomUUID()), s)).isFalse();
    }

    @Test
    void isIgnoring_offlinePlayer_falseWhenFlagUnset() {
        UUID id = UUID.randomUUID();
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Notch");
        when(op.getUniqueId()).thenReturn(id);
        Account acc = new Account("Notch", "Notch", id); // ignoreMessages defaults false
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.isIgnoring(op)).isFalse();
    }

    @Test
    void isIgnoring_uuid_falseWhenFlagUnset() {
        UUID id = UUID.randomUUID();
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        assertThat(service.isIgnoring(id)).isFalse();
    }

    @Test
    void load_skipsUuidVersionDetection_whenAlreadySet() {
        service.setUuidVersion(2); // already set → the < 0 block is skipped
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);

            service.load();

            assertThat(service.getUuidVersion()).isEqualTo(2);
            bk.verify(Bukkit::getOnlineMode, org.mockito.Mockito.never());
        }
    }

    @Test
    void isServerEconomyAccount_falseForDifferentUuid_whenSet() {
        when(config.getServerEconomyAccount()).thenReturn("Bank");
        UUID bankId = UUID.randomUUID();
        Account bank = new Account("Bank", "Bank", bankId);
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);
            when(mapper.findLatestByName("Bank")).thenReturn(bank);
            when(mapper.findLatestByUuid(bankId)).thenReturn(bank);
            service.load();
        }
        assertThat(service.isServerEconomyAccount(UUID.randomUUID())).isFalse();
    }

    @Test
    void load_clearsServerEconomyAccount_whenResolvedAccountHasNullUuid() {
        when(config.getServerEconomyAccount()).thenReturn("Bank");
        Account bankNoUuid = new Account("Bank", "Bank", null); // getUuid() == null
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            bk.when(Bukkit::getOnlineMode).thenReturn(true);
            OfflinePlayer op = mock(OfflinePlayer.class);
            when(op.getUniqueId()).thenReturn(UUID.randomUUID());
            bk.when(() -> Bukkit.getOfflinePlayer("Server")).thenReturn(op);
            when(mapper.findByShortName(anyString())).thenReturn(null);
            when(mapper.findLatestByName("Bank")).thenReturn(bankNoUuid);

            service.load();

            assertThat(service.getServerEconomyAccount()).isNull();
        }
    }

    // ═══════════════════ getLastAccountFromName offline-player block (white-box) ═══════════════════
    // Only reachable via searchOfflinePlayer=true, which no public caller currently passes
    // (resolveAccount always passes false). Exercised directly to cover the historical
    // offline-lookup path retained from the NameManager split.

    private Account invokeGetLast(String name, boolean searchOffline) throws Throwable {
        var m = AccountServiceImpl.class.getDeclaredMethod("getLastAccountFromName", String.class, boolean.class);
        m.setAccessible(true);
        try {
            return (Account) m.invoke(service, name, searchOffline);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void withOfflineServer(OfflinePlayer op, ThrowingRunnable body) throws Throwable {
        try (MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            org.bukkit.Server server = mock(org.bukkit.Server.class);
            lenient().when(server.getOfflinePlayer(anyString())).thenReturn(op);
            cs.when(ChestShop::getBukkitServer).thenReturn(server);
            body.run();
        }
    }

    private interface ThrowingRunnable { void run() throws Throwable; }

    @Test
    void getLastAccountFromName_offline_resolvesValidPlayer() throws Throwable {
        UUID id = UUID.randomUUID();
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Notch");
        when(op.getUniqueId()).thenReturn(id);
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findByShortName("Notch")).thenReturn(null);
        when(mapper.findLatestByName("Notch")).thenReturn(null);
        when(mapper.findByUuidAndName(id, "Notch")).thenReturn(acc);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        withOfflineServer(op, () -> assertThat(invokeGetLast("Notch", true)).isSameAs(acc));
    }

    @Test
    void getLastAccountFromName_offline_marksInvalid_whenNoOfflinePlayer() throws Throwable {
        when(mapper.findByShortName("Ghost")).thenReturn(null);
        when(mapper.findLatestByName("Ghost")).thenReturn(null);
        withOfflineServer(null, () -> assertThat(invokeGetLast("Ghost", true)).isNull());
    }

    @Test
    void getLastAccountFromName_offline_marksInvalid_whenNameOrUuidMissing() throws Throwable {
        OfflinePlayer noName = mock(OfflinePlayer.class);
        when(noName.getName()).thenReturn(null);
        withOfflineServer(noName, () -> assertThat(invokeGetLast("Ghost1", true)).isNull());

        OfflinePlayer noUuid = mock(OfflinePlayer.class);
        when(noUuid.getName()).thenReturn("Ghost2");
        when(noUuid.getUniqueId()).thenReturn(null);
        withOfflineServer(noUuid, () -> assertThat(invokeGetLast("Ghost2", true)).isNull());
    }

    @Test
    void getLastAccountFromName_offline_skipsWhenAlreadyMarkedInvalid() throws Throwable {
        // First call marks "Ghost" invalid; a second call short-circuits on the invalidPlayers cache.
        withOfflineServer(null, () -> {
            invokeGetLast("Ghost", true);
            assertThat(invokeGetLast("Ghost", true)).isNull(); // contains(name) → block skipped
        });
    }

    @Test
    void getLastAccountFromName_offline_rejectsWrongUuidVersion() throws Throwable {
        service.setUuidVersion(4);
        when(config.isEnsureCorrectPlayerid()).thenReturn(true);
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Legacy");
        when(op.getUniqueId()).thenReturn(new UUID(0, 0)); // version 0 != 4 → marked invalid
        when(mapper.findByShortName("Legacy")).thenReturn(null);
        when(mapper.findLatestByName("Legacy")).thenReturn(null);
        withOfflineServer(op, () -> assertThat(invokeGetLast("Legacy", true)).isNull());
    }

    @Test
    void getLastAccountFromName_offline_acceptsMatchingUuidVersion() throws Throwable {
        service.setUuidVersion(4);
        when(config.isEnsureCorrectPlayerid()).thenReturn(true);
        UUID id = UUID.randomUUID(); // version 4 → matches
        OfflinePlayer op = mock(OfflinePlayer.class);
        when(op.getName()).thenReturn("Notch");
        when(op.getUniqueId()).thenReturn(id);
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findByShortName("Notch")).thenReturn(null);
        when(mapper.findLatestByName("Notch")).thenReturn(null);
        when(mapper.findByUuidAndName(id, "Notch")).thenReturn(acc);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        withOfflineServer(op, () -> assertThat(invokeGetLast("Notch", true)).isSameAs(acc));
    }

    @Test
    void getLastAccountFromName_offline_notReached_whenShortNameResolves() throws Throwable {
        // account found by short name → the account==null guard short-circuits, offline block skipped.
        UUID id = UUID.randomUUID();
        Account acc = new Account("Notch", "Notch", id);
        when(mapper.findByShortName("Notch")).thenReturn(acc);
        when(mapper.findLatestByUuid(id)).thenReturn(acc);
        withOfflineServer(null, () -> assertThat(invokeGetLast("Notch", true)).isSameAs(acc));
    }
    /**
     * Hand-written {@link io.paradaux.chestshop.services.BusinessAccountService} double — see the
     * FakeEconomy pattern for why it's a private, hand-written class rather than a Mockito mock.
     */
    private static final class FakeBusinessAccounts implements io.paradaux.chestshop.services.BusinessAccountService {
        java.util.function.Function<String, io.paradaux.chestshop.model.Account> resolve = name -> null;
        java.util.function.BiPredicate<org.bukkit.entity.Player, io.paradaux.chestshop.model.Account> canAccess = (p, a) -> false;
        @Override public void bind(io.paradaux.treasury.api.TreasuryApi treasury, io.paradaux.business.api.BusinessApi businessApi) { /* unused headless */ }
        @Override public io.paradaux.chestshop.model.Account resolveBusinessAccount(String name) { return resolve.apply(name); }
        @Override public boolean canAccessBusinessAccount(org.bukkit.entity.Player player, io.paradaux.chestshop.model.Account account) { return canAccess.test(player, account); }
    }
}
