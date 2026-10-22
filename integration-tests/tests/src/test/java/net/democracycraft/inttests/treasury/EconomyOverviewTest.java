package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EconomyOverviewTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private record Totals(BigDecimal players, BigDecimal businesses, BigDecimal governments, BigDecimal total) {}

    @Test
    void overviewTracksKnownTransfersBetweenCategoriesAndConservesTotalSupply() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Econ"));
             MockPlayer other = MockPlayer.connectLocal(Usernames.fresh("Econ"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(other.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("Firm");
            String government = Usernames.fresh("Gov");
            Perms.grant(owner.getUsername(), "griefprevention.spam");
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                owner.chatCommand("/gov account create " + government);
                owner.awaitMessageContaining("Created", TIMEOUT);
                Barrier.settle();
            });
            Perms.grant(owner.getUsername(), "treasury.economy");
            var before = overview(owner);
            BigDecimal personal = Ledger.personalBalance(owner.getUuid()).orElseThrow();
            long checkpoint = Ledger.transactionCheckpoint();
            String[] targets = {"business " + firm + " 91", "government " + government + " 37", "player " + other.getUsername() + " 23"};
            for (String target : targets) {
                owner.drainMessages();
                owner.chatCommand("/paya " + target);
                owner.awaitMessageContaining("Paid", TIMEOUT);
                Barrier.settle();
            }
            var after = overview(owner);
            assertThat(after.players()).isEqualByComparingTo(before.players().subtract(new BigDecimal("128")));
            assertThat(after.businesses()).isEqualByComparingTo(before.businesses().add(new BigDecimal("91")));
            assertThat(after.governments()).isEqualByComparingTo(before.governments().add(new BigDecimal("37")));
            assertThat(after.total()).isEqualByComparingTo(before.total());
            assertThat(after.total()).isEqualByComparingTo(after.players().add(after.businesses()).add(after.governments()));
            String service = ApiTokens.mintServiceKey(owner.getUuid()).token();
            List<Integer> accounts = List.of(Ledger.personalAccountId(other.getUuid()).orElseThrow(),
                    BusinessLedger.firmDefaultAccountId(BusinessLedger.firmId(firm).orElseThrow()).orElseThrow(),
                    Ledger.governmentAccountId(government).orElseThrow());
            List<BigDecimal> funds = List.of(new BigDecimal("10023"), new BigDecimal("91"), new BigDecimal("37"));
            long archiveCheckpoint = Ledger.transactionCheckpoint();
            for (int category = 0; category < accounts.size(); category++) {
                int account = accounts.get(category);
                String path = "/admin/accounts/" + account;
                var archived = RestApi.call("POST", path + "/archive", service, null);
                assertThat(archived.status()).as(archived.body()).isEqualTo(200);
                assertThat(archived.json().path("archived").asBoolean()).isTrue();
                var hidden = overview(owner);
                assertThat(hidden.players()).isEqualByComparingTo(after.players().subtract(category == 0 ? funds.get(category) : BigDecimal.ZERO));
                assertThat(hidden.businesses()).isEqualByComparingTo(after.businesses().subtract(category == 1 ? funds.get(category) : BigDecimal.ZERO));
                assertThat(hidden.governments()).isEqualByComparingTo(after.governments().subtract(category == 2 ? funds.get(category) : BigDecimal.ZERO));
                assertThat(hidden.total()).isEqualByComparingTo(after.total().subtract(funds.get(category)));
                assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(funds.get(category));
                var restored = RestApi.call("POST", path + "/unarchive", service, null);
                assertThat(restored.status()).as(restored.body()).isEqualTo(200);
                assertThat(restored.json().path("archived").asBoolean()).isFalse();
                assertThat(overview(owner)).isEqualTo(after);
                assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(funds.get(category));
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(archiveCheckpoint);
            }
            owner.drainMessages();
            owner.chatCommand("/economy help");
            assertThat(owner.awaitMessageContaining("Economy Overview Help", TIMEOUT)).contains("players, businesses and governments");
            RconClient.send("lp user " + owner.getUsername() + " permission set treasury.economy false");
            Await.until("economy permission revoked", () -> Perms.check(owner.getUsername(), "treasury.economy"), r -> r.contains("has=false"), TIMEOUT);
            owner.drainMessages();
            owner.chatCommand("/economy");
            Barrier.settle();
            assertThat(owner.collectedMessages()).noneMatch(m -> m.contains("Economy Overview") || m.startsWith("Total supply:"));
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo(personal.subtract(new BigDecimal("151")));
            assertThat(Ledger.personalBalance(other.getUuid()).orElseThrow()).isEqualByComparingTo("10023");
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint)).hasSize(6);
        }
    }

    private static Totals overview(MockPlayer owner) {
        // This journey includes repeated reads; allow vanilla's command budget to recover.
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        owner.drainMessages();
        owner.chatCommand("/economy");
        owner.awaitMessageContaining("Economy Overview", TIMEOUT);
        Barrier.settle();
        var messages = owner.collectedMessages();
        return new Totals(amount(messages, "Players:"), amount(messages, "Businesses:"), amount(messages, "Governments:"), amount(messages, "Total supply:"));
    }

    private static BigDecimal amount(List<String> messages, String label) {
        var matching = messages.stream().filter(m -> m.startsWith(label)).toList();
        assertThat(matching).hasSize(1);
        return new BigDecimal(matching.get(0).substring(label.length()).replace("$", "").replace(",", "").trim());
    }
}
