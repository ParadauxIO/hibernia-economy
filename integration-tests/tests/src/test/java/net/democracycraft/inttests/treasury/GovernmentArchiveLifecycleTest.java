package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GovernmentArchiveLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"gov", "government"})
    void fundedArchiveHidesAccountAndRejectsOperationsWithoutDeletingMoney(String root) {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("GArc"))) {
            Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
            Ops.run(admin.getUsername(), () -> {
                String archived = Usernames.fresh("ArcA");
                String active = Usernames.fresh("ArcZ");
                command(admin, "/" + root + " account create " + archived);
                command(admin, "/" + root + " account create " + active);
                int archivedId = Ledger.waitForGovernmentAccount(archived, TIMEOUT);
                int activeId = Ledger.waitForGovernmentAccount(active, TIMEOUT);
                command(admin, "/paya government " + archived + " 23.45 ArchiveSeed");
                command(admin, "/paya government " + active + " 11 ActiveSeed");
                Ledger.waitForGovernmentBalance(archived, new BigDecimal("23.45"), TIMEOUT);
                Ledger.waitForGovernmentBalance(active, new BigDecimal("11"), TIMEOUT);
                String archivedRow = archivedId + ". " + archived + " - $23.45";
                String activeRow = activeId + ". " + active + " - $11.00";
                var before = command(admin, "/" + root + " account list");
                assertThat(before).contains(archivedRow, activeRow);
                assertThat(before.indexOf(archivedRow)).isLessThan(before.indexOf(activeRow));
                long checkpoint = Ledger.transactionCheckpoint();
                int postingCount = Ledger.postingsCountForAccount(archivedId);
                assertThat(postingCount).isEqualTo(1);
                assertThat(command(admin, "/" + root + " account archive " + archived))
                        .anyMatch(m -> m.contains("Archived government account " + archived));
                assertThat(Ledger.governmentAccountIsArchived(archived)).isTrue();
                var after = command(admin, "/" + root + " account list");
                assertThat(after).contains(activeRow).noneMatch(m -> m.contains(archived));
                for (String suffix : List.of("account balance " + archived, "account history " + archived,
                        "account transfer " + archived + " " + active + " 1",
                        "account transfer " + active + " " + archived + " 1",
                        "payout " + archived + " " + admin.getUsername() + " 1")) {
                    var denied = command(admin, "/" + root + " " + suffix);
                    assertThat(denied).anyMatch(m -> m.contains("Government account '" + archived + "' not found."));
                    assertThat(denied).noneMatch(m -> m.startsWith("#") || m.contains("balance:") || m.contains("Transferred") || m.contains("Paid"));
                }
                assertThat(command(admin, "/pay " + archived + " 1")).anyMatch(m -> m.contains("That player has never joined this server."));
                assertThat(command(admin, "/" + root + " account create " + archived)).anyMatch(m -> m.contains("already exists"));
                assertThat(Ledger.governmentAccountIsArchived(archived)).isTrue();
                assertThat(Ledger.accountBalanceById(archivedId).orElseThrow()).isEqualByComparingTo("23.45");
                assertThat(Ledger.accountBalanceById(activeId).orElseThrow()).isEqualByComparingTo("11");
                assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("9965.55");
                assertThat(Ledger.postingsCountForAccount(archivedId)).isEqualTo(postingCount);
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            });
        }
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }
}
