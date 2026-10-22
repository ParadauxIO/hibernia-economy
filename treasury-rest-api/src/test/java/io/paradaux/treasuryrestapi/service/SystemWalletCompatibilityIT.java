package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** REST writers must preserve the same SYSTEM identity consumed by the plugin mapper. */
class SystemWalletCompatibilityIT extends EmbeddedDbIT {
    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private TransferService transfers;

    @Test
    void restRenameTransferAndLifecycleKeepOriginalSystemWalletKeyAndFunds() throws Exception {
        UUID owner = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        VerifiedToken operator = new VerifiedToken(1L, owner, "SERVICE", null, null);
        insertAccount(1, "SYSTEM", owner, "FixturePlugin");
        insertAccount(2, "BUSINESS", null, "Destination");
        seedBalance(1, "100.00");
        seedBalance(2, "0.00");

        assertThat(accounts.rename(operator, 1L, "Renamed SYSTEM label").getDisplayName())
                .isEqualTo("Renamed SYSTEM label");
        var transfer = transfers.adminTransfer(operator, 1L, 2L, "40.00", "Compatibility fixture", null);
        assertThat(transfer.fromAccountId()).isEqualTo(1L);
        assertThat(transfer.toAccountId()).isEqualTo(2L);
        assertThat(matBalance(1)).isEqualByComparingTo("60.00");
        assertThat(matBalance(2)).isEqualByComparingTo("40.00");
        assertThat(sumPostings(1).add(sumPostings(2))).isEqualByComparingTo("0.00");
        accounts.archive(operator, 1L);
        accounts.unarchive(operator, 1L);

        // The internal key is not a public namespace reservation: a player may
        // own this spelling after the SYSTEM display label has been changed.
        insertPlayer(UUID.randomUUID(), "FixturePlugin");
        assertThatThrownBy(() -> accounts.rename(operator, 1L, "FixturePlugin"))
                .hasRootCauseInstanceOf(SQLException.class);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery("SELECT account_id,HEX(owner_uuid_bin),display_name,system_plugin_key "
                                + "FROM accounts WHERE account_type='SYSTEM' AND system_plugin_key='fixtureplugin'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
            assertThat(rows.getString(2)).isEqualTo("AA".repeat(16));
            assertThat(rows.getString(3)).isEqualTo("Renamed SYSTEM label");
            assertThat(rows.getString(4)).isEqualTo("FixturePlugin");
            assertThat(rows.next()).isFalse();
        }
        assertThat(rowCount("account_balances_mat")).isEqualTo(2);
    }
}
