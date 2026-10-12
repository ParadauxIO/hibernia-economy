package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorization-escalation attacks on firm-account operations.
 *
 * <p>The security model business-rian enforces:
 * <ul>
 *   <li>A non-employee of a firm has <b>no access</b> to its accounts —
 *       can't deposit, withdraw, transfer, list members, or read history.</li>
 *   <li>A member with rank Employee (5) has <em>read</em> access to the
 *       account but no financial-write access.</li>
 *   <li>A member with FINANCIAL permission (Manager+) can withdraw.</li>
 *   <li>A member with ADMIN permission (Proprietor/Co-Proprietor) can
 *       grant other members financial / admin permissions.</li>
 *   <li>Removing an authorizer must immediately revoke their ability to
 *       withdraw — a stale cache that lets a fired manager keep
 *       withdrawing is the textbook escalation bug.</li>
 * </ul>
 *
 * <p>Each test exercises one role boundary by setting up two mock players
 * (owner + attacker), giving the attacker the highest privilege they're
 * supposed to have, and then trying the operation immediately above
 * that. The post-condition is always: the attempted action did NOT mutate
 * the firm's balance.
 */
class FirmAuthorizationAttacksTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String suffix() {
        return Long.toHexString(Instant.now().toEpochMilli() ^ System.nanoTime());
    }

    @Test
    void nonMemberCannotWithdrawFromFirmAccount() {
        // Attacker has never been employed by the firm.
        String owner = Usernames.fresh("NmO");
        String attacker = Usernames.fresh("NmA");
        String firm = "Nm" + suffix();
        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer a = MockPlayer.connectLocal(attacker)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int acctId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                // Seed the firm so a successful withdraw would be observable.
                o.chatCommand("/eco give " + owner + " 1000");
                o.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                o.chatCommand("/business account deposit " + firm + " " + acctId + " 1000");
                o.awaitMessage(m -> !m.isBlank(), TIMEOUT);

                BigDecimal firmBefore = Ledger.governmentBalance(firm + " Corporate Account")
                        .orElse(BigDecimal.ZERO);

                // Attacker is NOT op — only has whatever default permissions
                // a vanilla player has. No Ops.run wrapper.
                a.drainMessages();
                a.chatCommand("/business account withdraw " + firm + " " + acctId + " 1000");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);

                // Cardinal assertion: firm balance unchanged.
                assertThat(Ledger.governmentBalance(firm + " Corporate Account").orElse(BigDecimal.ZERO))
                        .as("non-member must not be able to drain firm account")
                        .isEqualByComparingTo(firmBefore);
            });
        }
    }

    @Test
    void nonMemberCannotDepositToForceMembership() {
        // Some plugins use "first deposit auto-creates membership" — a
        // bug pattern we want to ensure business-rian doesn't have. A
        // non-member depositing to a firm account must either: (a) be
        // rejected outright, or (b) at minimum not create a membership
        // row as a side-effect.
        String owner = Usernames.fresh("NdO");
        String attacker = Usernames.fresh("NdA");
        String firm = "Nd" + suffix();
        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer a = MockPlayer.connectLocal(attacker)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int acctId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                a.drainMessages();
                a.chatCommand("/business account deposit " + firm + " " + acctId + " 50");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);

                // The membership-creation post-condition: attacker MUST NOT
                // become an employee as a side-effect of the deposit attempt.
                assertThat(BusinessLedger.isCurrentEmployee(firmId, a.getUuid()))
                        .as("deposit attempt must not implicitly grant membership")
                        .isFalse();
            });
        }
    }

    @Test
    void employeeWithoutFinancialPermissionCannotWithdraw() {
        // Owner hires a regular Employee (rank 5, DEFAULT perm only).
        // Default-perm employees can READ the firm but cannot withdraw.
        // Test the boundary: that employee tries /business account
        // withdraw and gets refused. Firm balance must be untouched.
        String owner = Usernames.fresh("EwO");
        String employee = Usernames.fresh("EwE");
        String firm = "Ew" + suffix();
        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer e = MockPlayer.connectLocal(employee)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(e.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int acctId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                // Seed.
                o.chatCommand("/eco give " + owner + " 500");
                o.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                o.chatCommand("/business account deposit " + firm + " " + acctId + " 500");
                o.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                // Hire the attacker at default rank.
                o.chatCommand("/business hire " + firm + " " + employee);
                o.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });

            BigDecimal firmBefore = Ledger.governmentBalance(firm + " Corporate Account")
                    .orElse(BigDecimal.ZERO);

            // Employee is NOT op — uses their own default permissions.
            e.drainMessages();
            e.chatCommand("/business account withdraw " + firm + " "
                    + BusinessLedger.firmDefaultAccountId(BusinessLedger.firmId(firm).orElseThrow()).orElseThrow()
                    + " 100");
            e.awaitMessage(m -> !m.isBlank(), TIMEOUT);

            assertThat(Ledger.governmentBalance(firm + " Corporate Account").orElse(BigDecimal.ZERO))
                    .as("Employee-rank member must not be able to withdraw firm funds")
                    .isEqualByComparingTo(firmBefore);
        }
    }

    @Test
    void firedEmployeeImmediatelyLosesWithdrawAccess() {
        // Promote an employee high enough to withdraw, then fire them.
        // Their subsequent withdraw attempt must fail. Stale-cache
        // escalation is a classic bug pattern: a perms cache that
        // doesn't invalidate on fire/demote lets the attacker drain
        // the firm for the cache TTL.
        String owner = Usernames.fresh("FfO");
        String manager = Usernames.fresh("FfM");
        String firm = "Ff" + suffix();
        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer m = MockPlayer.connectLocal(manager)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(m.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int acctId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                o.chatCommand("/eco give " + owner + " 2000");
                o.awaitMessage(m2 -> !m2.isBlank(), TIMEOUT);
                o.chatCommand("/business account deposit " + firm + " " + acctId + " 2000");
                o.awaitMessage(m2 -> !m2.isBlank(), TIMEOUT);
                o.chatCommand("/business hire " + firm + " " + manager);
                o.awaitMessage(m2 -> !m2.isBlank(), TIMEOUT);
                // Promote to Manager-ish so they have FINANCIAL.
                o.chatCommand("/business staff promote " + firm + " " + manager);
                o.awaitMessage(m2 -> !m2.isBlank(), TIMEOUT);
                o.chatCommand("/business staff promote " + firm + " " + manager);
                o.awaitMessage(m2 -> !m2.isBlank(), TIMEOUT);
                // Now fire them.
                o.chatCommand("/business fire " + firm + " " + manager);
                o.awaitMessage(m2 -> !m2.isBlank(), TIMEOUT);
            });

            BigDecimal firmBefore = Ledger.governmentBalance(firm + " Corporate Account")
                    .orElse(BigDecimal.ZERO);

            // Fired manager (no op) tries to withdraw.
            m.drainMessages();
            m.chatCommand("/business account withdraw " + firm + " "
                    + BusinessLedger.firmDefaultAccountId(BusinessLedger.firmId(firm).orElseThrow()).orElseThrow()
                    + " 500");
            m.awaitMessage(msg -> !msg.isBlank(), TIMEOUT);

            assertThat(Ledger.governmentBalance(firm + " Corporate Account").orElse(BigDecimal.ZERO))
                    .as("fired manager must lose withdraw access immediately")
                    .isEqualByComparingTo(firmBefore);
        }
    }

    @Test
    void nonOpCannotInvokeEcoGiveToInflateBalance() {
        // /eco give is the universal "print money" admin command. A non-op
        // player calling it must hit the permission gate at the Brigadier
        // layer — the command shouldn't even resolve. Asserting balance
        // unchanged is the security gate.
        String attacker = Usernames.fresh("NoeA");
        try (MockPlayer p = MockPlayer.connectLocal(attacker)) {
            BigDecimal start = Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            p.drainMessages();
            // No Ops.run — attacker is a vanilla player.
            p.chatCommand("/eco give " + attacker + " 1000000");
            p.awaitMessage(m -> !m.isBlank(), TIMEOUT);

            assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow())
                    .as("non-op /eco give must NOT credit the attacker")
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void nonOpCannotIssueFineToOthers() {
        // /fine issue requires treasury.gov.fine.issue (default: op).
        // A vanilla player invoking it must be refused at the perm gate.
        String attacker = Usernames.fresh("NofA");
        String victim = Usernames.fresh("NofV");
        try (MockPlayer a = MockPlayer.connectLocal(attacker);
             MockPlayer v = MockPlayer.connectLocal(victim)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            BigDecimal victimStart = Ledger.waitForPersonalBalance(v.getUuid(), TIMEOUT);

            a.drainMessages();
            a.chatCommand("/fine issue GovernmentFines " + victim + " 9999 illegitimate");
            a.awaitMessage(m -> !m.isBlank(), TIMEOUT);

            assertThat(Ledger.personalBalance(v.getUuid()).orElseThrow())
                    .as("non-op /fine issue must NOT debit the victim")
                    .isEqualByComparingTo(victimStart);
        }
    }
}
