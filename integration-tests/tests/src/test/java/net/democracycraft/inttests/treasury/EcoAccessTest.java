package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Real command permissions, offline lookup and both sides of administrative adjustments. */
class EcoAccessTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @CsvSource({"give,12.34,10012.34,false", "take,12.34,9987.66,false", "set,42.42,42.42,false",
            "reset,'',10000,false", "give,12.34,10012.34,true", "take,12.34,9987.66,true",
            "set,42.42,42.42,true", "reset,'',10000,true"})
    void eachPermissionGatesOnlineAndOfflineTargets(String operation, String amount, String expected, boolean offline) {
        try (var admin = MockPlayer.connectLocal(Usernames.fresh("EcAd"));
             var target = MockPlayer.connectLocal(Usernames.fresh("EcTg"))) {
            Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(target.getUuid(), TIMEOUT);
            if (operation.equals("reset")) {
                RconClient.send("eco set " + target.getUsername() + " 5");
                Barrier.settle();
                assertThat(Ledger.personalBalance(target.getUuid()).orElseThrow()).isEqualByComparingTo("5");
            }
            if (offline) {
                target.close();
                Await.until("eco target offline", () -> Perms.check(target.getUsername(), "treasury.eco"),
                        "offline"::equals, TIMEOUT);
            }
            Perms.grant(admin.getUsername(), "treasury.eco");
            String permission = "treasury.eco." + operation;
            String command = "/eco " + operation + " " + target.getUsername() + (amount.isEmpty() ? "" : " " + amount);
            int account = Ledger.personalAccountId(target.getUuid()).orElseThrow();
            BigDecimal before = Ledger.accountBalanceById(account).orElseThrow();
            deny(admin, permission);
            long checkpoint = Ledger.transactionCheckpoint();
            runDenied(admin, command);
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(before);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);

            Perms.grant(admin.getUsername(), permission);
            admin.drainMessages();
            admin.chatCommand(command);
            admin.awaitMessageContaining(switch (operation) {
                case "give" -> "Gave";
                case "take" -> "Took";
                case "set" -> "Set";
                default -> "Reset";
            }, TIMEOUT);
            Barrier.settle();
            assertAdjustment(admin.getUuid(), checkpoint, account, new BigDecimal(expected), before);

            deny(admin, permission);
            checkpoint = Ledger.transactionCheckpoint();
            runDenied(admin, command);
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(expected);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    @ParameterizedTest
    @CsvSource({"give,12.34,10012.34", "take,12.34,9987.66", "set,42.42,42.42", "reset,'',10000"})
    void consoleAdjustsOfflineTargetWithPairedPostings(String operation, String amount, String expected) {
        try (var target = MockPlayer.connectLocal(Usernames.fresh("EcCo"))) {
            Ledger.waitForPersonalBalance(target.getUuid(), TIMEOUT);
            if (operation.equals("reset")) {
                RconClient.send("eco set " + target.getUsername() + " 5");
                Barrier.settle();
            }
            int account = Ledger.personalAccountId(target.getUuid()).orElseThrow();
            BigDecimal before = Ledger.accountBalanceById(account).orElseThrow();
            target.close();
            Await.until("console eco target offline", () -> Perms.check(target.getUsername(), "treasury.eco"),
                    "offline"::equals, TIMEOUT);
            long checkpoint = Ledger.transactionCheckpoint();
            RconClient.send("eco " + operation + " " + target.getUsername() + (amount.isEmpty() ? "" : " " + amount));
            Barrier.settle();
            assertAdjustment(new UUID(0, 1), checkpoint, account, new BigDecimal(expected), before);
        }
    }

    private static void assertAdjustment(UUID actor, long checkpoint, int account, BigDecimal expected, BigDecimal before) {
        assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(expected);
        var postings = Ledger.initiatedPostingsAfter(actor, checkpoint);
        assertThat(postings).hasSize(2);
        assertThat(Ledger.initiatedTransactionCountAfter(actor, checkpoint)).isEqualTo(1);
        assertThat(postings).extracting(Ledger.Posting::transactionId).containsOnly(postings.getFirst().transactionId());
        var target = postings.stream().filter(p -> p.accountId() == account).toList();
        var counterpart = postings.stream().filter(p -> p.accountId() != account).toList();
        assertThat(target).singleElement().satisfies(p -> assertThat(p.amount()).isEqualByComparingTo(expected.subtract(before)));
        assertThat(counterpart).singleElement().satisfies(p -> assertThat(p.amount()).isEqualByComparingTo(before.subtract(expected)));
        // Explicit account identity, not just any balancing entry.
        assertThat(counterpart.getFirst().accountId()).isEqualTo(Ledger.governmentAccountId("Eco").orElseThrow());
    }

    private static void deny(MockPlayer admin, String permission) {
        RconClient.send("lp user " + admin.getUsername() + " permission set " + permission + " false");
        Await.until("eco permission denied", () -> Perms.check(admin.getUsername(), permission),
                result -> result.contains("has=false"), TIMEOUT);
    }

    private static void runDenied(MockPlayer admin, String command) {
        admin.drainMessages();
        admin.chatCommand(command);
        admin.awaitMessage(m -> m.toLowerCase(java.util.Locale.ROOT).contains("permission")
                || m.toLowerCase(java.util.Locale.ROOT).contains("unknown"), TIMEOUT);
        Barrier.settle();
    }
}
