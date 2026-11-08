package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.common.UuidBin;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.services.AccountResolutionAuditService;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountResolutionAuditIT extends IntegrationTestBase {
    @Test
    void auditTracksCaseInsensitiveCollisionsRenamesAndArchivalWithoutRequiringPersonalAccount() throws Exception {
        var accounts = injector.getInstance(AccountService.class);
        var directory = injector.getInstance(PlayerDirectoryService.class);
        var audit = injector.getInstance(AccountResolutionAuditService.class);
        UUID player = UUID.randomUUID();
        var government = accounts.createAccount(AccountType.GOVERNMENT, UUID.randomUUID(), "GovTest");
        directory.recordLogin(player, "govTEST", 1L);

        assertThat(accounts.hasGovernmentNameCollision(player)).isTrue();
        var collision = audit.findCollisions().getFirst();
        assertThat(collision.getGovernmentId()).isEqualTo(government.getAccountId());
        assertThat(collision.getGovernmentName()).isEqualTo("GovTest");
        assertThat(collision.getPlayerUuid()).isEqualTo(player);
        assertThat(collision.getPersonalId()).isNull();
        assertThat(collision.getRedirectId()).isNull();
        int personal = accounts.getOrCreatePersonalAccountId(player);
        assertThat(audit.findCollisions())
                .singleElement()
                .satisfies(row -> assertThat(row.getPersonalId()).isEqualTo(personal));

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO account_redirects (redirect_uuid_bin, account_id, note) VALUES (?, ?, 'audit fixture')")) {
            statement.setBytes(1, UuidBin.toBytes(player));
            statement.setInt(2, government.getAccountId());
            statement.executeUpdate();
        }
        assertThat(audit.findCollisions())
                .singleElement()
                .satisfies(row -> assertThat(row.getRedirectId()).isEqualTo(government.getAccountId()));

        directory.recordLogin(player, "Renamed", 2L);
        assertThat(accounts.hasGovernmentNameCollision(player)).isFalse();
        assertThat(audit.findCollisions()).isEmpty();
        directory.recordLogin(player, "GovTest", 3L);
        accounts.archiveAccount(government.getAccountId());
        assertThat(accounts.hasGovernmentNameCollision(player)).isFalse();
        assertThat(audit.findCollisions()).isEmpty();
    }

    @Test
    void vaultCollisionRefusalPrecedesCachedPersonalResolutionAndLeavesBalancesUnchanged() throws Exception {
        var accounts = injector.getInstance(AccountService.class);
        var directory = injector.getInstance(PlayerDirectoryService.class);
        var ledger = injector.getInstance(LedgerService.class);
        UUID player = UUID.randomUUID();
        ledger.bootstrapGovernmentAccounts();
        var personal = ledger.resolveOrCreatePersonal(player);
        // Warm the funded-personal cache before the player changes name.
        ledger.resolveOrCreatePersonal(player);
        var government = accounts.createAccount(AccountType.GOVERNMENT, UUID.randomUUID(), "GovTest");
        directory.recordLogin(player, "GovTest", 1L);
        var before = accounts.getBalanceReadOnly(personal.getAccountId());
        assertThatThrownBy(() -> ledger.vaultDeposit(
                        "audit-test", player, BigDecimal.ONE, "must refuse", player, null, "collision-deposit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ambiguous");
        assertThatThrownBy(() -> ledger.vaultWithdraw(
                        "audit-test", player, BigDecimal.ONE, "must refuse", player, null, "collision-withdraw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ambiguous");
        assertThat(accounts.getBalanceReadOnly(personal.getAccountId())).isEqualByComparingTo(before);
        assertThat(accounts.getBalanceReadOnly(government.getAccountId())).isZero();
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO account_redirects (redirect_uuid_bin, account_id, note) VALUES (?, ?, 'approved routing fixture')")) {
            statement.setBytes(1, UuidBin.toBytes(player));
            statement.setInt(2, government.getAccountId());
            statement.executeUpdate();
        }
        injector.getInstance(io.paradaux.treasury.services.cache.AccountRedirectCache.class)
                .reload();
        ledger.vaultDeposit(
                "audit-test", player, BigDecimal.ONE, "explicit redirect", player, null, "redirect-deposit");
        assertThat(accounts.getBalanceReadOnly(government.getAccountId())).isEqualByComparingTo("1");
        assertThat(accounts.getBalanceReadOnly(personal.getAccountId())).isEqualByComparingTo(before);
        // An explicitly PERSONAL API operation still refers to the personal wallet.
        assertThat(ledger.resolveOrCreatePersonal(player).getAccountId()).isEqualTo(personal.getAccountId());
    }
}
