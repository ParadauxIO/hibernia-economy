package io.paradaux.treasury.services.impl;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;
import io.paradaux.treasury.guice.DatabaseModule;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.model.config.SourceIncomeTaxConfiguration;
import io.paradaux.treasury.services.ConfigPublishService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.testsupport.TestServicesModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfigPublishServiceImpl} against the real {@code economy_config} table
 * (economy-flyway V32): the published keys and their encoding, idempotent
 * republishing, and cleanup of keys Treasury no longer publishes (PAR-80).
 */
class ConfigPublishServiceIT extends IntegrationTestBase {

    private static NavigableMap<BigDecimal, BigDecimal> brackets(String... floorRatePairs) {
        NavigableMap<BigDecimal, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < floorRatePairs.length; i += 2) {
            m.put(new BigDecimal(floorRatePairs[i]), new BigDecimal(floorRatePairs[i + 1]));
        }
        return m;
    }

    /** A publisher over the given configs, built through Guice so {@code @Transactional} applies. */
    private ConfigPublishService publisher(BalanceTaxConfiguration balance, SourceIncomeTaxConfiguration income) {
        Injector i = Guice.createInjector(new DatabaseModule(dataSource),
                Modules.override(new TestServicesModule()).with(new AbstractModule() {
                    @Override protected void configure() {
                        bind(BalanceTaxConfiguration.class).toInstance(balance);
                        bind(SourceIncomeTaxConfiguration.class).toInstance(income);
                        bind(ConfigPublishService.class).to(ConfigPublishServiceImpl.class);
                    }
                }));
        return i.getInstance(ConfigPublishService.class);
    }

    private ConfigPublishService defaultPublisher() {
        return publisher(
                BalanceTaxConfiguration.forTesting(true, "DCGovernment",
                        brackets("0.00", "0", "100000.00", "0.01", "250000.00", "0.015")),
                SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.05"), "DCGovernment",
                        Map.of("Jobs", new BigDecimal("0.10"), "ChestShop", new BigDecimal("0.02"))));
    }

    private record Row(String owner, String type, String value, Timestamp updatedAt) {}

    private Map<String, Row> rows() throws Exception {
        Map<String, Row> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT config_key, owner, value_type, config_value, updated_at FROM economy_config ORDER BY config_key")) {
            while (rs.next()) {
                out.put(rs.getString(1), new Row(rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5)));
            }
        }
        return out;
    }

    private void execute(String sql, Object... args) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    @Test
    void publish_writesEveryTaxKey_withEffectiveValues() throws Exception {
        defaultPublisher().publish();

        Map<String, Row> rows = rows();
        assertThat(rows.keySet()).containsExactlyInAnyOrder(
                "tax.balance.enabled", "tax.balance.brackets",
                "tax.source-income-tax.enabled", "tax.source-income-tax.default-rate",
                "tax.source-income-tax.plugin-rates");
        assertThat(rows.values()).allSatisfy(r -> assertThat(r.owner()).isEqualTo("treasury"));

        assertThat(rows.get("tax.balance.enabled")).extracting(Row::type, Row::value)
                .containsExactly("BOOLEAN", "true");
        // Ascending by floor, configured scale kept.
        assertThat(rows.get("tax.balance.brackets")).extracting(Row::type, Row::value)
                .containsExactly("JSON", "[{\"floor\":\"0.00\",\"rate\":\"0\"},"
                        + "{\"floor\":\"100000.00\",\"rate\":\"0.01\"},"
                        + "{\"floor\":\"250000.00\",\"rate\":\"0.015\"}]");
        assertThat(rows.get("tax.source-income-tax.default-rate")).extracting(Row::type, Row::value)
                .containsExactly("DECIMAL", "0.05");
        // Sorted by plugin name so an unchanged config always serialises identically.
        assertThat(rows.get("tax.source-income-tax.plugin-rates")).extracting(Row::type, Row::value)
                .containsExactly("JSON", "{\"ChestShop\":\"0.02\",\"Jobs\":\"0.10\"}");
    }

    @Test
    void publish_disabledTaxes_stillPublishesTheirRates() throws Exception {
        publisher(BalanceTaxConfiguration.forTesting(false, "DCGovernment", brackets("0.00", "0.005")),
                SourceIncomeTaxConfiguration.forTesting(false, BigDecimal.ZERO, "DCGovernment", Map.of()))
                .publish();

        Map<String, Row> rows = rows();
        assertThat(rows.get("tax.balance.enabled").value()).isEqualTo("false");
        assertThat(rows.get("tax.source-income-tax.enabled").value()).isEqualTo("false");
        assertThat(rows.get("tax.source-income-tax.plugin-rates").value()).isEqualTo("{}");
        // Disabled is a flag, not an absence: the docs say "not collected" and still show the rates.
        assertThat(rows.get("tax.balance.brackets").value()).isEqualTo("[{\"floor\":\"0.00\",\"rate\":\"0.005\"}]");
        assertThat(rows.get("tax.source-income-tax.default-rate").value()).isEqualTo("0");
    }

    @Test
    void republish_movesUpdatedAtOnlyForChangedValues() throws Exception {
        defaultPublisher().publish();
        Timestamp old = Timestamp.valueOf("2026-01-01 00:00:00");
        execute("UPDATE economy_config SET updated_at = ?", old);

        // Same config again (a restart): nothing changes, not even updated_at.
        defaultPublisher().publish();
        assertThat(rows().values()).allSatisfy(r -> assertThat(r.updatedAt()).isEqualTo(old));

        // Default income rate changed in config.
        publisher(
                BalanceTaxConfiguration.forTesting(true, "DCGovernment",
                        brackets("0.00", "0", "100000.00", "0.01", "250000.00", "0.015")),
                SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.07"), "DCGovernment",
                        Map.of("Jobs", new BigDecimal("0.10"), "ChestShop", new BigDecimal("0.02"))))
                .publish();

        Map<String, Row> rows = rows();
        assertThat(rows.get("tax.source-income-tax.default-rate").value()).isEqualTo("0.07");
        assertThat(rows.get("tax.source-income-tax.default-rate").updatedAt()).isAfter(old);
        assertThat(rows.get("tax.balance.brackets").updatedAt()).isEqualTo(old);
    }

    @Test
    void publish_dropsStaleTreasuryKeys_butLeavesOtherOwners() throws Exception {
        execute("INSERT INTO economy_config (config_key, owner, value_type, config_value) VALUES (?, ?, ?, ?)",
                "tax.retired-setting", "treasury", "DECIMAL", "0.5");
        execute("INSERT INTO economy_config (config_key, owner, value_type, config_value) VALUES (?, ?, ?, ?)",
                "tax.business.enabled", "business", "BOOLEAN", "true");

        defaultPublisher().publish();

        Map<String, Row> rows = rows();
        assertThat(rows).doesNotContainKey("tax.retired-setting");
        assertThat(rows).containsKey("tax.business.enabled");
        assertThat(rows).hasSize(6);
    }
}
