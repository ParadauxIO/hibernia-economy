package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FirmListPaginationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private record FirmRow(String name, String owner, int id) {}

    @Test
    void twoFullPagesHaveExactRowsForOfflineOwnersAcrossAllAliases() {
        List<FirmRow> fixtures = new ArrayList<>();
        // Separate real proprietors preserve the production creation cooldown.
        // Closing each connection also exercises stored names for offline owners.
        for (int i = 0; i < 20; i++) {
            try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("PgOwn"))) {
                Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
                String name = Usernames.fresh("PgFrm");
                Ops.run(owner.getUsername(), () -> command(owner, "/business create " + name));
                int id = BusinessLedger.waitForFirm(name, TIMEOUT);
                fixtures.add(new FirmRow(name, owner.getUsername(), id));
                assertThat(BusinessLedger.firmProprietor(id)).hasValue(owner.getUuid());
                assertThat(BusinessLedger.firmDefaultBalance(id).orElseThrow()).isEqualByComparingTo("0");
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            }
        }
        Collections.reverse(fixtures);
        List<String> first = expectedRows(fixtures.subList(0, 10));
        List<String> second = expectedRows(fixtures.subList(10, 20));
        for (String root : List.of("business", "firm", "company", "db", "democracybusiness")) {
            try (MockPlayer reader = MockPlayer.connectLocal(Usernames.fresh("PgRe"))) {
                Ledger.waitForPersonalBalance(reader.getUuid(), TIMEOUT);
                Perms.grant(reader.getUsername(), "business.list.all");
                long checkpoint = Ledger.transactionCheckpoint();
                var implicit = command(reader, "/" + root + " list all");
                assertThat(rows(implicit)).containsExactlyElementsOf(first);
                assertThat(implicit).anyMatch(m -> m.contains("Firms (page 1)"));
                assertThat(command(reader, "/" + root + " list all 1")).isEqualTo(implicit);
                var next = command(reader, "/" + root + " list all 2");
                assertThat(rows(next)).containsExactlyElementsOf(second);
                assertThat(next).anyMatch(m -> m.contains("Firms (page 2)"));
                for (int page : List.of(0, -1)) {
                    assertThat(rows(command(reader, "/" + root + " list all " + page)))
                            .containsExactlyElementsOf(first);
                }
                var distant = command(reader, "/" + root + " list all 9999999");
                assertThat(rows(distant)).isEmpty();
                assertThat(distant).anyMatch(m -> m.contains("No firms on page 9999999."));
                RconClient.send("lp user " + reader.getUsername() + " permission set business.list.all false");
                Await.until("list permission revoked", () -> Perms.check(reader.getUsername(), "business.list.all"),
                        r -> r.contains("has=false"), TIMEOUT);
                for (String suffix : List.of("", " 2")) {
                    var denied = command(reader, "/" + root + " list all" + suffix);
                    assertThat(rows(denied)).isEmpty();
                    assertThat(denied).noneMatch(m -> m.contains("Firms (page"));
                }
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
                assertThat(Ledger.personalBalance(reader.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            }
        }
    }

    private static List<String> expectedRows(List<FirmRow> firms) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < firms.size(); i++) {
            FirmRow f = firms.get(i);
            rows.add((i + 1) + ". " + f.name() + " owned by " + f.owner() + " (" + f.id() + ")");
        }
        return rows;
    }

    private static List<String> rows(List<String> messages) {
        return messages.stream().filter(m -> m.matches("\\d+\\. .* owned by .*" )).toList();
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages(); player.chatCommand(command); Barrier.settle();
        return player.collectedMessages();
    }
}
