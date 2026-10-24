package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DisbandAuthorizationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void confirmationRequiresMatchingFirmCurrentOwnerAndLivePermission(String root) {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("DAdm"));
             MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("DOwn"));
             MockPlayer other = MockPlayer.connectLocal(Usernames.fresh("DOth"))) {
            for (MockPlayer p : List.of(admin, owner, other)) Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            for (MockPlayer p : List.of(owner, other)) {
                Perms.grant(p.getUsername(), "business.disband");
                Perms.grant(p.getUsername(), "griefprevention.spam");
            }
            String first = Usernames.fresh("DAFir");
            String second = Usernames.fresh("DBFir");
            Ops.run(admin.getUsername(), () -> {
                for (String firm : List.of(first, second)) {
                    command(admin, "/" + root + " create " + firm);
                    BusinessLedger.waitForFirm(firm, TIMEOUT);
                    command(admin, "/" + root + " admin set proprietor " + firm + " " + owner.getUsername());
                }
                command(admin, "/paya business " + first + " 23.45 FirstSeed");
                command(admin, "/paya business " + second + " 67.89 SecondSeed");
            });
            int firstId = BusinessLedger.waitForFirm(first, TIMEOUT);
            int secondId = BusinessLedger.waitForFirm(second, TIMEOUT);
            int firstAccount = BusinessLedger.firmDefaultAccountId(firstId).orElseThrow();
            int secondAccount = BusinessLedger.firmDefaultAccountId(secondId).orElseThrow();
            assertThat(BusinessLedger.firmProprietor(firstId)).hasValue(owner.getUuid());
            assertThat(BusinessLedger.firmProprietor(secondId)).hasValue(owner.getUuid());
            long checkpoint = Ledger.transactionCheckpoint();
            assertPrompt(command(owner, "/" + root + " disband " + first), "23.45");
            assertDenied(command(other, "/" + root + " disband " + first));
            assertDenied(command(other, "/" + root + " disband " + first + " confirm"));
            assertThat(command(owner, "/" + root + " disband " + second + " confirm"))
                    .anyMatch(m -> m.contains("No pending disband for " + second));
            assertIntact(first, firstId, firstAccount, second, secondId, secondAccount, checkpoint);

            assertPrompt(command(owner, "/" + root + " disband " + first), "23.45");
            RconClient.send("lp user " + owner.getUsername() + " permission set business.disband false");
            Await.until("disband node revoked", () -> Perms.check(owner.getUsername(), "business.disband"), r -> r.contains("has=false"), TIMEOUT);
            var denied = command(owner, "/" + root + " disband " + first + " confirm");
            assertThat(denied).noneMatch(m -> m.contains("funds have been returned"));
            assertIntact(first, firstId, firstAccount, second, secondId, secondAccount, checkpoint);
            Perms.grant(owner.getUsername(), "business.disband");

            // Reassign while the old proprietor has a pending confirmation.
            Ops.run(admin.getUsername(), () -> command(admin,
                    "/" + root + " admin set proprietor " + first + " " + other.getUsername()));
            assertThat(BusinessLedger.firmProprietor(firstId)).hasValue(other.getUuid());
            assertDenied(command(owner, "/" + root + " disband " + first + " confirm"));
            // A pending confirmation is not transferred to the new proprietor.
            assertThat(command(other, "/" + root + " disband " + first + " confirm"))
                    .anyMatch(m -> m.contains("No pending disband for " + first));
            assertIntact(first, firstId, firstAccount, second, secondId, secondAccount, checkpoint);
            assertPrompt(command(other, "/" + root + " disband " + first), "23.45");
            assertThat(command(other, "/" + root + " disband " + first + " confirm"))
                    .anyMatch(m -> m.contains("has been disbanded, and funds have been returned"));
            assertThat(BusinessLedger.firmIsArchived(first)).isTrue();
            assertThat(BusinessLedger.activeFirmAccountCount(firstId)).isZero();
            assertThat(BusinessLedger.firmIsArchived(second)).isFalse();
            assertThat(BusinessLedger.activeFirmAccountCount(secondId)).isEqualTo(1);
            assertThat(Ledger.accountBalanceById(firstAccount).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.accountBalanceById(secondAccount).orElseThrow()).isEqualByComparingTo("67.89");
            assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("9908.66");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(other.getUuid()).orElseThrow()).isEqualByComparingTo("10023.45");
            var postings = Ledger.initiatedPostingsAfter(other.getUuid(), checkpoint);
            assertThat(postings).hasSize(2);
            assertThat(postings.stream().filter(p -> p.accountId() == firstAccount).map(Ledger.Posting::amount).toList())
                    .containsExactly(new BigDecimal("-23.45"));
            int personal = Ledger.personalAccountId(other.getUuid()).orElseThrow();
            assertThat(postings.stream().filter(p -> p.accountId() == personal).map(Ledger.Posting::amount).toList())
                    .containsExactly(new BigDecimal("23.45"));
        }
    }

    private static void assertIntact(String first, int firstId, int firstAccount, String second, int secondId, int secondAccount, long checkpoint) {
        assertThat(BusinessLedger.firmIsArchived(first)).isFalse();
        assertThat(BusinessLedger.firmIsArchived(second)).isFalse();
        assertThat(BusinessLedger.activeFirmAccountCount(firstId)).isEqualTo(1);
        assertThat(BusinessLedger.activeFirmAccountCount(secondId)).isEqualTo(1);
        assertThat(Ledger.accountBalanceById(firstAccount).orElseThrow()).isEqualByComparingTo("23.45");
        assertThat(Ledger.accountBalanceById(secondAccount).orElseThrow()).isEqualByComparingTo("67.89");
        assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
    }

    private static void assertPrompt(List<String> messages, String amount) {
        assertThat(messages).anyMatch(m -> m.contains("is permanent") && m.contains("$" + amount));
    }

    private static void assertDenied(List<String> messages) {
        assertThat(messages).anyMatch(m -> m.contains("You do not have permission to run this command."));
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }
}
