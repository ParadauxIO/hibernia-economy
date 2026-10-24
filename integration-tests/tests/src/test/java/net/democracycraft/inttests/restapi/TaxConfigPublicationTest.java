package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.DriverManager;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/** Changes shared Treasury configuration only in the dedicated tax profile. */
@EnabledIfEnvironmentVariable(named = "IT_TAX_PROFILE", matches = "true")
class TaxConfigPublicationTest {
    @Test
    void paperReloadPublishesExactPublicTaxSnapshotAndInvalidReloadKeepsIt() throws Exception {
        Path config = Path.of(System.getenv("IT_SERVER_DIR"), "plugins", "Treasury", "config.yml");
        String original = Files.readString(config);
        int start = original.indexOf("\ntax:\n");
        int end = original.indexOf("\nfines:\n", start);
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(start);
        String tax = """

                tax:
                  balance:
                    enabled: true
                    government-account: DCGovernment
                    brackets:
                      100000: 0.03
                      0: 0.00
                      50000: 0.015
                  source-income-tax:
                    enabled: true
                    default-rate: 0.025
                    government-account: DCGovernment
                    plugin-rates:
                      Zeta: 0.12
                      Alpha: 0.04
                """;
        String replacement = original.substring(0, start) + tax + original.substring(end);
        long checkpoint = Ledger.transactionCheckpoint();
        try {
            Files.writeString(config, replacement);
            assertThat(RconClient.send("treasury reload")).contains("Reloaded config.yml");
            JsonNode first = snapshot();
            assertThat(first.size()).isEqualTo(2);
            JsonNode balance = first.path("balanceTax"), income = first.path("sourceIncomeTax");
            assertThat(balance.path("enabled").asBoolean()).isTrue();
            assertThat(balance.path("brackets")).isEqualTo(new ObjectMapper().readTree("""
                    [{"floor":"0","weeklyRate":"0.0"},{"floor":"50000","weeklyRate":"0.015"},{"floor":"100000","weeklyRate":"0.03"}]
                    """));
            assertThat(income.path("enabled").asBoolean()).isTrue();
            assertThat(income.path("defaultRate").asText()).isEqualTo("0.025");
            assertThat(income.path("pluginRates")).isEqualTo(new ObjectMapper().readTree("""
                    [{"plugin":"Alpha","rate":"0.04"},{"plugin":"Zeta","rate":"0.12"}]
                    """));
            assertThat(Instant.parse(balance.path("updatedAt").asText())).isBeforeOrEqualTo(Instant.now());
            assertThat(Instant.parse(income.path("updatedAt").asText())).isBeforeOrEqualTo(Instant.now());
            assertThat(RconClient.send("treasury reload")).contains("Reloaded config.yml");
            assertThat(snapshot()).as("unchanged reload preserves published values and timestamps").isEqualTo(first);
            Files.writeString(config, replacement.replace("default-rate: 0.025", "default-rate: 1.50"));
            assertThat(RconClient.send("treasury reload")).doesNotContain("Reloaded config.yml");
            assertThat(snapshot()).as("invalid income rate must not partially republish").isEqualTo(first);
            Files.writeString(config, replacement.replace("enabled: true", "enabled: false"));
            assertThat(RconClient.send("treasury reload")).contains("Reloaded config.yml");
            JsonNode disabled = snapshot();
            assertThat(disabled.path("balanceTax").path("enabled").asBoolean()).isFalse();
            assertThat(disabled.path("sourceIncomeTax").path("enabled").asBoolean()).isFalse();
            assertThat(disabled.path("balanceTax").path("brackets")).isEqualTo(balance.path("brackets"));
            assertThat(disabled.path("sourceIncomeTax").path("pluginRates")).isEqualTo(income.path("pluginRates"));
            assertThat(disabled.path("sourceIncomeTax").path("defaultRate")).isEqualTo(income.path("defaultRate"));
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        } finally {
            Files.writeString(config, original);
            assertThat(RconClient.send("treasury reload")).contains("Reloaded config.yml");
        }
    }
    @ParameterizedTest
    @CsvSource({
            "tax.balance.brackets,balanceTax,sourceIncomeTax,missing",
            "tax.balance.brackets,balanceTax,sourceIncomeTax,malformed",
            "tax.source-income-tax.plugin-rates,sourceIncomeTax,balanceTax,missing",
            "tax.source-income-tax.plugin-rates,sourceIncomeTax,balanceTax,malformed"
    })
    void incompleteOrUnreadablePublishedSectionIsNullAndReloadRestoresIt(
            String key, String affected, String unaffected, String fault) throws Exception {
        assertThat(RconClient.send("treasury reload")).contains("Reloaded config.yml");
        JsonNode baseline = snapshot();
        assertThat(baseline.path(affected).isObject()).isTrue();
        assertThat(baseline.path(unaffected).isObject()).isTrue();
        long checkpoint = Ledger.transactionCheckpoint();
        try {
            // Controlled malformed/missing published-row fixture; no live config objects are changed.
            try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
                 var q = c.prepareStatement(fault.equals("missing")
                         ? "DELETE FROM economy_config WHERE config_key = ?"
                         : "UPDATE economy_config SET config_value = '{broken' WHERE config_key = ?")) {
                q.setString(1, key);
                assertThat(q.executeUpdate()).isEqualTo(1);
            }
            JsonNode degraded = snapshot();
            assertThat(degraded.has(affected)).isTrue();
            assertThat(degraded.path(affected).isNull()).isTrue();
            assertThat(degraded.path(unaffected)).isEqualTo(baseline.path(unaffected));
            assertThat(degraded.size()).isEqualTo(2);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        } finally {
            assertThat(RconClient.send("treasury reload")).contains("Reloaded config.yml");
        }
        JsonNode recovered = snapshot();
        var expectedSection = baseline.path(affected).deepCopy();
        var actualSection = recovered.path(affected).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) expectedSection).remove("updatedAt");
        ((com.fasterxml.jackson.databind.node.ObjectNode) actualSection).remove("updatedAt");
        assertThat(actualSection).isEqualTo(expectedSection);
        assertThat(recovered.path(unaffected)).isEqualTo(baseline.path(unaffected));
        assertThat(Instant.parse(recovered.path(affected).path("updatedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(baseline.path(affected).path("updatedAt").asText()));
        assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
    }
    private static JsonNode snapshot() {
        var response = RestApi.call("GET", "/config/tax", null, null);
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        return response.json();
    }
}
