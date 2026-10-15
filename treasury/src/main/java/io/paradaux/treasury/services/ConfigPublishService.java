package io.paradaux.treasury.services;

/**
 * Publishes the tax tunables Treasury is applying into the shared
 * {@code economy_config} table, so the explorer docs and the REST API display the
 * live rates rather than hand-copied ones (PAR-80). Runs at enable and after
 * {@code /treasury reload}.
 *
 * <p>Published keys, all owned by {@code treasury}:
 * <ul>
 *   <li>{@code tax.balance.enabled} — BOOLEAN;</li>
 *   <li>{@code tax.balance.brackets} — JSON array of {@code {"floor","rate"}},
 *       ascending, after the fallback to the default brackets;</li>
 *   <li>{@code tax.source-income-tax.enabled} — BOOLEAN;</li>
 *   <li>{@code tax.source-income-tax.default-rate} — DECIMAL;</li>
 *   <li>{@code tax.source-income-tax.plugin-rates} — JSON object of plugin name
 *       to rate, sorted by name.</li>
 * </ul>
 * Values are the parsed, effective ones (an invalid entry the plugin skipped is
 * not published), and decimals keep their configured scale.
 */
public interface ConfigPublishService {

    /**
     * Upserts every published key and drops any {@code treasury}-owned key no
     * longer published, in one transaction.
     *
     * @throws RuntimeException if the database write fails; callers log and carry
     *                          on, since the snapshot is informational only
     */
    void publish();
}
