package net.democracycraft.inttests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paradaux.mockclient.MockPlayer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Same-stack browser fixture: all account creation and payment use real players. */
public final class ExplorerFixture {
    private static String browserName(String prefix, String fixed) {
        return "true".equals(System.getenv("IT_KEYCLOAK_PROFILE")) ? fixed : Usernames.fresh(prefix);
    }

    public static void main(String[] args) throws Exception {
        Duration timeout = Duration.ofSeconds(10);
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("WebS"));
             MockPlayer recipient = MockPlayer.connectLocal(browserName("WebR", "WebAuthNew502"));
             MockPlayer outsider = MockPlayer.connectLocal(browserName("WebO", "WebAuthOld502"))) {
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
            // A real Business proprietor supplies the scheduled browser role fixture.
            String firm = Usernames.fresh("WebF");
            Ops.run(outsider.getUsername(), () -> {
                outsider.chatCommand("/business create " + firm);
                outsider.awaitMessageContaining("Successfully created", timeout);
                Barrier.settle();
            });
            int firmId = BusinessLedger.waitForFirm(firm, timeout);
            fixture.put("outsiderFirm", firmId);
            fixture.put("outsiderFirmName", firm);
            Ops.run(outsider.getUsername(), () -> {
                outsider.chatCommand("/business deposit " + firm + " 123.45");
                outsider.awaitMessageContaining("Deposited", timeout);
                Barrier.settle();
            });
            int firmAccount = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            fixture.put("firmAccount", firmAccount);
            try (var customer = MockPlayer.connectLocal(Usernames.fresh("WBuy"));
                 var shop = ChestShopFixture.create()) {
                Ledger.waitForPersonalBalance(customer.getUuid(), timeout);
                shop.setupSellShop("B:" + Integer.toString(firmAccount, 36).toUpperCase(java.util.Locale.ROOT), 2, 17, "diamond");
                shop.clickBuy(customer, customer.getUsername());
                var sale = Market.waitForSaleForCustomer(customer.getUuid(), timeout);
                assertThat(sale.shopFirmId()).isEqualTo(firmId);
                assertThat(sale.quantity()).isEqualTo(2);
                assertThat(sale.totalPrice()).isEqualByComparingTo("17");
                assertThat(Inv.playerCount(customer.getUsername(), "DIAMOND")).isEqualTo(2);
                assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(22);
                assertThat(Ledger.accountBalanceById(firmAccount).orElseThrow()).isEqualByComparingTo("140.45");
                fixture.put("itemKey", sale.itemKey());
                Ops.run(outsider.getUsername(), () -> {
                    outsider.chatCommand("/fine issue GovernmentFines " + customer.getUsername() + " 11.25 browser-private-reason");
                    outsider.awaitMessageContaining("Issued", timeout);
                    Barrier.settle();
                });
                assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9971.75");
                assertThat(Ledger.accountBalanceById(Ledger.governmentAccountId("GovernmentFines").orElseThrow()).orElseThrow())
                        .isEqualByComparingTo("11.25");
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                    Path.of(System.getenv("IT_EXPLORER_FIXTURE")).toFile(), fixture);
        }
    }
}
