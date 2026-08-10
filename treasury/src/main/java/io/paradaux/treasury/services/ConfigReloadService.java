package io.paradaux.treasury.services;

import io.paradaux.hibernia.framework.i18n.Message;

/**
 * Refreshes the runtime configuration without a full plugin restart, for the
 * {@code /treasury reload} command.
 *
 * <p>What it reloads:
 * <ul>
 *   <li>{@code messages.properties} via {@link Message#reload()};</li>
 *   <li>the annotation-driven {@code @ConfigurationComponent} POJOs (Economy,
 *       Government, Logging, TaxCycle, Bytebin, DiscordWebhook, Database) — the
 *       same singleton instances services hold are re-populated in place by
 *       re-running the framework {@code ConfigurationProcessor} over them;</li>
 *   <li>the manually-parsed config POJOs (salaries, balance tax, source-income
 *       tax) via their own {@code reload()};</li>
 *   <li>the log level (re-applied via {@code LoggingConfigurer}).</li>
 * </ul>
 *
 * <p><b>Not</b> reloaded (constructed once per JVM — need a restart): the DB pool,
 * the Guice injector, the {@code account}-formatter pattern cached in
 * AccountService, and the salary/tax-cycle <em>schedule intervals</em> (the timers
 * are fixed when scheduled at enable; amounts/rates/enabled flags do update live).
 */
public interface ConfigReloadService {

    /** Re-reads config.yml + messages.properties and refreshes the live config objects. */
    void reloadAll();
}
