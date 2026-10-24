package io.paradaux.treasury.services;

import io.paradaux.treasury.model.tax.TaxCycleReport;
import io.paradaux.treasury.model.tax.TaxCycleType;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Owns mutable state related to tax cycles:
 * <ul>
 *   <li>The next-fire timestamp per cycle type, written by {@code TaxCycleTask}.</li>
 *   <li>The set of plugins that have called {@code TaxApi#registerCycleParticipant}.</li>
 *   <li>The active accumulator session, used to build a {@link TaxCycleReport} after each cycle.</li>
 * </ul>
 *
 * <p>Split out of {@code TaxApiImpl} so that the tax-collection logic stays
 * focused on collection. {@code TaxCycleTask} and {@code TaxCommand} inject
 * this registry directly instead of casting the {@link io.paradaux.treasury.api.TaxApi}
 * to its implementation type.
 */
public interface TaxCycleRegistry {

    // ---- Schedule introspection ----

    Optional<Instant> getNextFireTime(TaxCycleType cycleType);

    void notifyNextFire(TaxCycleType cycleType, Instant when);

    // ---- Participant registration ----

    void registerCycleParticipant(String pluginName, TaxCycleType... cycleTypes);

    Set<String> getCycleParticipants(TaxCycleType cycleType);

    // ---- Cycle session ----

    void startSession(TaxCycleType cycleType, Instant periodStart,
                      boolean manual, @Nullable String triggeredBy);

    void recordCollected(int destinationAccountId, String taxType, BigDecimal amount);

    void recordSkipped();

    void recordFailed();

    boolean hasActiveSession();

    @Nullable TaxCycleReport endSession();
}
