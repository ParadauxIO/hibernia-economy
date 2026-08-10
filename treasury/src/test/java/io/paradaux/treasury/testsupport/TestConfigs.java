package io.paradaux.treasury.testsupport;

import io.paradaux.treasury.model.config.BytebinConfiguration;
import io.paradaux.treasury.model.config.DiscordWebhookConfiguration;
import io.paradaux.treasury.model.config.FineWebhookConfiguration;
import io.paradaux.treasury.model.config.EconomyConfiguration;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.model.config.TaxCycleConfiguration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Reflection-based factories for {@code @ConfigurationComponent} POJOs whose
 * production constructors require a Bukkit plugin. Test code calls these to
 * build configuration instances directly without booting a server.
 */
public final class TestConfigs {

    private TestConfigs() {}

    public static EconomyConfiguration economy() {
        return economy(10_000.0);
    }

    public static EconomyConfiguration economy(double startingBalance) {
        EconomyConfiguration cfg = newInstance(EconomyConfiguration.class);
        setField(cfg, "economyFormat", "$#,##0.00");
        setField(cfg, "currencyNameSingular", "Dollar");
        setField(cfg, "currencyNamePlural", "Dollars");
        setField(cfg, "startingBalance", java.math.BigDecimal.valueOf(startingBalance));
        return cfg;
    }

    public static GovernmentConfiguration government() {
        return government("starting-balances", "DCGovernment", "GovernmentFines");
    }

    public static GovernmentConfiguration government(String startingBalances, String taxIncome, String fines) {
        GovernmentConfiguration cfg = newInstance(GovernmentConfiguration.class);
        setField(cfg, "startingBalancesAccount", startingBalances);
        setField(cfg, "taxIncomeAccount", taxIncome);
        setField(cfg, "finesAccount", fines);
        return cfg;
    }

    public static BytebinConfiguration bytebin(String postUrl, String baseUrl) {
        BytebinConfiguration cfg = newInstance(BytebinConfiguration.class);
        setField(cfg, "postUrl", postUrl);
        setField(cfg, "baseUrl", baseUrl);
        return cfg;
    }

    public static DiscordWebhookConfiguration discordWebhook(boolean enabled, String url) {
        DiscordWebhookConfiguration cfg = newInstance(DiscordWebhookConfiguration.class);
        setField(cfg, "enabled", enabled);
        setField(cfg, "url", url);
        return cfg;
    }

    public static FineWebhookConfiguration fineWebhook(boolean enabled, String url) {
        FineWebhookConfiguration cfg = newInstance(FineWebhookConfiguration.class);
        setField(cfg, "enabled", enabled);
        setField(cfg, "url", url);
        return cfg;
    }

    public static TaxCycleConfiguration taxCyclesAllDisabled() {
        TaxCycleConfiguration cfg = newInstance(TaxCycleConfiguration.class);
        setField(cfg, "dailyEnabled", false);
        setField(cfg, "weeklyEnabled", false);
        setField(cfg, "monthlyEnabled", false);
        setField(cfg, "dailyHour", 3);
        setField(cfg, "weeklyHour", 3);
        setField(cfg, "weeklyDayOfWeek", 1);
        setField(cfg, "monthlyHour", 3);
        setField(cfg, "monthlyDayOfMonth", 1);
        return cfg;
    }

    // ---- Reflection helpers ----

    private static <T> T newInstance(Class<T> clazz) {
        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct " + clazz.getSimpleName(), e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to set field " + fieldName + " on " + target.getClass().getSimpleName(), e);
        }
    }
}
