package io.paradaux.chestshop.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.AccountType;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Government shop owners (PAR-529): G:<name> / G:#<base36> resolution and per-account permissions. */
class GovernmentAccountServiceImplTest {

    private TreasuryApi treasury;
    private GovernmentAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        treasury = mock(TreasuryApi.class);
        service = new GovernmentAccountServiceImpl();
        service.bind(treasury);
    }

    private static io.paradaux.treasury.model.economy.Account account(int id, String name, AccountType type) {
        io.paradaux.treasury.model.economy.Account a = new io.paradaux.treasury.model.economy.Account();
        a.setAccountId(id);
        a.setDisplayName(name);
        a.setAccountType(type);
        return a;
    }

    // ── resolveGovernmentAccount ──────────────────────────────────────────────

    @Test
    void resolve_byNameKeepsCanonicalNameOnTheSign() {
        when(treasury.getGovernmentAccountByName("govjustice"))
                .thenReturn(account(29862, "GovJustice", AccountType.GOVERNMENT));

        Account resolved = service.resolveGovernmentAccount("g:govjustice");

        assertThat(resolved.getName()).isEqualTo("GovJustice");
        assertThat(resolved.getShortName()).isEqualTo("G:GovJustice");
        assertThat(resolved.getUuid()).isEqualTo(BusinessAccountUtil.toBusinessUuid(29862));
    }

    @Test
    void resolve_byBase36IdForNamesTooWideForTheSign() {
        // DCGovernmentJUD: "G:" + 15 characters is wider than a sign line.
        int id = 99393;
        when(treasury.getAccountById(id)).thenReturn(account(id, "DCGovernmentJUD", AccountType.GOVERNMENT));

        Account resolved = service.resolveGovernmentAccount("G:#" + Integer.toString(id, 36));

        assertThat(resolved.getName()).isEqualTo("DCGovernmentJUD");
        assertThat(resolved.getShortName())
                .isEqualTo("G:#" + Integer.toString(id, 36).toUpperCase(java.util.Locale.ROOT));
        assertThat(resolved.getUuid()).isEqualTo(BusinessAccountUtil.toBusinessUuid(id));
    }

    @Test
    void resolve_byIdOfShortNameRewritesToTheReadableForm() {
        when(treasury.getAccountById(29862)).thenReturn(account(29862, "GovJustice", AccountType.GOVERNMENT));

        assertThat(service.resolveGovernmentAccount("G:#" + Integer.toString(29862, 36))
                        .getShortName())
                .isEqualTo("G:GovJustice");
    }

    @ParameterizedTest
    @EnumSource(
            value = AccountType.class,
            names = {"PERSONAL", "BUSINESS", "SYSTEM"})
    void resolve_idFormRefusesNonGovernmentAccounts(AccountType type) {
        when(treasury.getAccountById(5)).thenReturn(account(5, "Someone", type));

        assertThat(service.resolveGovernmentAccount("G:#5")).isNull();
    }

    @Test
    void resolve_refusesArchivedAccounts() {
        var archived = account(136699, "DCGovernmentWL", AccountType.GOVERNMENT);
        archived.setArchived(true);
        when(treasury.getGovernmentAccountByName("DCGovernmentWL")).thenReturn(archived);

        assertThat(service.resolveGovernmentAccount("G:DCGovernmentWL")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"G:", "G:#", "G:#not-base36", "B:1A", "GovJustice"})
    void resolve_rejectsMalformedOrForeignTokens(String token) {
        lenient()
                .when(treasury.getGovernmentAccountByName(anyString()))
                .thenReturn(account(1, "x", AccountType.GOVERNMENT));
        lenient().when(treasury.getAccountById(anyInt())).thenReturn(account(1, "x", AccountType.GOVERNMENT));

        assertThat(service.resolveGovernmentAccount(token)).isNull();
    }

    @Test
    void resolve_unknownNameOrUnboundTreasuryYieldsNull() {
        assertThat(service.resolveGovernmentAccount("G:Nowhere")).isNull();
        GovernmentAccountServiceImpl unbound = new GovernmentAccountServiceImpl();
        assertThat(unbound.resolveGovernmentAccount("G:GovJustice")).isNull();
    }

    @Test
    void resolve_swallowsTreasuryErrors() {
        when(treasury.getGovernmentAccountByName("GovJustice")).thenThrow(new IllegalStateException("db down"));

        assertThat(service.resolveGovernmentAccount("G:GovJustice")).isNull();
    }

    // ── canAccessGovernmentAccount ────────────────────────────────────────────

    private static Player player() {
        Player p = mock(Player.class);
        lenient().when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        return p;
    }

    private static Account justice() {
        return new Account("GovJustice", "G:GovJustice", BusinessAccountUtil.toBusinessUuid(29862));
    }

    @Test
    void access_grantedByThatAccountsNodeOnly() {
        Player p = player();
        assertThat(service.canAccessGovernmentAccount(p, justice())).isFalse();

        when(p.hasPermission("chestshop.government.govhealth")).thenReturn(true);
        assertThat(service.canAccessGovernmentAccount(p, justice())).isFalse();

        when(p.hasPermission("chestshop.government.govjustice")).thenReturn(true);
        assertThat(service.canAccessGovernmentAccount(p, justice())).isTrue();
    }

    @Test
    void access_wildcardGrantsUnlessTheAccountIsSetFalse() {
        Player p = player();
        when(p.hasPermission("ChestShop.government.*")).thenReturn(true);
        assertThat(service.canAccessGovernmentAccount(p, justice())).isTrue();

        when(p.isPermissionSet("chestshop.government.govjustice")).thenReturn(true);
        assertThat(service.canAccessGovernmentAccount(p, justice())).isFalse();
    }

    @Test
    void access_rejectsNonGovernmentAccounts() {
        Player p = player();
        when(p.hasPermission("ChestShop.government.*")).thenReturn(true);

        assertThat(service.canAccessGovernmentAccount(p, null)).isFalse();
        assertThat(service.canAccessGovernmentAccount(
                        p, new Account("Acme", "B:1A", BusinessAccountUtil.toBusinessUuid(46))))
                .isFalse();
        // A G: short name on a real player UUID is not a government account.
        assertThat(service.canAccessGovernmentAccount(p, new Account("GovJustice", "G:GovJustice", UUID.randomUUID())))
                .isFalse();
    }
}
