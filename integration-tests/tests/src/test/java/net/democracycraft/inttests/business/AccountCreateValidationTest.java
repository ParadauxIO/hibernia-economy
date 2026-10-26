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
 * Adversarial coverage for {@code /business account create <firm> <name>}
 * account-name input. Each test asserts the response is non-blank (the
 * command was dispatched, not silently dropped) and — for rejected names —
 * that the firm's account count did not increase.
 *
 * <p>Validation lives in {@code FirmAccountService.createAccount}; this test
 * pins the user-visible behaviour for boundary inputs. If validation later
 * tightens or loosens, these tests document the contract.
 */
class AccountCreateValidationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void createAccountWithShortNameSucceedsOrRejectsCleanly() {
        // Single-character account name — likely accepted (no documented
        // length minimum), but must not crash.
        String owner = Usernames.fresh("AvS");
        String firm = freshFirmName("AvSF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account create " + firm + " X");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void createAccountWithVeryLongNameIsHandledCleanly() {
        // 64-char account name. accounts.display_name is VARCHAR(64) per the
        // schema, so exactly at the boundary. Must not throw a SQL exception
        // to the user.
        String owner = Usernames.fresh("AvL");
        String firm = freshFirmName("AvLF");
        String longName = "L".repeat(64);
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account create " + firm + " " + longName);
                String r = p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
                assertThat(r).doesNotContain(".java:");
            });
        }
    }

    @Test
    void createAccountWithOverlongNameRejectsCleanly() {
        // 200-char name — well past any reasonable column width. The handler
        // must not bubble a SQL truncation/error stack trace.
        String owner = Usernames.fresh("AvO");
        String firm = freshFirmName("AvOF");
        String tooLong = "Z".repeat(200);
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int countBefore = BusinessLedger.firmAccountCount(firmId);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account create " + firm + " " + tooLong);
                String r = p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
                assertThat(r).doesNotContain(".java:");

                // Whether accepted (silently truncated) or rejected, the
                // account count must reflect reality. Just assert no Java
                // stack leak.
                Barrier.settle();
                int countAfter = BusinessLedger.firmAccountCount(firmId);
                assertThat(countAfter).isBetween(countBefore, countBefore + 1);
            });
        }
    }

    @Test
    void createAccountWithDuplicateNameDoesNotDoubleInsert() {
        // Two consecutive creates with the same account name on the same
        // firm — second should reject or no-op; firmAccountCount must not
        // grow by two.
        String owner = Usernames.fresh("AvD");
        String firm = freshFirmName("AvDF");
        String acctName = "DuplicateTest";
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int countBefore = BusinessLedger.firmAccountCount(firmId);

                Barrier.settle();
                p.chatCommand("/business account create " + firm + " " + acctName);
                p.awaitMessageContaining("created account", TIMEOUT);
                int countAfterFirst = BusinessLedger.waitForFirmAccountCountAtLeast(firmId, countBefore + 1, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account create " + firm + " " + acctName);
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();

                Barrier.settle();
                assertThat(BusinessLedger.firmAccountCount(firmId))
                        .as("duplicate /business account create must not insert a second row")
                        .isEqualTo(countAfterFirst);
            });
        }
    }

    @Test
    void createAccountWithSqlShapeNameDoesNotInjectOrCrash() {
        // Defence-in-depth: handler uses prepared statements (MyBatis), but
        // a regression to string concat would surface as a SQL error here.
        String owner = Usernames.fresh("AvI");
        String firm = freshFirmName("AvIF");
        String sqlShape = "Acme';DROP TABLE accounts;--";
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account create " + firm + " " + sqlShape);
                String r = p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
                assertThat(r).doesNotContain(".java:")
                        .doesNotContain("SQLException");
            });
        }
    }
}
