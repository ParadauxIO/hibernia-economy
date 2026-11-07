package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.DevServer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RestApi;
import net.democracycraft.inttests.support.RestApi.Response;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage of the soft-delete read filters in the Treasury REST API.
 *
 * <p>The plugins write {@code left_at} / {@code revoked_at} /
 * {@code deleted_at} / {@code removed_at} on the shared MariaDB. The REST
 * API mappers must filter on those columns when reading; otherwise a
 * removed member or a removed firm-account link would still pass the
 * authorisation checks. These tests fail loudly if any of those filters
 * is removed in a future refactor.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>A removed Treasury account member can no longer read account
 *       transactions via {@code GET /accounts/{id}/transactions}.</li>
 *   <li>A soft-deleted {@code firm_accounts} link can no longer be used
 *       as a transfer source via {@code POST /transfers} on a BUSINESS
 *       token.</li>
 *   <li>A soft-deleted {@code firm_accounts} link is excluded from the
 *       summed balance returned by {@code GET /firms/me/balance}.</li>
 * </ol>
 */
class SoftDeleteAuthTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static String freshFirm(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void removedAccountMemberCannotReadTransactionsViaApi() {
        String owner = Usernames.fresh("Sdo");
        String member = Usernames.fresh("Sdm");
        String account = "Sda" + Long.toHexString(Instant.now().toEpochMilli());

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer m = MockPlayer.connectLocal(member)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(m.getUuid(), TIMEOUT);

            int[] accountIdHolder = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/gov account create " + account);
                o.awaitMessageContaining("Created", TIMEOUT);
                accountIdHolder[0] = Ledger.waitForGovernmentAccount(account, TIMEOUT);

                Barrier.settle();
                o.chatCommand("/gov account member add " + account + " " + member);
                o.awaitMessageContaining("Added", TIMEOUT);
                Ledger.waitForMemberCount(accountIdHolder[0], 1, TIMEOUT);
            });
            int accountId = accountIdHolder[0];

            // Member issues their own PERSONAL API token; their account_id
            // claim is their PERSONAL account, not the gov account. They
            // can read the gov account's history only because they're a
            // member of it (TransactionService line 88).
            IssuedKey memberKey = ApiTokens.issuePersonal(member, m);

            Response active = RestApi.getTransactions(memberKey.token(), accountId, null, null);
            assertThat(active.status())
                    .as("active member should be able to read transactions, body=%s", active.body())
                    .isEqualTo(200);

            // Owner removes the member — sets account_members.left_at on
            // the row but does not delete it. The REST API's isMember
            // query must filter on left_at IS NULL or this would still
            // return 200.
            Ops.run(owner, () -> {
                o.drainMessages();
                o.chatCommand("/gov account member remove " + account + " " + member);
                o.awaitMessageContaining("Removed", TIMEOUT);
                Ledger.waitForMemberCount(accountId, 0, TIMEOUT);
            });

            Response afterRemoval = RestApi.getTransactions(memberKey.token(), accountId, null, null);
            assertThat(afterRemoval.status())
                    .as("removed member must not read transactions, body=%s", afterRemoval.body())
                    .isEqualTo(403);
            assertThat(afterRemoval.error()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void removedFirmAccountLinkCannotBeUsedAsTransferSource() {
        String proprietor = Usernames.fresh("Spr");
        String recipient = Usernames.fresh("Spt");
        String firm = freshFirm("Sft");

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer r = MockPlayer.connectLocal(recipient)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(r.getUuid(), TIMEOUT);

            int[] firmIdHolder = new int[1];
            int[] secondAccountHolder = new int[1];
            Ops.run(proprietor, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                firmIdHolder[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int baseline = BusinessLedger.firmAccountCount(firmIdHolder[0]);

                Barrier.settle();
                p.chatCommand("/business account create " + firm + " Savings" + firm);
                p.awaitMessageContaining("created account", TIMEOUT);
                BusinessLedger.waitForFirmAccountCountAtLeast(firmIdHolder[0], baseline + 1, TIMEOUT);
                secondAccountHolder[0] = BusinessLedger.firmAccountIdAt(firmIdHolder[0], 1);

                // Fund the new account so we have something to transfer.
                Barrier.settle();
                p.chatCommand("/business account deposit " + firm + " " + secondAccountHolder[0] + " 200");
                p.awaitMessageContaining("Deposited", TIMEOUT);
            });
            int firmId = firmIdHolder[0];
            int savingsAccount = secondAccountHolder[0];
            int recipientAccount = Ledger.personalAccountId(r.getUuid()).orElseThrow();

            IssuedKey key = ApiTokens.issueBusiness(proprietor, p, firm);

            // Sanity: transfer FROM Savings works while link is active.
            Response active = RestApi.postTransfer(key.token(),
                    "{\"fromAccountId\":" + savingsAccount + ",\"toAccountId\":" + recipientAccount
                            + ",\"amount\":\"5.00\",\"memo\":\"pre\"}", null);
            assertThat(active.status())
                    .as("transfer from active firm-account should succeed, body=%s", active.body())
                    .isEqualTo(200);

            // Isolate link removal without archiving the Treasury account.
            // FirmBalanceLifecycleTest separately covers the real account-archive
            // command, which both archives the account and removes this link.
            softDeleteFirmAccountLink(firmId, savingsAccount);

            Response afterUnlink = RestApi.postTransfer(key.token(),
                    "{\"fromAccountId\":" + savingsAccount + ",\"toAccountId\":" + recipientAccount
                            + ",\"amount\":\"5.00\",\"memo\":\"post\"}", null);
            assertThat(afterUnlink.status())
                    .as("transfer from soft-deleted firm-account must be rejected, body=%s", afterUnlink.body())
                    .isEqualTo(403);
            assertThat(afterUnlink.error()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void softDeletedFirmAccountIsExcludedFromBalanceSum() {
        String proprietor = Usernames.fresh("Sbp");
        String firm = freshFirm("Sbf");

        try (MockPlayer p = MockPlayer.connectLocal(proprietor)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            int[] firmIdHolder = new int[1];
            int[] savingsHolder = new int[1];
            Ops.run(proprietor, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                firmIdHolder[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int baseline = BusinessLedger.firmAccountCount(firmIdHolder[0]);

                Barrier.settle();
                p.chatCommand("/business deposit " + firm + " 100");
                p.awaitMessageContaining("Paid", TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business account create " + firm + " Vault");
                p.awaitMessageContaining("created account", TIMEOUT);
                BusinessLedger.waitForFirmAccountCountAtLeast(firmIdHolder[0], baseline + 1, TIMEOUT);
                savingsHolder[0] = BusinessLedger.firmAccountIdAt(firmIdHolder[0], 1);

                Barrier.settle();
                p.chatCommand("/business account deposit " + firm + " " + savingsHolder[0] + " 250");
                p.awaitMessageContaining("Deposited", TIMEOUT);
            });
            int firmId = firmIdHolder[0];
            int vault = savingsHolder[0];

            IssuedKey key = ApiTokens.issueBusiness(proprietor, p, firm);

            // Total before unlink should be exactly 350 (100 default + 250 Vault).
            // Use /firms/{firmName}/balance — that route hits sumFirmBalance,
            // which is the read we're regression-testing.
            Response before = RestApi.getFirmBalanceByName(key.token(), firm);
            assertThat(before.status())
                    .as("firm balance read should succeed, body=%s", before.body())
                    .isEqualTo(200);
            BigDecimal totalBefore = new BigDecimal(before.json().path("totalBalance").asText());
            assertThat(totalBefore).isEqualByComparingTo(new BigDecimal("350.00"));

            softDeleteFirmAccountLink(firmId, vault);

            Response after = RestApi.getFirmBalanceByName(key.token(), firm);
            assertThat(after.status()).isEqualTo(200);
            BigDecimal totalAfter = new BigDecimal(after.json().path("totalBalance").asText());
            assertThat(totalAfter)
                    .as("removed firm-account must drop out of the sum (was %s, now %s)",
                            totalBefore, totalAfter)
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    /**
     * Stamps {@code firm_accounts.removed_at} for one (firm_id, account_id)
     * row. The plugin's {@link net.democracycraft.inttests.support.BusinessLedger}
     * helpers don't expose this fixture-only operation. The real archive command
     * also archives the account; these tests deliberately change only the link
     * to isolate the REST API's removed-link authorization and query filters.
     */
    private static void softDeleteFirmAccountLink(int firmId, int accountId) {
        try (Connection c = DriverManager.getConnection(
                DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE firm_accounts SET removed_at = CURRENT_TIMESTAMP "
                             + "WHERE firm_id = ? AND account_id = ?")) {
            ps.setInt(1, firmId);
            ps.setInt(2, accountId);
            int updated = ps.executeUpdate();
            if (updated != 1) {
                throw new AssertionError("Expected to soft-delete 1 firm_accounts row, updated " + updated);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to soft-delete firm_accounts link", e);
        }
    }
}
