package io.paradaux.treasury.api.impl;

import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.SaleRow;
import io.paradaux.treasury.api.market.SalesQuery;
import io.paradaux.treasury.api.market.SalesSummary;
import io.paradaux.treasury.api.market.TopCustomer;
import io.paradaux.treasury.api.market.TopItem;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the ChestShop sales read API ({@link SalesQueryApiImpl} →
 * {@code ChestShopSalesReadMapper}) against a real (embedded) MariaDB. Exercises
 * the actual dynamic SQL: owner scoping, filters, pagination, the BUY/SELL split,
 * the time window, and the item/customer leaderboards. chestshop_sale and
 * economy_players come from the economy-flyway migrations (PAR-239).
 */
class SalesQueryApiImplIT extends IntegrationTestBase {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final int FIRM = 7;
    private static final int OTHER_FIRM = 8;

    private SalesQueryApiImpl sales;
    private MarketApiImpl market;

    @BeforeEach
    void seed() throws Exception {
        sales = injector.getInstance(SalesQueryApiImpl.class);
        market = injector.getInstance(MarketApiImpl.class);
        // Seeds synthetic firm/account ids, so drop the V20 FK enforcement the
        // Flyway harness applied to the ChestShop analytics tables.
        dropChestShopMarketForeignKeys();
        exec("TRUNCATE TABLE chestshop_sale");
        exec("DELETE FROM economy_players");

        firmPlayer(ALICE, "Alice");
        firmPlayer(BOB, "Bob");

        // FIRM (7): two DIAMOND BUYs by Alice + one IRON_INGOT SELL by Bob (now).
        market.recordSale(firmSale("BUY", ALICE, "DIAMOND", "Diamond", 2, "5.0000", "10.00", "0.10"));
        market.recordSale(firmSale("BUY", ALICE, "DIAMOND", "Diamond", 3, "5.0000", "15.00", "0.15"));
        market.recordSale(firmSale("SELL", BOB, "IRON_INGOT", "Iron Ingot", 64, "1.0000", "64.00", "0.00"));
        // A different firm — must never leak into FIRM's results.
        market.recordSale(new ChestShopSaleRecord(null, "BUY", ALICE, 800, "BUSINESS", OTHER_FIRM, null,
                false, "DIAMOND", "DIAMOND", "Diamond", false, null,
                1, new BigDecimal("5.0000"), new BigDecimal("5.00"), BigDecimal.ZERO, "world", 9, 9, 9, 0));
        // An OLD FIRM sale (100 days ago) — outside a 30-day window.
        exec("INSERT INTO chestshop_sale (occurred_at, direction, customer_uuid_bin, shop_account_type, "
                + "shop_firm_id, material, item_key, item_name, quantity, unit_price, total_price, tax_amount) VALUES ("
                + "NOW() - INTERVAL 100 DAY, 'BUY', UNHEX('" + hex(ALICE) + "'), 'BUSINESS', " + FIRM
                + ", 'DIAMOND', 'DIAMOND', 'Diamond', 1, 5.0000, 5.00, 0.00)");
    }

    @Test
    void listSales_scopedToFirm_newestFirst_withResolvedCustomerName() {
        List<SaleRow> rows = sales.listSales(firmQuery().windowDays(30).build());

        assertThat(rows).hasSize(3); // 3 recent FIRM sales; OTHER_FIRM + the old one excluded
        // sale_id DESC tiebreak on equal timestamps → the IRON SELL (last inserted) is first.
        assertThat(rows.get(0).getDirection()).isEqualTo("SELL");
        assertThat(rows.get(0).getItemName()).isEqualTo("Iron Ingot");
        assertThat(rows.get(0).getCustomerName()).isEqualTo("Bob");
        assertThat(rows.get(0).getQuantity()).isEqualTo(64);
        assertThat(rows).allSatisfy(r -> assertThat(r.getCustomerName()).isIn("Alice", "Bob"));
    }

    @Test
    void listSales_paginates() {
        assertThat(sales.listSales(firmQuery().windowDays(30).limit(2).offset(0).build())).hasSize(2);
        assertThat(sales.listSales(firmQuery().windowDays(30).limit(2).offset(2).build())).hasSize(1);
    }

    @Test
    void listSales_filtersByDirectionItemAndCustomer() {
        assertThat(sales.listSales(firmQuery().windowDays(30).direction("BUY").build())).hasSize(2);
        assertThat(sales.listSales(firmQuery().windowDays(30).itemKey("IRON_INGOT").build())).hasSize(1);
        assertThat(sales.listSales(firmQuery().windowDays(30).customerUuid(BOB).build())).hasSize(1);
    }

    @Test
    void countSales_respectsScopeAndWindow() {
        assertThat(sales.countSales(firmQuery().build())).isEqualTo(4);            // incl. the old sale
        assertThat(sales.countSales(firmQuery().windowDays(30).build())).isEqualTo(3); // old excluded
        assertThat(sales.countSales(firmQuery().windowDays(30).direction("SELL").build())).isEqualTo(1);
    }

    @Test
    void summarize_totalsSplitAndLeaderboards() {
        SalesSummary s = sales.summarize(firmQuery().windowDays(30).build(), 5);

        assertThat(s.getSaleCount()).isEqualTo(3);
        assertThat(s.getTotalUnits()).isEqualTo(69);                 // 2 + 3 + 64
        assertThat(s.getTotalVolume()).isEqualByComparingTo("89.00"); // 10 + 15 + 64
        assertThat(s.getTotalTax()).isEqualByComparingTo("0.25");
        assertThat(s.getBuyCount()).isEqualTo(2);
        assertThat(s.getBuyVolume()).isEqualByComparingTo("25.00");
        assertThat(s.getSellCount()).isEqualTo(1);
        assertThat(s.getSellVolume()).isEqualByComparingTo("64.00");

        // Top items by units: IRON_INGOT (64) before DIAMOND (5).
        assertThat(s.getTopItems()).extracting(TopItem::getItemKey).containsExactly("IRON_INGOT", "DIAMOND");
        assertThat(s.getTopItems().get(1).getUnits()).isEqualTo(5);
        assertThat(s.getTopItems().get(1).getSaleCount()).isEqualTo(2);

        // Top customers by sale count: Alice (2) before Bob (1).
        assertThat(s.getTopCustomers()).extracting(TopCustomer::getCustomerName).containsExactly("Alice", "Bob");
        assertThat(s.getTopCustomers().get(0).getVolume()).isEqualByComparingTo("25.00");
    }

    @Test
    void unscopedQuery_isRejected() {
        // A query with no owner scope must never read server-wide sales.
        SalesQuery noScope = SalesQuery.builder().windowDays(30).build();
        assertThatThrownBy(() -> sales.listSales(noScope)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sales.countSales(noScope)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sales.summarize(noScope, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiScopeQuery_isRejected() {
        // Exactly one owner scope: setting two (here firmId + ownerUuid) is a caller
        // bug that would AND to an empty result, so it's refused up front (ADT-90).
        SalesQuery twoScopes = SalesQuery.builder().firmId(FIRM).ownerUuid(UUID.randomUUID()).windowDays(30).build();
        assertThatThrownBy(() -> sales.listSales(twoScopes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sales.countSales(twoScopes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sales.summarize(twoScopes, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static SalesQuery.SalesQueryBuilder firmQuery() {
        return SalesQuery.builder().firmId(FIRM);
    }

    private static ChestShopSaleRecord firmSale(String dir, UUID customer, String key, String name,
                                                int qty, String unit, String total, String tax) {
        return new ChestShopSaleRecord(null, dir, customer, 700, "BUSINESS", FIRM, null, false,
                key, key, name, false, null,
                qty, new BigDecimal(unit), new BigDecimal(total), new BigDecimal(tax), "world", 1, 1, 1, 0);
    }

    private static String hex(UUID u) {
        return u.toString().replace("-", "");
    }

    private void firmPlayer(UUID uuid, String name) throws Exception {
        exec("INSERT INTO economy_players (player_uuid_bin, current_name) VALUES (UNHEX('"
                + hex(uuid) + "'), '" + name + "')");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
