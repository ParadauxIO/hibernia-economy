package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /business account ... — explicit Treasury-account management on a firm.
 *
 * <p>Each firm starts with an auto-created default account (firm_accounts row,
 * one entry); these tests exercise creating additional accounts, swapping the
 * default, archiving, and member/authorizer management.
 */
class AccountCommandsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void createAccountAddsFirmAccountsRow() {
        String owner = Usernames.fresh("AcO");
        String firm = freshFirmName("AcCre");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int baseline = BusinessLedger.firmAccountCount(firmId);

                Barrier.settle();
                p.chatCommand("/business account create " + firm + " Savings");
                p.awaitMessageContaining("created account", TIMEOUT);

                Await.untilEquals("account count of firm " + firmId, () -> BusinessLedger.firmAccountCount(firmId),
                        baseline + 1, TIMEOUT);
            });
        }
    }

    @Test
    void crossFirmDuplicateNamesKeepSeparateAccounts() {
        String owner = Usernames.fresh("NcA");
        String otherOwner = Usernames.fresh("NcB");
        String firm = freshFirmName("NcFirmA");
        String otherFirm = freshFirmName("NcFirmB");
        String accountName = "Shared" + firm;
        try (MockPlayer first = MockPlayer.connectLocal(owner);
             MockPlayer second = MockPlayer.connectLocal(otherOwner)) {
            Ledger.waitForPersonalBalance(first.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(second.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                first.chatCommand("/business create " + firm);
                first.awaitMessageContaining("Successfully created", TIMEOUT);
                Barrier.settle();
                first.chatCommand("/business account create " + firm + " " + accountName);
                first.awaitMessageContaining("created account", TIMEOUT);
            });
            int firstFirmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            BusinessLedger.waitForFirmAccountCountAtLeast(firstFirmId, 2, TIMEOUT);
            int originalAccountId = BusinessLedger.firmAccountIdAt(firstFirmId, 1);
            Ops.run(otherOwner, () -> {
                second.chatCommand("/business create " + otherFirm);
                second.awaitMessageContaining("Successfully created", TIMEOUT);
                int secondFirmId = BusinessLedger.waitForFirm(otherFirm, TIMEOUT);
                Barrier.settle();
                second.drainMessages();
                second.chatCommand("/business account create " + otherFirm + " " + accountName);
                second.awaitMessageContaining("created account", TIMEOUT);
                Barrier.settle();
                assertThat(BusinessLedger.firmAccountCount(secondFirmId)).isEqualTo(2);
                assertThat(BusinessLedger.firmAccountCount(firstFirmId)).isEqualTo(2);
                assertThat(BusinessLedger.firmAccountIdAt(firstFirmId, 1)).isEqualTo(originalAccountId);
                assertThat(BusinessLedger.firmAccountIdAt(secondFirmId, 1)).isNotEqualTo(originalAccountId);
            });
        }
    }

    @Test
    void listAccountsShowsHeader() {
        String owner = Usernames.fresh("AlO");
        String firm = freshFirmName("AcLst");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business account list " + firm);
                p.awaitMessageContaining("Treasury Accounts for " + firm, TIMEOUT);
            });
        }
    }

    @Test
    void setDefaultPointsFirmDefaultAtNewAccount() {
        String owner = Usernames.fresh("AsO");
        String firm = freshFirmName("AcDef");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int originalDefault = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.chatCommand("/business account create " + firm + " Reserves");
                p.awaitMessageContaining("created account", TIMEOUT);

                BusinessLedger.waitForFirmAccountCountAtLeast(firmId, 2, TIMEOUT);
                int newAccountId = BusinessLedger.firmAccountIdAt(firmId, 1);
                assertThat(newAccountId).isNotEqualTo(originalDefault);

                Barrier.settle();
                p.chatCommand("/business account setdefault " + firm + " " + newAccountId);
                p.awaitMessageContaining("default account for " + firm, TIMEOUT);

                BusinessLedger.waitForFirmDefaultAccount(firmId, newAccountId, TIMEOUT);
            });
        }
    }

    @Test
    void archiveAccountSucceedsForNonDefault() {
        String owner = Usernames.fresh("AaO");
        String firm = freshFirmName("AcArc");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business account create " + firm + " ToArchive");
                p.awaitMessageContaining("created account", TIMEOUT);

                BusinessLedger.waitForFirmAccountCountAtLeast(firmId, 2, TIMEOUT);
                int newAccountId = BusinessLedger.firmAccountIdAt(firmId, 1);

                Barrier.settle();
                p.chatCommand("/business account archive " + firm + " " + newAccountId);
                p.awaitMessageContaining("archived account", TIMEOUT);
            });
        }
    }

    @Test
    void syncAccountReportsSuccess() {
        String owner = Usernames.fresh("AyO");
        String firm = freshFirmName("AcSync");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.chatCommand("/business account sync " + firm + " " + defaultId);
                p.awaitMessageContaining("synced staff members to account", TIMEOUT);
            });
        }
    }

    @Test
    void listMembersAndAuthorizersShowHeader() {
        String owner = Usernames.fresh("AmO");
        String firm = freshFirmName("AcMem");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.chatCommand("/business account members " + firm + " " + defaultId);
                p.awaitMessage(m -> m.contains("Members of account") || m.contains("No members found"), TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business account authorizers " + firm + " " + defaultId);
                p.awaitMessage(m -> m.contains("Authorizers of account") || m.contains("No authorizers found"), TIMEOUT);
            });
        }
    }

    @Test
    void accountBalanceShowsAmount() {
        String owner = Usernames.fresh("AbO");
        String firm = freshFirmName("AcBal");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.chatCommand("/business account balance " + firm + " " + defaultId);
                p.awaitMessageContaining("Account " + defaultId + " balance for " + firm, TIMEOUT);
            });
        }
    }

    @Test
    void accountDepositAndWithdrawRoundTrip() {
        String owner = Usernames.fresh("AdO");
        String firm = freshFirmName("AcDep");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.chatCommand("/business account deposit " + firm + " " + defaultId + " 75");
                p.awaitMessageContaining("Deposited", TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business account withdraw " + firm + " " + defaultId + " 75");
                p.awaitMessageContaining("Withdrew", TIMEOUT);
            });
        }
    }

    @Test
    void accountTransactionsShowsHeaderAfterDeposit() {
        String owner = Usernames.fresh("AtO");
        String firm = freshFirmName("AcTx");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.chatCommand("/business account deposit " + firm + " " + defaultId + " 25");
                p.awaitMessageContaining("Deposited", TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business account transactions " + firm + " " + defaultId);
                p.awaitMessage(m -> m.contains("Transactions for " + firm + " account " + defaultId), TIMEOUT);
            });
        }
    }
}
