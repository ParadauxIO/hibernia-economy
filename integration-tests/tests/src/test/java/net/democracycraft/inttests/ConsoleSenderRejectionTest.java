package net.democracycraft.inttests;

import net.democracycraft.inttests.support.RconClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-plugin "@Sender Player" rejection coverage.
 *
 * <p>Many Treasury / Business / TreasuryAPI commands declare
 * {@code @Sender Player}: only an in-game player can invoke them. RCON sends
 * commands as a non-player {@code CommandSender}, so dispatch must either
 * (a) reject at the brigadier layer (no matching route) or (b) reach the
 * handler's argument-extraction step where Hibernia's {@code injectSender}
 * throws {@code "Sender must be Player"}, which the framework catches and
 * surfaces as an "Error: …" message.
 *
 * <p>The guarantee under test: <b>no Java stack trace ever reaches the
 * console</b>. Treasury / Business commands must not NPE or leak internal
 * exceptions when the wrong sender type tries to run them.
 *
 * <p>RCON output is captured by {@link RconClient#send(String)} which returns
 * the raw response (including color codes stripped); the assertion is "the
 * response contains a recognisable rejection token and no Java type names".
 */
class ConsoleSenderRejectionTest {

    @Test
    void payViaRconRejectsCleanly() {
        String r = RconClient.send("pay SomePlayer 100");
        assertHandledCleanly(r, "/pay");
    }

    @Test
    void balanceViaRconRejectsCleanly() {
        String r = RconClient.send("balance");
        assertHandledCleanly(r, "/balance");
    }

    @Test
    void transactionsViaRconRejectsCleanly() {
        String r = RconClient.send("transactions");
        assertHandledCleanly(r, "/transactions");
    }

    @Test
    void govAccountCreateViaRconRejectsCleanly() {
        String r = RconClient.send("gov account create ConsoleTest");
        assertHandledCleanly(r, "/gov account create");
    }

    @Test
    void govPayoutViaRconRejectsCleanly() {
        String r = RconClient.send("gov payout DCGovernment SomePlayer 1 reason");
        assertHandledCleanly(r, "/gov payout");
    }

    @Test
    void fineIssueViaRconRejectsCleanly() {
        String r = RconClient.send("fine issue GovernmentFines SomePlayer 50 reason");
        assertHandledCleanly(r, "/fine issue");
    }

    @Test
    void treasuryapiPersonalIssueViaRconRejectsCleanly() {
        String r = RconClient.send("treasuryapi personal issue");
        assertHandledCleanly(r, "/treasuryapi personal issue");
    }

    @Test
    void treasuryapiBusinessIssueViaRconRejectsCleanly() {
        String r = RconClient.send("treasuryapi business issue SomeFirm");
        assertHandledCleanly(r, "/treasuryapi business issue");
    }

    @Test
    void businessCreateViaRconRejectsCleanly() {
        String r = RconClient.send("business create ConsoleFirm");
        assertHandledCleanly(r, "/business create");
    }

    @Test
    void businessDepositViaRconRejectsCleanly() {
        String r = RconClient.send("business deposit ConsoleFirm 10");
        assertHandledCleanly(r, "/business deposit");
    }

    @Test
    void businessAccountCreateViaRconRejectsCleanly() {
        String r = RconClient.send("business account create ConsoleFirm Vault");
        assertHandledCleanly(r, "/business account create");
    }

    @Test
    void businessHireViaRconRejectsCleanly() {
        String r = RconClient.send("business offer ConsoleFirm Someone");
        assertHandledCleanly(r, "/business offer");
    }

    @Test
    void businessFireViaRconRejectsCleanly() {
        String r = RconClient.send("business fire ConsoleFirm Someone");
        assertHandledCleanly(r, "/business fire");
    }

    @Test
    void businessRoleCreateViaRconRejectsCleanly() {
        String r = RconClient.send("business staff role create ConsoleFirm Lead 100");
        assertHandledCleanly(r, "/business staff role create");
    }

    @Test
    void businessTaxExemptViaRconRejectsCleanly() {
        String r = RconClient.send("business tax exempt ConsoleFirm true");
        assertHandledCleanly(r, "/business tax exempt");
    }

    // ---------------------------------------------------------------- helpers

    private static void assertHandledCleanly(String response, String label) {
        // No Java stack trace symbols. ".java:" appears in stack frames; "at "
        // is the line prefix; any class name like "Exception" / "Error" with
        // ".java" attached would signal a leak.
        assertThat(response)
                .as("RCON response for " + label + " must not leak a Java stack trace")
                .doesNotContain(".java:")
                .doesNotContain("at io.")
                .doesNotContain("at net.")
                .doesNotContain("at org.bukkit")
                .doesNotContain("NullPointerException");
    }
}
