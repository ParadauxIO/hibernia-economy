package io.paradaux.treasury.model.tax;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Describes a single tax to be collected via {@link io.paradaux.treasury.api.TaxApi#collectTax}.
 *
 * <p>Use the static factory methods rather than the canonical constructor directly:
 * <ul>
 *   <li>{@link #toDefaultAccount} — routes proceeds to Treasury's configured tax account (recommended).</li>
 *   <li>{@link #toAccount} — routes proceeds to a specific account by ID.</li>
 * </ul>
 *
 * <p><b>Dedup keys:</b> Provide a dedup key (e.g. {@code Idempotency.sha256("my-tax:" + uuid + ":" + date)})
 * to guarantee at-most-once semantics. A second call with the same key will return a
 * {@link TaxResult.Skipped} instead of charging the account again.
 *
 * @param sourceAccountId       Account that pays the tax.
 * @param destinationAccountId  Account that receives the tax, or {@code null} to use Treasury's default.
 * @param amount                Exact amount to charge (must be &gt; 0.00).
 * @param taxType               Machine-readable identifier for this tax regime, e.g. {@code "property-tax"}.
 * @param description           Human-readable ledger message, e.g. {@code "Weekly Property Tax: my_plot"}.
 * @param initiator             UUID of the entity that triggered the collection.
 *                              Use {@code TreasuryConstants.VIRTUAL_TREASURY_INITIATOR} for scheduled taxes.
 * @param pluginSystem          Optional plugin attribution shown in the ledger, e.g. {@code "realty"}.
 * @param dedupKey              Optional SHA-256 idempotency key (32 bytes). {@code null} disables dedup.
 */
public record TaxCollection(
        int sourceAccountId,
        @Nullable Integer destinationAccountId,
        BigDecimal amount,
        String taxType,
        String description,
        UUID initiator,
        @Nullable String pluginSystem,
        byte @Nullable [] dedupKey
) {

    public TaxCollection {
        // Validate the money-bearing fields and defensively copy the mutable
        // dedup-key array so the idempotency key can't be mutated post-construction (ADT-40).
        java.util.Objects.requireNonNull(amount, "amount");
        java.util.Objects.requireNonNull(taxType, "taxType");
        java.util.Objects.requireNonNull(description, "description");
        java.util.Objects.requireNonNull(initiator, "initiator");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0, was " + amount);
        }
        dedupKey = dedupKey == null ? null : dedupKey.clone();
    }

    /** Defensive copy so the returned idempotency key can't mutate the record's. */
    @Override
    public byte @Nullable [] dedupKey() {
        return dedupKey == null ? null : dedupKey.clone();
    }

    // ---- Factory methods ----

    /**
     * Collect {@code amount} from {@code sourceAccountId} into Treasury's default tax account.
     * No idempotency key — suitable when the caller guarantees single-delivery (e.g. event-driven).
     */
    public static TaxCollection toDefaultAccount(
            int sourceAccountId,
            BigDecimal amount,
            String taxType,
            String description,
            UUID initiator,
            @Nullable String pluginSystem) {
        return new TaxCollection(sourceAccountId, null, amount, taxType, description, initiator, pluginSystem, null);
    }

    /**
     * Collect {@code amount} from {@code sourceAccountId} into Treasury's default tax account,
     * with an idempotency key to prevent double-charging.
     */
    public static TaxCollection toDefaultAccount(
            int sourceAccountId,
            BigDecimal amount,
            String taxType,
            String description,
            UUID initiator,
            @Nullable String pluginSystem,
            byte @Nullable [] dedupKey) {
        return new TaxCollection(sourceAccountId, null, amount, taxType, description, initiator, pluginSystem, dedupKey);
    }

    /**
     * Collect {@code amount} from {@code sourceAccountId} into a specific destination account.
     * No idempotency key.
     */
    public static TaxCollection toAccount(
            int sourceAccountId,
            int destinationAccountId,
            BigDecimal amount,
            String taxType,
            String description,
            UUID initiator,
            @Nullable String pluginSystem) {
        return new TaxCollection(sourceAccountId, destinationAccountId, amount, taxType, description, initiator, pluginSystem, null);
    }

    /**
     * Collect {@code amount} from {@code sourceAccountId} into a specific destination account,
     * with an idempotency key.
     */
    public static TaxCollection toAccount(
            int sourceAccountId,
            int destinationAccountId,
            BigDecimal amount,
            String taxType,
            String description,
            UUID initiator,
            @Nullable String pluginSystem,
            byte @Nullable [] dedupKey) {
        return new TaxCollection(sourceAccountId, destinationAccountId, amount, taxType, description, initiator, pluginSystem, dedupKey);
    }
}
