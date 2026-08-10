package io.paradaux.chestshop.services.impl;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.api.FirmApi;
import io.paradaux.business.api.StaffApi;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.RolePermission;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import io.paradaux.treasury.api.TreasuryApi;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Read-only firm-account resolution and access checks over mocked Treasury/Business APIs — the
 * {@code B:<base36>} native form, the legacy firm-name form, and the permission fallbacks.
 */
class BusinessAccountServiceImplTest {

    private TreasuryApi treasury;
    private BusinessApi business;
    private FirmApi firms;
    private StaffApi staff;
    private BusinessAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        treasury = mock(TreasuryApi.class);
        business = mock(BusinessApi.class);
        firms = mock(FirmApi.class);
        staff = mock(StaffApi.class);
        lenient().when(business.firms()).thenReturn(firms);
        lenient().when(business.staff()).thenReturn(staff);
        service = new BusinessAccountServiceImpl();
    }

    private static io.paradaux.treasury.model.economy.Account treasuryAccount(int id, String display) {
        io.paradaux.treasury.model.economy.Account a = new io.paradaux.treasury.model.economy.Account();
        a.setAccountId(id);
        a.setDisplayName(display);
        return a;
    }

    // ── resolveBusinessAccount ────────────────────────────────────────────────

    @Test
    void resolve_rejectsNullShortAndNonPrefixedNames() {
        service.bind(treasury, business);
        assertThat(service.resolveBusinessAccount(null)).isNull();
        assertThat(service.resolveBusinessAccount("B:")).isNull();      // too short (< 3)
        assertThat(service.resolveBusinessAccount("XYZ")).isNull();     // no "B:" prefix
    }

    @Test
    void resolve_nativeBase36Id() {
        service.bind(treasury, business);
        when(treasury.getAccountById(46)).thenReturn(treasuryAccount(46, "Acme Corp")); // 1A base36 = 46

        Account account = service.resolveBusinessAccount("B:1A");

        assertThat(account).isNotNull();
        assertThat(account.getName()).isEqualTo("Acme Corp");
        assertThat(account.getShortName()).isEqualTo("B:1A");
        assertThat(account.getUuid()).isEqualTo(BusinessAccountUtil.toBusinessUuid(46));
    }

    @Test
    void resolve_legacyFirmName_fallsThroughToNameLookup() {
        service.bind(treasury, business);
        // "My Shop" is not base-36 (space) -> NumberFormatException -> firm-name lookup.
        Firm firm = mock(Firm.class);
        when(firm.getDefaultAccountId()).thenReturn(7);
        when(firms.getFirm("My Shop")).thenReturn(firm);
        when(treasury.getAccountById(7)).thenReturn(treasuryAccount(7, "My Shop Ltd"));

        Account account = service.resolveBusinessAccount("B:My Shop");

        assertThat(account).isNotNull();
        assertThat(account.getName()).isEqualTo("My Shop Ltd");
        assertThat(account.getUuid()).isEqualTo(BusinessAccountUtil.toBusinessUuid(7));
    }

    @Test
    void resolve_base36IdMissing_fallsBackToFirmLookup() {
        service.bind(treasury, business);
        when(treasury.getAccountById(46)).thenReturn(null); // no such treasury account for the id
        Firm firm = mock(Firm.class);
        when(firm.getDefaultAccountId()).thenReturn(99);
        when(firms.getFirm("1A")).thenReturn(firm);
        when(treasury.getAccountById(99)).thenReturn(treasuryAccount(99, "Recovered"));

        Account account = service.resolveBusinessAccount("B:1A");

        assertThat(account).isNotNull();
        assertThat(account.getName()).isEqualTo("Recovered");
    }

    @Test
    void resolve_firmWithoutDefaultAccount_yieldsNull() {
        service.bind(treasury, business);
        when(treasury.getAccountById(anyInt())).thenReturn(null);
        Firm firm = mock(Firm.class);
        when(firm.getDefaultAccountId()).thenReturn(null); // firm exists but has no default account
        when(firms.getFirm("1A")).thenReturn(firm);

        assertThat(service.resolveBusinessAccount("B:1A")).isNull();
    }

    @Test
    void resolve_base36IdMissing_andNoSuchFirm_yieldsNull() {
        service.bind(treasury, business);
        when(treasury.getAccountById(46)).thenReturn(null);
        when(firms.getFirm("1A")).thenReturn(null); // firm lookup misses too

        assertThat(service.resolveBusinessAccount("B:1A")).isNull();
    }

    @Test
    void resolve_noBusinessApi_andNoTreasuryAccount_yieldsNull() {
        service.bind(treasury, null);
        when(treasury.getAccountById(46)).thenReturn(null);

        assertThat(service.resolveBusinessAccount("B:1A")).isNull();
    }

    @Test
    void resolve_swallowsUnexpectedErrors() {
        service.bind(treasury, business);
        when(treasury.getAccountById(46)).thenThrow(new RuntimeException("db down"));

        assertThat(service.resolveBusinessAccount("B:1A")).isNull();
    }

    // ── canAccessBusinessAccount ──────────────────────────────────────────────

    private Player player(UUID id) {
        Player p = mock(Player.class);
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getName()).thenReturn("player");
        return p;
    }

    private static Account businessAccount(int accountId) {
        return new Account("Firm", "B:" + Integer.toString(accountId, 36).toUpperCase(),
                BusinessAccountUtil.toBusinessUuid(accountId));
    }

    @Test
    void access_rejectsNullOrNonBusinessAccounts() {
        service.bind(treasury, business);
        Player p = player(UUID.randomUUID());

        assertThat(service.canAccessBusinessAccount(p, null)).isFalse();
        assertThat(service.canAccessBusinessAccount(p, new Account("x", null, UUID.randomUUID()))).isFalse();
        assertThat(service.canAccessBusinessAccount(p, new Account("x", "P:1", UUID.randomUUID()))).isFalse();
        // "B:" short name but not a synthetic business UUID.
        assertThat(service.canAccessBusinessAccount(p, new Account("x", "B:1", UUID.randomUUID()))).isFalse();
    }

    @Test
    void access_grantedByFirmStaffPermission() {
        service.bind(treasury, business);
        UUID pid = UUID.randomUUID();
        when(staff.hasPermissionForAccount(eq(5), eq(pid), eq(RolePermission.CHESTSHOP))).thenReturn(true);

        assertThat(service.canAccessBusinessAccount(player(pid), businessAccount(5))).isTrue();
    }

    @Test
    void access_grantedWhenNoLiveFirmOwnsTheAccount() {
        service.bind(treasury, business);
        UUID pid = UUID.randomUUID();
        when(staff.hasPermissionForAccount(anyInt(), any(), any())).thenReturn(false);
        when(firms.getFirmByAccountId(5)).thenReturn(null); // disbanded -> left accessible

        assertThat(service.canAccessBusinessAccount(player(pid), businessAccount(5))).isTrue();
    }

    @Test
    void access_deniedWhenFirmOwnsItButNoPermission() {
        service.bind(treasury, business);
        UUID pid = UUID.randomUUID();
        when(staff.hasPermissionForAccount(anyInt(), any(), any())).thenReturn(false);
        when(firms.getFirmByAccountId(5)).thenReturn(mock(Firm.class));

        assertThat(service.canAccessBusinessAccount(player(pid), businessAccount(5))).isFalse();
    }

    @Test
    void access_fallsBackToTreasuryMembershipWhenBusinessAbsent() {
        service.bind(treasury, null);
        UUID pid = UUID.randomUUID();

        // member -> true (short-circuits the owner check)
        when(treasury.isAccountMember(pid, 5)).thenReturn(true);
        assertThat(service.canAccessBusinessAccount(player(pid), businessAccount(5))).isTrue();

        // not member but owner -> true
        when(treasury.isAccountMember(pid, 5)).thenReturn(false);
        when(treasury.isOwnerForAccountId(pid, 5)).thenReturn(true);
        assertThat(service.canAccessBusinessAccount(player(pid), businessAccount(5))).isTrue();

        // neither -> false
        when(treasury.isOwnerForAccountId(pid, 5)).thenReturn(false);
        assertThat(service.canAccessBusinessAccount(player(pid), businessAccount(5))).isFalse();
    }

    @Test
    void access_swallowsUnexpectedErrors() {
        service.bind(treasury, business);
        when(business.staff()).thenThrow(new RuntimeException("boom"));

        assertThat(service.canAccessBusinessAccount(player(UUID.randomUUID()), businessAccount(5))).isFalse();
    }
}
