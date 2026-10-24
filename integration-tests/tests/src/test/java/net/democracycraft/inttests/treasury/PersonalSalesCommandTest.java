package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class PersonalSalesCommandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void salesAndSummaryShowOnlyOwnersTradesWithCorrectDirectionQuantitiesAndTotals() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Sale"));
             MockPlayer customer = MockPlayer.connectLocal(Usernames.fresh("Cust"));
             ChestShopFixture buy = ChestShopFixture.create();
             ChestShopFixture sell = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(customer.getUuid(), TIMEOUT);
            Perms.grant(owner.getUsername(), "treasury.sales");
            Perms.grant(customer.getUsername(), "treasury.sales");
            Perms.grant(owner.getUsername(), "griefprevention.spam");
            RconClient.send("diag warmname " + owner.getUsername());
            buy.setupSellShop(owner.getUsername(), 3, 37, "dirt");
            sell.setupBuyShop(owner.getUsername(), 2, 11, "dirt");
            buy.clickBuy(customer, customer.getUsername());
            sell.clickSell(customer, customer.getUsername());
            for (String command : new String[]{"sales", "sales 1"}) {
                owner.drainMessages();
                owner.chatCommand("/" + command);
                assertThat(owner.awaitMessageContaining("Your Sales", TIMEOUT)).contains("Page 1/1");
                Barrier.settle();
                var lines = owner.collectedMessages().stream().filter(m -> m.contains("x Dirt")).toList();
                assertThat(lines).hasSize(2);
                assertThat(lines.get(0)).contains("Bought 2x Dirt", customer.getUsername(), "$11.00", "tax $0.00");
                assertThat(lines.get(1)).contains("Sold 3x Dirt", customer.getUsername(), "$37.00", "tax $0.00");
            }
            for (String command : new String[]{"sales summary", "sales summary 1"}) {
                owner.drainMessages();
                owner.chatCommand("/" + command);
                assertThat(owner.awaitMessageContaining("Your Sales Summary", TIMEOUT))
                        .contains(command.endsWith(" 1") ? "last 1 days" : "last 30 days");
                Barrier.settle();
                assertThat(owner.collectedMessages()).anyMatch(m -> m.contains("Sales: 2") && m.contains("Units: 5") && m.contains("Revenue: $48.00") && m.contains("Tax: $0.00"));
                assertThat(owner.collectedMessages()).anyMatch(m -> m.contains("Sold: 1 ($37.00)") && m.contains("Bought: 1 ($11.00)"));
                assertThat(owner.collectedMessages()).anyMatch(m -> m.contains("Dirt") && m.contains("5 units") && m.contains("$48.00"));
            }
            customer.drainMessages();
            customer.chatCommand("/sales");
            customer.awaitMessageContaining("no recorded ChestShop sales", TIMEOUT);
            owner.drainMessages();
            owner.chatCommand("/sales 2");
            owner.awaitMessageContaining("no recorded ChestShop sales", TIMEOUT);
            owner.drainMessages();
            owner.chatCommand("/sales help");
            assertThat(owner.awaitMessageContaining("Sales Help", TIMEOUT)).contains("/sales summary");
            RconClient.send("lp user " + owner.getUsername() + " permission set treasury.sales false");
            Await.until("sales permission revoked", () -> Perms.check(owner.getUsername(), "treasury.sales"), r -> r.contains("has=false"), TIMEOUT);
            owner.drainMessages();
            owner.chatCommand("/sales");
            Barrier.settle();
            assertThat(owner.collectedMessages()).noneMatch(m -> m.contains("Your Sales") || m.contains("x Dirt"));
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10026");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9974");
            assertThat(Inv.chestCount(buy.chestPos(), "DIRT")).isEqualTo(33);
            assertThat(Inv.chestCount(sell.chestPos(), "DIRT")).isEqualTo(2);
            assertThat(Inv.playerCount(customer.getUsername(), "DIRT")).isEqualTo(1);
            assertThat(Market.saleCountForCustomer(customer.getUuid())).isEqualTo(2);
        }
    }
}
