package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class GovernmentGroupViewerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"gov", "government"})
    void groupViewerCannotSpendAndBothGroupRevocationsTakeEffectWithoutReconnect(String root) {
        String groupTyped = Usernames.fresh("Dept");
        String group = groupTyped.toLowerCase(Locale.ROOT);
        String marker = "inttests.viewer." + group;
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("GOwn"));
             MockPlayer viewer = MockPlayer.connectLocal(Usernames.fresh("GGrp"));
             MockPlayer direct = MockPlayer.connectLocal(Usernames.fresh("GDir"))) {
            Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(viewer.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(direct.getUuid(), TIMEOUT);
            for (String node : List.of("treasury.gov", "treasury.gov.account.view", "griefprevention.spam")) {
                Perms.grant(viewer.getUsername(), node);
            }
            RconClient.send("lp creategroup " + group);
            RconClient.send("lp group " + group + " permission set " + marker + " true");
            RconClient.send("lp user " + viewer.getUsername() + " parent add " + group);
            Perms.awaitHas(viewer.getUsername(), marker);
            try {
                Ops.run(admin.getUsername(), () -> {
                    String account = Usernames.fresh("GDept");
                    assertThat(command(admin, "/" + root + " account create " + account)).anyMatch(m -> m.contains("Created"));
                    Ledger.waitForGovernmentAccount(account, TIMEOUT);
                    command(admin, "/paya government " + account + " 23.45 GroupViewerSeed");
                    Ledger.waitForGovernmentBalance(account, new BigDecimal("23.45"), TIMEOUT);
                    assertThat(command(admin, "/" + root + " account viewer addgroup " + account + " " + groupTyped))
                            .anyMatch(m -> m.contains("Granted group") && m.contains("read-only view"));
                    assertThat(command(admin, "/" + root + " account viewer add " + account + " " + direct.getUsername()))
                            .anyMatch(m -> m.contains(direct.getUsername()) && m.contains("read-only view"));
                    var listed = command(admin, "/" + root + " account viewer list " + account);
                    assertThat(listed).anyMatch(m -> m.contains("Viewers of " + account));
                    assertThat(listed).contains(direct.getUsername(), "[group] " + groupTyped);
                    long checkpoint = Ledger.transactionCheckpoint();
                    assertBalance(viewer, root, account);
                    var history = command(viewer, "/" + root + " account history " + account);
                    assertThat(history.stream().filter(m -> m.startsWith("#")).toList()).singleElement()
                            .asString().contains("+$23.45", "GroupViewerSeed");
                    assertDenied(command(viewer, "/" + root + " payout " + account + " " + viewer.getUsername() + " 1"));
                    assertDenied(command(viewer, "/" + root + " account viewer list " + account));

                    RconClient.send("lp user " + viewer.getUsername() + " parent remove " + group);
                    Await.until("inherited marker removed", () -> Perms.check(viewer.getUsername(), marker), r -> r.contains("has=false"), TIMEOUT);
                    assertDenied(command(viewer, "/" + root + " account balance " + account));
                    RconClient.send("lp user " + viewer.getUsername() + " parent add " + group);
                    Perms.awaitHas(viewer.getUsername(), marker);
                    assertBalance(viewer, root, account);
                    assertThat(command(admin, "/" + root + " account viewer removegroup " + account + " " + groupTyped))
                            .anyMatch(m -> m.contains("Revoked group"));
                    assertDenied(command(viewer, "/" + root + " account balance " + account));
                    assertDenied(command(viewer, "/" + root + " account history " + account));
                    var remaining = command(admin, "/" + root + " account viewer list " + account);
                    assertThat(remaining).contains(direct.getUsername()).noneMatch(m -> m.contains("[group]"));
                    command(admin, "/" + root + " account viewer remove " + account + " " + direct.getUsername());
                    assertThat(command(admin, "/" + root + " account viewer list " + account)).anyMatch(m -> m.contains("No viewers."));
                    assertThat(Ledger.governmentBalance(account).orElseThrow()).isEqualByComparingTo("23.45");
                    assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
                    assertThat(Ledger.personalBalance(viewer.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                    assertThat(Ledger.personalBalance(direct.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                    assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
                });
            } finally {
                RconClient.send("lp user " + viewer.getUsername() + " parent remove " + group);
                RconClient.send("lp deletegroup " + group);
            }
        }
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }

    private static void assertBalance(MockPlayer viewer, String root, String account) {
        assertThat(command(viewer, "/" + root + " account balance " + account))
                .anyMatch(m -> m.contains(account + " balance: $23.45"));
    }

    private static void assertDenied(List<String> messages) {
        assertThat(messages).anyMatch(m -> m.contains("You do not have access to that account."));
        assertThat(messages).noneMatch(m -> m.startsWith("#") || m.contains("balance:") || m.contains("GroupViewerSeed") || m.contains("Viewers of"));
    }
}
