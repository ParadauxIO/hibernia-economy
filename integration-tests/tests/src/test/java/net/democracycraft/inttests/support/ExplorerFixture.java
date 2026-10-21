package net.democracycraft.inttests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paradaux.mockclient.MockPlayer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Same-stack browser fixture: all account creation and payment use real players. */
public final class ExplorerFixture {
    public static void main(String[] args) throws Exception {
        Duration timeout = Duration.ofSeconds(10);
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("WebS"));
             MockPlayer recipient = MockPlayer.connectLocal(Usernames.fresh("WebR"));
             MockPlayer outsider = MockPlayer.connectLocal(Usernames.fresh("WebO"))) {
            for (MockPlayer player : new MockPlayer[]{sender, recipient, outsider}) {
                Ledger.waitForPersonalBalance(player.getUuid(), timeout);
            }
            long checkpoint = Ledger.transactionCheckpoint();
            String memo = "browser-payment-" + sender.getUsername();
            sender.chatCommand("/paya player " + recipient.getUsername() + " 23.45 " + memo);
            sender.awaitMessageContaining("Paid", timeout);
            Barrier.settle();
            assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(recipient.getUuid()).orElseThrow()).isEqualByComparingTo("10023.45");
            var postings = Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint);
            assertThat(postings).hasSize(2);
            assertThat(postings.get(0).transactionId()).isEqualTo(postings.get(1).transactionId());
            Map<String, Object> fixture = new java.util.LinkedHashMap<>();
            fixture.put("senderUuid", sender.getUuid().toString());
            fixture.put("senderName", sender.getUsername());
            fixture.put("senderAccount", Ledger.personalAccountId(sender.getUuid()).orElseThrow());
            fixture.put("recipientUuid", recipient.getUuid().toString());
            fixture.put("recipientName", recipient.getUsername());
            fixture.put("recipientAccount", Ledger.personalAccountId(recipient.getUuid()).orElseThrow());
            fixture.put("outsiderUuid", outsider.getUuid().toString());
            fixture.put("outsiderName", outsider.getUsername());
            fixture.put("transactionId", postings.get(0).transactionId());
            fixture.put("memo", memo);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                    Path.of(System.getenv("IT_EXPLORER_FIXTURE")).toFile(), fixture);
        }
    }
}
