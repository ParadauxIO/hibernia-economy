package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Top-level money supply broken down by account type — the in-game counterpart
 * to the explorer's total-supply view. Mirrors that view's scope: active
 * (non-archived) PERSONAL / BUSINESS / GOVERNMENT balances only, SYSTEM excluded.
 */
@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
public class EconomySummary {
    private BigDecimal personal;
    private BigDecimal business;
    private BigDecimal government;

    /** Grand total across the three real account types. */
    public BigDecimal getTotal() {
        return nz(personal).add(nz(business)).add(nz(government));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
