package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ArchivedBusinessShopTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @AfterEach
    void restoreAccessGate() {
        RconClient.send("diag config ignoreAccessPerms true");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void existingShopCannotAcceptPaymentsAfterFirmDisband(boolean ignoreAccessPerms) throws Exception {
        RconClient.send("diag config ignoreAccessPerms " + ignoreAccessPerms);
        assertThat(RconClient.send("diag configget ignoreAccessPerms"))
                .contains("ignoreAccessPerms = " + ignoreAccessPerms);
        String firm = Usernames.fresh("DeadF");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("DeadO"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("DeadB"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            Ops.run(owner.getUsername(), () -> assertThat(shop.createSellShop(owner.getUsername(),
                    "B:" + Integer.toString(account, 36).toUpperCase(), 2, 7, "DIRT")).contains("cancelled=false"));
            shop.clickBuy(buyer, buyer.getUsername());
            Market.waitForSaleCount(buyer.getUuid(), 1, TIMEOUT);
            Barrier.settle();
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("7");
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business disband " + firm);
                owner.awaitMessageContaining("is permanent", TIMEOUT);
                owner.chatCommand("/business disband " + firm + " confirm");
                owner.awaitMessageContaining("has been disbanded, and funds have been returned", TIMEOUT);
            });
            Barrier.settle();
            assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isZero();
            assertThat(BusinessLedger.firmDefaultAccountId(id)).isEmpty();
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10007");
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9993");
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isEqualTo(2);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(22);
            long checkpoint = Ledger.transactionCheckpoint();
            shop.clickBuy(buyer, buyer.getUsername());
            Barrier.settle();
            // Collect every effect so a rejection failure still records money, items and sale evidence.
            assertSoftly(soft -> {
                soft.assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).as("buyer balance").isEqualByComparingTo("9993");
                soft.assertThat(Ledger.accountBalanceById(account).orElseThrow()).as("archived account balance").isEqualByComparingTo("0");
                soft.assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).as("former owner balance").isEqualByComparingTo("10007");
                soft.assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).as("buyer items").isEqualTo(2);
                soft.assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).as("chest stock").isEqualTo(22);
                soft.assertThat(Market.saleCountForCustomer(buyer.getUuid())).as("recorded sales").isEqualTo(1);
                soft.assertThat(Ledger.transactionCheckpoint()).as("no new ledger transaction").isEqualTo(checkpoint);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void existingShopCannotTakeCustomerItemsAfterFirmDisband(boolean ignoreAccessPerms) throws Exception {
        RconClient.send("diag config ignoreAccessPerms " + ignoreAccessPerms);
        assertThat(RconClient.send("diag configget ignoreAccessPerms"))
                .contains("ignoreAccessPerms = " + ignoreAccessPerms);
        String firm = Usernames.fresh("GoneF");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("GoneO"));
             var seller = MockPlayer.connectLocal(Usernames.fresh("GoneS"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(seller.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                owner.chatCommand("/business deposit " + firm + " 100");
                owner.awaitMessageContaining("Paid", TIMEOUT);
            });
            int id = BusinessLedger.firmId(firm).orElseThrow();
            int account = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            // Direct sign fixture; both sell attempts use actual customer interaction packets.
            shop.setupBuyShop("B:" + Integer.toString(account, 36).toUpperCase(), 2, 7, "DIRT");
            Inv.give(seller.getUsername(), "DIRT", 4);
            Inv.waitForPlayerCount(seller.getUsername(), "DIRT", 4, TIMEOUT);
            shop.clickSell(seller, seller.getUsername());
            var sale = Market.waitForSaleForCustomer(seller.getUuid(), TIMEOUT);
            Barrier.settle();
            assertThat(sale.direction()).isEqualTo("SELL");
            assertThat(sale.shopFirmId()).isEqualTo(id);
            assertThat(sale.quantity()).isEqualTo(2);
            assertThat(sale.totalPrice()).isEqualByComparingTo("7");
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("93");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9900");
            assertThat(Ledger.personalBalance(seller.getUuid()).orElseThrow()).isEqualByComparingTo("10007");
            assertThat(Inv.playerCount(seller.getUsername(), "DIRT")).isEqualTo(2);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(2);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business disband " + firm);
                owner.awaitMessageContaining("is permanent", TIMEOUT);
                owner.chatCommand("/business disband " + firm + " confirm");
                owner.awaitMessageContaining("has been disbanded, and funds have been returned", TIMEOUT);
            });
            Barrier.settle();
            assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isZero();
            assertThat(BusinessLedger.firmDefaultAccountId(id)).isEmpty();
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9993");
            long checkpoint = Ledger.transactionCheckpoint();
            shop.clickSell(seller, seller.getUsername());
            Barrier.settle();
            assertSoftly(soft -> {
                soft.assertThat(Ledger.personalBalance(seller.getUuid()).orElseThrow()).as("seller balance").isEqualByComparingTo("10007");
                soft.assertThat(Ledger.accountBalanceById(account).orElseThrow()).as("archived account balance").isEqualByComparingTo("0");
                soft.assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).as("refunded owner balance").isEqualByComparingTo("9993");
                soft.assertThat(Inv.playerCount(seller.getUsername(), "DIRT")).as("seller retains items").isEqualTo(2);
                soft.assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).as("chest stock").isEqualTo(2);
                soft.assertThat(Market.saleCountForCustomer(seller.getUuid())).as("recorded sales").isEqualTo(1);
                soft.assertThat(Ledger.transactionCheckpoint()).as("no new ledger transaction").isEqualTo(checkpoint);
            });
        }
    }

}
