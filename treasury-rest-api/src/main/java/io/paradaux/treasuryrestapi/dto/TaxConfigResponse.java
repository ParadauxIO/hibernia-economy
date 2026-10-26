package io.paradaux.treasuryrestapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * The tax rates the Treasury plugin is applying, as it last published them.
 * A section is {@code null} until the plugin has published it. Rates are
 * fractions as decimal strings ({@code "0.012"} = 1.2 %).
 */
public record TaxConfigResponse(BalanceTax balanceTax, SourceIncomeTax sourceIncomeTax) {

    /**
     * Weekly tax on personal balances, prorated per login. The whole balance is
     * taxed at the rate of the highest bracket whose floor it reaches (not marginal).
     */
    public record BalanceTax(boolean enabled, List<Bracket> brackets, Instant updatedAt) {}

    public record Bracket(String floor, String weeklyRate) {}

    /** Tax taken from income as it is paid, per paying plugin, else the default rate. */
    public record SourceIncomeTax(boolean enabled, String defaultRate, List<PluginRate> pluginRates,
                                  Instant updatedAt) {}

    public record PluginRate(String plugin, String rate) {}
}
