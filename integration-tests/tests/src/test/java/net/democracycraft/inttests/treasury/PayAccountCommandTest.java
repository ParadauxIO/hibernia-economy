package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Typed payments must resolve the requested account and debit only the caller. */
class PayAccountCommandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(strings = {"player", "government", "gov", "business", "firm", "account", "id"})
    void typedPaymentSelectsRequestedAccount(String type) {
        String name = Usernames.fresh("Targ");
        // Keep all destination kinds present and verify the typed route changes
        // only its selected ledger.
        String governmentName = Usernames.fresh("Tgov");
        String firmName = Usernames.fresh("Tfirm");
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("Typed"))) {
            Ops.run(sender.getUsername(), () -> {
                sender.chatCommand("/gov account create " + governmentName);
                sender.awaitMessageContaining("Created", TIMEOUT);
            });
            Ledger.waitForGovernmentAccount(governmentName, TIMEOUT);
            try (MockPlayer target = MockPlayer.connectLocal(name)) {
                Ledger.waitForPersonalBalance(sender.getUuid(), TIMEOUT);
                Ledger.waitForPersonalBalance(target.getUuid(), TIMEOUT);
                Ops.run(name, () -> {
                    target.chatCommand("/business create " + firmName);
                    target.awaitMessageContaining("Successfully created", TIMEOUT);
                });
                int government = Ledger.waitForGovernmentAccount(governmentName, TIMEOUT);
                int firm = BusinessLedger.waitForFirm(firmName, TIMEOUT);
                int business = BusinessLedger.firmDefaultAccountId(firm).orElseThrow();
                int personal = Ledger.personalAccountId(target.getUuid()).orElseThrow();
                int source = Ledger.personalAccountId(sender.getUuid()).orElseThrow();
                int destination = switch (type) {
                    case "government", "gov" -> government;
                    case "business", "firm" -> business;
                    default -> personal;
                };
                Barrier.settle();
                int[] accounts = {source, personal, government, business};
                BigDecimal[] before = java.util.Arrays.stream(accounts)
                        .mapToObj(id -> Ledger.accountBalanceById(id).orElseThrow()).toArray(BigDecimal[]::new);
                long checkpoint = Ledger.transactionCheckpoint();
                String token = switch (type) {
                    case "account", "id" -> "" + destination;
                    case "government", "gov" -> governmentName;
                    case "business", "firm" -> firmName;
                    default -> name;
                };
                String memo = "invoice with several words";
                sender.chatCommand("/pay-account " + type + " " + token + " 12.34 " + memo);
                sender.awaitMessageContaining("Paid", TIMEOUT);
                Barrier.settle();
                BigDecimal amount = new BigDecimal("12.34");
                for (int i = 0; i < accounts.length; i++) {
                    BigDecimal delta = accounts[i] == source ? amount.negate()
                            : accounts[i] == destination ? amount : BigDecimal.ZERO;
                    assertThat(Ledger.accountBalanceById(accounts[i]).orElseThrow())
                            .as("balance of account %s for target type %s", accounts[i], type)
                            .isEqualByComparingTo(before[i].add(delta));
                }
                var postings = Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint);
                assertThat(postings).hasSize(2);
                assertThat(postings).extracting(Ledger.Posting::transactionId).containsOnly(postings.getFirst().transactionId());
                assertThat(postings).allSatisfy(p -> assertThat(p.message()).endsWith(": " + memo));
                assertThat(postings.stream().filter(p -> p.accountId() == source).toList())
                        .singleElement().satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("-12.34"));
                assertThat(postings.stream().filter(p -> p.accountId() == destination).toList())
                        .singleElement().satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("12.34"));
            }
        }
    }

    @org.junit.jupiter.api.Test
    void explicitPermissionDenialLeavesBothAccountsUnchanged() {
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("Deny"));
             MockPlayer target = MockPlayer.connectLocal(Usernames.fresh("Targ"))) {
            BigDecimal before = Ledger.waitForPersonalBalance(sender.getUuid(), TIMEOUT);
            BigDecimal targetBefore = Ledger.waitForPersonalBalance(target.getUuid(), TIMEOUT);
            RconClient.send("lp user " + sender.getUsername() + " permission set treasury.pay false");
            Await.until("explicit payment denial applied", () -> Perms.check(sender.getUsername(), "treasury.pay"),
                    "has=false set=true"::equals, TIMEOUT);
            Barrier.settle();
            long checkpoint = Ledger.transactionCheckpoint();
            sender.chatCommand("/pay-account player " + target.getUsername() + " 12.34");
            sender.awaitMessage(m -> m.toLowerCase().contains("permission") || m.toLowerCase().contains("unknown"), TIMEOUT);
            Barrier.settle();
            assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo(before);
            assertThat(Ledger.personalBalance(target.getUuid()).orElseThrow()).isEqualByComparingTo(targetBefore);
            assertThat(Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint)).isEmpty();
        }
    }

    @org.junit.jupiter.api.Test
    void competingPaymentsCannotSpendTheSameFundsTwice() {
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("Race"));
             MockPlayer target = MockPlayer.connectLocal(Usernames.fresh("Targ"))) {
            BigDecimal initial = Ledger.waitForPersonalBalance(sender.getUuid(), TIMEOUT);
            BigDecimal targetBefore = Ledger.waitForPersonalBalance(target.getUuid(), TIMEOUT);
            assertThat(initial).isEqualByComparingTo("10000");
            Barrier.settle();
            long checkpoint = Ledger.transactionCheckpoint();
            // Both @Async handlers are dispatched without waiting for the first response.
            // This exercises contended requests, but does not force a particular DB interleaving.
            sender.chatCommand("/pay-account player " + target.getUsername() + " 7500");
            sender.chatCommand("/paya player " + target.getUsername() + " 7500");
            sender.awaitMessageContaining("Paid", TIMEOUT);
            sender.awaitMessageContaining("Insufficient funds", TIMEOUT);
            Barrier.settle();
            assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo("2500");
            assertThat(Ledger.personalBalance(target.getUuid()).orElseThrow())
                    .isEqualByComparingTo(targetBefore.add(new BigDecimal("7500")));
            var postings = Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint);
            assertThat(postings).hasSize(2);
            assertThat(postings).extracting(Ledger.Posting::transactionId).containsOnly(postings.getFirst().transactionId());
            assertThat(postings.stream().map(Ledger.Posting::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "player TARGET 0, Amount must",
            "player TARGET -1, Amount must",
            "player TARGET 0.001, Amount must",
            "player TARGET 999999, Insufficient funds",
            "player SELF 1, cannot pay yourself",
            "account SELFID 1, cannot pay yourself",
            "account notanumber 1, must be a number",
            "account 2147483647 1, No account",
            "nonsense TARGET 1, must be one of",
            "player MissingUser499 1, Unknown player",
            "government MissingGov499 1, Unknown government",
            "business MissingFirm499 1, Unknown business"
    })
    void rejectionDoesNotWriteMoneyOrPostings(String arguments, String response) {
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("Rej"));
             MockPlayer target = MockPlayer.connectLocal(Usernames.fresh("Targ"))) {
            BigDecimal sourceBefore = Ledger.waitForPersonalBalance(sender.getUuid(), TIMEOUT);
            BigDecimal targetBefore = Ledger.waitForPersonalBalance(target.getUuid(), TIMEOUT);
            int source = Ledger.personalAccountId(sender.getUuid()).orElseThrow();
            int destination = Ledger.personalAccountId(target.getUuid()).orElseThrow();
            Barrier.settle();
            int sourcePostings = Ledger.postingsCountForAccount(source);
            int targetPostings = Ledger.postingsCountForAccount(destination);
            long checkpoint = Ledger.transactionCheckpoint();
            sender.chatCommand("/paya " + arguments.replace("SELFID", "" + source)
                    .replace("SELF", sender.getUsername()).replace("TARGET", target.getUsername()));
            sender.awaitMessageContaining(response, TIMEOUT);
            Barrier.settle();
            assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo(sourceBefore);
            assertThat(Ledger.personalBalance(target.getUuid()).orElseThrow()).isEqualByComparingTo(targetBefore);
            assertThat(Ledger.postingsCountForAccount(source)).isEqualTo(sourcePostings);
            assertThat(Ledger.postingsCountForAccount(destination)).isEqualTo(targetPostings);
            assertThat(Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint)).isEmpty();
        }
    }
}
