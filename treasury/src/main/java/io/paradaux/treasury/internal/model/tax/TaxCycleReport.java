package io.paradaux.treasury.internal.model.tax;

import io.paradaux.treasury.model.tax.TaxCycleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable summary of a completed tax cycle run, used for webhook notifications and monitoring.
 *
 * @param cycleType             The cycle type that fired (DAILY, WEEKLY, MONTHLY).
 * @param periodStart           The canonical period start instant.
 * @param manual                {@code true} if triggered manually via {@code /tax trigger}.
 * @param triggeredBy           Name of the player who triggered it, or {@code null} for scheduled runs.
 * @param totalCollected        Sum of all successfully collected amounts.
 * @param byDestinationAccount  Map of account display name → total amount received.
 * @param byTaxType             Map of tax type identifier → total amount collected.
 * @param collectedCount        Number of individual charges that succeeded.
 * @param skippedCount          Number of charges that were intentionally skipped.
 * @param failedCount           Number of charges that failed.
 */
public record TaxCycleReport(
        TaxCycleType cycleType,
        Instant periodStart,
        boolean manual,
        @Nullable String triggeredBy,
        BigDecimal totalCollected,
        Map<String, BigDecimal> byDestinationAccount,
        Map<String, BigDecimal> byTaxType,
        int collectedCount,
        int skippedCount,
        int failedCount) {}
