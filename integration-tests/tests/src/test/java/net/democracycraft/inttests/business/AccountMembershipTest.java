package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
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
 * Explicit per-route coverage for the Business account member / authorizer
 * management subroutes, asserted against Treasury's {@code account_access}
 * table (V18: one row per subject, level VIEWER/MEMBER/AUTHORIZER, soft-removed
 * via {@code removed_at}) through {@link BusinessLedger}.
 *
 * <p>Firm-account access is <b>role-derived</b> (PAR-77 / PAR-141): the owner plus
 * every employee whose role carries ADMIN is a member + authorizer, every
 * employee whose role carries FINANCIAL is a member, and nobody else has access.
 * Every manual {@code account addmember|removemember|addauthorizer|removeauthorizer}
 * is followed by a reconcile to that role truth, so a manual write can never make
 * the account drift from the firm's roles. The default roles are Employee
 * (DEFAULT) → Supervisor (CHESTSHOP) → Manager (FINANCIAL) → Co-Proprietor
 * (ADMIN), which is what the promotions below walk through.
 */
class AccountMembershipTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    /** Owner offers, hire accepts: the hire joins at the lowest default role (Employee). */
    private static void hire(MockPlayer o, MockPlayer h, String hireName, String firm, int firmId) {
        Barrier.settle();
        o.chatCommand("/business offer " + firm + " " + hireName);
        o.awaitMessageContaining("job offer has sent", TIMEOUT);
        Barrier.settle();
        Ops.run(hireName, () -> {
            h.chatCommand("/business offer accept " + firm);
            h.awaitMessageContaining("accepted a job offer", TIMEOUT);
        });
        BusinessLedger.waitForEmployeeRole(firmId, h.getUuid(), "Employee", TIMEOUT);
    }

    private static void promoteTo(MockPlayer o, MockPlayer h, String hireName, String firm, int firmId,
                                  String role) {
        Barrier.settle();
        o.drainMessages();
        o.chatCommand("/business staff promote " + firm + " " + hireName);
        o.awaitMessageContaining("has been promoted to", TIMEOUT);
        BusinessLedger.waitForEmployeeRole(firmId, h.getUuid(), role, TIMEOUT);
    }

    private static void demoteTo(MockPlayer o, MockPlayer h, String hireName, String firm, int firmId,
                                 String role) {
        Barrier.settle();
        o.drainMessages();
        o.chatCommand("/business staff demote " + firm + " " + hireName);
        o.awaitMessageContaining("has been demoted to", TIMEOUT);
        BusinessLedger.waitForEmployeeRole(firmId, h.getUuid(), role, TIMEOUT);
    }

    /** Runs an account-access command and returns the (first non-blank) reply. */
    private static String accountCommand(MockPlayer o, String command) {
        Barrier.settle();
        o.drainMessages();
        o.chatCommand(command);
        String reply = o.awaitMessage(m -> !m.isBlank(), TIMEOUT);
        Barrier.settle();
        // Let the write + the reconcile that follows it commit before asserting.
        Barrier.settle();
        return reply;
    }

    @Test
    void manualAddMemberCannotGrantAccessOutsideFirmRoles() {
        String owner = Usernames.fresh("AnA");
        String outsider = Usernames.fresh("AnE");
        String firm = freshFirmName("AnF");

        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer e = MockPlayer.connectLocal(outsider)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(e.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                // A fresh firm account has exactly one member + authorizer: the proprietor.
                BusinessLedger.waitForAccountMemberCount(defaultId, 1, TIMEOUT);
                assertThat(BusinessLedger.isAccountAuthorizer(defaultId, p.getUuid())).isTrue();

                // The outsider holds no role in the firm, so the reconcile that follows
                // the manual add takes the access straight back out.
                accountCommand(p, "/business account addmember " + firm + " " + defaultId + " " + outsider);
                BusinessLedger.waitForAccountMemberCount(defaultId, 1, TIMEOUT);
                assertThat(BusinessLedger.isAccountMember(defaultId, e.getUuid())).isFalse();
            });
        }
    }

    @Test
    void accountAddAndRemoveMemberAdjustsCount() {
        String owner = Usernames.fresh("AmA");
        String employee = Usernames.fresh("AmE");
        String firm = freshFirmName("AmF");

        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer e = MockPlayer.connectLocal(employee)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(e.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                int baseline = BusinessLedger.accountMemberCount(defaultId);

                // Employee / Supervisor carry no FINANCIAL permission: no account access.
                hire(p, e, employee, firm, firmId);
                promoteTo(p, e, employee, firm, firmId, "Supervisor");
                Barrier.settle();
                assertThat(BusinessLedger.isAccountMember(defaultId, e.getUuid())).isFalse();

                // Manager carries FINANCIAL → becomes a member (not an authorizer).
                promoteTo(p, e, employee, firm, firmId, "Manager");
                BusinessLedger.waitForAccountMemberCount(defaultId, baseline + 1, TIMEOUT);
                assertThat(BusinessLedger.isAccountMember(defaultId, e.getUuid())).isTrue();
                assertThat(BusinessLedger.isAccountAuthorizer(defaultId, e.getUuid())).isFalse();

                // addmember on someone already entitled is idempotent.
                accountCommand(p, "/business account addmember " + firm + " " + defaultId + " " + employee);
                BusinessLedger.waitForAccountMemberCount(defaultId, baseline + 1, TIMEOUT);

                // removemember cannot strip role-derived access: the reconcile restores it.
                accountCommand(p, "/business account removemember " + firm + " " + defaultId + " " + employee);
                BusinessLedger.waitForAccountMemberCount(defaultId, baseline + 1, TIMEOUT);
                assertThat(BusinessLedger.isAccountMember(defaultId, e.getUuid())).isTrue();

                // Losing the FINANCIAL role is what removes the membership.
                demoteTo(p, e, employee, firm, firmId, "Supervisor");
                BusinessLedger.waitForAccountMemberCount(defaultId, baseline, TIMEOUT);
                assertThat(BusinessLedger.isAccountMember(defaultId, e.getUuid())).isFalse();
            });
        }
    }

    @Test
    void accountAddAndRemoveAuthorizerAdjustsCount() {
        String owner = Usernames.fresh("AaA");
        String employee = Usernames.fresh("AaE");
        String firm = freshFirmName("AaF");

        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer e = MockPlayer.connectLocal(employee)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(e.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                int baselineMembers = BusinessLedger.accountMemberCount(defaultId);
                int baselineAuth = BusinessLedger.accountAuthorizerCount(defaultId);

                hire(p, e, employee, firm, firmId);
                promoteTo(p, e, employee, firm, firmId, "Supervisor");
                promoteTo(p, e, employee, firm, firmId, "Manager");
                BusinessLedger.waitForAccountMemberCount(defaultId, baselineMembers + 1, TIMEOUT);

                // A Manager is a member, so addauthorizer is accepted — but FINANCIAL
                // alone doesn't entitle them to it, so the reconcile demotes them again.
                String reply = accountCommand(p,
                        "/business account addauthorizer " + firm + " " + defaultId + " " + employee);
                assertThat(reply).doesNotContain("internal error");
                BusinessLedger.waitForAccountAuthorizerCount(defaultId, baselineAuth, TIMEOUT);
                assertThat(BusinessLedger.isAccountAuthorizer(defaultId, e.getUuid())).isFalse();
                assertThat(BusinessLedger.isAccountMember(defaultId, e.getUuid())).isTrue();

                // Co-Proprietor carries ADMIN → becomes an authorizer.
                promoteTo(p, e, employee, firm, firmId, "Co-Proprietor");
                BusinessLedger.waitForAccountAuthorizerCount(defaultId, baselineAuth + 1, TIMEOUT);
                assertThat(BusinessLedger.isAccountAuthorizer(defaultId, e.getUuid())).isTrue();

                // removeauthorizer cannot strip role-derived access either.
                accountCommand(p,
                        "/business account removeauthorizer " + firm + " " + defaultId + " " + employee);
                BusinessLedger.waitForAccountAuthorizerCount(defaultId, baselineAuth + 1, TIMEOUT);

                // Losing ADMIN drops them back to a plain member.
                demoteTo(p, e, employee, firm, firmId, "Manager");
                BusinessLedger.waitForAccountAuthorizerCount(defaultId, baselineAuth, TIMEOUT);
                assertThat(BusinessLedger.isAccountMember(defaultId, e.getUuid())).isTrue();
            });
        }
    }

    /**
     * Promoting someone who is not a member of the account is a user error and must
     * be answered as one. (Exposes a product bug at the time of writing: Treasury's
     * MembershipServiceImpl.addAuthorizer throws a bare IllegalStateException, which
     * the command framework renders as "An internal error occurred" and logs as an
     * unhandled exception.)
     */
    @Test
    void addAuthorizerForNonMemberIsRejectedCleanly() {
        String owner = Usernames.fresh("AxA");
        String outsider = Usernames.fresh("AxE");
        String firm = freshFirmName("AxF");

        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer e = MockPlayer.connectLocal(outsider)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(e.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                int baselineAuth = BusinessLedger.accountAuthorizerCount(defaultId);

                String reply = accountCommand(p,
                        "/business account addauthorizer " + firm + " " + defaultId + " " + outsider);

                assertThat(BusinessLedger.accountAuthorizerCount(defaultId)).isEqualTo(baselineAuth);
                assertThat(BusinessLedger.isAccountMember(defaultId, e.getUuid())).isFalse();
                assertThat(reply)
                        .as("a non-member must be refused with an explanation, not an internal error")
                        .doesNotContain("internal error")
                        .containsIgnoringCase("member");
            });
        }
    }

    @Test
    void accountMembersListShowsAddedEmployee() {
        String owner = Usernames.fresh("AlmA");
        String employee = Usernames.fresh("AlmE");
        String firm = freshFirmName("AlmF");

        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer e = MockPlayer.connectLocal(employee)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(e.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                int baseline = BusinessLedger.accountMemberCount(defaultId);

                hire(p, e, employee, firm, firmId);
                promoteTo(p, e, employee, firm, firmId, "Supervisor");
                promoteTo(p, e, employee, firm, firmId, "Manager");
                BusinessLedger.waitForAccountMemberCount(defaultId, baseline + 1, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account members " + firm + " " + defaultId);
                // Header message arrives first, then one entry per member as
                // a separate chat line — wait specifically for the entry that
                // mentions the employee's username.
                String header = p.awaitMessageContaining("Members of account", TIMEOUT);
                assertThat(header).contains("(" + (baseline + 1) + " total)");
                String entry = p.awaitMessageContaining(employee, TIMEOUT);
                assertThat(entry).contains(employee);
            });
        }
    }

    @Test
    void accountAuthorizersListShowsAddedEmployee() {
        String owner = Usernames.fresh("AluA");
        String employee = Usernames.fresh("AluE");
        String firm = freshFirmName("AluF");

        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer e = MockPlayer.connectLocal(employee)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(e.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                int baselineAuth = BusinessLedger.accountAuthorizerCount(defaultId);

                hire(p, e, employee, firm, firmId);
                promoteTo(p, e, employee, firm, firmId, "Supervisor");
                promoteTo(p, e, employee, firm, firmId, "Manager");
                promoteTo(p, e, employee, firm, firmId, "Co-Proprietor");
                BusinessLedger.waitForAccountAuthorizerCount(defaultId, baselineAuth + 1, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account authorizers " + firm + " " + defaultId);
                String header = p.awaitMessageContaining("Authorizers of account", TIMEOUT);
                assertThat(header).contains("(" + (baselineAuth + 1) + " total)");
                String entry = p.awaitMessageContaining(employee, TIMEOUT);
                assertThat(entry).contains(employee);
            });
        }
    }
}
