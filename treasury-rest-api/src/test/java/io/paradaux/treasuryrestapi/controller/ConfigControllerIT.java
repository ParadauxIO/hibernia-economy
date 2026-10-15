package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/config/tax} over real HTTP against the {@code economy_config}
 * rows the Treasury plugin publishes (PAR-80): public (no Bearer token), shaped
 * from the published encoding, and tolerant of sections not yet published.
 */
class ConfigControllerIT extends EmbeddedDbIT {

    private void publish(String key, String type, String value, String updatedAt) {
        exec("INSERT INTO economy_config (config_key, owner, value_type, config_value, updated_at) VALUES (?,?,?,?,?)",
                ps -> {
                    ps.setString(1, key);
                    ps.setString(2, "treasury");
                    ps.setString(3, type);
                    ps.setString(4, value);
                    ps.setString(5, updatedAt);
                });
    }

    @Test
    void tax_returnsPublishedRates_withoutAToken() throws Exception {
        publish("tax.balance.enabled", "BOOLEAN", "true", "2026-10-01 10:00:00");
        publish("tax.balance.brackets", "JSON",
                "[{\"floor\":\"0.00\",\"rate\":\"0\"},{\"floor\":\"100000.00\",\"rate\":\"0.012\"}]",
                "2026-10-02 10:00:00");
        publish("tax.source-income-tax.enabled", "BOOLEAN", "false", "2026-10-01 10:00:00");
        publish("tax.source-income-tax.default-rate", "DECIMAL", "0.05", "2026-10-03 09:30:00");
        publish("tax.source-income-tax.plugin-rates", "JSON", "{\"ChestShop\":\"0.02\",\"Jobs\":\"0.10\"}",
                "2026-10-01 10:00:00");

        mvc.perform(get("/api/v1/config/tax"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceTax.enabled").value(true))
                .andExpect(jsonPath("$.balanceTax.brackets", hasSize(2)))
                .andExpect(jsonPath("$.balanceTax.brackets[1].floor").value("100000.00"))
                .andExpect(jsonPath("$.balanceTax.brackets[1].weeklyRate").value("0.012"))
                .andExpect(jsonPath("$.balanceTax.updatedAt").value("2026-10-02T10:00:00Z"))
                .andExpect(jsonPath("$.sourceIncomeTax.enabled").value(false))
                .andExpect(jsonPath("$.sourceIncomeTax.defaultRate").value("0.05"))
                .andExpect(jsonPath("$.sourceIncomeTax.pluginRates[0].plugin").value("ChestShop"))
                .andExpect(jsonPath("$.sourceIncomeTax.pluginRates[1].rate").value("0.10"))
                .andExpect(jsonPath("$.sourceIncomeTax.updatedAt").value("2026-10-03T09:30:00Z"));
    }

    @Test
    void tax_nothingPublished_returnsNullSections() throws Exception {
        mvc.perform(get("/api/v1/config/tax"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceTax").doesNotExist())
                .andExpect(jsonPath("$.sourceIncomeTax").doesNotExist());
    }

    @Test
    void tax_partialOrUnreadableSection_isTreatedAsUnpublished() throws Exception {
        publish("tax.balance.enabled", "BOOLEAN", "true", "2026-10-01 10:00:00");
        publish("tax.balance.brackets", "JSON", "not json", "2026-10-01 10:00:00");
        publish("tax.source-income-tax.enabled", "BOOLEAN", "true", "2026-10-01 10:00:00");

        mvc.perform(get("/api/v1/config/tax"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceTax").doesNotExist())
                .andExpect(jsonPath("$.sourceIncomeTax").doesNotExist());
    }
}
